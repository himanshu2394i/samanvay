CREATE TABLE orchestration_instance (
    id                          UUID PRIMARY KEY,
    journey_code                VARCHAR(60) NOT NULL,
    citizen_id                  UUID NOT NULL,
    process_instance_id         VARCHAR(100) NOT NULL UNIQUE,
    pinned_connector_versions   JSONB NOT NULL DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orchestration_step_state (
    id                    UUID PRIMARY KEY,
    instance_id           UUID NOT NULL REFERENCES orchestration_instance(id),
    step_code             VARCHAR(60) NOT NULL,
    status                VARCHAR(30) NOT NULL CHECK (status IN
        ('PENDING','IN_PROGRESS','COMPLETED','PENDING_SOURCE','AUTHORIZATION_WITHDRAWN','FAILED')),
    attempt_count         INT NOT NULL DEFAULT 0,
    last_failure_reason   VARCHAR(200),
    next_retry_at         TIMESTAMPTZ,
    UNIQUE (instance_id, step_code)
);
