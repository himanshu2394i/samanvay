#!/usr/bin/env python3
"""
Sets the secret of each department's caller client (dept-revenue, dept-dbt, dept-education, dept-agriculture) in the staff realm to
the value gen-demo-data.py generated for that department's server, then proves each one works.

Why: Keycloak makes up new client secrets every time the realm is imported, but a department's server is configured with a fixed
one (SAMANVAY_CLIENT_SECRET in <dept>.env). Run this ON THE MIDDLE-LAYER SERVER after Keycloak is up, and again after any
re-import. It is safe to repeat.

  python3 provision-department-clients.py middle-layer/department-clients.json

The file is {"dept-revenue": "<secret>", ...} (written by scripts/gen-demo-data.py; it holds secrets, keep it private).
The Keycloak admin login is read from ~/samanvay/docker-compose.override.yml (KC_BOOTSTRAP_ADMIN_*), as the other deploy scripts do.
Nothing secret is printed.
"""
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = os.environ.get("KEYCLOAK_LOCAL_URL", "http://localhost:8180")
REALM = os.environ.get("SAMANVAY_STAFF_REALM", "samanvay-staff")


def call(url, data=None, headers=None, method=None):
    body = data if isinstance(data, bytes) else (urllib.parse.urlencode(data).encode() if data else None)
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def admin_login():
    override = open(os.path.expanduser("~/samanvay/docker-compose.override.yml")).read()
    user = re.search(r"KC_BOOTSTRAP_ADMIN_USERNAME:\s*(\S+)", override).group(1).strip("\"'")
    password = re.search(r"KC_BOOTSTRAP_ADMIN_PASSWORD:\s*(\S+)", override).group(1).strip("\"'")
    status, body = call(f"{KEYCLOAK}/realms/master/protocol/openid-connect/token",
                        {"grant_type": "password", "client_id": "admin-cli", "username": user, "password": password})
    if status != 200:
        sys.exit(f"could not sign in to the Keycloak admin API (HTTP {status})")
    return json.loads(body)["access_token"]


def claims(jwt):
    part = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    secrets = json.load(open(sys.argv[1], encoding="utf-8"))
    token = admin_login()
    auth = {"Authorization": "Bearer " + token}
    failed = False
    for client_id, secret in secrets.items():
        status, body = call(f"{KEYCLOAK}/admin/realms/{REALM}/clients?clientId={urllib.parse.quote(client_id)}", headers=auth)
        found = json.loads(body) if status == 200 else []
        if not found:
            print(f"{client_id}: NOT FOUND in realm {REALM} (is the staff realm imported?)")
            failed = True
            continue
        rep = found[0]
        rep["secret"] = secret
        status, body = call(f"{KEYCLOAK}/admin/realms/{REALM}/clients/{rep['id']}",
                            json.dumps(rep).encode(), {**auth, "Content-Type": "application/json"}, "PUT")
        if status not in (200, 204):
            print(f"{client_id}: could not set the secret (HTTP {status})")
            failed = True
            continue
        status, body = call(f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
                            {"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret})
        if status != 200:
            print(f"{client_id}: secret set, but the sign in check failed (HTTP {status})")
            failed = True
            continue
        c = claims(json.loads(body)["access_token"])
        roles = c.get("realm_access", {}).get("roles", [])
        ok = "department" in roles and bool(c.get("department"))
        print(f"{client_id}: secret set; token works; department claim = {c.get('department')}; department role = {'department' in roles}")
        failed = failed or not ok
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
