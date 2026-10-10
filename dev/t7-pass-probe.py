#!/usr/bin/env python3
#
# Skald - Copyright (C) 2026 Gard Kroken
# SPDX-License-Identifier: Apache-2.0
#

"""T7's Pass probe (WORKPLAN-BUNDLES.md T7): real apps, built by the wired worker, pulled cold
and run.

  "Pass: actual built image with recorded digest/provenance, useful failed-build log,
   bounded teardown, and cold runtime pull from the chosen registry authority. No mocked
   'build succeeded' counts toward this task."

This script stands up what a deployment provides, runs the Java half
(T7PassProbeTest, through Maven in Docker as `make test` does), then checks every image
the Java half reports as built, from the registry, cold:

  infrastructure  an outer network; forge on it as `forge`, listening on :80 (the gateway
                  serves repositories on 80 and 443 only, and GatewayConfig's ports are not
                  widened for a probe); an htpasswd registry:2 on it, published on
                  127.0.0.1:REGISTRY_PORT; the trusted bases published to that registry by
                  dev/publish-bases.py, under the name the worker uses (<registry>:5000)
  java            T7PassProbeTest: the application's own configuration with a deployment's
                  properties; bundles stored as T8 will store them; results.json
  pulled          for each built image: no local copy of the repository exists, then
                  `docker pull localhost:PORT/<repository>@<digest>` succeeds (the daemon
                  allows plain http to localhost only; the digest is the one the worker's
                  reference names, so it is the same image)
  served/user/    the pulled image, run hardened (dev/recipes-probe.py's checks): GET / is a
  read-only       Shiny page, every process is uid 10001 (PID 1 included), /app is not
                  writable
  host            after everything, nothing labelled eu.skald.build-attempt or named
                  skald-b-*; no loop device backed by skald-quota-*; skald-quota-images empty

The probe's own containers, network and volumes are named skald-t7p-* and removed at the end.
Docker credentials for the probe registry live in a temporary DOCKER_CONFIG, never in the
user's.

Usage: python3 dev/t7-pass-probe.py [--keep] [--test METHOD]
  --keep   leave the infrastructure up afterwards (for debugging; removed by the next run)
  --test   run one T7PassProbeTest method instead of all
"""

import importlib.util
import json
import os
import pathlib
import secrets
import shutil
import subprocess
import sys
import tempfile
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
PREFIX = "skald-t7p"
OUTER = PREFIX + "-outer"
FORGE = PREFIX + "-forge"
REGISTRY = PREFIX + "-registry"
REGISTRY_PORT = 18092
REGISTRY_IMAGE = "registry@sha256:a3d8aaa63ed8681a604f1dea0aa03f100d5895b6a58ace528858a7b332415373"
USERNAME = "skald-t7p"
OUT = REPO / "target/t7probe"
MAVEN_IMAGE = "maven:3.9-eclipse-temurin-21"


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, REPO / "dev" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bases = load("bases_probe", "bases-probe.py")
recipes = load("recipes_probe", "recipes-probe.py")

results = []


def record(name, ok, detail):
    results.append((name, ok, detail))
    print("  %s  %-22s %s" % ("ok  " if ok else "FAIL", name, detail), flush=True)


def docker(args, timeout=600, env=None):
    return subprocess.run(["docker"] + args, capture_output=True, text=True, timeout=timeout,
                          env=env)


def teardown():
    for c in (FORGE, REGISTRY):
        docker(["rm", "-f", "-v", c])
    docker(["network", "rm", OUTER])


