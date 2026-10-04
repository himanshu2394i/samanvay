-- Lookups by citizen / consent that had no index (Postgres does not index foreign keys on its own).
CREATE INDEX IF NOT EXISTS idx_identity_link_citizen          ON identity_link (citizen_id);
CREATE INDEX IF NOT EXISTS idx_grant_consent                  ON consent_access_grant (consent_id);
CREATE INDEX IF NOT EXISTS idx_consent_request_subject        ON consent_request (subject_citizen_id);
CREATE INDEX IF NOT EXISTS idx_consent_event_consent          ON consent_event (consent_id);
CREATE INDEX IF NOT EXISTS idx_orchestration_instance_citizen ON orchestration_instance (citizen_id);
