# Make My Marriage frontend

This directory contains the Angular foundation from Milestones 0C and 0D, the Stage 1B-C email-verification confirmation page, the Stage 1C-C authentication UI, and the Stage 1D-D password-recovery pages. It provides a strict standalone application, lazy routing, a responsive application shell, reviewed Figma design tokens, persistent Light/Dark themes, automated lint and browser checks, and a production-style container image. Wedding creation and dashboard business data are not implemented.

## Requirements

- Node.js 22.23.2
- npm 11.21.0

Angular 22.2.2 requires Node `^22.22.3`, `^24.15.0`, or `^26.0.0`, TypeScript `>=6.0 <6.1`, and a supported RxJS 6/7 release. The exact project versions are pinned in `package.json` and `package-lock.json`.

The machine's bundled npm 10.9.8 crashes while resolving Vitest's optional peer graph. Use the pinned npm release for dependency installation:

```powershell
npx.cmd npm@11.21.0 ci
```

On macOS or Linux, omit the `.cmd` suffix.

## Run locally

```powershell
npm.cmd start
```

Open [http://127.0.0.1:4200](http://127.0.0.1:4200). The development server uses `proxy.conf.json` to forward relative `/api` requests to `http://127.0.0.1:8080`. Start the backend, PostgreSQL, and Redis separately for authentication; `app.authentication.enabled` is false by default and must be enabled in the local backend configuration. Only verified, ACTIVE users can sign in. The foundation pages themselves do not call the API.

Available foundation routes:

- `/foundation` — visual foundation, scope boundary, and scaffold status
- `/design-tokens` — reviewable color, semantic, spacing, and radius tokens
- `/verify-email#token=<opaque-token>` — explicit email verification confirmation; the fragment is stripped immediately and never stored
- `/login` — responsive email/password sign-in with CSRF bootstrap and generic errors
- `/forgot-password` — generic reset-link request for an eligible account
- `/reset-password#token=<opaque-token>` — explicit password reset; the fragment is immediately removed
- `/account` — guarded temporary account page with current-user details and logout
- `/` and unknown paths redirect to `/foundation`

## Design system structure

- `src/styles/_tokens.scss` contains the primitive Figma values and the Light/Dark semantic mappings.
- `src/styles.scss` contains global reset, typography, focus, and reduced-motion behavior.
- `src/app/core/theme/theme.service.ts` selects a saved theme, falls back to the operating-system preference, and updates the document theme and browser theme color.
- `src/app/layout/app-shell/` contains accessible responsive navigation and the theme control.
- `src/app/pages/` contains route-level foundation pages. Future business pages should be added by vertical slice rather than grouped into a speculative global component catalog.

Application components must consume semantic variables such as `--bg-surface` and `--text-primary`. Use primitive variables only when a fixed brand color is intentional. Browser configuration is public and must not contain secrets.

The verification page requests a session-bound CSRF token before either POST. It shows generic resend results and accessible status/errors. The backend registration/verification endpoints are local/test-only and require an encrypted outbox key. The opt-in local worker can deliver queued mail to Mailpit; without it, jobs remain pending. See the [Stage 1B-D note](../docs/implementation/milestone-1b-d-mailpit-integration.md).

Stage 1C-C uses same-origin HttpClient calls with browser cookies. Every unsafe authentication request fetches a fresh JSON CSRF token first; a rejected request is shown to the user and is never automatically replayed. Session state lives in an Angular service, not browser storage. Application startup and protected navigation call `/api/v1/users/me`; a `401` redirects protected navigation to sign-in and a service failure hides stale account details. A failed logout is checked with `/users/me` before the UI reports the session ended. See the [Stage 1C-C note](../docs/implementation/milestone-1c-c-angular-authentication.md).

The DTOs in `src/app/core/api/auth-api.service.ts` and the recovery request shapes in `src/app/features/password-recovery/password-recovery-api.service.ts` are manually mirrored from `docs/api/openapi.yaml`. The locked toolchain has no OpenAPI generator yet; when one is adopted, generated types must be kept separate from handwritten services. Registration UI remains deferred. Verification and recovery links work only where their gated backend endpoints are enabled.

Stage 1D-D uses the same-origin `/api` proxy and obtains a fresh anonymous-session CSRF token before either recovery POST. The forgot page gives the same accepted message for every valid account state. The reset page strips the token fragment on load and on same-route navigation, retains it only in component memory, requires explicit submission, and clears it after success or definitive invalidity. It does not sign the user in. The document uses a `no-referrer` policy. A confirmed reset clears the Angular account state immediately; existing `/users/me` restoration and protected-route checks reject older sessions, while uncertain logout still needs a server check. The backend recovery gate must be enabled separately in `local` or `test`, along with the outbox and mail worker for a delivered link. See the [1D-D implementation note](../docs/implementation/milestone-1d-d-angular-password-recovery.md).

## Checks

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test -- --watch=false
npm.cmd run format:check
npm.cmd run test:e2e
```

The Angular unit-test builder runs Vitest in jsdom. The normal Playwright suite exercises foundation, authentication, and recovery with controlled API fixtures, including protected-route restoration, logout, session expiry, and mobile Light/Dark behavior in Chromium. Stage 1C-D adds an opt-in real-service Chromium authentication journey; 1D-D adds a separate full recovery journey through Mailpit and global session revocation. These run only with `MMM_REAL_SERVICES=1` and isolated service-container environment variables; see the [1C-D](../docs/implementation/milestone-1c-d-authentication-integration.md) and [1D-D](../docs/implementation/milestone-1d-d-angular-password-recovery.md) notes. The local web-server setting reuses a healthy application already listening on port 4200, so the same journeys can validate the container profile.

The [Dockerfile](Dockerfile) builds the locked production bundle and serves it from NGINX as the unprivileged `nginx` user. NGINX provides SPA fallback routing, security headers, correct static-asset types, and an `/api` reverse proxy to the backend container. Angular critical-style inlining is disabled for production so the strict Content Security Policy can reject inline scripts and styles.
