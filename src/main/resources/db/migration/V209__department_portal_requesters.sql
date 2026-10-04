-- The department portals run the journeys and ask for consent as THEMSELVES (docs/superpowers/specs/2026-10-04-department-journeys-design.md).
--
-- 1. Education runs the post-matric scholarship on its own portal. The older scholarship journey and its purposes belong to the
--    stand-in "SCHOLARSHIP" requester, so Education gets its own purpose (the journey itself arrives with Education's manifest).
-- 2. Every department that runs a journey needs the registry clearance to discover the categories the journey reads: Education
--    (marks, income, caste, bank), Revenue (its own income certificate) and DBT (its own bank record), as Agriculture already has.
INSERT INTO catalog_purpose
    (code, text, category_type, status, requester_department, data_categories,
     data_types, requester_rule, max_duration_days, duration_rule, frequency,
     label_en, label_mr, label_en_status, label_mr_status, separate_opt_in)
VALUES
    ('EDU_SCHOLARSHIP_ELIGIBILITY',
     'Check that you qualify for this scholarship', 'JOURNEY', 'ACTIVE', 'EDUCATION',
     ARRAY['INCOME_CERTIFICATE','CASTE_CERTIFICATE','MARKS','BANK_ACCOUNT'],
     ARRAY['INCOME_CERTIFICATE_VERIFICATION_RESULT','CASTE_CERTIFICATE_VERIFICATION_RESULT','LAST_MARKSHEET_VERIFICATION_RESULT','BANK_ACCOUNT_CHECK_RESULT'],
     'CATALOG_DEPARTMENT', 180, 'UNTIL_APPLICATION_DECIDED', 'ONCE_PER_DOCUMENT_PER_APPLICATION',
     'Check that you qualify for this scholarship',
     'या शिष्यवृत्तीसाठी तुमची पात्रता तपासणे',
     'APPROVED', 'DRAFT', false)
ON CONFLICT (code) DO NOTHING;

INSERT INTO registry_clearance (requester_id, sensitivity) VALUES
    ('EDUCATION', 'PUBLIC'), ('EDUCATION', 'RESTRICTED'), ('EDUCATION', 'SENSITIVE'),
    ('REVENUE', 'PUBLIC'), ('REVENUE', 'RESTRICTED'), ('REVENUE', 'SENSITIVE'),
    ('DBT', 'PUBLIC'), ('DBT', 'RESTRICTED'), ('DBT', 'SENSITIVE')
ON CONFLICT DO NOTHING;
