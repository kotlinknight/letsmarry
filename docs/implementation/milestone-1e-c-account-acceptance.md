# Stage 1E-C — Complete account lifecycle acceptance testing

## Purpose and acceptance criteria

Stage 1E-C proves the local/test account lifecycle as one browser-driven system: register in Angular, receive and confirm a real verification message, sign in with a Redis-backed session, restore that session, request and complete password recovery, reject every earlier session and credential, sign in with the replacement password, and sign out. The journey uses the implemented OpenAPI operations, PostgreSQL, Redis, the encrypted PostgreSQL email outbox, Mailpit SMTP/API, and Chromium. It does not mock an application endpoint.

The acceptance boundary remains local/test. Public registration, production SMTP, wedding creation, Milestone 2, and an authentication redesign are outside this stage. No backend contract, migration, dependency, security rule, or feature gate changed.

## Test environment architecture

The final run used the host Angular development server on `127.0.0.1:4200` and its relative `/api` proxy, plus the packaged Spring Boot application on `127.0.0.1:8080`. A dedicated Compose project, `mmm1ec20261017`, supplied PostgreSQL 17.11 on host port 25432, Redis 8.2.9 on 26379, and Mailpit v1.31.4 on SMTP 21025 / HTTP 28025. Its database, Redis keys, Mailpit inbox, network, containers, and named volumes were separate from development services.

The backend ran with the `local` profile and explicit process-only opt-ins for authentication, password recovery, and the outbox worker. Fresh random keys were generated in process for outbox encryption and the three HMAC-keyed limiter domains. The local Mailpit sender used a non-production address. No key was printed, committed, stored in browser configuration, or written to this document. Public signup and production SMTP were not enabled.

Flyway applied V1–V5 to the isolated empty database and Hibernate validated it. Testcontainers in the backend suite continued to use their own independent containers. The development PostgreSQL database, Redis data, and Compose volumes were neither used nor reset.

## Real-service setup and isolation

`frontend/e2e/account-lifecycle-real.spec.ts` is opt-in behind `MMM_REAL_SERVICES=1`. It also requires the exact isolated PostgreSQL and Redis container names, database name/user, and Mailpit base URL. SQL and Redis assertions therefore cannot silently target an unspecified service. Every run creates a UUID-based email address; no personal or fixed account is reused. Mail is selected by that unique recipient.

The test uses bounded Mailpit polling rather than fixed delivery sleeps. The existing recovery limiter test allocates a separate loopback source address through a monotonic key in the isolated Redis instance, so its deliberate 21-request reset-limit check cannot exhaust the browser journeys' source identity. No keyspace flush, table truncation, database reset, or volume deletion is part of setup or reruns.

Token-bearing links are retained only in test-process memory. The lifecycle test disables screenshots, traces, and video for its file so verification and reset credentials are not written to Playwright artifacts. Assertions and errors do not print token values.

## Complete browser journey

The automated lifecycle test performs these phases through the real Angular pages:

1. `/register` submits the visible form, observes the real registration POST, verifies a non-empty CSRF header and exact request fields, receives `201`, displays **Check your email**, and confirms `/users/me` is still `401`.
2. Mailpit delivers the verification email. PostgreSQL confirms the recipient's outbox row is `SENT` with ciphertext purged. Chromium opens the credential link, Angular removes its fragment, and PostgreSQL confirms the token is still unused until **Verify email** is clicked. The POST returns `204`; reuse through the UI returns `400`; `/users/me` remains `401`.
3. `/login` accepts the original password and returns `200`. `/account` displays the expected name and email. The test confirms an HttpOnly SameSite=Lax local cookie, a 30-minute Redis session hash and expiry shadow, and the principal index. Reload observes a real `200 /users/me`. Browser local and session storage remain empty.
4. A separate unauthenticated browser context submits `/forgot-password` while the first context has an active session. The API returns generic `202`; Mailpit delivers the reset message. The reset page removes its fragment and does not consume the token on load. Explicit submission returns `204`, increments `auth_version`, consumes the token, removes the old principal index, and makes the old browser receive `401`. Reuse returns `400`; the old password returns `401`; the new password returns `200`; Mailpit delivers the password-changed notice. All three terminal outbox rows are `SENT` with ciphertext purged.
5. `/account` signs out through the UI and receives `204`. The browser cookie is gone. Spring Session's indexed repository retains the bounded destruction hash with zero inactivity interval, a TTL no greater than five minutes, and no expiry shadow. Replaying the former cookie receives `401`, and protected navigation redirects to sign-in.

This is the first acceptance journey in the repository that begins registration through the Angular UI. The older 1C-D and 1D-D real-service tests intentionally retain their direct API setup because they isolate authentication and recovery behavior.

## Automated negative and security coverage

The full suites retain focused coverage instead of forcing every destructive boundary into one long browser test:

