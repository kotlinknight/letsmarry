# Make My Marriage — REST API Design

**Status:** MVP endpoint contract baseline  
**Version:** 1.0  
**Last updated:** 2026-10-16

**Base path:** `/api/v1`  
**Backend:** Spring Boot + Spring Security  
**Frontend:** Angular + generated/typed OpenAPI client  
**Format:** JSON, RFC 9457 Problem Details for errors

> This is the broader MVP API **design document**. The implemented account endpoints through Stage 1D-C have a machine-readable [OpenAPI 3.0.3 contract](api/openapi.yaml); other catalog entries remain plans, not deployed endpoints.

## 1. API principles

1. Resource-oriented REST; plural, lowercase, kebab-case path segments.
2. Wedding-scoped endpoints carry `/weddings/{weddingId}`.
3. No cross-wedding access: verify membership, permission **and** resource ownership.
4. Separate `/public` guest/website endpoints with minimum necessary data.
5. Stable request/response DTOs; never serialize JPA entities directly.
6. Version breaking changes in URL (`/api/v1`, later `/api/v2`).
7. `GET` paginated for potentially large collections; explicit filtering/search/sorting.
8. Domain commands such as `complete`, `publish` or `resend` may use `POST`.
9. `PATCH` for partial updates; absent fields unchanged, explicit `null` clears when allowed.
10. Idempotency for money or invitation commands with significant side effects.
11. Conditional updates with `ETag`/`If-Match` where concurrent edits matter.
12. Use OpenAPI to validate/generate Angular client types.

## 2. Base URLs and conventions

| Environment | Example |
|---|---|
| Local | `http://localhost:8080/api/v1` |
| Staging | `https://staging.makemymarriage.com/api/v1` (illustrative application origin) |
| Production | `https://makemymarriage.com/api/v1` (illustrative application origin) |

Angular uses relative `/api/v1` URLs through a development proxy locally and a same-origin reverse proxy when deployed. The hostnames above are examples, not selected deployment domains.

JSON requests send `Content-Type: application/json`, responses `application/json`, errors `application/problem+json`.

**IDs:** UUIDv7 serialized as strings.  
**Calendar date:** `YYYY-MM-DD`.  
**Timestamp:** ISO-8601 with offset; store instants consistently.  
**Timezone:** IANA name, e.g. `Asia/Kolkata`.  
**Enums:** uppercase snake-case, e.g. `IN_PROGRESS`.  
**Monetary values:** decimal strings and explicit currency:

```json
{ "amount": "150000.00", "currency": "INR" }
```

## 3. Authentication and tokens

### Private planner endpoints

```http
Cookie: SESSION=<opaque-session-id>
```

- Spring Session stores planner sessions in Redis. The browser sends an opaque HttpOnly session cookie; Angular cannot read the session credential.
- Use `Secure` cookies under deployed HTTPS, deliberate `SameSite` settings, trusted-origin checks, and CSRF protection for cookie-authenticated writes. Local HTTP exceptions are local-only.
- `GET /auth/csrf` creates an anonymous Redis-backed `SESSION` cookie and returns `{ "token": "...", "headerName": "X-CSRF-TOKEN" }` with `Cache-Control: no-store`. It is independent of registration/outbox gates. Send the cookie and header together for registration, verification, login, and logout. Fetch a fresh token after login or logout before another unsafe request.
- Login establishes an authenticated session. Stage 1C-B `/users/me` restores fresh identity from PostgreSQL after a page reload; logout invalidates the current Redis session. Expired, invalidated, or ineligible sessions return `401` without a JWT refresh loop.
- [ADR-007](adr/ADR-007-redis-sessions.md) is accepted. Planner access/refresh JWTs and `/auth/refresh` are removed from the design.

### Guest invitations

- A guest receives a high-entropy invitation token in the link `/i/:token`.
- Angular calls `POST /public/invitations/exchange` with that secret in the JSON **body**.
- API returns a short-lived narrowly scoped guest bearer token; it does **not** create a user account.
- Guest bearer token can only read the associated invitation and submit approved event-level RSVPs.
- Invitation secret is not logged or preserved in referrers/analytics.

## 4. Authentication endpoint catalog

| Method | Path | Purpose |
|---|---|---|
| GET | `/auth/csrf` | **Implemented 1A; made gate-independent in 1C-A:** anonymous CSRF/session bootstrap |
| POST | `/auth/register` | **Updated 1B-C:** local/test-only atomic user, verification token, and encrypted outbox job |
| POST | `/auth/login` | **Implemented 1C-A:** default-off gated login, Redis session rotation, safe user response |
| POST | `/auth/logout` | **Implemented 1C-B:** CSRF-protected current-session invalidation and cookie expiry |
| POST | `/auth/email/verify` | **Implemented 1B-C:** consume token and verify email |
| POST | `/auth/email/verification-requests` | **Implemented 1B-C:** generic resend request |
| POST | `/auth/password/forgot` | **Implemented 1D-B:** gated, generic reset-email request |
| POST | `/auth/password/reset` | **Implemented 1D-C:** consume token, change password, revoke prior sessions |
| GET | `/users/me` | **Implemented 1C-B:** fresh verified, ACTIVE account details |

