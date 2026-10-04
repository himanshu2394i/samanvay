# Manifest signature contract (v1, with the `aud` claim)

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
| Payload | `{"sha256": "<hex SHA-256 of the exact response body bytes>", "iat": <epoch seconds>, "aud": "<origin of the department's public base URL>"}` |

Samanvay verifies, in this order, using the **bytes it received** (before parsing them):

1. the JWS is well formed and ES256, and the header carries a public EC key;
2. the signature verifies with that key;
3. `sha256` equals the SHA-256 of the body (so a manifest altered in transit, or a signature copied onto other content, fails);
4. `iat` is within **10 minutes** of now, in either direction (so an old signed manifest cannot be replayed; the department signs
   every response afresh).

5. `aud` equals the **origin** of the base URL Samanvay fetched the manifest from (see below), so a signed manifest cannot be replayed
   from one host as another's. A payload without `aud`, or with a different one, fails the signature.

A signature that is present but fails any step makes the whole fetch fail. No signature header means "unsigned".

### The `aud` claim

`aud` is a single string: the **origin** (`scheme://host[:port]`) of the department's configured public base URL.

- scheme and host in lower case; no path, no query, no fragment, **no trailing slash**;
- the port is written only if the configured URL has one (`https://dept.example.com:8443`, but `https://dept.example.com` stays
  without `:443`: nothing is added or removed);
- examples: `https://Revenue.Example.com/` becomes `https://revenue.example.com`; `http://127.0.0.1:8091` stays as it is.

The department computes it from its own public base URL setting (`<dept>.public-base-url`, the address published in the manifest
identity block). A department refuses to start if that setting is not an absolute http(s) URL, because it could not sign. Samanvay
computes the same origin from the address the admin entered at onboarding and compares the two strings exactly.

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
(`<dept>.manifest.key-file`, created on first start with owner-only permissions, written through a temporary file and moved into place
in one step) so the approved thumbprint survives restarts. Deleting the file
rotates the key. A real department should hold it in its own key store / HSM.

## 4. The discovery credential (optional)

A department may refuse to show its manifest to anyone but Samanvay. If it does, it issues Samanvay a **discovery credential** out
of band, and Samanvay sends it as `X-Discovery-Key: <value>` on the manifest request.

- The operator provisions the value into Samanvay's SecretStore under `manifest-<host[-port]>-credential` (host lower-cased, every
  run of characters other than `a-z0-9` replaced by `-`; e.g. `http://127.0.0.1:8091` -> `manifest-127-0-0-1-8091-credential`).
- If the department answers 401/403, Samanvay's error names that exact key. The value is never shown.
- Reference services: `<dept>.manifest.discovery-key` (empty = the manifest is public).

## 4a. Paths the department decides on

The reference services decide on a **normalised** path (no `;parameters`, percent escapes decoded, dot segments resolved), never on the
raw request line, and answer a request whose path has `;` parameters, an encoded `;` `/` `\` or `.`, a dot segment or `//` with
**400**. `/.well-known/samanvay/manifest;x` is therefore refused, never served unsigned or without the discovery key.

## 5. What this does not do

- It does not replace HTTPS. It protects the manifest's **integrity and origin**; confidentiality is TLS's job.
- Pinning is **trust on first approval**: an admin who approves a thumbprint without checking it with the department has trusted
  whoever answered first.
- It is separate from the login-assertion key (`docs/contracts/login-assertion.md`), which rotates freely and is fetched from a JWKS.
