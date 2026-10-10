# Backend

This is the Spring Boot foundation for the modular monolith. Milestones 0B and 0D provide infrastructure, security configuration, module verification, formatting, tests, and a production-style container image. There are no business controllers, entities, accounts, or email workflows.

## Toolchain and dependencies

Use Java 25 and the included Gradle 9.8.1 Wrapper. Spring Boot 4.1.1 manages Spring Security, JPA/Hibernate, Redis, Spring Session, Flyway, PostgreSQL JDBC, JUnit, and Testcontainers versions. Spring Modulith uses its 2.1.1 BOM. The wrapper verifies the official Gradle distribution checksum; `gradle.lockfile` records resolved application/test dependencies.

The build uses Boot 4's focused Web MVC, Redis Session, and Flyway starters. No separate dependency-management plugin is needed: Gradle imports the BOMs directly. Validation, mail/provider SDKs, API generation, and persistent event publication remain deferred. Spotless 8.10.4 with Google Java Format 1.36.1 enforces the Java source format.

There is one Gradle project with base package `com.makemymarriage`. `platform/SecurityConfiguration` is the only application configuration class. Create business modules only in later feature milestones.

Future module rules:

- `identity` owns accounts and authentication without wedding-role knowledge.
- `wedding` owns creation, couple profiles, membership, and permissions in separated internal packages.
- `notification` owns the single transactional email outbox and delivery adapter.
- `audit` owns sensitive action history.
- Modules expose small public contracts and keep repositories internal.

Flyway initializes and validates its own history table. The [migration directory](src/main/resources/db/migration/README.md) intentionally contains no SQL yet. Hibernate uses `ddl-auto=validate`, with Open Session in View disabled. Add the first real migration when identity is implemented.

The entry point sets the backend JVM's default timezone to UTC before Spring starts. This makes JDBC startup and server timestamps independent of the host timezone, including the Windows `Asia/Calcutta` alias that the selected PostgreSQL image rejects. It does not change the host timezone or future wedding-specific IANA timezone fields. Integration tests launch through this same entry point and verify the database connection timezone.

## Run locally

Start PostgreSQL, Redis, and Mailpit using the [infrastructure instructions](../infrastructure/README.md). From the repository root, configure the backend's environment in PowerShell, then start it:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/makemymarriage'
$env:DATABASE_USERNAME = 'makemymarriage'
$env:DATABASE_PASSWORD = 'local-development-only'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
Set-Location backend
.\gradlew.bat bootRun
```

These are development examples. Match the database name, username, password, and ports to your ignored root `.env` file. Compose's `--env-file` does **not** export variables into the host-run Java process. Configure them in the launching shell or IDE; the backend does not read `.env` automatically. On macOS/Linux, export the same variables and run `sh ./gradlew bootRun` from this directory.

`local` binds the backend to `127.0.0.1:8080` and permits HTTP session cookies for development. Outside that profile, the database credentials and Redis host must be supplied, and session cookies are Secure. Override the backend port with `SERVER_PORT` if necessary. Stop the backend with Ctrl+C.

## Foundation behavior

- `GET /actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` are public. Responses hide dependency details; readiness includes PostgreSQL and Redis, while liveness checks application state.
- Other requests require authentication. Anonymous reads return `401`, without redirecting to a login screen. Writes without a valid CSRF token return `403`.
- Basic authentication, generated users, login/logout pages, and saved-request redirects are disabled. No login or CSRF-bootstrap business endpoint exists yet.
- Spring Session persists sessions in Redis under `makemymarriage:session`. Cookies are HttpOnly and SameSite=Lax; URL-based session tracking is disabled. The configurable 30-minute timeout is a foundation default, not the final account policy.
- Default Spring Security session-based CSRF protection remains enabled. Angular's cookie/header bootstrap and renewal contract will be added with accounts.

Once the backend starts, open [health](http://127.0.0.1:8080/actuator/health) or run:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

An `UP` readiness response verifies application connectivity to PostgreSQL and Redis. Mailpit is a separate development service; the backend does not send email yet.

## Verification

With Java 25 and Docker's Linux engine running, from `backend/`:

```powershell
.\gradlew.bat build
```

Six foundation integration tests use isolated Testcontainers PostgreSQL/Redis instances with random ports. They check public health probes, protected routes, CSRF and secure cookies, absence of default login/logout, Flyway initialization without business tables, and Redis session persistence/invalidation. A seventh test verifies the Spring Modulith structure. The tests do not use or erase the Compose database. Docker is required; an unavailable engine is a failed prerequisite, not a skipped success.

The executable JAR is `build/libs/make-my-marriage-0.0.1-SNAPSHOT.jar`. It accepts the same environment as `bootRun`. Test reports are in `build/reports/tests/test/index.html`.

The [Dockerfile](Dockerfile) builds the locked application with the Gradle Wrapper and runs the JAR on a pinned Temurin JRE as unprivileged user `10001:10001`. Build and exercise it through the `application` profile in the [infrastructure instructions](../infrastructure/README.md).

Stop a running packaged application before rebuilding its JAR; replacing a JAR underneath a running JVM can break later class loading. Flyway's "No migrations found" warning is expected at this stage because business schema changes have not started.

For deliberate dependency updates, review compatibility first, regenerate locks with `./gradlew.bat build --write-locks`, review the lockfile diff, then run the normal locked build. Do not update versions just to bypass a failing test.

Registration, login, email delivery workflows, and wedding creation are outside Milestone 0.

See the [development plan](../docs/05-development-plan.md) and [architecture](../docs/02-system-architecture.md).
