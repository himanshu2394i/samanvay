-- Direct Benefit Transfer (DBT) database schema. FAKE data only; never a real system.
--
-- Mounted into the department's own Postgres (docker-entrypoint-initdb.d) ahead of the generated seed file, which fills these
-- tables with the demo citizens. Idempotent, so it is safe to run again.
--
-- Passwords are bcrypt hashes made with pgcrypto: crypt(password, gen_salt('bf')), checked in SQL with
-- password_hash = crypt(:password, password_hash). No plaintext password is ever stored.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Who can sign in to the DBT citizen login, and which beneficiary each login belongs to.
CREATE TABLE IF NOT EXISTS citizen_login (
    mobile         VARCHAR(10)  PRIMARY KEY,
    password_hash  TEXT         NOT NULL,
    person_id      VARCHAR(32)  NOT NULL UNIQUE
);

-- A person registered for direct benefit transfers. One DBT ID unlocks their bank record (no resolve step).
CREATE TABLE IF NOT EXISTS beneficiary (
    person_id      VARCHAR(32)  PRIMARY KEY,
    full_name      VARCHAR(120) NOT NULL,
    mobile         VARCHAR(10)  NOT NULL UNIQUE,
    date_of_birth  DATE         NOT NULL
);

-- The account benefit payments go to. The account number and IFSC are held masked, as DBT shares them.
CREATE TABLE IF NOT EXISTS bank_account (
    person_id      VARCHAR(32)  PRIMARY KEY REFERENCES beneficiary (person_id),
    account_ref    VARCHAR(20)  NOT NULL,
    ifsc_masked    VARCHAR(16)  NOT NULL,
    holder_name    VARCHAR(120) NOT NULL,
    bank_name      VARCHAR(80)  NOT NULL,
    seeded_on      DATE         NOT NULL
);
