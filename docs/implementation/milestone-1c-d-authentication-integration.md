# Stage 1C-D — Authentication integration and verification

## Purpose and scope

This stage verifies the 1B registration/email path and the 1C Spring Security and Angular authentication path as one system. It adds one opt-in real-service Chromium journey and corrects a Redis session namespace configuration defect. It adds no account or wedding feature, dependency, or Flyway migration. Registration and verification remain local/test-only; authentication remains disabled unless explicitly enabled.

## Isolated test environment

The real browser run used the Angular development server and its same-origin `/api` proxy at `127.0.0.1:4200`, the packaged Spring Boot 4.1.1 backend at `127.0.0.1:8080`, and a separate Compose project named `mmm1cd20261016`: PostgreSQL 17.11 on host port 15432, Redis 8.2.9 on 16379, and Mailpit v1.31.4 on SMTP 11025 / HTTP 18025. Only these three Compose services were started. The backend used the `local` profile and explicit opt-ins for registration, verification, authentication, and the email worker, with fresh process-only test keys. Flyway applied V1–V4 to that project's newly created database; Hibernate validated the schema. The existing development Compose project and database were not used or reset.

## Complete authentication journey

The opt-in test at [`frontend/e2e/authentication-real.spec.ts`](../../frontend/e2e/authentication-real.spec.ts) uses actual HTTP requests and a real Chromium cookie jar. It bootstraps CSRF, registers a unique account, and checks the 201 safe response. A direct read of the isolated PostgreSQL container confirms one unverified user, one 32-byte token hash, and one outbox entry. The separate rollback integration test proves the three writes are atomic if outbox enqueue fails. The browser test checks an unverified login is refused.

It polls Mailpit's real inbox for that recipient, fetches the delivered message, and confirms that its token hashes to the database value and that the sent outbox row has purged its ciphertext. Chromium opens the actual link, loads `/verify-email`, observes that the fragment is removed from browser history, and confirms the token remains unused until the **Verify email** button is clicked. After confirmation, PostgreSQL shows a verified `ACTIVE` user and consumed token. Reuse returns 400.

The same browser then signs in through `/login`, reaches `/account`, calls `/users/me`, reloads and restores the session, and signs out through the UI. The test confirms that the HttpOnly, SameSite=Lax local cookie rotates on login; the old anonymous Redis session disappears, the authenticated Redis key exists, and logout removes it. A replay of the old cookie gets 401, and protected navigation redirects to `/login`. Angular localStorage and sessionStorage remain empty. The explicit `secure=false` assertion applies only to the local HTTP profile; base configuration requires Secure cookies.

## Real-service approach and results

Set `MMM_REAL_SERVICES=1` to run this test. It intentionally skips in the normal fixture-based Playwright suite, which does not require Docker or a live backend. The test also requires `MMM_REAL_POSTGRES_CONTAINER`, `MMM_REAL_REDIS_CONTAINER`, `MMM_REAL_DB_NAME`, `MMM_REAL_DB_USER`, and `MMM_REAL_MAILPIT_URL`. Its SQL and Redis assertions target only the named isolated containers. Do not point these variables at a development or shared database.

On 2026-10-16, the final isolated Chromium journey passed: **1 passed**. The normal Playwright run passed **7 fixture-based journeys, 1 real-service journey skipped**. The Angular production build, lint, formatting check, and **32 unit tests** passed. The full backend build, Spotless, JUnit/Testcontainers, Modulith, and migration checks passed with **80 tests** and zero failures or skips. The backend Testcontainers suite used its own PostgreSQL and Redis containers, separate from both the browser-test stack and development services. No live production or GitHub Actions run is claimed.

## Session, CSRF, and security regression

