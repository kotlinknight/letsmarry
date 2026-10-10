# Database migrations

Flyway scans this directory at startup and manages its own schema-history table.
There are no business migrations in Milestone 0B. A zero-migration validation is
expected; the first real schema change will arrive with the account feature.

Add immutable `V<number>__description.sql` files in implementation order. Never
edit a migration that has been shared. Hibernate validates the schema and must
not generate it. Automatic baselining and Flyway clean are disabled.

The foundation test checks startup and validation on a fresh PostgreSQL instance,
with only Flyway's history table present. Schema evolution tests follow when
actual migrations exist.
