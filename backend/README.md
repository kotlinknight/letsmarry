# Backend

This is the Spring Boot modular monolith. Stage 1C-B adds current-user retrieval, server-side logout, and account revalidation to the opt-in Redis-session login. Angular authentication and wedding features are not implemented yet.

## Toolchain and dependencies

Use Java 25 and the included Gradle 9.8.1 Wrapper. Spring Boot 4.1.1 manages Spring Security, JPA/Hibernate, Redis, Spring Session, Flyway, PostgreSQL JDBC, JUnit, and Testcontainers versions. Spring Modulith uses its 2.1.1 BOM. The wrapper verifies the official Gradle distribution checksum; `gradle.lockfile` records resolved application/test dependencies.

The build uses Boot 4's focused Web MVC, Redis Session, Flyway, Validation, and Mail starters. No separate dependency-management plugin is needed: Gradle imports the BOMs directly. Production provider SDKs, client generation, and persistent event publication remain deferred. Spotless 8.10.4 with Google Java Format 1.36.1 enforces the Java source format.

There is one Gradle project with base package `com.makemymarriage`. `identity` owns accounts and verification tokens; `notification` owns the encrypted outbox and delivery worker; `platform` holds shared security and correlation-ID support. Add other modules with their first features.

Future module rules:

- `identity` owns accounts and authentication without wedding-role knowledge.
- `wedding` owns creation, couple profiles, membership, and permissions in separated internal packages.
- `notification` exposes `EmailOutbox`, `EmailSender`, and delivery-eligibility contracts; the local adapter uses SMTP through Mailpit.
- `audit` owns sensitive action history.
- Modules expose small public contracts and keep repositories internal.

Flyway applies the [users migration](src/main/resources/db/migration/V1__create_users.sql), [verification-token migration](src/main/resources/db/migration/V2__create_email_verification_tokens.sql), and [outbox migration](src/main/resources/db/migration/V3__create_email_outbox.sql), then validates its history. Hibernate uses `ddl-auto=validate`, with Open Session in View disabled. Never edit a shared migration or use Hibernate schema generation.

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
$env:EMAIL_OUTBOX_ACTIVE_KEY_ID = 'v1'
$env:EMAIL_OUTBOX_KEYS = 'v1:<base64-of-a-private-random-32-byte-key>'
$env:VERIFICATION_RATE_LIMIT_KEY = '<base64-of-a-different-private-random-32-byte-key>'
$env:SPRING_MAIL_HOST = '127.0.0.1'
$env:SPRING_MAIL_PORT = '1025'
$env:MAIL_FROM = 'hello@makemymarriage.local'
$env:EMAIL_OUTBOX_WORKER_ENABLED = 'true'
$env:AUTHENTICATION_ENABLED = 'true'
$env:AUTHENTICATION_RATE_LIMIT_KEY = '<base64-of-a-third-private-random-32-byte-key>'
Set-Location backend
.\gradlew.bat bootRun
```

These are development examples. Match the database name, username, password, and ports to your ignored root `.env` file. Compose's `--env-file` does **not** export variables into the host-run Java process. Configure them in the launching shell or IDE; the backend does not read `.env` automatically. On macOS/Linux, export the same variables and run `sh ./gradlew bootRun` from this directory.

`local` binds the backend to `127.0.0.1:8080` and permits HTTP session cookies for development. Outside that profile, the database credentials and Redis host must be supplied, and session cookies are Secure. Override the backend port with `SERVER_PORT` if necessary. Stop the backend with Ctrl+C.

## Registration and authentication behavior

`app.registration.enabled` and `app.email-outbox.enabled` are false in the base profile. The registration controller requires both flags and a `local` or `test` profile; `production` blocks it even if also activated. The `local` profile enables the flags and Redis rate limiting, so private outbox and rate-limit keys are required at startup. Registration, verification, and resend return `404` when gated off; `GET /api/v1/auth/csrf` remains available independently. The scheduled worker additionally requires explicit `EMAIL_OUTBOX_WORKER_ENABLED=true`.

Login requires separate `AUTHENTICATION_ENABLED=true` and a private `AUTHENTICATION_RATE_LIMIT_KEY` (Base64 of at least 32 random bytes). The flag is false by default, including local development, and has no profile restriction. A future approved production deployment may enable login while public registration remains disabled. Do not reuse the outbox encryption or verification rate-limit keys. A missing or weak authentication rate-limit key fails startup when login is enabled. The initial configurable limits are 10 attempts per normalized email and 60 per direct IP per 15 minutes.

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

Registration returns `201` with a UUIDv7 ID and `emailVerified: false`. The anonymous Redis session is only for CSRF; registration does not log the user in. Repeating the example email returns `409`. The password and raw token are never returned. With the worker enabled, wait a few seconds and open [Mailpit](http://127.0.0.1:8025); open the verification link, click **Confirm email address** on the Angular page, and observe a verified result. A used link fails on retry. You can request a fresh email with the same session and CSRF header:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/email/verification-requests -Method Post -WebSession $registrationSession -Headers $headers -ContentType 'application/json' -Body '{"email":"anjali@example.com"}'
```

