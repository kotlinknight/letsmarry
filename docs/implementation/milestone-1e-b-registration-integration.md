# Stage 1E-B — Registration integration and account navigation

## Scope and API contract

The existing standalone `/register` form now creates a local/test account through `POST /api/v1/auth/register`. The feature-local `RegistrationApiService` mirrors the implemented [OpenAPI operation](../api/openapi.yaml); it uses Angular HttpClient and a relative `/api/v1` path through the existing development or NGINX proxy. No backend endpoint, migration, dependency, or public signup gate changed. The backend still requires the `local` or `test` profile with `app.registration.enabled=true` and `app.email-outbox.enabled=true`.

The form normalizes display name by trimming and email by trimming/lowercasing, then sends **only** `displayName`, `email`, and `password`. Confirmation stays in Angular. The password is never trimmed, lowercased, logged, or stored in browser persistence. Shared validation enforces the NFC-normalized 15–128 Unicode-code-point policy, while the backend remains authoritative for edge cases. The service first calls `GET /api/v1/auth/csrf` with browser credentials, then sends the returned `X-CSRF-TOKEN` header and browser-managed anonymous `SESSION` cookie on the POST. It does not cache a token or replay a failed POST automatically.

## Submission and result states

The Create account button is disabled for an invalid form or pending request. A synchronous pending-state guard prevents a second request even if submit is invoked directly. Invalid fields are announced and focused. Only HTTP `201` with the expected unverified-user response shows **Check your email**. Success clears both password controls, hides the form, and explains that verification is required before sign-in. It notes that local mail reaches Mailpit when the worker is enabled. Links lead to `/login` and the existing `/verify-email` resend screen. Registration does not call login, set an Angular user, or create wedding state.

RFC 9457 Problem Details drive feedback: `422` field errors are shown beside recognized fields; `409` gives local/test duplicate-email guidance with sign-in, verification-resend, and password-recovery links; `403` distinguishes `CSRF_INVALID` from another security rejection; `404` replaces the form with a registration-unavailable state; `429` advises a later retry without inventing a wait time. Network failures, `5xx`, and unexpected success responses are treated as **unconfirmed**, with a safe path to check mail or request a verification link before resubmitting. Values remain available after errors, so a user can correct them deliberately. The existing duplicate-email response is still unsuitable for public signup without an account-enumeration review.

The login page now links to registration, and successful email verification links back to login. The pre-existing forgot-password, reset-success, account, and logout routes remain intact. Account registration is separate from Figma's wedding-creation onboarding.

## Verification and limits

From `frontend/` run:

```powershell
npm.cmd run build
npm.cmd test -- --watch=false
npm.cmd run lint
npm.cmd run format:check
npm.cmd run test:e2e
```

Unit tests cover client validation, CSRF and credential options, exact request body, pending guard, `201` and password cleanup, field errors, duplicate email, CSRF retry, unavailable/rate-limit/network outcomes, and no automatic login. Fixture-based Chromium journeys cover submission, success, invalid feedback, duplicate guidance, deliberate CSRF retry, account links, and mobile Dark mode. These fixtures do not prove the complete live backend/Mailpit journey. Stage 1E-C owns real-service end-to-end account acceptance. Production signup, registration abuse controls, account-enumeration-safe public responses, and wedding onboarding remain deferred.

Verification on 2026-10-17: the Angular production build, ESLint, and Prettier check passed. All **67 unit tests in 11 files** passed. The full fixture browser suite passed twice with **13 enabled journeys and 3 opt-in real-service journeys skipped**, including a run with the final navigation assertion. The OpenAPI YAML parsed and its registration request/response references and documented status codes matched the existing backend contract. Changed Markdown links and Git whitespace checks passed. No backend test or live registration journey was run, because no backend behavior changed and the complete real-service journey is reserved for 1E-C. No development database or Docker volume was changed.
