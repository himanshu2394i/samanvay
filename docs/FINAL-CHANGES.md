# Samanvay — final changes log

Running record of every change we decide on, in the order we decide it.
Nothing here is a PR yet; all PRs are opened together at the end.

- Baseline: `main` @ `cbe145f` (PR #82), worktree `D:\Devpost\SIH26129-main`
- Status legend: **DECIDED** (agreed, not built) · **BUILT** · **OPEN** (question to settle)

## 0. Current design on one page (read this first; details in the numbered sections)

1. **Four separate department services** (Revenue, DBT, Education, Agriculture), each with its own data,
   login, protocols and manifest (§1).
2. **One manifest per department** at `/.well-known/samanvay/manifest`, extended to carry everything
   needed to onboard from just a base URL: per-document protocol details, security parameters,
   how each document is keyed, identity-proof info, journeys (§2, §10, §12, §13).
3. **Onboarding in one go:** URL -> review a plan -> tick documents -> approve the proposed field matches -> onboard (all drafts, one
   transaction) -> the operator does the listed steps (provision secrets, pin SFTP/JDBC hosts, issue the portal's caller
   credential) -> config test + probe -> publish (§3, §10; built in section 15, task 13).
4. **Journey onboarding:** department changes its journey code to call Samanvay; Samanvay issues the
   department a caller credential; readiness decides "connected" (§4).
5. **Identity linking is per department, via that department's own login.** The login returns ONE
   person ID for that department as a signed assertion; Samanvay stores the link (§8, §11).
6. **Fetching a document** = person ID + Samanvay's credentials over the declared protocol. Whether one
   person ID unlocks all documents or each document has its own key is handled by one optional
   `resolve` step, declared per document by the department, never assumed (§12).
7. **Security parameters are protocol-specific** and declared in the manifest; values are exchanged
   out-of-band and kept in the SecretStore (§13).
8. **Documents never move into Samanvay.** DigiLocker is only an optional document source, not the link (§7, §8).

**Sections superseded by later ones:** §5 steps 1-3 (see §8, §11); §6's last "what this means" bullets (see §8).

**STATUS (2026-10-04): the department work (sections 1-15) and the department-journeys redesign (Phase 8 in section 15) are built and
deployed on branch `feat/department-journeys`; no PR yet.** The line below is the 2026-10-01 status, kept as history.

**STATUS (2026-10-01): built on branch `feat/dept-revenue`, uncommitted, no PR.** Everything above is implemented and tested
(see section 15 for the log and section 14 for what maps to what). The gaps this file once listed are closed: REST/SOAP now apply the
declared credentials; REST honours path inputs; the optional `resolve` step exists; connectors bind `link.personId`; credentials are
multi-parameter; the manifest carries `access`, `auth`, `lookup`, `identity`. What is NOT done is listed under "Deferred" at the end.

---

## 1. Split the departments into real, separate services — DECIDED

**Today:** one `simulators/` app plus `docker/department-db` and `docker/department-sftp`.
The "departments" are really one fake service (`SandboxDepartmentController`, manifest
code `SANDBOX`) that serves several document types.

**Change:** four separate department folders, each its own deployable service with its
own data store, its own port/hostname, and its own published manifest.

```
departments/
  dept1/   dept2/   dept3/   dept4/
```

- Each department stores its own people's documents in its own backend DB. Samanvay never holds them.
- Each speaks 1 or 2 protocols (REST / SOAP / SFTP / JDBC — the middle layer already has all four).
- **DECIDED names:** dept1 = Revenue, dept2 = DBT, dept3 = Education, dept4 = Agriculture.
- **DECIDED protocols** (confirmed; all four already supported by the middle layer):

  | Dept | Documents | Protocol 1 | Protocol 2 |
  |---|---|---|---|
  | 1 Revenue | income, caste, domicile, 7/12 | REST | SFTP (batch) |
  | 2 DBT | bank account | REST | - |
  | 3 Education | marks | SOAP | - |
  | 4 Agriculture | crop/farmer record | JDBC | SFTP (batch) |

## 2. Every department publishes a manifest — DECIDED (the contract exists; it grows)

Each department exposes one base URL with `GET /.well-known/samanvay/manifest`
(already implemented in `SamanvayManifestController`, parsed by `DepartmentManifest`).

It already says: department identity, documents (category, protocol, path, inputs, fields,
which are sensitive), and journeys (code, SLA, consent purpose, required document categories).

Additions we want:
- Per journey: exactly which documents it expects (already `requiredCategories`) — keep.
- Per journey: whether the middle layer is currently connected to it, and its audit/log link.
  (This is middle-layer-side state shown beside the manifest, not something the department publishes.)

## 3. Onboarding a department (middle-layer side) — DECIDED

Order of work:

1. **One-time: seed the central schema** in the middle layer for each document type
   (every field/parameter) - DECIDED to stay seeded (migration/seed files), not an admin screen.
   Must be done before the department can be onboarded; reused by all departments.
2. **Enter the department's base URL.** Samanvay fetches the manifest and shows:
   documents + their structure, journeys + what each journey expects, and which journeys
   are already connected to the middle layer.
3. **Pick which documents to onboard** from that list.
4. **Mapping:** department fields → central schema (OpenAPI-style suggestions, admin confirms; machines propose, humans dispose).
5. **Protocol/connector setup** (REST/SOAP/SFTP/JDBC + how to authenticate to that source).
6. **Test (live probe) → Publish.**

Already built: steps 2–6 largely exist (onboarding wizard, manifest import, live probe,
readiness computed from connector availability). Step 1 stays seeded, as today.

## 4. Onboarding a journey — DECIDED (answers recorded; see "Open" at the bottom)

A journey can only be onboarded after its department is onboarded.
Samanvay already knows the journey from the manifest. To go live:

- **Department side (code change):** the department's own journey flow is changed to call
  Samanvay for the documents it needs, instead of asking the citizen to upload/fetch them itself.
- **Middle-layer side:** the journey is marked connected once every document it requires has a
  published, mapped connector (readiness). From then on its runs, consent checks and audit rows show under that journey.
- **Credentials:** yes, one is needed — the *journey's caller* (the department's portal) must
  prove who it is when it calls Samanvay. Today that is a Keycloak service account with
  `ROLE_DEPARTMENT` + one `source:<code>` scope per data source (e.g. `dept-scholarship-dev`).
  Direction is the reverse of the connector credential:
  - connector credential = Samanvay → department (to fetch documents)
  - caller credential = department portal → Samanvay (to start journeys / request consent)
- After that, citizens use the department's journey as normal; the flow runs through Samanvay underneath.

## 5. Same person across departments (identity resolution) — DECIDED (steps 1-3 superseded by §8 and §11)

Flow, as understood:

1. Citizen logs into the department portal (their one login).
2. Opens a journey. Samanvay checks which departments the journey needs documents from and
   which of them are not yet linked for this citizen (this is the existing
   `ConnectAccounts` / `DepartmentLinkNeed` flow).
3. For each unlinked department, the citizen proves they are the same person there
   (logs into that department). Samanvay stores the **link** (citizen ↔ that department's local ID).
4. Next time: citizen logs into the department, opens the journey, **only gives consent**.
   No re-linking.

Rules kept: the link is stored, the documents are not; the link is auditable; ambiguous
matches go to the human review queue.

---

## 6. Research: how Maharashtra departments do login / sign-up — FINDINGS

Sources are the portals' own pages where I could reach them, otherwise secondary guides
(so treat details as "typical", verify before quoting to judges).

| Portal (department) | Journeys | Sign-up | Login |
|---|---|---|---|
| **Aaple Sarkar** (Mahaonline; 1000+ services, 33 depts — Revenue income/caste/domicile certs) | Certificates, licences | Pick district + mobile → OTP; **Aadhaar-OTP or manual details**; then create username + password | Username + password + captcha + district |
| **MahaDBT** (scholarships + farmer schemes) | Scholarship, farmer subsidy | Aadhaar + OTP, personal + bank details, create username/password, verify mobile + email by OTP | User ID + password **or** Aadhaar number with OTP/biometric |
| **Mahabhulekh** (Revenue/land, 7/12) | Land record lookup | "New user registration" form | Registered login (many lookups are open search by district/taluka/survey no.) |
| **MH Farmer Registry / AgriStack** (Agriculture) | Farmer ID (needed for farm schemes) | Aadhaar + OTP, then add land plots from 7/12 | Aadhaar OTP |

Pattern: **every department has its own account, and Aadhaar-OTP is the common anchor** —
but each portal then issues its own username/password, so a citizen has 3–4 unrelated logins.
That is the exact problem Samanvay's linking solves.

**What this means for "proving identity at another department" (§5):**
- Real departments have no shared SSO and no common login API we can call. So the proof must
  be one of: (a) the citizen logs in at that department through its own login (department IdP
  brokering — what the `DEPT_IDP` proof kind is for), (b) Aadhaar-OTP style one-time proof,
  (c) a DigiLocker consent.
- For our four departments we **build each one's login to look like the real one** (own
  user ID + password and/or Aadhaar-style OTP), then link via that login.

