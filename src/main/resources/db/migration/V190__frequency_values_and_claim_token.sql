-- V190__frequency_values_and_claim_token.sql (forward-only)
--
-- 1. frequency is no longer free text. A typo used to switch the one-check rule
--    off silently; now the database refuses it, and Java maps the value to
--    Purpose.Frequency, failing loudly on anything else. Allowed values are
--    exactly the ones the V186 seeds use. NULL stays allowed: legacy purposes
--    (V181/V184) have no frequency, and consents granted before V189 were never
--    given one.
ALTER TABLE catalog_purpose
    ADD CONSTRAINT catalog_purpose_frequency_known
        CHECK (frequency IN ('ONCE', 'ONCE_PER_DOCUMENT_PER_APPLICATION', 'ONCE_PER_PAYMENT', 'ONCE_PER_YEAR'));
ALTER TABLE consent_artifact
    ADD CONSTRAINT consent_artifact_frequency_known
        CHECK (frequency IN ('ONCE', 'ONCE_PER_DOCUMENT_PER_APPLICATION', 'ONCE_PER_PAYMENT', 'ONCE_PER_YEAR'));

-- 2. Claim token. Every PENDING claim, including a stale takeover, writes a NEW
--    random token. Marking USED and releasing both match (id, claim_token,
--    state = 'PENDING'), so a check whose claim was taken over while its
--    department call was still running cannot settle the new owner's claim:
--    it updates 0 rows, its result is thrown away, and CONSENT_CHECK_LOST_CLAIM
--    is audited. Existing rows get a token of their own.
ALTER TABLE consent_usage ADD COLUMN claim_token UUID;
UPDATE consent_usage SET claim_token = gen_random_uuid() WHERE claim_token IS NULL;
ALTER TABLE consent_usage ALTER COLUMN claim_token SET NOT NULL;

-- 3. The per-check key is a scope, not always an application. application_id
--    becomes scope_key; for ONCE and ONCE_PER_DOCUMENT_PER_APPLICATION it holds
--    the application (journey instance) id. The UNIQUE constraint
--    consent_usage_one_check follows the column: (consent_id, document_type,
--    scope_key). No other scope (e.g. per payment) is written yet.
ALTER TABLE consent_usage RENAME COLUMN application_id TO scope_key;
