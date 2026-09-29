-- V188__catalog_journey_academic_year_start_month.sql
-- A scheme's academic year is scheme (journey) configuration, not purpose
-- data: the same purpose may serve several schemes, and an award is an
-- approved application of one journey, so the award's own journey says which
-- academic year it belongs to. Consent reads it for PRIOR_AWARD_DEPARTMENT
-- purposes (e.g. SCH_RENEWAL_CHECK): a renewal needs an approved award in the
-- academic year immediately before the current one (Asia/Kolkata), with both
-- years computed from the award journey's start month.
--
--   academic_year_start_month - 1..12; NULL: the scheme has no academic year,
--                               so its approvals never count as prior awards
--
-- Kept out of V186 on purpose: V186 is catalog_purpose; this is the journey
-- table. Forward-only; numbered above every existing migration (see V180).
ALTER TABLE catalog_journey
    ADD COLUMN academic_year_start_month INT
        CHECK (academic_year_start_month BETWEEN 1 AND 12);

-- The scholarship scheme's academic year starts in June.
UPDATE catalog_journey SET academic_year_start_month = 6 WHERE code = 'POST_MATRIC_SCHOLARSHIP';
