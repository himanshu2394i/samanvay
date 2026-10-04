# Deploying the demo on separate servers

Five servers, each small, all running **fake data only**:

| Server | Runs | Its own database |
|---|---|---|
| **Middle layer** (Samanvay) | the app, Keycloak, its Postgres | Samanvay's Postgres |
| **Revenue** | Revenue service, SFTP (7/12 land records), HTTPS | Revenue's Postgres |
| **DBT** | DBT service, HTTPS | DBT's Postgres |
| **Education** | Education service (SOAP), HTTPS | Education's Postgres |
| **Agriculture** | Agriculture service, SFTP (crop reports), HTTPS | Agriculture's Postgres (Samanvay reads one view of it over JDBC) |

Every department has **20 citizens with fake documents**, and each citizen has a **mobile number + password** login at each
department, followed by the one-time code **123456**. Nothing here is a real government system.

**Who uses what.** Citizens never use the middle layer. Each department serves its **own citizen portal** at
`https://<department address>/portal/`: the citizen signs in there, sees that department's services, and runs the service there. When the
service needs another department's records, the portal sends the citizen to that department's own login and back. The middle layer is
for the Samanvay team and officers (the staff console) and is called by the department portals' servers.

## 0. What you must decide first

Five public addresses, each a **DNS name** that points at its server (HTTPS needs a name, not a bare IP). If you have no domains,
`nip.io` works: for a server at `203.0.113.10` the name `revenue.203.0.113.10.nip.io` resolves to it, and Caddy gets a real
certificate for it automatically.

| Address | Example |
|---|---|
| Middle layer (staff console; the department portals call its API) | `https://app.203.0.113.5.nip.io` |
| Revenue | `https://revenue.203.0.113.10.nip.io` |
| DBT | `https://dbt.203.0.113.11.nip.io` |
| Education | `https://education.203.0.113.12.nip.io` |
| Agriculture | `https://agriculture.203.0.113.13.nip.io` |

Firewall (security group) per server:

| Server | Open to everyone | Open ONLY to the middle layer's address |
|---|---|---|
| each department | 80, 443 | |
| Revenue | | 2223 (SFTP) |
| Agriculture | | 2224 (SFTP), 5434 (database) |
| Middle layer | 80, 443 | |

Never open the SFTP or database ports to the world.

## 1. Generate the data, logins and secrets (once, on your laptop)

```bash
python scripts/gen-demo-data.py \
  --app-url https://app.203.0.113.5.nip.io \
  --revenue-url https://revenue.203.0.113.10.nip.io --dbt-url https://dbt.203.0.113.11.nip.io \
  --education-url https://education.203.0.113.12.nip.io --agriculture-url https://agriculture.203.0.113.13.nip.io
```

Needs only Python 3 and `ssh-keygen`. It writes `deploy/generated/` (**git-ignored, private**: it holds every password):

| File | What it is |
|---|---|
| `CREDENTIALS.md`, `credentials.csv` | the 20 citizens: name, mobile, person ID and password at each department, the code 123456 |
| `ONBOARDING.md` | per department: the base URL to onboard from, its discovery credential, which fingerprints to check |
| `<dept>/<dept>.env` | that server's settings and secrets |
| `<dept>/seed.sql`, `712.csv`, `crop.csv` | that department's citizens and documents |
| `revenue/ssh`, `agriculture/ssh` | the SFTP servers' host keys (so the pinned fingerprint survives a re-created container) |
| `middle-layer/departments.env` | every credential the departments issued to Samanvay, the SFTP pins, the JDBC address |
| `state.json` | the random passwords and secrets, kept so **re-running keeps them the same** |

It also gives every department server its own Keycloak client secret (`SAMANVAY_CLIENT_SECRET`), a portal session secret, the address of
the middle layer (`SAMANVAY_URL`) and of Keycloak (`SAMANVAY_TOKEN_URL`; `--auth-url`, default `auth.` instead of `app.`), and the list
of the OTHER departments' portal return addresses it may send a citizen back to. `middle-layer/department-clients.json` holds the four
client secrets for step 3.

