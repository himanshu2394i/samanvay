# IFSC / bank-account simulator: where each field comes from

The simulator copies the shape of public reference APIs. It does not invent a new API.
If this file and a published spec ever disagree, the spec wins; fix the simulator.

## IFSC format: RBI
An IFSC has 11 characters: 4 letters for the bank code, then `0` (a reserved position), then 6 alphanumerics
for the branch code (RBI, NEFT/RTGS procedural guidelines). The simulator's `RBI_IFSC` regex is
`^[A-Z]{4}0[A-Z0-9]{6}$`. Lookup is case-insensitive, as ifsc.razorpay.com is.

## `GET /{ifsc}`: Razorpay IFSC API (https://ifsc.razorpay.com/{ifsc})
| Field | Source |
|---|---|
| `BANK`, `IFSC`, `BRANCH`, `CENTRE`, `DISTRICT`, `STATE`, `ADDRESS`, `CONTACT`, `CITY`, `MICR`, `SWIFT`, `ISO3166`, `BANKCODE` | Razorpay IFSC API response keys (upper case), values copied verbatim for the real branches in `ifsc-bank.json` |
| `IMPS`, `NEFT`, `RTGS`, `UPI` | Razorpay IFSC API booleans |
| 404 with body `"Not Found"` (a JSON string) | What the live API returns for unknown **and** malformed codes (checked 2026-09-28) |
| `samanvay_simulator: true` | **Simulator-only** marker (see below) |

## `POST /v1/fund_accounts/validations`: Razorpay X account validation / penny drop
Reference: https://razorpay.com/docs/api/x/account-validation/ (entity + bank-account validation pages).
| Field | Source |
|---|---|
| auth: HTTP Basic `key_id:key_secret` | Razorpay X API authentication |
| `id` (`fav_...`), `entity` = `fund_account.validation`, `status` (`created`/`completed`/`failed`), `amount`, `currency`, `notes`, `created_at`, `utr` | Account Validation entity |
| `fund_account.{id, entity, account_type, bank_account.{name, bank_name, ifsc, account_number}, batch_id, active}` | Account Validation entity |
| `results.account_status` (`active`/`invalid`), `results.registered_name` | Account Validation entity |
| `status_details.{description, source, reference_id, reason}` | Documented fields. The **values** of `reason` (`success`, `invalid_account_number`, `account_closed`) are simulator choices: the docs don't list them |
| 400 `{"error":{"code":"BAD_REQUEST_ERROR","description","source","step","reason","metadata","field"}}` | Razorpay error envelope. The description texts are illustrative |
| 401 `The api key provided is invalid` | Razorpay auth-failure envelope |
| 503 `{"error":{"code":"SERVER_ERROR",...}}` | Razorpay 5xx error envelope |

**Known deviation (open question):** live Razorpay X takes `fund_account: {"id": "fa_..."}`, which means a
fund account (and a contact) has to be created first. To stay one call, the simulator takes the inline
`fund_account: {"account_type": "bank_account", "bank_account": {name, ifsc, account_number}}`, i.e. the
same object the response echoes back. The department's real account-verification API may not be
Razorpay at all; the shape is a stand-in for a typical penny-drop response.

## Deterministic outcomes
| Input | Outcome |
|---|---|
| Fixture account, supplied name == holder name | `completed`, `active`, `registered_name` = holder (**valid**) |
| Fixture account, a different supplied name | `completed`, `active`, `registered_name` = the real holder. The caller detects the **account mismatch** |
| Account number not held at that IFSC | `completed`, `invalid`, `registered_name` null, reason `invalid_account_number` |
| Account with `status: closed` | `completed`, `invalid`, `registered_name` null, reason `account_closed` (**closed account**) |
| IFSC that is malformed or not in the fixtures | 400 `BAD_REQUEST_ERROR`, `field: ifsc` (**invalid IFSC**) |
| Fault triggers (bank code `SAMS`, account numbers `00009000000xxx`) | timeout / 503 / truncated JSON |

## Simulator marker
Every response has the header `X-Samanvay-Simulator: true`. Every JSON-object body also has
`"samanvay_simulator": true`. The IFSC 404 body (a bare JSON string, as live) and the deliberately
malformed body only carry the header.