## 7. What DigiLocker is for in this project — FINDINGS (from code + docs)

DigiLocker is **modelled as a labelled mock/sandbox only; the live service is not connected**.
It is used in exactly two places:

1. **Identity-link proof** (`DigiLockerLinkProofProvider`, `MockDigiLockerClient`): one of the
   ways a citizen proves "this department account is mine". The mock accepts the code `sandbox`.
2. **Issued-documents picker** (`IssuedDocumentsImpl`, `GET /api/connector/issued-documents`):
   the scholarship portal lists a citizen's issued certificates (income, caste, 7/12, marks…)
   as if from a DigiLocker locker, with a fake preview.

Our own docs say: DigiLocker is *not* login, *not* the source of truth, *not* Samanvay's
consent store; certificates stay at the issuer. Live DigiLocker needs a MeitY partner account
(listed as future scope).

Real DigiLocker: OAuth2 authorization-code flow — the citizen signs in with Aadhaar-OTP, consents
per document, the requester gets a token to read issued documents. Two API families:
Issuer (departments push documents in) and Requester (apps read with consent).

DECIDED (via §8): linking uses each department's own login; DigiLocker stays only an optional
document source (and the existing mock picker), not a link proof.

---

## 8. Where documents really live, and how linking works — DECIDED (supersedes §5 step 3, §6 last bullets)

**Where documents live (reality):**
- Each document lives in the **issuing authority's own repository** (certificates in the state
  e-District/Aaple Sarkar backend, 7/12 in the land-records system, marks at the board, bank
  account at the bank/NPCI-DBT). Departments *do* hold them — just not necessarily in a
  simple "department DB" a portal exposes.
- **DigiLocker does not own them either.** Under DigiLocker's Issuer API, the issuer keeps the
  document and gives DigiLocker a way to pull it by Aadhaar/identifier; DigiLocker shows the
  citizen a link/copy in their "issued documents". Aaple Sarkar publicly announced income,
  caste and domicile certificates are available in DigiLocker.
- So Samanvay's rule stays: the document stays at the issuer; we move it only on consent.

**The user's observation (accepted):**
- Redirecting a citizen to log into 4 departments one by one is too heavy.
- Several departments already anchor on **Aadhaar + OTP**. One Aadhaar-OTP proof can therefore
  establish identity for *all* Aadhaar-keyed departments at once.
- DigiLocker alone is only "fetch documents with a login each time" — a document source, not a
  link. Correct. Linking is what lets the next journey need only consent.

**DECIDED model: linking is per department, using that department's own login.**
(Replaces the earlier "two-key" proposal. We do NOT hardcode "Aadhaar-keyed vs local-ID-keyed".)

- A citizen links a department by logging into **that department's own login** once, whatever
  method it uses (user ID + password, Aadhaar-OTP, mobile-OTP, a future third method...).
- After a successful login the department tells Samanvay "this is local person X"; Samanvay
  saves the link (citizen <-> department local ID) in its own DB. Next time: consent only.
- The login method is a property of the department, not of Samanvay. Adding a department with a
  new login style must not require a Samanvay code change - it plugs in as another proof
  provider (the existing `LinkProofProvider` / `DEPT_IDP` idea).
- Each department's link is independent: linking Revenue does not link DBT. Shared methods
  (e.g. two departments that both use Aadhaar-OTP) may *offer* a shortcut later, but that is an
  optimisation, not the model.

**Needs department tech teams.** Samanvay cannot do this alone. Each department must expose
a way to confirm a login (e.g. an OIDC/OAuth sign-in or a signed "this user logged in" assertion
returning their local ID). Until a real department does, we simulate it: each of our four
departments gets its own login page and assertion endpoint that behaves like the real portal's.

**Still true:** documents stay at the issuer; DigiLocker is an optional document source, not the
link; never store a raw Aadhaar number (store a reference/hash) - verify the legal detail later.

Open implementation questions (not decided, do not build yet):
- The exact contract a department implements to return the "logged-in as local ID X" assertion.
- Whether the simulated department logins share one fake IdP or run inside each department service.

---

## 9. How "choosing a protocol" really plugs a department in — FINDINGS (from code)

**Many protocols per department is already supported.** `protocol` lives on the *data source*,
not the department (`catalog_data_source.protocol` in REST/SOAP/SFTP_CSV/JDBC; many sources per
department; each connector binds ONE document category to ONE source). So Revenue can have a REST
source for income and an SFTP source for 7/12. No gap in the data model.
To verify: that the manifest-import UI creates one source per distinct protocol in the manifest
(it reads a protocol per document, so it should - not tested yet).

**Picking "REST" does not plug anything in.** The protocol only selects the *transport adapter*
(how to talk). Being plugged in needs four more things, per document:

| Needed | Where it comes from |
|---|---|
| Where (host + path/operation) | Manifest `documents[].path`, `method`, or entered by the admin |
| What to send (inputs: ration card no., student ID...) | Manifest `documents[].inputs` |
| What comes back (fields) | Manifest `documents[].fields`, mapped to the central schema |
| How to authenticate to the department | Admin sets auth type + secret reference on the data source |

The adapters are generic: `RestAdapter` just calls `host + endpoint` with the bound inputs and
parses JSON; nothing in it knows a specific department. The department-specific knowledge lives
in the connector row + field mapping - i.e. in data, not code.

**So the honest answer to "we don't know the endpoints or the data":** we don't - the department
must tell us. Either (a) it publishes the manifest (our approach), or (b) a human types it in
from the department's API documentation. Without one of those, no protocol choice can work.

**Gaps found (to fix or accept):**
1. REST adapter is **GET + query-string only**. The manifest allows `method` and inputs `in`
   path/body/query - POST or body inputs would be ignored. SOAP already uses a template.
2. REST adapter shows no auth handling in itself; confirm where the data source's auth config
   is applied for each protocol before claiming auth works end to end.
3. Response parsing assumes JSON; department-specific quirks (envelopes, pagination, error shapes)
   have no place to be described in the manifest yet.

---

## 10. One-go onboarding: extend the manifest, do not add more endpoints — DECIDED (design)

**Decision:** keep ONE discovery endpoint (`/.well-known/samanvay/manifest`) and make it rich
enough that onboarding needs only the base URL. Today it already carries per document:
category, title, protocol, method, path, inputs, fields (+sensitive flag), and journeys.
We add a per-document `access` block with the protocol-specific details, and a few top-level items.

**Manifest v2 additions (sketch):**
```
documents[]:
  access:                                  # protocol-specific, all non-secret
    REST : { baseUrl, method, path, inputsIn: query|path|body, responseRoot, errorShape }
    SOAP : { wsdlUrl | endpoint + soapAction + requestTemplate, namespace }
    SFTP : { host, port, directory, fileNamePattern, format: CSV, columns[], schedule, hostKeyFingerprint }
    JDBC : { host, port, db, readOnlyView, keyColumn }      # a VIEW name - never SQL from the department
  auth: { scheme: NONE | API_KEY | OAUTH2_CLIENT | BASIC | SSH_KEY | DB_USER, docsUrl }   # how, never the secret
  alternates: [ another access block for the same document ]  # e.g. REST live + SFTP bulk
  sample: { request, response }            # synthetic example for the mapping/test step
top level:
  manifestVersion, contact (tech team), identityProof: { how a citizen proves login here, see §8 }
  changedAt / etag                         # lets Samanvay detect a department changing its structure
```
If a department already has an OpenAPI/WSDL, `access` may simply link it and Samanvay imports it.

**Onboarding screen, start to finish (one go):**
1. Admin pastes base URL -> Samanvay fetches the manifest.
2. Shows every document with its protocol, endpoint, inputs, fields (protocol pre-selected from the
   manifest; the admin picks among `alternates` only if the department offers several).
3. Admin ticks which documents to onboard.
4. Field mapping to the central schema is **proposed automatically**; admin confirms or edits.
5. **Credentials are the one thing never published.** The plan lists, per source, the SecretStore key and the parameter names;
   the operator provisions the values the department gave them out of band (the app cannot write secrets, and they never pass
   through the browser). *(Built that way: see section 15, task 10 findings and task 13.)*
6. Live test per document -> Publish. Journeys listed in the manifest appear with readiness (§4).

**Rules that keep it safe:**
- The manifest is **untrusted input**: the admin confirms before anything goes live; the existing
  host allow-list (`DataSourceHostPolicy`) still applies to every host it names.
- Never accept SQL or code from a manifest; JDBC uses a named read-only view and a fixed query built by us
  (`JdbcSqlGuard` exists for this).
- No secrets, and no citizen values, in the manifest - capability metadata only.

**Still manual, cannot be discovered:** credentials; the central schema for a brand-new document
type (defined once, per §3 step 1); the department-side journey code change (§4).

