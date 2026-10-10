# Make My Marriage — Product Requirements Document (PRD)

**Status:** MVP baseline (working specification)  
**Version:** 1.0  
**Last consolidated:** 2026-10-10  
**Product:** Make My Marriage (MMM)  
**Platform:** Responsive web application  
**Initial market:** Couples and families planning Indian weddings  
**Initial language:** English

> This Markdown edition consolidates the product requirements agreed during planning. It is an implementation reference, not a claim that the product has already shipped.

## 1. Executive summary

Make My Marriage is a collaborative wedding-planning workspace for Indian weddings. It brings together events, tasks, guests, invitations, RSVPs, budgets, expenses, vendors, documents, seating, gifts, and a shareable wedding website. It reduces the need to coordinate through scattered spreadsheets, WhatsApp messages, and individual notes.

**Promise:** Couples and their trusted people should be able to organize a multi-event wedding with clarity, privacy, and minimal setup friction.

**Product personality:** Calm, modern, approachable, and joyful. The authenticated planning workspace must prioritize utility; public wedding pages can be more celebratory. The current brand direction uses coral, lilac, mint, warm ivory, and midnight ink, with Light/Dark token modes.

## 2. Problem and opportunity

Common wedding-planning challenges:

- Multiple ceremonies or functions have different dates, places, vendors, and guest lists.
- Guests may be invited to some events but not others, and RSVP updates are hard to track.
- Budget decisions, advances, and remaining payments are spread across chats or sheets.
- Several family members help, but not everyone should see private finances or confidential tasks.
- Vendor contacts, agreements, receipts, and deadlines are not centralized.
- Couples need a practical planning workspace; guests need a simple, mobile-friendly invitation and RSVP experience without registration.

**Outcomes we aim to improve:** visibility of what's next, ownership of tasks, completeness of guest responses, accuracy of expense/payment tracking, and confidence in shared planning.

## 3. Target users

| Persona | Main need | Access model |
|---|---|---|
| Bride | Shared control of wedding and decisions | Owner |
| Groom | Equal control of wedding and decisions | Owner |
| Family organizer | Manage work on behalf of couple | Admin or family member |
| Friend/collaborator | Help with assigned areas | Collaborator with scoped permissions |
| Guest | Open invitation and RSVP easily | Secure invitation token, no account |

The product must not assume that the account creating the wedding belongs to the bride or groom.

## 4. Goals and success measures

### Product goals

1. Let an authenticated user create a wedding workspace in minutes.
2. Allow bride and groom to join as **equal owners** even when someone else creates the workspace.
3. Support collaboration without revealing private financial or task information.
4. Coordinate multi-event guests, invitations, and login-free RSVP.
5. Track planned spending, actual expenses, vendor commitments, installments, and payments.
6. Make wedding information shareable to guests through a curated, controllable public/unlisted website.
7. Preserve useful wedding records after the event through archiving.

### Suggested MVP metrics (instrument, not hard commitments)

- Wedding creation completion rate; time to first wedding created.
- Percentage of new weddings with first event/task/guest added.
- Partner owner-invitation acceptance rate.
- Guest invitation → RSVP completion rate by event.
- Percentage of tasks assigned and completed by due date.
- Percentage of active expense records with payment/milestone tracking.
- Retained weekly active wedding workspaces.

Do not promise numeric targets until there is real usage data.

## 5. MVP scope

