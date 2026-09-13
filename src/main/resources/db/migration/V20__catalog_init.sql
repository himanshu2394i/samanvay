CREATE TABLE catalog_department (
    code            VARCHAR(60) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    idp_realm       VARCHAR(100),
    contact_email   VARCHAR(200),
    default_sla_ms  INT,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_data_source (
    code             VARCHAR(60) PRIMARY KEY,
    department_code  VARCHAR(60) NOT NULL REFERENCES catalog_department(code),
    protocol         VARCHAR(20) NOT NULL CHECK (protocol IN ('REST','SOAP','SFTP_CSV','JDBC')),
    base_host        VARCHAR(300) NOT NULL,
    auth_type        VARCHAR(30) NOT NULL,
    auth_config_ref  VARCHAR(200) NOT NULL,
    retry_config     JSONB NOT NULL DEFAULT '{"max":3,"backoff":"exponential","base_ms":500}',
    breaker_config   JSONB NOT NULL DEFAULT '{"failure_rate":50,"window":20,"open_seconds":30}',
    health_status    VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' CHECK (health_status IN ('GREEN','AMBER','RED','UNKNOWN')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_schema (
    ref         VARCHAR(120) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    version     INT NOT NULL,
    definition  JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_connector (
    ref               VARCHAR(120) PRIMARY KEY,
    connector_id      VARCHAR(60)  NOT NULL,
    version           INT NOT NULL,
    data_source_code  VARCHAR(60) NOT NULL REFERENCES catalog_data_source(code),
    data_category     VARCHAR(60) NOT NULL,
    capabilities      JSONB NOT NULL,
    inputs            JSONB NOT NULL,
    sla_ms            INT,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED','RETIRED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (connector_id, version)
);

CREATE TABLE catalog_mapping (
    ref            VARCHAR(120) PRIMARY KEY,
    connector_ref  VARCHAR(120) NOT NULL REFERENCES catalog_connector(ref),
    rules          JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_journey (
    code                 VARCHAR(60) PRIMARY KEY,
    name                 VARCHAR(200) NOT NULL,
    bpmn_ref             VARCHAR(120) NOT NULL,
    required_categories  TEXT[] NOT NULL,
    policy               JSONB NOT NULL DEFAULT '{}',
    status               VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
