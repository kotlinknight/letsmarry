# Stage 1B-C — Registration and email verification

## Scope

This stage joins Milestone 1A registration, 1B-A hash-only tokens, and 1B-B encrypted email work. It adds two local/test-only APIs and a minimal Angular confirmation page. No SMTP sender, login, wedding feature, or production signup is enabled.

## Registration transaction

`RegistrationService` normalizes and validates the request, hashes the password, and inserts an `ACTIVE` but unverified user. In the same PostgreSQL transaction, `VerificationTokenService` creates a 32-byte random secret, stores only its SHA-256 digest with 24-hour expiry, and `EmailVerificationService` uses the notification module's public `EmailOutbox` contract to persist an AES-GCM encrypted `VERIFY_EMAIL` payload. The payload contains a frontend URL with the token in its `#token=` fragment. The outbox requires the caller's transaction. Any token, key, encryption, or outbox persistence failure rolls back the user and token inserts. Nothing sends during that transaction.

The controller requires the `local` or `test` profile and both registration/outbox flags. Base defaults are false; local enables both and requires a private key at startup. The worker remains disabled, so jobs remain pending until 1B-D installs a sender.

## Verification and resend

`POST /api/v1/auth/email/verify` consumes `{ "token": "..." }`. The service hashes the presented secret, obtains the user lock before the token lock, checks single-use, expiry, revocation, and `ACTIVE` unverified status, then atomically sets `users.email_verified_at` and consumes the token. Status remains `ACTIVE`; later login must require both verified email and acceptable account status. Success is empty `204`. Invalid, expired, revoked, reused, and ineligible links receive the same `400 VERIFICATION_TOKEN_INVALID` Problem Details response. Verification does not authenticate or create membership.

`POST /api/v1/auth/email/verification-requests` normalizes `{ "email": "..." }` and returns empty `202` for all syntactically valid addresses, regardless of account existence or eligibility. An older 1A account with no token receives its first one without a reset. For eligible accounts with a token, the user lock serializes resend against verification. The old token is revoked, its `PENDING` outbox job is marked `EXPIRED` with safe code `SUPERSEDED` and its ciphertext purged, then a replacement token/job is inserted in the same transaction. An already processing email cannot be recalled; its old link remains invalid. Verified and suspended accounts produce no new job. Repeated requests rotate again; cooldown and full abuse controls remain deferred.

All three POST endpoints use the anonymous Redis `SESSION` cookie and session-bound CSRF header from `GET /api/v1/auth/csrf`. API responses carry server-generated correlation IDs. No plaintext token appears in API responses, token table columns, or application logs. The token exists temporarily in process memory and encrypted outbox data until delivery or terminal purge.

## Schema and modules

Existing V1–V3 migrations are unchanged. V4 adds nullable `email_outbox.purpose_id` and a partial index for pending-job cancellation. No cross-module foreign key is introduced. `identity` calls only the root public `notification.EmailOutbox` contract; notification does not access identity persistence. Spring Modulith verification checks this boundary.

## Angular confirmation

The lazy `/verify-email` route reads `#token=` from the URL fragment, promptly removes it from browser history, retains it only in component memory, and waits for an explicit “Verify email” click. Both verify and resend first fetch a CSRF token and send the cookie and required header. The page uses existing semantic Light/Dark tokens, responsive layout, labels, focus outlines, accessible errors, and generic resend feedback. It does not include registration or login UI. The current frontend API types are small local interfaces; generated OpenAPI typing remains a follow-up before the API surface grows.

## Verification and limits

Backend integration tests use isolated PostgreSQL/Redis Testcontainers and a private per-test-process AES key. They decrypt only the test outbox to exercise the API; production endpoints never reveal raw tokens. Coverage includes atomic registration, encrypted/hash-only storage, CSRF, replay, expiry, revocation, resend rotation, older accounts, suspended/verified accounts, and rollback on outbox failure. V4 upgrade testing preserves existing pending jobs. Angular tests cover fragment removal, explicit action, CSRF POST, and resend. Full results are reported in the stage handoff.

The final locked backend build and Spotless check passed with 54 JUnit tests and zero failures, including Spring Modulith verification, Flyway validation, Hibernate schema validation, and isolated PostgreSQL/Redis integration checks. Angular build, lint, formatting, and 9 unit tests passed; all 4 Chromium Playwright journeys passed. OpenAPI 3.0.3 parsed with four paths and all 31 local references resolved. Changed Markdown links and `git diff --check` passed. No developer database was reset or changed by these tests.

The worker and real email sender are deferred to 1B-D. Public signup still needs rate limits/cooldowns, duplicate-email enumeration review, provider delivery, and operational key management. The Compose `application` backend runs without the local/test gate by default, so its verification APIs remain absent. Existing 1B-A/B work remains uncommitted; this stage does not commit or push it.
