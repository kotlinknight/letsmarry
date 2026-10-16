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

Test CSRF rejection/renewal, login session establishment, cookie configuration, logout invalidation, expiry, and safe behavior when Redis is unavailable. Session configuration arrives in the foundation; account workflows do not.

See [architecture](../02-system-architecture.md), [API design](../04-api-design.md), and [Spring Security SPA CSRF guidance](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
