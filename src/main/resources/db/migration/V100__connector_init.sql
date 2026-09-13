CREATE TABLE connector_submission_attempt (
    id                    VARCHAR(100) PRIMARY KEY,
    grant_id              UUID NOT NULL,
    workflow_instance_id  VARCHAR(100) NOT NULL,
    connector_ref         VARCHAR(100) NOT NULL,
    request_hash          VARCHAR(64) NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    external_reference    VARCHAR(200),
    response_snapshot     JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ
);

CREATE TABLE connector_batch_file (
    id                UUID PRIMARY KEY,
    data_source_code  VARCHAR(60)  NOT NULL,
    filename          VARCHAR(300) NOT NULL,
    checksum          VARCHAR(64)  NOT NULL,
    file_timestamp    TIMESTAMPTZ  NOT NULL,
    row_offset        INT NOT NULL DEFAULT 0,
    rows_total        INT,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETE','FAILED')),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    UNIQUE (data_source_code, checksum)
);

CREATE TABLE connector_exception (
    id                UUID PRIMARY KEY,
    data_source_code  VARCHAR(60) NOT NULL,
    connector_ref     VARCHAR(100),
    source_file       VARCHAR(300),
    raw_content       TEXT NOT NULL,
    violations        JSONB NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    resolved_by       VARCHAR(100)
);
