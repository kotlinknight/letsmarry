# Database migrations

Flyway scans this directory at startup and manages its own schema-history table.
`V1__create_users.sql` is the first business migration. It creates the identity
user table and case-insensitive unique email index for Milestone 1A.

Add immutable `V<number>__description.sql` files in implementation order. Never
edit a migration that has been shared. Hibernate validates the schema and must
not generate it. Automatic baselining and Flyway clean are disabled.

The integration tests check startup and validation on fresh PostgreSQL instances,
with the Flyway history and `users` tables present. Future schema changes must
append migrations and preserve existing data.