| Module | MVP requirements |
|---|---|
| Accounts & security | Email/password registration, verification, login, reset password, secure sessions |
| Wedding workspace | Create, update, list, complete and archive weddings |
| Couple & team | Bride/groom profiles, secure owner invitations, admins, family/collaborator roles, permission overrides |
| Dashboard | Wedding countdown, upcoming events, task progress, RSVP snapshot, finance cards only when authorized |
| Events | Create/manage multi-event celebrations, dates, venues, public visibility |
| Tasks | Status, priority, due dates, assignees, event link, private visibility |
| Guests | Guest records, households, wedding side, relationship details, event assignments |
| Invitations | Create/revoke/share secure guest invitation links; email delivery |
| RSVP | Guest account not required; invited event-by-event responses; internal summary |
| Money | Overall budget, categories, expenses, payment milestones, recorded payments and derived totals |
| Vendors | Private vendor directory, contacts, quotes, agreed amounts, event links |
| Documents | Contracts, invoices, receipts, relevant attachments with private storage |
| Seating | Tables/capacities and one table assignment per guest per event |
| Gifts | Cash/physical gifts (including shagun), return-gift stock and distribution |
| Wedding website | Publish/unpublish, public/unlisted visibility, curated event details and content |
| Media | Small curated photo gallery, optional event live-stream URL (external provider) |
| Notifications | Email only; invitations and necessary transactional notifications |
| Administration | Activity feed, sensitive audit trail, soft deletion of business records |

### Explicitly out of scope for MVP

- Vendor marketplace, vendor accounts, or in-app bookings.
- Accommodation and transportation coordination.
- Minute-by-minute wedding-day run-of-show.
- AI or automated wedding planning features.
- Native Android/iOS applications.
- Full photo-backup or large media library.
- Payment gateway, online subscriptions, billing, or payout handling.
- WhatsApp/SMS notifications (possible later).
- Real-time collaboration/WebSockets.
- Multiple languages (English only for MVP).
- Elasticsearch, Kafka, microservices, or complex analytics infrastructure.

The application starts free; monetization is a later decision.

## 6. Membership, ownership and privacy

### Identity model

- A `User` is a registered account.
- A `WeddingCoupleProfile` represents a BRIDE or GROOM within a wedding, with or without an associated account.
- A `WeddingMember` gives a registered user access to a specific wedding with a role and permissions.
- `Wedding.created_by_user_id` is historical creator information, **not** proof of ownership.

### Roles

| Role | Intended use |
|---|---|
| OWNER | Bride or groom; equal control and strong protection from removal/demotion |
| ADMIN | Trusted organizer with broad capabilities but not ownership |
| FAMILY_MEMBER | Configurable access for relatives |
| COLLABORATOR | Limited contribution to selected areas |

**Invariants:**

1. Each wedding has at most one currently active BRIDE owner and one active GROOM owner.
2. An owner must map to the appropriate couple profile; the profile can exist before account signup.
3. A wedding may be created by a family member or friend: they become a **setup admin**, never a hidden owner.
4. A verified account creating a new wedding may self-declare BRIDE/GROOM and become that owner. Subsequent owner claims require a secure invitation and verified intended email; a setup admin cannot claim ownership on behalf of the couple.
5. Standard member-management endpoints cannot silently remove or demote an owner.
6. Multiple admins are allowed.
7. Authorization is enforced on the backend for every read/write, not just by hiding UI.

### Privacy

- Finance is permission-scoped: `VIEW_FINANCE` and `MANAGE_FINANCE` are separate.
- Selected/private tasks are visible only to authorized people, regardless of assignment UI conventions.
- Guest records, gift records, audit details, contracts, and media are private by default.
- Public pages return only specifically published data; invitation recipients never get internal dashboards.
- A user may belong to multiple weddings; data must never leak between wedding IDs.

## 7. User journeys and functional acceptance criteria

### Journey A — Bride/groom creates a wedding

1. Register or log in.
2. Choose role: Bride, Groom, Family member, or Friend.
3. Enter couple names (create separate couple profiles).
4. Enter wedding name, primary date, timezone and location.
5. Optionally invite the other partner as an owner.
6. Review and create workspace.
7. Land on an empty-state dashboard with suggested first actions.

**Acceptance:** If Bride created, she is the BRIDE owner; GROOM owner seat remains unclaimed until legitimate acceptance (and vice versa). The invitation step can be skipped and resumed later.

### Journey B — Family/friend creates for a couple

1. Sign in and select Family member or Friend.
2. Enter bride/groom names and basic wedding information.
3. Optionally invite both as owners by email.
4. Create workspace as setup ADMIN.
5. Couple members accept invitations and become separate equal owners.

**Acceptance:** Setup admin can arrange permitted planning work but does not become an owner. Owner acceptance is transactional, verified and idempotent.

