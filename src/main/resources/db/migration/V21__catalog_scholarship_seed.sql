-- Journey 1 catalog rows. Hosts are the in-process mock sentinel (not registered via API).

INSERT INTO catalog_department (code, name, idp_realm, contact_email, default_sla_ms, status) VALUES
    ('REVENUE', 'Department of Revenue', 'revenue', 'revenue@example.gov', 3000, 'ACTIVE'),
    ('EDUCATION', 'Department of Education', 'education', 'education@example.gov', 3000, 'ACTIVE'),
    ('DBT', 'Direct Benefit Transfer', 'dbt', 'dbt@example.gov', 3000, 'ACTIVE'),
    ('SCHOLARSHIP', 'Scholarship Portal', 'scholarship', 'sch@example.gov', 3000, 'ACTIVE');

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('revenue-rest-mock', 'REVENUE', 'REST', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN'),
    ('education-soap-mock', 'EDUCATION', 'SOAP', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN'),
    ('dbt-rest-mock', 'DBT', 'REST', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN');

INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('Credential/IncomeCertificate@1', 'IncomeCertificate', 1, '{"type":"object","required":["annualIncome"]}'),
    ('Credential/CasteCertificate@1', 'CasteCertificate', 1, '{"type":"object","required":["casteCategory"]}'),
    ('Credential/Marks@1', 'Marks', 1, '{"type":"object","required":["percentage"]}'),
    ('Credential/BankAccount@1', 'BankAccount', 1, '{"type":"object","required":["accountRef"]}');

INSERT INTO catalog_connector (ref, connector_id, version, data_source_code, data_category, capabilities, inputs, sla_ms, status) VALUES
    ('rev-income@1', 'rev-income', 1, 'revenue-rest-mock', 'INCOME_CERTIFICATE',
     '{"FETCH":{"endpoint":"/income","mapping_ref":"map-rev-income@1","output_schema":"Credential/IncomeCertificate@1","error_paths":[]}}',
     '[{"name":"rationCard","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED'),
    ('rev-caste@1', 'rev-caste', 1, 'revenue-rest-mock', 'CASTE_CERTIFICATE',
     '{"FETCH":{"endpoint":"/caste","mapping_ref":"map-rev-caste@1","output_schema":"Credential/CasteCertificate@1","error_paths":[]}}',
     '[{"name":"rationCard","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED'),
    ('edu-marks@1', 'edu-marks', 1, 'education-soap-mock', 'MARKS',
     '{"FETCH":{"endpoint":"/marks","template":"<soap:Envelope><id>{{studentId}}</id></soap:Envelope>","mapping_ref":"map-edu-marks@1","output_schema":"Credential/Marks@1","error_paths":[]}}',
     '[{"name":"studentId","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED'),
    ('dbt-bank@1', 'dbt-bank', 1, 'dbt-rest-mock', 'BANK_ACCOUNT',
     '{"FETCH":{"endpoint":"/bank","mapping_ref":"map-dbt-bank@1","output_schema":"Credential/BankAccount@1","error_paths":[]}}',
     '[{"name":"dbtId","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED');

INSERT INTO catalog_mapping (ref, connector_ref, rules) VALUES
    ('map-rev-income@1', 'rev-income@1', '[{"source":"annualIncome","target":"annualIncome","transforms":[{"fn":"trim","args":[]}]},{"source":"holderName","target":"holderName","transforms":[{"fn":"trim","args":[]}]}]'),
    ('map-rev-caste@1', 'rev-caste@1', '[{"source":"casteCategory","target":"casteCategory","transforms":[{"fn":"upper","args":[]}]}]'),
    ('map-edu-marks@1', 'edu-marks@1', '[{"source":"percentage","target":"percentage","transforms":[]}]'),
    ('map-dbt-bank@1', 'dbt-bank@1', '[{"source":"accountRef","target":"accountRef","transforms":[{"fn":"mask","args":["4"]}]}]');

INSERT INTO catalog_journey (code, name, bpmn_ref, required_categories, policy, status) VALUES
    ('POST_MATRIC_SCHOLARSHIP', 'Post-matric scholarship', 'scholarship',
     ARRAY['INCOME_CERTIFICATE','CASTE_CERTIFICATE','MARKS','BANK_ACCOUNT'],
     '{"accept_stale":false,"sla_hours":72}', 'PUBLISHED');
