-- Department login assertions already accepted for a home sign in, by jti, so a copied assertion cannot be replayed.
CREATE TABLE identity_assertion_use (
    department_code VARCHAR(60) NOT NULL,
    jti             VARCHAR(200) NOT NULL,
    used_at         TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (department_code, jti)
);
CREATE INDEX idx_identity_assertion_use_expiry ON identity_assertion_use (expires_at);
