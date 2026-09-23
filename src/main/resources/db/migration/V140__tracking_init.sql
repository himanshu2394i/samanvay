CREATE SEQUENCE tracking_reference_seq START 1;

CREATE TABLE tracking_application (
    id                   UUID PRIMARY KEY,
    reference_no         VARCHAR(40) NOT NULL UNIQUE,
    citizen_id           UUID NOT NULL,
    journey_code         VARCHAR(60) NOT NULL,
    process_instance_id  VARCHAR(100) NOT NULL,
    status               VARCHAR(30) NOT NULL CHECK (status IN
        ('SUBMITTED','PARTIALLY_VERIFIED','VERIFIED','APPROVED','REJECTED','CLOSED')),
    submitted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    sla_due_at           TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ
);
CREATE INDEX idx_tracking_app_citizen ON tracking_application (citizen_id);

CREATE TABLE tracking_step (
    id               UUID PRIMARY KEY,
    application_id   UUID NOT NULL REFERENCES tracking_application(id),
    step_code        VARCHAR(60) NOT NULL,
    department_code  VARCHAR(60),
    status           VARCHAR(30) NOT NULL CHECK (status IN
        ('PENDING','IN_PROGRESS','COMPLETED','PENDING_SOURCE','AUTHORIZATION_WITHDRAWN','FAILED')),
    outcome          VARCHAR(500),
    source           VARCHAR(20) NOT NULL CHECK (source IN ('API','BATCH','CITIZEN_UPLOAD')),
    data_as_of       TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    sla_due_at       TIMESTAMPTZ,
    audit_ref        BIGINT,
    UNIQUE (application_id, step_code)
);
