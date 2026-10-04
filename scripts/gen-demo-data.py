#!/usr/bin/env python3
"""
Generates everything a multi-server demo deployment needs, for FAKE data only:

  * 20 citizens, each present in all four departments with that department's OWN person ID, and fake documents in each
  * a mobile number + password login for every citizen in every department (one-time code 123456 everywhere)
  * every secret the departments and the middle layer must share, and one config file per server

Output goes to deploy/generated/ (git-ignored: it holds passwords). Passwords and secrets are RANDOM but are kept in
deploy/generated/state.json, so re-running (for example once the real server addresses are known) keeps them stable.
Pass --rotate to make new ones. The documents themselves are deterministic.

Usage (repo root):
  python scripts/gen-demo-data.py --app-url https://app.example.com \
      --revenue-url https://revenue.example.com --dbt-url https://dbt.example.com \
      --education-url https://education.example.com --agriculture-url https://agriculture.example.com

Python standard library only.
"""
import argparse
import base64
import csv
import datetime as dt
import json
import pathlib
import random
import re
import secrets
import shutil
import subprocess
import urllib.parse

ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"  # no look-alike characters (0/o, 1/l/i)
CODE = "123456"
DEPTS = ("revenue", "dbt", "education", "agriculture")
PREFIX = {"revenue": "RV", "dbt": "DBT", "education": "EDU", "agriculture": "AG"}

# (name, date of birth or None to generate). The first two keep the data the built-in demo seeds always had.
CITIZENS = [
    ("Asha Patil", "2007-03-14"), ("Ravi Deshmukh", "2007-08-02"), ("Meera Kulkarni", None), ("Sanjay Shinde", None),
    ("Pooja Jadhav", None), ("Amit Pawar", None), ("Sneha More", None), ("Rahul Gaikwad", None), ("Kavita Bhosale", None),
    ("Vijay Chavan", None), ("Anjali Kale", None), ("Nitin Salunkhe", None), ("Rekha Thorat", None), ("Prakash Sawant", None),
    ("Swati Kadam", None), ("Ganesh Mane", None), ("Shweta Joshi", None), ("Mahesh Wagh", None), ("Priya Deshpande", None),
    ("Sachin Gawande", None),
]
PLACES = [  # (district, taluka, village)
    ("Nashik", "Nashik", "Ojhar"), ("Pune", "Haveli", "Loni"), ("Pune", "Baramati", "Supe"), ("Satara", "Karad", "Malkapur"),
    ("Kolhapur", "Karveer", "Gokul Shirgaon"), ("Nagpur", "Kamptee", "Koradi"), ("Aurangabad", "Paithan", "Bidkin"),
    ("Solapur", "Pandharpur", "Karkamb"), ("Ahmednagar", "Rahuri", "Deolali Pravara"), ("Jalgaon", "Bhusawal", "Waregaon"),
    ("Amravati", "Daryapur", "Pimpalkhuta"), ("Latur", "Udgir", "Hala"), ("Sangli", "Miraj", "Bedag"), ("Dhule", "Shirpur", "Boradi"),
    ("Nanded", "Kinwat", "Mandvi"), ("Beed", "Georai", "Talwada"), ("Yavatmal", "Pusad", "Shembal"), ("Raigad", "Karjat", "Neral"),
    ("Thane", "Shahapur", "Kasara"), ("Ratnagiri", "Chiplun", "Savarda"),
]
CASTES = [("Kunbi", "OBC"), ("Mahar", "SC"), ("Matang", "SC"), ("Bhil", "ST"), ("Gond", "ST"), ("Dhangar", "NT-C"), ("Teli", "OBC"),
          ("Mali", "OBC"), ("Maratha", "SEBC"), ("Brahmin", "Open")]
BANKS = [("SBIN", "State Bank of India"), ("HDFC", "HDFC Bank"), ("BARB", "Bank of Baroda"), ("MAHB", "Bank of Maharashtra"),
         ("ICIC", "ICICI Bank"), ("PUNB", "Punjab National Bank")]
