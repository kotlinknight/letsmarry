CREATE TABLE email_outbox (
    id UUID PRIMARY KEY,
    recipient_email VARCHAR(320) NOT NULL,
    template_code VARCHAR(100) NOT NULL,
    encrypted_payload BYTEA,
    nonce BYTEA,
    key_version VARCHAR(32),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_id UUID,
    lease_until TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    provider_message_id VARCHAR(255),
    failure_code VARCHAR(80),
    CONSTRAINT ck_email_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_email_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_email_outbox_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_email_outbox_payload_parts CHECK (
        (encrypted_payload IS NOT NULL AND nonce IS NOT NULL AND key_version IS NOT NULL)
        OR (encrypted_payload IS NULL AND nonce IS NULL AND key_version IS NULL)
    ),
    CONSTRAINT ck_email_outbox_nonce_length
        CHECK (nonce IS NULL OR octet_length(nonce) = 12),
    CONSTRAINT ck_email_outbox_claim_state CHECK (
        (status = 'PROCESSING' AND claim_id IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_id IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_email_outbox_terminal_payload CHECK (
        status NOT IN ('SENT', 'FAILED', 'EXPIRED') OR encrypted_payload IS NULL
    ),
    CONSTRAINT ck_email_outbox_sent_time CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL)
        OR (status <> 'SENT' AND sent_at IS NULL)
    )
);

CREATE INDEX ix_email_outbox_pending
    ON email_outbox (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX ix_email_outbox_abandoned
    ON email_outbox (lease_until, id)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_email_outbox_expiry
    ON email_outbox (expires_at, id)
    WHERE status IN ('PENDING', 'PROCESSING');
