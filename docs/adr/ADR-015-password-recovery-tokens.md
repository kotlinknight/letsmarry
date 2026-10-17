# ADR-015 — Separate password-reset tokens and durable session revocation

**Status:** Accepted; token foundation, forgot-email delivery, reset, and session enforcement implemented locally

**Date:** 2026-10-16

## Context

Email verification and planner sessions already exist, but password recovery needs its own token purpose and a way to reject every session authenticated before a reset. Reusing verification-token rows or relying only on Redis key deletion would weaken that boundary.

## Decision

- Keep password-reset tokens in a separate `password_reset_tokens` table under `identity`. Store only the SHA-256 digest of 32 cryptographically random bytes. Tokens expire after 30 minutes and may be consumed once.
- Permit one unconsumed, unrevoked token per user through a PostgreSQL partial unique index. Replacement first revokes any prior active row, including an expired row. Issuance, revocation, and consumption lock the user row before the token row to serialize races.
- Only verified, `ACTIVE` users are eligible for issuance or consumption. Token consumption must join an existing transaction; the later reset command will update the password, consume the token, and increment `users.auth_version` in that transaction.
- V5 initializes `auth_version` to zero for every retained account and constrains it to nonnegative values. Stage 1D-A does not change existing passwords, login, Redis sessions, or account eligibility.
- Stage 1D-C will use the PostgreSQL version as the authoritative all-session revocation check. Sessions lacking a captured version when enforcement starts must reauthenticate. Deleting indexed Redis sessions after a reset is best effort, after version enforcement is in place.
- The future forgot/reset APIs remain independently gated from registration and restricted to approved local/test use until production mail and security review. The existing encrypted PostgreSQL outbox will be the only durable reset-mail pipeline.

## Consequences

There is no usable password-recovery HTTP journey in 1D-A. V5 is additive and preserves V1–V4 history and data. A later stage must prove that old credentials and all earlier browser sessions are rejected after an atomic reset, including Redis cleanup failure and legacy sessions. See [ADR-007](ADR-007-redis-sessions.md), [ADR-009](ADR-009-email-outbox.md), and the [stage note](../implementation/milestone-1d-a-password-reset-tokens.md).

Stage 1D-B adds a local/test-only, independently gated forgot request. It returns generic `202` for syntactically valid emails, issues only for verified `ACTIVE` users, and commits the token and encrypted outbox job together. Redis enforces a five-minute email cooldown, three per email per day, and 30 per direct IP per hour with HMAC-keyed names and fail-closed behavior. A fixed minimum response duration reduces obvious timing differences; it is not a constant-time guarantee. The existing Mailpit sender delivers a fragment link, while the worker rejects stale purposes where possible. Reset submission, password mutation, and all-session revocation remain 1D-C work. See the [1D-B note](../implementation/milestone-1d-b-forgot-password.md).

Stage 1D-C implements the local/test-only reset API. The transaction locks the user before the token row, validates and consumes the token, writes the NFC-normalized versioned PBKDF2 password hash, increments `users.auth_version`, cancels pending reset-link mail, and enqueues an encrypted `PASSWORD_CHANGED` notice with no credentials or token. A failed notification enqueue rolls the whole reset back. The login principal captures the version; every protected request compares it with PostgreSQL, and versionless legacy sessions must reauthenticate. Spring Session's indexed Redis repository deletes UUID-indexed sessions after commit on a best-effort basis. Previously stored sessions without an index may remain until access, but their versionless principal is rejected. A reset request never creates an authenticated session. The 20-per-direct-IP-per-hour attempt limit fails closed when Redis is unavailable. See the [1D-C note](../implementation/milestone-1d-c-password-reset.md).

Stage 1D-D supplies the local Angular consumer of the existing reset contract. The token arrives only in a URL fragment, is removed from browser history on entry and same-route fragment navigation, and stays in component memory until explicit use. The page has `no-referrer` policy, does not put the token in web storage, and does not authenticate the browser after reset. The existing server-side `auth_version` comparison remains the revocation authority; Angular redirects a protected page after `/users/me` rejects the old session. See the [1D-D note](../implementation/milestone-1d-d-angular-password-recovery.md).
