# Feature: Admin Dashboard (Operations Console)

**Business Purpose** — The platform operator's control room: KYC verification, content moderation, dispute arbitration, support ticketing, finance/reconciliation, fee configuration, and subscription/billing administration. It's how Influora runs trust-and-safety and money operations. It is a self-contained mini-app at `/admin/*`.

**Who uses it** — Platform admins: **SUPER_ADMIN**, **ADMIN**, **SUPPORT**.

## User Roles
`AdminRole` (SUPER_ADMIN/ADMIN/SUPPORT), re-read from DB, MFA-gated for SUPER_ADMIN/ADMIN.

## Permissions
Server-enforced by role (`service/admin/*` + `requireRoleWithMfaSatisfied`). Frontend `ROLE_PERMISSIONS` matrix is UX-only. SUPER_ADMIN = all; ADMIN = ops minus admin-management + finance-reconcile; SUPPORT = read-heavy + support/moderation.

## Business Flow
```
Admin login (+MFA) → Pulse dashboard (KPIs, red flags) →
  Users (KYC verify / suspend / reinstate / tier) · Campaigns (at-risk/hype ops) ·
  Finance (revenue/escrow/payouts/reconciliation/fee-config) · Support (tickets) ·
  Moderation (content flags) · Disputes (resolve → settle escrow) · Billing (comp/override)
```

## Frontend
A separate mini-app under `src/admin/*`:
- **Router**: `pages/admin-console.tsx` → `AdminLayout` + nested `<Routes>` (dashboard/users/campaigns/finance/support/moderation/disputes/billing).
- **Own API client**: `admin/services/api-contracts.ts` (base `/api/v1/admin`, `admin_token`, `{success,data|error}` shape).
- **RBAC/auth**: `hooks/useAdminAuth.ts` (JWT exp sanity check + permission matrix).
- **Realtime**: `services/websocket.ts` + `useAdminSocket` (native WebSocket, token-in-query, backoff reconnect, heartbeat — advisory only).
- **Audit**: `utils/auditLogger.ts` (best-effort client trail to `/admin/audit`).
- **Components**: `dashboard/PulseDashboard`, `users/{BrandProfile,CreatorProfile}`, `campaigns/CampaignTable`, `finance/FeeControlPanel`, `support/TicketList`, `moderation/FlagQueue`, `disputes/DisputeList`, `billing/BillingConsole` (mock).

## Backend
`Admin*Controller` family: `AdminAuthController`, `AdminDashboardController`, `AdminBrandController`, `AdminCreatorController`, `AdminCampaignController`, `AdminDisputeController`, `AdminModerationController`, `AdminSupportController`, `AdminBillingController`, `PlatformFeeAdminController`, `AuditLogController`. Services in `service/admin/*`. Admin controllers return **raw DTOs** (not `ApiResponse`) and paginate via headers.

## Database
`admin_users`, `admin_refresh_tokens`, `admin_audit_logs`, `content_flags`, `disputes`, `support_tickets`(+messages), `platform_fee_config`, plus read access across the platform.

## APIs
`/admin/*` groups — see [../api.md](../api.md). Notable: `POST /admin/disputes/{id}/resolve`, `PUT /admin/finance/fee-config`, `POST /admin/creators/{id}/reviewApplication|suspend|reinstate`, `PUT /admin/brands/{id}/verifyKyc`.

## AI
Not directly; admins review AI-scored content flags.

## Notifications
Admin realtime events over WebSocket: `dashboard.pulse.updated`, `support.ticket.*`, `moderation.flag.created`, `approval.queued`.

## Dependencies
- **Depends on**: auth (admin), disputes/escrow (resolve settles money), moderation (content flags), billing/fees.
- **Depended on by**: trust-and-safety and finance ops for the whole platform.

## Connected Files
`src/admin/*` (entire tree), `pages/admin-console.tsx`, `pages/admin-login.tsx`, the `Admin*Controller` and `service/admin/*` classes.

## Execution Flow
```
Admin action → admin api-contracts client → /api/v1/admin/... (admin_token JWT)
  → Admin*Controller → service/admin (role + MFA check) → DB (+ escrow settle for disputes) → raw DTO
```

## Error Handling
`FEE_CONFIG_CONFLICT` (409 optimistic lock), `DISPUTE_RESOLVE_CONFLICT` (409), `ASSIGNEE_NOT_FOUND` (400). Two flagged frontend contract mismatches (error path, base path). Some admin frontend calls (`support escalate`/`getStats`) hit nonexistent endpoints → 404.

## Security
Separate admin token + cookie path; MFA enforced; metadata-only audit logging (never PII/message bodies); client RBAC is UX-only. Admin lockout has no in-app recovery.

## Performance
List pagination clamped (≤200); reconciliation/finance queries are batched group-bys.

## Testing
Admin tests exist (`rbac-permission-matrix`, `AdminLayout`, `FlagQueue`, `BrandProfile`) — several stale.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~80%
- **Known issues**: billing console is mock (AdminBillingController partial); support escalate/stats endpoints missing; raw-DTO contract mismatches.
- **Last verified**: 2026-07-15
