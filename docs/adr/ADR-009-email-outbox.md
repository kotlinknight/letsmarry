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

See [architecture](../02-system-architecture.md) and [database design](../03-database-design.md).
