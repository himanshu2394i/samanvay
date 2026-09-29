-- Domicile becomes a first-class, fetchable data category.
--
-- SCH_ELIGIBILITY_CHECK (V186) already lists DOMICILE_CERTIFICATE_VERIFICATION_RESULT
-- among its DEPA data_types (shown to the citizen), but DOMICILE_CERTIFICATE was
-- not a data_category, and data_categories are what drive fetches. So the domicile
-- result was described but could never be fetched.
--
-- The certificate is issued through Aaple Sarkar and (assumption, pending final
-- confirmation) available as an issued document in the DigiLocker partner sandbox
-- the platform already models for income/caste — so it is wired exactly like the
-- income certificate: a REVENUE REST source, one FETCH capability, keep only the
-- verification result. No bespoke offline XML verifier is added, because the
-- platform models DigiLocker as a partner sandbox, not raw signed XML.
--
-- Forward-only. Numbered V191, above the frequency PR's V189/V190, so merge order
-- consent-record -> consent-frequency -> this keeps Flyway monotonic.

INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('Credential/DomicileCertificate@1', 'DomicileCertificate', 1, '{"type":"object","required":["district"]}');

INSERT INTO catalog_connector (ref, connector_id, version, data_source_code, data_category, capabilities, inputs, sla_ms, status) VALUES
    ('rev-domicile@1', 'rev-domicile', 1, 'revenue-rest-mock', 'DOMICILE_CERTIFICATE',
     '{"FETCH":{"endpoint":"/domicile","mapping_ref":"map-rev-domicile@1","output_schema":"Credential/DomicileCertificate@1","error_paths":[]}}',
     '[{"name":"rationCard","from":"link.localIdToken","required":true}]', 3000, 'PUBLISHED');

INSERT INTO catalog_mapping (ref, connector_ref, rules) VALUES
    ('map-rev-domicile@1', 'rev-domicile@1',
     '[{"source":"district","target":"district","transforms":[{"fn":"trim","args":[]}]},{"source":"issueDate","target":"issueDate","transforms":[{"fn":"trim","args":[]}]}]');

-- Enable domicile on the scholarship eligibility purpose (idempotent).
UPDATE catalog_purpose
SET data_categories = data_categories || ARRAY['DOMICILE_CERTIFICATE']
WHERE code = 'SCH_ELIGIBILITY_CHECK'
  AND NOT ('DOMICILE_CERTIFICATE' = ANY(data_categories));
