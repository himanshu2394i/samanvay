#!/usr/bin/env python3
"""
Prints the fingerprint (RFC 7638 thumbprint) of the key a department signs its manifest with, as the department serves it.
Compare it with the "Manifest signing key thumbprint" line in the department server's log, and with what the staff console
shows when you onboard. Usage: python scripts/manifest-fingerprint.py <base-url> [discovery-key]
(This only reads the key the manifest carries; Samanvay itself verifies the signature.)
"""
import base64
import hashlib
import json
import sys
import urllib.request

if len(sys.argv) not in (2, 3):
    sys.exit(__doc__)
req = urllib.request.Request(sys.argv[1].rstrip("/") + "/.well-known/samanvay/manifest")
if len(sys.argv) == 3:
    req.add_header("X-Discovery-Key", sys.argv[2])
with urllib.request.urlopen(req, timeout=15) as r:
    signature = r.headers.get("X-Samanvay-Signature")
if not signature:
    sys.exit("the manifest is not signed")


def b64d(part):
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


jwk = json.loads(b64d(signature.split(".")[0]))["jwk"]
members = json.dumps({k: jwk[k] for k in ("crv", "kty", "x", "y")}, sort_keys=True, separators=(",", ":"))
print(base64.urlsafe_b64encode(hashlib.sha256(members.encode()).digest()).rstrip(b"=").decode())
