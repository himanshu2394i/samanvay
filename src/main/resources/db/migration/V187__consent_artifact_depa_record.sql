-- V187__consent_artifact_depa_record.sql
-- The consent artefact becomes a DEPA-style record: besides who (citizen,
-- requester), what (purpose code, data categories) and when (valid_from =
-- created, valid_until = expires, capped by the purpose's max_duration_days),
-- it now keeps
--   data_types  - the purpose's data-type descriptors, copied from the catalog
--                 at grant time (later catalog edits never change a record)
--   revoked_at  - when the citizen withdrew it
--   revoked_by  - the token subject that withdrew it
--
-- Retention POLICY: consent records are kept for 7 years after they end
-- (revoked or expired). This is a platform policy, not a legal claim; no
-- purge job exists yet, and nothing here deletes rows.
--
-- Existing rows: data_types stays empty and revoked_at/by NULL (a consent
-- revoked before this migration has no recorded time/actor; the REVOKED
-- consent_event row still exists). Nothing is backfilled.
-- Numbered above every existing migration (Flyway outOfOrder=false); see V180.
ALTER TABLE consent_artifact
    ADD COLUMN data_types TEXT[]       NOT NULL DEFAULT '{}',
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoked_by VARCHAR(100),
    ADD CONSTRAINT consent_artifact_revoked_fields
        CHECK (revoked_at IS NULL OR status = 'REVOKED');
