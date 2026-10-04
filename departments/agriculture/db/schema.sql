-- Department of Agriculture database schema (for a deployment). FAKE data only; never a real system.
--
-- Mounted into the department's own Postgres (docker-entrypoint-initdb.d) ahead of the generated seed file, which fills these
-- tables with the demo farmers and creates the database roles with generated passwords. Idempotent.
--
-- Two accounts use this database, with very different rights (created and granted by the generated seed):
--   agriculture_app  this department's own service: reads citizen_login to sign a farmer in, and the farmer's name and date of
--                    birth (column grant) for the login assertion.
--   agri_ro          the read-only login Samanvay uses over JDBC: SELECT on the VIEW v_farmer_record only. It cannot see the
--                    base table or internal_notes.
-- (db/init.sql is the older all-in-one script with fixed dev passwords, kept for the local docker compose.)
--
-- Passwords are bcrypt hashes made with pgcrypto: crypt(password, gen_salt('bf')), checked in SQL with
-- password_hash = crypt(:password, password_hash). No plaintext password is ever stored.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Who can sign in to the farmer login, and which farmer each login belongs to.
CREATE TABLE IF NOT EXISTS citizen_login (
    mobile         VARCHAR(10)  PRIMARY KEY,
    password_hash  TEXT         NOT NULL,
    person_id      VARCHAR(32)  NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS farmer (
    agri_person_id  VARCHAR(32)   PRIMARY KEY,
    farmer_name     VARCHAR(120)  NOT NULL,
    village         VARCHAR(80)   NOT NULL,
    taluka          VARCHAR(80)   NOT NULL,
    land_hectares   NUMERIC(6, 2) NOT NULL,
    internal_notes  TEXT,
    date_of_birth   DATE
);

-- Databases created before the portal change have no date of birth column yet.
ALTER TABLE farmer ADD COLUMN IF NOT EXISTS date_of_birth DATE;

-- Crop sowing reports. Also exported as a CSV for Samanvay to pick up over SFTP (outbound/crop.csv).
CREATE TABLE IF NOT EXISTS crop_sowing (
    agri_person_id  VARCHAR(32)   NOT NULL REFERENCES farmer (agri_person_id),
    season          VARCHAR(24)   NOT NULL,
    crop            VARCHAR(60)   NOT NULL,
    area_hectares   NUMERIC(6, 2) NOT NULL,
    PRIMARY KEY (agri_person_id, season, crop)
);

-- What Agriculture chooses to share with Samanvay: no internal notes.
CREATE OR REPLACE VIEW v_farmer_record AS
    SELECT agri_person_id, farmer_name, village, taluka, land_hectares
    FROM farmer;
