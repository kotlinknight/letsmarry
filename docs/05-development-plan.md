# Make My Marriage — Development Plan

**Status:** Milestone 0 and 1A complete; Stages 1B-A/B/C implemented locally, uncommitted and pending review

**Last updated:** 2026-10-13

## 1. Delivery boundaries

Implement one approved stage at a time. Run its relevant checks, explain significant changes and limitations, then stop for approval before the next stage. Approval of the overall architecture does not authorize later implementation stages, commits, or pushes.

Milestone 0 prepares a runnable foundation. Registration, login, wedding creation, business tables, and email delivery workflows belong to later milestones.

| Stage | Deliverables | Verification gate |
|---|---|---|
| **0A — Repository and documentation** | Directory guidance, README, AGENTS.md, repository hygiene, focused baseline corrections, and ADRs | Check local Markdown links, diff whitespace, documentation consistency, scope, and unchanged Git history |
| **0B — Backend and services** | Spring Boot/Gradle, PostgreSQL, Redis, Flyway, session/security configuration, health support, Compose services including Mailpit, local environment example | Verify official compatibility before generation; build/start backend; check service health, protected routes, Redis connectivity, and Flyway initialization/validation when Docker is available |
| **0C — Angular foundation** | Angular app, routing, reviewed Figma tokens, Light/Dark responsive shell, required UI primitives, development API proxy | Verify official compatibility before generation; build/type-check and run available tests; inspect routing, themes, keyboard behavior, and mobile/desktop layouts |
| **0D — Tests, CI, and containers** | Complete automated checks, GitHub Actions, Dockerfiles, reverse proxy, optional application-container Compose profile, final setup guide | Run backend/frontend suites, integration and browser smoke checks, image builds, and fresh-checkout setup verification; distinguish local workflow checks from actual GitHub runs |

Use lightweight checks as each stage is built. The 0D testing stage completes the suite and automation; it does not defer all validation until the end.

At 0A, no application commands were available. Stage 0B adds backend and Compose commands to their directory READMEs. Source/configuration directories are introduced only when they contain real work. The existing Git repository must not be reinitialized.

## 2. Approved architecture

- One Angular CLI workspace and one Spring Boot Gradle project; no monorepo orchestration framework initially.
- PostgreSQL is the durable business store. Redis is required for planner sessions and later rate limiting.
- Spring Session with Redis replaces the provisional planner JWT/refresh-token design. Use an HttpOnly session cookie, CSRF protection, and a same-origin frontend/API topology.
- Keep identity independent of wedding-specific roles. Initially place wedding creation, couple profiles, membership, and permissions inside the `wedding` Modulith module with separated internal packages.
- Commit wedding creation and initial creator membership synchronously in one PostgreSQL transaction. A verified bride/groom creator self-declares the corresponding owner role; a family/friend creator becomes setup ADMIN.
- Use one PostgreSQL transactional email outbox. Milestone 1A registration creates only an unverified user under a local/test-only feature gate. From 1B onward, registration will commit the user, verification-token record, and outbox entry atomically; a worker sends afterward. See [ADR-009](adr/ADR-009-email-outbox.md).
- Token tables store hashes. Temporarily encrypted outbox token material is permitted for delivery retries; keep its key outside PostgreSQL and purge the material after delivery or expiry.
- Use Figma semantic tokens, custom standalone Angular controls, CDK where needed, Reactive Forms, and Signals/RxJS.
- Keep business authorization, private-task visibility, and same-wedding relationships enforced by the backend.

Details: [sessions](adr/ADR-007-redis-sessions.md), [email outbox](adr/ADR-009-email-outbox.md), and [wedding/membership boundary](adr/ADR-014-wedding-membership.md).

## 3. Selected version baseline and verification policy

The planning review selected the following versions on 2026-10-10. Implementation and verification status is recorded below; a version selection alone is not proof of a working build. Before each stage's generation or installation, recheck release availability, compatibility, and relevant security updates against official documentation and published package metadata. Record any change and its reason rather than silently switching version families.

For 0B, the backend/service subset below was reverified before setup. For 0C, the frontend subset was reverified before generation and installation. For 0D, quality tools, browser automation, action revisions, and container bases were checked before use. The backend and frontend now use their selected framework families with dependency locks; implementation-specific corrections are recorded below.

