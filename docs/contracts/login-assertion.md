# Department login assertion - contract v1

How a department proves to Samanvay "this citizen just logged in here, and at this department they are person X".
Every department implements this once, whatever its own login method is (password, OTP, ...). Samanvay needs no
per-department code; it only reads the department's manifest. See `docs/FINAL-CHANGES.md` sections 8 and 11.

## Flow (browser redirect)

1. Samanvay sends the citizen's browser to the department's `loginUrl` (from the manifest `identity` block):
   `GET {loginUrl}?return_to={samanvayCallbackUrl}&state={opaque}&nonce={opaque}`
2. The department shows ITS OWN login page and authenticates the citizen however it normally does.
3. On success it redirects (303) to `return_to` with the signed assertion:
   `{return_to}?assertion={compact JWS}&state={the same state}`
4. Samanvay verifies the assertion (below) and stores the link: citizen <-> (department, personId).

`return_to` must match an address the department allow-lists for Samanvay; otherwise the department answers 400
and never redirects (no open redirect). `state` and `nonce` are required.

## The assertion

A compact JWS (JWT), algorithm **ES256**, header `{"alg":"ES256","kid":"<key id>","typ":"JWT"}`.

| Claim | Meaning |
|---|---|
| `iss` | `dept:<DEPARTMENT_CODE>`, e.g. `dept:REVENUE` (the code in the manifest `department.code`) |
| `aud` | `samanvay` |
| `sub` | the person's ID **at that department** (the `personId`) |
| `person_id_type` | the manifest `identity.personIdType`, e.g. `REVENUE_PERSON_ID` |
| `dept_code` | the department code (same as in `iss`) |
| `auth_time` | epoch seconds of the actual login |
| `iat` / `exp` | issued-at / expiry; `exp - iat` is at most 300 seconds |
| `jti` | unique ID of this assertion (for audit; replay protection is the one-time `state`, below) |
| `nonce` | echo of the request `nonce` |
| `state` | echo of the request `state` |
| `name` | optional: the person's name as the department holds it |
| `dob` | optional: date of birth, `YYYY-MM-DD`; a malformed value is ignored |

No raw Aadhaar number, password or other secret is ever in an assertion. `name` and `dob` are what Samanvay uses to make
its record of a person the first time they sign in at a department's own portal (see "Home sign in" below); when `dob` is
absent the record is made with year-only precision and a placeholder year.

## Home sign in (the citizen signs in at the department's own portal)

Citizens never sign in to Samanvay. A citizen signs in on a department's own portal; the portal's server then sends the same
kind of assertion to Samanvay (`POST /api/department/citizens/resolve`, docs/contracts/department-api.md), with a fresh random
`state` and `nonce` that Samanvay did not issue. Everything above is checked the same way, except there is no issued state to
match: replay is stopped by remembering each assertion's `jti` (a second use is refused). The `dept_code` must be the department
that is calling.

## Keys

The department publishes its public keys as a JWKS at `identity.jwksUrl` (manifest). Samanvay fetches it
over the manifest's host (subject to Samanvay's host allow-list) and re-fetches when it meets an unknown `kid`.

## What Samanvay checks

Signature against the department's JWKS; `iss` and `dept_code` equal the onboarded department; `aud=samanvay`;
`person_id_type` equals the manifest value; `exp` not passed (with 60 s clock skew) and `auth_time` recent (default
under 10 minutes); `state` and `nonce` are the pair Samanvay issued to THIS citizen for THIS department, unexpired and unused: they are consumed on success, so a replayed assertion is refused. The state is consumed last, so a bad assertion cannot burn a pending login. Only ES256 is accepted (no unsigned or HMAC tokens); an unknown `kid` triggers one fresh key fetch.
Any failure is one generic refusal; the reason is only logged.

## Manifest `identity` block

```json
"identity": {
  "personIdType": "REVENUE_PERSON_ID",
  "loginUrl": "https://revenue.example/login",
  "jwksUrl": "https://revenue.example/.well-known/jwks.json",
  "assertionIssuer": "dept:REVENUE"
}
```