Example register:

```http
POST /api/v1/auth/register
Content-Type: application/json

Cookie: SESSION=<anonymous-session-id>
X-CSRF-TOKEN: <token-from-GET-auth-csrf>

{"displayName":"Anjali","email":"anjali@example.com","password":"SecurePassword123!"}
```

Response `201 Created`:

```json
{
  "id": "<uuid>",
  "displayName": "Anjali",
  "email": "anjali@example.com",
  "emailVerified": false
}
```

Registration requires the `local` or `test` profile plus `app.registration.enabled=true` and `app.email-outbox.enabled=true`; the default flags are false. The gated endpoints are absent (`404`) when disabled. Registration trims the display name (1–150 characters) and lowercases/trims the email (valid address, at most 320 characters). The password is NFC-normalized without trimming, must contain 15–128 Unicode code points, and cannot contain control characters. No character-composition rule is imposed. The response has no `Location` header because there is no addressable resource for the newly registered user; `/users/me` requires login. Registration atomically creates an `ACTIVE` but unverified user, a 24-hour hash-only token, and an encrypted outbox job; it does not log in, call SMTP in the request transaction, or create a wedding. The opt-in local worker delivers through Mailpit later. `400` means malformed JSON, `422` invalid fields, `409` duplicate email, and `403` missing/invalid CSRF. Errors use RFC 9457 Problem Details with a stable `code`, server-generated `correlationId`, and matching `X-Correlation-ID` header. Duplicate-email `409` still needs an account-enumeration review before public signup.

`POST /api/v1/auth/email/verify` accepts `{ "token": "<opaque-token>" }`; valid, unexpired, unrevoked, unused tokens return `204` and atomically set `users.email_verified_at` plus consumption time. Account status remains `ACTIVE`; suspended accounts cannot verify. Invalid, expired, revoked, reused, and ineligible tokens all return generic `400 VERIFICATION_TOKEN_INVALID`. `POST /api/v1/auth/email/verification-requests` accepts `{ "email": "anjali@example.com" }`, normalizes the email, and returns an empty `202` for any syntactically valid address, whether eligible or not, including an email-specific rate limit. An eligible 1A account receives its first token; a later allowed request revokes its old token, purges the old pending job where possible, and enqueues a replacement atomically. A processing email may still arrive with an invalid link. Both POSTs require the anonymous `SESSION` cookie and CSRF header from `GET /auth/csrf`; neither authenticates the requester. Stage 1B-D adds a five-minute per-email resend cooldown, three resends per rolling day, a configurable direct-IP-wide resend ceiling (30/hour default), and a verification-attempt ceiling (20/hour default). IP-wide excess returns generic `429 VERIFICATION_RATE_LIMITED`; Redis failures return `503 VERIFICATION_TEMPORARILY_UNAVAILABLE` before touching tokens. Redis keys contain keyed HMAC digests, not plaintext addresses. See the [OpenAPI contract](api/openapi.yaml) and [1B-D note](implementation/milestone-1b-d-mailpit-integration.md).

Stage 1D-B `POST /api/v1/auth/password/forgot` accepts `{ "email": "anjali@example.com" }`, trims/lowercases the address, and requires the anonymous session and CSRF header. Valid addresses receive the same empty, no-store `202` whether the account is eligible, absent, unverified, suspended, deactivated, or subject to an email-specific limit. Only verified `ACTIVE` accounts get a 30-minute token and encrypted `PASSWORD_RESET` outbox job. The endpoint requires independent `app.password-recovery.enabled=true` and the existing outbox flag, plus `local` or `test` without `production`; otherwise it is absent. Initial configurable limits are one request per normalized email per five minutes, three per email per rolling day, and 30 per direct IP per hour. Email and IP Redis keys are HMAC-derived; direct-IP excess returns `429 PASSWORD_RECOVERY_RATE_LIMITED`, and Redis/storage failure returns `503 PASSWORD_RECOVERY_TEMPORARILY_UNAVAILABLE`. Malformed JSON returns `400`, invalid fields `422`, and missing CSRF `403`, all as Problem Details with correlation IDs. A configurable 250 ms response floor reduces obvious timing differences for `202` responses; no absolute timing equivalence is promised. Stage 1D-B does not change a password or session. See [OpenAPI](api/openapi.yaml) and the [stage note](implementation/milestone-1d-b-forgot-password.md).

