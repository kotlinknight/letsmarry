# Make My Marriage

Make My Marriage is a wedding-planning SaaS for Indian weddings. Couples and trusted organizers will use a shared workspace to plan events, tasks, guests, invitations, budgets, vendors, and related wedding details.

## Current status

**Milestones 0 and 1 are complete for local/test use.** The repository contains the Spring Boot and Angular foundations plus registration, email verification, Redis-session login/logout, current-account restoration, password recovery, encrypted transactional email delivery through Mailpit, and complete real-service account-lifecycle acceptance coverage.

Public signup and password recovery remain disabled outside the guarded local/test configuration. Production email/provider configuration and the wider production-readiness work listed in the [Milestone 1 final acceptance record](docs/implementation/milestone-1-final-acceptance.md) are still required before launch. Wedding creation and other wedding-planning business features have not been implemented; they require separate Milestone 2 approval.

## Repository layout

| Directory | Purpose | Implementation stage |
|---|---|---|
| [backend](backend/README.md) | One Java/Spring Boot application with the implemented account lifecycle and Spring Modulith boundaries | Milestone 1 |
| [frontend](frontend/README.md) | Angular application shell and account UI using the reviewed Figma design system | Milestone 1 |
| [infrastructure](infrastructure/README.md) | Local PostgreSQL, Redis, Mailpit, and the optional complete application profile | 0B / 0D / Milestone 1 local acceptance |
| [docs](docs/05-development-plan.md) | Product baseline, architecture, database/API designs, delivery plan, and decisions | Maintained throughout |
| [.github](.github/README.md) | GitHub Actions CI configuration | 0D |

`backend/`, `frontend/`, and `infrastructure/` contain runnable foundation code and configuration. `.github/` contains the CI workflow; it has been validated locally but cannot run on GitHub until a commit is pushed with permission.

## Architecture

- Angular SPA with standalone components, Signals/RxJS, and custom UI backed by Angular CDK.
- One Spring Boot modular monolith and one PostgreSQL database, with wedding-scoped access enforced by the backend.
- Spring Security and Redis-backed Spring Session for planner authentication, using an HttpOnly cookie and CSRF protection.
- Wedding, couple profiles, membership, and permissions initially share the `wedding` module. Initial wedding creation and creator membership are atomic.
- Flyway owns schema changes; Hibernate validates the schema.
- One PostgreSQL transactional email outbox, with Mailpit locally and a provider-independent sender later.
- Guest invitation credentials are separate from authenticated planner sessions.

## Documentation

1. [Product requirements](docs/01-product-requirements.md)
2. [System architecture](docs/02-system-architecture.md)
3. [Database design](docs/03-database-design.md)
4. [REST API design](docs/04-api-design.md)
5. [Development stages, selected versions, and verification gates](docs/05-development-plan.md)

Recorded decisions:

- [ADR-007: Redis-backed planner sessions](docs/adr/ADR-007-redis-sessions.md)
- [ADR-009: Transactional email outbox](docs/adr/ADR-009-email-outbox.md)
- [ADR-014: Wedding and membership module boundary](docs/adr/ADR-014-wedding-membership.md)
- [ADR-015: Password-recovery token lifecycle](docs/adr/ADR-015-password-recovery-tokens.md)

## Local development

The backend uses Java 25 LTS and the Gradle 9.8.1 Wrapper, with Docker Desktop's Linux engine for services and integration tests. The frontend uses Node 22.23.2, npm 11.21.0, and Angular 22.2.2. Framework and tool compatibility was rechecked against official requirements before each setup stage. The [development plan](docs/05-development-plan.md) records sources, changes, and managed dependency versions.

1. Follow [local service setup](infrastructure/README.md) to copy `.env.example` to an ignored `.env` and start Compose.
2. Follow [backend startup](backend/README.md) to set the host process environment and run the backend.
3. Follow [frontend setup](frontend/README.md), then open [the Angular application](http://127.0.0.1:4200). Requests under `/api` are proxied to the local backend.
4. Check [backend readiness](http://127.0.0.1:8080/actuator/health/readiness) and [Mailpit](http://127.0.0.1:8025).
5. From `backend/`, run `.\gradlew.bat build` to build and execute the foundation integration tests. Docker must be running. On macOS/Linux use `sh ./gradlew build`.

To run the deployment-style local stack, use the `application` Compose profile documented in [infrastructure](infrastructure/README.md). This builds and starts the frontend and backend containers in addition to the development services.

Compose reads its environment file explicitly; it does not configure a host-run Java process. Do not commit `.env`. Keep named volumes when stopping services. An unavailable dependency must be reported, and checks requiring it must be recorded as blocked rather than passed.

## Working agreements

- Use short-lived branches and small, focused changes. Preserve the existing Git repository and history.
- Run the relevant checks and summarize each stage before starting the next approved stage.
- Commit or push only with explicit permission.
- Keep secrets in ignored local environment files or deployment secret stores. Frontend configuration is public.
- Follow [AGENTS.md](AGENTS.md) for repository-specific coding-agent instructions.

The [Figma design system](https://www.figma.com/design/aLnG4EylloYZJOnrCXSpxT) is the visual reference. Its local primitive, semantic Light/Dark, spacing, radius, and typography variables are represented in the Angular token layer.
