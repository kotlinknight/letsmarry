# Backend

This is the Spring Boot modular monolith. Milestones 0B and 0D provide the foundation. Milestone 1A adds local/test-only user registration, the first `users` migration, and an OpenAPI contract. Email verification, login, and wedding features are not implemented.

## Toolchain and dependencies

Use Java 25 and the included Gradle 9.8.1 Wrapper. Spring Boot 4.1.1 manages Spring Security, JPA/Hibernate, Redis, Spring Session, Flyway, PostgreSQL JDBC, JUnit, and Testcontainers versions. Spring Modulith uses its 2.1.1 BOM. The wrapper verifies the official Gradle distribution checksum; `gradle.lockfile` records resolved application/test dependencies.

The build uses Boot 4's focused Web MVC, Redis Session, Flyway, and Validation starters. No separate dependency-management plugin is needed: Gradle imports the BOMs directly. Mail/provider SDKs, client generation, and persistent event publication remain deferred. Spotless 8.10.4 with Google Java Format 1.36.1 enforces the Java source format.

There is one Gradle project with base package `com.makemymarriage`. `identity` is the first business module; `platform` holds shared security and correlation-ID support. Add other modules with their first features.

Future module rules:

- `identity` owns accounts and authentication without wedding-role knowledge.
- `wedding` owns creation, couple profiles, membership, and permissions in separated internal packages.
- `notification` owns the single transactional email outbox and delivery adapter.
- `audit` owns sensitive action history.
- Modules expose small public contracts and keep repositories internal.

Flyway applies the [first migration](src/main/resources/db/migration/V1__create_users.sql) and validates its history. Hibernate uses `ddl-auto=validate`, with Open Session in View disabled. Never edit a shared migration or use Hibernate schema generation.

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

## Registration behavior

`app.registration.enabled` is false by default, and the controller also requires the `local` or `test` profile. The `local` profile enables `GET /api/v1/auth/csrf` and `POST /api/v1/auth/register` only for local development; integration tests opt in explicitly. These endpoints return `404` when the gate is off. This is not a production-ready public signup: verification email, outbox, rate limiting, and login arrive in later account stages.

With the local backend running, use the same web session for CSRF bootstrap and registration:

```powershell
$csrfResponse = Invoke-WebRequest http://127.0.0.1:8080/api/v1/auth/csrf -SessionVariable registrationSession
$csrf = $csrfResponse.Content | ConvertFrom-Json
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
$body = @{
  displayName = 'Anjali'
  email = 'anjali@example.com'
  password = 'SecurePassword123!'
} | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/register -Method Post -WebSession $registrationSession -Headers $headers -ContentType 'application/json' -Body $body
```

Registration returns `201` with a UUIDv7 ID and `emailVerified: false`. The anonymous Redis session is only for CSRF; registration does not log the user in. Repeating the example email returns `409`. The password is never returned. See the [OpenAPI contract](../docs/api/openapi.yaml) and [implementation note](../docs/implementation/milestone-1a-user-registration.md).

## Foundation behavior

- `GET /actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` are public. Responses hide dependency details; readiness includes PostgreSQL and Redis, while liveness checks application state.
- Other requests require authentication. Anonymous reads return `401`, without redirecting to a login screen. Writes without a valid CSRF token return `403`.
- Basic authentication, generated users, login/logout pages, and saved-request redirects are disabled. The only account endpoints are the gated registration and CSRF bootstrap endpoints.
- Spring Session persists sessions in Redis under `makemymarriage:session`. Cookies are HttpOnly and SameSite=Lax; URL-based session tracking is disabled. The configurable 30-minute timeout is a foundation default, not the final account policy.
- Default Spring Security session-based CSRF protection remains enabled. Local/test registration uses `SESSION` and `X-CSRF-TOKEN`; Angular integration and token renewal after future login/logout are deferred.

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

The JUnit suite includes isolated Testcontainers PostgreSQL/Redis integration tests for the foundation and registration, password-policy/service unit tests, and Spring Modulith verification. It checks Flyway validation, user persistence, case-insensitive uniqueness, CSRF/session behavior, response safety, and the default-off gate. The tests do not use or erase the Compose database. Docker is required; an unavailable engine is a failed prerequisite, not a skipped success.

The executable JAR is `build/libs/make-my-marriage-0.0.1-SNAPSHOT.jar`. It accepts the same environment as `bootRun`. Test reports are in `build/reports/tests/test/index.html`.

The [Dockerfile](Dockerfile) builds the locked application with the Gradle Wrapper and runs the JAR on a pinned Temurin JRE as unprivileged user `10001:10001`. Build and exercise it through the `application` profile in the [infrastructure instructions](../infrastructure/README.md).

Stop a running packaged application before rebuilding its JAR; replacing a JAR underneath a running JVM can break later class loading.

For deliberate dependency updates, review compatibility first, regenerate locks with `./gradlew.bat build --write-locks`, review the lockfile diff, then run the normal locked build. Do not update versions just to bypass a failing test.

Login, email delivery workflows, and wedding creation are outside Milestone 1A.

See the [development plan](../docs/05-development-plan.md) and [architecture](../docs/02-system-architecture.md).
