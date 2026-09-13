CREATE TABLE identity_citizen (
    id          UUID PRIMARY KEY,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','MERGED','SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_profile (
    citizen_id      UUID PRIMARY KEY REFERENCES identity_citizen(id),
    name_latin      VARCHAR(200) NOT NULL,
    name_devanagari VARCHAR(200),
    given_name      VARCHAR(100),
    family_name     VARCHAR(100),
    father_name     VARCHAR(200),
    dob             DATE NOT NULL,
    dob_precision   VARCHAR(10) NOT NULL CHECK (dob_precision IN ('DAY','MONTH','YEAR')),
    gender          VARCHAR(10),
    contact_masked  VARCHAR(50),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_link (
    id               UUID PRIMARY KEY,
    citizen_id       UUID NOT NULL REFERENCES identity_citizen(id),
    department_code  VARCHAR(60)  NOT NULL,
    local_id_type    VARCHAR(60)  NOT NULL,
    local_id_token   VARCHAR(200) NOT NULL,
    provenance       VARCHAR(30)  NOT NULL CHECK (provenance IN
        ('CITIZEN_ASSERTED','DETERMINISTIC','PROBABILISTIC','OFFICER_CONFIRMED')),
    confidence       NUMERIC(4,3),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    verified_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_no_auto_probabilistic_link
        CHECK (NOT (status = 'ACTIVE' AND provenance = 'PROBABILISTIC')),
    UNIQUE (department_code, local_id_type, local_id_token)
);

CREATE TABLE identity_match_key (
    id          UUID PRIMARY KEY,
    citizen_id  UUID NOT NULL REFERENCES identity_citizen(id),
    key_type    VARCHAR(30) NOT NULL,
    key_value   VARCHAR(200) NOT NULL,
    UNIQUE (key_type, key_value, citizen_id)
);
CREATE INDEX idx_match_key_lookup ON identity_match_key (key_type, key_value);

CREATE TABLE identity_candidate_match (
    id               UUID PRIMARY KEY,
    citizen_id       UUID NOT NULL REFERENCES identity_citizen(id),
    department_code  VARCHAR(60) NOT NULL,
    score            NUMERIC(4,3) NOT NULL,
    features         JSONB NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','REJECTED')),
    reviewed_by      VARCHAR(100),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (citizen_id, department_code)
);
