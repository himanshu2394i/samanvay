-- DEV/DEMO ONLY. Fake Agriculture department farmer data. Never real.
--
-- Runs once on first container init (postgres /docker-entrypoint-initdb.d), as the container's
-- POSTGRES_USER against POSTGRES_DB (agridb). Samanvay never sees the base table: it logs in as the
-- read-only role agri_ro, which can SELECT from the VIEW v_farmer_record only. The view exposes
-- the fields Agriculture chooses to share and hides the rest (internal_notes).

CREATE TABLE IF NOT EXISTS farmer (
    agri_person_id  VARCHAR(32) PRIMARY KEY,
    farmer_name     VARCHAR(120) NOT NULL,
    village         VARCHAR(80)  NOT NULL,
    taluka          VARCHAR(80)  NOT NULL,
    land_hectares   NUMERIC(6, 2) NOT NULL,
    internal_notes  TEXT
);

INSERT INTO farmer (agri_person_id, farmer_name, village, taluka, land_hectares, internal_notes) VALUES
    ('AG-1001', 'Asha Patil',     'Ojhar', 'Nashik', 1.85, 'internal: subsidy audit pending'),
    ('AG-1002', 'Ravi Deshmukh',  'Loni',  'Haveli', 0.92, 'internal: none')
ON CONFLICT (agri_person_id) DO NOTHING;

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
