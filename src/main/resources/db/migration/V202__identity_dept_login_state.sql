-- A department login a citizen has started: a one-time state + nonce bound to that citizen and department
-- (docs/contracts/login-assertion.md). The department echoes both in its signed assertion; Samanvay accepts an
-- assertion only for the citizen who started that login, once (consumed_at). Short-lived; old rows are housekeeping.
CREATE TABLE identity_dept_login_state (
    state           VARCHAR(100) PRIMARY KEY,
    nonce           VARCHAR(100) NOT NULL,
    citizen_id      UUID         NOT NULL,
    department_code VARCHAR(60)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ
);

CREATE INDEX identity_dept_login_state_expiry ON identity_dept_login_state (expires_at);
