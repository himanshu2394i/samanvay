CREATE TABLE notification_subscription (
    id            UUID PRIMARY KEY,
    recipient_id  VARCHAR(100) NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    channel       VARCHAR(20)  NOT NULL CHECK (channel IN ('IN_APP','SMS','EMAIL')),
    locale        VARCHAR(10)  NOT NULL DEFAULT 'en',
    enabled       BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (recipient_id, event_type, channel)
);

CREATE TABLE notification_delivery (
    id             UUID PRIMARY KEY,
    recipient_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    channel        VARCHAR(20)  NOT NULL,
    dedupe_key     VARCHAR(200) NOT NULL,
    template_ref   VARCHAR(100) NOT NULL,
    rendered_body  TEXT,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts       INT NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (recipient_id, channel, dedupe_key)
);
