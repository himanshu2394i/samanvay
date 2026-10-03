-- State Board of Education database schema. FAKE data only; never a real system.
--
-- Mounted into the department's own Postgres (docker-entrypoint-initdb.d) ahead of the generated seed file, which fills these
-- tables with the demo students. Idempotent, so it is safe to run again.
--
-- Passwords are bcrypt hashes made with pgcrypto: crypt(password, gen_salt('bf')), checked in SQL with
-- password_hash = crypt(:password, password_hash). No plaintext password is ever stored.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Who can sign in to the Board's student login, and which student each login belongs to.
CREATE TABLE IF NOT EXISTS citizen_login (
    mobile         VARCHAR(10)  PRIMARY KEY,
    password_hash  TEXT         NOT NULL,
    person_id      VARCHAR(32)  NOT NULL UNIQUE
);

-- A student registered with the Board. person_id is the Board's own student ID.
CREATE TABLE IF NOT EXISTS student (
    person_id      VARCHAR(32)  PRIMARY KEY,
    full_name      VARCHAR(120) NOT NULL,
    mobile         VARCHAR(10)  NOT NULL UNIQUE,
    date_of_birth  DATE         NOT NULL,
    seat_number    VARCHAR(16)  NOT NULL UNIQUE,
    school         VARCHAR(160) NOT NULL
);

-- The marks statement for an examination. The SOAP service returns the student's latest one.
CREATE TABLE IF NOT EXISTS marks_statement (
    person_id      VARCHAR(32)   NOT NULL REFERENCES student (person_id),
    exam           VARCHAR(40)   NOT NULL,
    board          VARCHAR(40)   NOT NULL,
    percentage     NUMERIC(5, 2) NOT NULL CHECK (percentage BETWEEN 0 AND 100),
    exam_year      INTEGER       NOT NULL,
    PRIMARY KEY (person_id, exam)
);