CROPS = {"KHARIF-2025": ["Soybean", "Cotton", "Jowar", "Tur", "Bajra", "Groundnut"], "RABI-2025": ["Wheat", "Gram", "Onion", "Jowar"]}
SCHOOLS = ["Z.P. Junior College", "Shivaji Junior College", "Mahatma Phule Vidyalaya", "Dnyanada Junior College", "New English Junior College"]


def pw():
    return "".join(secrets.choice(ALPHABET) for _ in range(12))


def token(prefix):
    return prefix + secrets.token_urlsafe(24)


def q(v):
    """A SQL string literal."""
    return "'" + str(v).replace("'", "''") + "'"


def rng(c, purpose):
    """A fresh generator per citizen and purpose, so a value is the same every time it is asked for (CSV and SQL must agree)."""
    return random.Random(f"{c['i']}-{purpose}")


def build_citizens():
    out = []
    for i, (name, dob) in enumerate(CITIZENS, start=1):
        c = {"i": i, "name": name, "mobile": f"90000000{i:02d}", "place": PLACES[i - 1]}
        c["dob"] = dob or (dt.date(2001, 1, 1) + dt.timedelta(days=rng(c, "dob").randrange(0, 365 * 8))).isoformat()
        out.append(c)
    return out


def income_certs(c, counter):
    """Asha and Ravi keep the records the built-in seed always had; everyone else is generated."""
    district, taluka, _ = c["place"]
    office = f"Tahsildar, {taluka}"
    if c["i"] == 1:
        return [("INC-2025-0001", "2025-04-10", "2024-25", 160000, "Tahsildar, Nashik", "Nashik"),
                ("INC-2026-0007", "2026-04-12", "2025-26", 185000, "Tahsildar, Nashik", "Nashik")]
    if c["i"] == 2:
        return [("INC-2025-0002", "2025-05-02", "2024-25", 742000, "Tahsildar, Pune", "Pune")]
    r = rng(c, "income")
    certs = [(f"INC-2025-{counter + 100:04d}", f"2025-0{r.randint(4, 6)}-{r.randint(10, 28)}", "2024-25", r.randrange(80, 900) * 1000, office, district)]
    if c["i"] % 3 == 0:  # a renewal: the manifest's resolve step picks the latest
        certs.append((f"INC-2026-{counter + 300:04d}", f"2026-0{r.randint(4, 6)}-{r.randint(10, 28)}", "2025-26", r.randrange(80, 900) * 1000, office, district))
    return certs


def revenue(c):
    """Returns (person, income[], caste|None, domicile, land)."""
    district, taluka, village = c["place"]
    r = rng(c, "revenue")
    if c["i"] == 1:
        caste, dom_date, caste_date = ("Maratha", "OBC", "2024-08-20"), "2024-08-21", "2024-08-20"
        land = ("GAT-212/3", 1.85)
    elif c["i"] == 2:
        caste, dom_date, caste_date = None, "2025-01-15", None
        land = ("SN-47/1", 0.92)
    else:
        caste, dom_date, caste_date = CASTES[r.randrange(len(CASTES))], f"2024-0{r.randint(1, 9)}-{r.randint(10, 28)}", f"2024-0{r.randint(1, 9)}-{r.randint(10, 28)}"
        land = (f"GAT-{r.randint(20, 480)}/{r.randint(1, 9)}", round(r.uniform(0.4, 4.5), 2))
    return caste, caste_date, dom_date, land


