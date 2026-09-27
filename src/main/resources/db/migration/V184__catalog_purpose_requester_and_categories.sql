-- V184__catalog_purpose_requester_and_categories.sql
-- A consent request's requester, purpose text and data categories now come
-- from the catalog purpose, never from the request body:
--   requester_department - the department that may request consent under
--                          this purpose (an officer/department client of any
--                          other department is refused)
--   data_categories      - the data categories the purpose covers
--
-- Backfilled from the journeys that declare each purpose (the same source
-- V181 used), so existing journeys keep working. Numbered above every
-- existing migration (Flyway outOfOrder=false); see V180.
ALTER TABLE catalog_purpose
    ADD COLUMN requester_department VARCHAR(60) REFERENCES catalog_department(code),
    ADD COLUMN data_categories      TEXT[]      NOT NULL DEFAULT '{}';

UPDATE catalog_purpose p
SET requester_department = j.requester,
    data_categories      = j.categories
FROM (
    SELECT DISTINCT ON (policy->>'purpose')
           policy->>'purpose'   AS purpose,
           policy->>'requester' AS requester,
           required_categories  AS categories
    FROM catalog_journey
    WHERE COALESCE(policy->>'purpose', '') <> ''
    ORDER BY policy->>'purpose', code
) j
WHERE j.purpose = p.code;
