# Schema Changes Log (Vikram — Backend)

Every Prisma/Flyway migration is logged here per team rule (no migration without a log entry).

| Date | Migration | Table | Change | Reversible / Data impact | Task |
|------|-----------|-------|--------|--------------------------|------|
| 2026-07-18 | `V20260718160000__creator_tier_override.sql` | `creator_profiles` | Add nullable `tier_override VARCHAR(20)` (values NANO/MICRO/MID/MACRO enforced app-side via `CreatorTier.valueOf`, not a DB CHECK — matches `@Column(length=20)` under `ddl-auto=validate`), `tier_adjusted_by VARCHAR(26)` (FK → `admin_users.id`), `tier_adjusted_at TIMESTAMP`; index on `tier_override`. | Additive + nullable only. No existing rows rewritten; NULL override = keep the follower-derived tier (current behavior preserved). No destructive/backfill step. | Admin `creatorApi.adjustTier` (PUT /admin/creators/{id}/tier) |
| 2026-07-18 | `V20260718180000__workspace_phone.sql` | `workspaces` | `ALTER TABLE workspaces ADD COLUMN phone VARCHAR(30) NULL AFTER billing_email;` | Additive + nullable only, no default, no backfill. Existing rows get `phone = NULL`. | Brand Settings > General phone field (`WorkspaceService#updateMyWorkspace`/`getMyWorkspace`, `PATCH`/`GET /workspaces/me`) — approved by Swapnil, follow-up to the L-9 workspace read/update endpoints which had flagged phone as unpersistable (no column). |

## Notes
- `V20260718160000` backs the admin tier-adjust endpoint. Before it, `creator_profiles` had **no** persisted tier — tier was always derived from `total_followers` at read time (`AdminCreatorService.deriveTier`). The override wins over the derived value when set; `AdminCreatorService.resolveTier` centralizes that precedence.
- FK from a pre-admin-panel table (V6) into `admin_users` (V34) — safe under Flyway's strictly-increasing order, same pattern as V36/V38.
- `V20260718180000` closes the phone gap flagged in the L-9 pass (`Workspace#updateContactEmail`/`WorkspaceMemberDtos.WorkspaceReadResponse` javadocs previously called this out explicitly; those comments are now removed since the gap is closed). `phone` follows the same full-replace, blank-clears-it semantics as `billing_email` (`Workspace#updatePhone`), with a loose format check (allowed chars: `+ ( ) - space`, 7-15 digits) enforced in `WorkspaceService#updateMyWorkspace` — not a DB CHECK, same precedent as `tier_override` above.
