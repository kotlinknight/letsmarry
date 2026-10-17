# Stage 1D-B — Forgot-password request and email delivery

## Implemented boundary

`POST /api/v1/auth/password/forgot` accepts a trimmed, lowercased, valid email using the same anonymous `SESSION` cookie and `X-CSRF-TOKEN` flow as registration. It is present only with a `local` or `test` profile (never `production`) plus independent `app.password-recovery.enabled=true` and existing `app.email-outbox.enabled=true`. Registration and login flags are not prerequisites. The worker is separately enabled for Mailpit delivery. No reset submission or Angular recovery page exists yet.

Every valid request receives empty `202 Accepted`, `Cache-Control: no-store`, and a server-generated correlation ID whether the account is eligible, unknown, unverified, suspended, deactivated, or under an email-specific limit. Malformed JSON is `400`, invalid fields `422`, missing/invalid CSRF `403`, direct-IP excess `429`, and unavailable Redis/storage `503`, using the established Problem Details codes. A configurable 250 ms minimum successful-response duration reduces obvious account-enumeration timing differences; it does not promise constant-time behavior under unusual database or SMTP load.

## Token, outbox, and delivery

For a verified `ACTIVE` account, identity locks the account, calls the Stage 1D-A token service, revokes any prior active token, cancels and purges its pending outbox job, and enqueues a new `PASSWORD_RESET` job in one PostgreSQL transaction. The job links to the token through the existing generic `purpose_id`; no migration or second queue is needed. Only its AES-GCM ciphertext holds the URL with the 32-byte secret. The configured frontend URL must be HTTPS except for loopback HTTP, and the token is placed after `#token=`. The future Angular page will strip that fragment before further navigation. The current page is not yet implemented, so delivery can be inspected in Mailpit but cannot complete a password change.

The existing worker checks identity-owned token and account eligibility before SMTP, then sends a 30-minute reset template through the same Mailpit adapter and purges ciphertext on `SENT`, `FAILED`, or `EXPIRED`. It retains PostgreSQL row claiming, leases, bounded retries, and outside-transaction sending. An email already in flight cannot be recalled; its revoked token remains unusable. No password hash, `auth_version`, or Redis authenticated session changes in 1D-B.

## Abuse limits and configuration

`PASSWORD_RECOVERY_ENABLED` defaults false. When enabled, provide a private Base64-encoded 32-byte-or-longer `PASSWORD_RECOVERY_RATE_LIMIT_KEY` distinct from the outbox, verification, and login keys. Redis uses HMAC digests rather than plaintext email/IP key names. Defaults are one allowed request per normalized email per 300 seconds, three per email per rolling day, and 30 per direct IP per hour. These controls apply before account lookup, including unknown addresses. A missing/weak key prevents startup; a Redis error fails closed before token work. Direct IP is used until a trusted-proxy policy is defined. `PASSWORD_RECOVERY_FRONTEND_URL` defaults to `http://127.0.0.1:4200/reset-password`; `PASSWORD_RECOVERY_RESPONSE_FLOOR_MS` defaults to 250.

See [backend setup](../../backend/README.md), [OpenAPI](../api/openapi.yaml), [ADR-009](../adr/ADR-009-email-outbox.md), and [ADR-015](../adr/ADR-015-password-recovery-tokens.md). Production mail provider, trusted-origin/proxy review, reset API, password-changed notification, durable session version enforcement, Angular pages, and full account lifecycle acceptance remain later work.

## Verification record

The Stage 1D-A gate was rerun before 1D-B changes: the locked offline Gradle build and a forced Testcontainers test run passed all 92 tests, including V4-to-V5 migration, Hibernate validation, concurrency, and Modulith verification. After the 1D-B changes, `spotlessCheck build --offline` passed all 105 tests in 31 suites with no failures, errors, or skips. The suite covers HTTP/CSRF and generic responses, account eligibility, Redis limits and concurrency, PostgreSQL replacement/rollback, encrypted payloads, Mailpit delivery, worker retry/expiry, and the existing Modulith boundary test. The OpenAPI 3.0.3 file parsed with 77 valid internal references; changed-document relative links, Docker Compose application-profile configuration, and `git diff --check` passed. These checks used isolated Testcontainers services and did not reset the development database.
