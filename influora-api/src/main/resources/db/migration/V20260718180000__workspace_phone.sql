-- Brand self-service Settings > General > Workspace Information (WorkspaceService
-- #updateMyWorkspace / #getMyWorkspace, PATCH+GET /workspaces/me). The Settings page's
-- "phone" field previously had no backing column anywhere on `workspaces` (see the now-removed
-- comments on Workspace#updateContactEmail and WorkspaceMemberDtos.WorkspaceReadResponse) and was
-- deliberately left unpersisted per TECH-STACK.md rule 7 (no fabricated columns). Swapnil approved
-- adding a real column so the field round-trips like `billing_email` does.
--
-- Additive + nullable only, no default, no backfill: existing workspaces simply have phone = NULL
-- until a brand admin fills it in via PATCH /workspaces/me.

ALTER TABLE workspaces
  ADD COLUMN phone VARCHAR(30) NULL AFTER billing_email;
