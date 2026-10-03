-- The SHA-256 of the manifest a department was last onboarded from. A later plan compares it with the department's current
-- manifest to flag that its published structure has changed (docs/FINAL-CHANGES.md section 10). NULL = never onboarded from a manifest.
ALTER TABLE catalog_department
    ADD COLUMN manifest_digest VARCHAR(64);
