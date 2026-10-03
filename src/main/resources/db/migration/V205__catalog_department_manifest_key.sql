-- The thumbprint of the key an admin approved to sign this department's manifest (RFC 7638, base64url SHA-256, 43 chars).
-- Once set, every later manifest from the department must be signed by that key; a changed key needs a new admin approval.
-- NULL = no signing key pinned (never onboarded from a signed manifest).
ALTER TABLE catalog_department
    ADD COLUMN manifest_key_thumbprint VARCHAR(64);
