CREATE TABLE registry_category_policy (
    data_category     VARCHAR(60) PRIMARY KEY,
    sensitivity       VARCHAR(20) NOT NULL CHECK (sensitivity IN ('PUBLIC','RESTRICTED','SENSITIVE')),
    discovery_policy  VARCHAR(40) NOT NULL CHECK (discovery_policy IN ('VISIBLE','CONSENT_REQUIRED_TO_DISCOVER')),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE registry_clearance (
    requester_id  VARCHAR(60) NOT NULL,
    sensitivity   VARCHAR(20) NOT NULL CHECK (sensitivity IN ('PUBLIC','RESTRICTED','SENSITIVE')),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (requester_id, sensitivity)
);

CREATE TABLE registry_discovery_grant (
    subject_id     UUID NOT NULL,
    requester_id   VARCHAR(60) NOT NULL,
    data_category  VARCHAR(60) NOT NULL,
    consent_id     UUID NOT NULL,
    granted_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subject_id, requester_id, data_category)
);

CREATE TABLE registry_pointer (
    id               UUID PRIMARY KEY,
    subject_id       UUID NOT NULL,
    subject_type     VARCHAR(20) NOT NULL CHECK (subject_type IN ('PERSON','ORGANISATION','LAND_PARCEL')),
    department_code  VARCHAR(60) NOT NULL,
    data_category    VARCHAR(60) NOT NULL,
    source_ref       JSONB NOT NULL,
    issued_at        DATE,
    valid_until      DATE,
    as_of            TIMESTAMPTZ NOT NULL,
    freshness_mode   VARCHAR(20) NOT NULL CHECK (freshness_mode IN ('REALTIME','BATCH')),
    status           VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','EXPIRED','WITHDRAWN')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_id, department_code, data_category)
);
CREATE INDEX idx_pointer_subject ON registry_pointer (subject_id);

INSERT INTO registry_category_policy (data_category, sensitivity, discovery_policy) VALUES
    ('INCOME_CERTIFICATE', 'RESTRICTED', 'VISIBLE'),
    ('CASTE_CERTIFICATE', 'SENSITIVE', 'CONSENT_REQUIRED_TO_DISCOVER'),
    ('MARKS', 'RESTRICTED', 'VISIBLE'),
    ('BANK_ACCOUNT', 'SENSITIVE', 'CONSENT_REQUIRED_TO_DISCOVER');

INSERT INTO registry_clearance (requester_id, sensitivity) VALUES
    ('SCHOLARSHIP', 'PUBLIC'),
    ('SCHOLARSHIP', 'RESTRICTED'),
    ('SCHOLARSHIP', 'SENSITIVE');
