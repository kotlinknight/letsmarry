ALTER TABLE email_outbox ADD COLUMN purpose_id UUID;

CREATE INDEX ix_email_outbox_pending_purpose
    ON email_outbox (purpose_id)
    WHERE purpose_id IS NOT NULL AND status = 'PENDING';
