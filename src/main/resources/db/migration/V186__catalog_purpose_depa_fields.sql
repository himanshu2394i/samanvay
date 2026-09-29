-- V186__catalog_purpose_depa_fields.sql
-- Purposes are catalog data, not Java: everything a consent screen and a
-- consent record need about a purpose lives on its catalog_purpose row, so a
-- new journey adds purpose codes with no Java change. Java only checks that a
-- requested code exists and is ACTIVE, and interprets requester_rule.
--
--   data_types        - the DEPA data-type descriptors the citizen is shown
--                       and that are copied onto the consent record at grant
--                       time (finer than data_categories, which drive fetches)
--   requester_rule    - how the requester is fixed (never from a request body):
--                         CATALOG_DEPARTMENT     requester_department below
--                         PRIOR_AWARD_DEPARTMENT the department that approved
--                                                the citizen's prior-year award
--                                                (no such award: refused)
--   max_duration_days - hard cap on a consent's lifetime (NULL: the platform
--                       default of 365 days that applied before this column)
--   duration_rule     - when the consent is meant to end within that cap
--                       (informational in this PR; the cap is enforced)
--   frequency         - how often data may be checked under it (recorded;
--                       enforcement beyond the existing per-consent limit is a
--                       later PR)
--   label_en/_mr and their MISSING|DRAFT|APPROVED review status
--   separate_opt_in   - must be asked for on its own, never bundled
--
-- Existing rows (the journey purposes V181/V184 registered) keep working:
-- defaults leave them on CATALOG_DEPARTMENT with no labels (MISSING).
-- Numbered above every existing migration (Flyway outOfOrder=false); see V180.
ALTER TABLE catalog_purpose
    ADD COLUMN data_types        TEXT[]       NOT NULL DEFAULT '{}',
    ADD COLUMN requester_rule    VARCHAR(30)  NOT NULL DEFAULT 'CATALOG_DEPARTMENT'
        CHECK (requester_rule IN ('CATALOG_DEPARTMENT','PRIOR_AWARD_DEPARTMENT')),
    ADD COLUMN max_duration_days INT          CHECK (max_duration_days > 0),
    ADD COLUMN duration_rule     VARCHAR(40),
    ADD COLUMN frequency         VARCHAR(60),
    ADD COLUMN label_en          VARCHAR(300),
    ADD COLUMN label_mr          VARCHAR(300),
    ADD COLUMN label_en_status   VARCHAR(10)  NOT NULL DEFAULT 'MISSING'
        CHECK (label_en_status IN ('MISSING','DRAFT','APPROVED')),
    ADD COLUMN label_mr_status   VARCHAR(10)  NOT NULL DEFAULT 'MISSING'
        CHECK (label_mr_status IN ('MISSING','DRAFT','APPROVED')),
    ADD COLUMN separate_opt_in   BOOLEAN      NOT NULL DEFAULT false,
    ADD CONSTRAINT catalog_purpose_label_en_present
        CHECK (label_en_status = 'MISSING' OR label_en IS NOT NULL),
    ADD CONSTRAINT catalog_purpose_label_mr_present
        CHECK (label_mr_status = 'MISSING' OR label_mr IS NOT NULL);

-- Scholarship consent purposes (Phase 2). Demo/journey data: this repo does
-- not yet separate demo seeds from schema (the scholarship journey itself is
-- seeded by V21), so they sit next to it; moving journey seeds to a
-- dev/demo-only location is a separate PR. English labels are APPROVED,
-- Marathi labels are DRAFT pending review.
INSERT INTO catalog_purpose
    (code, text, category_type, status, requester_department, data_categories,
     data_types, requester_rule, max_duration_days, duration_rule, frequency,
     label_en, label_mr, label_en_status, label_mr_status, separate_opt_in)
VALUES
    ('SCH_ELIGIBILITY_CHECK',
     'Check that you qualify for this scholarship', 'JOURNEY', 'ACTIVE', 'SCHOLARSHIP',
     ARRAY['INCOME_CERTIFICATE','CASTE_CERTIFICATE','MARKS'],
     ARRAY['INCOME_CERTIFICATE_VERIFICATION_RESULT','CASTE_CERTIFICATE_VERIFICATION_RESULT',
           'DOMICILE_CERTIFICATE_VERIFICATION_RESULT','LAST_MARKSHEET_VERIFICATION_RESULT'],
     'CATALOG_DEPARTMENT', 180, 'UNTIL_APPLICATION_DECIDED', 'ONCE_PER_DOCUMENT_PER_APPLICATION',
     'Check that you qualify for this scholarship',
     'या शिष्यवृत्तीसाठी तुमची पात्रता तपासणे',
     'APPROVED', 'DRAFT', false),
    ('SCH_BANK_VERIFY',
     'Confirm the bank account your scholarship is paid into', 'JOURNEY', 'ACTIVE', 'SCHOLARSHIP',
     ARRAY['BANK_ACCOUNT'],
     ARRAY['BANK_IFSC','BANK_ACCOUNT_CHECK_RESULT'],
     'CATALOG_DEPARTMENT', 365, 'ONE_ACADEMIC_YEAR', 'ONCE_PER_PAYMENT',
     'Confirm the bank account your scholarship is paid into',
     'शिष्यवृत्ती जमा होणारे बँक खाते पडताळणे',
     'APPROVED', 'DRAFT', false),
    ('SCH_IDENTITY_REVIEW',
     'An officer checks that two records are both yours', 'JOURNEY', 'ACTIVE', 'SCHOLARSHIP',
     ARRAY['IDENTITY_MATCH_FIELDS'],
     ARRAY['NAME','DATE_OF_BIRTH','DEPARTMENT_ID'],
     'CATALOG_DEPARTMENT', 30, 'UNTIL_OFFICER_DECIDES', 'ONCE',
     'An officer checks that two records are both yours',
     'दोन नोंदी तुमच्याच आहेत का, हे अधिकारी तपासतील',
     'APPROVED', 'DRAFT', false),
    ('SCH_RENEWAL_CHECK',
     'Re-check that you still qualify next year', 'JOURNEY', 'ACTIVE', 'SCHOLARSHIP',
     ARRAY['MARKS','INCOME_CERTIFICATE'],
     ARRAY['NEW_MARKSHEET_VERIFICATION_RESULT','NEW_INCOME_CERTIFICATE_VERIFICATION_RESULT'],
     'PRIOR_AWARD_DEPARTMENT', 365, 'TWELVE_MONTHS', 'ONCE_PER_YEAR',
     'Re-check that you still qualify next year',
     'पुढील वर्षी तुमची पात्रता पुन्हा तपासणे',
     'APPROVED', 'DRAFT', true);
