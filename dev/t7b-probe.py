#!/usr/bin/env python3
#
# Skald - Copyright (C) 2026 Gard Kroken
# SPDX-License-Identifier: Apache-2.0
#

"""T7b's probe (WORKPLAN-BUNDLES.md T7b step 2, point 6): shared Linux R binaries end to end,
against the pinned forge with authentication on.

  infrastructure  an outer network; the probe's CRAN-like upstream (dev/t7b-upstream.py) on it
                  as `cranup`, serving CRAN plus the probe's own packages; forge on it as
                  `forge` (:80, for the worker's gateway) and on 127.0.0.1:FORGE_PORT (for
                  Skald's trusted side), started with -auth and a config file: cran-hosted,
                  cran-proxy (upstream cranup, caching for one second so a republish shows),
                  cran-linux (noOverwrite, sourceRepo cran-proxy: checked against the public
                  source, served through the group since forge c148f09) and the
                  cran-public group of the three, all anonymous-read as a worker's restore
                  needs; the htpasswd registry and the R base published to it
  platform        forge's registration for the base, made by forge's own
                  scripts/cran-linux-manifest.sh, whose builderDigest must equal the base's
                  image ID as publish-bases.py records it (forge-issues/006); a token that reads and
                  writes only bin/linux/<key>/** in cran-linux (and reads registrations)
  packages        dev/fixtures/t7b/packages built with R CMD build in the R base's upstream
                  image: public ones in the upstream's contrib (skald.old 1.0 in its archive,
                  1.1 current), skald.changes' republished bytes beside them for the Java half
                  to swap in, and two in cran-hosted only: skald.private, and a hosted
                  skald.shadowed that shadows the public one in the group
  java            T7bProbeTest: the rows A-E (see its javadoc), results.json
  judged          A and B built; every public package uploaded once, from the public source
                  (skald.shadowed's recorded source digest is the PUBLIC tarball's); the
                  chain uploaded in order; skald.old 1.0 built from the archive; skald.private
                  deferred, never uploaded; skald.changes, republished upstream after forge
                  cached it, built from the bytes forge serves (forge pins a proxied tarball
                  and its index entry: forge-issues/008); every uploaded binary served as a
                  binary to an R client of the
                  platform; C's restore against A's; D back on source with the
                  reason; E refused with no row; skald.netprobe's binary, read from forge, says
                  its install saw only lo, reached nothing and found no token; the host clean

The probe's containers, network and volumes are named skald-t7b-* and removed at the end.
Tokens and the registry password are passed by environment, never on a command line, and
never printed.

  mutants         with --mutants: the isolation, source and token rows under the defects they
                  exist to catch. Each mutant edits the source (an anchor that must match exactly
                  once), runs the short mode (T7bProbeTest with skald.t7b.mode=mutant:
                  skald.netprobe and skald.shadowed queued as an R build's success queues
                  them, and built) against a fresh
                  forge, restores the file (sha256 checked) and clears target/classes; it is
                  killed when every row it targets is red. "control" is the unmutated short run:
                  every row green.

Usage: python3 dev/t7b-probe.py [--keep] [--mutants all|NAME,...]
"""

import importlib.util
import io
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
PREFIX = "skald-t7b"
OUTER = PREFIX + "-outer"
FORGE = PREFIX + "-forge"
FORGE_PORT = 18101
UPSTREAM = PREFIX + "-cranup"
REGISTRY = PREFIX + "-registry"
REGISTRY_PORT = 18102
MAVEN_NAME = PREFIX + "-maven"
OUT = REPO / "target/t7b"
DATA = OUT / "upstream"
PACKAGES = REPO / "dev/fixtures/t7b/packages"
R_BASE = "r-shiny-4.6.1-amd64-r1"
KEY = "noble-x86_64/4.6"
HOSTED_ONLY = ("skald.private",)


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, REPO / "dev" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


t7p = load("t7_pass_probe", "t7-pass-probe.py")
bases = t7p.bases
docker = t7p.docker
results = []


def record(name, ok, detail):
    results.append((name, ok, detail))
    print("  %s  %-28s %s" % ("ok  " if ok else "FAIL", name, detail), flush=True)


def teardown():
    for c in (FORGE, UPSTREAM, REGISTRY, MAVEN_NAME):
        docker(["rm", "-f", "-v", c])
    docker(["network", "rm", OUTER])


def catalog_entry(base_id):
    return next(e for e in json.loads(bases.CATALOG.read_bytes())["bases"] if e["id"] == base_id)


