CREATE TABLE orchestration_exception (
    id           UUID PRIMARY KEY,
    instance_id  UUID NOT NULL,
    step_code    VARCHAR(60) NOT NULL,
    reason       VARCHAR(200),
    status       VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orch_exception_instance ON orchestration_exception (instance_id);
