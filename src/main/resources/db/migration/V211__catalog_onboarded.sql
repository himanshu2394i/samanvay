-- Rows created or adopted by manifest onboarding. The staff console lists only these; seeded demo and test rows stay FALSE.
ALTER TABLE catalog_data_source ADD COLUMN onboarded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE catalog_connector   ADD COLUMN onboarded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE catalog_journey     ADD COLUMN onboarded BOOLEAN NOT NULL DEFAULT FALSE;
