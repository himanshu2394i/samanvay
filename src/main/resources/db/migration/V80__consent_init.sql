CREATE TABLE consent_request (
    id                  UUID PRIMARY KEY,
    subject_citizen_id  UUID NOT NULL,
    requester_id        VARCHAR(60) NOT NULL,
    purpose_code        VARCHAR(60) NOT NULL,
    purpose_text        VARCHAR(500) NOT NULL,
    data_categories     TEXT[] NOT NULL,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','GRANTED','DENIED','EXPIRED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at        TIMESTAMPTZ
);

CREATE TABLE consent_artifact (
    id                  UUID PRIMARY KEY,
    subject_citizen_id  UUID NOT NULL,
    requester_id        VARCHAR(60) NOT NULL,
    purpose_code        VARCHAR(60) NOT NULL,
    purpose_text        VARCHAR(500) NOT NULL,
    data_categories     TEXT[] NOT NULL,
    granularity         VARCHAR(20) NOT NULL CHECK (granularity IN ('ONE_TIME','RECURRING')),
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL,
    frequency_limit     INT,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    version             INT NOT NULL DEFAULT 1,
    citizen_auth_ref    VARCHAR(200) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_artifact_subject ON consent_artifact (subject_citizen_id);
CREATE INDEX idx_consent_artifact_lookup  ON consent_artifact (requester_id, subject_citizen_id, status);

CREATE TABLE consent_event (
    id            UUID PRIMARY KEY,
    consent_id    UUID NOT NULL REFERENCES consent_artifact(id),
    event_type    VARCHAR(30) NOT NULL CHECK (event_type IN ('REQUESTED','GRANTED','REVOKED','EXPIRED')),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail        JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE consent_access_grant (
    id                  UUID PRIMARY KEY,
    nonce               BYTEA NOT NULL UNIQUE,
    consent_id          UUID NOT NULL REFERENCES consent_artifact(id),
    consent_version     INT NOT NULL,
    subject_citizen_id  UUID NOT NULL,
    requester_id        VARCHAR(60) NOT NULL,
    data_category       VARCHAR(60) NOT NULL,
    department_id       VARCHAR(60) NOT NULL,
    connector_ref       VARCHAR(120) NOT NULL,
    purpose_code        VARCHAR(60) NOT NULL,
    issued_at           TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ,
    signature           BYTEA NOT NULL
);
CREATE INDEX idx_grant_expiry ON consent_access_grant (expires_at);