def sql_revenue(cs, passwords):
    lines = ["-- Revenue demo data. FAKE. Generated by scripts/gen-demo-data.py; contains passwords, so keep it private.", "BEGIN;"]
    counter = 0
    for c in cs:
        pid = f"RV-{1000 + c['i']}"
        district, taluka, village = c["place"]
        lines.append(f"INSERT INTO person VALUES ({q(pid)}, {q(c['name'])}, {q(c['dob'])}, {q(district)}, {q(taluka)}, {q(village)});")
        lines.append(f"INSERT INTO citizen_login VALUES ({q(c['mobile'])}, crypt({q(passwords[c['mobile']])}, gen_salt('bf')), {q(pid)});")
        for key, issued, fy, amount, office, dist in income_certs(c, counter):
            f = {"annualIncome": str(amount), "annualIncomeDisplay": f"Rs {amount}", "holderName": c["name"], "district": dist,
                 "issuerOffice": office, "financialYear": fy}
            lines.append(f"INSERT INTO certificate VALUES ({q(key)}, 'INCOME_CERTIFICATE', {q(pid)}, {q(issued)}, {q(office)}, {q(json.dumps(f))});")
        counter += 1
        caste, caste_date, dom_date, land = revenue(c)
        office = f"Tahsildar, {taluka}"
        if caste:
            f = {"holderName": c["name"], "caste": caste[0], "casteCategory": caste[1], "issuerOffice": office}
            cst = "CST-%04d" % c["i"]
            lines.append(f"INSERT INTO certificate VALUES ({q(cst)}, 'CASTE_CERTIFICATE', {q(pid)}, {q(caste_date)}, {q(office)}, {q(json.dumps(f))});")
        f = {"holderName": c["name"], "state": "Maharashtra", "district": district, "issuerOffice": office}
        dom = "DOM-%04d" % c["i"]
        lines.append(f"INSERT INTO certificate VALUES ({q(dom)}, 'DOMICILE_CERTIFICATE', {q(pid)}, {q(dom_date)}, {q(office)}, {q(json.dumps(f))});")
        lines.append(f"INSERT INTO land_record VALUES ({q(pid)}, {q(land[0])}, {q(village)}, {q(taluka)}, {q(district)}, {land[1]:.2f}, {q(c['name'])});")
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def csv_712(cs):
    rows = [["personId", "surveyNo", "village", "taluka", "district", "areaHectares", "ownerName"]]
    for c in cs:
        district, taluka, village = c["place"]
        land = revenue(c)[3]
        rows.append([f"RV-{1000 + c['i']}", land[0], village, taluka, district, f"{land[1]:.2f}", c["name"]])
    return rows


def bank(c):
    r = rng(c, "bank")
    if c["i"] == 1:
        return "XXXXXX1234", "SBIN0XXX300", "State Bank of India"
    if c["i"] == 2:
        return "XXXXXX5678", "HDFC0XXX210", "HDFC Bank"
    code, name = BANKS[r.randrange(len(BANKS))]
    return f"XXXXXX{r.randint(1000, 9999)}", f"{code}0XXX{r.randint(100, 999)}", name


def sql_dbt(cs, passwords):
    lines = ["-- DBT demo data. FAKE. Generated by scripts/gen-demo-data.py; contains passwords, so keep it private.", "BEGIN;"]
    for c in cs:
        pid = f"DBT-{1000 + c['i']}"
        ref, ifsc, bank_name = bank(c)
        lines.append(f"INSERT INTO beneficiary VALUES ({q(pid)}, {q(c['name'])}, {q(c['mobile'])}, {q(c['dob'])});")
        lines.append(f"INSERT INTO bank_account VALUES ({q(pid)}, {q(ref)}, {q(ifsc)}, {q(c['name'])}, {q(bank_name)}, '2025-06-01');")
        lines.append(f"INSERT INTO citizen_login VALUES ({q(c['mobile'])}, crypt({q(passwords[c['mobile']])}, gen_salt('bf')), {q(pid)});")
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def percentage(c):
    return {1: "91.00", 2: "81.00"}.get(c["i"]) or f"{rng(c, 'marks').uniform(48, 96):.2f}"