You can run it with placeholder addresses first and run it again when the real ones exist. `--rotate` makes new passwords and
secrets. `--no-discovery-key` leaves the manifests public.

Check it: `python -m unittest scripts/test_gen_demo_data.py` (it loads every seed into a real Postgres if Docker is available).

## 2. Each department server (repeat for revenue, dbt, education, agriculture)

First, once on your laptop: build the portal pages. They are a generated copy of the front end that each department serves at `/portal/`
and the images pick them up, so do this BEFORE building any image (needs Node, or it uses the one in `frontend/node_modules`):

```bash
scripts/build-portal.sh       # builds frontend/ and copies it to departments/<d>/src/main/resources/static/portal/
```

Commit nothing from it (the copies are git-ignored); copy the whole repo folder, or run the script on each server before `up --build`.

On the server (Docker with the compose plugin, and git):

```bash
git clone <your repo URL> samanvay && cd samanvay      # the code
mkdir -p deploy/generated                               # then copy ONLY this department's folder from your laptop:
scp -r deploy/generated/revenue user@server:samanvay/deploy/generated/      # revenue shown; use the matching folder
docker compose -f deploy/departments/revenue/docker-compose.yml \
  --env-file deploy/generated/revenue/revenue.env up -d --build
rm deploy/generated/revenue/seed.sql                    # it holds the citizens' passwords; the database is already seeded
```

The first start builds the image (a few minutes). Then check, from your laptop:

```bash
# the manifest is refused without the discovery credential, and shown with it
curl -s -o /dev/null -w '%{http_code}\n' https://revenue.203.0.113.10.nip.io/.well-known/samanvay/manifest        # 401
python scripts/manifest-fingerprint.py https://revenue.203.0.113.10.nip.io <discovery key from ONBOARDING.md>      # prints the key fingerprint
```

The fingerprint must equal the line `Manifest signing key thumbprint: ...` in the server's log
(`docker compose -f deploy/departments/revenue/docker-compose.yml logs revenue | grep thumbprint`). Note it: you confirm it when you onboard.

For Revenue and Agriculture also check the SFTP pin: `scripts/sftp-hostkey.sh <host> 2223` (Agriculture: 2224) must print the
value in `ONBOARDING.md`.

The department's signing key and its database live in Docker volumes. If you delete the volumes the key changes, and Samanvay
will refuse the manifest until you approve the new fingerprint.

## 3. The middle layer

1. **Its environment.** Append `deploy/generated/middle-layer/departments.env` to the app's environment file (the systemd
   `EnvironmentFile`, or the compose `env_file`). It holds the department credentials (`SAMANVAY_SECRET_...`, base64), the discovery
   credentials, the SFTP pins and the JDBC address. Restart the app after changing it.
2. **The staff console must be served.** Build with the React app bundled in: `./mvnw -Pfrontend -DskipTests package`. It is then at
   `https://<your app address>/app/` (staff pages need the `#`, for example `/app/#/staff/admin/onboarding`).
3. **Keycloak.** Regenerate the realms with your address, and import them:
   `SAMANVAY_EXTRA_ORIGINS=https://app.203.0.113.5.nip.io python keycloak/gen_realms.py`. Build the providers
   (`./mvnw -DskipTests package` puts them in `target/keycloak-providers`) and give the Keycloak container
   `SAMANVAY_DEMO_FIXED_OTP=000000` if you want the staff authenticator code to be `000000` (see below).
   **Import only the staff realm** (`samanvay-staff`): Samanvay has no citizen sign in, and `departments.env` already sets
   `SAMANVAY_CITIZEN_ISSUER_URI` empty so the app does not look for a citizen realm.
4. **The department clients.** Keycloak makes up new client secrets at every import, but each department server is configured with a
   fixed one. Once Keycloak is up, copy `deploy/generated/middle-layer/department-clients.json` to the middle-layer server and run
   `python3 scripts/provision-department-clients.py department-clients.json`. It sets the four secrets (`dept-revenue`, `dept-dbt`,
   `dept-education`, `dept-agriculture`) and proves each works (a token with the right department claim). Run it again after any re-import.
