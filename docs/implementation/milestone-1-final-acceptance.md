# Milestone 1 final acceptance — account lifecycle

**Acceptance scope:** local and test environments only  
**Reviewed:** 2026-10-17  
**Production status:** not approved or configured for deployment

## 1. Milestone 1 objectives

Milestone 1 establishes a complete planner-account lifecycle before any wedding workspace is created. A person can register, verify the address, authenticate with a server-side session, restore the current account after a reload, log out, recover a forgotten password, and have every older authenticated session rejected after the reset. Account identity remains independent of wedding roles and membership.

This acceptance closes the account milestone for local/test development. It does not enable public signup, production password recovery, a production email provider, or any Milestone 2 wedding behavior.

## 2. Features completed

- Local/test-only registration with normalized input and versioned password hashing.
- Email-verification token issuance, resend, replacement, expiry, one-time consumption, and Mailpit delivery.
- Gated JSON login with generic credential failures and session fixation protection.
- Redis-backed Spring Session, `/users/me` restoration, account-eligibility revalidation, and current-session logout.
- Forgot/reset password flows, password-changed notification, and durable global old-session revocation.
- Angular registration, verification, login, account, forgot-password, and reset-password experiences.
- Responsive keyboard-usable Light/Dark presentation using the established semantic tokens.
- Backend, frontend, controlled-browser, and full real-service lifecycle coverage.

## 3. Account architecture

`identity` owns accounts, authentication, verification/reset tokens, and account eligibility without knowing wedding roles. `notification` owns the provider-neutral encrypted PostgreSQL outbox, delivery eligibility contract, worker, and local SMTP adapter. `platform` provides shared security, current-account contracts, CSRF bootstrap, correlation IDs, and Problem Details handling.

The Angular app keeps account state in memory and communicates through relative same-origin `/api/v1` requests with browser-managed cookies. It stores no session credential, password, verification token, or reset token in browser storage. Registration creates no wedding. Future modules must consume small public account contracts instead of identity repositories or JPA internals; Spring Modulith verification protects the current module boundaries.

## 4. Backend API summary

| Method and path | Implemented behavior |
|---|---|
| `GET /api/v1/auth/csrf` | Creates/uses an anonymous session and returns the session-bound CSRF header name and token with no-store caching |
| `POST /api/v1/auth/register` | Local/test-only atomic user, verification token, and encrypted outbox creation; does not authenticate or create a wedding |
| `POST /api/v1/auth/email/verify` | Consumes one eligible verification token and marks the account email verified |
| `POST /api/v1/auth/email/verification-requests` | Returns generic `202`; eligible accounts receive a replacement verification link |
| `POST /api/v1/auth/login` | Independently gated login for a verified `ACTIVE` account; rotates the session ID and returns a safe user DTO |
| `GET /api/v1/users/me` | Returns fresh safe account data for an eligible authenticated session |
| `POST /api/v1/auth/logout` | CSRF-protected invalidation of the current session and expiry of its cookie |
| `POST /api/v1/auth/password/forgot` | Local/test-only generic recovery request; eligible accounts receive a reset link |
| `POST /api/v1/auth/password/reset` | Local/test-only single-use reset, `auth_version` increment, and password-changed mail enqueue; does not log in |

The versioned OpenAPI 3.0.3 contract in `docs/api/openapi.yaml` defines these account operations, request/response DTOs, Problem Details variants, and security expectations. Angular DTOs are still manually mirrored because no generator has been adopted.

## 5. Frontend screens and routes

| Route | Purpose |
|---|---|
| `/register` | Validated account creation and check-your-email result |
| `/verify-email#token=...` | Explicit verification confirmation; fragment removed immediately |
| `/login` | Email/password login and recovery/registration navigation |
| `/account` | Guarded temporary current-account view and logout |
| `/forgot-password` | Generic reset-link request |
| `/reset-password#token=...` | Explicit reset; fragment removed immediately and secret retained only in memory |

The account routes use standalone components, Reactive Forms, semantic Light/Dark tokens, accessible labels and errors, keyboard focus, and responsive layouts. `/account` is a temporary account destination, not a wedding dashboard.

## 6. Database migrations V1–V5

- `V1__create_users.sql`: account identity, normalized unique email, password hash, status, and verification timestamp.
- `V2__create_email_verification_tokens.sql`: hash-only, expiring, revocable, single-use verification tokens.
- `V3__create_email_outbox.sql`: the sole durable encrypted email queue, worker claims, retries, leases, and terminal state.
- `V4__link_outbox_purpose.sql`: purpose identity and pending-job lookup for replacement/cancellation.
- `V5__create_password_reset_tokens.sql`: separate hash-only reset-token lifecycle and nonnegative `users.auth_version`.

