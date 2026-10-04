-- Consent: the terms the citizen was asked about, kept on the request, so a grant is refused if the catalog changed them since
-- (NULL on requests made before this migration: nothing to compare, so they are not refused).
ALTER TABLE consent_request ADD COLUMN data_types TEXT[];
ALTER TABLE consent_request ADD COLUMN validity_days INT;

-- Identity: a citizen has at most one ACTIVE link per department, and a REVOKED link must not block linking the same person again.
-- Two ACTIVE links for one citizen and department would make every lookup ambiguous; keep the newest if any exist.
UPDATE identity_link l SET status = 'REVOKED'
WHERE l.status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM identity_link n
              WHERE n.citizen_id = l.citizen_id AND n.department_code = l.department_code AND n.status = 'ACTIVE'
                AND (n.created_at > l.created_at OR (n.created_at = l.created_at AND n.id > l.id)));

CREATE UNIQUE INDEX uq_identity_link_active_citizen_department
    ON identity_link (citizen_id, department_code) WHERE status = 'ACTIVE';

-- V40 made (department, type, token) unique for every row, so a REVOKED link held the person forever. Only ACTIVE links are unique.
ALTER TABLE identity_link DROP CONSTRAINT IF EXISTS identity_link_department_code_local_id_type_local_id_token_key;
CREATE UNIQUE INDEX uq_identity_link_active_local_id
    ON identity_link (department_code, local_id_type, local_id_token) WHERE status = 'ACTIVE';

-- A citizen made by a department sign in is named only if the department said the name; the person ID is no substitute for a name.
ALTER TABLE identity_profile ALTER COLUMN name_latin DROP NOT NULL;
