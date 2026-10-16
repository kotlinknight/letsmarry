# Stage 1C-C — Angular authentication UI

## Scope and design source

This stage connects the existing Angular shell to the Stage 1C-A/B backend contracts. It adds sign-in and a temporary account landing page, without implementing registration, password recovery, weddings, or deployment. The connected Figma file `aLnG4EylloYZJOnrCXSpxT` was inspected across Brand & Direction, Foundations, Controls, Patterns, App Screens (desktop and mobile onboarding/dashboard), and Prototypes. There is no dedicated login screen. The new pages reuse its `mmm.` wordmark, Inter typography, 44-pixel field pattern, rounded controls, coral action, lilac accents, warm canvas, responsive editorial layout, and Light/Dark semantic variables already mapped in `frontend/src/styles/_tokens.scss`.

## Routes and code

- `/login`: `frontend/src/app/features/auth/login.component.*` has a Reactive Form, required/email validation, password visibility, loading state, generic failures, and a link to the existing verification page. It does not link to unimplemented registration or reset routes.
- `/account`: `frontend/src/app/features/auth/account.component.*` shows safe identity fields and logout. It contains no wedding data. The page rechecks the session when the window regains focus; a failed check hides account fields.
- `frontend/src/app/core/api/auth-api.service.ts`: typed same-origin calls to `GET /auth/csrf`, `POST /auth/login`, `GET /users/me`, and `POST /auth/logout`, always sending browser cookies. `core/api/csrf.service.ts` validates the JSON bootstrap shape.
- `frontend/src/app/core/auth/auth.service.ts`: Signals hold `unknown`, `loading`, `authenticated`, `unauthenticated`, or `unavailable` state and the safe current user. No credentials, token, or session ID enter localStorage or sessionStorage. `auth.guard.ts` waits for `/users/me` before rendering `/account` and redirects to `/login` when the server cannot authorize it. Backend authorization remains authoritative.

The login response updates UI state immediately. Application startup calls `/users/me` in the background even on a public route, and protected navigation calls it again before showing `/account`. If sign-in starts while the startup check is pending, it waits for that check to settle so stale restoration cannot overwrite a new login. A `401` clears the user and asks for sign-in; network, server, or malformed responses clear the user and mark the session unconfirmed. The only protected destination in this stage is `/account`, so the sign-in page navigates there after success and ignores arbitrary `next` query values.

## CSRF and logout

Each unsafe authentication call obtains a fresh session-bound token from `GET /auth/csrf` and sends its returned header on the POST with `withCredentials: true`. The token is used only in the request pipeline and is not cached or persisted. This also renews CSRF state after login's session rotation. A `403` displays a security-session error; the frontend does **not** automatically retry the POST. The existing email-verification service also continues to bootstrap each POST.

Logout accepts only the backend's `204` as direct confirmation. If the logout request fails or its response is lost, the service calls `/users/me`: `401` confirms there is no authenticated session, `200` preserves the current user and reports that sign-out was not confirmed, and an unavailable check clears visible user data while reporting uncertainty. The account page does not claim server-side revocation in the uncertain case.

## API typing and security

The request/response DTOs mirror `docs/api/openapi.yaml` (version `1.0.0-1c-b`). There is no pinned OpenAPI code generator in the current frontend toolchain, so Stage 1C-C keeps these small hand-maintained types inside `core/api`, separately from feature components. A future approved generator should output into an isolated generated directory; its output should not be hand-edited. Runtime checks reject unexpected user status or verification values despite TypeScript types. HttpOnly cookies are never read by Angular. The development proxy and deployed NGINX proxy keep `/api/v1` same-origin.

The backend authentication flag remains disabled by default. A verified, ACTIVE account and enabled local authentication gate are needed to use the UI. Registration and verification remain local/test-only. The login response is generic for invalid credentials, ineligible accounts, and unverified accounts; the page links to the existing resend flow without disclosing account standing. Production rollout, trusted-origin settings, full account recovery, and the registration-to-Mailpit-to-login journey remain deferred.

## Verification

From `frontend/`:

```powershell
npm.cmd run build
npm.cmd test -- --watch=false
npm.cmd run lint
npm.cmd run format:check
npm.cmd run test:e2e -- --workers=2
```

Unit tests exercise form validation, safe login response, generic failures, loading, rate limiting, CSRF rejection and renewal, `/me` restoration and expiry, guard decisions, and confirmed versus unconfirmed logout. Playwright uses controlled API fixtures for login, protected navigation, refresh, expiry, logout, mobile validation, and themes; it does not bypass production authentication. The full real-browser backend/Mailpit journey is Stage 1C-D.

Local verification on 2026-10-16: production Angular build passed; all 32 Vitest tests passed; Angular lint and Prettier checks passed; all 7 Playwright Chromium journeys passed with two workers. The OpenAPI 3.0.3 auth paths and required DTO fields were checked against the client, changed-document links resolved, and `git diff --check` passed. The browser journeys use controlled fixtures rather than a live backend; no real registration-to-login flow is claimed for this stage.