The real journey confirms browser cookie forwarding through the Angular proxy, Redis session storage, login rotation, reload restoration, logout deletion, and old-cookie rejection. Backend integration tests additionally check 30-minute inactivity expiry, fresh CSRF after login, CSRF rejection on unsafe requests, repeated logout semantics, multiple independent browsers, and fail-closed Redis outages. They verify identical 401 Problem Details for unknown account, wrong password, unverified email, suspended and deactivated accounts; both email and IP limits; concurrent attempt counting; and invalidation on account suspension, deactivation, unverification, or deletion. Frontend unit and fixture browser tests cover invalid form feedback, loading state, rate-limit/CSRF messages, protected routes, mobile Light/Dark use, and uncertainty after a failed or unreachable logout. These latter UI failure cases are controlled fixtures, not claims of a live outage test in Chromium.

The API contract at [`docs/api/openapi.yaml`](../api/openapi.yaml) was parsed with all local references resolved and compared with the implemented auth paths and Angular DTOs. No API behavior changed in 1C-D, so its version remains `1.0.0-1c-b`. No token, password, or session ID is returned in the safe API responses or written to Angular storage. The integration test deliberately avoids printing them.

## Issue found and fix

The first real journey reached a successful login but found no session under the documented `makemymarriage:session` Redis prefix. Direct key inspection showed the default `spring:session` prefix. Spring Boot 4.1.1 binds this setting at `spring.session.data.redis.namespace`; the old `spring.session.redis.namespace` path in `application.yml` was ignored. The configuration now uses the Boot 4 path, and `FoundationIntegrationTests` asserts both the intended key and absence of the default key. Spring Session Base64-encodes the cookie value, so the browser test decodes it before inspecting the Redis key. The corrected real journey and full backend suite pass.

## Local manual smoke procedure

1. Choose a new Compose project name and unused loopback ports. Set `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`, `REDIS_PORT`, `MAILPIT_SMTP_PORT`, and `MAILPIT_HTTP_PORT` in the shell, then start only `postgres redis mailpit` with `docker compose -p <unique-name> -f infrastructure/docker-compose.yml up -d --wait postgres redis mailpit`. Use a test-only database name and password.
2. Start the packaged backend with `SPRING_PROFILES_ACTIVE=local`, the matching `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, Redis host/port, Mailpit SMTP host/port, `MAIL_FROM`, and the process-only random encryption and rate-limit keys documented in the [backend README](../../backend/README.md). Explicitly set `AUTHENTICATION_ENABLED=true` and `EMAIL_OUTBOX_WORKER_ENABLED=true`. Check `/actuator/health/readiness` reports `UP`; startup must show Flyway validation. Keep public signup and production SMTP disabled.
3. Start Angular with `npm.cmd start -- --host 127.0.0.1 --port 4200`. Obtain `/api/v1/auth/csrf` through the Angular proxy, retaining its cookie. Register a unique test email with that CSRF header and inspect the 201 response. In the isolated database, confirm the user, hash-only token, and encrypted outbox job. Wait for the email in that project's Mailpit inbox.
4. Open the email link in Chromium. Confirm `/verify-email` shows a button and the address bar no longer has `#token`; only then click **Verify email**. Confirm success and that reusing the token fails. Visit `/login`, sign in, reach `/account`, refresh, then sign out. Confirm `/account` redirects to `/login` afterward. The automated test also checks the Redis key and old-cookie rejection.
5. Stop the isolated application processes and services when finished. `docker compose -p <unique-name> -f infrastructure/docker-compose.yml stop` keeps the isolated database volumes for inspection; never use a development project name or delete its volumes for this check.

## Known limitations and remaining work

The Playwright real-service test requires Docker CLI access and the exact local proxy ports 4200/8080. It does not simulate a production HTTPS reverse proxy or a live browser-side Redis outage; Secure-cookie deployment behavior is configuration-verified, while outage behavior is tested in backend integration tests. The normal Playwright suite skips this expensive journey by design. A worker may complete delivery before the test inspects its pending ciphertext; in that case the test accepts only a `SENT` row, and the backend encrypted-outbox tests provide the ciphertext assertion.

Milestone 1D still owns password recovery and remaining account management. Public registration remains disabled pending the duplicate-email account-enumeration and signup-abuse review. Production login rollout still needs trusted-origin/reverse-proxy and operational rate-limit review. Existing sessions are checked against account standing on their next protected request, not proactively broadcast-revoked across all browsers.