def sql_education(cs, passwords):
    lines = ["-- Education demo data. FAKE. Generated by scripts/gen-demo-data.py; contains passwords, so keep it private.", "BEGIN;"]
    for c in cs:
        pid = f"EDU-{1000 + c['i']}"
        school = f"{SCHOOLS[c['i'] % len(SCHOOLS)]}, {c['place'][1]}"
        seat = "B%d" % (310000 + c["i"] * 17)
        lines.append(f"INSERT INTO student VALUES ({q(pid)}, {q(c['name'])}, {q(c['mobile'])}, {q(c['dob'])}, {q(seat)}, {q(school)});")
        lines.append(f"INSERT INTO marks_statement VALUES ({q(pid)}, 'HSC 2025', 'msbshse', {percentage(c)}, 2025);")
        lines.append(f"INSERT INTO citizen_login VALUES ({q(c['mobile'])}, crypt({q(passwords[c['mobile']])}, gen_salt('bf')), {q(pid)});")
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def farm(c):
    """(land hectares, crops[(season, crop, area)], internal note)"""
    r = rng(c, "farm")
    if c["i"] == 1:
        return 1.85, [("KHARIF-2025", "Soybean", 1.20), ("RABI-2025", "Wheat", 0.65)], "internal: subsidy audit pending"
    if c["i"] == 2:
        return 0.92, [("KHARIF-2025", "Cotton", 0.92)], "internal: none"
    land = revenue(c)[3][1]
    first = round(land * r.uniform(0.5, 0.8), 2)
    crops = [("KHARIF-2025", r.choice(CROPS["KHARIF-2025"]), first)]
    if c["i"] % 2 == 0:
        crops.append(("RABI-2025", r.choice(CROPS["RABI-2025"]), round(land - first, 2) or 0.2))
    return land, crops, r.choice(["internal: none", "internal: inspection due", "internal: documents verified"])


def sql_agriculture(cs, passwords, sec):
    lines = ["-- Agriculture demo data and database roles. FAKE. Generated by scripts/gen-demo-data.py; contains passwords.", "BEGIN;"]
    for c in cs:
        pid = f"AG-{1000 + c['i']}"
        land, crops, note = farm(c)
        village, taluka = c["place"][2], c["place"][1]
        lines.append(f"INSERT INTO farmer VALUES ({q(pid)}, {q(c['name'])}, {q(village)}, {q(taluka)}, {land:.2f}, {q(note)}, {q(c['dob'])});")
        for season, crop, area in crops:
            lines.append(f"INSERT INTO crop_sowing VALUES ({q(pid)}, {q(season)}, {q(crop)}, {area:.2f});")
        lines.append(f"INSERT INTO citizen_login VALUES ({q(c['mobile'])}, crypt({q(passwords[c['mobile']])}, gen_salt('bf')), {q(pid)});")
    lines += [
        "-- The department's own service: reads citizen_login to sign a farmer in, nothing else.",
        f"CREATE ROLE agriculture_app LOGIN PASSWORD {q(sec['app_password'])};",
        "GRANT CONNECT ON DATABASE agridb TO agriculture_app;", "GRANT USAGE ON SCHEMA public TO agriculture_app;",
        "GRANT SELECT ON citizen_login TO agriculture_app;",
        "-- Name and date of birth only (for the login assertion), never the internal notes.",
        "GRANT SELECT (agri_person_id, farmer_name, date_of_birth) ON farmer TO agriculture_app;",
        "-- The read-only login Samanvay uses over JDBC: the VIEW only (no base table, no internal notes, no logins).",
        f"CREATE ROLE agri_ro LOGIN PASSWORD {q(sec['ro_password'])};",
        "GRANT CONNECT ON DATABASE agridb TO agri_ro;", "GRANT USAGE ON SCHEMA public TO agri_ro;",
        "GRANT SELECT ON v_farmer_record TO agri_ro;", "COMMIT;"]
    return "\n".join(lines) + "\n"


def csv_crop(cs):
    rows = [["agriPersonId", "season", "crop", "areaHectares"]]
    for c in cs:
        for season, crop, area in farm(c)[1]:
            rows.append([f"AG-{1000 + c['i']}", season, crop, f"{area:.2f}"])
    return rows


def sftp_host_key(folder, rotate):
    """Creates (once) the RSA host key an SFTP server presents and returns its SHA256 fingerprint, or None without ssh-keygen.

    The key is mounted into the SFTP container so a re-created container presents the SAME key; Samanvay pins its fingerprint
    (the SFTP client negotiates rsa-sha2, so the pin must be the RSA key's)."""
    if not shutil.which("ssh-keygen"):
        return None
    key = folder / "ssh" / "ssh_host_rsa_key"
    if rotate:
        for f in (key, key.with_name(key.name + ".pub")):
            f.unlink(missing_ok=True)
    if not key.exists():
        key.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ssh-keygen", "-q", "-t", "rsa", "-b", "3072", "-N", "", "-C", "samanvay-demo-sftp", "-f", str(key)], check=True)
    return subprocess.run(["ssh-keygen", "-lf", str(key) + ".pub"], check=True, capture_output=True, text=True).stdout.split()[1]


