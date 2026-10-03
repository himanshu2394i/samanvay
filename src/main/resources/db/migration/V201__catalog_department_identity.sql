-- How a citizen proves who they are at a department (docs/contracts/login-assertion.md): the manifest's `identity`
-- block (person-ID type, login URL, public-keys URL, assertion issuer). Public metadata only. '{}' = the department
-- publishes no login, so it cannot be linked by a department login (the other proof kinds are unaffected).
ALTER TABLE catalog_department
    ADD COLUMN identity_spec JSONB NOT NULL DEFAULT '{}'::jsonb;
