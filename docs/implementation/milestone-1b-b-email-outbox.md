# Milestone 1B-B — Encrypted transactional email outbox

## Purpose and scope

This stage establishes one durable PostgreSQL email pipeline inside the modular monolith. It adds an encrypted `email_outbox`, a provider-neutral sender port, and a worker tested with a fake sender. It does **not** connect registration to the outbox, expose email-verification APIs, add an Angular page, or configure Mailpit SMTP. The scheduled worker and enqueue service are disabled by default. No live email can be marked sent by a placeholder sender.

Stage 1B-A remains uncommitted in the same working tree. Create its own checkpoint before any eventual 1B-B commit; neither stage was committed or pushed as part of this work.

## Module and transaction boundaries

`notification` is a Spring Modulith module with no dependency on `identity`. Its root package exposes `EmailOutbox.enqueue(EmailDraft)` and the provider-neutral `EmailSender.send(EmailDelivery)` port. The application package performs transactional enqueue and worker orchestration. The domain package maps the outbox row so Hibernate validates V3. Infrastructure owns AES-GCM, JPA persistence, SQL claiming, and opt-in scheduling.

`enqueue` requires an existing transaction (`MANDATORY`). When Stage 1B-C calls it inside registration, the user, token, and outbox insert can commit or roll back together. The draft carries a recipient, template code, JSON object payload, and expiry; the payload is validated, encrypted, and never stored as ordinary JSONB. Its diagnostic string representation omits payload content. The outbox `id` is also the stable delivery identifier passed to any provider for deduplication when supported.

The worker's claim method uses a separate, short PostgreSQL transaction. It commits before decrypting or calling `EmailSender`. Success, retry, defer, failure, and expiry updates each use their own transaction and require the matching `id`, `claim_id`, `PROCESSING` status, and unexpired lease. Network delivery never holds a database row lock or the registration transaction.

## Migration and state machine

[`V3__create_email_outbox.sql`](../../backend/src/main/resources/db/migration/V3__create_email_outbox.sql) appends the outbox after immutable V1 and V2. It defines a UUID primary key, recipient/template, ciphertext/nonce/key version, status, attempts, next attempt, claim and lease, job expiry, creation and sent timestamps, optional provider ID, and a safe failure code. Checks enforce paired encryption fields, 12-byte nonce, valid status, nonnegative attempts, claim fields only while processing, a sent timestamp only after success, and ciphertext purge in terminal states. Partial indexes serve pending work, abandoned leases, and expiry cleanup. No wedding FK exists yet.

```text
PENDING --claim--> PROCESSING --accepted--> SENT
                         |--temporary--> PENDING (backoff)
                         |--permanent/max attempts--> FAILED
PENDING or abandoned PROCESSING --expired--> EXPIRED
PROCESSING --lease expiry--> eligible for another claim
```

`SENT`, `FAILED`, and `EXPIRED` retain safe metadata but clear ciphertext, nonce, and key version. Retention/deletion of nonsensitive metadata remains a later operational policy.

## Encryption and key configuration

The JDK's `AES/GCM/NoPadding` uses a 256-bit key, fresh 12-byte `SecureRandom` nonce per message, and 128-bit authentication tag. Associated data binds the outbox UUID, recipient, template code, expiry, and key version. Tampered ciphertext or bound metadata fails authentication and becomes a terminal safe-code failure. PostgreSQL contains ciphertext and key ID, never the key or a plaintext verification token or URL.

`EMAIL_OUTBOX_ACTIVE_KEY_ID` selects the write key; `EMAIL_OUTBOX_KEYS` supplies comma-separated `<id>:<Base64-encoded 32-byte key>` entries from the backend process environment or a future secret manager. No actual key belongs in `.env.example`, logs, Git, or PostgreSQL. Generate keys outside the repository. With `EMAIL_OUTBOX_ENABLED=true`, a missing or malformed active key fails application startup. Keep previous key IDs available for reads during rotation until their encrypted pending jobs are delivered or expired. If an older key is temporarily missing, the worker defers that job for five minutes without consuming an attempt or purging ciphertext; eventual job expiry still purges it.

## Claiming, retries, and recovery

The scheduled worker claims one row per poll with `FOR UPDATE SKIP LOCKED`, writes a fresh claim UUID and two-minute lease, and increments attempts. This keeps a bounded sender call well within the lease; the claim repository supports small batches for later tuning. Cleanup processes up to 100 expired or exhausted rows per poll without waiting on rows another instance locked. A dead worker's lease makes its row eligible for reclamation. A stale worker cannot update a row after its lease expires or a new claim replaces it.

Temporary failures retry after 1, 2, 4, and 8 minutes; a fifth failed attempt becomes `FAILED`. Explicit permanent sender failures and failed AES-GCM authentication become `FAILED` immediately. Expired jobs are never intentionally sent and become `EXPIRED`. Failure codes are fixed safe values; provider exception messages, payloads, keys, and token-bearing links are not logged. The two-minute lease requires every real sender adapter to use network timeouts safely below it. Generic SMTP connection/read/write timeout properties are already set to 5/10/10 seconds; the Mailpit adapter is deferred to Stage 1B-D.

Delivery is **at least once**. If a provider accepts a message and the process dies before recording `SENT`, another worker may resend it after lease expiry. The stable outbox ID is supplied to the sender for a provider deduplication feature when available, but exactly-once inbox delivery is not promised.

## Verification

From `backend/`, with Java 25 and Docker's Linux engine available:

```powershell
.\gradlew.bat spotlessJavaCheck test
.\gradlew.bat build
```

Unit tests cover unique nonces, round-trip decryption, metadata/ciphertext tampering, missing keys, and key-version rotation. PostgreSQL/Redis Testcontainers tests cover Flyway V3 and Hibernate validation, an isolated V2-to-V3 upgrade retaining user/token rows, transaction-required and rollback-safe enqueue, ciphertext-only storage, fake-sender delivery outside a database transaction, retries and five-attempt exhaustion, permanent failure, multiple worker claims, crash/lease recovery, stale-claim rejection, possible duplicate delivery, expiry, sensitive-data purge, and missing-old-key deferral. The existing Spring Modulith verification remains in the full build. No test uses or resets the developer's Compose database.

On 2026-10-13, the locked full backend build and Spotless check passed: 43 JUnit tests, zero failures. The default-off worker check passed in the integration context. No live SMTP or Mailpit delivery was attempted in this stage.

## Limitations and next stages

Registration still creates only an unverified user and remains local/test-only. Stage 1B-C must enqueue atomically with user/token creation and implement verification/resend behavior, including cancellation of pending old-token emails where possible. Stage 1B-D must add the real Mailpit `EmailSender`, enable the scheduler in approved environments, exercise SMTP failures and local end-to-end delivery, and complete abuse protections. The OpenAPI contract remains limited to implemented 1A endpoints until an HTTP API is added.