### Journey C — Plan events and tasks

- Create events such as Mehndi, Haldi, Ceremony and Reception.
- Store date/time with IANA timezone, venue and description.
- Create general or event-specific tasks with assignees, due dates and priority.
- Tasks can be for all authorized members or selected individuals.
- Dashboard reflects outstanding, complete and overdue work.

**Acceptance:** A task associated with an event must belong to the same wedding. Private tasks are absent from unauthorized lists, counts and feeds.

### Journey D — Guest management and RSVP

1. Organizer adds households/individual guests, including guests without email.
2. Organizer chooses the specific event(s) to which each guest is invited.
3. Organizer creates a shareable invitation link and optionally emails it.
4. Guest opens link on mobile, sees only authorized wedding/event details, and responds for invited event(s).
5. Organizer sees statuses: Attending, Not attending, Maybe, No response.
6. Organizer can report and follow up at event level.

**Acceptance:** RSVP cannot be submitted for a guest/event outside the invitation's authorization. No guest account is required. A household invitation can address multiple explicitly included guests. A guest invited to Reception only must not see private Ceremony-only invitations.

### Journey E — Budget and expenses

1. Set one overall wedding budget and optional category allocations.
2. Record expenses, vendor or event associations and total agreed cost.
3. Add milestones: deposit, installment, balance etc.
4. Record actual payments and date/reference/method.
5. View committed, paid, outstanding and remaining budget derived from authoritative records.

**Acceptance:** Only members with proper finance permissions can access these APIs and UI data. A retry must not duplicate a payment. Payment edits/deletions are audited.

### Journey F — Vendors, documents and payments

- Store one vendor against one or multiple events.
- Record quotes and agreed terms independently from actual payments.
- Upload receipts, contracts and supporting docs to private storage using short-lived upload/download URLs.
- Associate documents to supported parent records.

**Acceptance:** A document attachment must never link an entity from another wedding; files are verified after upload before marked available.

### Journey G — Seating

- For a given event, create tables with positive capacities.
- Assign each guest to at most one table for that event.
- Show unassigned attendees and warn/prevent over-capacity assignments.

**Acceptance:** Only guests assigned to the event can receive a seating assignment for that event.

### Journey H — Gifts and return gifts

- Record money/shagun or physical gifts and optionally associate giver/guest and event.
- Track return-gift items acquired and distributions to guests/households.
- Calculate inventory remaining from purchased quantity minus distribution records.
- Restrict sensitive gift details to authorized wedding members.

### Journey I — Wedding website and media

- Choose unique site slug, select visibility Public or Unlisted, select theme/configuration.
- Publish and unpublish without exposing internal wedding records.
- Publish approved event details, optional gallery media, and approved external livestream links.
- Ensure invitation-only content is not accidentally promoted to the public website.

**Acceptance:** Unpublished wedding websites cannot be fetched publicly. Public URLs do not expose private phone numbers, finance, guest manifests, private documents or membership lists.

### Journey J — Completion and archive

- Mark wedding completed, then archive when appropriate.
- Retain access to useful planning records and memories under the permission model.
- Default archive UX can be read-only or limited-write; confirm exact transition policy before implementation.

## 8. Screens and navigation

### Planner (responsive)

- Authentication and create-wedding onboarding.
- Wedding switcher and Overview dashboard.
- Events, Tasks, Guests, Invitations, RSVP, Seating.
- Money: Budget, Expenses, Payments.
- Vendors, Documents, Gifts, Return Gifts.
- Team and permissions; Wedding Website and Media.
- Activity and Settings.

### Public/guest-facing (mobile first)

- Wedding webpage (`/w/:slug`).
- Guest invitation (`/i/:token`).
- Event-specific RSVP and submission confirmation.
- Venue details and optional livestream link.

### UX expectations

- Clear empty states, loading/error/no-permission states and meaningful confirmation.
- Accessible form labels, keyboard focus, readable typography and contrast.
- Explicit indicators for private information and restricted-access features.
- Guest flows do not force registration.
- Responsive, touch-friendly guest experience; desktop-first planner but fully responsive.
- Existing Figma design system is the visual source of truth.

