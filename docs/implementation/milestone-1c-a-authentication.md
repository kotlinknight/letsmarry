# Stage 1C-A — Backend login and Redis session establishment

## Scope and checkpoint

This historical stage record describes backend JSON login before 1C-B added `/users/me`, logout, and session revalidation. It did not add Angular authentication, password recovery, wedding features, or public signup. The completed 1B changes were reviewed for scope and secrets and saved in separate local checkpoint `25ecb09` before 1C-A edits. The checkpoint was not pushed; 1C-A remains uncommitted.

## Authentication flow

`GET /api/v1/auth/csrf` is now a shared, always-available bootstrap endpoint. It returns a no-store token and creates an anonymous Redis-backed `SESSION` cookie independently of the registration and outbox gates. The anonymous session grants no planner access. `POST /api/v1/auth/login` requires the same cookie and named CSRF header, then accepts JSON email/password. The login endpoint exists only when `app.authentication.enabled=true`; this default-off gate has no profile restriction, so a later approved deployment can enable login while leaving public registration disabled. Registration and verification remain local/test-only.

The identity authentication provider normalizes email using the registration trim/lowercase policy and normalizes password to Unicode NFC without trimming. It checks the existing `{pbkdf2@SpringSecurity_v5_8}` hash through the configured `PasswordEncoder`, then requires both `users.status=ACTIVE` and a non-null `email_verified_at`. Unknown email performs a dummy hash check to reduce timing differences. Unknown account, incorrect password, unverified email, suspended status, and deactivated status share one `401 INVALID_CREDENTIALS` Problem Details response. Passwords and hashes never enter the success DTO or session principal.

Successful authentication invokes Spring Security's session-authentication strategy: it changes the anonymous session ID and clears its old CSRF token. The controller saves the Spring Security context to the HTTP session, which Spring Session persists in Redis. At 1C-A, the principal carried user ID, display name, and normalized email; 1C-B reduced it to the stable ID only. Credentials are null. The response is no-store `200` with a safe `user` object, and the rotated opaque ID remains in the HttpOnly cookie. A client must fetch `/auth/csrf` again before another unsafe request. The base 30-minute inactivity timeout and Secure/SameSite=Lax cookie settings remain; local loopback HTTP retains its deliberate Secure=false exception.

## Abuse controls and errors

The mandatory Redis limiter atomically counts every syntactically valid login attempt, whether the account exists or not. Its initial configurable limits are 10 attempts per normalized email and 60 per direct remote IP within 900 seconds. Redis key names contain HMAC-SHA-256 digests made with a separate private key; no email or IP appears as plaintext in those keys. An exceeded limit returns `429 AUTHENTICATION_RATE_LIMITED` with `Retry-After`; unavailable limiting returns `503 AUTHENTICATION_TEMPORARILY_UNAVAILABLE` before credential checking. CSRF remains required, and an invalid token returns `403 CSRF_INVALID`. Malformed JSON returns `400`; invalid request fields return `422`. Errors retain server-generated correlation IDs.

Set `AUTHENTICATION_ENABLED=true` and `AUTHENTICATION_RATE_LIMIT_KEY` (Base64 of at least 32 private random bytes) in the backend process environment to opt in. Optional `AUTHENTICATION_EMAIL_ATTEMPTS`, `AUTHENTICATION_IP_ATTEMPTS`, and `AUTHENTICATION_WINDOW_SECONDS` override the defaults. A missing/weak limiter key rejects startup when authentication is enabled. Local development still needs the existing database/Redis settings; for registration and Mailpit, it additionally needs the existing 1B keys and SMTP opt-in. Never put real keys in Git or browser configuration.

## Persistence and module boundary

No Flyway migration or dependency was added. Login reads the existing `users` table; it creates no wedding membership. `identity` owns the authentication provider, account eligibility, and rate limiting. `platform` owns the shared security and CSRF endpoint. The registration, verification, and notification paths remain unchanged except for sharing email normalization and making their CSRF bootstrap independent.

## Verification and remaining work

`./gradlew.bat build --offline` passed with Docker's Linux engine available: 74 tests, zero failures or skips. The build includes compilation, Spotless, the Spring Modulith boundary test, Flyway/Hibernate validation, and isolated PostgreSQL and Redis Testcontainers integration tests. The new tests cover generic failure responses, password/email normalization, rate limiting including concurrent attempts, CSRF, session-ID rotation, multiple simultaneous browser sessions, Redis persistence, safe principal content, and default-off behavior. The developer's Compose database was not reset or used by these tests.

Stage 1C-B subsequently added `/users/me`, logout, session-expiration behavior, and server-side account-status rechecks/revocation on protected requests. Angular login and route handling belong to 1C-C. Public signup remains blocked, including pending review of its duplicate-email `409` response. Production login also needs deployment-origin/proxy and operational rate-limit review before its gate is enabled.