The migrations are immutable. Flyway owns schema evolution; Hibernate uses `ddl-auto=validate`. Upgrade tests preserve earlier rows and no developer database reset is part of acceptance.

## 7. Authentication and Redis sessions

Planner authentication uses Spring Session's indexed Redis repository and an HttpOnly `SESSION` cookie. The adopted inactivity timeout is 30 minutes. The base cookie is `Secure`, HttpOnly, and SameSite=Lax; only the loopback `local` profile disables `Secure` for HTTP development. URL session tracking and generated login pages are disabled.

Login changes the session ID and renews CSRF state. The principal contains only the stable account ID and captured `auth_version`. Every protected request reloads the account from PostgreSQL and rejects a deleted, unverified, suspended, deactivated, or version-mismatched account. Redis/database failure denies access rather than retaining stale authorization.

## 8. Transactional email delivery

Registration, eligible resend/recovery requests, password reset, and their email intent use one PostgreSQL transactional outbox. Domain change and enqueue commit together; SMTP runs after commit through a provider-independent adapter. The local/test worker sends to Mailpit with leases and bounded retries.

Outbox JSON payloads are encrypted using AES-256-GCM with a random nonce and contextual authenticated data. `EMAIL_OUTBOX_ACTIVE_KEY_ID` selects the write key and `EMAIL_OUTBOX_KEYS` provides private 32-byte keys outside Git and PostgreSQL. Older key IDs must remain available while pending jobs need them. Terminal ciphertext is purged. Enabling the worker also requires explicit SMTP settings and `MAIL_FROM`; `hello@makemymarriage.local` is only a local example.

## 9. Password recovery and session revocation

Forgot-password responses are deliberately generic for valid email syntax. Only verified `ACTIVE` accounts receive a 30-minute reset token and encrypted outbox job. Replacement revokes the earlier token and cancels its pending email where possible. Reset tokens are random, hash-only at rest, expiring, and single use.

A successful reset atomically changes the password hash, consumes the token, increments `users.auth_version`, cancels obsolete pending reset mail, and enqueues a token-free password-changed notification. Indexed Redis deletion is a best-effort cleanup after commit; PostgreSQL version comparison is authoritative, so any surviving old session is rejected on its next protected request.

## 10. Unit and integration test evidence

The Stage 1E-D verification on 2026-10-17 completed the locked offline Gradle build and Spotless check with all 116 JUnit tests passing. The suite includes Spring Modulith verification, PostgreSQL/Redis Testcontainers integration, Flyway upgrade/history and Hibernate validation, CSRF/session behavior, rate limits, token replay/expiry, outbox encryption/claims/retries, eligibility changes, and password-reset revocation.

The Angular production build passed. All 67 Vitest tests in 11 files passed, and ESLint plus Prettier reported no errors. These tests cover routing, validation, CSRF request construction, safe feedback, session restoration/logout uncertainty, fragment handling, and account-state clearing.

## 11. Real-service Playwright acceptance results

The normal Chromium suite passed 13 controlled-fixture journeys and correctly skipped four opt-in real-service journeys. The combined opt-in suite passed all 17 journeys against real Angular, Spring Boot, PostgreSQL, Redis, and Mailpit services.

The complete journey starts in the real `/register` form and proves registration persistence, verification delivery and consumption, login, reload restoration, recovery from another browser context, global rejection of the older session, rejection of the old password, acceptance of the new password, password-changed mail, new-session logout, former-cookie rejection, and protected-route redirection. No backend account endpoint is mocked. Screenshot, trace, and video capture are disabled for the credential-bearing lifecycle test.

## 12. Manual local testing instructions

1. Copy `.env.example` to ignored `.env` and start PostgreSQL, Redis, and Mailpit as described in `infrastructure/README.md`. Do not remove existing volumes.
2. In the backend process environment, use the matching database/Redis values, `SPRING_PROFILES_ACTIVE=local`, private independent encryption/rate-limit keys, `AUTHENTICATION_ENABLED=true`, `PASSWORD_RECOVERY_ENABLED=true`, `EMAIL_OUTBOX_WORKER_ENABLED=true`, `SPRING_MAIL_HOST=127.0.0.1`, `SPRING_MAIL_PORT=1025`, and a local `MAIL_FROM` address. Never commit or print real keys.
3. Start the backend from `backend/` and confirm readiness at `http://127.0.0.1:8080/actuator/health/readiness`.
4. Start Angular from `frontend/`, open `http://127.0.0.1:4200/register`, register a unique account, and open the verification message in Mailpit at `http://127.0.0.1:8025`.
5. Verify, sign in, reload `/account`, request password recovery, open the newest Mailpit reset link, reset the password, and confirm the older signed-in browser is rejected. Sign in with the new password and log out.
6. Keep verification/reset fragments out of shell history, logs, screenshots, and bug reports. Stop services without deleting their named volumes.

