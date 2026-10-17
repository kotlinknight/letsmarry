ALTER TABLE users
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_users_auth_version_nonnegative CHECK (auth_version >= 0);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_password_reset_token_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT ck_password_reset_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_password_reset_token_terminal_state
        CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

CREATE UNIQUE INDEX ux_password_reset_token_hash
    ON password_reset_tokens (token_hash);

CREATE UNIQUE INDEX ux_password_reset_one_active_per_user
    ON password_reset_tokens (user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_password_reset_token_expiry
    ON password_reset_tokens (expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
