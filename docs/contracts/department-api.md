# Department API - contract v1

What a department's portal server calls on Samanvay, after a citizen signed in on the department's own portal. Samanvay has no
citizen sign in and no citizen screens: a person is known by the department login they proved.

## Who may call

A department's server, with its own credential: the Keycloak client `dept-<code>` of the staff realm, using the client-credentials
grant. The token carries the role `department` and a `department` claim fixed on the client. Samanvay reads the department from the
token, never from a body. Every call is audited with the client as the actor. Never send this token to a browser.

## Calls

All JSON, all under `/api/department` unless noted. A department acts only as itself and only for citizens who signed in with it
(a citizen that is not one of its own is `404`, so the answer reveals nothing).

| Call | Does |
|---|---|
| `POST /citizens/resolve` `{assertion}` | Verifies the home sign-in assertion (docs/contracts/login-assertion.md). Returns `{citizenId, created}`: the citizen linked to (department, person ID), made on the first sign in from the assertion's `name` and `dob`. |
| `POST /links/start` `{citizenId, departmentCode, returnTo}` | Starts a login at another department. Returns `{loginUrl}`. `returnTo` must be an address on the calling department's own host (the host of its manifest `identity.loginUrl`). |
| `POST /links` `{citizenId, departmentCode, assertion}` | Saves the link proven by that department's assertion (it must carry the state issued by `/links/start` for this citizen). Returns `{citizenId}`: the surviving citizen. If the person is already linked to another citizen and this citizen is an empty record made by a home sign in, the two records are merged and the other citizen's ID is returned; any other collision is `409`. |
| `GET /journeys/{code}/readiness?citizenId=` | `{journeyCode, departments:[{departmentCode, departmentName, categories, linked, departmentLoginAvailable}], consentActive}` for a journey the caller runs. It says which departments the citizen is connected to and never carries a department's person ID. |
| `POST /consents/requests` `{citizenId, journeyCode}` | The exact consent wording to show, and a one-time nonce: `{requestId, purposeCode, purposeText, categories, providers:[{code,name}], validityDays, nonce, expiresAt}`. |
| `POST /consents` `{statement}` | Grants the consent from the department's signed statement (docs/contracts/department-consent-statement.md). |
| `POST /api/journeys/{code}/start` `{citizenId, submission}` | Starts a journey the caller runs, for a citizen linked to the caller (any other citizen is `404`). The caller needs no per-source scope: consent and the citizen's links decide what is fetched. `409` if the journey is not published or the citizen already has an open application for it. |
| `GET /api/applications?citizenId=`, `/{ref}`, `/{ref}/steps`, `/{ref}/issued-records` | Only applications of journeys the caller runs; another department's are `404`. |

## Errors

`400` bad input, `401` no token or a proof that does not verify (one generic message), `403` the caller's department does not run
that journey, `404` not found (including "not your citizen"), `409` the link or merge is not allowed.
