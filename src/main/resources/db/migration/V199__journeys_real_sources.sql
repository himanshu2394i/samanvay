-- Repoint the five journey connectors that now have a real independent department service
-- (INCOME/MARKS/BANK via the standalone department-service, PROPERTY via department-sftp,
-- POLLUTION via department-db) from the in-process mock backend to real-transport data sources.
--
-- Additive new sources + surgical UPDATEs. Sibling connectors on the shared mock sources
-- (CASTE, LAND_RECORD, LAND_PARCEL, CROP, FIRE_NOC) are untouched and keep resolving to mock.
-- Hosts are non-resolving `.example` names, so the adapter routes them through the REAL transports
-- (base_host != mock.samanvay.test); dev/demo config maps the source codes to the real hosts.
-- Credentials are never here: auth_config_ref only NAMES a SecretStore key. Forward-only; V199 > V198.

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('dept-income-rest', 'REVENUE', 'REST', 'rest.revenue.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('dept-marks-soap', 'EDUCATION', 'SOAP', 'soap.education.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('dept-bank-rest', 'DBT', 'REST', 'rest.dbt.samanvay.example', 'NONE', 'secret:none', 'UNKNOWN'),
    ('dept-property-sftp', 'MUNICIPAL', 'SFTP_CSV', 'sftp.municipal.samanvay.example', 'PASSWORD', 'secret:dept-property-sftp', 'UNKNOWN'),
    ('dept-pollution-jdbc', 'POLLUTION', 'JDBC', 'jdbc.pollution.samanvay.example', 'PASSWORD', 'secret:dept-pollution-jdbc', 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

-- REST income: /income -> /v1/income (mapping annualIncome/holderName already matches the real service).
UPDATE catalog_connector SET data_source_code = 'dept-income-rest',
    capabilities = '{"FETCH":{"endpoint":"/v1/income","mapping_ref":"map-rev-income@1","output_schema":"Credential/IncomeCertificate@1","error_paths":[]}}'
    WHERE ref = 'rev-income@1';

-- SOAP marks: /marks -> /marks/service, template carries <studentId> as the real service parses.
UPDATE catalog_connector SET data_source_code = 'dept-marks-soap',
    capabilities = '{"FETCH":{"endpoint":"/marks/service","template":"<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><GetMarks><studentId>{{studentId}}</studentId></GetMarks></soap:Body></soap:Envelope>","mapping_ref":"map-edu-marks@1","output_schema":"Credential/Marks@1","error_paths":[]}}'
    WHERE ref = 'edu-marks@1';

-- REST bank: endpoint /bank unchanged, now the real department-service /bank.
UPDATE catalog_connector SET data_source_code = 'dept-bank-rest' WHERE ref = 'dbt-bank@1';

-- SFTP property: /property -> /outbound/property.csv.
UPDATE catalog_connector SET data_source_code = 'dept-property-sftp',
    capabilities = '{"FETCH":{"endpoint":"/outbound/property.csv","mapping_ref":"map-muni-property@1","output_schema":"Credential/PropertyTax@1","error_paths":[]}}'
    WHERE ref = 'muni-property@1';

-- JDBC pollution: template is already the real SELECT; only move the source.
UPDATE catalog_connector SET data_source_code = 'dept-pollution-jdbc' WHERE ref = 'pcb-clearance@1';

-- The real JDBC client lowercases column labels, so the mapping source must be clearance_status
-- (the mock backend returned camelCase clearanceStatus). Target stays clearanceStatus, so the
-- canonical output and its output schema are unchanged.
UPDATE catalog_mapping SET rules = '[{"source":"clearance_status","target":"clearanceStatus","transforms":[{"fn":"upper","args":[]}]}]'
    WHERE ref = 'map-pcb-clearance@1';
