# Admin Code Blueprint

> The internal **admin console** — operations, moderation, finance, support. Sourced from code.
> Lead: Vikram (backend), with the admin frontend in `src/admin/`.

Admins run the platform: verify KYC, review creator applications, moderate flagged content, manage disputes, run support tickets, configure platform fees, and watch KPIs. Admin auth is separate from brand/creator and requires **TOTP MFA**.

---

## 1. Frontend — `/src/admin` + top-level admin pages

### Routes
```
/admin/login   (AdminLoginPage — MFA)
/admin          (AdminDashboardPage, protected)
/admin/demo     (AdminDashboardPage demo)
```
Top-level pages: `src/pages/admin-login.tsx`, `admin-dashboard.tsx`, `admin-console.tsx`.

### Admin module (`src/admin/`)
```
admin/components/AdminLayout.tsx
admin/components/dashboard/{PulseDashboard, KpiCard}.tsx
admin/components/campaigns/CampaignTable.tsx
admin/components/finance/FeeControlPanel.tsx
admin/components/moderation/FlagQueue.tsx
admin/components/support/TicketList.tsx
admin/components/users/{BrandProfile, CreatorProfile}.tsx
admin/services/api-contracts.ts   (typed admin API client, 636 LOC)
admin/services/websocket.ts       (realtime updates)
admin/{hooks, types, utils}/
```

---

## 2. Backend (Spring) — admin controllers

| Controller | Base path | Key actions |
|---|---|---|
| `AdminAuthController` | `/admin/auth` | `/login`, `/refresh`, `/logout`, `/me`, **`/mfa/setup`**, **`/mfa/verify`** |
| `AdminDashboardController` | `/admin/dashboard` | `/pulse`, `/operations` |
| `AdminBrandController` | `/admin/brands` | list, `/{id}`, `/{id}/verify-kyc` |
| `AdminCreatorController` | `/admin/creators` | list, `/{id}`, `/{id}/review-application`, `/{id}/instagram/force-reauth` |
| `AdminCampaignController` | `/admin/campaigns` | campaign oversight |
| `AdminModerationController` | `/admin/moderation` | `/flags`, `/flags/{id}/action` |
| `ApprovalWorkflowController` | `/admin/moderation` | `/approvals/pending`, `/approvals/{id}` |
| `AdminDisputeController` | `/admin/disputes` | dispute resolution |
| `AdminSupportController` | `/admin/support/tickets` | list, `/{id}`, `/{id}/reply`, `/{id}/assign` |
| `PlatformFeeAdminController` | `/admin/finance/fee-config` | fee config, `/history` |
| `AuditLogController` | `/admin/audit` | `/entity/{type}/{id}` audit trail |

### Entities
`AdminUser`, `AdminRefreshToken`, `AdminAuditLog`, `AuditLogEntry`, `ContentFlag`, `SupportTicket`, `SupportTicketMessage`, `PlatformFeeConfig`, `PlatformStat`, `FeaturedCreator`, `Dispute`.

---

## 3. Admin security (see `05` / deployment doc)

- Separate credential + refresh token store (`AdminUser`, `AdminRefreshToken`).
- **TOTP MFA** enforced (`TotpService`, `AdminMfaProperties`) — `mfa/setup` & `mfa/verify` stay authenticated by design.
- Every privileged action writes to `AdminAuditLog` (`AuditLogController` exposes the trail).
- Admin routes fall through to `anyRequest().authenticated()` in `SecurityConfig`.

---

## 4. Admin flow (code path)

```
admin login (AdminAuthController) → MFA verify (TotpService)
  → Pulse dashboard KPIs (AdminDashboardController)
  → verify brand KYC (AdminBrandController./{id}/verify-kyc)
  → review creator application / force Instagram reauth (AdminCreatorController)
  → moderation flag queue → take action (AdminModerationController)
  → approval workflow for gated content (ApprovalWorkflowController)
  → resolve disputes (AdminDisputeController)
  → support tickets: reply / assign (AdminSupportController)
  → set platform fee config (PlatformFeeAdminController)
  → all actions logged (AdminAuditLog)
```