Build impact: widen `DepartmentManifest` + `SamanvayManifestController`, make onboarding create one data
source per distinct protocol/host in the manifest, and make the REST adapter honour `method` and
`inputsIn` (gap 1 in §9). Not started.

---

## 11. After the department login: how Samanvay learns the person's local ID — DECIDED (design)

**The department tells us; we never look into its DB.** The login happens at the department; its
login system hands Samanvay a signed statement ("this person is local ID X"). Samanvay verifies the
signature and stores only the link.

Flow:
1. Citizen is sent to the department's login page (department's own method: password / OTP / ...).
2. After a successful login the department returns a **signed assertion** (OIDC-style ID token)
   containing: department code, the person ID (its own stable ID for that person at that
   department), login time, and optionally name/DOB claims.
3. Samanvay verifies: signature (against the department's public keys, published in the manifest
   `identityProof` block), audience, expiry, and **freshness** (login time recent), and that the
   assertion belongs to the citizen who is currently signed in to Samanvay.
4. Samanvay writes one row in `identity_link`: citizen <-> (department, person ID), with provenance
   and confidence. Unique per (department, id type, id), so one department person cannot be
   attached to two Samanvay citizens.
5. Later, when a journey needs a document, the connector sends that **person ID** (or the document
   key from the optional `resolve` step, see §12) plus Samanvay's credentials. No new login;
   consent only.

**Already built (do not rebuild):** `DepartmentBrokerLinkProofProvider` (proof kind `DEPT_IDP`)
does steps 3-4 using Keycloak brokering: it accepts a token only if signature/issuer/audience/expiry
pass, `dept_code`, `dept_local_id_type`, `dept_local_id` claims match what is being linked, and
`auth_time` is under 10 minutes old. `identity_link` already stores department_code,
local_id_type, local_id_token, provenance, confidence.

**What changes for the four simulated departments:** each needs its own login that issues this
assertion (today one Keycloak "dept-idp" broker stands in for all). The ID type it returns is
declared in its manifest `identity` block.

**Note:** if the person ID a login returns differs from what its document API expects, the
department either returns the right ID or publishes `resolve` (§12). To settle with each department.

---

## 12. Department identity schema: login ID -> protocol input -> document — DECIDED (revised)

**Correction (user):** the login returns ONE unique ID of the person *at that department*
(`personId`), not ration-card-style document numbers. To fetch, Samanvay passes **both**:
- the person's department ID (`personId`), and
- **Samanvay's own credentials** (its service identity for that department).
The department checks that the caller is an authorised Samanvay and that the person exists.

**Assumption we must not hide:** "one person ID unlocks all of that person's documents in the
department" is only an assumption. Each department may instead hold a separate key per document
(e.g. a certificate number per certificate). Only the department's tech team can say which.
So the manifest must **declare** it (via the optional `resolve`) - we do not guess.

**One mechanism, not two (decided):** implement the general case (B) only. Case A is just B where the
document key equals the person ID.

```
documents[].lookup:   (optional)
  resolve: { path, method }   # present  -> Samanvay first asks "personId + credentials -> this person's
                              #             document key(s)", then fetches the document with that key
                              # absent   -> the document key IS the personId (a department whose one ID unlocks everything)
```
- If a person has several keys for a document (two certificates), the journey/officer picks one, or the
  department marks the latest.
- A department whose one ID unlocks everything just omits `resolve`; no extra call, no second code path
  in the manifest. A department with per-document keys publishes `resolve`.
- Onboarding shows which documents use `resolve`, so nothing is assumed silently.

**Chain:** department login -> assertion with `personId` -> `identity_link` (one row per department) ->
consent -> [optional `resolve` step] -> connector sends document key + Samanvay credentials -> document.

**Already in code:** link -> input binding (`inputs: [{name, from: link.x}]`, `bindInputs`) exists;
Samanvay-to-department credentials exist per data source (`authType`, `authConfigRef`).

**Gaps:** (1) journey step passes the link as the fixed key `localIdToken` only - fine when `resolve` is absent
(one ID per department), but the manifest input should name it explicitly instead of relying on that
fixed key; (2) no optional `resolve` step exists yet; (3) the credential is per data source, so a
department with two sources (REST + SFTP) needs the credential set for each.

Build impact (not started): add `lookup` to the manifest, an optional `resolve` step in the connector runtime,
and show which documents use it in onboarding. Our simulated departments mix both (Revenue uses `resolve`).

---

## 13. Protocols need more than one secret: the security parameters — DECIDED (design) + FINDINGS

**Assumption corrected:** "one credential per source" is too small. Each protocol/department can
require several security parameters, e.g.:

| Protocol | Typical security requirements |
|---|---|
| REST | API key in a header or query; OAuth2 client-id + secret -> bearer token; request signing (HMAC) with a timestamp; mutual TLS client certificate; extra required headers (client ID, request ID); caller IP allow-listing |
| SOAP | WS-Security username/password or signed envelope; client certificate |
| SFTP | username + password **or** SSH private key; pinned host-key fingerprint |
| JDBC | DB user + password; TLS required; read-only account |

**What we do:**
- The manifest `auth` block declares the **scheme and every parameter the department requires**
  (name, where it goes: header / query / body / certificate / signature, and whether it is a secret).
  It never contains values.
- Onboarding turns that into a form: the admin fills in only the values the department gave them
  (out-of-band), and each secret goes into the SecretStore. The non-secret parts (header names, token
  URL, scopes) come from the manifest.
- The adapter for each protocol applies exactly the declared scheme on every call.
- Departments also commonly restrict by caller IP / certificate: onboarding shows Samanvay's
  outbound IP / public cert for the department to allow-list.

**FINDINGS in today's code (honest):**
1. A source's credential is a single `keyId:keySecret` pair (`SourceCredentials`, SecretStore key
   `source-<code>-credential`). Enough for JDBC and SFTP username/password and the bank client.
2. `RestAdapter` and `SoapAdapter` do **not apply any credential** to the call; the data source's
   `authType` / `authConfigRef` are stored but not consumed there. So REST/SOAP departments are
   called unauthenticated today. This is a real gap and must be fixed before claiming a department is
   "securely plugged in".
3. No support yet for OAuth2 token fetch, HMAC signing, extra headers or client certificates.
   SFTP key-based login and host-key pinning: not verified.

**Proposed scheme per simulated department** (so the demo proves several real-world setups; unconfirmed):

| Dept | Protocols | Security enforced |
|---|---|---|
| 1 Revenue | REST + SFTP | API key header + IP allow-list; SFTP SSH key |
| 2 DBT | REST | OAuth2 client credentials (token) |
| 3 Education | SOAP | WS-Security username token |
| 4 Agriculture | JDBC + SFTP | DB user/password over TLS, read-only view; SFTP password + pinned host key |

Build impact (not started): richer `auth` in the manifest; a credential model with several named
parameters; REST/SOAP adapters that apply API-key / OAuth2 client-credentials / HMAC / mTLS / extra
headers; our four simulated departments each enforce a different scheme so the demo proves it.

## 14. Build order and outcome

1. REST/SOAP adapters apply the data source's credentials (§13) - **BUILT** (Part B task 10).
2. Manifest v2 contract (`access`, `auth`, `lookup`, `identity`) + parser (§10-§13) - **BUILT** (tasks 9, 13a).
3. Connector runtime: REST path inputs; multi-parameter credentials; optional `resolve` step (§9, §12, §13) - **BUILT** (tasks 10, 11).
   Not built: REST POST/body inputs, SOAPAction, HMAC signing, client certificates, SFTP key login.
4. Four department services under `departments/` with own data, login, manifest, security scheme (§1, §13) - **BUILT** (Part A).
5. Department login -> signed assertion -> link (§11) - **BUILT** (task 7 department side, task 12 core side, task 15 citizen pages).
6. One-go onboarding (§10) - **BUILT** (task 13: planner, service, endpoints, staff-console screen).
7. Retire the old single `SANDBOX` simulator - **DELIBERATELY NOT DELETED**: replaced by a cutover runbook
   (`docs/runbooks/department-cutover.md`), because a deployed demo depends on the old sources and a deletion migration is
   irreversible. New connectors are drafts until published; version precedence makes publishing the moment of cutover.

---

## 15. BUILD LOG (branch `feat/dept-revenue`, uncommitted)

### Slice 1 - Revenue department, REST face - BUILT (7 tests pass)
Location: `departments/revenue/` (standalone Spring Boot app, port 8091, own pom, ArchUnit rule that it never
touches main-app code; same pattern as `simulators/`). Run: `./mvnw -f departments/revenue/pom.xml spring-boot:run`.

- Own fake records: 2 people (`RV-1001` with TWO income certificates, `RV-1002`), income / caste / domicile
  certificates, each with its own key (so Revenue genuinely needs `resolve`). In-memory seed (`RevenueRecords`).
- **Manifest v2** at `/.well-known/samanvay/manifest`: v1 fields unchanged + `identity.personIdType`, per-document
  `lookup.resolve` and `auth` (scheme API_KEY, parameter `X-Api-Key`, never a value). `journeys` is empty for now.
