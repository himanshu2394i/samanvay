-- DEV/DEMO ONLY. Fake Agriculture department farmer data. Never real.
--
-- Runs once on first container init (postgres /docker-entrypoint-initdb.d), as the container's
-- POSTGRES_USER against POSTGRES_DB (agridb). Samanvay never sees the base table: it logs in as the
-- read-only role agri_ro, which can SELECT from the VIEW v_farmer_record only. The view exposes
-- the fields Agriculture chooses to share and hides the rest (internal_notes).

-- bcrypt password hashes (crypt(password, gen_salt('bf'))), as in schema.sql.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS farmer (
    agri_person_id  VARCHAR(32) PRIMARY KEY,
    farmer_name     VARCHAR(120) NOT NULL,
    village         VARCHAR(80)  NOT NULL,
    taluka          VARCHAR(80)  NOT NULL,
    land_hectares   NUMERIC(6, 2) NOT NULL,
    internal_notes  TEXT,
    date_of_birth   DATE
);

-- A database made by an older copy of this script has no date of birth column yet.
ALTER TABLE farmer ADD COLUMN IF NOT EXISTS date_of_birth DATE;

-- Who can sign in to the farmer login, and which farmer each login belongs to (what JdbcCitizens reads).
CREATE TABLE IF NOT EXISTS citizen_login (
    mobile         VARCHAR(10)  PRIMARY KEY,
    password_hash  TEXT         NOT NULL,
    person_id      VARCHAR(32)  NOT NULL UNIQUE
);

INSERT INTO farmer (agri_person_id, farmer_name, village, taluka, land_hectares, internal_notes, date_of_birth) VALUES
    ('AG-1001', 'Asha Patil',     'Ojhar', 'Nashik', 1.85, 'internal: subsidy audit pending', '2004-03-09'),
    ('AG-1002', 'Ravi Deshmukh',  'Loni',  'Haveli', 0.92, 'internal: none',                  '2003-11-21')
ON CONFLICT (agri_person_id) DO NOTHING;

-- DEV logins for the two fake farmers (the same ones the service's built-in demo accounts use).
INSERT INTO citizen_login (mobile, password_hash, person_id) VALUES
    ('9000000001', crypt('asha-demo-pass', gen_salt('bf')), 'AG-1001'),
    ('9000000002', crypt('ravi-demo-pass', gen_salt('bf')), 'AG-1002')
ON CONFLICT (mobile) DO NOTHING;

CREATE VIEW v_farmer_record AS
    SELECT agri_person_id, farmer_name, village, taluka, land_hectares
    FROM farmer;

-- Read-only login role Samanvay uses. DEV credential for local fake data only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'agri_ro') THEN
        CREATE ROLE agri_ro LOGIN PASSWORD 'agri_ro_demo';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE agridb TO agri_ro;
GRANT USAGE ON SCHEMA public TO agri_ro;
GRANT SELECT ON v_farmer_record TO agri_ro;

-- The service's own account for the citizen login: reads the logins, and the farmer's name and date of birth, nothing else.
-- DEV credential for local fake data only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'agriculture_app') THEN
        CREATE ROLE agriculture_app LOGIN PASSWORD 'agriculture_app_demo';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE agridb TO agriculture_app;
GRANT USAGE ON SCHEMA public TO agriculture_app;
GRANT SELECT ON citizen_login TO agriculture_app;
GRANT SELECT (agri_person_id, farmer_name, date_of_birth) ON farmer TO agriculture_app;
