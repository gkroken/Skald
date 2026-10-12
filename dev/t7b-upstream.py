#!/usr/bin/env python3
#
# Skald - Copyright (C) 2026 Gard Kroken
# SPDX-License-Identifier: Apache-2.0
#

"""The T7b probe's CRAN-like upstream: what forge's CRAN proxy member fronts in the probe, so
the probe can publish test packages as "public" (WORKPLAN-BUNDLES T7b step 2, point 6).

  /src/contrib/PACKAGES[.gz]          CRAN's real index (fetched once) with the records of the
                                      packages in DATA/contrib/ in place of CRAN's of the same
                                      name -- Package, Version, MD5sum and Depends/Imports/
                                      LinkingTo read from each tarball's DESCRIPTION, computed
                                      per request, so the probe can republish a tarball
  /src/contrib/<pkg>_<ver>.tar.gz     DATA/contrib/ if there, else CRAN's
  /src/contrib/Archive/<pkg>/<file>   DATA/archive/<pkg>/ if there, else CRAN's
  anything else                       CRAN's (streamed, no redirect)

Run with DATA as its first argument and the port as its second; stdlib only.
"""

import gzip
import hashlib
import http.server
import io
import os
import socketserver
import sys
import tarfile
import urllib.request

DATA = sys.argv[1] if len(sys.argv) > 1 else "/data"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
CRAN = "https://cloud.r-project.org"
FIELDS = ("Depends", "Imports", "LinkingTo")
_cran_index = None


def cran_index():
    global _cran_index
    if _cran_index is None:
        with urllib.request.urlopen(CRAN + "/src/contrib/PACKAGES", timeout=120) as r:
            _cran_index = r.read().decode("utf-8")
    return _cran_index


def description(path):
    with tarfile.open(path, "r:gz") as t:
        for m in t.getmembers():
            if m.name.count("/") == 1 and m.name.endswith("/DESCRIPTION"):
                text = t.extractfile(m).read().decode("utf-8")
                fields, last = {}, None
                for line in text.splitlines():
                    if line[:1] in (" ", "\t") and last:
                        fields[last] += " " + line.strip()
                    elif ":" in line:
                        last, value = line.split(":", 1)
                        fields[last] = value.strip()
                return fields
    raise ValueError("no DESCRIPTION in " + path)


def index():
    ours, names = [], set()
    contrib = os.path.join(DATA, "contrib")
    for f in sorted(os.listdir(contrib)) if os.path.isdir(contrib) else []:
        if not f.endswith(".tar.gz"):
            continue
        path = os.path.join(contrib, f)
        d = description(path)
        md5 = hashlib.md5(open(path, "rb").read()).hexdigest()
        names.add(d["Package"])
        record = ["Package: " + d["Package"], "Version: " + d["Version"]]
        record += ["%s: %s" % (k, d[k]) for k in FIELDS if k in d]
        record += ["License: " + d.get("License", "MIT"), "MD5sum: " + md5, "NeedsCompilation: no"]
        ours.append("\n".join(record))
    kept = [r for r in cran_index().strip().split("\n\n")
            if not any(r.startswith("Package: %s\n" % n) for n in names)]
    return ("\n\n".join(kept + ours) + "\n").encode("utf-8")


class Handler(http.server.BaseHTTPRequestHandler):
    def send(self, status, body, ctype="application/octet-stream"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/src/contrib/PACKAGES":
            return self.send(200, index(), "text/plain")
        if path == "/src/contrib/PACKAGES.gz":
            return self.send(200, gzip.compress(index()))
        if path == "/src/contrib/PACKAGES.rds":
            return self.send(404, b"no rds index here")
        local = None
        if path.startswith("/src/contrib/Archive/"):
            local = os.path.join(DATA, "archive", *path[len("/src/contrib/Archive/"):].split("/"))
        elif path.startswith("/src/contrib/") and path.count("/") == 3:
            local = os.path.join(DATA, "contrib", path[len("/src/contrib/"):])
        if local and ".." not in path and os.path.isfile(local):
            return self.send(200, open(local, "rb").read())
        try:
            with urllib.request.urlopen(CRAN + path, timeout=300) as r:
                return self.send(r.status, r.read())
        except urllib.error.HTTPError as e:
            return self.send(e.code, b"")
        except Exception as e:
            return self.send(502, str(e).encode())

    def log_message(self, fmt, *args):
        sys.stderr.write("upstream: " + (fmt % args) + "\n")


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    Server(("0.0.0.0", PORT), Handler).serve_forever()
