# Feature: Workspaces & Members

**Business Purpose** — A brand is modeled as a **workspace** (the billing/tenant boundary) with multiple **members** who collaborate on campaigns. Team access, seat limits, and role-based permissions all hang off this. Every brand-side resource is workspace-scoped.

**Who uses it** — Brands (members manage the team). Not used by creators/admins directly (admins act on workspaces via the admin console).

## User Roles
Workspace member roles (`MemberRole`): **OWNER**, **ADMIN**, **MANAGER**, **MEMBER**, **VIEWER**.

## Permissions
- Invite/deactivate members, manage invites → **OWNER/ADMIN**.
- OWNER assignable only at signup; a second OWNER invite → `INVALID_ROLE`.
- The sole OWNER cannot be deactivated (`CANNOT_REMOVE_SOLE_OWNER`).
- Seat count is capped by the plan (`Plan.seatLimit`: Free 1, Pro 5).

## Business Flow
```
Brand signup → workspace created + OWNER member + wallet
OWNER/ADMIN invites member (email + role) → invite row (PENDING)
Invitee accepts → WorkspaceMember created
OWNER/ADMIN deactivates member → member inactive (guarded for sole owner)
```

## Frontend
- **Pages/components**: brand settings surfaces + onboarding wizard (`components/brand/onboarding/*`, `components/brand/settings/*`).
- **API**: `api.workspaces.*`, `api.onboarding.*`.

## Backend
- **Controllers**: `web/WorkspaceController`, `web/WorkspaceMemberController`, `web/OnboardingController`.
- **Services**: `service/WorkspaceMemberService`, `service/BrandContextService` (`requireBrandWorkspace`, `requireRole`).
- **DTOs**: `web/dto/workspace/*`.

## Database
`workspaces` (V2, +V36 suspension/KYC/verification), `workspace_members` (V2), `workspace_member_invites` (V59), KYC docs (V3). See [../database.md](../database.md).

## APIs
`GET /workspaces/slug-check` (public), `GET /workspaces/me` (any active member), `PATCH /workspaces/me` (OWNER/ADMIN — brand Settings > General > Workspace Information: name/email/websiteUrl persist; no `phone` column exists anywhere, stays UI-only), `GET /workspaces/members`, `POST /workspaces/members/invite`, `GET/POST /workspaces/invites`, `POST /workspaces/members/{id}/deactivate`.

## AI
Not involved (but Meera's on-behalf tool calls require the acting member to hold OWNER/ADMIN for money-adjacent tools).

## Notifications
Member-invite notifications via `NotificationService` (invite lifecycle).

## Dependencies
- **Depends on**: auth (user identity), billing (seat limits via `Plan`).
- **Depended on by**: every brand feature (workspace is the tenant scope), wallet (WORKSPACE-owned), plan gating.

## Connected Files
`WorkspaceController`, `WorkspaceMemberController`, `OnboardingController`, `WorkspaceMemberService`, `BrandContextService`, `domain/entity/Workspace`, `WorkspaceMember`, `WorkspaceMemberInvite`.

## Execution Flow
```
Invite form → api.workspaces.invite → POST /workspaces/members/invite → WorkspaceMemberController
  → WorkspaceMemberService.inviteMember (requireRole OWNER/ADMIN, seat-limit check) → invite row → notify
```

## Error Handling
`INVALID_ROLE` (400), `CANNOT_REMOVE_SOLE_OWNER` (409), seat-limit → plan upgrade path, `FORBIDDEN` (403) for non-privileged members.

## Security
Role checks in the service layer; `listMembers` is an un-gated read; verification/suspension (KYC) states gate campaign activation. Workspace verification (`VERIFIED`) is required to set a campaign ACTIVE.

## Performance
Membership resolution is a small scoped query; roles cached within a request via context service.

## Testing
Covered by workspace/member service tests. Regression risks: sole-owner guard, seat limits, OWNER-assignment rule.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~85%
- **Known issues**: seat-limit enforcement depends on subscription state, which is affected by the subscription-webhook gap (see [../known-limitations.md](../known-limitations.md)).
- **Missing**: richer invite acceptance UX in places.
- **Last verified**: 2026-07-15
