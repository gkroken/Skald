#
# Skald - Copyright (C) 2026 Gard Kroken
# SPDX-License-Identifier: Apache-2.0
#

# Skald: MinIO faults on the upload and validation paths, on the real dev stack
# (WORKPLAN-BUNDLES.md T8 (f)). The in-process tests inject each fault where it lands; this
# breaks the real store under the real SDK, both ways a store fails: refusing (stopped) and
# hung (paused). Each case must end with the bundle VALIDATED and no re-upload, or for the
# upload, a retryable refusal after which the same PUT succeeds.
#
#   upload, store stopped mid-PUT   -> 503 STORAGE_UNAVAILABLE, still UPLOADING; same PUT -> VALIDATED
#   upload, store paused mid-PUT    -> a refusal or a slow 200; then VALIDATED either way
#   validation, store stopped       -> VALIDATED after the store returns (a storage retry, no lapse)
#   validation, store paused        -> VALIDATED after the store returns
#   validation, instance killed     -> VALIDATED after the restart, once the dead claim's lease lapses
#
# Usage: python3 dev/fault-probe.py <alice-cookie-jar> [BASE]   (stack up: make dev)
# Stops, pauses and restarts the dev stack's minio and skald containers; leaves them running.
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
jar_path, base = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080")
MINIO, SKALD = "skald-dev-minio-1", "skald-dev-skald-1"


def read_jar():
    cookies = []
    for line in pathlib.Path(jar_path).read_text().splitlines():
        fields = line.replace("#HttpOnly_", "", 1).split("\t")
        if len(fields) == 7 and not fields[0].startswith("#"):
            cookies.append(fields[5] + "=" + fields[6])
    return "; ".join(cookies)


COOKIE = read_jar()


def login():
    """alice again, through the browser flow as dev/smoke.sh does: a restart ends every session."""
    global COOKIE
    pathlib.Path(jar_path).unlink(missing_ok=True)
    page = subprocess.run(["curl", "-sL", "-c", jar_path, "-b", jar_path, base + "/"], capture_output=True,
                          text=True).stdout
    action = page.split('action="', 1)[1].split('"', 1)[0].replace("&amp;", "&")
    subprocess.run(["curl", "-sL", "-c", jar_path, "-b", jar_path, "-d", "username=alice", "-d", "password=alice",
                    "-d", "credentialId=", action, "-o", "/dev/null"], check=True)
    COOKIE = read_jar()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def call(method, path, body=None, headers=None, timeout=300):
    req = urllib.request.Request(base + path, data=body, method=method, headers=dict(headers or {}, Cookie=COOKIE))
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        text = e.read()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text.decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as e:
        return None, str(e)


def docker(*args):
    subprocess.run(["docker", *args], check=True, stdout=subprocess.DEVNULL)


def wait_up():
    for _ in range(120):
        s, _ = call("GET", "/admin/content", timeout=5)
        if s == 200:
            return
        if s == 302:
            login()
            continue
        time.sleep(2)
    raise SystemExit("the platform did not come back")


def fixture(name, outdir):
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(ROOT / "dev/fixtures/bundles"))
    import generate
    f = next(x for x in generate.FIXTURES if x["name"] == name)
    b = generate.Builder()
    f["build"](b)
    path = pathlib.Path(outdir) / (name + ".tar.gz")
    b.write(path)
    return path.read_bytes()


passed = failed = 0
made = []


def check(what, got, want):
    global passed, failed
    if got == want:
        passed += 1
        print("  ok   %s" % what, flush=True)
    else:
        failed += 1
        print("  FAIL %s (expected %r, got %r)" % (what, want, got), flush=True)


def content():
    s, c = call("POST", "/admin/content", json.dumps({"path": "faults-%s/%s" % (RUN, uuid.uuid4().hex[:6]),
                "title": "fault probe", "owner": "alice", "type": "shiny"}).encode(), {"Content-Type": "application/json"})
    d = c["data"]
    cid = d.get("content", d)["id"]
    made.append(cid)
    return cid


def bundle(cid):
    s, b = call("POST", "/admin/content/%s/bundles" % cid, b"{}", {"Content-Type": "application/json"})
    return b["data"]["id"]


def put(cid, bid, data):
    return call("PUT", "/admin/content/%s/bundles/%s/content" % (cid, bid), data,
                {"Content-Type": "application/gzip", "X-Skald-Upload": "1"}, timeout=600)