def forge_http(method, path, token, body=None, raw=False):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request("http://127.0.0.1:%d/%s" % (FORGE_PORT, path), data=data, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None and not isinstance(body, bytes):
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            out = r.read()
            return r.status, out if raw else out.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


# ---------------------------------------------------------------- packages

def build_packages():
    """R CMD build for every fixture, each into its own directory (two build the same file
    name), in the R base's upstream image, as the invoking user."""
    built = OUT / "built"
    built.mkdir(parents=True)
    script = ('set -e; for d in /src/*/; do n=$(basename "$d"); mkdir -p "/out/$n"; cd "/out/$n"; '
              'R CMD build --no-build-vignettes --no-manual "$d" >/dev/null; done')
    run = docker(["run", "--rm", "-u", "%d:%d" % (os.getuid(), os.getgid()), "-e", "HOME=/tmp",
                  "--network", "none", "-v", "%s:/src:ro" % PACKAGES, "-v", "%s:/out" % built,
                  catalog_entry(R_BASE)["upstream"], "bash", "-c", script], timeout=1800)
    if run.returncode != 0:
        raise SystemExit("R CMD build: " + (run.stdout + run.stderr).strip()[-500:])
    tarballs = {}
    for d in sorted(built.iterdir()):
        files = list(d.glob("*.tar.gz"))
        if len(files) != 1:
            raise SystemExit("R CMD build left %s in %s" % (files, d))
        tarballs[d.name] = files[0]
    (DATA / "contrib").mkdir(parents=True)
    (DATA / "republished").mkdir()
    (DATA / "archive/skald.old").mkdir(parents=True)
    hosted = []
    for name, tar in tarballs.items():
        if name.startswith("republished-"):
            shutil.copy(tar, DATA / "republished" / tar.name)
        elif name.startswith("hosted-") or name.split("-")[0] in HOSTED_ONLY:
            hosted.append(tar)
        elif name == "skald.old-1.0":
            shutil.copy(tar, DATA / "archive/skald.old" / tar.name)
        else:
            shutil.copy(tar, DATA / "contrib" / tar.name)
    return hosted


# ---------------------------------------------------------------- infrastructure

def forge_config():
    repos = [
        {"name": "cran-hosted", "format": "cran", "kind": "hosted", "anonymousRead": True, "enabled": True},
        {"name": "cran-proxy", "format": "cran", "kind": "proxy", "upstream": "http://cranup:8000",
         "anonymousRead": True, "enabled": True, "contentMaxAge": "1s", "metadataMaxAge": "1s"},
        {"name": "cran-linux", "format": "cran", "kind": "hosted", "anonymousRead": True, "enabled": True,
         # Checked against the public source only, and served by the group (forge-issues/005,
         # fixed in forge c148f09: a sourceRepo that is a member of the group).
         "noOverwrite": True, "sourceRepo": "cran-proxy"},
        {"name": "cran-public", "format": "cran", "kind": "group", "anonymousRead": True, "enabled": True,
         "members": ["cran-hosted", "cran-proxy", "cran-linux"]},
    ]
    return {"repositories": repos}


def start_upstream():
    python = catalog_entry("python-shiny-3.13.16-amd64-r1")["upstream"]
    run = docker(["run", "-d", "--name", UPSTREAM, "--network", OUTER, "--network-alias", "cranup",
                  "-u", "%d:%d" % (os.getuid(), os.getgid()),
                  "-v", "%s:/data:ro" % DATA, "-v", "%s:/upstream.py:ro" % (REPO / "dev/t7b-upstream.py"),
                  python, "python3", "/upstream.py", "/data", "8000"], timeout=900)
    if run.returncode != 0:
        raise SystemExit("upstream: " + run.stderr.strip()[-300:])
    probe = ("import urllib.request as u; r = u.urlopen('http://127.0.0.1:8000/src/contrib/PACKAGES', timeout=120);"
             " b = r.read().decode(); assert 'Package: skald.chaina' in b, 'no probe packages'")
    for _ in range(60):
        if docker(["exec", UPSTREAM, "python3", "-c", probe], timeout=180).returncode == 0:
            return
        time.sleep(2)
    raise SystemExit("upstream did not serve its index: " + docker(["logs", UPSTREAM]).stderr[-500:])


def start_forge(tmp):
    config = pathlib.Path(tmp) / "forge.config.json"
    config.write_text(json.dumps(forge_config(), indent=2))
    config.chmod(0o644)
    run = docker(["run", "-d", "--name", FORGE, "--network", OUTER, "--network-alias", "forge",
                  "-p", "127.0.0.1:%d:80" % FORGE_PORT,
                  "--read-only", "--tmpfs", "/data:uid=65532,gid=65532",
                  "-v", "%s:/etc/forge/config.json:ro" % config, bases.FORGE_IMAGE,
                  "-addr", ":80", "-data", "/data", "-auth", "-config", "/etc/forge/config.json"])
    if run.returncode != 0:
        raise SystemExit("forge: " + run.stderr.strip()[-300:])
    for _ in range(30):
        if docker(["exec", FORGE, "/forge", "-healthcheck", "-addr", ":80"]).returncode == 0:
            break
        time.sleep(1)
    else:
        raise SystemExit("forge did not become healthy")
    logs = docker(["logs", FORGE])
    # forge logs JSON: {"msg":"auth enabled: bootstrap admin token created",...,"secret":"forge_..."}
    m = re.search(r'bootstrap admin token created".*?"secret":"(forge_[0-9a-f]{64})"', logs.stdout + logs.stderr)
    if not m:
        raise SystemExit("no bootstrap admin token in forge's log")
    return m.group(1)


def start_registry(password, tmp):
    auth = pathlib.Path(tmp) / "auth"
    auth.mkdir()
    (auth / "htpasswd").write_text(t7p.htpasswd(password))
    run = docker(["create", "--name", REGISTRY, "--network", OUTER,
                  "-e", "REGISTRY_AUTH=htpasswd", "-e", "REGISTRY_AUTH_HTPASSWD_REALM=skald",
                  "-e", "REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd",
                  "-p", "127.0.0.1:%d:5000" % REGISTRY_PORT, t7p.REGISTRY_IMAGE])
    if run.returncode != 0:
        raise SystemExit("registry: " + run.stderr.strip()[-300:])
    docker(["cp", str(auth), REGISTRY + ":/"])
    docker(["start", REGISTRY])


def register_platform(admin, published, env):
    """forge's own manifest script on the published base. Its builderDigest must be the image ID
    publish-bases.py read from the registry (forge-issues/006, fixed: both are the config
    digest), or Skald's gate and forge's upload check would name different images."""
    pub = json.loads(published.read_text())
    reference = pub["bases"][R_BASE]
    local = "localhost:%d/%s" % (REGISTRY_PORT, reference.split("/", 1)[1])
    if docker(["pull", "-q", local], timeout=1800, env=env).returncode != 0:
        raise SystemExit("could not pull " + local)
    script = REPO.parent / "forge/scripts/cran-linux-manifest.sh"
    man = subprocess.run(["bash", str(script), local], capture_output=True, text=True, timeout=600, env=env)
    if man.returncode != 0:
        raise SystemExit("cran-linux-manifest.sh: " + man.stderr.strip()[-300:])
    platform = json.loads(man.stdout)
    record("builder digest", platform["builderDigest"] == pub["image_ids"][R_BASE],
           "forge's script %s, publish-bases %s" % (platform["builderDigest"][:19], pub["image_ids"][R_BASE][:19]))
    platform["builderImage"] = reference
    status, body = forge_http("PUT", "api/v1/cran/platforms/" + platform["key"], admin, platform)
    record("platform registered", status // 100 == 2 and platform["key"] == KEY,
           "%s %s, builderDigest %s, %d libraries" % (platform["key"], status, platform["builderDigest"][:19],
                                                      len(platform["libraries"])))
    if status // 100 != 2:
        raise SystemExit("platform PUT: %s %s" % (status, body[:300]))
    return platform


def mint_token(admin):
    status, body = forge_http("POST", "api/v1/tokens", admin, {
        "description": "skald t7b probe: " + KEY,
        "grants": [{"repo": "cran-linux", "actions": ["read", "write"],
                    "selectors": ["bin/linux/%s/contrib/%s/**" % tuple(KEY.split("/"))]}]})
    if status // 100 != 2:
        raise SystemExit("token: %s %s" % (status, body[:300]))
    return json.loads(body)["secret"]


def upload_hosted(admin, hosted):
    for tar in hosted:
        status, body = forge_http("PUT", "repository/cran-hosted/src/contrib/" + tar.name, admin, tar.read_bytes())
        record("hosted " + tar.name, status // 100 == 2, "%s %s" % (status, body[:120]))


# ---------------------------------------------------------------- the Java half

def maven(password, published, token, admin, extra_props=None):
    gid = str(os.stat("/var/run/docker.sock").st_gid)
    home = pathlib.Path.home()
    m2, m2home = home / ".cache/skald/m2", home / ".cache/skald/m2home"
    m2.mkdir(parents=True, exist_ok=True)
    m2home.mkdir(parents=True, exist_ok=True)
    props = {
        "skald.t7b.forge-port": str(FORGE_PORT),
        "skald.t7b.registry": REGISTRY, "skald.t7b.registry-port": str(REGISTRY_PORT),
        "skald.t7b.username": t7p.USERNAME, "skald.t7b.outer": OUTER,
        "skald.t7b.published-bases": "/ws/" + str(published.relative_to(REPO)),
        "skald.t7b.out": "/ws/" + str(OUT.relative_to(REPO)),
        "skald.t7b.data": "/ws/" + str(DATA.relative_to(REPO)),
    }
    props.update(extra_props or {})
    secret_env = {"SKALD_T7B_PASSWORD": password, "SKALD_T7B_FORGE_TOKEN": token, "SKALD_T7B_FORGE_ADMIN": admin}
    cmd = ["docker", "run", "--rm", "--name", MAVEN_NAME, "--network", "host", "--group-add", gid]
    for name in secret_env:
        cmd += ["-e", name]
    cmd += ["-v", "/var/run/docker.sock:/var/run/docker.sock",
            "-u", "%d:%d" % (os.getuid(), os.getgid()), "-e", "HOME=/m2home",
            "-v", "%s:/ws" % REPO, "-v", "%s:/m2" % m2, "-v", "%s:/m2home" % m2home,
            "-w", "/ws", t7p.MAVEN_IMAGE, "mvn", "-B", "-Dmaven.repo.local=/m2", "-Dlicense.skip=true",
            "test", "-Dtest=T7bProbeTest", "-Dsurefire.failIfNoSpecifiedTests=false"]
    cmd += ["-D%s=%s" % kv for kv in props.items()]
    log = OUT / "maven.log"
    with open(log, "w") as f:
        done = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, timeout=8 * 3600,
                              env=dict(os.environ, **secret_env))
    tail = [l for l in log.read_text().splitlines() if "Tests run:" in l or "FAIL" in l]
    return done.returncode, (tail[-1] if tail else "no result; see " + str(log))


# ---------------------------------------------------------------- judging

def sha256(path):
    import hashlib
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def binary(admin, package, version):
    """The binary forge serves for this key, read from cran-linux with the admin token."""
    codename_arch, rminor = KEY.split("/")
    status, body = forge_http("GET", "repository/cran-linux/bin/linux/%s/contrib/%s/%s_%s.tar.gz" % (
        codename_arch, rminor, package, version), admin, raw=True)
    return body if status == 200 else None


def sandbox_rows(admin):
    """skald.netprobe's install, read back from its binary in forge (its configure)."""
    body = binary(admin, "skald.netprobe", "1.0")
    probe = {}
    if body:
        with tarfile.open(fileobj=io.BytesIO(body), mode="r:gz") as t:
            member = t.extractfile("skald.netprobe/probe.txt")
            for line in member.read().decode().splitlines():
                k, _, v = line.partition("=")
                probe[k] = v.strip()
    seen = ", ".join("%s=%s" % kv for kv in probe.items()) or "no probe.txt in the binary"
    record("sandbox: network", probe.get("interfaces") == "lo" and probe.get("net") == "unreachable",
           "interfaces=%s, net=%s" % (probe.get("interfaces"), probe.get("net")))
    record("sandbox: forge token", probe.get("token_env") == "0" and probe.get("token_files") == "0",
           "token_env=%s, token_files=%s" % (probe.get("token_env"), probe.get("token_files")))
    record("sandbox: registry credential", probe.get("registry_auth_files") == "0",
           "registry_auth_files=%s" % probe.get("registry_auth_files"))
    record("sandbox: user", probe.get("uid") not in ("", "0", None), seen if not probe else "uid=%s" % probe.get("uid"))


def shadowed_row(drained):
    shadowed = drained.get("skald.shadowed", {})
    public = sha256(DATA / "contrib/skald.shadowed_1.0.tar.gz")
    hosted = sha256(OUT / "built/hosted-skald.shadowed-1.0/skald.shadowed_1.0.tar.gz")
    recorded = shadowed.get("source_sha256") or ""
    record("shadowed: public source", shadowed.get("state") == "UPLOADED" and recorded == public,
           "%s, recorded %s, public %s%s" % (shadowed.get("state"), recorded[:12], public[:12],
                                             " -- the HOSTED tarball's" if recorded == hosted else ""))


def judge_mutant(r, admin):
    shadowed_row({x["package"]: x for x in r.get("rows_after_drain", [])})
    sandbox_rows(admin)


def judge(r, admin):
    builds = r.get("builds", {})
    for name in ("A", "B", "C"):
        b = builds.get(name, {})
        record("build " + name, b.get("state") == "PUBLISHING",
               "%s %s, %ss, restore %ss" % (b.get("state"), b.get("error_code") or "", str(b.get("seconds"))[:6],
                                           b.get("restore_seconds")))
    rows = {x["package"]: x for x in r.get("rows_final", [])}
    drained = {x["package"]: x for x in r.get("rows_after_drain", [])}
    uploaded = sorted(p for p, x in drained.items() if x["state"] == "UPLOADED")
    others = {p: "%s %s%s" % (x["state"], x.get("refusal_code") or x.get("error_code") or "",
                              " (deferred %s)" % x["deferrals"] if x.get("deferred") == "true" else "")
              for p, x in drained.items() if x["state"] != "UPLOADED"}
    expected_others = {"skald.private"}
    record("queue drained", set(others) == expected_others,
           "%d uploaded, %.0fs; not uploaded: %s" % (len(uploaded), r.get("drain_seconds", -1),
                                                    "; ".join("%s %s" % kv for kv in sorted(others.items()))))
    record("chain", all(drained.get(p, {}).get("state") == "UPLOADED"
                        for p in ("skald.chaina", "skald.chainb", "skald.chainc")),
           ", ".join("%s %s" % (p, drained.get(p, {}).get("state")) for p in ("skald.chainc", "skald.chainb",
                                                                              "skald.chaina")))
    old = drained.get("skald.old", {})
    record("archived version", old.get("state") == "UPLOADED" and old.get("source_kind") == "archive"
           and old.get("version") == "1.0", "skald.old %s %s from %s" % (old.get("version"), old.get("state"),
                                                                         old.get("source_kind")))
    shadowed_row(drained)
    private = drained.get("skald.private", {})
    record("hosted-only package", private.get("state") in ("PENDING_SOURCE", "REFUSED")
           and private.get("binary_sha256") in (None, "None") and binary(admin, "skald.private", "1.0") is None,
           "skald.private %s %s, deferrals %s, no binary in forge" % (
               private.get("state"), private.get("refusal_code") or "", private.get("deferrals")))
    # forge's proxy pins the tarball it cached first (forge-issues/008), so the republished bytes
    # never reach Skald: the binary must be of what forge serves, never of the new bytes.
    # HANDIN_DIGEST and SOURCE_CHANGED stay covered by SourceVerifierTest/PackageSandboxTest.
    changes = drained.get("skald.changes", {})
    status, served = forge_http("GET", "repository/cran-proxy/src/contrib/skald.changes_1.0.tar.gz", None, raw=True)
    served_sha = __import__("hashlib").sha256(served).hexdigest() if status == 200 else None
    republished = sha256(DATA / "republished/skald.changes_1.0.tar.gz")
    record("republished upstream", changes.get("state") == "UPLOADED" and changes.get("source_sha256") == served_sha
           and served_sha != republished,
           "swapped while %s; %s from %s, forge serves %s, upstream now %s" % (
               r.get("changes_state_before_republish"), changes.get("state"), (changes.get("source_sha256") or "")[:12],
               (served_sha or "-")[:12], republished[:12]))
    a, c = builds.get("A", {}).get("restore_seconds", -1), builds.get("C", {}).get("restore_seconds", -1)
    record("restore with binaries", 0 < c < a, "A (cold, source) %.0fs; C (binaries) %.0fs; %.1fx" % (
        a, c, a / c if c > 0 else 0))
    d = builds.get("D", {})
    deps = ((d.get("provenance") or {}).get("dependencies") or {})
    record("registration changed", d.get("state") == "PUBLISHING"
           and (deps.get("mirrors") or {}).get("cran_linux_binaries") is False
           and "cran_linux_binaries_reason" in deps and r.get("rows_added_by_D") == 0,
           "%s; binaries %s: %s; rows added %s" % (d.get("state"), (deps.get("mirrors") or {}).get("cran_linux_binaries"),
                                                   str(deps.get("cran_linux_binaries_reason"))[:100],
                                                   r.get("rows_added_by_D")))
    e = builds.get("E", {})
    record("remote package", e.get("state") not in (None, "PUBLISHING") and r.get("remote_rows") == 0,
           "%s %s: %s; rows %s" % (e.get("state"), e.get("error_code"), (e.get("error_detail") or "")[:100],
                                   r.get("remote_rows")))
    # Every row uploaded once, its binary in forge with the recorded digest.
    mismatched = []
    for p, x in rows.items():
        if x["state"] == "UPLOADED":
            body = binary(admin, p, x["version"])
            if body is None or __import__("hashlib").sha256(body).hexdigest() != x["binary_sha256"]:
                mismatched.append(p)
    record("binaries in forge", not mismatched and uploaded,
           "%d uploaded rows, each served with its recorded digest%s" % (
               len([x for x in rows.values() if x["state"] == "UPLOADED"]),
               "" if not mismatched else "; mismatched: " + ", ".join(mismatched)))
    # What an R client of the platform gets through the group's __linux__ URL, as C's restore.
    # skald.shadowed must be SOURCE: the group serves cran-hosted's package under that name, and
    # forge serves a binary only while the group serves the source it was checked against.
    # GET, headers only: forge answers HEAD 404 for a hosted tarball (forge-issues/009).
    ua = "R (4.6.1 x86_64-pc-linux-gnu x86_64 linux-gnu)"
    shadowed_as = None
    as_source = []
    for p, x in rows.items():
        if x["state"] == "UPLOADED":
            # An archived version is where renv asks for it: src/contrib/Archive/<pkg>/.
            sub = ("Archive/%s/" % p if x.get("source_kind") == "archive" else "") + "%s_%s.tar.gz" % (p, x["version"])
            req = urllib.request.Request("http://127.0.0.1:%d/repository/cran-public/__linux__/%s/latest/src/contrib/%s"
                                         % (FORGE_PORT, KEY.split("-")[0], sub),
                                         headers={"User-Agent": ua})
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    kind = resp.headers.get("X-Forge-Package-Type")
                    if p == "skald.shadowed":
                        shadowed_as = kind
                    elif kind != "binary":
                        as_source.append(p)
            except urllib.error.HTTPError as e:
                as_source.append("%s (%d)" % (p, e.code))
    record("served as binaries", not as_source and shadowed_as == "source",
           "every uploaded row a binary to %s, the shadowed one source (%s)%s" % (
               ua, shadowed_as, "" if not as_source else "; not binary: " + ", ".join(as_source)))
    sandbox_rows(admin)
    versions = {}
    for x in rows.values():
        versions.setdefault(x["package"], set()).add(x["version"])
    print("  measured: %d rows; packages with more than one version: %d; hosted-only rows: %s" % (
        len(rows), sum(1 for v in versions.values() if len(v) > 1),
        [p for p, x in rows.items() if p in HOSTED_ONLY]))


def package_phases():
    """Where a package attempt's time went (T7b, the fixed cost): PackageBuildDriver logs one
    "package attempt <id> (<pkg> <ver>): phases: total T s: name s, ..." line per attempt. Prints
    each phase's median and maximum over the run's attempts; written to package-phases.json."""
    line = re.compile(r"package attempt (p[0-9a-f]+) \((\S+) (\S+)\): phases: total ([0-9.]+) s:(.*)$")
    attempts = []
    for l in (OUT / "maven.log").read_text(errors="replace").splitlines():
        m = line.search(l)
        if m:
            phases = {"total": float(m.group(4))}
            for part in m.group(5).split(","):
                kv = part.split()
                if len(kv) == 2:
                    phases[kv[0]] = float(kv[1])
            attempts.append({"attempt": m.group(1), "package": m.group(2), "version": m.group(3), "phases": phases})
    (OUT / "package-phases.json").write_text(json.dumps(attempts, indent=1))
    names = []
    for a in attempts:
        names += [n for n in a["phases"] if n not in names]
    print("  package attempts: %d (target/t7b/package-phases.json)" % len(attempts))
    for n in names:
        xs = sorted(a["phases"][n] for a in attempts if n in a["phases"])
        print("    %-16s median %6.1f s  max %6.1f s" % (n, xs[len(xs) // 2], xs[-1]))


RECIPE = REPO / "src/main/java/eu/openanalytics/shinyproxy/publisher/binaries/PackageRecipe.java"
CRANINDEX = RECIPE.parent / "CranIndex.java"
VERIFIER = RECIPE.parent / "SourceVerifier.java"
LEAK = 'System.getenv("SKALD_T7B_FORGE_TOKEN")'
SANDBOX_ROWS = {"sandbox: network", "sandbox: forge token", "sandbox: registry credential", "sandbox: user"}
# name -> (source edits, extra properties, rows that must go red[, rows it cannot judge])
MUTANTS = {
    "control": ([], {}, set()),
    # The install RUN with the worker's network (89a33dc N2's --network=none dropped).
    "network": ([(RECIPE, '"RUN --network=none [', '"RUN [')], {}, {"sandbox: network"}),
    # The forge token in the sandbox: as an environment variable, as a context file, and on a
    # separate mount only (a tmpfs written from a base64 form the search pattern does not
    # match), which a search confined to the root filesystem (-xdev) would miss (5b80946 N2).
    "token-env": ([(RECIPE, '+ "USER " + user + "\\n"\n',
                    '+ "USER " + user + "\\n" + "ENV SKALD_LEAK=" + ' + LEAK + ' + "\\n"\n')],
                  {}, {"sandbox: forge token"}),
    "token-file": ([(RECIPE, '        files.put("skald/build.sh",',
                     '        files.put("skald/leak", ' + LEAK + '.getBytes(StandardCharsets.US_ASCII));\n'
                     '        files.put("skald/build.sh",')], {}, {"sandbox: forge token"}),
    "token-mount": ([(RECIPE, '"RUN --network=none [', '"RUN --network=none --mount=type=tmpfs,target=/mnt/leak ['),
                     (RECIPE, '        return "w=" + WORK + "\\n"',
                      '        return "echo " + java.util.Base64.getEncoder().encodeToString(' + LEAK
                      + '.getBytes()) + " | base64 -d > /mnt/leak/t\\n" + "w=" + WORK + "\\n"')],
                    {}, {"sandbox: forge token"}),
    # A registry credential (a docker config) in the sandbox (5b80946 N3).
    "registry-auth": ([(RECIPE, '        files.put("skald/build.sh",',
                        '        files.put("skald/config.json", "{\\"auths\\": {\\"r\\": {\\"auth\\": \\"eA==\\"}}}"'
                        '.getBytes(StandardCharsets.US_ASCII));\n        files.put("skald/build.sh",')],
                      {}, {"sandbox: registry credential"}),
    # Sources read from the operator's hosted repository as if it were the public one: its
    # skald.shadowed must never be recorded as the public source. skald.netprobe is not hosted,
    # so the sandbox rows have nothing to read in these runs. forge's hosted index carries no
    # MD5sum and CranIndex trusts no record without one, so "source-hosted" is caught there
    # (the row defers); "source-unchecked" also removes that layer and the tarball's MD5 check,
    # so only the row itself is left to see the hosted bytes recorded.
    "source-hosted": ([], {"skald.t7b.source-repo": "cran-hosted"}, {"shadowed: public source"}, SANDBOX_ROWS),
    "source-unchecked": ([(CRANINDEX, 'String md5 = r.get("MD5sum");',
                           'String md5 = r.getOrDefault("MD5sum", "0".repeat(32));'),
                          (VERIFIER, 'if (entry != null && !read.md5().equals(entry.md5())) {',
                           'if (false && entry != null && !read.md5().equals(entry.md5())) {')],
                         {"skald.t7b.source-repo": "cran-hosted"}, {"shadowed: public source"}, SANDBOX_ROWS),
}


def mutate(edits):
    """Applies edits; returns the originals to restore. Each anchor must match exactly once."""
    originals = {}
    for path, anchor, replacement in edits:
        text = path.read_text()
        originals.setdefault(path, text)
        if text.count(anchor) != 1:
            restore(originals)
            raise SystemExit("mutant anchor matches %d times in %s: %r" % (text.count(anchor), path.name, anchor))
        path.write_text(text.replace(anchor, replacement))
    return originals


def restore(originals):
    import hashlib
    for path, text in originals.items():
        path.write_text(text)
        if hashlib.sha256(path.read_bytes()).hexdigest() != hashlib.sha256(text.encode()).hexdigest():
            raise SystemExit("could not restore " + str(path))
    for d in ("target/classes", "target/test-classes"):
        t7p.remove_tree(REPO / d)


def run_mutants(names, password, published, env, tmp, hosted):
    verdicts = []
    for name in names:
        edits, props, targets = MUTANTS[name][:3]
        unjudged = MUTANTS[name][3] if len(MUTANTS[name]) > 3 else set()
        print("== mutant %s ==" % name, flush=True)
        docker(["rm", "-f", "-v", FORGE])
        admin = start_forge(tmp)
        register_platform(admin, published, env)
        token = mint_token(admin)
        upload_hosted(admin, hosted)
        (OUT / "results.json").unlink(missing_ok=True)
        originals = mutate(edits)
        try:
            code, summary = maven(password, published, token, admin,
                                  dict(props, **{"skald.t7b.mode": "mutant"}))
        finally:
            restore(originals)
        before = len(results)
        record("java", code == 0, summary)
        report = OUT / "results.json"
        if report.exists():
            try:
                judge_mutant(json.loads(report.read_text()), admin)
            except Exception as e:
                record("judge", False, "%s: %s" % (type(e).__name__, e))
        rows = results[before:]
        red = {n for n, ok, _ in rows if not ok} - unjudged
        del results[before:]
        if name == "control":
            ok = not red
            detail = "every row green" if ok else "red: " + ", ".join(sorted(red))
        else:
            ok = targets <= red and not (red - targets - {"java"})
            detail = "red: %s (targets %s)" % (", ".join(sorted(red)) or "none", ", ".join(sorted(targets)))
        verdicts.append(("mutant " + name, ok, detail))
        record("mutant " + name, ok, detail)
    return verdicts


def main(argv):
    keep = "--keep" in argv
    password = secrets.token_hex(16)
    tmp = tempfile.mkdtemp(prefix="skald-t7b-")
    env = dict(os.environ, DOCKER_CONFIG=os.path.join(tmp, "docker"))
    t7p.remove_tree(OUT)
    OUT.mkdir(parents=True)
    try:
        print("== infrastructure ==")
        teardown()
        t7p.images()
        if docker(["network", "create", OUTER]).returncode != 0:
            raise SystemExit("could not create " + OUTER)
        hosted = build_packages()
        start_upstream()
        admin = start_forge(tmp)
        start_registry(password, tmp)
        login = subprocess.run(["docker", "login", "-u", t7p.USERNAME, "--password-stdin",
                                "localhost:%d" % REGISTRY_PORT], input=password, capture_output=True,
                               text=True, env=env, timeout=120)
        if login.returncode != 0:
            raise SystemExit("login: " + login.stderr.strip()[-300:])
        published = OUT / "published-bases.json"
        pub = subprocess.run([sys.executable, str(REPO / "dev/publish-bases.py"), "--push-to",
                              "localhost:%d" % REGISTRY_PORT, "--registry", REGISTRY + ":5000",
                              "--out", str(published), "--only", R_BASE], env=env, capture_output=True,
                             text=True, timeout=4 * 3600)
        record("base published", pub.returncode == 0, ((pub.stdout + pub.stderr).strip().splitlines() or ["-"])[-1])
        if pub.returncode != 0:
            print(pub.stdout[-3000:], pub.stderr[-1000:])
            return 1
        if "--mutants" in argv:
            wanted = argv[argv.index("--mutants") + 1]
            names = list(MUTANTS) if wanted == "all" else wanted.split(",")
            unknown = [n for n in names if n not in MUTANTS]
            if unknown:
                raise SystemExit("unknown mutants: %s" % unknown)
            run_mutants(names, password, published, env, tmp, hosted)
            t7p.host_clean()
            results.extend(t7p.results)
            return 1 if [x for x in results if not x[1]] else 0
        register_platform(admin, published, env)
        token = mint_token(admin)
        upload_hosted(admin, hosted)
        print("== the shared binaries (T7bProbeTest) ==")
        code, summary = maven(password, published, token, admin)
        record("java", code == 0, summary)
        report = OUT / "results.json"
        if report.exists():
            print("== judged ==")
            try:
                judge(json.loads(report.read_text()), admin)
            except Exception as e:  # a judging bug is a failed row, never a skipped host check
                record("judge", False, "%s: %s" % (type(e).__name__, e))
        package_phases()
        t7p.host_clean()
        results.extend(t7p.results)
    finally:
        if not keep:
            teardown()
        shutil.rmtree(tmp, ignore_errors=True)
    failed = [x for x in results if not x[1]]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