- **Resolve:** `GET /v1/persons/{personId}/documents?type=...` -> the person's certificate keys, newest flagged.
  404 for unknown person; empty list for a type they lack.
- **Fetch:** `GET /v1/{income|caste|domicile}/{key}`; a key of the wrong kind is 404.
- **Security:** every `/v1` call needs `X-Api-Key` (constant-time compare), else 401; the manifest is public.
  Dev key via `REVENUE_API_KEY`, default only for local use.
- Tests: written first, failed (5/5, endpoints missing), then passed. `RevenueDepartmentTest` (5) + boundary (2).

Not done yet (next slices): SFTP face for 7/12; IP allow-list; Revenue login + assertion; Revenue's journeys;
compose/deploy wiring; verifying the existing onboarding parser accepts this manifest end to end (the parser is
documented as lenient, but untested against v2); the main app's REST adapter still sends no API key and cannot
call `resolve` (gaps in sections 9, 12, 13) - Revenue cannot be onboarded end to end until those are built.

### Slices 2-6 - department services (Part A tasks 1-6) - BUILT
| Dept | Dir / port | Protocols + security | Tests |
|---|---|---|---|
| 1 Revenue | `departments/revenue` :8091 | REST (API key + IP allow-list, `resolve`) + SFTP 7/12 CSV (`revenue-sftp` compose :2223) | 14 |
| 2 DBT | `departments/dbt` :8092 | REST + OAuth2 client credentials (`/oauth/token`, bearer, expiry); no `resolve` | 9 |
| 3 Education | `departments/education` :8093 | SOAP 1.1 + WS-Security UsernameToken (faults, DOCTYPE rejected); no `resolve` | 8 |
| 4 Agriculture | `departments/agriculture` :8094 | JDBC read-only VIEW (`db/init.sql`, role `agri_ro`) + SFTP crop CSV; manifest-only service | 9 |

Each publishes a v2 manifest (`identity.personIdType`, per-document `auth`/`access`/`lookup`) and one journey with
`requiredCategories` across departments: Revenue `INCOME_CERT_RENEWAL`, DBT `DBT_ACCOUNT_SEEDING`, Education
`POST_MATRIC_SCHOLARSHIP` (income+caste from Revenue, marks, bank from DBT), Agriculture `FARMER_SUBSIDY`
(farmer record, 7/12 from Revenue, bank from DBT).

Deviations / notes: Revenue's SFTP uses PASSWORD login (an SSH key would mean committing a private key);
SFTP host-key fingerprint is published only when `*_SFTP_HOSTKEY` is set (compose gives no stable key yet);
Agriculture DB script is checked by text tests only - run against a real Postgres in Task 8.
Findings for core (Part B): core's SOAP adapter sends only the rendered template (no security header);
core's SFTP adapter hardcodes one ID column; core's JDBC path takes SQL from the connector row (§10 wants a view
name + fixed query built by core); journeys' `consentPurpose` values need seeding in core's purpose table.

### Slices 7-8 - login assertion + deployables (Part A tasks 7-8) - BUILT
- **Login assertion contract v1:** `docs/contracts/login-assertion.md` (ES256 JWT; claims iss/aud/sub=personId/
  person_id_type/dept_code/auth_time/iat/exp<=5min/jti/nonce/state; JWKS; redirect flow with `return_to` allow-list
  and required `state`+`nonce`; what Samanvay must verify). Closes the "login assertion contract" open item.
- **Each department has its own login that issues it** (own style, as in section 6): Revenue user ID + password,
  DBT mobile + one-time code (fixed demo OTP), Education seat number + date of birth, Agriculture user ID + password.
  9 tests each (redirect carries a verifiable assertion, wrong creds refused, no open redirect, state/nonce required,
  page escapes reflected values, unique jti, manifest `identity` block with loginUrl/jwksUrl/assertionIssuer).
  Test totals: Revenue 23, DBT 18, Education 17, Agriculture 18.
- **Compose + Dockerfiles** for all four, plus `revenue-sftp`, `agriculture-db`, `agriculture-sftp`; `departments/README.md`.
  Verified for real: Agriculture `init.sql` on a throwaway Postgres (role `agri_ro` reads the view; base table and
  writes denied; `internal_notes` hidden); Revenue image built and run (manifest served, 401 without key, document with key).
- ponytail notes: assertion key regenerates at startup; demo users in config with plaintext dev passwords; DBT OTP is a
  fixed demo code (real flow is challenge-response); no lockout/throttling.

### Part B tasks 9-11 - core: manifest v2, secured adapters, `resolve` - BUILT
**Task 9 - manifest v2 in core.** `DepartmentManifest` now carries `identity`, per-document `lookup`/`auth`/`access`
(soap/sftp/jdbc). Parsing is lenient (unknown fields ignored, v1 manifests still parse). Fixtures
`src/test/resources/manifests/*.json` are the REAL output of the four departments (`scripts/capture-department-manifests.sh`
regenerates them). 6 tests.

**Task 10 - credentials + secured adapters (closes the "REST/SOAP send no credentials" gap).**
- Data model: V200 adds `catalog_data_source.auth_spec` (the manifest's non-secret `auth` block: scheme, parameter
  names/places, token URL, scopes, password type). `AuthSpec` reads it; with no spec the stored `auth_type` decides, so every
  existing source (all `NONE`) behaves exactly as before. `DataSourceDraft`/`DataSourceDefinition`/`AdapterRequest` gained the
  fields with backward-compatible constructors.
- Secret VALUES are looked up by parameter name: `SourceCredentials.params(...)` reads a JSON object of strings from the
  SecretStore (the old `keyId:keySecret` still works and is exposed as `username`/`password`).
