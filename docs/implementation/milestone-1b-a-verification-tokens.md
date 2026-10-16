# Milestone 1B-A — Verification-token groundwork

## Scope and current behavior

This stage adds the token table and internal identity lifecycle only. The existing local/test-only registration endpoint still saves an unverified user without issuing a token or sending email. There is no verification or resend HTTP endpoint, outbox, email worker, Mailpit integration, or Angular confirmation page. The OpenAPI file remains the accurate contract for the two existing 1A endpoints.

Milestone 1A is preserved as a separate local checkpoint (`89c6f20`); Stage 1B-A is not committed or pushed. Existing 1A accounts remain in place and can receive a token through a later resend/request endpoint without a database reset.

## Migration and persistence

[`V2__create_email_verification_tokens.sql`](../../backend/src/main/resources/db/migration/V2__create_email_verification_tokens.sql) appends a table after the immutable `users` migration. Each UUIDv7 row references `users(id)` and stores a 32-byte `BYTEA` SHA-256 digest, creation and expiry timestamps, and optional consumption or revocation timestamps. The digest is unique. A partial unique index allows at most one unconsumed, unrevoked token per user, including expired rows until they are explicitly revoked. A separate expiry index supports future cleanup. Database checks enforce digest length, expiry after creation, and mutually exclusive terminal states. Flyway owns the schema; Hibernate retains `ddl-auto=validate`.

The `identity` repository locks a user before token changes. Issuance revokes and flushes an old active token before inserting a replacement, satisfying the partial unique index even when the previous token has expired. The user lock serializes concurrent issuance and consumption; consumption then locks the token row and rechecks its state. The database index is the final safeguard against two active rows.

## Secret and account lifecycle

The internal service generates 32 random bytes with `SecureRandom` and encodes them as a 43-character unpadded Base64URL secret. It stores SHA-256 of the decoded bytes, never the secret or a link. Input must use the canonical encoding. The secret exists transiently in the service result for future encrypted outbox delivery; its diagnostic string representation excludes it. Do not log or return it through an ordinary API response.

Tokens expire exactly 24 hours after issuance; the boundary is exclusive. Invalid, expired, revoked, or consumed tokens cannot be used. Replacement revokes the old token in the same transaction as the new insert. A later resend stage must cancel pending old email jobs where possible; a message already in flight may contain an invalidated link. The future UI should handle that safely and offer a fresh request.

Successful internal verification consumes the token and sets `users.email_verified_at` in one transaction. It leaves `users.status` as `ACTIVE`: that field represents standing rather than email proof. `SUSPENDED` and `DEACTIVATED` accounts cannot issue or consume verification tokens. Future login must require both `ACTIVE` and a non-null verification timestamp. No session is created by the token service.

## Verification

From `backend/`, with Java 25 and Docker's Linux engine available:

```powershell
.\gradlew.bat spotlessJavaCheck test
.\gradlew.bat build
```

Unit tests cover secret format, canonical decoding and hashing, expiry boundaries, revocation, and single use. PostgreSQL/Redis Testcontainers tests cover V2 migration and Hibernate validation, an existing unverified user's ability to receive a token, hash-only storage, expiry and rotation, status preservation, replay rejection, concurrent issuance, concurrent consumption, and suspended-account rejection. They do not use or reset the developer's Compose database.

On 2026-10-13, the complete backend build and Spotless check passed: 30 JUnit tests, zero failures. A separate PostgreSQL migration test applied V1, inserted an unverified account, then applied V2 and confirmed that the account remained intact. No manual migration was run against the developer's database.

## Remaining 1B work

1B-B must add the sole encrypted PostgreSQL email outbox and delivery worker. 1B-C must make registration save user, token, and outbox entry atomically, expose guarded verification/resend APIs, and add the minimal Angular confirmation page. 1B-D must wire Mailpit, abuse limits, end-to-end checks, and final documentation. Registration remains local/test-only. The existing duplicate-email `409` response requires account-enumeration review before public signup.
