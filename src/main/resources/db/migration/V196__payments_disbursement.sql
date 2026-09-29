-- V196__payments_disbursement.sql (forward-only, additive)
--
-- 1. The payments module: a disbursement issued once an application is approved, with the
--    instalment ids that payment-scoped checks (consent frequency ONCE_PER_PAYMENT) refer to.
--    DBT is mocked (HLD 1.5): this records that money is due and gives each instalment an id;
--    no payment rail is called and no amount is held.
--    One disbursement per application (the UNIQUE constraint is what makes issuing idempotent:
--    a redelivered approval event inserts nothing). No foreign key to another module's table.
CREATE TABLE payments_disbursement (
    id             UUID         PRIMARY KEY,
    application_id UUID         NOT NULL,
    citizen_id     UUID         NOT NULL,
    journey_code   VARCHAR(60)  NOT NULL,
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('ISSUED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payments_disbursement_one_per_application UNIQUE (application_id)
);

CREATE TABLE payments_instalment (
    id              UUID        PRIMARY KEY,
    disbursement_id UUID        NOT NULL REFERENCES payments_disbursement(id),
    sequence_no     INTEGER     NOT NULL CHECK (sequence_no >= 1),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('SCHEDULED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payments_instalment_sequence UNIQUE (disbursement_id, sequence_no)
);

-- 2. consent_usage.scope_key_version: for a ONCE_PER_PAYMENT check, scope_key is
--    'PAYMENT:' || hex(HMAC-SHA256(key, payment id)) and this names the key version that
--    produced the hash (LLD 05 section 7.4). NULL for every other scope (application id,
--    YEAR:<year>) and for every existing row: one nullable column, no default, no backfill,
--    no rewrite of any row. The UNIQUE constraint consent_usage_one_check is unchanged.
ALTER TABLE consent_usage ADD COLUMN scope_key_version VARCHAR(32);
