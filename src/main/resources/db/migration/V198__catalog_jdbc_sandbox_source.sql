-- Onboard the fourth protocol (JDBC) as a REAL-transport sandbox source, completing REST/SOAP/SFTP
-- (V193) so all four protocols are onboardable against deployable sandbox services. base_host is NOT
-- the mock sentinel (mock.samanvay.test), so JdbcAdapter routes it through the real JdbcQueryClient.
--
-- Additive only; idempotent (ON CONFLICT DO NOTHING). Forward-only; V198 follows V197. The rows live
-- under the existing SANDBOX department (V193) with their own data category, so existing catalog
-- resolves are untouched.
--
-- The base_host is an `.example` name (RFC 2606) that never resolves and is unused by the JDBC path:
-- JdbcQueryClient connects to samanvay.sources.jdbc.sources.sandbox-pollution-jdbc.jdbc-url (set to
-- the deployable dept DB in application-demo.yml). Credentials are never here: auth_config_ref only
-- NAMES SecretStore key source-sandbox-pollution-jdbc-credential (value username:password). Only a
-- parameterized SELECT is ever run (JdbcSqlGuard), as a read-only DB account.

INSERT INTO catalog_department (code, name, idp_realm, contact_email, default_sla_ms, status) VALUES
    ('SANDBOX', 'Partner Sandbox (real-transport demo)', NULL, 'sandbox@example.gov', 5000, 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('sandbox-pollution-jdbc', 'SANDBOX', 'JDBC', 'jdbc.sandbox.samanvay.example', 'PASSWORD', 'secret:sandbox-pollution-jdbc', 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('Credential/SandboxPollution@1', 'SandboxPollution', 1, '{"type":"object","required":["clearanceStatus"]}')
ON CONFLICT (ref) DO NOTHING;

INSERT INTO catalog_connector (ref, connector_id, version, data_source_code, data_category, capabilities, inputs, sla_ms, status) VALUES
    ('sandbox-pollution@1', 'sandbox-pollution', 1, 'sandbox-pollution-jdbc', 'SANDBOX_POLLUTION',
     '{"FETCH":{"endpoint":"/pollution","template":"SELECT clearance_status, holder FROM pcb_clearance WHERE premise_id = :premiseId","mapping_ref":"map-sandbox-pollution@1","output_schema":"Credential/SandboxPollution@1","error_paths":[]}}',
     '[{"name":"premiseId","from":"link.localIdToken","required":true}]', 5000, 'PUBLISHED')
ON CONFLICT (ref) DO NOTHING;

INSERT INTO catalog_mapping (ref, connector_ref, rules) VALUES
    ('map-sandbox-pollution@1', 'sandbox-pollution@1',
     '[{"source":"clearance_status","target":"clearanceStatus","transforms":[{"fn":"upper","args":[]}]},{"source":"holder","target":"holder","transforms":[{"fn":"trim","args":[]}]}]')
ON CONFLICT (ref) DO NOTHING;
