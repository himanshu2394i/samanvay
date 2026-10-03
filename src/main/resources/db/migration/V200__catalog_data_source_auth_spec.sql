-- The non-secret half of how Samanvay authenticates to a department source: the manifest's `auth` block
-- (scheme, which parameters it needs and where each goes, OAuth token URL/scopes, SOAP password type).
-- Secret VALUES never live here; they are looked up in the SecretStore by parameter name
-- (docs/FINAL-CHANGES.md section 13). '{}' = no spec: the stored auth_type alone decides, so every source onboarded
-- before manifests behaves exactly as before.
ALTER TABLE catalog_data_source
    ADD COLUMN auth_spec JSONB NOT NULL DEFAULT '{}'::jsonb;
