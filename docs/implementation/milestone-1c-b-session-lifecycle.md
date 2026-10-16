# Stage 1C-B — Current user, logout, and session lifecycle

## Scope and endpoints

`GET /api/v1/users/me` requires an authenticated Redis session and returns a no-store DTO with the current account's ID, display name, normalized email, email-verification state, and account status. It reads current PostgreSQL data; it never returns a password hash, token, or session ID. Anonymous, expired, invalidated, ineligible, and deleted-account sessions receive `401 AUTHENTICATION_REQUIRED` Problem Details with the server-generated correlation ID.

`POST /api/v1/auth/logout` uses Spring Security's logout handling. It requires a valid CSRF header, invalidates only the current Redis session, clears the security context, expires the `SESSION` cookie, and returns empty no-store `204`. Logout with a valid anonymous CSRF session also returns `204`, making a repeated logout safe after the client calls `/auth/csrf` again. Replaying the pre-logout token returns `403 CSRF_INVALID`. Logout does not revoke other browser sessions for the same account.

## Security integration and account standing

The session principal now stores only the stable user UUID. The JSON login response and `/users/me` response obtain safe display fields from PostgreSQL. A Spring Security filter positioned after authorization and before application code checks the current `users` row for every protected request. It requires `ACTIVE` status and non-null `email_verified_at`. A missing, suspended, deactivated, or newly unverified account causes immediate invalidation of that browser session and generic `401`. The validated safe account snapshot is reused by `/users/me`, avoiding a second query on that request. Public authentication and health endpoints do not perform account-standing queries; logout remains available even if an account was suspended.

The filter uses a small platform contract implemented inside `identity`; platform security code does not access identity JPA repositories. No wedding roles or permissions are added. No schema migration or new dependency is required.

## Redis and CSRF lifecycle

The configured inactivity timeout remains 30 minutes. Spring Session stores sessions only in Redis. Multiple browsers can stay signed in, while logout revokes only the browser that sends the request. Cookies remain HttpOnly and SameSite=Lax, Secure for HTTPS outside the deliberate local loopback HTTP profile. Session IDs and credentials stay out of JSON responses and logs.

`GET /api/v1/auth/csrf` still creates an anonymous Redis session independently of registration and outbox gates. Login rotates the session ID and clears the earlier CSRF token. A fresh `/auth/csrf` response supplies the token for authenticated writes, including logout. Logout invalidates that session and its CSRF state; an anonymous session and token can be bootstrapped afterward. Expired sessions cannot restore identity. Redis or database access failures deny protected access with `503 AUTHENTICATION_TEMPORARILY_UNAVAILABLE` Problem Details; no in-memory session fallback exists. A temporary dependency outage does not deliberately revoke an otherwise eligible session.

## Verification

`./gradlew.bat build --offline` passed with Docker's Linux engine available: 80 JUnit tests, zero failures or skips. The build includes compilation, Spotless checks, Spring Modulith verification, and Flyway/Hibernate validation. The Testcontainers security suite uses isolated PostgreSQL and Redis containers, including a paused-Redis check; it does not reset the developer's Compose database. Tests cover current account freshness and safe fields, anonymous and expired sessions, logout cookie and Redis invalidation, old-session reuse, CSRF renewal, repeated logout, two concurrent browsers, account suspension/deactivation/unverification/deletion, public-path query bypass, and fail-closed reads and logout during Redis outage. Existing registration and login tests remain in the full build. The OpenAPI 3.0.3 file was parsed and its local references resolved separately.

## Remaining limits and next stage

Angular login, session restoration, and logout UI belong to 1C-C. Password recovery and wedding permissions remain later work. The login feature gate is still off by default and independent of the local/test-only registration gate. Public signup remains disabled, including pending review of duplicate-registration account enumeration. Production rollout still needs trusted-origin/reverse-proxy configuration and operational review of login rate limits. A status change is enforced on the next protected request; there is no cross-session proactive broadcast or immediate mass revocation. Existing browser sessions will expire normally if the login feature gate is turned off after they were created.
