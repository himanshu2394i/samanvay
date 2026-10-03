-- Revenue Department database schema. FAKE data only; never a real system.
--
-- Mounted into the department's own Postgres (docker-entrypoint-initdb.d) ahead of the generated seed file, which fills these
-- tables with the demo citizens. Idempotent, so it is safe to run again.
--
-- pgcrypto is used for password hashes: a stored hash is crypt(password, gen_salt('bf')) (bcrypt), checked in SQL with
-- password_hash = crypt(:password, password_hash). No plaintext password is ever stored.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Who can sign in to the Revenue citizen login, and which person each login belongs to.
CREATE TABLE IF NOT EXISTS citizen_login (
    mobile         VARCHAR(10)  PRIMARY KEY,
    password_hash  TEXT         NOT NULL,
    person_id      VARCHAR(32)  NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS person (
    person_id      VARCHAR(32)  PRIMARY KEY,
    full_name      VARCHAR(120) NOT NULL,
    date_of_birth  DATE         NOT NULL,
    district       VARCHAR(80)  NOT NULL,
    taluka         VARCHAR(80)  NOT NULL,
    village        VARCHAR(80)  NOT NULL
);

-- Certificates issued by this department. Each has its OWN certificate number (the document key): a person ID alone does not
-- fetch one, which is why the manifest publishes a resolve step. A person may hold several of a type (renewals).
CREATE TABLE IF NOT EXISTS certificate (
    cert_no        VARCHAR(40)  PRIMARY KEY,
    cert_type      VARCHAR(40)  NOT NULL CHECK (cert_type IN ('INCOME_CERTIFICATE', 'CASTE_CERTIFICATE', 'DOMICILE_CERTIFICATE')),
    person_id      VARCHAR(32)  NOT NULL REFERENCES person (person_id),
    issued_on      DATE         NOT NULL,
    issuing_office VARCHAR(120) NOT NULL,
    -- the fields the manifest publishes for this type, as the department holds them
    fields         JSON         NOT NULL
);
CREATE INDEX IF NOT EXISTS certificate_person_type ON certificate (person_id, cert_type);

-- 7/12 land records. They are also exported nightly as a CSV for Samanvay to pick up over SFTP (outbound/712.csv).
CREATE TABLE IF NOT EXISTS land_record (
    person_id      VARCHAR(32)  NOT NULL REFERENCES person (person_id),
    survey_no      VARCHAR(40)  NOT NULL,
    village        VARCHAR(80)  NOT NULL,
    taluka         VARCHAR(80)  NOT NULL,
    district       VARCHAR(80)  NOT NULL,
    area_hectares  NUMERIC(6, 2) NOT NULL,
    owner_name     VARCHAR(120) NOT NULL,
    PRIMARY KEY (person_id, survey_no)
);