Stage 1D-C `POST /api/v1/auth/password/reset` accepts `{ "token": "<opaque-token>", "newPassword": "<new-password>" }` under the same local/test recovery and outbox gates, independent of registration/login gates. It requires the anonymous session and CSRF header. Success returns empty no-store `204` without logging in. Invalid, expired, revoked, used, and ineligible tokens share `400 PASSWORD_RESET_TOKEN_INVALID`; invalid password policy returns `422 VALIDATION_ERROR`; missing CSRF returns `403`; direct-IP reset excess (20/hour default) returns `429 PASSWORD_RECOVERY_RATE_LIMITED`; unavailable Redis/storage returns `503 PASSWORD_RECOVERY_TEMPORARILY_UNAVAILABLE`. Malformed JSON returns `400 MALFORMED_JSON`. The password is NFC-normalized without trimming and encoded with the registration PBKDF2 encoder. Success atomically consumes the token, updates the hash, increments `auth_version`, and enqueues a `PASSWORD_CHANGED` notice. Every earlier authenticated session fails subsequent protected requests regardless of best-effort Redis cleanup. See [OpenAPI](api/openapi.yaml) and the [stage note](implementation/milestone-1d-c-password-reset.md).

Stage 1D-D implements the browser clients for these existing contracts without changing HTTP fields or statuses. `/forgot-password` sends only the normalized email and displays generic `202` copy. `/reset-password` reads an opaque token from the Mailpit link fragment, removes the fragment from history, and sends `{ token, newPassword }` only after a valid, explicit submission. Both calls bootstrap a fresh session-bound CSRF token through `/auth/csrf`. The UI treats a definitive `PASSWORD_RESET_TOKEN_INVALID` as terminal, keeps network uncertainty distinct from confirmed success, and never automatically authenticates the user. See the [1D-D note](implementation/milestone-1d-d-angular-password-recovery.md).

Example login response:

```json
{
  "user": { "id": "<uuid>", "displayName": "Anjali", "email": "anjali@example.com", "emailVerified": true, "status": "ACTIVE" }
}
```

Login sets the HttpOnly session cookie through `Set-Cookie`; it does not return session credentials in JSON. Logout returns `204 No Content`, invalidates only the current Redis session, and expires its cookie. Both flows require CSRF handling. A repeated logout with a newly bootstrapped anonymous session and valid CSRF also returns `204`; replaying the old token returns `403`. Authentication examples show bodies/paths rather than every cookie or CSRF header.

Stage 1C-A `POST /api/v1/auth/login` accepts `{ "email": "anjali@example.com", "password": "..." }` with the existing anonymous `SESSION` cookie and `X-CSRF-TOKEN` header. The request normalizes email by trimming/lowercasing and password by NFC without trimming. An `ACTIVE` account with a verified email and matching PBKDF2 hash receives `200`, a rotated HttpOnly session cookie, `Cache-Control: no-store`, and `{ "user": { "id": "<uuid>", "displayName": "Anjali", "email": "anjali@example.com", "emailVerified": true, "status": "ACTIVE" } }`. Retrieve a new CSRF token before subsequent writes. Unknown email, wrong password, unverified email, suspended status, and deactivated status all return the same `401 INVALID_CREDENTIALS`; malformed JSON returns `400`, invalid request fields `422`, missing/invalid CSRF `403`, rate limiting `429` with `Retry-After`, and unavailable Redis rate limiting `503`. Errors use the existing Problem Details and correlation-ID conventions. The configurable initial limits are 10 attempts per normalized email and 60 per direct IP per 15 minutes. The authentication gate is off by default and independent of public registration. See [OpenAPI](api/openapi.yaml).

Stage 1C-B `GET /api/v1/users/me` requires an authenticated session and returns `{ "id": "<uuid>", "displayName": "Anjali", "email": "anjali@example.com", "emailVerified": true, "status": "ACTIVE" }` from PostgreSQL with `Cache-Control: no-store`. A request with an absent, expired, invalidated, or ineligible session receives `401 AUTHENTICATION_REQUIRED` Problem Details and a correlation ID. The account is rechecked on each protected request; a suspended, deactivated, unverified, or deleted account causes the current session to be invalidated. Redis or database unavailability returns `503 AUTHENTICATION_TEMPORARILY_UNAVAILABLE` and grants no access. `POST /api/v1/auth/logout` requires CSRF, returns empty `204`, clears the security context, invalidates the current session, and expires its cookie. Other browser sessions remain valid. See [OpenAPI](api/openapi.yaml) and the [1C-B note](implementation/milestone-1c-b-session-lifecycle.md).

## 5. HTTP responses and errors

| Code | Meaning |
|---|---|
| 200 | Successful read/update |
| 201 | New resource (prefer `Location` header) |
| 202 | Async operation accepted |
| 204 | Successful no-body operation |
| 400 | Malformed request or input structure |
| 401 | No/invalid authentication |
| 403 | Forbidden, including a missing or invalid CSRF token |
| 404 | Missing or undisclosable resource |
| 409 | Business state/conflict (e.g. owner slot taken) |
| 412 | `If-Match` precondition failed |
| 422 | Valid JSON, invalid business input |
| 429 | Rate limited; `Retry-After` header |
| 500 | Unexpected internal error |
| 503 | Temporary dependency unavailable |

