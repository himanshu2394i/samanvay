-- A department portal collects consent and signs a statement of it (typ samanvay-consent). The statement is kept as evidence, and the
-- nonce Samanvay issued with the consent wording can be used once.
CREATE TABLE consent_statement_nonce (
    request_id UUID PRIMARY KEY REFERENCES consent_request(id),
    nonce      VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);

CREATE TABLE consent_evidence (
    consent_id      UUID PRIMARY KEY REFERENCES consent_artifact(id),
    statement       TEXT NOT NULL,
    jti             VARCHAR(200) NOT NULL UNIQUE,
    department_code VARCHAR(60) NOT NULL,
    key_thumbprint  VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