def htpasswd(password):
    # registry:2 reads bcrypt only; httpd's htpasswd writes it (as dev/run_attack_harness.py).
    # The password on stdin (-i), never a command line: b288e03 N2.
    out = subprocess.run(["docker", "run", "--rm", "-i", "httpd:2-alpine", "htpasswd", "-iBn", USERNAME],
                         input=password, capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise SystemExit("htpasswd: " + out.stderr.strip()[-300:])
    return out.stdout.strip() + "\n"


def images():
    """What `make test` prepares: the worker and gateway from this tree's images/, the pinned
    helpers. Built here, not trusted from a previous run, so the probe tests this commit."""
    for tag, context in (("skald-buildkit-worker:test", "images/buildkit-worker"),
                         ("skald-egress-gateway:test", "images/egress-gateway")):
        built = docker(["build", "-q", "-t", tag, str(REPO / context)], timeout=1800)
        if built.returncode != 0:
            raise SystemExit("build %s: %s" % (tag, built.stderr.strip()[-300:]))
    for pinned in (bases.FORGE_IMAGE, REGISTRY_IMAGE,
                   "busybox@sha256:ea2b9914a16a4ac1981994af97b318f7c7d4db76b580c56177f08bf76f4a0be8",
                   "debian@sha256:88200866dfff7ea7f5cbcb6ec7c8a701889efe6fe859fe64d6990e4b07ea4171"):
        if docker(["pull", "-q", pinned], timeout=1800).returncode != 0:
            raise SystemExit("could not pull " + pinned)


def setup(password, config_dir):
    teardown()
    images()
    if docker(["network", "create", OUTER]).returncode != 0:
        raise SystemExit("could not create " + OUTER)
    run = docker(["run", "-d", "--name", FORGE, "--network", OUTER, "--network-alias", "forge",
                  "--read-only", "--tmpfs", "/data:uid=65532,gid=65532", bases.FORGE_IMAGE,
                  "-addr", ":80", "-data", "/data"])
    if run.returncode != 0:
        raise SystemExit("forge: " + run.stderr.strip()[-300:])
    for _ in range(30):
        if docker(["exec", FORGE, "/forge", "-healthcheck", "-addr", ":80"]).returncode == 0:
            break
        time.sleep(1)
    else:
        raise SystemExit("forge did not become healthy: " + docker(["logs", FORGE]).stdout[-300:])
    auth = pathlib.Path(config_dir) / "auth"
    auth.mkdir()
    (auth / "htpasswd").write_text(htpasswd(password))
    run = docker(["create", "--name", REGISTRY, "--network", OUTER,
                  "-e", "REGISTRY_AUTH=htpasswd", "-e", "REGISTRY_AUTH_HTPASSWD_REALM=skald",
                  "-e", "REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd",
                  "-p", "127.0.0.1:%d:5000" % REGISTRY_PORT, REGISTRY_IMAGE])
    if run.returncode != 0:
        raise SystemExit("registry: " + run.stderr.strip()[-300:])
    docker(["cp", str(auth), REGISTRY + ":/"])
    docker(["start", REGISTRY])


def maven(test, password, published):
    """Runs T7PassProbeTest as `make test` runs Maven: in Docker, host network, the socket."""
    gid = str(os.stat("/var/run/docker.sock").st_gid)
    home = pathlib.Path.home()
    m2, m2home = home / ".cache/skald/m2", home / ".cache/skald/m2home"
    m2.mkdir(parents=True, exist_ok=True)
    m2home.mkdir(parents=True, exist_ok=True)
    selector = "T7PassProbeTest" + ("#" + test if test else "")
    props = {
        "skald.t7probe.registry": REGISTRY, "skald.t7probe.registry-port": str(REGISTRY_PORT),
        "skald.t7probe.username": USERNAME,
        "skald.t7probe.outer": OUTER, "skald.t7probe.published-bases": "/ws/" + str(published.relative_to(REPO)),
        "skald.t7probe.out": "/ws/target/t7probe",
    }
    # The password by environment (-e NAME takes the value from docker's own), never on a
    # command line, where every process on the host could read it.
    cmd = ["docker", "run", "--rm", "--network", "host", "--group-add", gid, "-e", "SKALD_T7PROBE_PASSWORD",
           "-v", "/var/run/docker.sock:/var/run/docker.sock",
           "-u", "%d:%d" % (os.getuid(), os.getgid()), "-e", "HOME=/m2home",
           "-v", "%s:/ws" % REPO, "-v", "%s:/m2" % m2, "-v", "%s:/m2home" % m2home,
           "-w", "/ws", MAVEN_IMAGE, "mvn", "-B", "-Dmaven.repo.local=/m2", "-Dlicense.skip=true",
           "test", "-Dtest=" + selector, "-Dsurefire.failIfNoSpecifiedTests=false"]
    cmd += ["-D%s=%s" % kv for kv in props.items()]
    log = OUT / "maven.log"
    with open(log, "w") as f:
        done = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, timeout=4 * 3600,
                              env=dict(os.environ, SKALD_T7PROBE_PASSWORD=password))
    tail = [l for l in log.read_text().splitlines() if "Tests run:" in l or "FAIL" in l]
    return done.returncode, (tail[-1] if tail else "no result; see " + str(log))


