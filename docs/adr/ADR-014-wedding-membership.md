# ADR-014 — Keep wedding and membership together initially

**Status:** Accepted

**Date:** 2026-10-10

## Context

Wedding creation requires two couple profiles and an initial creator membership to succeed or fail together. Separate top-level wedding and membership modules would introduce an orchestration boundary before the domain needs it.

## Decision

- Initially keep wedding creation, couple profiles, membership, and permissions inside the `wedding` Spring Modulith module.
- Organize those responsibilities in clearly separated internal packages. Keep contracts small so membership can be extracted later if justified.
- Use `@Transactional` to create the wedding, couple profiles, and initial membership synchronously in one PostgreSQL transaction.
- A verified account creating a new wedding as BRIDE/GROOM becomes the corresponding OWNER through self-declaration. A FAMILY_MEMBER/FRIEND creator becomes setup ADMIN, never OWNER by virtue of creation alone.
- Subsequent partner-owner acceptance requires a secure invitation and verification of the intended account/email, with atomic claim handling.
- Do not use asynchronous events to create initial membership. Persist any email intent with its originating transaction and send only after commit through the outbox worker.
- Keep the identity module independent of wedding roles. Other modules use published contracts, not wedding repositories or JPA internals.

## Consequences and verification

There is one module-level consistency boundary initially, without changing the wedding/member table distinction. Before ownership transitions, resolve recovery, suspended-owner slot protection, role defaults, override precedence, and setup-admin privileges.

Test creator roles, transactional rollback, concurrent owner claims, tenant isolation, and Modulith dependency boundaries. No wedding or membership implementation belongs to Milestone 0.

See [architecture](../02-system-architecture.md), [product requirements](../01-product-requirements.md), and [database design](../03-database-design.md).