The resend response is always empty `202` for a valid address under email-specific limits. Only one resend per email every five minutes and three per rolling day are allowed; the IP-wide default is 30/hour. Verification attempts default to 20/hour per direct remote IP. IP-wide overuse returns `429`; Redis failure returns `503` and does not issue or consume a token. Do not use forwarded IP headers without configuring a trusted proxy. See the [OpenAPI contract](../docs/api/openapi.yaml) and [Stage 1B-D note](../docs/implementation/milestone-1b-d-mailpit-integration.md).

After verification, obtain a fresh CSRF token with the same browser session, then log in:

```powershell
$csrfResponse = Invoke-WebRequest http://127.0.0.1:8080/api/v1/auth/csrf -WebSession $registrationSession
$csrf = $csrfResponse.Content | ConvertFrom-Json
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
$loginBody = @{ email = 'anjali@example.com'; password = 'SecurePassword123!' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/login -Method Post -WebSession $registrationSession -Headers $headers -ContentType 'application/json' -Body $loginBody
```

Login returns `200` with a safe user object and rotates the HttpOnly `SESSION` cookie. It does not return a session credential in JSON. Unknown accounts, wrong passwords, unverified email, and suspended/deactivated accounts receive the same `401 INVALID_CREDENTIALS`. Fetch a new CSRF token after login before another write. Public registration is still disabled outside local/test. See the [1C-A note](../docs/implementation/milestone-1c-a-authentication.md).

Read current account details or log out with the same browser session:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/users/me -WebSession $registrationSession
$csrfResponse = Invoke-WebRequest http://127.0.0.1:8080/api/v1/auth/csrf -WebSession $registrationSession
$csrf = $csrfResponse.Content | ConvertFrom-Json
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/logout -Method Post -WebSession $registrationSession -Headers $headers
```

`/users/me` returns the fresh verified `ACTIVE` account from PostgreSQL, without credentials or session data. Protected requests recheck account standing; suspension, deactivation, removal, or loss of email verification invalidates that browser session and returns generic `401 AUTHENTICATION_REQUIRED`. Logout returns empty `204`, expires only that browser's session cookie, and leaves other browser sessions active. Replaying the old CSRF token fails; obtain a new token from `/auth/csrf` to repeat logout or perform another write. Redis or database failure denies access with `503` Problem Details. See the [1C-B note](../docs/implementation/milestone-1c-b-session-lifecycle.md).

## Foundation behavior

- `GET /actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` are public. Responses hide dependency details; readiness includes PostgreSQL and Redis, while liveness checks application state.
- Other requests require authentication. Anonymous reads return `401`, without redirecting to a login screen. Writes without a valid CSRF token return `403`.
- Basic authentication, generated users, login/logout pages, and saved-request redirects are disabled. The JSON login endpoint is available only with its separate opt-in flag; `/users/me` and server-side logout are implemented.
- Spring Session persists sessions in Redis under `makemymarriage:session`. Cookies are HttpOnly and SameSite=Lax; URL-based session tracking is disabled. The configurable 30-minute timeout is a foundation default, not the final account policy.
- Default Spring Security session-based CSRF protection remains enabled. The local/test Angular verification page uses `SESSION` and `X-CSRF-TOKEN`; login and logout clear old CSRF state, so the client must call `/auth/csrf` again before another write.

Once the backend starts, open [health](http://127.0.0.1:8080/actuator/health) or run:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

An `UP` readiness response verifies application connectivity to PostgreSQL and Redis. It does not prove SMTP delivery; confirm that separately in Mailpit.

## Stage 1B-B outbox configuration

The base profile disables both `app.email-outbox.enabled` and `app.email-outbox.worker.enabled`; the local profile enables only the outbox. Enabling it requires `EMAIL_OUTBOX_ACTIVE_KEY_ID` and `EMAIL_OUTBOX_KEYS` in the backend process environment. `EMAIL_OUTBOX_KEYS` is a comma-separated list such as `v1:<Base64-encoded 32-byte key>`; keep older key IDs present until their pending messages are delivered or expired. Generate a stable key outside Git, provide it through the IDE or shell on each run, and never print or commit its value. The root `.env.example` documents variable names only; Compose's `--env-file` does not export them to a host-run backend.

Stage 1B-D supplies a Spring Boot managed SMTP sender. The scheduled worker remains disabled by default, is local/test-only, and fails startup if its sender or required SMTP settings are absent. `SPRING_MAIL_HOST=127.0.0.1` and `SPRING_MAIL_PORT=1025` work for a host-run backend; an application container uses `mailpit:1025`. Do not set `SPRING_MAIL_HOST` when no SMTP service is configured, because Boot's mail health check would then affect readiness. The configured SMTP connection/read/write timeouts are 5/10/10 seconds, below the two-minute worker lease. Keep both private keys stable across restarts while pending jobs exist. See the [Stage 1B-D note](../docs/implementation/milestone-1b-d-mailpit-integration.md).

## Stage 1B-C behavior

Registration atomically writes the unverified user, a hash-only 24-hour verification token, and an encrypted `VERIFY_EMAIL` outbox job. `V4__link_outbox_purpose.sql` adds a nullable purpose ID to identify a job for cancellation. The worker can now deliver that job to Mailpit when explicitly enabled.

The controller requires both `app.registration.enabled=true` and `app.email-outbox.enabled=true`, plus a `local` or `test` profile. Base defaults are false; `local` enables both. Set a private 32-byte AES-GCM key using `EMAIL_OUTBOX_ACTIVE_KEY_ID` and `EMAIL_OUTBOX_KEYS` in the backend process environment before starting. Missing key configuration fails startup safely. Keep the key outside Git and PostgreSQL. The frontend link base defaults to `http://127.0.0.1:4200/verify-email`; override it with `VERIFICATION_FRONTEND_URL` for another local origin. HTTPS is required outside loopback.

