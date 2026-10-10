# Repository instructions

## Scope and approvals

- Work only on the stage or feature explicitly authorized by the user. Authorization of an architecture or roadmap does not authorize every milestone.
- Milestone 0 is split into 0A (structure/docs), 0B (backend/services), 0C (frontend), and 0D (tests/CI/images). See `docs/05-development-plan.md`.
- Stop after the authorized stage, summarize changes and checks, and wait for approval before the next stage. Do not commit, push, deploy, or rewrite Git history without explicit permission.
- Preserve unrelated work and the four baseline documents. Update agreed inconsistencies in place; discuss important architectural conflicts before resolving them.
- Do not implement registration, login, wedding creation, or other business features during Milestone 0.

## Read the relevant baseline

- Product behavior: `docs/01-product-requirements.md`.
- Module boundaries and infrastructure: `docs/02-system-architecture.md` and relevant records under `docs/adr/`.
- Persistence: `docs/03-database-design.md`.
- HTTP contracts: `docs/04-api-design.md`.
- Current stage, version selections, and verification gates: `docs/05-development-plan.md`.

## Backend rules

- Use one Spring Boot deployment and one Gradle project. Add business packages only when implementing their first feature.
- Keep identity independent of wedding-specific roles. Wedding creation, couple profiles, membership, and permissions initially belong inside the `wedding` module with separated internal packages.
- Create a wedding and its initial owner/admin membership synchronously in one PostgreSQL transaction. Never use asynchronous events for initial membership creation.
- Communicate across modules through small public contracts. Do not access another module's repositories or JPA internals. Enforce boundaries with Spring Modulith tests when modules exist.
- Planner authentication uses Spring Session with Redis, an HttpOnly cookie, and CSRF protection. Do not reintroduce planner access/refresh JWTs. Guest invitation credentials are a separate security scope.
- Enforce membership, permissions, resource visibility, and same-wedding associations on the backend. Do not expose JPA entities as response DTOs.
- Use Flyway SQL migrations and Hibernate `ddl-auto=validate`. Shared migrations are immutable. Add migrations with their feature; do not precreate the whole database catalog.
- Persist email work in one PostgreSQL outbox, atomically with its domain change. Send outside the transaction through a provider-independent adapter. No second durable email pipeline.
- Keep token tables hash-only. Sensitive email payloads may be encrypted temporarily, with the key outside PostgreSQL, and must be purged after delivery or expiry. Never log credentials or token-bearing URLs.

## Frontend rules

- Use one Angular application, standalone components, feature routes, Signals/RxJS, and Reactive Forms.
- Reuse the existing Figma semantic tokens and custom UI components; use CDK where interaction behavior requires it. Support Light/Dark themes, keyboard access, accessible errors, and responsive layouts.
- Keep business state in feature services/stores. Keep session and API infrastructure in core code and generic presentation in shared UI.
- Use the OpenAPI contract for API typing. Keep generated clients isolated and do not hand-edit generated output.
- Route guards and hidden UI are usability features, not authorization enforcement. Browser configuration must not contain secrets.

## Verification and tooling

- Verify selected versions against official compatibility requirements before installation or project generation. Pin versions and use the committed wrappers/lockfiles once introduced.
- At 0A, there is no application build or test command. Check documentation links, scope, and `git diff --check`. Add real commands to the relevant README when each application is created.
- Later, run relevant backend tests, Modulith verification, migration/security integration checks, frontend checks, and browser journeys according to the changed behavior.
- If Docker or another required dependency is unavailable, report the blocker. Continue only with work that can be verified safely and distinguish completed checks from blocked checks.
- Never reset databases, remove Docker volumes, or disable security/tests to make a check pass without explicit authorization for that action.
- Report significant changes, checks performed, limitations, and remaining questions at each handoff.
