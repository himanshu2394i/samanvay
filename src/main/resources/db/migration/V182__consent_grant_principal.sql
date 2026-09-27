-- V182__consent_grant_principal.sql
-- An access grant records who triggered it (from the authenticated token):
-- an officer subject, a department client id, or the citizen themself.
-- The same pair is inside the Ed25519-signed grant body, so it cannot be
-- altered between consent and connector.
--
-- Nullable: grants issued before this migration have no recorded principal
-- and are left untouched (they are 60-second, single-use capabilities and
-- are never rewritten). Every grant issued from now on sets both columns.
-- Numbered above every existing migration (Flyway outOfOrder=false); see V180.
ALTER TABLE consent_access_grant
    ADD COLUMN principal_type VARCHAR(20)
        CHECK (principal_type IN ('CITIZEN','OFFICER','REVIEWER','ADMIN','DEPARTMENT')),
    ADD COLUMN principal_id   VARCHAR(100);
