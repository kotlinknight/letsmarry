# Stage 1D-A — Password-reset token foundation

## Scope and checkpoint

Completed Milestone 1C authentication changes were inspected, checked for unexpected files, generated artifacts, and credential patterns, then saved in a separate authorized local checkpoint `6c92a74`. Existing 1A (`89c6f20`) and 1B (`25ecb09`) commits were not amended. Nothing was pushed. Stage 1D-A remains uncommitted.

This stage adds only identity persistence and internal token operations. It does not add forgot/reset HTTP endpoints, email delivery, password mutation, Redis session changes, or Angular recovery pages.

## Schema and compatibility

[`V5__create_password_reset_tokens.sql`](../../backend/src/main/resources/db/migration/V5__create_password_reset_tokens.sql) appends to immutable V1–V4. It adds `users.auth_version BIGINT NOT NULL DEFAULT 0` with a nonnegative check. Existing users retain their email, password hash, verification, status, timestamps, and optimistic version. Zero is only the foundation value; Stage 1D-A does not compare it with sessions or increment it.

The separate `password_reset_tokens` table stores a UUIDv7 ID, restrictive user FK, 32-byte SHA-256 token hash, expiry, mutually exclusive consumed/revoked timestamps, and creation time. Unique hash and partial one-active-token-per-user indexes enforce storage invariants. An expired but otherwise active row still occupies the partial index, so issuing a replacement revokes it first. No raw token is stored in this table.

## Internal lifecycle and concurrency

`PasswordResetTokenSecrets` generates 32 random bytes with `SecureRandom`, uses canonical unpadded Base64 URL encoding for presentation, and hashes the decoded bytes with SHA-256. It rejects malformed or noncanonical presentations. `IssuedPasswordResetToken.toString()` omits its secret.

Only an `ACTIVE` user with `email_verified_at` may issue or consume a token. The service locks the user row before examining or locking token rows. Issuance revokes and flushes any prior active token, then inserts the replacement with a 30-minute expiry. Explicit revocation is also available internally. Consumption checks the hash, user, expiry, active state, and account standing under the same lock order. It requires an existing transaction and returns the user ID to its caller; replay or a concurrent second consumption fails. A future 1D-C reset command must call it in the same transaction as the new PBKDF2 password hash and `auth_version` increment. A rollback therefore restores token usability instead of leaving a consumed token with an unchanged password.

The existing password encoder and Unicode normalization policy remain untouched. Reset tokens are separate from email-verification tokens and have no access to the verification-token table. No notification outbox record exists for a reset token in this stage.

## Session transition and remaining work

Existing and new accounts start at `auth_version=0`. Existing Redis sessions carry only the user ID and continue to be checked for `ACTIVE` and verified standing as in 1C. Since no reset operation exists in 1D-A, the new version column alone does not claim all-session revocation. Stage 1D-C must capture a version at login, reject legacy sessions without one, compare it with PostgreSQL before protected work, and atomically increment it on reset. Deleting account sessions from Redis can then be added as best-effort cleanup after validating the indexed-repository behavior with the actual Spring Boot/Session versions.

Stage 1D-B owns the generic forgot request, independent local/test recovery gate, Redis abuse limits, encrypted outbox entry, Mailpit template, and token-delivery eligibility. Stage 1D-C owns password mutation, notification, and session revocation. Stage 1D-D owns Angular pages and the full journey. Registration UI remains in 1E. Public signup stays disabled.

## Verification record

With Docker Desktop's Linux engine available, `./gradlew.bat build --offline` passed: 92 tests across 27 suites, with no failures, errors, or skips. This includes fresh PostgreSQL migration and Hibernate schema validation, the V4-to-V5 data-preserving upgrade, token lifecycle and concurrent issue/consume tests, security regressions, and Spring Modulith verification. Spotless, changed Markdown links, and `git diff --check` passed. The first Docker-enabled run exposed two stale test expectations: the foundation test still counted four migrations, and the expiry test compared a Java nanosecond timestamp with PostgreSQL's stored timestamp precision. Both assertions were corrected and the affected tests and full suite rerun successfully. Tests used isolated containers and did not reset or modify the developer's Compose database.
