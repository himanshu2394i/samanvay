-- V189__consent_usage_one_check.sql
-- Frequency enforcement: a consent whose frequency allows one check lets each
-- document be checked once per application. A usage row is claimed (PENDING)
-- in the same transaction that issues the access grant, committed BEFORE the
-- connector is called, then marked USED on success or deleted on failure
-- (timeout, 5xx, malformed reply) so the citizen can retry.
--
-- The UNIQUE constraint below is the enforcement: two concurrent checks for the
-- same (consent, document type, application) cannot both claim a row. A PENDING
-- row older than 2x the configured connector timeout counts as released and is
-- taken over atomically by INSERT ... ON CONFLICT ... DO UPDATE ... WHERE.
--
-- consent_artifact.frequency: the purpose's frequency, copied at grant time
-- (like data_types), so later catalog edits never change an existing consent.
-- Existing consents keep NULL (no per-application rule).
-- Numbered above every existing migration (Flyway outOfOrder=false); see V180.
ALTER TABLE consent_artifact ADD COLUMN frequency VARCHAR(60);

CREATE TABLE consent_usage (
    id              UUID         PRIMARY KEY,
    consent_id      UUID         NOT NULL REFERENCES consent_artifact(id),
    document_type   VARCHAR(60)  NOT NULL,
    application_id  VARCHAR(100) NOT NULL,
    grant_id        UUID         NOT NULL,
    state           VARCHAR(10)  NOT NULL CHECK (state IN ('PENDING','USED')),
    claimed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    used_at         TIMESTAMPTZ,
    CONSTRAINT consent_usage_one_check UNIQUE (consent_id, document_type, application_id),
    CONSTRAINT consent_usage_used_at CHECK ((state = 'USED') = (used_at IS NOT NULL))
);
CREATE INDEX idx_consent_usage_grant ON consent_usage (grant_id);
