-- Phase 3: Journey 3 is catalog + BPMN only. LandParcel is a schema row, not a Java type.

INSERT INTO catalog_department (code, name, idp_realm, contact_email, default_sla_ms, status) VALUES
    ('AGRICULTURE', 'Department of Agriculture', 'agriculture', 'agri@example.gov', 3000, 'ACTIVE');

INSERT INTO catalog_data_source (code, department_code, protocol, base_host, auth_type, auth_config_ref, health_status) VALUES
    ('agriculture-rest-mock', 'AGRICULTURE', 'REST', 'mock.samanvay.test', 'NONE', 'secret:none', 'GREEN');

INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('LandParcel@1', 'LandParcel', 1, '{"type":"object","required":["surveyNo"]}'),
    ('Credential/CropRecord@1', 'CropRecord', 1, '{"type":"object"}');

INSERT INTO catalog_connector (ref, connector_id, version, data_source_code, data_category, capabilities, inputs, sla_ms, status) VALUES
    ('rev-712@1', 'rev-712', 1, 'revenue-rest-mock', 'LAND_PARCEL',
     '{"FETCH":{"endpoint":"/land","mapping_ref":"map-rev-712@1","output_schema":"LandParcel@1","error_paths":[]}}',
     '[{"name":"rationCard","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED'),
    ('agri-crop@1', 'agri-crop', 1, 'agriculture-rest-mock', 'CROP_RECORD',
     '{"FETCH":{"endpoint":"/crop","mapping_ref":"map-agri-crop@1","output_schema":"Credential/CropRecord@1","error_paths":[]}}',
     '[{"name":"farmerId","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED');

INSERT INTO catalog_mapping (ref, connector_ref, rules) VALUES
    ('map-rev-712@1', 'rev-712@1', '[{"source":"surveyNo","target":"surveyNo","transforms":[{"fn":"trim","args":[]}]}]'),
    ('map-agri-crop@1', 'agri-crop@1', '[{"source":"ok","target":"ok","transforms":[]}]');

INSERT INTO catalog_journey (code, name, bpmn_ref, required_categories, policy, status) VALUES
    ('FARMER_SUBSIDY', 'Farmer subsidy', 'farmer-subsidy',
     ARRAY['LAND_PARCEL','CROP_RECORD','BANK_ACCOUNT'],
     '{
       "accept_stale": true,
       "sla_hours": 96,
       "requester": "AGRICULTURE",
       "purpose": "FARMER_SUBSIDY",
       "reference_prefix": "FAR",
       "sources": {
         "LAND_PARCEL": "REVENUE",
         "CROP_RECORD": "AGRICULTURE",
         "BANK_ACCOUNT": "DBT"
       }
     }', 'PUBLISHED');
