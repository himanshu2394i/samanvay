# Department consent statement - contract v1

How a department proves to Samanvay "this citizen saw this exact wording and confirmed it". The citizen gives consent on the
department's own portal; the department signs a statement of it; Samanvay verifies it, grants the consent and keeps the
statement as evidence (`consent_evidence`).

## Flow

1. `POST /api/department/consents/requests` returns the wording (`purposeText`, `categories`, `providers`, `validityDays`) and a
   single-use `nonce` that ends at `expiresAt` (10 minutes).
2. The portal shows the wording word for word. The citizen confirms by entering the one-time code again.
3. Only then the portal signs the statement and sends it to `POST /api/department/consents` `{statement}`.

## The statement

A compact JWS, algorithm **ES256**, header `{"alg":"ES256","typ":"samanvay-consent","jwk":<the department's PUBLIC manifest key>}`.

| Claim | Meaning |
|---|---|
| `iss`, `dept_code` | `dept:<CODE>` and `<CODE>`; both must equal the calling department |
| `jti` | unique ID; each statement is accepted once |
| `citizen_id` | Samanvay's ID for the citizen (from `/citizens/resolve`) |
| `request_id`, `purpose`, `nonce` | repeated from the consent request |
| `categories` | the request's data categories (the same set) |
| `method` | `dept-otp` (the citizen confirmed with a one-time code) |
| `confirmed_at` | epoch seconds of the confirmation; at most 10 minutes old |
| `iat`, `exp` | `exp - iat` at most 300 seconds |

## What Samanvay checks

The header key's RFC 7638 thumbprint equals the thumbprint pinned for the department when its signed manifest was onboarded (so
the department must have been onboarded first, and signs with the same key as its manifest); the signature verifies; `iss` and
`dept_code` are the caller; the request exists, belongs to `citizen_id`, is still pending, asks for this purpose and these
categories; the nonce is the one issued, unused and unexpired (consumed on success); `jti` has never been seen. Any failure is
one generic `400` ("The consent statement could not be accepted"); the reason is only logged, and nothing is written.
