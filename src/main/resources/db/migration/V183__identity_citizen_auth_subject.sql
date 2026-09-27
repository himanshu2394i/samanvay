-- V183__identity_citizen_auth_subject.sql
-- Binds a citizen record to the citizen-realm token subject that
-- self-registered it, so "a citizen may only act on their own citizenId"
-- can be enforced server-side. NULL for assisted (staff) registrations and
-- for every pre-existing row - nothing is backfilled.
-- Numbered above every existing migration (Flyway outOfOrder=false); see V180.
ALTER TABLE identity_citizen ADD COLUMN auth_subject VARCHAR(100);
CREATE UNIQUE INDEX uq_identity_citizen_auth_subject
    ON identity_citizen (auth_subject) WHERE auth_subject IS NOT NULL;