Use RFC 9457 Problem Details and extension fields:

```json
{
  "type": "https://api.makemymarriage.com/problems/validation-error",
  "title": "Validation failed",
  "status": 422,
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/weddings",
  "code": "VALIDATION_ERROR",
  "correlationId": "c5ed...",
  "errors": [
    {"field":"primaryWeddingDate", "code":"INVALID_DATE", "message":"Enter a valid wedding date."}
  ]
}
```

Client logic should depend on stable `code` values, not natural-language `detail` text. Responses should return `X-Correlation-ID` and redact secret values from server logs.

## 6. Collection semantics

```http
GET /api/v1/weddings/{weddingId}/guests?page=0&size=25&sort=fullName,asc&q=rahul
```

Default `page` is 0; recommended maximum `size` is 100. Sorting must be whitelisted.

```json
{
  "items": [],
  "page": { "number": 0, "size": 25, "totalElements": 327, "totalPages": 14 }
}
```

Use `q` for full-text/fuzzy search where supported. Avoid generic SQL-like user-provided filter expressions.

## 7. Concurrency and idempotency

Versioned GET/PATCH responses can use:

```http
ETag: "12"
```

Client update:

```http
PATCH /api/v1/weddings/{weddingId}/expenses/{expenseId}
If-Match: "12"
```

If modified, return `412` with `RESOURCE_MODIFIED`. For important non-idempotent commands:

```http
Idempotency-Key: <client-generated-uuid>
```

Server must scope the key to user/wedding/endpoint, compare request hash, atomically lock or persist a result, and reject reuse with a conflicting payload. Payment recording especially requires durable/concurrency-safe deduplication.

## 8. Weddings and couple ownership

| Method | Path | Purpose |
|---|---|---|
| GET | `/weddings` | List current user's memberships/weddings |
| POST | `/weddings` | Create wedding and creator membership |
| GET | `/weddings/{weddingId}` | Read wedding |
| PATCH | `/weddings/{weddingId}` | Update wedding settings |
| POST | `/weddings/{weddingId}/complete` | Mark wedding completed |
| POST | `/weddings/{weddingId}/archive` | Archive wedding |
| GET | `/weddings/{weddingId}/couple` | Read bride/groom profiles and claim status |
| PATCH | `/weddings/{weddingId}/couple/{coupleRole}` | Edit couple profile |
| GET | `/weddings/{weddingId}/dashboard` | Permission-filtered aggregate summary |

Create wedding example:

```http
POST /api/v1/weddings
Content-Type: application/json
```

```json
{
  "name": "Arjun & Anjali Wedding",
  "primaryWeddingDate": "2027-02-15",
  "timezone": "Asia/Kolkata",
  "primaryLocation": "Thiruvananthapuram, Kerala",
  "creatorRelationship": "BRIDE",
  "couple": {
    "bride": { "displayName": "Anjali" },
    "groom": { "displayName": "Arjun" }
  }
}
```

`creatorRelationship`: `BRIDE`, `GROOM`, `FAMILY_MEMBER`, `FRIEND`.

**Create rules:**

- Verified creator self-declaring BRIDE/GROOM → corresponding owner role on creation of a new wedding.
- FAMILY_MEMBER/FRIEND → setup `ADMIN` and empty owner slots.
- Create two couple profiles even if account(s) are unlinked.
- Commit the wedding, both profiles, and initial membership synchronously in one PostgreSQL transaction inside the wedding module.
- Return `201 Created`, `Location: /api/v1/weddings/{id}`, and wedding/creator-membership summary.
- Partner owner invitations may be created during a separate optional step, before/after first dashboard access.

Example dashboard:

```json
{
  "countdown": { "weddingDate": "2027-02-15", "daysRemaining": 128 },
  "tasks": { "total": 74, "completed": 42, "overdue": 5 },
  "guests": { "invited": 412, "attending": 289, "declined": 31, "maybe": 7, "awaitingResponse": 85 },
  "finance": {
    "visible": true,
    "budget": {"amount":"1800000.00","currency":"INR"},
    "committed": {"amount":"1240000.00","currency":"INR"},
    "paid": {"amount":"970000.00","currency":"INR"}
  },
  "upcomingEvents": [],
  "upcomingTasks": [],
  "upcomingPayments": []
}
```

**Finance visibility:** if member cannot view finance, omit its figures and return `finance: {"visible":false}` (or omit object entirely; choose one OpenAPI contract and test it). Financial activity cannot appear in unauthorized summary lists.

## 9. Membership and permissions

