## Module / Area

<!-- e.g. identity, connector, catalog -->

## What changed

<!-- Short description -->

## Why

<!-- Link the phase/task this covers. See docs/architecture/hld/<module>.md for the module's acceptance criteria. -->

## Tests

- [ ] Added a test before writing the implementation
- [ ] Ran it, confirmed it failed for the expected reason first
- [ ] `./mvnw verify` passes locally

## Checklist

- [ ] Stays inside this module's package (`com.samanvay.<module>.*`) — no reaching into another module's internals
- [ ] Only the module's `api` package is touched by other modules
- [ ] No secrets, credentials, or `.env` values committed
- [ ] Relevant module doc in `docs/architecture/hld/` still matches what was built (update it if a design decision changed)
