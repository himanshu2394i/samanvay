-- V1__audit_init.sql
-- Creates audit.audit_entry / audit.audit_checkpoint, and the two-role
-- model the tamper-evidence claim depends on. See LLD §1 for why a
-- single shared role would have silently defeated the REVOKE below.

CREATE ROLE samanvay_app LOGIN PASSWORD '${appRolePassword}';

-- Every other module's tables land in `public`, created by later
-- migrations (V20+) still run as samanvay_migrate. Grant full DML now,
-- and have Postgres auto-grant it on every future table too.
GRANT USAGE ON SCHEMA public TO samanvay_app;
ALTER DEFAULT PRIVILEGES FOR ROLE samanvay_migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO samanvay_app;
ALTER DEFAULT PRIVILEGES FOR ROLE samanvay_migrate IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO samanvay_app;

CREATE SCHEMA audit AUTHORIZATION samanvay_migrate;

CREATE TABLE audit.audit_entry (
    seq             BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type      VARCHAR(20)  NOT NULL CHECK (actor_type IN ('CITIZEN','OFFICER','SYSTEM','ADMIN')),
    actor_id        VARCHAR(100) NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    subject_id      VARCHAR(100),
    resource        VARCHAR(200),
    department_id   VARCHAR(60),
    consent_id      UUID,
    grant_id        UUID,
    outcome         VARCHAR(20)  NOT NULL CHECK (outcome IN ('ALLOWED','DENIED','ERROR')),
    reason          VARCHAR(200),
    meta            JSONB        NOT NULL DEFAULT '{}',
    prev_hash       BYTEA        NOT NULL,
    hash            BYTEA        NOT NULL UNIQUE
);

CREATE INDEX idx_audit_entry_subject ON audit.audit_entry (subject_id);
CREATE INDEX idx_audit_entry_ts      ON audit.audit_entry (ts);
CREATE INDEX idx_audit_entry_grant   ON audit.audit_entry (grant_id);

CREATE TABLE audit.audit_checkpoint (
    seq             BIGSERIAL PRIMARY KEY,
    upto_entry_seq  BIGINT      NOT NULL REFERENCES audit.audit_entry(seq),
    root_hash       BYTEA       NOT NULL,
    signed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    signature       BYTEA       NOT NULL,
    published_ref   VARCHAR(500)
);

-- The actual guarantee: samanvay_app can append and read, nothing else.
-- Rewriting history needs samanvay_migrate's credential, which the
-- running application never holds.
GRANT USAGE ON SCHEMA audit TO samanvay_app;
GRANT SELECT, INSERT ON audit.audit_entry TO samanvay_app;
GRANT SELECT, INSERT ON audit.audit_checkpoint TO samanvay_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA audit TO samanvay_app;
ALTER DEFAULT PRIVILEGES FOR ROLE samanvay_migrate IN SCHEMA audit
    GRANT SELECT, INSERT ON TABLES TO samanvay_app;
