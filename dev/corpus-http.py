#
# Skald - Copyright (C) 2026 Gard Kroken
# SPDX-License-Identifier: Apache-2.0
#

# Skald: T2's bundle corpus through the real admin transport (WORKPLAN-BUNDLES.md T8 (f)).
#
# Every fixture of dev/fixtures/bundles is generated, uploaded as an admin through the
# browser-flow session against the dev stack, and followed to its verdict:
#   accept -> VALIDATED; reject -> REJECTED naming a rule, or refused at the PUT itself
#   (413 BUNDLE_TOO_LARGE for the archive over the compressed limit).
# The same check the T5 gate made against the extractor, made here through HTTP: the
# create, the upload, the validator on its claimed lease and the status read.
#
# Usage: python3 dev/corpus-http.py <alice-cookie-jar> [BASE]   (stack up: make dev)
import http.client
import json
import os
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
jar_path, base = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080")
# The session from curl's jar, sent as one Cookie header: http.cookiejar will not send a cookie
# for a bare "localhost" domain, and reads curl's "#HttpOnly_" lines as comments.
cookies = []
for line in pathlib.Path(jar_path).read_text().splitlines():
    fields = line.replace("#HttpOnly_", "", 1).split("\t")
    if len(fields) == 7 and not fields[0].startswith("#"):
        cookies.append(fields[5] + "=" + fields[6])
COOKIE = "; ".join(cookies)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # A redirect here means the session is gone: report it, never follow it to a login page.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def call(method, path, body=None, headers=None):
    req = urllib.request.Request(base + path, data=body, method=method, headers=dict(headers or {}, Cookie=COOKIE))
    try:
        with opener.open(req, timeout=600) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        text = e.read()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text.decode("utf-8", "replace")


def headers_only_put(path, length):
    u = urllib.parse.urlsplit(base)
    conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=60)
    conn.putrequest("PUT", path)
    for k, v in (("Content-Type", "application/gzip"), ("X-Skald-Upload", "1"),
                 ("Content-Length", str(length)), ("Cookie", COOKIE)):
        conn.putheader(k, v)
    conn.endheaders()
    r = conn.getresponse()
    body = r.read()
    conn.close()
    return r.status, json.loads(body)


def manifest_type(archive):
    try:
        with tarfile.open(archive, "r:gz") as t:
            m = t.extractfile("manifest.json")
            return json.load(m)["runtime"]["type"]
    except Exception:
        return None


expect = json.loads((ROOT / "dev/fixtures/bundles/expectations.json").read_text())
out = pathlib.Path(tempfile.mkdtemp(prefix="skald-corpus-"))
subprocess.run([sys.executable, "-I", str(ROOT / "dev/fixtures/bundles/generate.py"), str(out)], check=True,
               stdout=subprocess.DEVNULL)
run = uuid.uuid4().hex[:8]
passed = failed = 0
made = []
try:
    for fx in expect["fixtures"]:
        name = fx["name"]
        archive = out / (name + ".tar.gz")
        ctype = manifest_type(archive) or "shiny"
        s, c = call("POST", "/admin/content", json.dumps({"path": "corpus-" + run + "/" + name, "title": name,
                    "owner": "alice", "type": ctype}).encode(), {"Content-Type": "application/json"})
        if s not in (200, 201):
            print("  FAIL %s: content create %s %s" % (name, s, c)); failed += 1; continue
        d = c["data"]
        cid = d.get("content", d)["id"]
        made.append(cid)
        s, b = call("POST", "/admin/content/%s/bundles" % cid, b"{}", {"Content-Type": "application/json"})
        bid = b["data"]["id"]
        data = archive.read_bytes()
        path = "/admin/content/%s/bundles/%s/content" % (cid, bid)
        if len(data) > b["data"]["maxBytes"]:
            # Refused on its Content-Length before a byte is read, so only the headers are sent,
            # as a client waiting on 100-continue would; sending the body races the server's close.
            s, put = headers_only_put(path, len(data))
        else:
            s, put = call("PUT", path, data, {"Content-Type": "application/gzip", "X-Skald-Upload": "1"})
        verdict, rule = None, None
        if s == 413:
            verdict, rule = "REJECTED", put["data"]["code"]
        elif s != 200:
            print("  FAIL %s: upload %s %s" % (name, s, put)); failed += 1; continue
        deadline = time.time() + 300
        while verdict is None and time.time() < deadline:
            s, st = call("GET", "/admin/content/%s/bundles/%s" % (cid, bid))
            state = st["data"]["state"]
            if state in ("VALIDATED", "REJECTED"):
                verdict = state
                rule = (st["data"].get("rejection") or {}).get("rule")
            else:
                time.sleep(0.5)
        want = "VALIDATED" if fx["expect"] == "accept" else "REJECTED"
        if verdict == want and (want == "VALIDATED" or rule):
            passed += 1
            print("  ok   %s: %s%s" % (name, verdict, " " + rule if rule else ""), flush=True)
        else:
            print("  FAIL %s: expected %s, got %s %s" % (name, want, verdict, rule)); failed += 1
finally:
    # Our own content only, whatever happened above.
    for cid in made:
        call("DELETE", "/admin/content/%s" % cid)
print("corpus over HTTP: %d fixtures, passed %d, failed %d" % (len(expect["fixtures"]), passed, failed))
sys.exit(1 if failed else 0)
