# HMAC request signing - contract v1

For a department that wants every call from Samanvay signed (a shared secret that never travels). Declared in the department's
manifest `auth` block as scheme `HMAC_SHA256`. Implemented by Samanvay's REST adapter (`RestAuth`).

## Credential (provisioned by the operator, never in the manifest)

A JSON object in the SecretStore key `source-<code>-credential`:

| Parameter | Meaning |
|---|---|
| `key_id` | Identifies which secret signed the request (sent in a header). Not secret. |
| `secret` | The shared HMAC key. Never sent. |

## What Samanvay sends

| Header | Value |
|---|---|
| `X-Key-Id` | the `key_id` |
| `X-Timestamp` | the signing time, epoch seconds (UTC) |
| `X-Signature` | base64 of HMAC-SHA256(`secret`, canonical string) |

The **canonical string** is four lines joined by `\n` (no trailing newline):

```
METHOD                         GET or POST, upper case
PATH_AND_QUERY                 the raw path and raw query exactly as sent, e.g. /v1/farmer/AG%201?lang=mr
TIMESTAMP                      the same value as X-Timestamp
HEX_SHA256_OF_BODY             lower-case hex SHA-256 of the request body bytes ("" for a GET)
```

## What the department checks

1. Look up the secret for `X-Key-Id`; refuse an unknown key.
2. Recompute the signature from the request exactly as received and compare in constant time.
3. Refuse a stale `X-Timestamp` (the department chooses the window, typically a few minutes) to limit replay.
4. Optionally remember recent signatures to refuse an exact replay.

Because the body hash is signed, a changed body, path or query no longer matches. The secret is never in a header, URL or log.
A `POST` body is JSON (`application/json`) when the connector declares `body_inputs`.

## Limits

Samanvay signs REST `GET` and `POST` only. It does not sign SOAP (use WS-Security) or SFTP/JDBC (they have their own logins).
