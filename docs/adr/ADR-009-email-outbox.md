# ADR-009 — One PostgreSQL transactional email outbox

**Status:** Accepted

**Date:** 2026-10-10

## Context

Verification and invitation emails must survive process failures without requiring a message broker. The baseline offered either a custom outbox or Modulith persistent publications; operating both for the same email would duplicate delivery responsibilities.

## Decision

- Use the documented PostgreSQL `email_outbox` as the sole durable email work source.
- From Milestone 1B onward, registration will create the user, verification-token record, and outbox entry in one PostgreSQL transaction. Other email-producing commands persist delivery intent atomically with their state changes.
- A Spring Boot background worker claims pending work in short transactions, sends outside database transactions, and records the result afterward.
- Support multiple application instances through safe row claiming/locking, claim expiry/recovery, and ownership checks when recording completion.
- Use exponential backoff with a maximum attempt count. Preserve status, attempt count, next attempt, safe failure details, provider message ID, and a stable delivery/deduplication identifier.
- Send through a provider-independent `EmailSender` interface: Mailpit locally and a hosted provider when deployment is configured.
- Treat delivery as at least once. Provider acceptance followed by a worker crash may cause retries; use provider deduplication where available and do not promise exactly-once inbox delivery.
- Token tables store only hashes. Encrypt sensitive outbox token/link material with a key kept outside PostgreSQL; purge it after delivery or expiry. Ordinary JSON payloads, logs, and audit records must not contain raw tokens.
- A later resend cannot recover a token from its hash. Specify fresh issue/rotation and prior-link handling in the invitation contract before implementing it.
- Do not add Kafka, RabbitMQ, Redis queues, or a second Spring Modulith persistent event-publication pipeline for these emails.

## Consequences and verification

The worker, lease/retry fields, encryption-key lifecycle, retention, and provider behavior need explicit implementation tests in the account milestone. Test atomic rollback, restart/retry, concurrent workers, expired claims, bounded failures, and sensitive-data redaction/purge.

Milestone 1A is an explicitly temporary local/test-only registration stage. It creates an unverified `users` row in one transaction but no token or outbox record. `app.registration.enabled` is false by default, and the controller requires the `local` or `test` profile as well. Registration is not a production-ready public signup until Milestone 1B adds atomic token/outbox creation and delivery safeguards. Milestone 1B must decide how to issue verification for any retained 1A accounts without deleting or resetting them. The worker and email sending remain unimplemented in 1A.

Stage 1B-A introduces only the hash-only verification-token schema and internal lifecycle service. Token replacement revokes the previous token; a future resend operation must also cancel pending email jobs where possible. An email already in flight can arrive with a revoked link, which must fail safely and direct the user toward a fresh request. Registration still writes only the user until the approved outbox and registration-integration stages are implemented. No durable email pipeline exists yet.

Stage 1B-C ends the temporary user-only registration exception for new local/test registrations. Identity calls the notification module's public outbox contract in the same PostgreSQL transaction as user and token creation. V4 adds a nullable generic purpose ID to outbox rows, avoiding a foreign key across module tables. On resend, identity revokes the old token and asks notification to mark its `PENDING` job `EXPIRED` with code `SUPERSEDED`, purging ciphertext. Processing mail may still arrive, but its token is unusable. The worker remains disabled until Stage 1B-D provides a sender.

Stage 1B-D adds a Spring Boot managed SMTP adapter for local Mailpit only. The worker remains opt-in and local/test-only. Identity implements a public delivery-eligibility port so the notification worker can reject an expired, revoked, consumed, or ineligible token immediately before SMTP without importing identity persistence. Once SMTP begins, revocation cannot guarantee recall; the verification API still rejects the old link. Redis rate limits are transient abuse controls, never a second durable email queue.

See [architecture](../02-system-architecture.md) and [database design](../03-database-design.md).