## 9. Non-functional requirements

| Area | Requirement |
|---|---|
| Security | TLS, Redis-backed planner sessions, HttpOnly cookies, CSRF protection, session revocation, hashed invitation tokens, server authorization |
| Tenancy | Shared PostgreSQL schema; wedding_id-scoped reads and writes and cross-wedding FK validation |
| Reliability | Reliable email delivery via outbox, retries and deduplication |
| Performance | Pagination and indexed filtering for lists; dashboard uses targeted summaries |
| Uploads | Short-lived signed URLs, restricted size/types, private object storage |
| Audit | Append-only sensitive action records; no raw secrets in logs |
| Deletion | Soft delete business objects, define retention/anonymization for production |
| Observability | Structured logs, correlation IDs, Actuator and Micrometer in MVP |
| Testing | Unit, integration, modular boundary, security and Playwright E2E tests |
| Deployment | Docker-first, environments separated; cloud/cluster choice deferred |
| Localization | English MVP, sensible Indian defaults while storing explicit IANA timezones/currency |

## 10. MVP delivery phases

Before the product phases below, **Milestone 0** delivers the technical foundation in separately approved stages: 0A repository/docs, 0B backend/local services, 0C Angular/design shell, and 0D testing/CI/container verification. Milestone 0 implements no business features. See the [development plan](05-development-plan.md).

1. **Foundation:** auth, wedding creation, couple profiles, ownership and membership.
2. **Core planning:** dashboard, events, tasks and assignments.
3. **People:** households, guests, invitation links, RSVP, reports.
4. **Money and vendors:** budget, expense, milestone, payment, private finance and document handling.
5. **Completeness:** seating, gifts, return gifts, wedding website, curated media, livestream URLs.
6. **Release quality:** audit, hardening, usability, test coverage, accessibility, staging and production validation.

Phases organize implementation only; feature inclusion remains as described in MVP scope.

## 11. Dependencies and risks

- **Ownership claims:** must not allow forged bride/groom claiming or removal by a setup admin.
- **Guest privacy:** household invitation convenience must not reveal unrelated guests or uninvited events.
- **Financial correctness:** do not store competing payment totals; use transactions, versioning, idempotency and audit.
- **File safety:** enforce maximum sizes, allowed types and ownership when generating signed URLs.
- **Outbox delivery:** emails may retry; template and duplicate semantics should be defined.
- **Retention:** archived/private data and audit records require a clear privacy policy before launch.
- **Operational cost:** expected initial scale is modest (thousands of weddings, hundreds of guests per wedding). Avoid premature infrastructure complexity.

## 12. Future opportunities — not MVP commitments

WhatsApp notifications, paid tiers, AI assistance, deeper analytics, native apps, richer galleries, vendor marketplace/bookings, real-time collaboration, automation/reminders and multilingual support. These require separate scope and architectural review.

## 13. Open product decisions

- Wedding completion/archival edit rules and ownership-recovery process.
- Exact default permission matrix per role.
- RSVP link expiry policy and whether invitees can change a response after an event begins.
- Public wedding website privacy defaults (e.g. indexing for Public vs Unlisted).
- File size/storage quotas, retention and account erasure policy.
- Exact MVP default event/task/category templates.

## 14. Definition of done for MVP

- All in-scope journeys can complete on desktop/mobile browsers.
- Each privacy-sensitive module passes permission and cross-tenant security tests.
- Couple ownership follows the verified-creator self-declaration rule for a new workspace and verified invitation acceptance for subsequent claims; ordinary member management cannot revoke ownership.
- Guest RSVP works without an account and only exposes invited information.
- Expenses and recorded payments produce internally consistent totals.
- Delivery and upload workflows tolerate retries/failures.
- Core UI has accessible empty/error/loading states and verified responsive layouts.
- Database migrations rebuild cleanly; CI passes automated tests.

---

**Related documentation:** [System Architecture](02-system-architecture.md) · [Database Design](03-database-design.md) · [REST API Design](04-api-design.md)