def check_image(built, env):
    """pulled, served, user, read-only for one image the Java half reports as built."""
    name = built["case"] + "/" + built["app"]
    reference = built["output_image"]
    repository, digest = reference.split("@", 1)
    local_repo = "localhost:%d/%s" % (REGISTRY_PORT, repository.split("/", 1)[1])
    # Cold: no local copy of the repository, by any tag or digest.
    for image in docker(["images", "-q", "--no-trunc", local_repo]).stdout.split():
        docker(["rmi", "-f", image])
    present = docker(["images", "-q", local_repo]).stdout.strip()
    pulled = docker(["pull", local_repo + "@" + digest], timeout=1800, env=env)
    record(name + " pulled", pulled.returncode == 0 and not present,
           "cold pull of %s@%s: %s" % (local_repo, digest[:19], "ok" if pulled.returncode == 0
                                        else pulled.stderr.strip()[-200:]))
    if pulled.returncode != 0:
        return
    checks = recipes.serve(local_repo + "@" + digest, PREFIX + "-run")
    for check in ("served", "user", "read-only"):
        ok, detail = checks.get(check, (False, "not checked"))
        record(name + " " + check, ok, detail)
    docker(["rmi", "-f", local_repo + "@" + digest])


def host_clean():
    leftovers = []
    for flag in (["--filter", "label=eu.skald.build-attempt"],):
        leftovers += docker(["ps", "-aq"] + flag).stdout.split()
        leftovers += docker(["volume", "ls", "-q"] + flag).stdout.split()
        leftovers += docker(["network", "ls", "-q"] + flag).stdout.split()
    leftovers += [n for n in docker(["ps", "-a", "--format", "{{.Names}}"]).stdout.split()
                  if n.startswith(("skald-b-", "skald-bkt-", "skald-bkd-", "skald-helper-"))]
    leftovers += [v for v in docker(["volume", "ls", "-q"]).stdout.split() if v.startswith("skald-b-")]
    for backing in pathlib.Path("/sys/block").glob("loop*/loop/backing_file"):
        try:
            if "skald-quota-" in backing.read_text():
                leftovers.append(str(backing))
        except OSError:
            pass
    # Read only if it exists: a run naming a missing volume would create it.
    if docker(["volume", "inspect", "skald-quota-images"]).returncode == 0:
        images = docker(["run", "--rm", "-v", "skald-quota-images:/v:ro", "busybox@sha256:"
                         "ea2b9914a16a4ac1981994af97b318f7c7d4db76b580c56177f08bf76f4a0be8", "ls", "-A", "/v"])
        leftovers += images.stdout.split()
    record("host", not leftovers, "nothing left" if not leftovers else " ".join(leftovers)[:300])


def main(argv):
    keep = "--keep" in argv
    test = argv[argv.index("--test") + 1] if "--test" in argv else None
    password = secrets.token_hex(16)
    tmp = tempfile.mkdtemp(prefix="skald-t7p-")
    env = dict(os.environ, DOCKER_CONFIG=os.path.join(tmp, "docker"))
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True)
    try:
        print("== infrastructure ==")
        setup(password, tmp)
        login = subprocess.run(["docker", "login", "-u", USERNAME, "--password-stdin",
                                "localhost:%d" % REGISTRY_PORT], input=password, capture_output=True,
                               text=True, env=env, timeout=120)
        if login.returncode != 0:
            raise SystemExit("login: " + login.stderr.strip()[-300:])
        published = OUT / "published-bases.json"
        pub = subprocess.run([sys.executable, str(REPO / "dev/publish-bases.py"), "--push-to",
                              "localhost:%d" % REGISTRY_PORT, "--registry", REGISTRY + ":5000",
                              "--out", str(published)], env=env, capture_output=True, text=True,
                             timeout=4 * 3600)
        record("bases published", pub.returncode == 0, (pub.stdout + pub.stderr).strip().splitlines()[-1]
               if (pub.stdout + pub.stderr).strip() else "no output")
        if pub.returncode != 0:
            print(pub.stdout[-3000:], pub.stderr[-1000:])
            return 1
        print("== the wired worker (T7PassProbeTest) ==")
        code, summary = maven(test, password, published)
        record("java", code == 0, summary)
        report = OUT / "results.json"
        built = json.loads(report.read_text()) if report.exists() else []
        print("== cold pull and run ==")
        for b in built:
            if b.get("state") == "PUBLISHING" and b.get("log_complete") and b.get("output_image"):
                check_image(b, env)
        host_clean()
    finally:
        if not keep:
            teardown()
        shutil.rmtree(tmp, ignore_errors=True)
    failed = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
