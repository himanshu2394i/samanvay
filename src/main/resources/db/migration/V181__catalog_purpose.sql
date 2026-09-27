-- V181__catalog_purpose.sql
-- Catalog-owned purpose codes. An AccessGrant's purpose must be one of these
-- (consent refuses unknown/retired codes), so audit entries carry a governed
-- code, never free text.
--
-- Shape follows the DEPA consent-artefact "Purpose" object:
--   { "code", "refUri", "text", "Category": { "type" } }
--
-- Numbered above every existing migration (Flyway outOfOrder=false); see V180.
CREATE TABLE catalog_purpose (
    code           VARCHAR(60)  PRIMARY KEY,
    text           VARCHAR(500) NOT NULL,
    ref_uri        VARCHAR(300),
    category_type  VARCHAR(60)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- No purpose data of its own: register exactly the purposes the catalog's
-- existing journeys already declare, so those journeys keep working. New
-- purposes arrive through catalog administration, not migrations.
INSERT INTO catalog_purpose (code, text, category_type)
SELECT DISTINCT ON (policy->>'purpose')
       policy->>'purpose',
       'Purpose declared by catalog journey ' || name,
       'JOURNEY'
FROM catalog_journey
WHERE COALESCE(policy->>'purpose', '') <> ''
ORDER BY policy->>'purpose', code;