With the anonymous `SESSION` cookie and `X-CSRF-TOKEN` from `GET /api/v1/auth/csrf`, `POST /api/v1/auth/email/verify` accepts `{ "token": "<opaque-token>" }` and returns `204` on success. `POST /api/v1/auth/email/verification-requests` accepts `{ "email": "anjali@example.com" }` and returns an empty `202` for any valid email, whether eligible or not. It supports older 1A accounts and revokes previous links. The raw token is never returned by an API; tests inspect the isolated encrypted outbox. The duplicate registration `409` remains a known account-enumeration issue before public signup. See the [stage note](../docs/implementation/milestone-1b-c-registration-verification.md) and [OpenAPI contract](../docs/api/openapi.yaml).

## Verification

With Java 25 and Docker's Linux engine running, from `backend/`:

```powershell
.\gradlew.bat build
```

The JUnit suite includes isolated Testcontainers PostgreSQL/Redis integration tests for the foundation, registration, token lifecycle, and encrypted outbox, plus Spring Modulith verification. It checks Flyway/Hibernate validation, existing-data migration upgrades, CSRF/session behavior, safe responses, encrypted persistence, worker claims, retries, and default-off delivery. The tests do not use or erase the Compose database. Docker is required; an unavailable engine is a failed prerequisite, not a skipped success.

The executable JAR is `build/libs/make-my-marriage-0.0.1-SNAPSHOT.jar`. It accepts the same environment as `bootRun`. Test reports are in `build/reports/tests/test/index.html`.

The [Dockerfile](Dockerfile) builds the locked application with the Gradle Wrapper and runs the JAR on a pinned Temurin JRE as unprivileged user `10001:10001`. Build and exercise it through the `application` profile in the [infrastructure instructions](../infrastructure/README.md).

Stop a running packaged application before rebuilding its JAR; replacing a JAR underneath a running JVM can break later class loading.

For deliberate dependency updates, review compatibility first, regenerate locks with `./gradlew.bat build --write-locks`, review the lockfile diff, then run the normal locked build. Do not update versions just to bypass a failing test.

Angular authentication, password recovery, and wedding creation remain outside Stage 1C-B. Duplicate-registration `409` responses need an account-enumeration review before public signup.

See the [development plan](../docs/05-development-plan.md) and [architecture](../docs/02-system-architecture.md).