5. **Profile.** Run the app with the `demo` profile as before.

## 4. Onboard the departments, one by one

Sign in to the staff console (`https://<your app address>/app/#/staff/admin/onboarding`) as `dev-admin` (see `CREDENTIALS.md`),
then use **Onboard a department in one go**.
For each department:

1. Paste its base URL and **Review plan**. Nothing changes yet.
2. Check the **manifest signing key fingerprint** against the one you noted. Tick *I confirmed this key fingerprint with the department*.
3. Tick the documents, tick the field matches, **Onboard**. Everything is created as a draft.
4. **Run trial fetch** on each connector: it fetches the department's sample citizen through the real protocol and credentials.
5. Publish the connectors that worked, then the journeys.
6. Open a department's portal (`https://<department address>/portal/`), sign in with a citizen from `CREDENTIALS.md`, start the service,
   press **Log in at** the other departments it needs, give consent with the code, and apply. The staff console's journey page
   (`/app/#/staff/admin/journeys/<code>`, from Catalog) shows whether the journey is connected and working, with its log.

Do Revenue first (it carries four documents), then DBT, Education, Agriculture. The journeys need documents from several departments, so a
journey becomes ready only once all of its departments are onboarded and published.

## 5. The logins

- **Citizens at a department's portal:** mobile number + that department's password, then the code `123456`. See `CREDENTIALS.md`.
  The same code `123456` is asked again when they confirm consent.
- **Citizens on Samanvay itself:** they do not. There is no citizen account or sign up on the middle layer.
- **Staff:** `dev-officer`, `dev-reviewer`, `dev-admin`. The temporary password is `<user>-change-me`; sign in changes it. The
  authenticator code is **`000000`** if Keycloak runs with `SAMANVAY_DEMO_FIXED_OTP=000000` (no authenticator app to set up). Without that variable
  only a real authenticator-app code works, and a first sign-in enrols one.

## 6. What is deliberately demo-only (read before you show it publicly)

- The fixed codes (`123456` at departments, `000000` for staff) are back doors by design. They exist because the data is fake. A real
  department sends a fresh code; a real staff login needs a real authenticator.
- The staff fixed code only exists while the Keycloak server has `SAMANVAY_DEMO_FIXED_OTP` set to six digits, and Keycloak logs a warning
  when it does. Remove the variable and restart to turn it off.
- Keycloak in `start-dev` mode keeps its data in memory. Fine for a short demo, not for anything longer.
- Department passwords are bcrypt-hashed in each database, but `deploy/generated/` holds them in plain text for you. Keep it private.
- There is no lockout or rate limiting on the department logins.

## 7. When something is refused

| You see | It means | Do |
|---|---|---|
| "refused the manifest request (HTTP 401)" and a secret name | the department wants its discovery credential | the value is in `ONBOARDING.md`; it is already in `departments.env`; restart the app |
| "manifest is not signed" | the department is not sending a signature | check the department server runs this version |
| "signing key has changed" | its key file was lost or replaced | confirm the new fingerprint with the department, then approve it |
| "login/key address ... is not on the manifest's own host" | `<DEPT>_PUBLIC_URL` differs from the URL you onboarded from | make them the same host |
| trial fetch fails on SFTP | the pinned host key differs | `scripts/sftp-hostkey.sh` must match `ONBOARDING.md` |
| a portal says "Samanvay did not accept this department's request" | the department's client secret in Keycloak differs from its server's | run `scripts/provision-department-clients.py` on the middle layer |
| a portal says the other department's sign in link is "not valid" | its allowed return addresses do not list this portal | regenerate; each env lists the other three portals' `/portal/callback` |
| consent is refused ("could not be accepted") | the department is not onboarded yet, or its key file changed | onboard it (the key it signs consent with is its manifest key) |
| trial fetch fails on JDBC | Samanvay cannot reach 5434, or the role password differs | check the firewall rule for the middle layer's address |
