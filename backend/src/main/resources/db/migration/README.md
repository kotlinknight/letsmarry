# Database migrations

Flyway scans this directory at startup and manages its own schema-history table.
`V1__create_users.sql` is the first business migration. It creates the identity
user table and case-insensitive unique email index for Milestone 1A.
`V2__create_email_verification_tokens.sql` adds hash-only, single-use verification
tokens in Milestone 1B-A without changing existing users or V1.
`V3__create_email_outbox.sql` adds the sole durable, encrypted email-delivery
queue in Stage 1B-B without changing V1 or V2.
`V4__link_outbox_purpose.sql` adds an optional purpose ID and pending-job index
so replacement tokens can cancel and purge obsolete jobs without a module FK.

Add immutable `V<number>__description.sql` files in implementation order. Never
edit a migration that has been shared. Hibernate validates the schema and must
not generate it. Automatic baselining and Flyway clean are disabled.

The integration tests check startup and Hibernate validation on fresh PostgreSQL
instances, with Flyway history, `users`, `email_verification_tokens`, and
`email_outbox` present.
Future schema changes must append migrations and preserve existing data.