def secret_key_env(key):
    return "SAMANVAY_SECRET_" + key.upper().replace("-", "_")


def discovery_secret_key(url):
    p = urllib.parse.urlparse(url)
    host_port = (p.hostname or "").lower() + (f"-{p.port}" if p.port else "")
    return "manifest-" + re.sub(r"[^a-z0-9]+", "-", host_port) + "-credential"


def b64(v):
    return base64.b64encode(v.encode()).decode()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def write_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        csv.writer(f, lineterminator="\n").writerows(rows)


def env_file(title, pairs):
    return f"# {title}\n# FAKE-data demo. Contains secrets: keep this file private and never commit it.\n" + "".join(f"{k}={v}\n" for k, v in pairs)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--app-url", default="https://app.example.com", help="the middle layer's public address (staff use it; department portals call its API)")
    ap.add_argument("--auth-url", help="Keycloak's public address (default: --app-url with the first label 'app' replaced by 'auth')")
    for d in DEPTS:
        ap.add_argument(f"--{d}-url", default=f"https://{d}.example.com", help=f"{d}'s public base URL (what you enter when onboarding)")
        ap.add_argument(f"--{d}-host", help=f"{d} server's public host name or IP for its SFTP/database ports (default: the host of --{d}-url)")
    ap.add_argument("--no-discovery-key", action="store_true", help="leave every manifest public (default: each department asks for a discovery credential)")
    ap.add_argument("--rotate", action="store_true", help="make new passwords and secrets instead of keeping the saved ones")
    ap.add_argument("--out", default="deploy/generated")
    a = ap.parse_args()

    out = pathlib.Path(a.out)
    state_path = out / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() and not a.rotate else {}
    cs = build_citizens()
    assert len(cs) == 20 and len({c["mobile"] for c in cs}) == 20

    pws = state.setdefault("passwords", {})
    for d in DEPTS:
        pws.setdefault(d, {})
        for c in cs:
            pws[d].setdefault(c["mobile"], pw())
    sec = state.setdefault("secrets", {})
    for d in DEPTS:
        s = sec.setdefault(d, {})
        s.setdefault("db_password", token(""))
        s.setdefault("discovery_key", token("dk_"))
        s.setdefault("client_secret", token("dc_"))   # the secret of this department's caller client (dept-<code>) in the staff realm
        s.setdefault("session_secret", token("ss_"))  # signs this department portal's sign-in tickets and session cookie
    sec["revenue"].setdefault("api_key", token("rv_"))
    sec["revenue"].setdefault("sftp_password", token(""))
    sec["dbt"].setdefault("client_id", "samanvay")
    sec["dbt"].setdefault("client_secret", token("dbt_"))
    sec["education"].setdefault("wss_username", "samanvay")
    sec["education"].setdefault("wss_password", token("edu_"))
    sec["agriculture"].setdefault("app_password", token(""))
    sec["agriculture"].setdefault("ro_password", token(""))
    sec["agriculture"].setdefault("sftp_password", token(""))
    write(state_path, json.dumps(state, indent=2) + "\n")

    urls = {d: getattr(a, f"{d}_url").rstrip("/") for d in DEPTS}
    hosts = {d: getattr(a, f"{d}_host") or urllib.parse.urlparse(urls[d]).hostname for d in DEPTS}
    app = a.app_url.rstrip("/")
    auth = (a.auth_url or re.sub(r"^(https?://)app\.", r"\1auth.", app)).rstrip("/")
    token_url = auth + "/realms/samanvay-staff/protocol/openid-connect/token"
    discovery = not a.no_discovery_key

    pins = {d: sftp_host_key(out / d, a.rotate) for d in ("revenue", "agriculture")}
    todo = "TODO-install-ssh-keygen-and-rerun"

    # ---- per-department files -------------------------------------------------------------------------------------------
    write(out / "revenue/seed.sql", sql_revenue(cs, pws["revenue"]))
    write_csv(out / "revenue/712.csv", csv_712(cs))
    write(out / "dbt/seed.sql", sql_dbt(cs, pws["dbt"]))
    write(out / "education/seed.sql", sql_education(cs, pws["education"]))
    write(out / "agriculture/seed.sql", sql_agriculture(cs, pws["agriculture"], sec["agriculture"]))
    write_csv(out / "agriculture/crop.csv", csv_crop(cs))

    def common(d, port, db_password):
        return [("PUBLIC_HOST", urllib.parse.urlparse(urls[d]).hostname), (f"{d.upper()}_PUBLIC_URL", urls[d]), (f"{d.upper()}_PORT", str(port)),
                # A citizen is only ever sent back to a department PORTAL: the other departments' callbacks, never its own, never Samanvay.
                (f"{d.upper()}_ALLOWED_RETURN_URIS", ",".join(urls[o] + "/portal/callback" for o in DEPTS if o != d)),
                (f"{d.upper()}_SESSION_SECRET", sec[d]["session_secret"]),
                # How this department's server reaches Samanvay's department API: its own client, never sent to a browser.
                ("SAMANVAY_URL", app), ("SAMANVAY_TOKEN_URL", token_url), ("SAMANVAY_CLIENT_ID", f"dept-{d}"),
                ("SAMANVAY_CLIENT_SECRET", sec[d]["client_secret"]),
                (f"{d.upper()}_LOGIN_HINT", ""),  # empty hides the demo hint box: citizens are given their own credentials
                (f"{d.upper()}_LOGIN_CODE", CODE),
                # This is a DEMO deployment (one fixed code, 123456, and possibly plain http). A department refuses to start with those
                # unless it is told, on purpose, that it is a demo. A real deployment must NOT set this: it needs its own code and https.
                ("DEPARTMENT_DEMO_MODE", "true"),
                (f"{d.upper()}_DB_URL", f"jdbc:postgresql://db:5432/{'agridb' if d == 'agriculture' else d}"), (f"{d.upper()}_DB_USER", f"{d}_app"),
                (f"{d.upper()}_DB_PASSWORD", db_password),
                (f"{d.upper()}_MANIFEST_KEY_FILE", "/data/manifest-signing-key.jwk"),
                (f"{d.upper()}_MANIFEST_DISCOVERY_KEY", sec[d]["discovery_key"] if discovery else "")]

    rv = common("revenue", 8091, sec["revenue"]["db_password"]) + [("REVENUE_API_KEY", sec["revenue"]["api_key"]), ("REVENUE_SFTP_HOST", hosts["revenue"]),
                                        ("REVENUE_SFTP_PORT", "2223"), ("REVENUE_SFTP_HOSTKEY", pins["revenue"] or todo)]
    write(out / "revenue/revenue.env", env_file("Revenue department server", rv + [("REVENUE_SFTP_PASSWORD", sec["revenue"]["sftp_password"]),
                                                                                 ("POSTGRES_PASSWORD", sec["revenue"]["db_password"])]))
    db = common("dbt", 8092, sec["dbt"]["db_password"]) + [("DBT_CLIENT_ID", sec["dbt"]["client_id"]), ("DBT_CLIENT_SECRET", sec["dbt"]["client_secret"])]
    write(out / "dbt/dbt.env", env_file("DBT server", db + [("POSTGRES_PASSWORD", sec["dbt"]["db_password"])]))
    ed = common("education", 8093, sec["education"]["db_password"]) + [("EDUCATION_WSS_USERNAME", sec["education"]["wss_username"]), ("EDUCATION_WSS_PASSWORD", sec["education"]["wss_password"])]
    write(out / "education/education.env", env_file("Education server", ed + [("POSTGRES_PASSWORD", sec["education"]["db_password"])]))
    ag = common("agriculture", 8094, sec["agriculture"]["app_password"]) + [
        ("AGRICULTURE_DB_HOST", hosts["agriculture"]), ("AGRICULTURE_DB_PORT", "5434"),
        ("AGRICULTURE_SFTP_HOST", hosts["agriculture"]), ("AGRICULTURE_SFTP_PORT", "2224"), ("AGRICULTURE_SFTP_HOSTKEY", pins["agriculture"] or todo)]
    write(out / "agriculture/agriculture.env", env_file("Agriculture server", ag + [
        ("AGRICULTURE_SFTP_PASSWORD", sec["agriculture"]["sftp_password"]),
        ("POSTGRES_PASSWORD", sec["agriculture"]["db_password"])]))

    # ---- middle layer ------------------------------------------------------------------------------------------------------
    secrets_env = [
        ("source-revenue-rest-credential", json.dumps({"X-Api-Key": sec["revenue"]["api_key"]})),
        ("source-revenue-sftp-credential", f"revenue-sftp:{sec['revenue']['sftp_password']}"),
        ("source-dbt-rest-credential", json.dumps({"client_id": sec["dbt"]["client_id"], "client_secret": sec["dbt"]["client_secret"]})),
        ("source-education-soap-credential", json.dumps({"username": sec["education"]["wss_username"], "password": sec["education"]["wss_password"]})),
        ("source-agriculture-jdbc-credential", f"agri_ro:{sec['agriculture']['ro_password']}"),
        ("source-agriculture-sftp-credential", f"agri-sftp:{sec['agriculture']['sftp_password']}"),
    ]
    if discovery:
        secrets_env += [(discovery_secret_key(urls[d]), sec[d]["discovery_key"]) for d in DEPTS]
    lines = [(secret_key_env(k), b64(v)) for k, v in secrets_env]
    cfg = [("SAMANVAY_REVENUE_URL", urls["revenue"]), ("SAMANVAY_DBT_URL", urls["dbt"]), ("SAMANVAY_EDUCATION_URL", urls["education"]),
           ("SAMANVAY_REVENUE_SFTP_HOST", hosts["revenue"]), ("SAMANVAY_REVENUE_SFTP_PORT", "2223"),
           ("SAMANVAY_REVENUE_SFTP_HOSTKEY", pins["revenue"] or todo),
           ("SAMANVAY_AGRICULTURE_SFTP_HOST", hosts["agriculture"]), ("SAMANVAY_AGRICULTURE_SFTP_PORT", "2224"),
           ("SAMANVAY_AGRICULTURE_SFTP_HOSTKEY", pins["agriculture"] or todo),
           ("SAMANVAY_AGRICULTURE_JDBC_URL", f"jdbc:postgresql://{hosts['agriculture']}:5434/agridb"),
           ("SAMANVAY_DEPARTMENT_RETURN_PREFIXES", app + "/"),
           # Samanvay has no citizen sign in: the citizen realm is switched off (empty = not configured).
           ("SAMANVAY_CITIZEN_ISSUER_URI", "")]
    write(out / "middle-layer/departments.env", env_file("Middle layer: add to the app's environment file (systemd EnvironmentFile or compose env_file)",
                                                         lines + cfg))

    # The secrets of the department caller clients; scripts/provision-department-clients.py sets them in Keycloak (run on the middle layer).
    write(out / "middle-layer/department-clients.json", json.dumps({f"dept-{d}": sec[d]["client_secret"] for d in DEPTS}, indent=2) + "\n")

    # ---- credentials for people -----------------------------------------------------------------------------------------------
    rows = [["name", "mobile"] + [f"{d}_person_id" for d in DEPTS] + [f"{d}_password" for d in DEPTS] + ["one_time_code"]]
    for c in cs:
        rows.append([c["name"], c["mobile"]] + [f"{PREFIX[d]}-{1000 + c['i']}" for d in DEPTS] + [pws[d][c["mobile"]] for d in DEPTS] + [CODE])
    write_csv(out / "credentials.csv", rows)
    md = ["# Citizen logins (FAKE data, demo only)", "", "Every department has its own login: **registered mobile number + that department's password**, then the "
          f"one-time code **{CODE}** (the same everywhere in this demo). A citizen has a different password in each department, "
          "and a different person ID.", "", "| Citizen | Mobile | Revenue | DBT | Education | Agriculture |", "|---|---|---|---|---|---|"]
    for c in cs:
        md.append(f"| {c['name']} | {c['mobile']} | " + " | ".join(f"`{pws[d][c['mobile']]}`" for d in DEPTS) + " |")
    md += ["", "Person IDs: Revenue RV-10NN, DBT DBT-10NN, Education EDU-10NN, Agriculture AG-10NN (NN = the citizen's row, 01 to 20).",
           "Citizens 1 and 2 (Asha Patil, Ravi Deshmukh) carry the records the built-in demo always had; Ravi has no caste certificate on purpose.",
           "", "## Where a citizen goes", "",
           "Citizens never use Samanvay. Each department has its own portal; sign in there with that department's mobile number, password and code:", ""]
    md += [f"- {d.capitalize()}: {urls[d]}/portal/" for d in DEPTS]
    md += ["", "A citizen starts a service at the department that offers it, and the portal sends them to the other departments' own logins when the service needs "
           "their records.", "", "## Samanvay itself (staff only)", "",
           "- Staff: `dev-officer`, `dev-reviewer`, `dev-admin`. Temporary password `<user>-change-me`, changed at first sign in.",
           "  The authenticator code is 000000 when the server runs with `SAMANVAY_DEMO_FIXED_OTP=000000` (see deploy/README.md).", ""]
    write(out / "CREDENTIALS.md", "\n".join(md))

    # ---- onboarding sheet ---------------------------------------------------------------------------------------------------------
    sheet = ["# Onboarding sheet (generated; contains secrets)", "", f"Middle layer: **{app}**", "",
             "Add `middle-layer/departments.env` to the middle layer's environment and restart it BEFORE onboarding.",
             "Each department's portal calls Samanvay with its own Keycloak client. After Keycloak is up on the middle layer, copy "
             "`middle-layer/department-clients.json` there and run `python3 scripts/provision-department-clients.py department-clients.json` "
             "(it sets the four client secrets and proves each works). Do it again after any realm re-import.",
             "Then, for each department below: start its server, capture the two fingerprints, onboard, publish.", ""]
    for d in DEPTS:
        sheet += [f"## {d.capitalize()}", "", f"- **Base URL to onboard from:** `{urls[d]}`",
                  f"- Discovery credential (already in departments.env under `{discovery_secret_key(urls[d])}`): " + (f"`{sec[d]['discovery_key']}`" if discovery else "none, manifest is public"),
                  f"- Manifest signing key fingerprint: run `python scripts/manifest-fingerprint.py {urls[d]}" + (f" {sec[d]['discovery_key']}" if discovery else "") + "`; it must equal the line `Manifest signing key thumbprint` in the department server's log. You tick it in the staff console.",
                  {"revenue": f"- SFTP host key pin (already set in revenue.env and in departments.env): `{pins['revenue']}`. After the server is up, `scripts/sftp-hostkey.sh {hosts[d]} 2223` must print the same value.",
                   "dbt": "- No SFTP or database. REST + OAuth2 only.",
                   "education": "- No SFTP or database visible to Samanvay. SOAP + WS-Security only.",
                   "agriculture": f"- SFTP host key pin (already set in agriculture.env and in departments.env): `{pins['agriculture']}`. After the server is up, `scripts/sftp-hostkey.sh {hosts[d]} 2224` must print the same value. Database port 5434 must be reachable from the middle layer only."}[d], ""]
    write(out / "ONBOARDING.md", "\n".join(sheet))
    print(f"Wrote {out}/ : 4 department folders, middle-layer/departments.env, CREDENTIALS.md, credentials.csv, ONBOARDING.md")
    if any(h.endswith("example.com") for h in [urllib.parse.urlparse(u).hostname for u in urls.values()] + [urllib.parse.urlparse(app).hostname]):
        print("NOTE: placeholder addresses were used. Re-run with the real --*-url values; passwords and secrets are kept.")


if __name__ == "__main__":
    main()