- REST (`RestAuth`): API key in header or query, HTTP Basic, OAuth2 client credentials (token fetched from a path on the
  department's own host, cached per source, refetched at expiry; tokenUrl must be a path, not an absolute URL). Path inputs
  `{key}` are substituted URL-encoded; remaining inputs go in the query. CR/LF in a credential value is refused (no header
  injection). Missing credential => error naming source + parameter + SecretStore key, never a value, and no call is made.
- SOAP (`SoapSecurity`): WS-Security UsernameToken (PasswordText) inserted into the SOAP header (empty / existing / missing
  Header all handled; values XML-escaped).
- SFTP: key column is declared by the connector (`key_column`), not hardcoded. JDBC: a connector with no SQL builds the one
  fixed `SELECT * FROM <view> WHERE <key> = :<key>` from a published view + key column; identifiers must be plain
  (`[A-Za-z_][A-Za-z0-9_]*`), the value is bound, an explicit SQL template still wins (existing connectors unchanged).
- Tests added: AuthSpec/credentials 11, REST auth 13, SOAP auth 8, SFTP key column 3, JDBC view 8, data-source auth spec IT 2.
  Existing JDBC/SFTP/SOAP/REST/bank tests still pass.

**Task 11 - optional `resolve` step in the runtime.** A connector capability may declare
`resolve:{path,list_field,key_field,select(latest|first),latest_field,into}`. The runtime asks the department first (sending
only the inputs the path names), picks one key, binds it (`into`), then fetches the document; both calls are one
resilience/deadline exchange (counted once in ops metrics). No documents => `NotFound`, document call not made. Without
`resolve`, behaviour is unchanged. `into` may not overwrite an input the connector already binds. 10 tests. REST only.

**Findings / honest limits (carry into later tasks):**
- SFTP and JDBC hosts, ports, host-key pins and JDBC URLs are OPERATOR-CONFIGURED (`samanvay.sources.sftp|jdbc.sources.*`),
  deliberately not taken from a manifest, so a manifest cannot redirect Samanvay. Onboarding must therefore SHOW the manifest's
  host/port/fingerprint for the operator to pin, not apply them itself (Task 13).
- Secrets are provisioned into the SecretStore out of band (env/file), which is read-only for the app. So onboarding shows
  the parameter names and the SecretStore key to provision; it does NOT collect secret values in the browser (safer; this
  replaces the "admin enters credentials in the form" wording of section 10).
- Not done: POST/body inputs for REST, SOAPAction header, HMAC signing, mTLS, SFTP SSH-key login, OAuth2 over non-REST.
- `discover()` refuses localhost/private hosts (SSRF guard), so the local department stand-ins cannot be onboarded in dev
  until Task 13 adds a dev-only allow-list.
- `StandaloneDepartmentServiceTest` errors on Windows (it launches the `mvnw` shell script via CreateProcess, error 193):
  pre-existing, environmental, unrelated to these changes.

### Part B task 12 - department login proof + explicit link keys - BUILT (core)
- **Department identity config** persisted per department (V201 `catalog_department.identity_spec`): person-ID type, login URL,
  keys URL, assertion issuer. `DepartmentCatalog.identity(code)`; `DepartmentDraft` carries it. 4 tests (IT).
- **New link proof kind `DEPT_ASSERTION`** (`DepartmentAssertionLinkProofProvider`): verifies the department's ES256 assertion
  against its published keys (unknown `kid` => one forced refetch for rotation), checks iss/aud/dept_code/person_id_type,
  `exp-iat <= 5 min`, recent `auth_time`, that `state`+`nonce` are the ones issued to THIS citizen for THIS department, and consumes
  them (single use = replay protection; consumed last so a bad assertion cannot burn a pending login). Rejects unsigned/HMAC
  tokens. The person ID is the assertion `sub`; a `localId` in the request, if given, must equal it. Key-fetch failure = generic
  refusal. 18 tests with real ES256 signatures.
- **`JdbcDepartmentLoginStates`** (V202 `identity_dept_login_state`): one-time, expiring, citizen- and department-bound; consume
  is one atomic conditional UPDATE (8-thread race test: exactly one winner). 6 tests (IT).
- **`HttpJwksSource`**: http(s) only, no redirects, 64 KB cap, timeout, cached, forced refresh at most once per 30 s per URL, only
  public keys kept (secret/private material stripped), private/loopback hosts refused unless
  `samanvay.identity.department-assertion.allow-private-hosts` (true only in dev/demo yml). 9 tests.
- **API:** `POST /api/identity/department-login {citizenId, departmentCode, returnTo}` -> `{loginUrl}` (the department login URL with
  `return_to`, `state`, `nonce`); `returnTo` must start with an allow-listed prefix
  (`samanvay.identity.department-assertion.allowed-return-prefixes`, EMPTY = refuse all). `ConnectAccounts`/`DepartmentLinkNeed`
  now say `departmentLoginAvailable`. 7 service tests. The existing `POST /api/identity/links` accepts provider `DEPT_ASSERTION`
  with the assertion as `proof` (localId optional).
- **Explicit link keys:** connectors can bind `from: link.personId` / `link.localIdType` (the old `link.localIdToken` still works).
  2 tests.
- Contract doc updated: replay protection is the one-time `state`.
- NOT built yet: the browser callback page that receives `?assertion=..&state=..` and POSTs it to `/api/identity/links`
  (a portal/SPA front-end piece, planned with Task 13/15); the per-department "log in" button in the connect-accounts UI.

### Part B tasks 13-14 backend - one-go onboarding + seeded central schema - BUILT
**Department changes made on the way (found while wiring core):** categories aligned to the catalog's existing names
(`LAND_RECORD_7_12`->`LAND_PARCEL`, `CROP_SOWING`->`CROP_RECORD`); Agriculture's journey now equals the existing `FARMER_SUBSIDY`
(LAND_PARCEL/REVENUE, CROP_RECORD/AGRICULTURE, BANK_ACCOUNT/DBT, prefix FAR, 96 h, purpose FARMER_SUBSIDY); Revenue's `resolve`
now states its `query` (`type=<category>`) and the answer shape (`listField`, `keyField`, `latestField`) so onboarding needs no
guessing. Real manifests re-captured as fixtures.

**Central schema (Task 14) - V203.** Existing schemas gained `x-category` + `properties` (their `required` lists untouched, so
validation behaves as before); new `Credential/FarmerRecord@1`; purposes `INCOME_CERT_RENEWAL`, `DBT_ACCOUNT_SEEDING`. Derived from
the four departments' real manifests and PROVEN by test: every document category has exactly one central schema, and every
required central field is mappable from the department's published fields (5 IT tests).

**One-go onboarding (Task 13).**
- `ManifestOnboardingPlanner` (pure): groups documents into data sources by protocol+host+auth (`<dept>-rest|soap|sftp|jdbc`,
  `-2` for a second auth), builds each connector's capabilities + inputs (REST endpoint + `resolve`; SOAP template; SFTP
  `key_column`; JDBC `view`+`key_column`), proposes mappings onto the seeded central schema, plans journeys, and lists operator
  steps (PROVISION_SECRET with the key and parameter NAMES only, CONFIGURE_SFTP / CONFIGURE_JDBC with the exact property and the
  host-key pin to set, CONFIGURE_HTTPS). Flags documents that cannot be bound (several inputs, no schema, unmappable required
  field). 19 unit tests against the 4 real manifests.
- `ManifestOnboardingService` + `POST /api/catalog/onboard/plan` (review, changes nothing) and `POST /api/catalog/onboard` (admin
  only): refuses if the manifest changed since the reviewed digest; needs ticked documents; mappings are propose-only (accept the
  suggestions or supply your own); validates everything BEFORE the first write, then writes in ONE transaction (nothing left
  behind on refusal). Creates the department (+ identity block), data sources, connector DRAFTs, mappings and journey DRAFTs.
  An existing connector for the same department+category gets a NEW VERSION, so the live one keeps serving until the new one
  is published. Existing sources/journeys are skipped, not duplicated. The department's manifest digest is stored (V204) so the next
  plan flags a changed manifest. 13 IT tests incl. all four real manifests end to end, publish/resolve, rollback, drift.
- Dev: `samanvay.catalog.allowed-private-hosts` (dev/demo yml: localhost,127.0.0.1; empty by default and in production) exempts named
  hosts from the SSRF guard so the local departments can be discovered/onboarded. 4 unit tests.
- Security: `POST /api/identity/department-login` is CITIZEN-only (it had fallen into the fail-closed default); both onboarding
  routes inherit `POST /api/catalog/**` = ADMIN. API-access-matrix tests updated and green.
- Limit: `test(ref)` on a connector is still the existing config check (data source + mapping exist) and `probe` is reachability only;
  a true per-document trial fetch needs a sample person ID the manifest does not yet publish (future `sample` block).

