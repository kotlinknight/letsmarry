# ADR-007 — Redis-backed planner sessions

**Status:** Accepted

**Date:** 2026-10-10

**Supersedes:** Provisional planner access JWT plus rotating refresh token

## Context

The initial client is a first-party Angular application. Redis is already required. A server-side session avoids access-token storage, coordinated refresh requests, and refresh-family replay handling in the planner.

## Decision

- Use Spring Security with Spring Session backed by Redis and an opaque HttpOnly session cookie.
- Plan same-origin frontend/API access, using a development proxy locally and a reverse proxy in deployment.
- Use Secure cookies under deployed HTTPS, deliberate SameSite settings, CSRF protection, and trusted-origin checks. Any HTTP cookie exception is local-only.
- Login establishes the session and returns the user; `/users/me` restores identity after page reload. Logout invalidates the server session and clears the cookie.
- Bootstrap CSRF through `GET /api/v1/auth/csrf`; obtain a fresh token after login or logout before another unsafe request.
- Do not issue planner access/refresh JWTs or implement `/auth/refresh`.
- Resolve current wedding membership and permissions on the backend; identity itself has no wedding-specific roles.
- Guest invitation exchange and its narrow bearer credentials remain separate. A planner cookie is not a substitute for guest-scope authorization.

## Consequences and verification

Redis availability affects authenticated planner requests. Lost/expired sessions require reauthentication; PostgreSQL remains the durable source of account standing. Stage 1C-A adopts a 30-minute inactivity timeout, multiple concurrent browser sessions, session-ID rotation on login, and a generic verified/ACTIVE login policy. Stage 1C-B stores only the stable account ID in the session, rechecks status and email verification against PostgreSQL on every protected request, invalidates an ineligible session, and limits logout to the current session. Session-store failures fail closed with a generic `503` Problem Details response. The authentication feature gate is independent of public registration, and the CSRF bootstrap remains available even when registration is disabled.

Stage 1C-D confirmed real Redis storage and revocation through the browser journey. For Spring Boot 4.1, the application-specific key prefix is configured with `spring.session.data.redis.namespace=makemymarriage:session`; the earlier `spring.session.redis.namespace` setting was ignored. The foundation integration test guards the prefix.

Stage 1D-A adds `users.auth_version BIGINT NOT NULL DEFAULT 0` as the future durable revocation generation without altering login, session serialization, or the Redis repository. Existing accounts retain their credentials and existing sessions continue under 1C rules while no password-reset endpoint exists. Stage 1D-C must capture a version at authentication, reject versionless legacy sessions, and compare the captured version with PostgreSQL on every protected request. A successful reset will atomically increment the version with the password change and token consumption, making all earlier sessions unusable. Redis session lookup/deletion is only supplementary and requires a separate compatibility and migration check before changing repository type.

Stage 1D-C implements that check. The authenticated principal stores only the UUID and a captured `auth_version`; its principal name is the UUID string for Spring Session indexing. Every protected request rereads PostgreSQL and invalidates sessions with missing or stale versions, while retaining the verified/ACTIVE check. Spring Boot 4.1.1 and Spring Session 4.1.1 support `spring.session.data.redis.repository-type=indexed`; the same namespace and 30-minute inactivity timeout remain. Newly indexed sessions can be found and deleted after reset commit. Sessions written by the earlier default repository may lack an index and are not guaranteed to be found by cleanup; their versionless principals are rejected on use. Redis deletion failure cannot restore access because the database version is authoritative. Requests that passed authorization before reset commit may finish; requests beginning after commit are rejected. No Redis data or developer database is reset during the transition.

Stage 1D-D live verification confirmed old authenticated cookies receive `401` after password reset. The subsequent regression investigation established the indexed repository's logout behavior: `deleteById` removes the expiry shadow and principal index, sets the primary Redis session hash's `maxInactiveInterval` to zero, and retains that hash for up to five minutes for session-destruction processing. The hash still contains serialized session attributes, including the former security context, but `findById` treats it as expired. Logout clears the browser cookie and security context; replaying the old cookie receives `401`. This matches the Spring Session 4.1.1 [indexed repository behavior](https://github.com/spring-projects/spring-session/blob/4.1.1/spring-session-data-redis/src/main/java/org/springframework/session/data/redis/RedisIndexedSessionRepository.java) and [documented retention window](https://docs.spring.io/spring-session/reference/api.html). The live test now checks server-side expiry, shadow/index cleanup, cookie removal, and replay rejection instead of demanding immediate hash deletion. See the [1D-D verification note](../implementation/milestone-1d-d-angular-password-recovery.md).

Test CSRF rejection/renewal, login session establishment, cookie configuration, logout invalidation, expiry, and safe behavior when Redis is unavailable. Session configuration arrives in the foundation; account workflows do not.

See [architecture](../02-system-architecture.md), [API design](../04-api-design.md), and [Spring Security SPA CSRF guidance](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
