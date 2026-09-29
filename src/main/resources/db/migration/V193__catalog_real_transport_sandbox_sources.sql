-- Onboard one REAL-transport source per protocol (REST, SOAP, SFTP_CSV), pointed at
-- sandbox / fake hosts. Their base_host is NOT the mock sentinel (mock.samanvay.test),
-- so RestAdapter / SoapAdapter / SftpCsvAdapter route them through the real transports.
--
-- Additive only. The mock/simulator sources (V21-V23) are untouched, and these rows live
-- under their own SANDBOX department with their own data categories, so
-- ConnectorCatalog.resolve(<existing department>, <existing category>, ...) still finds
-- exactly the mock connectors it found before.
--
-- Hosts are `.example` (RFC 2606) names that never resolve: a real call to them fails
-- as a transport fault (Unavailable), it can never reach a live department. Point them
-- at a real sandbox by updating base_host in a later migration.
--
-- Credentials are never here. auth_config_ref only NAMES a SecretStore entry:
--   * REST / SOAP: the adapters send no credential today, so auth_type NONE.
--   * SFTP: secret:sandbox-property-sftp -> SecretStore key
--     source-sandbox-property-sftp-credential (value username:password). Connection
--     details (host, port, remote path, pinned host key) live in
--     samanvay.sources.sftp.sources.sandbox-property-sftp (application-demo.yml).
--
-- Idempotent: every insert is ON CONFLICT DO NOTHING. Forward-only; V193 follows V192.

INSERT INTO catalog_department (code, name, idp_realm, contact_email, default_sla_ms, status) VALUES
    ('SANDBOX', 'Partner Sandbox (real-transport demo)', NULL, 'sandbox@example.gov', 5000, 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('sandbox-income-rest', 'SANDBOX', 'REST', 'rest.sandbox.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('sandbox-marks-soap', 'SANDBOX', 'SOAP', 'soap.sandbox.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('sandbox-property-sftp', 'SANDBOX', 'SFTP_CSV', 'sftp.sandbox.samanvay.example', 'PASSWORD', 'secret:sandbox-property-sftp', 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('Credential/SandboxIncome@1', 'SandboxIncome', 1, '{"type":"object","required":["annualIncome"]}'),
    ('Credential/SandboxMarks@1', 'SandboxMarks', 1, '{"type":"object","required":["percentage"]}'),
    ('Credential/SandboxProperty@1', 'SandboxProperty', 1, '{"type":"object","required":["propertyRef"]}')
ON CONFLICT (ref) DO NOTHING;

INSERT INTO catalog_connector (ref, connector_id, version, data_source_code, data_category, capabilities, inputs, sla_ms, status) VALUES
    ('sandbox-income@1', 'sandbox-income', 1, 'sandbox-income-rest', 'SANDBOX_INCOME',
     '{"FETCH":{"endpoint":"/v1/income","mapping_ref":"map-sandbox-income@1","output_schema":"Credential/SandboxIncome@1","error_paths":[]}}',
     '[{"name":"rationCard","from":"link.localIdToken","required":true}]', 5000, 'PUBLISHED'),
    ('sandbox-marks@1', 'sandbox-marks', 1, 'sandbox-marks-soap', 'SANDBOX_MARKS',
     '{"FETCH":{"endpoint":"/marks/service","template":"<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><GetMarks><studentId>{{studentId}}</studentId></GetMarks></soap:Body></soap:Envelope>","mapping_ref":"map-sandbox-marks@1","output_schema":"Credential/SandboxMarks@1","error_paths":[]}}',
     '[{"name":"studentId","from":"link.localIdToken","required":true}]', 5000, 'PUBLISHED'),
    ('sandbox-property@1', 'sandbox-property', 1, 'sandbox-property-sftp', 'SANDBOX_PROPERTY',
     '{"FETCH":{"endpoint":"/outbound/property.csv","mapping_ref":"map-sandbox-property@1","output_schema":"Credential/SandboxProperty@1","error_paths":[]}}',
     '[{"name":"propertyId","from":"link.localIdToken","required":true}]', 5000, 'PUBLISHED')
ON CONFLICT (ref) DO NOTHING;

INSERT INTO catalog_mapping (ref, connector_ref, rules) VALUES
    ('map-sandbox-income@1', 'sandbox-income@1',
     '[{"source":"annualIncome","target":"annualIncome","transforms":[{"fn":"trim","args":[]}]},{"source":"holderName","target":"holderName","transforms":[{"fn":"trim","args":[]}]}]'),
    ('map-sandbox-marks@1', 'sandbox-marks@1',
     '[{"source":"percentage","target":"percentage","transforms":[]},{"source":"board","target":"board","transforms":[{"fn":"upper","args":[]}]}]'),
    ('map-sandbox-property@1', 'sandbox-property@1',
     '[{"source":"propertyRef","target":"propertyRef","transforms":[{"fn":"trim","args":[]}]},{"source":"ward","target":"ward","transforms":[{"fn":"trim","args":[]}]}]')
ON CONFLICT (ref) DO NOTHING;
