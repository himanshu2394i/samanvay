-- Phase 2: journey differences live in catalog policy, not Java switches.
UPDATE catalog_journey
SET policy = '{
  "accept_stale": false,
  "sla_hours": 72,
  "requester": "SCHOLARSHIP",
  "purpose": "SCHOLARSHIP_ELIGIBILITY",
  "reference_prefix": "SCH",
  "sources": {
    "INCOME_CERTIFICATE": "REVENUE",
    "CASTE_CERTIFICATE": "REVENUE",
    "MARKS": "EDUCATION",
    "BANK_ACCOUNT": "DBT"
  }
}'
WHERE code = 'POST_MATRIC_SCHOLARSHIP';

INSERT INTO catalog_department (code, name, idp_realm, contact_email, default_sla_ms, status) VALUES
    ('MUNICIPAL', 'Municipal Corporation', 'municipal', 'muni@example.gov', 5000, 'ACTIVE'),
    ('FIRE', 'Fire Services', 'fire', 'fire@example.gov', 3000, 'ACTIVE'),
    ('POLLUTION', 'Pollution Control Board', 'pollution', 'pcb@example.gov', 5000, 'ACTIVE'),
    ('INDUSTRY', 'Department of Industries', 'industry', 'industry@example.gov', 3000, 'ACTIVE');

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('municipal-sftp-mock', 'MUNICIPAL', 'SFTP_CSV', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN'),
    ('fire-rest-mock', 'FIRE', 'REST', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN'),
    ('pollution-jdbc-mock', 'POLLUTION', 'JDBC', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN');

INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('Credential/PropertyTax@1', 'PropertyTax', 1, '{"type":"object","required":["propertyRef"]}'),
    ('Credential/FireNoc@1', 'FireNoc', 1, '{"type":"object","required":["nocStatus"]}'),
    ('Credential/PollutionClearance@1', 'PollutionClearance', 1, '{"type":"object","required":["clearanceStatus"]}'),
    ('Credential/LandRecord@1', 'LandRecord', 1, '{"type":"object","required":["surveyNo"]}');

INSERT INTO catalog_connector (ref, connector_id, version, data_source_code, data_category, capabilities, inputs, sla_ms, status) VALUES
    ('muni-property@1', 'muni-property', 1, 'municipal-sftp-mock', 'PROPERTY',
     '{"FETCH":{"endpoint":"/property","mapping_ref":"map-muni-property@1","output_schema":"Credential/PropertyTax@1","error_paths":[]}}',
     '[{"name":"propertyId","from":"link.localIdToken","required":true}]', 5000, 'PUBLISHED'),
    ('fire-noc@1', 'fire-noc', 1, 'fire-rest-mock', 'FIRE_NOC',
     '{"FETCH":{"endpoint":"/fire","mapping_ref":"map-fire-noc@1","output_schema":"Credential/FireNoc@1","error_paths":[]}}',
     '[{"name":"premiseId","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED'),
    ('pcb-clearance@1', 'pcb-clearance', 1, 'pollution-jdbc-mock', 'POLLUTION_CLEARANCE',
     '{"FETCH":{"endpoint":"/pollution","template":"SELECT clearance_status, holder FROM pcb_clearance WHERE premise_id = :premiseId","mapping_ref":"map-pcb-clearance@1","output_schema":"Credential/PollutionClearance@1","error_paths":[]}}',
     '[{"name":"premiseId","from":"link.localIdToken","required":true}]', 5000, 'PUBLISHED'),
    ('rev-land@1', 'rev-land', 1, 'revenue-rest-mock', 'LAND_RECORD',
     '{"FETCH":{"endpoint":"/land","mapping_ref":"map-rev-land@1","output_schema":"Credential/LandRecord@1","error_paths":[]}}',
     '[{"name":"rationCard","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED');

INSERT INTO catalog_mapping (ref, connector_ref, rules) VALUES
    ('map-muni-property@1', 'muni-property@1', '[{"source":"propertyRef","target":"propertyRef","transforms":[{"fn":"trim","args":[]}]}]'),
    ('map-fire-noc@1', 'fire-noc@1', '[{"source":"nocStatus","target":"nocStatus","transforms":[{"fn":"upper","args":[]}]}]'),
    ('map-pcb-clearance@1', 'pcb-clearance@1', '[{"source":"clearanceStatus","target":"clearanceStatus","transforms":[{"fn":"upper","args":[]}]}]'),
    ('map-rev-land@1', 'rev-land@1', '[{"source":"surveyNo","target":"surveyNo","transforms":[{"fn":"trim","args":[]}]}]');

INSERT INTO catalog_journey (code, name, bpmn_ref, required_categories, policy, status) VALUES
    ('BUSINESS_NOC', 'Business licence / NOC', 'business-noc',
     ARRAY['PROPERTY','FIRE_NOC','POLLUTION_CLEARANCE','LAND_RECORD'],
     '{
       "accept_stale": true,
       "sla_hours": 120,
       "requester": "INDUSTRY",
       "purpose": "BUSINESS_NOC",
       "reference_prefix": "NOC",
       "sources": {
         "PROPERTY": "MUNICIPAL",
         "FIRE_NOC": "FIRE",
         "POLLUTION_CLEARANCE": "POLLUTION",
         "LAND_RECORD": "REVENUE"
       }
     }', 'PUBLISHED');
