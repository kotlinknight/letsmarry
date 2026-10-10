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
- Bootstrap a CSRF token and obtain a fresh token after login/logout. Final cookie/header names and endpoint details belong in the account milestone's OpenAPI contract.
- Do not issue planner access/refresh JWTs or implement `/auth/refresh`.
- Resolve current wedding membership and permissions on the backend; identity itself has no wedding-specific roles.
- Guest invitation exchange and its narrow bearer credentials remain separate. A planner cookie is not a substitute for guest-scope authorization.

## Consequences and verification

Redis availability affects authenticated planner requests. Lost/expired sessions require reauthentication; PostgreSQL remains the durable source of business records. Exact session timeout and account-security invalidation rules must be specified before account implementation.

Test CSRF rejection/renewal, login session establishment, cookie configuration, logout invalidation, expiry, and safe behavior when Redis is unavailable. Session configuration arrives in the foundation; account workflows do not.

See [architecture](../02-system-architecture.md), [API design](../04-api-design.md), and [Spring Security SPA CSRF guidance](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