## 13. Security protections

- Passwords are NFC-normalized without trimming, restricted to 15–128 Unicode code points, reject control characters, and use Spring Security's versioned PBKDF2 encoder.
- Unsafe account operations require a session-bound CSRF token; login/logout renew stale CSRF state rather than replaying rejected writes.
- Verification/reset secrets use 32 random bytes and SHA-256 hash-only persistence; APIs never return raw secrets.
- Session fixation protection, HttpOnly/SameSite cookies, expiration, eligibility revalidation, and durable version revocation are active.
- Login, resend, verification, forgot, and reset paths fail closed through HMAC-keyed Redis limits using independent secret keys.
- Generic login, verification, recovery, and protected-session failures reduce account/state disclosure; errors use stable Problem Details and correlation IDs.
- Email work is encrypted at rest, committed atomically, sent outside the domain transaction, and purged after terminal delivery/expiry.
- Token fragments are stripped from browser history and remain only in component memory; the frontend uses a no-referrer policy.
- Identity does not contain wedding-role knowledge, notification has no inward module dependency, and Modulith tests enforce the implemented boundaries.
- Application logs were reviewed for explicit logging of tokens, passwords, credentials, or authorization values; no such statement was found.

## 14. Known limitations

- Registration, verification, delivery, and recovery are deliberately local/test-only; authentication is separately opt-in.
- Duplicate registration returns `409`, which is useful locally but can disclose account existence if exposed publicly.
- Mailpit proves local SMTP handling, not real-provider delivery, reputation, bounce processing, or inbox placement.
- SMTP is at-least-once around crashes; a duplicate or newly invalid link can arrive in narrow race windows.
- Angular API types are handwritten mirrors of OpenAPI rather than generated clients.
- Browser automation is Chromium-focused and is not a complete assistive-technology or cross-browser certification.
- The temporary `/account` view is not wedding onboarding, selection, or a dashboard.

## 15. Production-readiness gaps

Before production, decide and test public-safe duplicate-email semantics and registration-specific abuse controls. Select and configure a production SMTP provider and verified sender domain, secret manager, key-rotation/recovery runbook, monitoring/alerting, bounce/complaint policy, and operational retention controls.

Deploy behind HTTPS with an explicitly trusted reverse proxy, trusted origins/hosts, correct forwarded-header handling, Secure cookie/domain/SameSite policy, HSTS and content-security review. Complete broader browser, mobile, keyboard, screen-reader, and accessibility verification. Add production observability for auth/rate-limit/outbox anomalies without logging secrets, plus backup/restore, incident response, dependency scanning, capacity testing, and a staged rollback procedure. Reassess password-hashing cost against production hardware and policy before launch.

## 16. Remaining follow-up work

- Make the production account rollout/security decisions above; do not merely enable the local flags.
- Introduce generated API clients only through an approved toolchain change.
- Run GitHub Actions after an authorized push; this local acceptance does not claim a remote CI result.
- Keep each later business feature responsible for its migrations, OpenAPI expansion, permissions, audit, and cross-tenant tests.
- Preserve historical stage notes as point-in-time records; use this document and the current READMEs for present status.

## 17. Handoff requirements for Milestone 2

The account system is ready to provide an authenticated, verified, `ACTIVE` current account to a separately approved wedding module. It does not automatically create a wedding. Milestone 2 must add the wedding module only with its first feature and preserve identity independence.

Before implementation, confirm the exact `POST /api/v1/weddings` request/response in OpenAPI, authenticated `GET /api/v1/weddings` selection contract, empty-dashboard contract, validation/timezone rules, and error semantics. Use the existing `CurrentAccount`/planner principal contract rather than identity persistence. Add immutable wedding, couple-profile, and membership migrations with wedding-scoped keys and constraints.

Wedding creation must synchronously commit the wedding, two couple profiles, and initial membership in one PostgreSQL transaction. A verified BRIDE/GROOM creator becomes the matching owner; a FAMILY_MEMBER/FRIEND creator becomes setup `ADMIN`, never an implicit owner. The implementation needs explicit default setup-admin permissions and a decision about post-owner setup-admin privileges. Owner invitation/claim, transfer/recovery, and suspended-owner-slot semantics remain later decisions unless separately included and approved.

Acceptance must cover creator-role mapping, transaction rollback, multiple-wedding selection, membership/resource tenant isolation, no cross-wedding disclosure, DTO-only APIs, Modulith boundaries, responsive onboarding, and landing on a permission-safe empty dashboard. No Milestone 2 code, migration, route, or API was added during this closure stage.
