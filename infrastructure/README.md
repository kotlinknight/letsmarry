# Infrastructure

Docker Compose provides local PostgreSQL, Redis, and Mailpit services. Run applications on the host while editing, or enable the `application` profile to build and run the complete container stack.

## Services

| Service | Pinned image | Local port | Persistent data |
|---|---|---|---|
| PostgreSQL | `postgres:17.11` | 5432 | `postgres-data` |
| Redis | `redis:8.2.9` | 6379 | `redis-data`, append-only persistence |
| Mailpit | `axllent/mailpit:v1.31.4` | SMTP 1025, UI 8025 | `mailpit-data` |

All published ports bind to `127.0.0.1`. Every service has a health check. Redis and Mailpit are intentionally unauthenticated local development services; this Compose file is not a production deployment. There is no SMTP relay configured.

## Start and inspect

Install/start Docker Desktop with Linux containers. From the repository root, copy the example **only if `.env` does not already exist**:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infrastructure/docker-compose.yml config --quiet
docker compose --env-file .env -f infrastructure/docker-compose.yml up -d --wait
docker compose --env-file .env -f infrastructure/docker-compose.yml ps
```

Keep `.env` ignored. The example's credentials are local-only; adjust values before starting if needed. Shell environment variables can override Compose's environment-file values. Avoid printing fully rendered configuration when it contains real secrets.

Open [Mailpit](http://127.0.0.1:8025). Service checks:

```powershell
docker compose --env-file .env -f infrastructure/docker-compose.yml exec postgres pg_isready
docker compose --env-file .env -f infrastructure/docker-compose.yml exec redis redis-cli ping
docker compose --env-file .env -f infrastructure/docker-compose.yml exec mailpit /mailpit readyz
```

`up --wait` checks service readiness; backend readiness additionally verifies its database credentials and Redis connectivity. No tables are pre-seeded. Flyway manages its history table when the backend starts, and business migrations arrive later.

If a port is busy, change its value in `.env` and the corresponding backend connection settings. See the [backend startup instructions](../backend/README.md); Compose does not export environment variables into your shell or IDE.

Stop services without deleting data:

```powershell
docker compose --env-file .env -f infrastructure/docker-compose.yml stop
```

Named volumes survive stops and container recreation. Changing `POSTGRES_*` values does not change credentials in an already initialized PostgreSQL volume. Do not delete volumes or reset a database to resolve a startup failure without explicit approval.

## Application containers and deployment boundary

The optional `application` profile adds the backend and frontend images. The backend waits for PostgreSQL, Redis, and Mailpit health; the frontend waits for backend readiness and proxies `/api` requests to it. Both application ports bind only to loopback and both images run as non-root users. For local verification email in containers, set `BACKEND_SPRING_PROFILES_ACTIVE=local`, both private keys, and `EMAIL_OUTBOX_WORKER_ENABLED=true` in the ignored `.env`; the backend then uses `mailpit:1025` internally and generates browser links to `http://127.0.0.1:4200/verify-email`. The default container profile does not expose registration or send mail.

Build, start, and check the complete local stack from the repository root:

```powershell
docker compose --env-file .env -f infrastructure/docker-compose.yml --profile application config --quiet
docker compose --env-file .env -f infrastructure/docker-compose.yml --profile application up -d --build --wait
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
Invoke-WebRequest http://127.0.0.1:4200/design-tokens
```

The default container ports are backend `8080` and frontend `4200`; override `BACKEND_CONTAINER_PORT` or `FRONTEND_CONTAINER_PORT` in the ignored `.env` when needed. Stop every service without deleting named data:

```powershell
docker compose --env-file .env -f infrastructure/docker-compose.yml --profile application stop
```

Hosting, managed services, deployment secrets, and infrastructure as code are later decisions. Local development credentials/configuration are not production configuration. Do not delete volumes as a routine setup or repair step.

See the [development plan](../docs/05-development-plan.md) for stage boundaries and version verification.