def put_breaking(cid, bid, data, fault):
    """The PUT, sent in paced 1 MiB pieces, with `fault` run once half the body has gone out:
    a local upload is otherwise over before any timer could break the store under it."""
    import http.client
    import urllib.parse
    u = urllib.parse.urlsplit(base)
    conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=600)
    conn.putrequest("PUT", "/admin/content/%s/bundles/%s/content" % (cid, bid))
    for k, v in (("Content-Type", "application/gzip"), ("X-Skald-Upload", "1"),
                 ("Content-Length", str(len(data))), ("Cookie", COOKIE)):
        conn.putheader(k, v)
    conn.endheaders()
    piece = 1 << 20
    try:
        for at in range(0, len(data), piece):
            if at == (len(data) // 2) // piece * piece:
                fault()
            conn.send(data[at:at + piece])
            time.sleep(0.005)
        r = conn.getresponse()
        body = r.read()
        return r.status, json.loads(body)
    except (OSError, http.client.HTTPException) as e:
        # The server may answer and close while the rest of the body is still going out.
        try:
            r = conn.getresponse()
            return r.status, json.loads(r.read())
        except Exception:
            return None, str(e)
    finally:
        conn.close()


def state(cid, bid):
    s, st = call("GET", "/admin/content/%s/bundles/%s" % (cid, bid), timeout=30)
    return st["data"] if s == 200 else {"state": None}


def await_state(cid, bid, want, seconds):
    until = time.time() + seconds
    st = {}
    while time.time() < until:
        st = state(cid, bid)
        if st.get("state") in ("VALIDATED", "REJECTED"):
            break
        time.sleep(1)
    return st.get("state"), (st.get("rejection") or {}).get("rule")


def await_validating(cid, bid, seconds=30):
    until = time.time() + seconds
    while time.time() < until and state(cid, bid).get("state") != "VALIDATING":
        time.sleep(0.2)


def later(seconds, fn):
    t = threading.Timer(seconds, fn)
    t.start()
    return t


RUN = uuid.uuid4().hex[:8]
with tempfile.TemporaryDirectory() as tmp:
    big = fixture("bomb-compressed-at-limit", tmp)      # 256 MiB to upload
    slow = fixture("bomb-expanded-at-limit", tmp)       # 2 MiB that expands to 2 GiB to validate
try:
    print("== upload, store stopped mid-PUT ==")
    cid = content(); bid = bundle(cid)
    s, r = put_breaking(cid, bid, big, lambda: docker("stop", "-t", "1", MINIO))
    check("the PUT is refused as the store's failure", (s, r.get("data", {}).get("code") if isinstance(r, dict) else r),
          (503, "STORAGE_UNAVAILABLE"))
    check("the bundle is still UPLOADING", state(cid, bid).get("state"), "UPLOADING")
    docker("start", MINIO); time.sleep(5)
    s, r = put(cid, bid, big)
    check("the same PUT succeeds once the store is back", s, 200)
    check("and the bundle is validated", await_state(cid, bid, "VALIDATED", 300), ("VALIDATED", None))

    print("== upload, store paused mid-PUT ==")
    cid = content(); bid = bundle(cid)
    u = None

    def pause():
        global u
        docker("pause", MINIO)
        u = later(40, lambda: docker("unpause", MINIO))
    s, r = put_breaking(cid, bid, big, pause)
    if u is not None:
        u.join()
    print("       the PUT answered %s %s" % (s, r.get("data", {}).get("code") if isinstance(r, dict) else ""))
    if s != 200:
        check("a refused PUT is retryable", r.get("data", {}).get("retryable") if isinstance(r, dict) else None, True)
        time.sleep(3)
        s, r = put(cid, bid, big)
    check("the upload completes", s, 200)
    check("and the bundle is validated", await_state(cid, bid, "VALIDATED", 300), ("VALIDATED", None))

    print("== validation, store stopped ==")
    cid = content(); bid = bundle(cid)
    check("the upload completes", put(cid, bid, slow)[0], 200)
    docker("stop", "-t", "1", MINIO)
    time.sleep(20)
    check("validation has not reached a verdict without the store", state(cid, bid).get("state"), "VALIDATING")
    docker("start", MINIO)
    check("the bundle is validated once the store is back", await_state(cid, bid, "VALIDATED", 600), ("VALIDATED", None))

    print("== validation, store paused ==")
    cid = content(); bid = bundle(cid)
    check("the upload completes", put(cid, bid, slow)[0], 200)
    docker("pause", MINIO)
    time.sleep(40)
    check("validation has not reached a verdict while the store hangs", state(cid, bid).get("state"), "VALIDATING")
    docker("unpause", MINIO)
    check("the bundle is validated once the store answers", await_state(cid, bid, "VALIDATED", 900), ("VALIDATED", None))

    print("== validation, instance killed ==")
    cid = content(); bid = bundle(cid)
    check("the upload completes", put(cid, bid, slow)[0], 200)
    time.sleep(1)
    docker("kill", SKALD)
    docker("start", SKALD)
    wait_up()
    # The dead instance's claim holds until its lease (300 s) lapses; then any validator takes it.
    check("the bundle is validated after the restart, no re-upload", await_state(cid, bid, "VALIDATED", 900),
          ("VALIDATED", None))
finally:
    subprocess.run(["docker", "unpause", MINIO], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["docker", "start", MINIO, SKALD], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    wait_up()
    for cid in made:
        call("DELETE", "/admin/content/%s" % cid)
print("fault probe: passed %d, failed %d" % (passed, failed))
sys.exit(1 if failed else 0)
