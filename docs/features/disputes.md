# Feature: Disputes

**Business Purpose** — When a brand and creator disagree about delivered work, either side opens a dispute. Opening one **freezes** the funded escrow and moves the deal to DISPUTED; an admin then arbitrates and the resolution **settles the frozen money** (release to creator, refund to brand, or split). This is the safety valve that makes escrow trustworthy.

**Who uses it** — Brands and creators (open, view), admins (resolve).

## User Roles
Brand/Creator (open a dispute on their own collaboration, view), Admin (resolve, MFA).

## Permissions
Open → participant of a collaboration with **funded, unreleased** escrow. Resolve → admin (SUPER_ADMIN/ADMIN, MFA).

## Business Flow
```
Open (reason) → guard: funded unreleased escrow + no active dispute → freeze escrow (FUNDED→FROZEN) → collaboration DISPUTED
Admin resolve (BRAND/CREATOR/SPLIT + notes [+split %]) → settle frozen escrow → dispute RESOLVED_*
```
`DisputeStatus`: OPEN → RESOLVED_BRAND / RESOLVED_CREATOR / RESOLVED_SPLIT (UNDER_REVIEW defined but unreached).

## Frontend
- **Admin**: `admin/pages/DisputesPage` → `DisputeList` + `DisputeResolveModal` (radio + split %).
- **Brand**: `brand-disputes` (read-only). **Creator**: `creator-disputes` + `OpenDisputeForm`.

## Backend
- **Controllers**: `DealController.POST /deals/{dealId}/disputes` (open), `BrandDisputeController`, `CreatorDisputeController`, `AdminDisputeController` (resolve).
- **Service**: `DisputeService` (openDispute, resolveDispute); escrow settlement via `EscrowService.admin*ForDispute`.

## Database
`disputes` (V45, +V53 `version` optimistic lock). No `workspace_id` — scoped via `collaboration → campaign → workspace`. See [../database.md](../database.md).

## APIs
`POST /deals/{dealId}/disputes`, `GET /brand/disputes` (+`/list`), `GET /creator/disputes`, `GET /admin/disputes`, `POST /admin/disputes/{id}/resolve`.

## AI
Not involved.

## Notifications
Dispute open/resolve notifications (participants).

## Dependencies
- **Depends on**: collaborations, escrow (freeze/settle), admin console.
- **Depended on by**: escrow (settlement), reviews (blocked while disputed).

## Connected Files
`DisputeService`, `AdminDisputeController`, `EscrowService`, `domain/entity/Dispute`, `web/dto/dispute/*`; admin dispute components.

## Execution Flow
```
Open: DisputeService.openDispute → requireOwnedCollaboration → hasFundedUnreleasedEscrow guard
  → one-active-dispute guard → FREEZE escrow BEFORE save (crash-safe) → collaboration DISPUTED
Resolve: DisputeService.resolveDispute → requireRoleWithMfaSatisfied → settle escrow BEFORE status flip
  (release minus fee / refund / split) → dispute.resolve → saveAndFlush (@Version) → 409 on lock conflict
```

## Error Handling
`NO_FUNDED_ESCROW` (409), `DISPUTE_ALREADY_OPEN` (409), `INVALID_RESOLUTION` (400), `DISPUTE_ALREADY_RESOLVED` (409), `DISPUTE_RESOLVE_CONFLICT` (409, optimistic lock), `CREATOR_SPLIT_PERCENT_INVALID` (400).

## Security
Escrow frozen before dispute save (no window where an open dispute has releasable escrow); double-resolve blocked at three layers (fast-path, entity check, `@Version`); admin MFA required; reason sanitized.

## Performance
Optimistic lock via `saveAndFlush`; settlement in the same transaction as the status flip.

## Testing
Dispute open/resolve tests incl. concurrent-resolve. Regression risks: freeze-before-save ordering, split math, lock conflict.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~85%
- **Known issues**: `UNDER_REVIEW` status/transition is dead (no endpoint triggers it); no `GET /admin/disputes/{id}`.
- **Last verified**: 2026-07-15
