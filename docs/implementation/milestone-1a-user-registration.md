# Milestone 1A — User registration

## Objective and scope

Milestone 1A is the first backend business slice. It creates an unverified planner account in PostgreSQL and publishes only a local/test registration API. It does not implement email verification, outbox delivery, login/logout, password recovery, Angular registration screens, wedding creation, or wedding roles. The approved temporary exception to the eventual atomic registration/email workflow is recorded in [ADR-009](../adr/ADR-009-email-outbox.md).

## Architecture and responsibilities

| Area | Responsibility |
|---|---|
| `identity.api` | Request/response DTOs, registration and CSRF endpoints, validation/error mapping |
| `identity.application` | Transactional registration use case, normalization, password policy, duplicate-email translation |
| `identity.domain` | JPA user model and account status; no wedding-specific role |
| `identity.infrastructure` | Spring Data repository and versioned PBKDF2 password encoder |
| `platform` | Existing security policy plus correlation IDs and RFC 9457 responses for security errors |

The API DTOs never expose the JPA entity. The registration service flushes within its transaction so a unique-index violation can become a consistent `409` before returning. Other integrity failures remain server errors rather than being misreported as duplicate email.

## Database migration

[`V1__create_users.sql`](../../backend/src/main/resources/db/migration/V1__create_users.sql) creates only `users`:

| Column | PostgreSQL type | Constraint or purpose |
|---|---|---|
| `id` | `UUID` | Primary key; generated as UUIDv7 by Hibernate before insert |
| `email` | `VARCHAR(320)` | Required, normalized; unique index on `lower(email)` |
| `password_hash` | `VARCHAR(255)` | Required, versioned adaptive hash |
| `display_name` | `VARCHAR(150)` | Required |
| `phone` | `VARCHAR(30)` | Nullable; not supplied by the 1A API |
| `status` | `VARCHAR(30)` | Required; check allows `ACTIVE`, `SUSPENDED`, `DEACTIVATED` |
| `email_verified_at` | `TIMESTAMPTZ` | Null on registration |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | Required; `now()` default |
| `version` | `BIGINT` | Required, default `0`, JPA optimistic locking |

Flyway owns the schema; Hibernate remains at `ddl-auto=validate`. This migration is append-only once shared. `ACTIVE` does not imply a verified email: the future login policy must check `email_verified_at` too.

## Request flow and API contract

1. `GET /api/v1/auth/csrf` materializes a token in an anonymous Redis-backed Spring Session and returns JSON `{ "token": "...", "headerName": "X-CSRF-TOKEN" }` plus an HttpOnly `SESSION` cookie. The response is `no-store`.
2. The caller sends `POST /api/v1/auth/register` with the same cookie, token header, and JSON `displayName`, `email`, and `password`.
3. Bean Validation checks required fields and email syntax after display-name/email trimming. The application normalizes email to lowercase and password to Unicode NFC, validates password length, hashes it, and inserts a user in one PostgreSQL transaction.
4. Success returns `201` with `id`, trimmed `displayName`, normalized `email`, and `emailVerified: false`. No authenticated session or wedding membership is created.

The complete machine-readable contract is [OpenAPI 3.0.3](../api/openapi.yaml). There is no `Location` header because a user-read resource has not been implemented. Malformed JSON returns `400/MALFORMED_JSON`; invalid fields return `422/VALIDATION_ERROR` with field errors; an existing email returns `409/EMAIL_ALREADY_REGISTERED`; missing or invalid CSRF returns `403/CSRF_INVALID`. Errors use `application/problem+json`, with a server-generated `X-Correlation-ID` matching the body extension. The default-off gate makes both endpoints absent (`404`) outside local/test opt-in.

## Validation and security decisions

- Display name is trimmed, required, and at most 150 characters. Email is trimmed, lowercased with `Locale.ROOT`, checked as an email address, and at most 320 characters. The database unique index is authoritative for case-insensitive uniqueness, including concurrent requests.
- Password is **not trimmed**. It is NFC-normalized, then must be 15–128 Unicode code points with no control characters. Spaces and Unicode are accepted without character-composition rules. The encoder writes a `{pbkdf2@SpringSecurity_v5_8}` prefix so a future login can select the correct verifier and later algorithms can be added without rewriting existing hashes.
- Passwords and hashes never appear in responses or application logs. Registration does not create a login session. The CSRF session remains anonymous and grants no access to protected APIs.
- `app.registration.enabled=false` is the default, and the controller also requires the `local` or `test` profile. The `local` profile enables it only on `127.0.0.1` with the local HTTP cookie exception; integration tests enable it explicitly. Deployed environments cannot expose the 1A controller merely by setting the property. CSRF is never disabled for registration.

## Verification

From `backend/`, with Java 25 and Docker's Linux engine available:

```powershell
.\gradlew.bat build
.\gradlew.bat spotlessJavaCheck
```

The build runs unit tests, fresh PostgreSQL/Redis Testcontainers integration tests, Flyway validation, and Spring Modulith boundary verification. Registration tests cover normalized persistence, UUIDv7, hash-only storage, initial unverified state, duplicate case/race behavior, validation/Problem Details, CSRF cookie/token pairing, lack of authenticated access, and default-off behavior. These tests use isolated containers and do not reset the developer's Compose database. The [backend README](../../backend/README.md) contains a manual local API request sequence.

On 2026-10-13, `gradlew.bat build` passed with 18 tests and zero failures; Spotless and Modulith checks passed. The OpenAPI YAML and its 18 local references parsed, changed Markdown links resolved, and `git diff --check` found no whitespace errors. No manual request against the developer's Compose database was made; the HTTP behavior was exercised against the isolated Testcontainers application.

## Limitations and next milestone

Milestone 1A accounts cannot verify email or log in. It has no production signup rate limit or verification delivery, so the registration gate stays off by default. There is no token table, outbox table, mail sender, worker, or Angular account UI. Milestone 1B must add hash-only verification tokens and the sole PostgreSQL email outbox, make future registration insert user/token/outbox atomically, and define how retained unverified 1A accounts receive verification without deleting them. Login/session renewal and account recovery remain stages 1C and 1D.
