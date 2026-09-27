# IFSC / bank-check simulator: where each field comes from

The simulator implements **Samanvay's own bank-check contract v1**,
[`docs/contracts/bank-check-v1.yaml`](../../../../../docs/contracts/bank-check-v1.yaml). It is not a vendor API.
The live source is not decided yet (likely PFMS or the department's own validation); a live adapter will
map that system onto the contract. If the simulator and the contract ever disagree, the contract wins.

## IFSC format: RBI
An IFSC has 11 characters: 4 letters for the bank code, then `0` (a reserved position), then 6 alphanumerics
for the branch code (RBI, NEFT/RTGS procedural guidelines). Regex: `^[A-Z]{4}0[A-Z0-9]{6}$`.

## `GET /{ifsc}`: public IFSC lookup
| Field | Source |
|---|---|
| `BANK`, `IFSC`, `BRANCH`, `CENTRE`, `DISTRICT`, `STATE`, `ADDRESS`, `CONTACT`, `CITY`, `MICR`, `SWIFT`, `ISO3166`, `BANKCODE`, `IMPS`, `NEFT`, `RTGS`, `UPI` | Keys of the open razorpay/ifsc dataset, which republishes the RBI IFSC list. The values for the 6 real branches in `ifsc-bank.json` are copied verbatim from https://ifsc.razorpay.com/{ifsc} (2026-09-28) |
| 404 with body `"Not Found"` | What the public dataset API returns for unknown and malformed codes |

Public, with no credentials: the branch list is open RBI data.

## `POST /v1/bank-checks`: bank check (HTTP Basic key id + secret)
| Field | Source |
|---|---|
| request `ifsc`, `accountNumber`, `applicantName` | contract v1 |
| response `accountStatus` = `VALID` / `CLOSED` / `INVALID` | contract v1, taken as-is from the fixture account |
| response `nameMatch` = `MATCH` / `PARTIAL` / `NO_MATCH` / `NOT_CHECKED` | contract v1, taken as-is from the fixture account. **The simulator does no matching** and ignores the value of `applicantName` |
| 400 / 401 / 503 `application/problem+json` (RFC 9457), `invalidParams[].name` on 400 | contract v1 |

The response never contains the holder name. Fixture `holder_name` values are canary strings
(`SIMULATED Canary <tag>`). The simulator doesn't even load them into memory, and `BankCheckCanaryLeakIT`
in the main app fails if one shows up anywhere on the Samanvay side.

## Fixture outcomes (fixed)
| IFSC / account | accountStatus | nameMatch |
|---|---|---|
| SBIN0000300 / 00001000000001 | VALID | MATCH |
| MAHB0000001 / 00001000000002 | VALID | PARTIAL |
| HDFC0000001 / 00001000000003 | VALID | NO_MATCH |
| SBIN0001593 / 00001000000006 | VALID | NOT_CHECKED (stands for a holder name in another script) |
| BKID0000150 / 00001000000004 | CLOSED | NOT_CHECKED |
| UTIB0000004 / 00001000000005 | INVALID | NOT_CHECKED |
| any other account, or a well-formed IFSC not in the list | INVALID | NOT_CHECKED |
| malformed IFSC / account number, blank name | 400, `invalidParams` | |

## Faults
Triggered by bank code `SAMS` (IFSC `SAMS0000408`/`500`/`422`, not RBI-allotted) or account numbers
`00009000000408`/`500`/`422`, or by the header `X-Samanvay-Simulator-Fault: timeout|server_error|malformed`:
- timeout: the answer is held for `simulator.faults.timeout-delay`.
- server_error: 503 problem.
- malformed: 200 with a truncated JSON body.

Fault bodies are fixed text. They never echo request or fixture data.

## Simulator marker
Every response has the header `X-Samanvay-Simulator: true`. Every JSON-object body also has
`"samanvay_simulator": true`. The IFSC 404 body (a bare JSON string) and the deliberately malformed body
only carry the header.
