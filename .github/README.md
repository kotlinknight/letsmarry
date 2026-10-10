# GitHub configuration

[`ci.yml`](workflows/ci.yml) defines the Milestone 0 continuous-integration checks for pushes to `main`, pull requests, and manual runs. It uses read-only repository permissions, cancels superseded runs on the same ref, and pins every third-party action to an immutable commit.

The workflow has three jobs:

- **Backend:** sets up Java 25 and runs the locked Gradle build, including formatting, unit/integration tests, and Spring Modulith verification.
- **Frontend:** uses Node 22.23.2 and npm 11.21.0, then runs a clean install, Angular ESLint, Prettier, Vitest, the production build, and Playwright Chromium journeys.
- **Containers:** builds both application images, starts the complete Compose application profile with isolated CI ports, and checks readiness, SPA fallback routing, API proxy security behavior, and response security headers.

The container job always removes its ephemeral containers after the run. It does not publish images or deploy the application. Deployment workflows require a later architecture decision and explicit approval.

The workflow syntax is checked locally with actionlint. A local syntax check and successful local commands do not prove that GitHub-hosted CI passed; the first remote run can occur only after an approved commit and push.

Keep changes small and use short-lived branches. Commit and push only with explicit user permission. See the [development plan](../docs/05-development-plan.md).
