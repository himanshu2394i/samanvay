-- The last onboarding trial of each connector (when, and how it went), shown on the staff journey page. No fetched data is stored.
CREATE TABLE connector_trial (
    connector_ref VARCHAR(200) PRIMARY KEY,
    tried_at      TIMESTAMPTZ NOT NULL,
    outcome       VARCHAR(30) NOT NULL
);
