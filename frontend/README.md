# Make My Marriage frontend

This directory contains the Angular foundation from Milestones 0C and 0D and the minimal Stage 1B-C email-verification confirmation page. It provides a strict standalone application, lazy routing, a responsive application shell, reviewed Figma design tokens, persistent Light/Dark themes, automated lint and browser checks, and a production-style container image. It does not contain registration, login, wedding creation, or dashboard data.

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

Open [http://127.0.0.1:4200](http://127.0.0.1:4200). The development server uses `proxy.conf.json` to forward relative `/api` requests to `http://127.0.0.1:8080`. Start the backend separately when an API is required; the foundation pages themselves do not call it.

Available foundation routes:

- `/foundation` — visual foundation, scope boundary, and scaffold status
- `/design-tokens` — reviewable color, semantic, spacing, and radius tokens
- `/verify-email#token=<opaque-token>` — explicit email verification confirmation; the fragment is stripped immediately and never stored
- `/` and unknown paths redirect to `/foundation`

## Design system structure

- `src/styles/_tokens.scss` contains the primitive Figma values and the Light/Dark semantic mappings.
- `src/styles.scss` contains global reset, typography, focus, and reduced-motion behavior.
- `src/app/core/theme/theme.service.ts` selects a saved theme, falls back to the operating-system preference, and updates the document theme and browser theme color.
- `src/app/layout/app-shell/` contains accessible responsive navigation and the theme control.
- `src/app/pages/` contains route-level foundation pages. Future business pages should be added by vertical slice rather than grouped into a speculative global component catalog.

Application components must consume semantic variables such as `--bg-surface` and `--text-primary`. Use primitive variables only when a fixed brand color is intentional. Browser configuration is public and must not contain secrets.

The verification page requests a session-bound CSRF token before either POST. It shows generic resend results and accessible status/errors. The backend endpoints are local/test-only and require an encrypted outbox key. No SMTP sender exists yet, so a resend queues a job but does not deliver an email. See the [Stage 1B-C note](../docs/implementation/milestone-1b-c-registration-verification.md).

## Checks

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test -- --watch=false
npm.cmd run format:check
npm.cmd run test:e2e
```

The Angular unit-test builder runs Vitest in jsdom. Playwright exercises desktop navigation/theme persistence, mobile keyboard/drawer behavior, and unknown-route recovery in Chromium. Its local web-server setting reuses a healthy application already listening on port 4200, so the same journeys can validate the container profile.

The [Dockerfile](Dockerfile) builds the locked production bundle and serves it from NGINX as the unprivileged `nginx` user. NGINX provides SPA fallback routing, security headers, correct static-asset types, and an `/api` reverse proxy to the backend container. Angular critical-style inlining is disabled for production so the strict Content Security Policy can reject inline scripts and styles.