| Method | Path | Purpose |
|---|---|---|
| GET | `/weddings/{weddingId}/members` | List members |
| GET | `/weddings/{weddingId}/members/{memberId}` | Read member |
| PATCH | `/weddings/{weddingId}/members/{memberId}` | Update eligible role/status |
| DELETE | `/weddings/{weddingId}/members/{memberId}` | Remove eligible non-owner |
| GET | `/permissions` | Global capability catalog |
| GET | `/weddings/{weddingId}/members/{memberId}/permissions` | Effective permissions |
| PUT | `/weddings/{weddingId}/members/{memberId}/permission-overrides` | Replace explicit overrides |
| POST | `/weddings/{weddingId}/member-invitations` | Invite member/owner |
| GET | `/weddings/{weddingId}/member-invitations` | List invitations |
| POST | `/weddings/{weddingId}/member-invitations/{id}/resend` | Resend pending invite |
| DELETE | `/weddings/{weddingId}/member-invitations/{id}` | Cancel pending invite |
| POST | `/member-invitations/accept` | Accept token as authenticated intended user |

Sample owner invitation:

```json
{ "email": "arjun@example.com", "targetRole": "OWNER", "coupleRole": "GROOM" }
```

Sample family invitation:

```json
{ "email": "priya@example.com", "targetRole": "FAMILY_MEMBER" }
```

Acceptance:

```http
POST /api/v1/member-invitations/accept
Cookie: SESSION=<opaque-session-id>
X-XSRF-TOKEN: <csrf-token>
Content-Type: application/json

{"token":"<raw-member-invitation-token>"}
```

Cookie/header names above are illustrative pending the account OpenAPI contract. Acceptance verifies the session, CSRF, invitation hash, expiry, email ownership and target role/slot, links couple profile where applicable, creates active membership and marks invitation used atomically. Unauthorized people cannot assign themselves OWNER or demote an owner via normal member APIs.

## 10. Events

| Method | Path |
|---|---|
| GET | `/weddings/{weddingId}/events` |
| POST | `/weddings/{weddingId}/events` |
| GET | `/weddings/{weddingId}/events/{eventId}` |
| PATCH | `/weddings/{weddingId}/events/{eventId}` |
| DELETE | `/weddings/{weddingId}/events/{eventId}` |

Example POST:

```json
{
  "name": "Wedding Ceremony",
  "description": "Main ceremony",
  "startsAt": "2027-02-15T10:30:00+05:30",
  "endsAt": "2027-02-15T12:00:00+05:30",
  "timezone": "Asia/Kolkata",
  "venueName": "Example Convention Centre",
  "venueAddress": "Thiruvananthapuram, Kerala",
  "dressCode": "Traditional",
  "publishOnWebsite": true
}
```

Permissions: `MANAGE_EVENTS` for mutating; active wedding membership for appropriate read. DELETE usually soft-deletes and must handle dependent assignments intentionally.

## 11. Tasks

| Method | Path | Purpose |
|---|---|---|
| GET | `/weddings/{weddingId}/tasks` | Filters: status, priority, assigneeId, eventId, dueBefore, dueAfter, q |
| POST | `/weddings/{weddingId}/tasks` | Create task |
| GET | `/weddings/{weddingId}/tasks/{taskId}` | Read, if visible |
| PATCH | `/weddings/{weddingId}/tasks/{taskId}` | Update |
| DELETE | `/weddings/{weddingId}/tasks/{taskId}` | Soft delete |
| POST | `/weddings/{weddingId}/tasks/{taskId}/complete` | Mark completed |
| POST | `/weddings/{weddingId}/tasks/{taskId}/reopen` | Reopen task |

Example:

```json
{
  "title": "Finalize photographer",
  "description": "Confirm package and dates.",
  "eventId": "<uuid>",
  "priority": "HIGH",
  "dueAt": "2027-01-15T18:00:00+05:30",
  "assigneeIds": ["<member-uuid>"],
  "visibility": "SELECTED_MEMBERS",
  "visibleMemberIds": ["<member-uuid>"]
}
```

`GET /tasks?assignedTo=me` is an additional convenience filter. Private tasks must not leak in list counts/activity.

## 12. Households, guests and event assignment

| Method | Path | Purpose |
|---|---|---|
| GET, POST | `/weddings/{weddingId}/households` | List/create households |
| PATCH, DELETE | `/weddings/{weddingId}/households/{householdId}` | Update/remove |
| GET, POST | `/weddings/{weddingId}/guests` | List/create guests |
| GET, PATCH, DELETE | `/weddings/{weddingId}/guests/{guestId}` | Read/update/remove |
| GET | `/weddings/{weddingId}/events/{eventId}/guests` | Event guest list |
| POST | `/weddings/{weddingId}/events/{eventId}/guest-assignments` | Add guest(s) to event |
| DELETE | `/weddings/{weddingId}/events/{eventId}/guest-assignments/{guestId}` | Remove assignment |

Example guest:

```json
{
  "fullName": "Rahul Sharma",
  "email": "rahul@example.com",
  "phone": "+919876543210",
  "householdId": "<uuid>",
  "side": "GROOM",
  "relationship": "College Friend"
}
```