### Part B task 15 - journey onboarding, citizen department login, caller credentials - BUILT (with limits)
- Onboarding now adds an `ISSUE_CALLER_CREDENTIAL` operator step for a department that offers journeys: the reverse-direction
  credential (the department's PORTAL calls Samanvay): client id `dept-<code>`, department claim, role, one `source:<code>` scope per
  data source, and the `samanvay.security.staff.allowed-clients` property to extend. The secret is never entered or shown. 2 tests.
- Citizen side of the department login: `shared/dept-login.js` (start a login at the department, complete the link with the
  returned assertion; safe return path, http(s)-only login address, pending login used once) + `shared/dept-callback.html/.js`
  (no inline script) + a "Log in at <department>" button in the scholarship, licence and farmer portals, shown only where the
  department publishes a login (`departmentLoginAvailable`). 9 script tests (vitest/jsdom) + 3 static-page tests.
- The now-always-available `DEPT_ASSERTION` proof kind changed `GET /api/identity/proof-providers`; `ConnectAccountsIT` updated.
- NOT done: dev Keycloak clients for the four departments. `keycloak/gen_realms.py` does not reproduce the committed realm JSON
  (the committed files carry the deployed HTTPS origin the generator lacks), so regenerating would drop it; the realm change is left
  to the identity-provider owner. Also not built: department-side portals that call Samanvay (the four stand-ins have no portal;
  the three existing static portals are the callers).

### End-to-end proof - BUILT (`DepartmentsEndToEndIT`, 9 tests)
Runs the four department jars as REAL processes, an in-process SFTP server for each of Revenue and Agriculture (serving the
departments' own CSVs), and Agriculture's own Postgres seeded by its own `db/init.sql` (read through the read-only account). Onboards
all four from their real manifests; a citizen logs in at each department (each login in its own style); the signed assertion is
verified and saved as a link; then a document is fetched from each through the REAL connector runtime: Revenue REST + API key + resolve
(latest of two certificates), Revenue SFTP, DBT REST + OAuth2, Education SOAP + WS-Security, Agriculture JDBC (view only; hidden
column not returned) and SFTP. Also proven: an assertion is bound to the citizen who started the login, one department person links
to one citizen, a used assertion cannot be replayed, and a wrong credential is refused. Jars come from `scripts/build-departments.sh`
(skipped, not failed, if absent).

### Part B task 16 - dev wiring, docs, verification
- `application-dev.yml` / `application-demo.yml`: REST/SOAP URLs, SFTP sources (host, path, pin placeholder) and the JDBC source for
  the four departments' source codes; `scripts/dev-department-secrets.sh` prints their dev credentials as the base64 env vars the
  SecretStore reads. Dev/demo profiles verified to boot (matrix + sign-in ITs green).
- README (departments section, legacy note on `simulators/`), HLD addenda (catalog, identity, connector),
  `docs/runbooks/department-cutover.md`, `departments/README.md`, staff nav blurb.
- Verification (this session): all four department builds green (Revenue 24, DBT 18, Education 17, Agriculture 18 tests); core UNIT
  suite (`mvn test`, run twice) 538 tests with 2 errors, both Windows environment limits unrelated to this work
  (`StandaloneDepartmentServiceTest` launches the `mvnw` script via CreateProcess; `FileSecretStoreTest` needs the symlink privilege).
  NOTE: `mvn test` does not run the `*IT` classes (failsafe runs them at `verify`). The FULL integration suite (`mvn verify`) was run
  on this branch AND on a pristine `main` worktree for comparison: `main` = 197 tests, 3 failures + 28 errors; this branch = 236
  tests, the IDENTICAL 3 failures + 28 errors, i.e. 39 more integration tests, all passing, and no new failure. The pre-existing
  failures are on this machine/main, not this work: five Keycloak classes (`KeycloakRealmExportIT`, `KeycloakSignInPathsIT`,
  `KeycloakTokensIT`, `DepartmentBrokerSignInIT`, `DepartmentBrokerLinkIT`) cannot start the `keycloak:26.4` container here, and
  `AuditAttributionIT` fails with a broken audit hash chain only when run after other ITs in the shared database (it passes alone;
  worth a separate look: an audit chain race with a background consent job). Frontend: tsc clean, eslint clean, all 232 vitest tests green.

### Phase 3 - close the smaller gaps, delete the old simulators, browser check - BUILT
- **Old simulators deleted:** `simulators/src/**/department/**` and `StandaloneDepartmentServiceTest` are gone. The V193/V199 sandbox
  rows in the database stay (history); the demo now depends on `DemoDepartmentBootstrap` plus the operator secrets and host-key pins.
- **Gaps closed (each test-first):** REST POST / body inputs, required SOAPAction, HMAC request signing, mTLS client certificates,
  SFTP private-key login, IP allow-lists on all four departments, `sample.personId` in the manifest, and a **trial fetch** (staff
  "Run trial fetch" button, `POST /api/connector/trial/{ref}`, no grant, audited `CONNECTOR_TRIAL`). The demo bootstrap publishes a
  connector only if its trial passes.
- **Browser check (real processes: core on a throwaway Postgres, four department jars, two SFTP servers, one department DB):**
  - Core boot: the bootstrap onboarded and published REVENUE (4 connectors), DBT (1), EDUCATION (1), AGRICULTURE (2).
  - Staff console (demo admin, Onboarding): plan for `http://localhost:8091` showed documents, protocols, central schema, field
    matches and the operator to-do list; onboarding a ticked document created `rev-income@3`; "Run trial fetch" returned Asha Patil's
    income record for sample person RV-1001.
  - Citizen portal (scholarship): demo sign-in, applicant details, "Log in at ..." buttons for Revenue, Education, DBT; logging in at
    Education's own page returned a signed assertion that core verified, and the portal showed the link as Connected (2 of 3).
  - Defects found and fixed: (1) an already-onboarded, unchanged department was shown as if a new version were pending, now says
    "already onboarded from this manifest"; (2) after the department login the portal reopened on an empty "details" form instead of
    the connect step (`SamanvayDeptLogin.takeReturned()`, all three portals); (3) the department login pages were unstyled (small
    inline style added); (4) two core tests still expected the deleted sandbox sources and the no-grant rule did not know about
    `trial` (updated).
- (Superseded by phase 4: the DigiLocker sandbox and its banner were removed entirely.)

### Phase 4 - the remaining deferred items, DigiLocker removed, real sign-in only - BUILT
- **DigiLocker removed** (it was a sandbox mock): the proof kind, provider, mock client, the "pull issued documents" dialog and the
  `/api/connector/issued-documents` locker listing are gone, from the API, the three portals, the React app and the tests. The only
  mock proof left is the labelled local-ID + OTP, shown only for a department that has no login of its own.
- **No demo login any more:** `DemoSignIn`, the paste-token script, the `devSignIn` switch and the SPA "Demo: ..." buttons are
  deleted; `NoDemoSignInIT` proves the demo profile has none of them. Everyone signs in through Keycloak.
- **Citizens sign up** with first name, last name, email and password (email is the user name; password policy length 8, not the
  user name or email) and sign in with email + password or a passkey; no TOTP. Keycloak's sign-up and sign-in pages are used as-is.
  The old mock "Revenue Department sign-in" broker button is hidden from the sign-in page (`hideOnLogin`); `kc_idp_hint` still works.
- **Staff** keep passkey or password + TOTP. Ready-made accounts `dev-officer`, `dev-reviewer`, `dev-admin` (temporary password
  `<user>-change-me`; the first sign-in sets a new password and enrols an authenticator).
- **Dev Keycloak clients and realm drift:** `dept-revenue`, `dept-dbt`, `dept-education`, `dept-agriculture` (service accounts, scopes
  for exactly the sources each owns, `department` claim) are generated and allowed by the API. `gen_realms.py` now reads
  `SAMANVAY_EXTRA_ORIGINS` (default: the AWS demo origin), so regenerating reproduces the committed realms instead of dropping it.
- **Passphrase-protected SFTP private keys:** the credential may carry `passphrase` (OpenSSH-format encrypted keys work with the
  bundled crypto); a wrong or missing passphrase is refused without leaking either.
- **REST `resolve` via POST:** a resolve step may declare `method: POST`; the person ID goes in the body, and the resolve call never
  inherits the document call's method or body.
- **Central schema admin screen** (staff console, Central schema): `GET /api/catalog/schema-details` (officer, admin) and
  `POST /api/catalog/schemas` (admin) add a schema or a new version (never edited in place; ref, category and field rules checked
  server-side; refusals shown). Onboarding already picks the highest version for a category.
- **The "pre-existing" Keycloak test failures were a test-support bug,** not Keycloak: on Docker Desktop the Mailpit container's
  address is not at the top level of its network info, so `add-host` got "null". Fixed in `KeycloakTestSupport`; all Keycloak ITs
  (realm export, sign-in paths, tokens, department broker) now run and pass here.
- **`AuditAttributionIT` chain break (investigated):** `ConsentLifecycleJobsIT` inserted a raw audit row with made-up hashes into the
  shared database, which broke every later whole-chain verify. Fixed by appending through `AuditService`.
- **Browser check of the new sign-in** (real Keycloak with the regenerated realms, fresh database, the four department services):
  a new citizen signed up (name, email, password) and landed signed in on the scholarship portal; the connect step offered only
  "Log in at ..." buttons (no DigiLocker); logging in at Revenue linked the account. `dev-admin` signed in (password, authenticator
  enrolment, new password) and opened Central schema: 16 schemas listed; adding `Credential/Marks@2` showed it in the list.

### Phase 5 - manifest trust, and answers settled in review (2026-10-04) - BUILT
Settled in discussion (no code change needed, recorded so nobody re-asks):
- **A department has one ID, and the person ID comes from the login assertion** (`sub`). The citizen logs in at a department ONCE; Samanvay
  stores the link; later journeys only ask for consent.
- **Document keys are NOT stored.** When a department publishes `resolve`, the runtime asks it for the person's document keys on every
  fetch (`resolveThenExecute`, in memory, one exchange) and uses them for that call only. Samanvay holds only the link (person ID); the
  department stays the source of truth, so a new or revoked certificate is seen immediately and nothing goes stale. The fetch itself
  needs a consent grant (only the staff trial fetch is exempt, and it is audited).
- **The resolve call is secured like any other department call:** it goes over the same source, with the same declared credentials
  (API key, OAuth2, ...), only the person-ID input, and a missing credential means no call is made.
- **The manifest endpoint is public discovery metadata** (no secrets, no citizen data). Its integrity rests on: HTTPS in production,
  the admin reviewing the plan, the digest check at onboard time and on drift, the SSRF host guard, REST/SOAP hosts taken from the base
  URL the admin typed (never from the manifest), SFTP/JDBC hosts and pins set by the operator, no SQL accepted, secrets out of band.
  (Phase 6 below then added a signed manifest and an optional discovery credential, closing the two gaps this bullet first left open.)
- **New check (found in review):** the identity block (`loginUrl`, `jwksUrl`) decides whose signature Samanvay trusts for "this person is
  X", and it was taken from the manifest unchecked. It must now be on the SAME HOST as the base URL the admin entered
  (`ManifestOnboardingPlanner.identityHostProblem`); plan and onboard both refuse otherwise, and nothing is written. Host-only match
  (ponytail: a department that serves login from another host needs an operator allow-list). Tests: 4 planner unit tests, 1 IT
  (planner 29, `ManifestOnboardingIT` 14, `DemoDepartmentBootstrapTest` 12, `DepartmentsEndToEndIT` 11, all green).
- Possible later upgrade: the assertion returns in the browser URL (5-minute, single-use, citizen-bound); an authorization-code exchange
  between Samanvay and the department servers would keep it out of browser history.
- Linking options for real departments, if one will not build a login: a verify-by-OTP challenge to the mobile the department holds
  (needs a small department API); Aadhaar-OTP (legal review); attribute matching only as a last resort (human review queue).

### Phase 6 - signed manifest, and credentials in both directions at every hop (2026-10-04) - BUILT
Contract: `docs/contracts/manifest-signature.md`.

**Signed manifest.** Each department signs the exact bytes of its manifest (ES256 JWS in `X-Samanvay-Signature`; payload = SHA-256 of
the body + issue time; the department's PUBLIC key travels in the JWS header). Samanvay verifies it on the bytes it received, requires
a fresh `iat` (10 min, no replay of an old manifest), and returns the signing key's RFC 7638 thumbprint. **Trust is pinned, not
assumed:** the plan shows the thumbprint; the admin confirms it with the department out of band and sends it as `approvedManifestKey`;
Samanvay stores it (V205 `catalog_department.manifest_key_thumbprint`). After that a manifest must be signed by that key; a different
key needs a new approval (= rotation); a pinned department can never go back to unsigned; an unsigned manifest is refused unless
`samanvay.catalog.allow-unsigned-manifests=true` (default false; tests and local development only). Staff console shows the fingerprint
and requires an "I confirmed this key fingerprint with the department" tick when the key is new or changed. The demo bootstrap approves
a first-seen key (like it accepts suggested mappings) but never a changed one.
Tests: `ManifestSignaturesTest` 7, `ManifestTrustTest` 7, `SignedManifestOnboardingIT` 5, 2 more in `DemoDepartmentBootstrapTest`,
4 frontend tests, `ManifestSigningTest` in each department (Revenue 5, DBT 5, Education 5, Agriculture 5), and
`DepartmentsEndToEndIT` now onboards all four from SIGNED manifests in strict mode and pins their keys.

**Discovery credential (Samanvay -> department, before onboarding).** A department may show its manifest only to Samanvay
(`<dept>.manifest.discovery-key`, header `X-Discovery-Key`). The operator provisions the value under SecretStore key
`manifest-<host[-port]>-credential`; a 401/403 names that exact key. The end-to-end test makes Revenue require it.

**The credential map: who proves what to whom, at every hop** (nothing here is new except the first two rows):

| Hop | Direction | Credential | Where it lives |
|---|---|---|---|
| Read the manifest | Samanvay -> dept | optional discovery credential (`X-Discovery-Key`) | dept issues it; SecretStore `manifest-<host>-credential` |
| Trust the manifest | dept -> Samanvay | ES256 signature, key pinned by an admin | dept's key file; thumbprint in `catalog_department` |
| Fetch a document (and `resolve`) | Samanvay -> dept | per-source credential: API key / OAuth2 client / WS-Security / DB user / SFTP login | dept issues it; SecretStore `source-<code>-credential` |
| Dept's portal calls Samanvay | dept -> Samanvay | Keycloak client-credentials client `dept-<code>`, `source:<code>` scopes | staff realm; plan step `ISSUE_CALLER_CREDENTIAL` |
| Citizen proves who they are at the dept | dept -> Samanvay | signed login assertion (ES256, pinned JWKS on the dept's own host, one-time state/nonce) | dept's own key; JWKS URL stored at onboarding |

Both sides therefore authenticate: Samanvay holds credentials the department issued (rows 1 and 3), and the department holds
credentials Samanvay's identity provider issued (row 4) plus keys it signs with (rows 2 and 5).

Honest limits: pinning is trust-on-first-approval (an admin who does not check the fingerprint with the department has trusted
whoever answered first); the department's signing key is a file in the reference services (a real department keeps it in its own
key store); the discovery credential is one shared value per department (per-caller values are the upgrade).

### Phase 7 - multi-server demo: real department databases, one login style, 20 citizens, deployment bundles (2026-10-04) - BUILT
Decided by the user: departments run on separate small servers, the middle layer on its own; every department has its own database and is as
close to a real one as we can make it; 20 citizens with fake documents in every department; every department login is **mobile number +
password, then the one-time code 123456**; the staff authenticator code may be **000000**. Runbook: `deploy/README.md`.

- **Each department has its own Postgres** (`departments/<d>/db/schema.sql`): Revenue (person, certificate with its own key, land record),
  DBT (beneficiary, bank account), Education (student, marks statement), Agriculture (farmer, crop sowing, plus the read-only view Samanvay
  uses). Logins live in a `citizen_login` table with **bcrypt hashes** (pgcrypto), checked in SQL with bound parameters. A service uses its
  database only when `<DEPT>_DB_URL` is set; with none it uses the built-in two-citizen seed, so tests and a bare local run are unchanged.
  Agriculture has two database roles: its own service (reads logins only) and Samanvay's `agri_ro` (the view only).
- **One login style everywhere** (`LoginPages`, `LoginController`, `LoginTicket` in each department): password page, then a code page. A stateless
  signed ticket (HMAC, 5 minutes, tied to the exact state and nonce) carries step one to step two. The pages are server-rendered, no scripts,
  no external requests, light and dark, WCAG AA contrast checked in a browser (all text at 6.4:1 or better), one accent colour per department.
- **Generator** `scripts/gen-demo-data.py` (stdlib only): 20 citizens (documents deterministic, passwords and secrets random but kept in
  `deploy/generated/state.json` so a re-run changes nothing), their documents in each department, logins, every shared secret, one env file per
  server, the SFTP host keys with their fingerprints (mounted into the SFTP container so a re-created container keeps the pin), the middle
  layer's `departments.env`, `CREDENTIALS.md`, `ONBOARDING.md`. `deploy/generated/` is git-ignored. Checked by `scripts/test_gen_demo_data.py`
  (15 tests; it loads every schema and seed into a real Postgres and verifies a login the way the services do).
- **Deployment bundles** `deploy/departments/<d>/docker-compose.yml`: service + its Postgres + SFTP where it has one + Caddy HTTPS. Verified for real
  on this machine: all four stacks were built from their Dockerfiles and started, and driven with the generated credentials (manifest 401 without the
  discovery key and signed with it, the fingerprint script equals the log line, two-step login, Revenue documents from Postgres, DBT OAuth2 + bank
  record, Education SOAP + WS-Security, Agriculture view-only role, SFTP host key equals the pin after re-creating the container).
- **Staff authenticator code 000000** (`keycloak/email-otp/.../fixedotp`): the staff second factor is now `samanvay-otp-form`, the stock OTP step
  plus ONE addition: when the Keycloak server has `SAMANVAY_DEMO_FIXED_OTP` set to exactly six digits it also accepts that code and skips
  authenticator-app enrolment. **Unset (the default) it is identical to the stock step**: 000000 is refused and enrolment is forced (the existing
  `KeycloakSignInPathsIT` proves it). Anything that is not six digits is ignored; Keycloak logs a warning when it is on. This is a deliberate back
  door for a fake-data demo; `StaffFixedOtpIT` (own Keycloak container) proves it, and that the password is still required, a wrong code is still
  refused and a real authenticator code still works. The three ready-made staff users no longer carry a forced CONFIGURE_TOTP (the flow enrols anyone who
  needs it). Not unit-tested in isolation (the extension is compiled after the tests in this build); its six-digit rule was run directly once.
- **Front end:** the React citizen app (not only the static portals) now links a department by logging in at it: "Log in at <department>" on a
  department that publishes a login, the return page `#/dept-callback` that hands the signed assertion to Samanvay, bilingual (en/mr). 14 new tests;
  260 in all. The staff console's onboarding screen shows the manifest key fingerprint and asks the admin to confirm it (phase 6).
- Department services also log `Manifest signing key thumbprint: ...` at start, and `scripts/manifest-fingerprint.py` / `scripts/sftp-hostkey.sh`
  print the live fingerprints, so an admin can compare them out of band.

Honest limits: the middle layer was run against the department stacks one hop at a time (jars in `DepartmentsEndToEndIT`; the Docker stacks by hand),
not all together in one run; the fixed codes are back doors by design; department database connections have no pool; no login lockout; Keycloak in
`start-dev` keeps its data in memory; the department stacks use Caddy/Let's Encrypt, which needs real DNS names (nip.io works) and was not run here.

### Phase 8 (2026-10-04, branch `feat/department-journeys`): journeys live on the departments' own portals

Contracts: `docs/contracts/department-api.md`, `docs/contracts/department-consent-statement.md`, and `login-assertion.md` (home sign in, `name`, `dob`).

**The decision:** Samanvay is for the Samanvay team and officers. A citizen never signs in to it and never sees a Samanvay screen. Every citizen
journey runs on a department's own portal (`https://<department>/portal/`): the citizen signs in there (mobile + password + code), sees that
department's services, and when a service needs another department's records the portal sends them to THAT department's own login and back
(the link is saved). Consent is collected on the portal and is **signed by the department**; Samanvay verifies and keeps it as evidence. The
manifest is made from the department's own `journeys.json`, so it cannot promise a journey the portal does not have.

What was built:
- **Samanvay, department API** (`/api/department/**`, role DEPARTMENT, the department is the token's claim): resolve-or-create the citizen from the
  home sign-in assertion (accepted once, by `jti`); start and complete a link to another department (the return address must be on the caller's own
  host); readiness; the consent wording plus a one-time nonce; grant from a signed statement; start and read applications only for journeys the
  caller runs (replaces the old "scoped for every data source" start rule). A person who starts at two departments ends up as ONE citizen: when a
  link collides with an existing citizen and the current one is an empty record made by a home sign in, the two are merged (audited
  `CITIZEN_MERGED`); any other collision stays a 409. Migrations V206 (consent evidence, nonce), V207 (assertion use), V208 (citizen origin),
  V209 (Education's purpose, clearances for Education, Revenue, DBT), V210 (`connector_trial`: the last onboarding trial of each connector,
  durable, shown on the staff journey page), V211 (`onboarded` flag on `catalog_data_source`, `catalog_connector`, `catalog_journey`: the staff
  Departments and Journeys pages list only rows created or adopted by manifest onboarding; seeded demo and test rows stay FALSE).
- **Consent statement:** ES256 JWS `typ=samanvay-consent` signed with the department's manifest key. Samanvay checks it against the key it PINNED from the
  signed manifest, the open request, citizen, purpose, categories, nonce (single use), recency, and `jti` (single use). One generic refusal.
- **Manifest** `journeys[].portalUrl` (must be on the manifest's own host); the citizen realm is optional and switched off in the deployment;
  the three old citizen portals, their helpers and the React citizen surface are deleted; `/` is a short staff landing page.
- **`departments/kit`** (library, reactor `departments/pom.xml`): `SamanvayClient` (client credentials, cached token), `ConsentSigner`, `PortalSession`
  (HMAC cookie), `JourneyCatalog` (from `journeys.json`), the `/portal-api` backend, `/portal/` pages. Each department supplies only a
  `CitizenDirectory`, a `HomeAssertions`, `journeys.json` and the `portal:` config. Education's journey is now `EDUCATION_SCHOLARSHIP`
  (requester EDUCATION); Revenue, DBT and Agriculture keep their codes. `/login` opened directly is the portal sign in. Agriculture's farmer table gains `date_of_birth`.
- **Portal front end** (`frontend/src/portal`, built by `scripts/build-portal.sh` into each department's `static/portal/`, git-ignored): sign in, services,
  a three-step journey page (connect departments, consent, apply), tracking with the records received.
- **Staff per-journey page** `#/staff/admin/journeys/:code` (`GET /api/ops/journeys/{code}`): per document the serving connector, source health, last trial and
  whether it works; counts; recent applications; the middle-layer log of those applications.
- **Deployment:** the generator gives every department server its own Keycloak client secret, session secret, Samanvay and Keycloak addresses and the
  other portals' return addresses; `scripts/provision-department-clients.py` sets the four client secrets in Keycloak; Dockerfiles build from `departments/`.

Tests: `DepartmentIdentityIT`, `DepartmentConsentIT`, `DepartmentJourneyIT`, `StaffOnlyRealmIT`, `JourneyStatus*`, kit (21), each department's `*PortalTest`,
the portal and staff front-end tests, the generator tests, and `DepartmentsEndToEndIT` now runs all four journeys through the real department jars
(sign in, link the others by their own logins, department-signed consent, apply, track) plus the two-home-sign-in merge.

Honest limits: the last connector trial is durable since V210; the log's latency is the step's duration and its
connector is derived from what the instance pinned; the staff page has no Probe or Run-trial buttons (they are on the Onboarding screen; the old Catalog and Discover pages were removed);
pending consent wordings and the portal session secret live in one process (a restart signs citizens out); the fixed codes remain demo back doors.

## Decisions closed from the open questions

- **Case A vs B:** no separate cases. One mechanism: optional `resolve` (§12). Revenue's simulated
  service uses `resolve`; the other three omit it (document key = person ID). Real departments to confirm.
- **Protocols:** confirmed as proposed in §1.
- **Login system:** each department has its **own** login (own identity provider) - DECIDED. The
  single shared `samanvay-department` realm is replaced per department.
- **Central schema:** stays **seeded** (migration/seed files) - DECIDED. Rule: before a department can
  be onboarded, its document types must first be seeded into the central schema; onboarding then maps the
  department's fields onto that seeded schema. A self-service schema screen is deferred.

## Deferred (honest list: not built, with why)

- **SFTP and JDBC sources are operator-configured and need a restart** (by design: a manifest must not redirect Samanvay). The
  secrets (`source-<code>-credential`) are provisioned by the operator, never typed in the browser.
- **Department-side portals and admin portals** (a department's own screens for calling Samanvay): out of scope by decision. The
  caller client (`dept-<code>`) now exists in the dev realm; building the department's own UI is the department's work.
- **A department with no login yet** (Municipal, Fire, Pollution in the licence journey) can only be linked with the labelled
  local-ID + OTP demo. Each needs its own department service with a login before that mock can go.
- **Real DigiLocker: left out on purpose** (removed, see phase 4), not deferred work.
- **Aadhaar number handling.** Never stored; the legal detail of any future Aadhaar-based proof still needs review.
- **Staff TOTP on every dev sign-in.** The ready-made staff accounts keep password + authenticator code (the design); a pre-enrolled
  dev authenticator secret would remove the first-login step but is a committed credential, so it was not done.
- **Citizen email is not verified at sign-up** (`verifyEmail` is off in the dev realm so sign-up needs no mail server). Turn it on
  where a mail server exists; a citizen's identity is proven by the department login, never by the email.
- **Nothing is committed or pushed; no PR has been opened.**

## Resolved during the build

- Login assertion contract: `docs/contracts/login-assertion.md` (implemented by all four departments and verified by core).
- Central schema fields: seeded in V203, derived from the four departments and proven mappable by test.

## Change history

| Date | Change |
|---|---|
| 2026-10-04 | Phase 8: journeys run on the departments' own portals; department-signed consent; department API; kit module; portal front end; staff journey page; Samanvay has no citizen UI. |
| 2026-10-04 | Phase 7: real department Postgres, one login style (mobile + password + code), 20 citizens per department, generator, deployment bundles, staff code 000000 (env-gated), React department login. |
| 2026-10-04 | Phase 6: signed manifest with an admin-pinned key, optional discovery credential, credential map, compose key volumes, staff console key confirmation. |
| 2026-10-04 | Phase 5: identity URLs must be on the manifest's host; recorded that document keys are resolved per fetch, never stored; manifest trust model written down. |
| 2026-10-02 | Phase 4: deferred items built (passphrase keys, POST resolve, dept Keycloak clients, schema admin screen), DigiLocker and demo login removed, citizen sign-up with password, Keycloak ITs fixed, audit-chain test pollution fixed. |
| 2026-10-01 | Phase 3: simulators deleted, smaller gaps closed, browser check done (4 defects fixed); deferred list trimmed. |
| 2026-10-01 | Final pass: status, build order outcome, tasks 15-16 + end-to-end IT logged; deferred list written; runbook added. |
| 2026-10-01 | §15: Part B tasks 13-14 backend built (central schema V203, planner, onboarding service + endpoints, dev host allow-list, security matrix). |
| 2026-10-01 | §15: Part B task 12 built (DEPT_ASSERTION proof, login-state store, JWKS fetcher, start-login API, explicit link keys). |
| 2026-10-01 | §15: Part B tasks 9-11 built (manifest v2 in core, secured REST/SOAP/SFTP/JDBC, resolve step). |
| 2026-10-01 | §15: Part A complete (tasks 7-8: login assertions, compose, Dockerfiles; DB + image verified). |
| 2026-10-01 | §15: Part A tasks 1-6 built (Revenue SFTP + IP allow-list, DBT, Education, Agriculture, journeys). |
| 2026-10-01 | Added §15 build log: Revenue REST slice built (7 tests green). |
| 2026-10-01 | Open items clarified: assertion contract defined; central schema fields derived from the four departments during build. |
| 2026-10-01 | Tidied: removed stale case A/B wording, "ration card" example, proposed/open flags now decided. |
| 2026-10-01 | Case A/B collapsed to one optional-`resolve` mechanism; own login per department; central schema stays seeded (seed before onboarding). |
| 2026-10-01 | Added §0 one-page summary, §14 build order, per-department security schemes; marked superseded parts. |
| 2026-10-01 | Added §13 (multi-parameter protocol security; REST/SOAP send no credentials today). |
| 2026-10-01 | §12 revised: login returns ONE person ID; fetch = person ID + Samanvay credentials; manifest declares per-document lookup (later simplified to optional `resolve`). |
| 2026-10-01 | Added §11 (how the local ID reaches Samanvay after department login). |
| 2026-10-01 | Added §10 (one-go onboarding via richer manifest v2; no extra endpoints). |
| 2026-10-01 | Added §9 (how protocol choice really plugs a department in; gaps found). |
| 2026-10-01 | §1: departments named (Revenue, DBT, Education, Agriculture); protocols proposed. |
| 2026-10-01 | §8 revised: linking is per-department login (no fixed two-key model); needs department tech teams; simulate until then. |
| 2026-10-01 | Added §6 (Maharashtra login/sign-up research) and §7 (DigiLocker usage). |
| 2026-10-01 | File created. §1–§5 captured from the first design discussion. No code changed yet. |