| Component | Selected baseline | Compatibility source / policy |
|---|---|---|
| Java | Temurin 25 LTS | [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html); no preview language features |
| Spring Boot | 4.1.1 | Manage Spring Security, Spring Data, Hibernate, Spring Session, Flyway, and supporting versions through the Boot BOM |
| Spring Modulith | 2.1.1 | [Release explicitly aligned to Boot 4.1.1](https://github.com/spring-projects/spring-modulith/releases/tag/2.1.1); import its BOM |
| Gradle | 9.8.1, Kotlin DSL | [Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html); running on Java 25 requires Gradle 9.1+ |
| Angular / CLI / CDK | 22.2.2 | [Angular compatibility](https://angular.dev/reference/versions); keep versions aligned |
| TypeScript | 6.0.3 | Angular 22.2.2 requires `>=6.0 <6.1`; do not install TypeScript's unconstrained latest release |
| Node / npm | 22.23.2 / 11.21.0 | Meets Angular and npm engine requirements; use the same baseline locally and in CI |
| RxJS | 7.8.2 | Within Angular's supported range |
| PostgreSQL | 17.11 | [Supported release policy](https://www.postgresql.org/support/versioning/); retain major 17 and review patch updates |
| Redis | 8.2.9 | [Redis 8.2 release notes](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.2-release-notes/) |
| Mailpit | 1.31.4 | [Official releases](https://github.com/axllent/mailpit/releases); local email testing only |
| JUnit / Testcontainers | Boot-managed; planning snapshot 6.0.3 / 2.0.5 | [Boot dependency management](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html); do not independently override |
| Vitest / Playwright | 5.0.0 / 1.64.0 | Angular's unit-test builder accepts Vitest 5; [Playwright supports current Node 22 and Ubuntu 24.04](https://playwright.dev/docs/intro) |
| Angular ESLint / ESLint / typescript-eslint | 22.5.0 / 10.12.0 / 8.71.1 | Keep angular-eslint's major aligned with Angular CLI; selected releases support ESLint 10 and Node 22 |
| Spotless / Google Java Format | 8.10.4 / 1.36.1 | Spotless supports the selected Gradle/JRE; retain the formatter version exercised by the build |
| OpenAPI Generator | 7.26.0, introduced with the first API slice | [Angular generator](https://openapi-generator.tech/docs/generators/typescript-angular/); generate from a reviewed OpenAPI 3.0.3 contract |

The planning review observed Java 25, Node 22.23.2, npm 10.9.8, Docker client 29.5.2, and Compose plugin 5.1.3 on this machine. Docker's Linux engine was initially unreachable, but the read-only 0A check returned client/server 29.5.2. No project containers were started during 0A; the 0B verification is recorded below. Stage 0C retains Node 22.23.2 and pins npm 11.21.0 because the bundled npm resolver crashes on the valid Vitest optional-peer graph. Recheck prerequisites at the relevant implementation stage; do not reinstall tooling unnecessarily.

### Stage 0B compatibility verification — 2026-10-10

- [Boot 4.1.1 requirements](https://docs.spring.io/spring-boot/system-requirements.html) support Java 17–26 and Gradle 8.14+ in the 8.x line or 9.x. The installed Temurin 25.0.4.1 meets this requirement; no JDK installation was needed.
- [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html) supports running Java 25 from Gradle 9.1 onward. The wrapper pins 9.8.1 and verifies its [official SHA-256](https://services.gradle.org/distributions/gradle-9.8.1-bin.zip.sha256).
- [Modulith 2.1.1](https://github.com/spring-projects/spring-modulith/releases/tag/2.1.1) is aligned with Boot 4.1.1. Their published Maven BOMs were checked before resolving dependencies.
- [Boot's managed dependencies](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) supply Security 7.1.1, Session 4.1.1, Flyway 12.4.0, PostgreSQL JDBC 42.7.13, JUnit Jupiter 6.0.3, and Testcontainers 2.0.5. Use Boot 4's focused Web MVC/Flyway/Redis Session starters; adding `flyway-core` alone does not supply all Boot 4 auto-configuration.
- [Flyway PostgreSQL support](https://documentation.red-gate.com/fd/postgresql-database-277579325.html) requires the PostgreSQL database module, included alongside the JDBC driver. [PostgreSQL 17.11](https://www.postgresql.org/support/versioning/) is the supported patch for our selected major.
- Redis 8.2.9 and Mailpit v1.31.4 release availability and published container tags were verified. Service images pin patch releases; there are no `latest` tags.

Docker client/server 29.5.2 and Compose 5.1.3 are available for 0B. The old global Gradle installation is not the project version; use the included wrapper. Source compatibility, dependency resolution, and runtime checks are separate evidence and must all pass before declaring the stage complete.

### Stage 0C compatibility verification — 2026-10-10

- [Angular's compatibility table](https://angular.dev/reference/versions) requires Node `^22.22.3 || ^24.15.0 || ^26.0.0`, TypeScript `>=6.0 <6.1`, and RxJS `^6.5.3 || ^7.4.0` for Angular 22.2.x. Node 22.23.2, TypeScript 6.0.3, and RxJS 7.8.2 satisfy those ranges.
- Angular core, CLI, compiler, build tooling, and CDK are aligned at 22.2.2. The CDK peer range accepts Angular 22, and the Angular unit-test builder accepts Vitest `^4.0.8 || ^5.0.0`.
- Vitest 5.0.0 supports Node `^22.12.0` and jsdom 30.1.2 supports Node `^22.22.2`; both accept the selected runtime. Inter Variable 5.3.0 is packaged locally so the application does not depend on a runtime font CDN.
- npm 10.9.8 and 10.9.9 crash in Arborist while resolving Vitest's valid optional peer graph. npm 11.21.0 supports Node `^20.17.0 || >=22.9.0`, resolves the graph correctly, and reports invalid package versions clearly. The project pins npm 11.21.0 and explicitly records the four reviewed transitive install scripts used by Angular's build cache and compiler tooling.
- The connected Figma file was inspected before implementation. Local primitive color, semantic Light/Dark, spacing, radius, and typography variables were transcribed into the frontend token layer; no temporary Figma asset URLs are used by the application.

### Stage 0D compatibility verification — 2026-10-10

- [angular-eslint 22.5.0](https://github.com/angular-eslint/angular-eslint/releases) aligns its major with Angular 22 and supports ESLint 9/10. ESLint 10.12.0 and typescript-eslint 8.71.1 accept the selected Node 22 runtime.
- [Playwright 1.64.0](https://playwright.dev/docs/intro) supports current Node 22 and Ubuntu 24.04. Its Chromium build was installed and exercised before CI configuration was finalized.
- [Spotless 8.10.4](https://github.com/diffplug/spotless/blob/main/plugin-gradle/README.md) supports JRE 17+ and Gradle 7.3+. Google Java Format 1.37.0 proved incompatible with Spotless 8.10.4 because of a removed formatter API, so the build uses Spotless's supported 1.36.1 formatter release.
- The GitHub workflow pins `actions/checkout`, `actions/setup-node`, and `actions/setup-java` to verified full commit SHAs. actionlint 1.7.12 validates the workflow locally.
- Runtime images use patch-specific tags and verified multi-platform digests: Temurin 25.0.4.1 JDK/JRE, Node 22.23.2 Bookworm Slim, and NGINX 1.30.5 Alpine. Application images run as non-root users.

Use the committed Gradle Wrapper, npm lockfile, and pinned container references once introduced. Resolve formatting/linting tool versions against this baseline before installing them. Use Angular ESLint/Prettier and Spotless/Google Java Format.

Defer production email/storage SDKs, UUIDv7 support until the first entity, API generation until the first API contract, and caching/reporting libraries until needed. No Kafka, RabbitMQ, Redis email queue, persistent Modulith email publication pipeline, Kubernetes, or microservice scaffolding.

## 4. Development and verification workflow

- Prefer short-lived branches and focused commits. Commit/push only with explicit permission; stage completion alone is not that permission.
- Keep real secrets in ignored local files or deployment secret stores. Add `.env.example` with actual configuration in 0B. Browser configuration is public.
- Use host-run applications while editing, backed by Compose PostgreSQL/Redis/Mailpit. Use the optional `application` Compose profile for deployment-style container verification.
- Add immutable Flyway migrations with features. Validate against PostgreSQL; keep Hibernate at `ddl-auto=validate`. Do not invent business tables to make the foundation look complete.
- Test business rules in unit tests and actual persistence/session behavior with Testcontainers. Use security and module-boundary tests as the respective behavior appears.
- Write OpenAPI definitions for each slice before its controllers. Keep generated Angular clients isolated and check regeneration in CI. No full future API generation during Milestone 0.
- Build only the Figma components used by the current stage. Review desktop/mobile and Light/Dark behavior; avoid hardcoded duplicates of semantic tokens.
- Run backend, frontend, integration, browser, and container checks in GitHub Actions. A workflow file or a local check does not prove a remote CI run succeeded.
- If a dependency is unavailable, report the exact blocker, continue only with safely verifiable work, and list blocked checks separately. Never disable security/tests or erase databases to bypass a failure.

## 5. Subsequent milestones

| Milestone | Useful outcome | Required evidence |
|---|---|---|
| 1 — Accounts | Register, verify email, log in/out, recover account; transactional email outbox | Atomic user/token/outbox creation, expiry/replay protection, CSRF/session checks, Mailpit delivery, worker retry/crash/concurrency tests |
| 2 — Wedding workspace | Couple/family/friend onboarding and an empty dashboard | Correct creator role, two couple profiles, transaction rollback on membership failure, tenant isolation, responsive browser journey |
| 3 — Partner and team | Verified owner invitations, permissions, audit baseline | Concurrent owner claims, intended-email checks, owner protection, permission tests |
| 4 — Core planning | Events, tasks, assignments, private visibility, real dashboard summaries | Same-wedding validation, hidden private tasks/counts/activity, concurrency/timezone checks |
| 5 — Guests and RSVP | Households, event assignments, links, login-free RSVP | Scoped guest/event access, expiry/revocation, one current response, clear counting semantics |
| 6 — Money and files | Budget, vendors, expenses, milestones, payments, private documents | Durable payment deduplication, consistent totals, linked-data permissions, safe uploads/downloads |
| 7 — Remaining MVP | Seating, gifts, return gifts, public website, curated media, external livestream links | Capacity/inventory concurrency, publication/privacy checks, resource permissions |
| 8 — Release | Staging, hosting, monitoring, backups/restore, deployment | Complete journeys, accessibility, recovery drills, privacy decisions, verified rollback procedure |

The first useful end-to-end slice spans milestones 1 and 2: register → verify email → log in → create a wedding → reach its empty dashboard.

Milestone 1 is delivered in separately approved stages: **1A** user registration and the first `users` migration; **1B** email verification, token storage, PostgreSQL outbox, and Mailpit delivery; **1C** login/logout with Redis sessions and CSRF renewal; **1D** current-user and password-recovery APIs, rate limiting, and account-lifecycle integration checks. The 1A API is intentionally local/test-only and does not send email or authenticate users. Any retained 1A accounts need a deliberate verification path in 1B without database reset.

## 6. Decisions still open

Resolve each before its feature is implemented; none requires speculative business implementation in Milestone 0.

- Accounts: session timeouts, account-security invalidation behavior, verification/reset lifetimes, and outbox retry/lease/encryption-key lifecycle details.
- Membership: default permissions, override precedence, protected owner capabilities, recovery/transfer, suspended-owner slot protection, and setup-admin privileges after owners join.
- Tasks: visibility for owners/admins/creators/assignees on selected-member tasks.
- Guests: live versus frozen invitation eligibility, expiry, immediate guest-access revocation, response cutoff, and unique-person versus event-invitation totals.
- Finance/files: refunds, overpayments, currency policy, durable idempotency details, attachment permissions, upload limits, scanning, and retention.
- Public/release: archive mutability, website visibility/indexing, server rendering for previews/search, data retention/erasure, and hosting/provider selection.

## 7. Stage 0A documentation change record

The four original documents retain their product scope, section organization, and unrelated contracts. Focused changes align:

- Planner authentication, endpoint examples, frontend behavior, and tests with Redis-backed sessions and CSRF instead of provisional JWT refresh.
- Wedding/membership boundaries and atomic initial ownership, including the approved creator self-declaration rule.
- Email durability with one transactional PostgreSQL outbox, encrypted temporary delivery material, and explicit at-least-once behavior.
- The illustrative owner constraint with required role/null checks, while leaving recovery and suspended-slot policy open.
- Repository naming, incremental migration ordering, and the 0A–0D stage/approval boundaries.

Guest bearer credentials remain a separate future RSVP design. No application behavior or SQL migration is implemented by these documentation changes.

## 8. Stage 0B implementation and verification record

Completed on 2026-10-10. The [backend](../backend/README.md) now contains the Gradle build/wrapper/lockfile, application entry point, Spring Security configuration, environment-based configuration, health support, migration guidance, and six foundation integration tests. [Compose](../infrastructure/docker-compose.yml) provides PostgreSQL, Redis, and Mailpit with patch-pinned images, health checks, loopback ports, and named volumes. The root `.env.example` documents the separate Compose and host-backend settings.

The application entry point sets the JVM default timezone to UTC before connecting to PostgreSQL. The first integration run exposed rejection of the host's `Asia/Calcutta` alias; Hibernate's JDBC timestamp setting alone cannot fix the earlier connection handshake. The same entry point is exercised by the integration tests, which verify `SHOW timezone` returns UTC. No machine-wide timezone change was made.

Verification completed:

- Official framework requirements, BOM artifacts, and container release tags checked before setup. Both the Gradle distribution checksum and generated wrapper JAR checksum matched official values.
- Normal locked `gradlew.bat build` passed. All **six** JUnit/Testcontainers tests passed, with zero failures, errors, or skipped tests, against isolated PostgreSQL 17.11 and Redis 8.2.9 containers.
- Compose configuration validated and all three services became healthy. PostgreSQL readiness, Redis PING, and Mailpit HTTP/SMTP availability passed.
- The packaged backend started with the local environment. Public health/readiness/liveness returned `200`; protected reads returned `401`; a write without CSRF returned `403`. Local cookie attributes were verified, alongside Secure cookies in the automated test profile.
- Flyway initialized its history table and validated zero migrations. The Compose database contains no business tables. A subsequent packaged startup validated the same schema without a reset.
- With Redis stopped, readiness returned `503` in approximately two seconds while liveness remained `200`. Readiness recovered to `200` after Redis restarted.
- Documentation links, whitespace, ignored environment/build files, and trackable wrapper/lockfile checks passed. The original four baseline documents were not changed during 0B. Git history and the index were preserved; no commit or push occurred.

No current dependency blocker remained at the end of 0B. The 30-minute session timeout is a configurable foundation default; final account policy and Angular CSRF bootstrap/renewal remain account-milestone decisions. Authentication workflows, business modules/tables, frontend generation, GitHub Actions, and application Dockerfiles were outside that stage, and 0C still required separate approval at that point.

## 9. Stage 0C implementation and verification record

Completed on 2026-10-10. The [frontend](../frontend/README.md) now contains a strict standalone Angular application with lazy route-level pages, a responsive application shell, persistent Light/Dark theming, an accessible mobile navigation drawer, local Inter Variable assets, a development `/api` proxy, and a visual token reference. The token stylesheet mirrors the reviewed Figma primitive, semantic, spacing, radius, and typography collections.

The only implemented routes are `/foundation` and `/design-tokens`; `/` and unknown paths redirect to the foundation. These pages explain and demonstrate the scaffold. They do not implement registration, login, wedding creation, dashboard data, API calls, or any other business behavior.

Verification completed:

- Official Angular, Node, TypeScript, RxJS, CDK, Vitest, jsdom, and npm compatibility was checked before finalizing the dependency manifest. A clean `npm ci` from the committed lockfile installed 282 packages successfully with npm 11.21.0.
- The production build passed with no warnings. The initial bundle is 240.60 kB raw and 65.34 kB estimated transfer size; the shell and both pages are emitted as separate lazy chunks.
- All **six** Vitest/jsdom tests passed across three test files, covering the root outlet, both routed pages, and theme restoration/toggling/persistence.
- Prettier verification passed for every frontend source and configuration file. Reviewed dependency install scripts are explicit, and npm reports no unreviewed install scripts.
- Browser inspection passed for desktop and a 390 × 844 mobile viewport. Both themes, saved-theme reload, route navigation/titles, responsive drawer state, first-tab skip link, and desktop/mobile layouts behaved as intended. The browser console contained no warnings or errors.
- The original four baseline documents were not changed during 0C. Git history and the index were preserved; no commit or push occurred.

## 10. Stage 0D implementation and verification record

Completed on 2026-10-10. The backend build now enforces Spotless formatting and verifies its Spring Modulith structure. The frontend adds Angular ESLint and three Playwright journeys. Pinned multi-stage Dockerfiles produce non-root backend and frontend images, while the optional Compose `application` profile connects them to PostgreSQL and Redis. NGINX provides SPA fallback routing, security headers, static asset serving, and the same-origin `/api` proxy.

Browser automation exposed that the closed mobile navigation remained keyboard-focusable; the shell now marks that off-canvas region inert until it opens. Container verification also exposed a conflict between Angular's inline critical-style output and the strict Content Security Policy. Production builds now emit an external stylesheet, preserving the policy without unsafe-inline exceptions.

Verification completed:

- Official compatibility and package metadata were checked for Angular ESLint, ESLint, typescript-eslint, Playwright, Spotless, Google Java Format, the GitHub setup actions, and every application container base. The formatter correction from Google Java Format 1.37.0 to compatible 1.36.1 is recorded above.
- The locked backend build passed with all **seven** JUnit/Testcontainers/Modulith tests, plus Spotless checks. No test used or erased the Compose database.
- A clean frontend install passed Angular ESLint, Prettier, the production build, and all **six** Vitest tests. All **three** Playwright Chromium journeys passed, including the container-served application at desktop and mobile sizes.
- Both application images built from their pinned bases. PostgreSQL, Redis, Mailpit, backend, and frontend reached healthy status. Backend readiness returned `UP`; root and deep SPA routes returned `200`; an anonymous API request through NGINX returned `401`.
- The frontend image serves JavaScript and CSS with the correct MIME types, emits no inline script/style blocks, and returns its Content Security Policy, referrer, no-sniff, and frame-denial headers. NGINX configuration validation passed. The backend runs as `10001:10001`; the frontend runs as `nginx`.
- The GitHub Actions workflow passed actionlint 1.7.12 locally. It runs backend, frontend, browser, image, and stack smoke checks with read-only repository permissions and full-SHA action pins. No remote GitHub run is claimed because no commit or push was authorized.
- Documentation links and whitespace checks passed. The original four baseline documents were not changed during 0D. Git history and the index were preserved; no commit, push, deployment, database reset, or volume deletion occurred.

A literal clean Git clone was not created because Stages 0A–0D remain uncommitted in the preserved worktree. Clean npm installation and isolated Docker build contexts verified locked reconstruction without copying local build output. Milestone 1 accounts and all other business features remain outside this completed milestone and require separate approval.

## 11. Milestone 1A implementation record

Completed locally on 2026-10-13. The first `users` Flyway migration and `identity` module implement unverified registration, versioned PBKDF2 password hashing, case-insensitive email uniqueness, and RFC 9457 errors. `GET /api/v1/auth/csrf` bootstraps an anonymous Redis session for the protected registration POST. Both endpoints require a `local` or `test` profile plus the registration flag; the flag is off by default. The [OpenAPI contract](api/openapi.yaml) describes only these two endpoints, and the [implementation note](implementation/milestone-1a-user-registration.md) records the security and schema choices.

The accepted outbox decision is unchanged for production registration beginning in 1B. This explicitly temporary 1A flow stores only the unverified user in a PostgreSQL transaction. 1B must create token/outbox records atomically for future registrations and handle any retained 1A users without deleting them. Stages 1B–1D still require separate approval. No Angular account UI, login, verification email, outbox, or wedding behavior was added.

Verification: the locked Gradle build and Spotless check passed; all 18 JUnit tests passed, including fresh PostgreSQL/Redis Testcontainers registration and foundation checks, concurrent email uniqueness, CSRF/session pairing, the production-profile gate, and Spring Modulith verification. OpenAPI YAML parsed, all 18 local `$ref` targets resolved, changed Markdown links resolved, and `git diff --check` passed. These checks used isolated test databases and did not reset the developer's Compose database. No commit or push was made during 1A.

## 12. Milestone 1B-A implementation record

The separately authorized first stage of email verification adds `V2__create_email_verification_tokens.sql` and an internal identity token lifecycle. It leaves the 1A registration API, local/test gate, OpenAPI contract, and existing accounts unchanged. Tokens use 32 random bytes, SHA-256 hash-only persistence, 24-hour expiry, row-lock ordering, revocation on replacement, and single-use consumption. Successful internal consumption sets `users.email_verified_at` without changing `users.status`; only `ACTIVE` accounts may verify. The [stage note](implementation/milestone-1b-a-verification-tokens.md) records the implementation and remaining steps.

The local 1A checkpoint is commit `89c6f20`, created only after explicit authorization. Stage 1B-A changes remain uncommitted and unpushed. The outbox, worker, Mailpit adapter, verification/resend APIs, Angular page, and atomic registration integration require their later stage approvals. Duplicate-registration responses need an account-enumeration review before public signup.

Verification on 2026-10-13: the locked backend build and Spotless check passed with 30 JUnit tests and zero failures. PostgreSQL/Redis Testcontainers covered the new lifecycle; a separate PostgreSQL test migrated an existing V1 account to V2 without deleting it. Flyway validated both migrations, Hibernate validated the resulting schema, and the existing registration and Modulith tests still passed. No developer database was reset or modified by these checks.

## 13. Milestone 1B-B implementation record

The separately authorized Stage 1B-B adds [`V3__create_email_outbox.sql`](../backend/src/main/resources/db/migration/V3__create_email_outbox.sql) and an independent `notification` Modulith module. Its root contract requires an existing transaction to enqueue encrypted email work. AES-GCM protects a validated JSON object payload with keys outside Git and PostgreSQL. The worker claims with PostgreSQL `FOR UPDATE SKIP LOCKED`, sends after the claim transaction commits, retries temporary failures at 1/2/4/8 minutes, and stops after five attempts. Two-minute leases, claim ownership checks, expiry handling, and terminal ciphertext purge support crash recovery. The [stage note](implementation/milestone-1b-b-email-outbox.md) records the exact schema, configuration, tests, and limitations.

The worker is off by default and no real `EmailSender` is configured in this stage. The current 1A registration API still writes only a user, and the OpenAPI contract is unchanged. Stage 1B-A remains uncommitted; Stage 1B-B changes are also uncommitted and unpushed. A separate 1B-A checkpoint is recommended before a future 1B-B commit. Registration integration, verification/resend APIs, Angular confirmation, and Mailpit delivery require later approval.

Verification on 2026-10-13: the locked backend build and Spotless check passed with 43 JUnit tests and zero failures. PostgreSQL/Redis Testcontainers verified encrypted persistence, transaction rollback, multi-worker claims, retries and crash recovery; a separate PostgreSQL migration test preserved existing V2 user/token rows when applying V3. Flyway and Hibernate validated the schema, and the existing registration, security, and Modulith checks passed. No developer database was reset or modified by these checks.

## 14. Milestone 1B-C implementation record

Stage 1B-C connects registration to token issuance and the encrypted transactional outbox. The gated verification and resend APIs use the existing anonymous Redis session and CSRF protection. Resend supports retained 1A users without a database reset, revokes previous tokens, and purges pending jobs by the optional V4 outbox purpose ID. The Angular `/verify-email` route consumes a token from the URL fragment only after an explicit user action. See the [stage note](implementation/milestone-1b-c-registration-verification.md).

The outbox worker remains disabled and no SMTP or Mailpit sender is installed. Registration and verification remain local/test-only; the default application profile exposes none of these endpoints. Full signup abuse prevention, cooldowns, and account-enumeration review remain before public launch. Stage 1B-A/B/C changes remain uncommitted and unpushed pending separate approval. Verification results are recorded in the stage note after checks.

## 15. Milestone 1B-D implementation record

Stage 1B-D adds the Boot-managed SMTP adapter, branded multipart verification email, opt-in local/test worker, best-effort token eligibility checks before send, and Redis HMAC-keyed resend/verification limits. Mailpit receives mail only when the worker is enabled with complete SMTP, encryption, and rate-limit configuration. Host-run Java uses `127.0.0.1:1025`; an optional application container uses the `mailpit:1025` Compose service address. The default and production profiles do not expose registration or enable delivery. No new SQL migration is needed. See the [stage note](implementation/milestone-1b-d-mailpit-integration.md) for results and remaining limitations. Stage 1B-A/B/C/D changes remain uncommitted and unpushed.