- Registration unit, fixture, and backend integration tests cover missing/invalid fields, email/password/confirmation policy, duplicate email, authoritative `422` errors, the disabled gate, pending-submit suppression, CSRF rejection, rate limits, and uncertain network or server outcomes.
- Verification integration and browser tests cover explicit confirmation, fragment removal, invalid/expired/revoked/reused tokens, generic resend behavior, suspended accounts, concurrency, attempt limits, Redis failure, and no automatic authentication.
- Authentication integration and browser tests cover wrong password, unknown/unverified/suspended accounts with the same response, session rotation and expiry, CSRF, email/IP login limits, unauthenticated route protection, account-state rechecks, Redis failure, and current-session logout.
- Recovery integration and browser tests cover unknown/ineligible generic responses, invalid/expired/revoked/reused tokens, password policy, request/reset limits, concurrent consumption, atomic rollback, old-session revocation, old-password rejection, new-password authentication, and notification delivery.

Backend authorization, CSRF, token validation, feature gates, rate limits, and indexed-session semantics were not weakened for the browser run.

## Accessibility and responsive verification

Automated Angular and Playwright coverage checks associated labels, inline errors, alert/status announcements, password visibility controls, disabled/loading submission states, deliberate focus after invalid input, keyboard-accessible navigation, mobile overflow, persisted Light/Dark themes, and navigation among registration, verification, login, recovery, and account pages. The complete live journey exercises the desktop Light presentation; fixture journeys cover a 390 × 844 mobile viewport and Dark presentation for registration, login, and recovery. Source inspection confirmed the same shared account layout and semantic tokens on verification and account states.

No new usability or accessibility defect was observed during Stage 1E-C. This is automated and source-inspection evidence, not a screen-reader certification or a full WCAG audit. Production HTTPS, browser diversity beyond Chromium, assistive-technology testing, and external device testing remain unverified.

## Commands and results

Run the standard checks from their respective directories:

```powershell
# backend
.\gradlew.bat --offline spotlessCheck build --rerun-tasks

# frontend
npm.cmd run build
npm.cmd test -- --watch=false
npm.cmd run lint
npm.cmd run format:check
npm.cmd run test:e2e -- --workers=1
```

For the opt-in live suite, set `MMM_REAL_SERVICES=1` plus `MMM_REAL_POSTGRES_CONTAINER`, `MMM_REAL_REDIS_CONTAINER`, `MMM_REAL_DB_NAME`, `MMM_REAL_DB_USER`, and `MMM_REAL_MAILPIT_URL` to a dedicated stack, then run the same Playwright command with one worker. Never point those variables at development or shared services.

Final verification on 2026-10-17:

- Backend `spotlessCheck build --rerun-tasks`: **116 tests in 33 suites passed**, with zero failures, errors, or skips. The build includes PostgreSQL/Redis/Mailpit Testcontainers coverage, Flyway V1–V5, Hibernate validation, Spring Modulith verification, security, sessions, transactional outbox, and SMTP behavior.
- Angular production build: passed.
- Angular unit tests: **67 tests in 11 files passed**.
- ESLint and Prettier checks: passed.
- Fixture-only Chromium run: **13 passed, 4 opt-in real-service tests skipped**.
- Combined fixture and real-service Chromium suite: **17 passed** against the isolated stack, then **17 passed again** against the same services without cleanup.
- New complete lifecycle journey alone: passed after test-harness corrections.

The combined live result establishes the complete registration-to-logout journey without mocked backend APIs. PostgreSQL persistence, Redis sessions, CSRF, browser cookies, encrypted outbox delivery, Mailpit retrieval, token single use, global reset revocation, replacement-password login, and logout denial were all asserted automatically.

## Bugs found and fixes

No application integration defect was found. Initial development of the acceptance test exposed three test-harness assumptions: a Playwright response body was unavailable after Angular consumed it, PostgreSQL rendered a concatenated boolean as `true` rather than `t`, and a display-name locator matched both the greeting and definition value. The test now identifies the created account through the isolated database, asserts the actual PostgreSQL representation, and uses an exact text locator. No product code or security behavior changed.

The first backend launch attempt also omitted the documented local `MAIL_FROM` process setting, so startup failed closed before accepting traffic. Supplying the local-only sender address started the same built artifact successfully. No configuration was committed and no production mail path was enabled.

The backend build emitted Spring Session cleanup exceptions during shutdown of some Testcontainers application contexts after Redis had already stopped. The Gradle build and all 116 assertions still passed. This shutdown-only log noise did not occur in the live application process and did not grant access or fail a test; it remains a low-priority test-lifecycle observation rather than an account-lifecycle defect.

## Known limitations and remaining Stage 1E-D work

- The acceptance environment is loopback HTTP with the Angular development proxy. It does not prove deployed HTTPS, Secure-cookie, trusted-proxy/origin, or production reverse-proxy behavior.
- Chromium is the only automated browser. A formal screen-reader/WCAG audit and physical-device coverage were not performed.
- Mailpit proves the local SMTP adapter and encrypted outbox, not a production email provider, deliverability, bounce handling, or provider deduplication.
- Existing duplicate-email behavior and public-registration abuse hardening remain deliberately deferred. Public signup is still disabled outside local/test.
- The hand-written Angular DTOs still mirror OpenAPI; an approved compatible generator has not been introduced.

Stage 1E-D has not been authorized or implemented here. Its exact scope should be approved before work begins; likely release-readiness candidates include deployed HTTPS/proxy acceptance, production email-provider decisions, browser/assistive-technology expansion, and the already-deferred public-signup privacy and abuse review. Wedding creation and Milestone 2 remain separate.
