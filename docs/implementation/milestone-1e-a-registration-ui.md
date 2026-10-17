# Stage 1E-A — Angular registration form

## Objective and boundary

Add a reviewable, responsive `/register` form before connecting account creation. This stage changes only Angular UI, shared form validation, frontend tests, and documentation. It does not call `POST /api/v1/auth/register`, bootstrap CSRF for registration, create a user, send email, authenticate, change the backend gate, or begin wedding onboarding. The temporary **Check details** action validates locally and says explicitly that no account was created. The completed 1D changes were saved in the authorized local checkpoint `bcb7730` before 1E-A edits; 1E-A remains uncommitted and unpushed.

## Design and component architecture

The connected Figma file `aLnG4EylloYZJOnrCXSpxT` was reviewed across all seven pages: Brand & Direction, Foundations, Components — Controls, Components — Patterns, App Screens, Prototypes, and Explorations. Its desktop/mobile onboarding frames are for **wedding creation**, not account registration; Explorations is empty. The new form derives its layout from the existing login and recovery screens and uses the Figma-linked CSS semantic tokens in `frontend/src/styles/_tokens.scss`: warm canvas/surface, coral primary action, lilac accents/focus, mint notice, Inter type, spacing, and radius scales. The application shell's existing `ThemeService` supplies Light/Dark behavior. No new UI framework or design-token values were added.

`frontend/src/app/features/registration/register.component.*` owns the standalone route, Reactive Form, validation feedback, password visibility, and focus behavior. It uses the shared `account.validators.ts` password and email checks; the existing reset and forgot forms now import the same validators without changing their API flows. The form has no service dependency or hidden HTTP call. The page links back to `/login`; a sign-in-page link to `/register` is deferred with the full navigation work in 1E-B.

## Validation and accessibility

Display name is required, trimmed, and limited to 150 UTF-16 code units, matching Java's `@Size(max=150)` on the trimmed field. Email is required, trimmed, lowercased, checked with Angular's email validator, and limited to 320 units. Password is **not trimmed or lowercased**. The existing shared policy validates 15–128 Unicode code points after NFC normalization and rejects control characters without a composition rule. Confirmation is required and compares NFC-normalized values; it is never an API field. The backend remains authoritative when Stage 1E-B connects the endpoint.

Angular `Validators.email` and Java/Jakarta `@Email` can disagree on edge-case addresses, and Java `String.trim()` and JavaScript `trim()` do not remove exactly the same Unicode whitespace. This UI uses ordinary browser validation and normalization for helpful feedback; it does not change the backend contract. Stage 1E-B must present backend `422` field errors as authoritative and avoid claiming exact client/server equivalence on those edge cases.

The form uses persistent labels, `aria-invalid`, linked help/error text, announced inline errors, semantic button controls with `aria-pressed`, and a visible focus outline from the global stylesheet. Invalid checks focus the first invalid input; a valid local check focuses its status message. Password visibility buttons are `type=button` and do not submit. The existing responsive account layout hides its editorial panel on narrow screens. There is no loading indicator because this stage performs no asynchronous submission.

## Routes, tests, and limitations

`/register` is public and directly reachable. The form includes a sign-in link, but login-to-register navigation and the post-registration check-your-email view belong to 1E-B. The backend continues to restrict registration to local/test; this browser route does not imply public signup. Its temporary success-like local validation notice explicitly says **no account has been created**.

Run from `frontend/`:

```powershell
npm.cmd run build
npm.cmd test -- --watch=false
npm.cmd run lint
npm.cmd run format:check
npm.cmd run test:e2e -- --grep 'registration form'
```

Component tests cover initialization, required/invalid fields, name trimming/length, email normalization, Unicode password boundaries, control rejection, normalized confirmation, visibility buttons, focus/announcements, and the absence of CSRF/registration requests. Route and Chromium tests check direct navigation, keyboard order, local-only action, mobile overflow, Light/Dark presentation, and the sign-in link.

Verification on 2026-10-17: the Angular production build, lint, and Prettier check passed. All **60 unit tests in 11 files** passed, including the unchanged recovery behavior after validator reuse. Both focused registration Playwright journeys passed. The full fixture browser suite passed with **11 passed, 3 opt-in real-service journeys skipped** with one worker and then twice with the usual eight workers. Initially, the existing foundation test pressed Tab before waiting for the Angular shell to render under heavier load; its setup now waits for the heading and skip link before testing the same keyboard-focus behavior. No security or UI assertion was removed. No backend file, database, dependency, or generated client was changed; backend tests and real-service account journeys were not run for this frontend-only form stage.

Stage 1E-B subsequently replaced the temporary **Check details** action with the real registration request and check-your-email outcome. The form-only behavior above records what was delivered and verified at the 1E-A handoff; see the [1E-B integration note](milestone-1e-b-registration-integration.md) for the current browser behavior.
