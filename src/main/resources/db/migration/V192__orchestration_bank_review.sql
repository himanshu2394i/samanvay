-- Officer review of a bank check that did not auto-accept (BankCheckReview.OFFICER_REVIEW).
-- The holder's name is never stored (it never leaves the connector adapter); only the masked
-- account, the machine reason, and the matcher version are kept, plus, once uploaded, a hash of
-- the passbook/cancelled cheque. The file itself is deleted on decision or after 30 days; the
-- hash and the officer's decision are kept (like other signed documents).
--
-- Forward-only. Numbered V192, above phase-2's V186-V191, so it stays monotonic under any merge order.

CREATE TABLE orchestration_bank_review (
    id                   UUID PRIMARY KEY,
    application_id       TEXT NOT NULL,
    citizen_id           UUID NOT NULL,
    account_masked       TEXT NOT NULL,
    review_reason        TEXT NOT NULL,
    matcher_version      TEXT,
    status               TEXT NOT NULL,
    document_hash        TEXT,
    document_path        TEXT,
    document_uploaded_at TIMESTAMPTZ,
    decided_by           TEXT,
    decision_reason      TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    decided_at           TIMESTAMPTZ,
    CONSTRAINT orchestration_bank_review_status
        CHECK (status IN ('PENDING_OFFICER', 'DOCUMENT_REQUESTED', 'APPROVED', 'REJECTED')),
    -- A decided review keeps its hash but never a live file path.
    CONSTRAINT orchestration_bank_review_decided_has_no_file
        CHECK (status IN ('PENDING_OFFICER', 'DOCUMENT_REQUESTED') OR document_path IS NULL)
);

CREATE INDEX orchestration_bank_review_open
    ON orchestration_bank_review (created_at)
    WHERE status IN ('PENDING_OFFICER', 'DOCUMENT_REQUESTED');
