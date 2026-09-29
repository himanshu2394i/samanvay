-- DEV/DEMO ONLY. Fake pollution-control-board data for the JDBC sandbox source
-- (sandbox-pollution-jdbc, onboarded by catalog migration V198). Never real data.
--
-- Runs once on first container init (postgres /docker-entrypoint-initdb.d), as the
-- container's POSTGRES_USER against POSTGRES_DB (deptdb). The connector queries this
-- through a SEPARATE read-only role (pcb_ro), never the owner: a parameterized SELECT
-- only (JdbcSqlGuard), matching the SecretStore credential
-- source-sandbox-pollution-jdbc-credential (value pcb_ro:pcb_ro_demo).

CREATE TABLE IF NOT EXISTS pcb_clearance (
    premise_id       VARCHAR(64) PRIMARY KEY,
    clearance_status VARCHAR(32)  NOT NULL,
    holder           VARCHAR(120) NOT NULL
);

INSERT INTO pcb_clearance (premise_id, clearance_status, holder) VALUES
    ('PR-1001', 'clear',   'Acme Textiles Pvt Ltd'),
    ('PR-1002', 'pending', 'Beta Dyes & Chemicals'),
    ('PR-1003', 'clear',   'Ganesh Foods')
ON CONFLICT (premise_id) DO NOTHING;

-- Read-only login role the connector uses. DEV credential for local fake data only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pcb_ro') THEN
        CREATE ROLE pcb_ro LOGIN PASSWORD 'pcb_ro_demo';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE deptdb TO pcb_ro;
GRANT USAGE ON SCHEMA public TO pcb_ro;
GRANT SELECT ON pcb_clearance TO pcb_ro;
