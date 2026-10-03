-- The central schema for the documents the four departments publish (docs/FINAL-CHANGES.md: the central schema stays
-- SEEDED; a department's document types are seeded here first, then onboarding maps the department's fields onto them).
--
-- Each schema now names the document CATEGORY it describes ("x-category", the key onboarding uses to find it) and its
-- fields ("properties"). The existing `required` lists are NOT changed, so existing validation behaves exactly as before.
-- Field names are the shared vocabulary, derived from what Revenue, DBT, Education and Agriculture actually publish.

UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'INCOME_CERTIFICATE',
    'properties', jsonb_build_object(
        'annualIncome', jsonb_build_object('type', 'integer'),
        'annualIncomeDisplay', jsonb_build_object('type', 'string'),
        'holderName', jsonb_build_object('type', 'string'),
        'district', jsonb_build_object('type', 'string'),
        'issuerOffice', jsonb_build_object('type', 'string'),
        'financialYear', jsonb_build_object('type', 'string')))
WHERE ref = 'Credential/IncomeCertificate@1';

UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'CASTE_CERTIFICATE',
    'properties', jsonb_build_object(
        'casteCategory', jsonb_build_object('type', 'string'),
        'caste', jsonb_build_object('type', 'string'),
        'holderName', jsonb_build_object('type', 'string'),
        'issuerOffice', jsonb_build_object('type', 'string')))
WHERE ref = 'Credential/CasteCertificate@1';

UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'DOMICILE_CERTIFICATE',
    'properties', jsonb_build_object(
        'district', jsonb_build_object('type', 'string'),
        'state', jsonb_build_object('type', 'string'),
        'holderName', jsonb_build_object('type', 'string'),
        'issuerOffice', jsonb_build_object('type', 'string')))
WHERE ref = 'Credential/DomicileCertificate@1';

UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'MARKS',
    'properties', jsonb_build_object(
        'percentage', jsonb_build_object('type', 'number'),
        'board', jsonb_build_object('type', 'string'),
        'exam', jsonb_build_object('type', 'string')))
WHERE ref = 'Credential/Marks@1';

UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'BANK_ACCOUNT',
    'properties', jsonb_build_object(
        'accountRef', jsonb_build_object('type', 'string'),
        'ifscMasked', jsonb_build_object('type', 'string'),
        'holderName', jsonb_build_object('type', 'string')))
WHERE ref = 'Credential/BankAccount@1';

-- The 7/12 land extract (Revenue, over SFTP).
UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'LAND_PARCEL',
    'properties', jsonb_build_object(
        'surveyNo', jsonb_build_object('type', 'string'),
        'village', jsonb_build_object('type', 'string'),
        'taluka', jsonb_build_object('type', 'string'),
        'district', jsonb_build_object('type', 'string'),
        'areaHectares', jsonb_build_object('type', 'number'),
        'ownerName', jsonb_build_object('type', 'string')))
WHERE ref = 'LandParcel@1';

-- The crop sowing record (Agriculture, over SFTP). No `required` list was ever set; it stays unset.
UPDATE catalog_schema SET definition = definition || jsonb_build_object(
    'x-category', 'CROP_RECORD',
    'properties', jsonb_build_object(
        'crop', jsonb_build_object('type', 'string'),
        'season', jsonb_build_object('type', 'string'),
        'areaHectares', jsonb_build_object('type', 'number')))
WHERE ref = 'Credential/CropRecord@1';

-- New: the farmer record (Agriculture, read through a database view).
INSERT INTO catalog_schema (ref, name, version, definition) VALUES
    ('Credential/FarmerRecord@1', 'FarmerRecord', 1,
     '{"type":"object","x-category":"FARMER_RECORD","required":["farmerName"],"properties":{"farmerName":{"type":"string"},"village":{"type":"string"},"taluka":{"type":"string"},"landHectares":{"type":"number"}}}')
ON CONFLICT (ref) DO NOTHING;

-- Consent purposes for the journeys Revenue and DBT publish (the existing journeys' purposes are already registered).
INSERT INTO catalog_purpose (code, text, category_type, status, requester_department, data_categories) VALUES
    ('INCOME_CERT_RENEWAL', 'Check the income certificate already on file when you renew it', 'JOURNEY', 'ACTIVE', 'REVENUE',
     ARRAY['INCOME_CERTIFICATE']),
    ('DBT_ACCOUNT_SEEDING', 'Confirm the bank account on file is ready to receive benefit payments', 'JOURNEY', 'ACTIVE', 'DBT',
     ARRAY['BANK_ACCOUNT'])
ON CONFLICT (code) DO NOTHING;