Example event assignment:

```json
{ "guestIds": ["<uuid-1>","<uuid-2>"] }
```

Guest filters: `q`, `side`, `householdId`, `eventId`, `rsvpStatus`. Bulk assignment must be idempotent for guests already assigned and reject cross-wedding IDs.

## 13. Guest invitation and RSVP

### Organizer endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/weddings/{weddingId}/guest-invitations` | Create tokenized invitation for guest IDs |
| GET | `/weddings/{weddingId}/guest-invitations` | List invitations (safe DTO, never raw token) |
| POST | `/weddings/{weddingId}/guest-invitations/{invitationId}/send` | Queue invitation email; `202` |
| DELETE | `/weddings/{weddingId}/guest-invitations/{invitationId}` | Revoke invitation |
| GET | `/weddings/{weddingId}/rsvps/summary?eventId=...` | Internal RSVP summary |

Example invitation create:

```json
{ "guestIds": ["<uuid-1>","<uuid-2>"] }
```

Creation response may return one-time `shareUrl` to authorized organizer. **Never subsequently reveal the raw token** from ordinary list APIs; if regeneration is required, rotate/revoke and issue a new invitation.

Queued email may retain sensitive link material only as temporary encrypted outbox data, purged after delivery or expiry. A later send/resend cannot reconstruct a token from its hash; specify fresh issue/rotation and prior-link behavior before implementing that command. See [ADR-009](adr/ADR-009-email-outbox.md).

### Guest/public endpoints

| Method | Path | Authorization |
|---|---|---|
| POST | `/public/invitations/exchange` | Raw invitation token in POST JSON body |
| GET | `/public/invitation` | Guest bearer token |
| PUT | `/public/rsvps/{eventGuestId}` | Guest bearer token scoped to eligible guest/event |

Token exchange:

```json
{ "token": "<raw-invitation-token>" }
```

Response:

```json
{ "guestAccessToken": "<short-lived-guest-jwt>", "expiresIn": 1800 }
```

RSVP:

```http
PUT /api/v1/public/rsvps/{eventGuestId}
Authorization: Bearer <guest-access-token>
Content-Type: application/json

{"status":"ATTENDING","message":"Looking forward to it!"}
```

Statuses: `ATTENDING`, `NOT_ATTENDING`, `MAYBE`. Missing RSVP = `NO_RESPONSE`. Scope restricts recipient identities and eventGuest IDs; guest sees no internal household/finance/team data beyond intended invitation information.

## 14. Vendors

| Method | Path |
|---|---|
| GET, POST | `/weddings/{weddingId}/vendors` |
| GET, PATCH, DELETE | `/weddings/{weddingId}/vendors/{vendorId}` |

Example POST:

```json
{
  "name": "Dream Frames Photography",
  "category": "PHOTOGRAPHY",
  "contactPerson": "Rahul",
  "phone": "+91...",
  "quotedAmount": {"amount":"175000.00","currency":"INR"},
  "agreedAmount": {"amount":"150000.00","currency":"INR"},
  "eventIds": ["<event-uuid>"]
}
```

## 15. Finance: budget, categories, expenses, payments

All endpoints require explicit `VIEW_FINANCE` for reads and/or `MANAGE_FINANCE` for writes.

| Method | Path | Purpose |
|---|---|---|
| GET | `/weddings/{weddingId}/finance/summary` | Budget/committed/paid/outstanding summaries |
| GET | `/weddings/{weddingId}/budget` | Get singleton budget |
| PUT | `/weddings/{weddingId}/budget` | Create/replace singleton budget |
| GET, POST | `/weddings/{weddingId}/budget-categories` | List/create categories |
| PATCH, DELETE | `/weddings/{weddingId}/budget-categories/{categoryId}` | Update/delete category |
| GET, POST | `/weddings/{weddingId}/expenses` | List/create expenses |
| GET, PATCH, DELETE | `/weddings/{weddingId}/expenses/{expenseId}` | Read/update/delete expense |
| GET, POST | `/weddings/{weddingId}/expenses/{expenseId}/milestones` | Payment schedule |
| PATCH, DELETE | `/weddings/{weddingId}/expenses/{expenseId}/milestones/{milestoneId}` | Edit milestone |
| GET, POST | `/weddings/{weddingId}/expenses/{expenseId}/payments` | List/record actual payment |
| PATCH, DELETE | `/weddings/{weddingId}/expenses/{expenseId}/payments/{paymentId}` | Correct/delete payment with audit |
| GET | `/weddings/{weddingId}/payments/upcoming` | Upcoming milestone obligations |

Example expense:

```json
{
  "name": "Photography Package",
  "categoryId": "<uuid>",
  "vendorId": "<uuid>",
  "eventId": "<uuid>",
  "totalAmount": {"amount":"150000.00","currency":"INR"},
  "expenseDate": "2027-01-01"
}
```

Example milestone:

```json
{
  "name": "Advance",
  "amountDue": {"amount":"30000.00","currency":"INR"},
  "dueDate": "2027-01-01"
}
```

Payment:

```http
POST /api/v1/weddings/{weddingId}/expenses/{expenseId}/payments
Idempotency-Key: <uuid>
Content-Type: application/json
```

```json
{
  "paymentMilestoneId": "<uuid>",
  "amount": {"amount":"30000.00","currency":"INR"},
  "paidAt": "2027-01-01T10:30:00+05:30",
  "paymentMethod": "BANK_TRANSFER",
  "reference": "TXN12345"
}
```

Response `201 Created`. Derived status/paid/outstanding is read from records, not duplicated in `expenses`.

## 16. Private document upload/download

| Method | Path | Purpose |
|---|---|---|
| POST | `/weddings/{weddingId}/documents/upload-requests` | Request short-lived signed object upload |
| POST | `/weddings/{weddingId}/documents` | Confirm upload and associate |
| GET | `/weddings/{weddingId}/documents` | List authorized files |
| GET | `/weddings/{weddingId}/documents/{documentId}` | File metadata |
| DELETE | `/weddings/{weddingId}/documents/{documentId}` | Soft delete/mark unavailable |
| POST | `/weddings/{weddingId}/documents/{documentId}/download-url` | Short-lived private download URL |

Upload intent example:

```json
{
  "fileName": "photography-contract.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 284919
}
```

Response contains `uploadId`, `uploadUrl` and `expiresAt`. Do **not** rely on a client-supplied storage key to authorize a file. Confirm request's ID, uploaded object identity, size/type and same-wedding parent association server-side before recording document metadata.

Confirm upload:

```json
{ "uploadId": "<uuid>", "association": {"type":"VENDOR","id":"<vendor-uuid>"} }
```

## 17. Gifts and return gifts

| Method | Path |
|---|---|
| GET, POST | `/weddings/{weddingId}/gifts` |
| GET, PATCH, DELETE | `/weddings/{weddingId}/gifts/{giftId}` |
| GET, POST | `/weddings/{weddingId}/return-gifts` |
| PATCH, DELETE | `/weddings/{weddingId}/return-gifts/{itemId}` |
| POST | `/weddings/{weddingId}/return-gifts/{itemId}/distributions` |

Gift privacy is separate from ordinary guest visibility. Return-gift stock remaining is derived from distributions rather than maintained as a second free-running counter.

## 18. Seating

| Method | Path |
|---|---|
| GET, POST | `/weddings/{weddingId}/events/{eventId}/seating-tables` |
| PATCH, DELETE | `/weddings/{weddingId}/events/{eventId}/seating-tables/{tableId}` |
| PUT | `/weddings/{weddingId}/events/{eventId}/guests/{guestId}/seat` |
| DELETE | `/weddings/{weddingId}/events/{eventId}/guests/{guestId}/seat` |
| GET | `/weddings/{weddingId}/events/{eventId}/seating/unassigned-guests` |

Seat assignment `PUT`:

```json
{ "tableId": "<uuid>", "seatLabel": null }
```

Must validate event, guest eligibility, same-wedding table, unique assignment, and table capacity atomically. Default unassigned listing may filter to `ATTENDING` RSVPs.

## 19. Website, media and livestream

| Method | Path | Purpose |
|---|---|---|
| GET | `/weddings/{weddingId}/website` | Private site configuration |
| PATCH | `/weddings/{weddingId}/website` | Set slug, visibility, sections, theme |
| POST | `/weddings/{weddingId}/website/publish` | Publish |
| POST | `/weddings/{weddingId}/website/unpublish` | Unpublish |
| GET | `/public/weddings/{slug}` | Public/unlisted curated wedding site |
| POST | `/weddings/{weddingId}/media/upload-requests` | Signed upload intent |
| POST | `/weddings/{weddingId}/media` | Confirm media upload |
| GET | `/weddings/{weddingId}/media` | List private media |
| PATCH, DELETE | `/weddings/{weddingId}/media/{mediaId}` | Update/remove media |
| GET, PUT, DELETE | `/weddings/{weddingId}/events/{eventId}/livestream` | Approved external video link |

Wedding website config example:

```json
{
  "slug": "arjun-and-anjali",
  "visibility": "PUBLIC",
  "headline": "We're getting married!",
  "coupleMessage": "...",
  "themeConfig": {},
  "layoutConfig": {}
}
```

The public DTO excludes guest manifests, expenses, team/member emails, private media, contracts, documents and tasks. Publishing is a domain command, not simply setting arbitrary internal DB flags.

## 20. Activity, audit and notifications

| Method | Path | Purpose |
|---|---|---|
| GET | `/weddings/{weddingId}/activity` | Permission-filtered human-readable activity |
| GET | `/weddings/{weddingId}/audit-logs` | Authorized sensitive audit history |
| POST | `/weddings/{weddingId}/member-invitations/{id}/resend` | Requeue invitation email |
| POST | `/weddings/{weddingId}/guest-invitations/{id}/send` | Queue guest email (`202`) |

