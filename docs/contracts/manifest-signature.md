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
| Payload | `{"sha256": "<hex SHA-256 of the exact response body bytes>", "iat": <epoch seconds>, "aud": "<origin>"}` |

Samanvay verifies, in this order, using the **bytes it received** (before parsing them):

1. the JWS is well formed and ES256, and the header carries a public EC key;
2. the signature verifies with that key;
3. `sha256` equals the SHA-256 of the body (so a manifest altered in transit, or a signature copied onto other content, fails);
4. `iat` is within **10 minutes** of now, in either direction (so an old signed manifest cannot be replayed; the department signs
   every response afresh);
5. `aud` is present and is the **origin Samanvay fetched the manifest from** (so a manifest signed for one host cannot be replayed
   from another within those 10 minutes).

`aud` is `scheme://host[:port]`, lower case, no trailing slash, no path (for example `https://revenue.example.gov`). Samanvay compares
it as an origin (case, a trailing slash and a default port `:80`/`:443` do not matter) with the base URL the admin onboarded from, so a
department signs the address it is served at. A signature with no `aud`, or with another one, makes the fetch fail with a message
naming both.

A signature that is present but fails any step makes the whole fetch fail. No signature header means "unsigned".

### Fetching the manifest

- The base URL must be **https**. Plain http is accepted only for a host named in `samanvay.catalog.allowed-private-hosts` (dev/demo,
  default empty), which is also the only way to reach a private address. Every address a name resolves to must be public: loopback,
  link-local, site-local, carrier-grade NAT (100.64/10), IPv6 unique-local (fc00::/7), multicast and 0/8 are refused, and IPv6
  literals such as `[::1]` are judged like any other address.
- A base URL with a user name or password is refused.
- At most **1 MB** is read; a longer answer is refused (the same cap applies to every department REST/SOAP answer).
- Not covered: DNS rebinding (a name that resolves to a public address when checked and to a private one when called). Pinning the
  checked address into the call is the upgrade.

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
| Signed by a **different** key | Plan shown with a warning (`identityChange`); onboarding refused until the admin approves the new thumbprint (`approvedManifestKey`) **and** acknowledges the identity change (`acknowledgeIdentityChange`); this is key rotation |
| Pinned department now **unsigned** | Refused, always |
| Never pinned and unsigned | Refused unless `samanvay.catalog.allow-unsigned-manifests=true` (default `false`; tests and local development only) |

### An existing department is not taken over

A manifest for a department that already exists may not silently change who Samanvay trusts to say a person is who they claim to be.
When the department already has an identity (or a pinned key) and the manifest's differs in its person-ID type, login address, key
(JWKS) address, assertion issuer, or the key that signs the manifest, the plan carries
`identityChange: {warning, changes[]}` (loud text for the admin, one line per difference, current to proposed), and `POST /api/catalog/onboard`
returns **HTTP 409** (`IDENTITY_CHANGE_NOT_ACKNOWLEDGED`, nothing changed) unless the request has `"acknowledgeIdentityChange": true`. Confirm
the change with the department first. A department that has no identity yet may gain one without it.

The plain endpoints `POST /api/catalog/departments` and `/data-sources` **merge** when the code exists (only the fields sent are changed;
the pinned key, manifest digest, onboarded flag, health and creation time are kept). They cannot replace an existing identity (409), and a
data source cannot be moved to another department (400).

A manifest's journeys must name the manifest's own department as `requester`, and a journey code that already exists is adopted only when its
requester is that department (or it is a hand-made row with none); another department's journey refuses the onboarding.

## 3. The department's signing key

Kept by the department, never published (only the public half travels in the header). The reference services keep it in a file
(`<dept>.manifest.key-file`, created on first start, owner-only) so the approved thumbprint survives restarts. Deleting the file
rotates the key. A real department should hold it in its own key store / HSM.

## 4. The discovery credential (optional)

A department may refuse to show its manifest to anyone but Samanvay. If it does, it issues Samanvay a **discovery credential** out
of band, and Samanvay sends it as `X-Discovery-Key: <value>` on the manifest request.

- The operator provisions the value into Samanvay's SecretStore under `manifest-<host>[---<port>]-credential`. In the host a dot is one
  dash and a real dash is two, and the port follows three dashes, so two different hosts can never share a key (before, `a.b` and
  `a-b` both gave `manifest-a-b-credential`, so a credential meant for one host could be sent to the other). Examples:
  `https://dept.example.gov` -> `manifest-dept-example-gov-credential`; `http://127.0.0.1:8091` ->
  `manifest-127-0-0-1---8091-credential`; `https://dept-1.example.gov` -> `manifest-dept--1-example-gov-credential`. A host that is
  not letters, digits, dots and dashes (an IPv6 literal) has no key, so no credential is ever sent to it.
- **Compatibility:** the old name (`manifest-127-0-0-1-8091-credential`) is still accepted as a fallback, but only where it cannot
  belong to another host: a host with **no dash** on an explicit **port**. With no port the old and new names are the same. A host with
  a dash in its name must be re-provisioned under the new name (the old name was shared with the dotted host). The error message
  always names the new key.
- If the department answers 401/403, Samanvay's error names that exact key. The value is never shown.
- Reference services: `<dept>.manifest.discovery-key` (empty = the manifest is public).

## 5. What this does not do

- It does not replace HTTPS. It protects the manifest's **integrity and origin**; confidentiality is TLS's job.
- Pinning is **trust on first approval**: an admin who approves a thumbprint without checking it with the department has trusted
  whoever answered first.
- It is separate from the login-assertion key (`docs/contracts/login-assertion.md`), which rotates freely and is fetched from a JWKS.
