# Manifest signature contract (v1)

A department's manifest (`GET {base}/.well-known/samanvay/manifest`) is **signed by the department** and, optionally, **shown only
to Samanvay**. Both are checked by Samanvay before anything from the manifest is used.

## 1. The signature

The department answers the manifest request with a header:

```
X-Samanvay-Signature: <compact JWS>
```

| Part | Value |
|---|---|
| Algorithm | `ES256` (ECDSA, P-256). Anything else is refused. |
| JWS header | `alg`, `typ: samanvay-manifest`, and **`jwk`: the department's PUBLIC key** (a private key in the header is refused) |
| Payload | `{"sha256": "<hex SHA-256 of the exact response body bytes>", "iat": <epoch seconds>}` |

Samanvay verifies, in this order, using the **bytes it received** (before parsing them):

1. the JWS is well formed and ES256, and the header carries a public EC key;
2. the signature verifies with that key;
3. `sha256` equals the SHA-256 of the body (so a manifest altered in transit, or a signature copied onto other content, fails);
4. `iat` is within **10 minutes** of now, in either direction (so an old signed manifest cannot be replayed; the department signs
   every response afresh).

A signature that is present but fails any step makes the whole fetch fail. No signature header means "unsigned".

## 2. Who is trusted: the pinned key

The key travels with the signature, so a valid signature alone proves nothing about **who** signed. Trust comes from a one-time
human step:

1. Samanvay shows the admin the key's **thumbprint** (RFC 7638: base64url SHA-256 of the key's required members, 43 characters).
2. The admin confirms that fingerprint **with the department out of band** (phone, signed letter, ticket) and approves it in the
   onboarding request (`approvedManifestKey`).
3. Samanvay stores it on the department (**pins** it). From then on a manifest must be signed by that key.

| Situation | Result |
|---|---|
| First manifest, signed, key not yet approved | Plan shown; onboarding refused until the admin approves exactly that thumbprint |
| Signed by the pinned key | Accepted, no new approval |
| Signed by a **different** key | Plan shown with a warning; onboarding refused until the admin approves the new thumbprint (this is key rotation) |
| Pinned department now **unsigned** | Refused, always |
| Never pinned and unsigned | Refused unless `samanvay.catalog.allow-unsigned-manifests=true` (default `false`; tests and local development only) |

## 3. The department's signing key

Kept by the department, never published (only the public half travels in the header). The reference services keep it in a file
(`<dept>.manifest.key-file`, created on first start, owner-only) so the approved thumbprint survives restarts. Deleting the file
rotates the key. A real department should hold it in its own key store / HSM.

## 4. The discovery credential (optional)

A department may refuse to show its manifest to anyone but Samanvay. If it does, it issues Samanvay a **discovery credential** out
of band, and Samanvay sends it as `X-Discovery-Key: <value>` on the manifest request.

- The operator provisions the value into Samanvay's SecretStore under `manifest-<host[-port]>-credential` (host lower-cased, every
  run of characters other than `a-z0-9` replaced by `-`; e.g. `http://127.0.0.1:8091` -> `manifest-127-0-0-1-8091-credential`).
- If the department answers 401/403, Samanvay's error names that exact key. The value is never shown.
- Reference services: `<dept>.manifest.discovery-key` (empty = the manifest is public).

## 5. What this does not do

- It does not replace HTTPS. It protects the manifest's **integrity and origin**; confidentiality is TLS's job.
- Pinning is **trust on first approval**: an admin who approves a thumbprint without checking it with the department has trusted
  whoever answered first.
- It is separate from the login-assertion key (`docs/contracts/login-assertion.md`), which rotates freely and is fetched from a JWKS.