No public CRUD operations for audit logs, and **no** generic `POST /send-email` endpoint. Email must be triggered by domain actions and stored as durable work.

Audit filters: `actorUserId`, `action`, `entityType`, `from`, `to`. Audit read access is narrower than basic membership and responses must respect private finance/task visibility.

## 21. Validation and tenant-security requirements

Every private wedding-scoped request follows:

```text
Authenticate user
  → Resolve active wedding membership
  → Check functional permission
  → Check resource wedding_id association
  → Check item-level visibility rules
  → Perform operation / emit audit
```

Examples of required validation:

- Wedding event end time does not precede start time.
- Payment amount is positive and currency consistent.
- Vendor/event/category IDs must belong to the same wedding.
- Owner invitation targets a legitimate vacant/recoverable BRIDE/GROOM slot.
- Guest cannot RSVP for an event missing from their invitation.
- Table capacity cannot be exceeded by simultaneous seat requests.
- Private task listing and activity feed cannot reveal existence to unauthorized members.
- Email verification/password reset failures do not expose whether an account exists.
- Uploaded document parent associations remain wedding-scoped.

Angular form validation improves UX; the backend remains authoritative.

## 22. Rate limiting and security headers

Redis rate limiting on `login`, password recovery, account verification, invitation exchange, RSVP, member invitation creation and email send operations.

Example limit response:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/problem+json
```

CORS, if needed, must permit only explicit application origins; same-origin planner/API access is the baseline. Session cookies require HTTPS in deployment, deliberate SameSite settings, and CSRF/origin protection. Avoid raw token printing in browser/app logs.

## 23. Angular API consumption

```text
Feature component
  → Feature facade/store (Signals/RxJS)
  → Generated API client
  → Angular HTTP interceptors
  → /api/v1
```

Planner HTTP infrastructure handles CSRF, session-expiry responses, correlation ID and Problem Details. Guest bearer attachment is restricted to the guest invitation/RSVP API. Components should not embed raw backend URLs or read session credentials; there is no planner refresh-token mechanism.

Stage 1C-C consumes the four implemented authentication endpoints from Angular: `/auth/csrf`, `/auth/login`, `/users/me`, and `/auth/logout`. The frontend fetches JSON CSRF state before each unsafe authentication request and sends the named header with the same browser session. It never replays a failed POST automatically. The guard awaits `/users/me` before displaying `/account`; `401` means sign-in is required, while a network or `503` failure leaves authentication unconfirmed and hides prior account details. After a failed logout, `/users/me` distinguishes a still-live session from confirmed invalidation. The hand-maintained DTOs currently mirror this OpenAPI contract because no pinned generator is installed; see the [1C-C note](implementation/milestone-1c-c-angular-authentication.md).

## 24. Testing checklist

- [ ] Register, verify, login, restore identity through `/users/me`, logout and reset password.
- [ ] CSRF protection/renewal, session expiry/revocation, and cookie configuration work correctly.
- [ ] Bride creates wedding → BRIDE owner, groom accepts invite → GROOM owner.
- [ ] Family creates wedding → setup ADMIN, both partners independently become OWNER.
- [ ] Family member without finance permission → `403` on expense read.
- [ ] Wedding A member requesting Wedding B entity → no disclosure.
- [ ] Invitation exchange limits guest access to explicitly included events.
- [ ] Multiple RSVP submissions update one current RSVP reliably.
- [ ] Payment POST retried with same idempotency key → one payment.
- [ ] Concurrent expense edits return `412` appropriately.
- [ ] Over-capacity seating fails without corrupting assignments.
- [ ] Expired/revoked token cannot open private data.
- [ ] File upload/download verifies owner, wedding, size, type and expiry.
- [ ] Public website responds only when published and filters private data.
- [ ] Audit records sensitive changes; logs contain no secrets.
- [ ] Flyway/schema verification and OpenAPI contract checks pass in CI.

## 25. Implementation handoff and unresolved decisions

The first business slice now has `docs/api/openapi.yaml` (OpenAPI 3.0.3) with registration and CSRF contracts. Expand it per feature. Resolve the following before the corresponding feature's code generation or implementation:

- Deployment trusted-origin/proxy configuration for cookie-authenticated planner requests; local same-origin access and CSRF/session lifecycle are implemented through 1C-B.
- Exact default role permission matrix.
- Invitation expiration/response cutoff and guest access revocation behavior.
- Payment refunds/overpayments and idempotency persistence.
- Version requirement/ETag coverage for each mutable resource.
- Archive-mode mutability and owner transfer/recovery semantics.
- Upload quotas and website/public privacy defaults.

---

**Related documentation:** [Product Requirements](01-product-requirements.md) · [System Architecture](02-system-architecture.md) · [Database Design](03-database-design.md)
