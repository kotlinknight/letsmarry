# Stage 1B-D — Mailpit delivery and verification safeguards

## Scope and delivery flow

This stage adds local/test-only SMTP delivery to the existing account-verification workflow. A registration transaction writes the unverified `ACTIVE` user, a SHA-256 token hash, and an AES-GCM encrypted `VERIFY_EMAIL` outbox job. It never calls SMTP. The opt-in worker claims a due job with PostgreSQL `FOR UPDATE SKIP LOCKED`, commits that claim, checks the linked token and account, decrypts the payload, checks eligibility again, and calls the notification module's `EmailSender` port outside a database transaction. `SmtpEmailSender` implements the port using Spring Boot's managed `JavaMailSender`; Mailpit captures local mail. The worker then records the result only if it still owns the lease. No second durable email pipeline or new migration was added.

The message is multipart HTML and plain text. It uses the Make My Marriage name, a verification button/link to the configured Angular `/verify-email#token=...` route, a 24-hour expiry note, and an instruction to ignore unsolicited mail. Angular strips the fragment from browser history immediately and waits for an explicit click before calling the CSRF-protected API. Successful verification sets `users.email_verified_at` and consumes the token in one transaction while leaving account status `ACTIVE`; it does not authenticate the user.

The worker checks that the purpose ID still identifies an unexpired, unrevoked, unused token for an `ACTIVE` unverified account. A definitely invalid link is expired and its encrypted payload purged before SMTP. A resend revokes the previous token and cancels its pending email where possible. If SMTP has already begun, the old email may still arrive, but its link cannot verify. This is at-least-once delivery: acceptance followed by a worker crash can cause a duplicate message. The stable `X-Delivery-ID` header supports investigation; Mailpit does not deduplicate it.

## Configuration

The base profile disables registration, outbox writes, rate limiting, and the worker. The `local` profile enables registration/outbox/rate limiting; the worker still needs `EMAIL_OUTBOX_WORKER_ENABLED=true`. The adapter and scheduler are restricted to `local` or `test` and excluded from `production`. The local backend binds to loopback and uses an HTTP development cookie. No public signup or production SMTP is enabled.

Set these variables in the **backend process** environment, not merely in Compose's `.env` file:

| Variable | Host-run local value/purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Existing local PostgreSQL connection |
| `REDIS_HOST`, `REDIS_PORT` | Existing local Redis connection |
| `EMAIL_OUTBOX_ACTIVE_KEY_ID` | Active version, for example `v1` |
| `EMAIL_OUTBOX_KEYS` | `v1:` followed by Base64 of 32 private random bytes; keep older versions while jobs need them |
| `VERIFICATION_RATE_LIMIT_KEY` | Base64 of another private random key of at least 32 bytes; HMACs rate-limit key names |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `127.0.0.1`, `1025` for host-run Java; `mailpit`, `1025` in Compose |
| `MAIL_FROM` | A valid development sender, for example `hello@makemymarriage.local` |
| `EMAIL_OUTBOX_WORKER_ENABLED` | `true` only when local services and keys are ready |
| `VERIFICATION_FRONTEND_URL` | Optional browser URL, default `http://127.0.0.1:4200/verify-email` |

Generate each key once with a cryptographic random generator, Base64-encode it, and put it in a private environment/IDE secret setting. Do not print, commit, or regenerate the AES key while encrypted pending jobs exist. The application rejects incomplete key, sender, or rate-limit configuration at startup without printing values. SMTP connect/read/write timeouts are 5/10/10 seconds, below the two-minute claim lease. Worker polling defaults to every five seconds. `VERIFICATION_RESEND_IP_HOURLY` and `VERIFICATION_VERIFY_IP_HOURLY` override the defaults of 30 and 20. The application ignores forwarded IP headers for these local limits; a future trusted-proxy deployment needs an explicit proxy trust design.

## Retry, cleanup, and abuse controls

Temporary SMTP failures schedule retries at 1, 2, 4, then 8 minutes. The fifth failed attempt is terminal. Expired jobs, unusable token purposes, invalid payloads, and exhausted attempts end with ciphertext/nonce/key-version purged. An unavailable decryption key defers without consuming an attempt so key restoration can recover the job. Claim leases and ownership checks support worker restarts and multiple workers. Only safe failure codes and provider message IDs are retained; raw links are not logged or stored as plaintext database fields.

The Redis limiter atomically checks limits with Lua scripts. Every syntactically valid resend request counts against the direct-IP hourly ceiling. Each normalized email may receive one resend per five minutes and three per rolling 24 hours. Email-specific denials return the same empty `202` as an unknown, verified, or ineligible account. IP-wide excess returns `429 VERIFICATION_RATE_LIMITED`. Verification attempts are limited to 20 per direct IP per hour by default. An unavailable Redis limiter fails closed with `503 VERIFICATION_TEMPORARILY_UNAVAILABLE` before token issuance/consumption. Redis keys use HMAC-SHA-256 digests of addresses and IPs, never plaintext email addresses. These transient limits do not replace CSRF or the existing production-signup review.

## Local exercise and evidence

Start PostgreSQL, Redis, and Mailpit as described in [infrastructure](../../infrastructure/README.md), then configure the host backend using [backend instructions](../../backend/README.md) and run Angular. Request `/api/v1/auth/csrf`, register with the returned `SESSION` cookie and CSRF header, wait for the worker, then open [Mailpit](http://127.0.0.1:8025). Follow the link, click **Confirm email address**, and confirm a reused link fails. The same CSRF session can request a fresh link, subject to cooldown. Do not delete the existing Compose database to test this.

Automated Testcontainers use isolated PostgreSQL, Redis, and Mailpit containers. `MailpitJourneyIntegrationTests` exercises real SMTP delivery, the email text/link, encrypted outbox cleanup, CSRF rejection, single-use verification, and no automatic login; it also rejects a revoked purpose before SMTP and tests generic `202` cooldown responses. `VerificationRateLimiterIntegrationTests` exercises concurrent resends, HMAC-only keys, IP/attempt ceilings, the daily resend cap, and fail-closed behavior when Redis calls fail. `SmtpEmailSenderTests` covers temporary failure classification, a real stalled-SMTP read timeout, and missing/malformed configuration. Existing outbox tests cover rollback, retry scheduling, max attempts, stale leases, concurrent claims, key failures, expiry, and at-least-once behavior. Existing verification API tests cover legacy 1A accounts, invalid/reused tokens, account status, and feature gates. The Angular unit and browser tests cover fragment removal and explicit confirmation.

Verification on 2026-10-16: the locked backend `build` and `spotlessCheck` passed with 64 JUnit tests, including actual SMTP delivery into an isolated Mailpit container. Flyway V1–V4, Hibernate schema validation, and Spring Modulith verification passed in that suite. Angular build, nine unit tests, lint, Prettier check, and four Chromium Playwright journeys passed. The Compose configuration parsed and the backend container image built. OpenAPI 3.0.3 parsed with 39 local references resolved, updated Markdown links resolved, and `git diff --check` passed. No existing Compose database or volume was reset. A manual human click-through in the Mailpit UI was not performed; the automated Mailpit API and Angular browser journey cover those halves separately.

## Remaining limits

Mailpit captures mail locally; it does not prove delivery to a real inbox. SMTP acceptance can be followed by a worker crash, producing duplicates. A token can be revoked between the final eligibility check and SMTP acceptance, so an unusable link can still arrive in that narrow race. The registration endpoint's duplicate-email `409` still needs an account-enumeration review before public signup. Production mail-provider configuration, trusted-proxy IP handling, login/logout, password reset, and wedding features remain outside this stage.
