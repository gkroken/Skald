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

  infrastructure  an outer network; forge on it as `forge` (and `forge2`, the same forge under
                  a second name, for the cache row that changes the mirror), listening on :80 (the gateway
                  serves repositories on 80 and 443 only, and GatewayConfig's ports are not
                  widened for a probe); an htpasswd registry:2 on it, published on
                  127.0.0.1:REGISTRY_PORT; the trusted bases published to that registry by
                  dev/publish-bases.py, under the name the worker uses (<registry>:5000)
  java            T7PassProbeTest: the application's own configuration with a deployment's
                  properties; bundles stored as T8 will store them; results.json
  cache           part 4e-2's rows, as the Java half judged them from each build's log:
                  whether the dependency restore ran or was reused, and the key
  injected        part 4e-3's rows (failed restore, unreachable repository, push failure,
                  timeout, cancellation, worker death), as the Java half judged them: the
                  state and code, a complete log naming the cause, and the seconds until
                  nothing the attempt owned was left
  pulled          for each built image: no local copy of the repository exists, then
                  `docker pull localhost:PORT/<repository>@<digest>` succeeds (the daemon
                  allows plain http to localhost only; the digest is the one the worker's
                  reference names, so it is the same image)
  served/user/    the pulled image, run hardened (dev/recipes-probe.py's checks): GET / is a
  read-only       Shiny page, every process is uid 10001 (PID 1 included), /app is not
                  writable
  restart         part 4e-4: Postgres and MinIO run here (skald-t7p-pg, skald-t7p-minio) so
                  they outlive a JVM; phase one (restartPhaseOneRunsUntilTheProcessIsKilled)
                  starts a build, and once its restore runs the probe `docker kill`s that
                  Maven container -- no shutdown hook, no disposal, no log finish; phase two,
                  a new JVM on the same database and store, must settle the attempt
                  INTERRUPTED, dispose of what it left, finish its log, and build again
  host            after everything, nothing labelled eu.skald.build-attempt or named
                  skald-b-*; no loop device backed by skald-quota-*; skald-quota-images empty

The probe's own containers, network and volumes are named skald-t7p-* and removed at the end.
Docker credentials for the probe registry live in a temporary DOCKER_CONFIG, never in the
user's.

Usage: python3 dev/t7-pass-probe.py [--keep] [--test METHOD | --test restart]
  --keep   leave the infrastructure up afterwards (for debugging; removed by the next run)
  --test   run one T7PassProbeTest method instead of all, or only the restart rows
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
PG, PG_PORT = PREFIX + "-pg", 18093
MINIO, MINIO_PORT = PREFIX + "-minio", 18094
MAVEN_NAME = PREFIX + "-maven"
PG_IMAGE = "postgres:16"
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


def remove_tree(path):
    """rmtree that also removes a frozen (read-only) extraction tree, which a killed JVM leaves
    in the workspace: each directory is made writable before its entries go."""
    if not path.exists():
        return
    for root, dirs, _ in os.walk(path):
        for d in dirs:
            full = os.path.join(root, d)
            if not os.path.islink(full):
                os.chmod(full, 0o700)
    os.chmod(path, 0o700)
    shutil.rmtree(path)


def teardown():
    for c in (FORGE, REGISTRY, PG, MINIO, MAVEN_NAME):
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
                  "--network-alias", "forge2",
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


def minio_image():
    """The MinIO the Java tests pin (MinioTestContainer.IMAGE), read from its source so the
    two cannot drift."""
    src = (REPO / "src/test/java/eu/openanalytics/shinyproxy/publisher/storage/MinioTestContainer.java").read_text()
    import re
    m = re.search(r'IMAGE =\s*"([^"]+)"\s*\+\s*"([^"]+)";', src)
    if not m:
        raise SystemExit("could not read MinioTestContainer.IMAGE")
    return m.group(1) + m.group(2)


def restart_stores(db_password):
    """Postgres and MinIO for the restart rows, published on 127.0.0.1 only."""
    for c in (PG, MINIO):
        docker(["rm", "-f", "-v", c])
    run = docker(["run", "-d", "--name", PG, "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_DB=skald",
                  "-p", "127.0.0.1:%d:5432" % PG_PORT, PG_IMAGE],
                 env=dict(os.environ, POSTGRES_PASSWORD=db_password))
    if run.returncode != 0:
        raise SystemExit("postgres: " + run.stderr.strip()[-300:])
    run = docker(["run", "-d", "--name", MINIO, "-e", "MINIO_ROOT_USER=skald", "-e", "MINIO_ROOT_PASSWORD=skaldskald",
                  "-p", "127.0.0.1:%d:9000" % MINIO_PORT, minio_image(), "server", "/data"], timeout=1800)
    if run.returncode != 0:
        raise SystemExit("minio: " + run.stderr.strip()[-300:])
    for _ in range(60):
        if docker(["exec", PG, "pg_isready", "-U", "postgres", "-d", "skald"]).returncode == 0:
            break
        time.sleep(1)
    else:
        raise SystemExit("postgres did not become ready")
    for _ in range(60):
        if docker(["exec", MINIO, "mc", "ready", "local"]).returncode == 0 or \
                subprocess.run(["curl", "-sf", "http://127.0.0.1:%d/minio/health/ready" % MINIO_PORT]).returncode == 0:
            break
        time.sleep(1)
    else:
        raise SystemExit("minio did not become ready")
    return {"skald.t7probe.jdbc-url": "jdbc:postgresql://localhost:%d/skald" % PG_PORT,
            "skald.t7probe.jdbc-user": "postgres",
            "skald.t7probe.s3-endpoint": "http://localhost:%d" % MINIO_PORT}


def restart(password, published):
    """Part 4e-4: kill a JVM mid-build, then let a new one reconcile."""
    db_password = secrets.token_hex(16)
    props = restart_stores(db_password)
    marker = OUT / "restart.json"
    marker.unlink(missing_ok=True)
    env = {"SKALD_T7PROBE_DB_PASSWORD": db_password}
    phase1 = maven("restartPhaseOneRunsUntilTheProcessIsKilled", password, published,
                   dict(props, **{"skald.t7probe.restart-phase": "crash",
                                  "skald.t7probe.results": "results-restart-crash.json"}),
                   env, background=True, log_name="maven-restart-crash.log")
    deadline = time.time() + 1800
    while not marker.exists() and phase1.poll() is None and time.time() < deadline:
        time.sleep(2)
    if not marker.exists():
        phase1.kill()
        docker(["rm", "-f", MAVEN_NAME])
        record("restart: phase one", False, "the build never reached its restore; see maven-restart-crash.log")
        return
    killed = docker(["kill", MAVEN_NAME])
    phase1.wait(timeout=120)
    attempt = json.loads(marker.read_text())["attempt"]
    alive = docker(["inspect", "-f", "{{.State.Running}}", "skald-b-%s-worker" % attempt]).stdout.strip()
    record("restart: killed mid-build", killed.returncode == 0 and alive == "true",
           "JVM killed during the restore; its worker skald-b-%s-worker still running: %s" % (attempt[:12], alive))
    code, summary = maven("restartPhaseTwoReconcilesWhatTheDeadProcessLeft", password, published,
                          dict(props, **{"skald.t7probe.restart-phase": "recover",
                                         "skald.t7probe.results": "results-restart.json"}),
                          env, log_name="maven-restart-recover.log")
    record("restart: java", code == 0, summary)
    report = OUT / "results-restart.json"
    for b in json.loads(report.read_text()) if report.exists() else []:
        if b["case"] == "restart-crashed":
            record("restart: reconciled", b.get("state") == "INTERRUPTED" and b.get("teardown_seconds", -1) >= 0,
                   "%s, log %s, settled %.0fs after the new process started, then teardown %.1fs" % (
                       b.get("state"), (b.get("log_final") or {}).get("outcome"), b.get("settled_seconds", -1),
                       b.get("teardown_seconds", -1)))
    return json.loads(report.read_text()) if report.exists() else []


def maven(test, password, published, extra_props=None, extra_env=None, background=False, log_name="maven.log"):
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
    props.update(extra_props or {})
    # The password by environment (-e NAME takes the value from docker's own), never on a
    # command line, where every process on the host could read it.
    secret_env = dict(SKALD_T7PROBE_PASSWORD=password, **(extra_env or {}))
    cmd = ["docker", "run", "--rm", "--name", MAVEN_NAME, "--network", "host", "--group-add", gid]
    for name in secret_env:
        cmd += ["-e", name]
    cmd += [
           "-v", "/var/run/docker.sock:/var/run/docker.sock",
           "-u", "%d:%d" % (os.getuid(), os.getgid()), "-e", "HOME=/m2home",
           "-v", "%s:/ws" % REPO, "-v", "%s:/m2" % m2, "-v", "%s:/m2home" % m2home,
           "-w", "/ws", MAVEN_IMAGE, "mvn", "-B", "-Dmaven.repo.local=/m2", "-Dlicense.skip=true",
           "test", "-Dtest=" + selector, "-Dsurefire.failIfNoSpecifiedTests=false"]
    cmd += ["-D%s=%s" % kv for kv in props.items()]
    log = OUT / log_name
    env = dict(os.environ, **secret_env)
    if background:
        return subprocess.Popen(cmd, stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    with open(log, "w") as f:
        done = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, timeout=4 * 3600, env=env)
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
    remove_tree(OUT)
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
        built = []
        if test != "restart":
            print("== the wired worker (T7PassProbeTest) ==")
            extra = {}
            # T7c: several builds per instance, and N builds admitted at once to measure them.
            if "--parallel" in argv:
                extra["skald.t7probe.parallel"] = argv[argv.index("--parallel") + 1]
            if "--concurrent" in argv:
                extra["skald.t7probe.concurrent"] = argv[argv.index("--concurrent") + 1]
            code, summary = maven(test, password, published, extra_props=extra)
            record("java", code == 0, summary)
            report = OUT / "results.json"
            built = json.loads(report.read_text()) if report.exists() else []
        if test in (None, "restart"):
            print("== a crash mid-build, then a restart ==")
            built += restart(password, published) or []
        print("== the dependency cache (judged by the Java half) ==")
        for b in built:
            if "cache" in b:
                c = b["cache"]
                record("%s/%s" % (b["case"], b["app"]), b.get("state") == "PUBLISHING",
                       "restore %s, key %s..., %ss, imported %s" % (
                           c.get("restore"), (c.get("key") or "")[:12], str(b.get("seconds"))[:6],
                           ",".join(r.rsplit("/", 1)[-1][:22] for r in c.get("imported") or []) or "none"))
        concurrent = [b for b in built if b["case"] == "concurrent"]
        if concurrent:
            print("== concurrent builds (T7c) ==")
            for b in concurrent:
                ph = b.get("phases") or {}
                record("concurrent/%s" % b["build_id"][:8], b.get("state") == "PUBLISHING",
                       "parallel %s: %.1fs from admission, attempt %.1fs, launch.setup %.1fs" % (
                           b.get("parallel"), float(b.get("since_admission") or -1), float(ph.get("total", -1)),
                           float(ph.get("launch.setup", -1))))
            done = sorted(float(b.get("since_admission") or 0) for b in concurrent)
            print("  last outcome %.1fs after admission; setups %s" % (done[-1], sorted(
                round(float((b.get("phases") or {}).get("launch.setup", -1)), 1) for b in concurrent)))
        print("== injected failures (judged by the Java half) ==")
        for b in built:
            if b["case"].startswith("inject-"):
                record(b["case"], b.get("teardown_seconds", -1) >= 0,
                       "%s %s, log %s, teardown %.1fs" % (
                           b.get("state"), b.get("error_code") or "", (b.get("log_final") or {}).get("outcome"),
                           b.get("teardown_seconds", -1)))
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
