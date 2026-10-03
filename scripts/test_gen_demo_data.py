#!/usr/bin/env python3
"""
Checks for scripts/gen-demo-data.py. Run:  python -m unittest scripts/test_gen_demo_data.py -v   (from the repo root)

The last test loads every department's schema and generated seed into a REAL Postgres (needs Docker; skipped without it) and
proves a citizen can be verified with the generated password, exactly as the department services do it.
"""
import csv
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "gen-demo-data.py"
DEPTS = ("revenue", "dbt", "education", "agriculture")
URLS = ["--app-url", "https://app.example.net", "--revenue-url", "https://revenue.example.net", "--dbt-url", "https://dbt.example.net",
        "--education-url", "https://education.example.net", "--agriculture-url", "http://203.0.113.9:8094"]


def generate(out, *extra):
    subprocess.run([sys.executable, str(SCRIPT), "--out", str(out), *URLS, *extra], check=True, capture_output=True, text=True)


def rows(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.reader(f))


class GeneratedData(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = pathlib.Path(tempfile.mkdtemp())
        cls.out = cls.tmp / "gen"
        generate(cls.out)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_twenty_citizens_with_unique_mobiles_each_have_a_login_in_every_department(self):
        creds = rows(self.out / "credentials.csv")
        self.assertEqual(len(creds), 21)  # header + 20
        mobiles = [r[1] for r in creds[1:]]
        self.assertEqual(len(set(mobiles)), 20)
        self.assertTrue(all(re.fullmatch(r"[0-9]{10}", m) for m in mobiles))
        for d in DEPTS:
            seed = (self.out / d / "seed.sql").read_text(encoding="utf-8")
            self.assertEqual(seed.count("INSERT INTO citizen_login"), 20, d)

    def test_every_citizen_has_their_own_person_id_and_a_different_password_in_each_department(self):
        header, *body = rows(self.out / "credentials.csv")
        pw_cols = [header.index(f"{d}_password") for d in DEPTS]
        for r in body:
            self.assertEqual(len({r[i] for i in pw_cols}), 4, r[0])
        self.assertEqual(body[0][header.index("revenue_person_id")], "RV-1001")
        self.assertEqual(body[19][header.index("agriculture_person_id")], "AG-1020")
        self.assertTrue(all(r[-1] == "123456" for r in body))

    def test_a_departments_passwords_are_not_in_any_other_departments_files(self):
        header, *body = rows(self.out / "credentials.csv")
        for d in DEPTS:
            col = header.index(f"{d}_password")
            for other in DEPTS:
                if other == d:
                    continue
                text = (self.out / other / "seed.sql").read_text(encoding="utf-8")
                for r in body:
                    self.assertNotIn(f"'{r[col]}'", text, f"{d} password leaked into {other}")

    def test_documents_agree_between_the_sql_and_the_csv_exports(self):
        land = rows(self.out / "revenue" / "712.csv")
        self.assertEqual(len(land), 21)
        seed = (self.out / "revenue" / "seed.sql").read_text(encoding="utf-8")
        for r in land[1:]:
            self.assertIn(f"'{r[0]}', '{r[1]}', '{r[2]}', '{r[3]}', '{r[4]}', {r[5]}", seed)
        crops = rows(self.out / "agriculture" / "crop.csv")
        agri = (self.out / "agriculture" / "seed.sql").read_text(encoding="utf-8")
        for r in crops[1:]:
            self.assertIn(f"'{r[0]}', '{r[1]}', '{r[2]}', {r[3]}", agri)
        self.assertGreaterEqual(len(crops), 21)

    def test_the_built_in_demo_citizens_keep_their_records_and_everyone_else_has_all_documents(self):
        rv = (self.out / "revenue" / "seed.sql").read_text(encoding="utf-8")
        self.assertIn("INC-2026-0007", rv)
        self.assertIn("GAT-212/3", rv)
        self.assertNotIn("'CST-0002'", rv)  # Ravi has no caste certificate, as in the built-in seed
        self.assertEqual(rv.count("'CASTE_CERTIFICATE'"), 19)
        self.assertEqual(rv.count("'DOMICILE_CERTIFICATE'"), 20)
        self.assertEqual((self.out / "dbt" / "seed.sql").read_text(encoding="utf-8").count("INSERT INTO bank_account"), 20)
        self.assertEqual((self.out / "education" / "seed.sql").read_text(encoding="utf-8").count("INSERT INTO marks_statement"), 20)
        self.assertEqual((self.out / "agriculture" / "seed.sql").read_text(encoding="utf-8").count("INSERT INTO farmer"), 20)

    def test_rerunning_keeps_passwords_and_secrets_and_rotate_changes_them(self):
        before = (self.out / "credentials.csv").read_text(encoding="utf-8")
        env_before = (self.out / "revenue" / "revenue.env").read_text(encoding="utf-8")
        generate(self.out)
        self.assertEqual((self.out / "credentials.csv").read_text(encoding="utf-8"), before)
        self.assertEqual((self.out / "revenue" / "revenue.env").read_text(encoding="utf-8"), env_before)
        other = self.tmp / "rotated"
        generate(other)
        generate(other, "--rotate")
        self.assertNotEqual((other / "credentials.csv").read_text(encoding="utf-8"), before)

    def test_the_middle_layer_gets_every_credential_the_departments_issued_and_the_discovery_keys_by_host(self):
        env = dict(line.split("=", 1) for line in (self.out / "middle-layer" / "departments.env").read_text(encoding="utf-8").splitlines()
                   if line and not line.startswith("#"))
        for key in ("SOURCE_REVENUE_REST", "SOURCE_REVENUE_SFTP", "SOURCE_DBT_REST", "SOURCE_EDUCATION_SOAP", "SOURCE_AGRICULTURE_JDBC",
                    "SOURCE_AGRICULTURE_SFTP"):
            self.assertIn(f"SAMANVAY_SECRET_{key}_CREDENTIAL", env)
        # the same naming rule as the Java side (CatalogServices.discoverySecretKey): host, plus -port when explicit
        self.assertIn("SAMANVAY_SECRET_MANIFEST_REVENUE_EXAMPLE_NET_CREDENTIAL", env)
        self.assertIn("SAMANVAY_SECRET_MANIFEST_203_0_113_9_8094_CREDENTIAL", env)
        self.assertEqual(env["SAMANVAY_DEPARTMENT_RETURN_PREFIXES"], "https://app.example.net/")
        state = json.loads((self.out / "state.json").read_text(encoding="utf-8"))
        import base64
        self.assertEqual(json.loads(base64.b64decode(env["SAMANVAY_SECRET_SOURCE_REVENUE_REST_CREDENTIAL"])),
                         {"X-Api-Key": state["secrets"]["revenue"]["api_key"]})

    def test_a_department_env_hides_the_demo_hint_sets_the_code_and_points_at_its_own_database(self):
        env = dict(line.split("=", 1) for line in (self.out / "revenue" / "revenue.env").read_text(encoding="utf-8").splitlines()
                   if line and not line.startswith("#"))
        self.assertEqual(env["REVENUE_LOGIN_HINT"], "")
        self.assertEqual(env["REVENUE_LOGIN_CODE"], "123456")
        self.assertEqual(env["REVENUE_DB_URL"], "jdbc:postgresql://db:5432/revenue")
        self.assertEqual(env["REVENUE_ALLOWED_RETURN_URIS"], "https://app.example.net/")
        self.assertEqual(env["REVENUE_PUBLIC_URL"], "https://revenue.example.net")

    @unittest.skipUnless(shutil.which("ssh-keygen"), "ssh-keygen is not available")
    def test_the_sftp_host_key_pin_is_the_fingerprint_of_the_key_that_gets_mounted_and_is_the_same_on_both_sides(self):
        def env(path):
            return dict(l.split("=", 1) for l in path.read_text(encoding="utf-8").splitlines() if l and not l.startswith("#"))
        mid = env(self.out / "middle-layer" / "departments.env")
        for dept, var in (("revenue", "REVENUE"), ("agriculture", "AGRICULTURE")):
            key = self.out / dept / "ssh" / "ssh_host_rsa_key"
            actual = subprocess.run(["ssh-keygen", "-lf", str(key) + ".pub"], capture_output=True, text=True, check=True).stdout.split()[1]
            self.assertTrue(actual.startswith("SHA256:"))
            self.assertEqual(env(self.out / dept / f"{dept}.env")[f"{var}_SFTP_HOSTKEY"], actual)
            self.assertEqual(mid[f"SAMANVAY_{var}_SFTP_HOSTKEY"], actual)

    def test_no_discovery_key_flag_leaves_manifests_public(self):
        other = self.tmp / "public"
        generate(other, "--no-discovery-key")
        env = (other / "revenue" / "revenue.env").read_text(encoding="utf-8")
        self.assertIn("REVENUE_MANIFEST_DISCOVERY_KEY=\n", env)

    def test_the_output_names_nothing_with_a_long_dash(self):
        for f in self.out.rglob("*"):
            if f.is_file():
                self.assertNotIn("—", f.read_text(encoding="utf-8"), str(f))


@unittest.skipUnless(shutil.which("docker"), "docker is not available")
class SeedsLoadIntoRealPostgres(unittest.TestCase):
    """Schema + generated seed in a throwaway Postgres per department; then verify a login the way the services do."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = pathlib.Path(tempfile.mkdtemp())
        generate(cls.tmp)
        cls.creds = rows(cls.tmp / "credentials.csv")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def psql_session(self, dept, db_name, sql_after=""):
        name = f"gen-test-{dept}-{uuid.uuid4().hex[:8]}"
        subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=pw", "-e", f"POSTGRES_DB={db_name}", "postgres:16"],
                       check=True, capture_output=True)
        self.addCleanup(lambda: subprocess.run(["docker", "rm", "-f", name], capture_output=True))
        for _ in range(60):
            r = subprocess.run(["docker", "exec", name, "pg_isready", "-U", "postgres", "-d", db_name], capture_output=True)
            if r.returncode == 0:
                # the image restarts once after init; confirm it stays up
                subprocess.run(["docker", "exec", name, "sleep", "2"], capture_output=True)
                if subprocess.run(["docker", "exec", name, "pg_isready", "-U", "postgres", "-d", db_name], capture_output=True).returncode == 0:
                    break
            subprocess.run(["docker", "exec", name, "sleep", "1"], capture_output=True)
        schema = (ROOT / "departments" / dept / "db" / "schema.sql").read_text(encoding="utf-8")
        seed = (self.tmp / dept / "seed.sql").read_text(encoding="utf-8")
        run = subprocess.run(["docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", db_name, "-v", "ON_ERROR_STOP=1", "-q"],
                             input=schema + seed + sql_after, capture_output=True, text=True)
        self.assertEqual(run.returncode, 0, run.stderr)
        return lambda q: subprocess.run(["docker", "exec", name, "psql", "-U", "postgres", "-d", db_name, "-At", "-c", q],
                                        capture_output=True, text=True, check=True).stdout.strip()

    def check_login(self, dept, query, person_prefix):
        header = self.creds[0]
        asha = self.creds[1]
        password = asha[header.index(f"{dept}_password")]
        q = self.psql_session(dept, dept if dept != "agriculture" else "agridb")
        found = q(f"SELECT person_id FROM citizen_login WHERE mobile = '{asha[1]}' AND password_hash = crypt('{password}', password_hash)")
        self.assertEqual(found, f"{person_prefix}-1001")
        wrong = q(f"SELECT person_id FROM citizen_login WHERE mobile = '{asha[1]}' AND password_hash = crypt('not-it', password_hash)")
        self.assertEqual(wrong, "")
        self.assertEqual(q("SELECT count(*) FROM citizen_login"), "20")
        self.assertEqual(q(query), "20")

    def test_revenue(self):
        self.check_login("revenue", "SELECT count(DISTINCT person_id) FROM certificate", "RV")

    def test_dbt(self):
        self.check_login("dbt", "SELECT count(*) FROM bank_account", "DBT")

    def test_education(self):
        self.check_login("education", "SELECT count(*) FROM marks_statement", "EDU")

    def test_agriculture_and_its_two_database_roles(self):
        self.check_login("agriculture", "SELECT count(*) FROM v_farmer_record", "AG")


if __name__ == "__main__":
    unittest.main()
