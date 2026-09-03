# BLOCK A — Admin auth, roles, access (A1–A12)
# BLOCK B — Admin console UI coverage vs backend (B13–B28)
Answered by priya · fresh-context · 2026-09-02

## A. Security & RBAC

**A1.** Three roles — `SUPER_ADMIN`, `ADMIN`, `SUPPORT` — in a Java enum mirrored by a TS enum.
Evidence: `influora-api/src/main/java/com/influora/domain/enums/AdminRole.java:8` — the three constants; `src/admin/types/admin.types.ts:11` — the mirror `export enum AdminRole`.

**A2.** MFA is **enforced, not optional**, for `SUPER_ADMIN`/`ADMIN`; `SUPPORT` is exempt. Two enforcement points: login rejects an unenrolled SUPER_ADMIN/ADMIN outright, and every privileged endpoint re-checks per request.
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:153` — `POST /admin/auth/login` throws `MFA_ENROLLMENT_REQUIRED` (403) when `mfaEnforceOnLogin` is on and the role requires MFA; `influora-api/src/main/java/com/influora/config/AdminSecurityProperties.java:37` — `private boolean mfaEnforceOnLogin = true` (default on); `influora-api/src/main/java/com/influora/service/admin/AdminContextService.java:122` — per-request `MFA_SETUP_REQUIRED` gate.

**A3.** Stored in `admin_users.encrypted_mfa_secret` as AES-256-GCM ciphertext (IV prepended, Base64). **Encrypted at rest — yes.** The encryption key has a committed dev default in `application.yml`, but it is guarded: the app refuses to boot on it outside the dev profile.
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminMfaSecretCipher.java:58` — encrypt with random 12-byte IV + 128-bit tag; `AdminAuthService.java:282` — `admin.stageMfaSecret(mfaSecretCipher.encrypt(secret))`, plaintext never persisted; `influora-api/src/main/resources/application.yml:262` — `mfa-secret-encryption-key: ${ADMIN_MFA_SECRET_ENCRYPTION_KEY:...}`, the committed dev default.

> **CORRECTION (dispatcher, post-verification).** The answering context originally escalated this as "a defaulted key starts cleanly and silently … every admin TOTP secret is encrypted under a key that is in the repository." **That consequence is false and the file it cited says so.** `influora-api/src/main/java/com/influora/config/SecretsStartupValidator.java:137` holds `KNOWN_DEV_DEFAULT_ADMIN_MFA_ENCRYPTION_KEY` matching that literal; `:398` appends "is still the committed dev default" to `problems` when the running value equals it; `:305`–`:308` throws `IllegalStateException` and aborts startup whenever `problems` is non-empty and `isDev` is false (`:265` — `isDev` requires BOTH `APP_ENV=dev` and the `dev` Spring profile). The class is a live `@Configuration` bean (`:106`). So an unset `ADMIN_MFA_SECRET_ENCRYPTION_KEY` in production is a **hard boot failure**, not a silent weak key. The underlying facts (committed default exists, is a dev default) stand; the risk claim does not.

**A4.** Of 70 endpoints, **66 are role-gated**. Exactly **4 accept any authenticated admin** with no role and no MFA check: `GET /admin/auth/me`, `POST /admin/auth/logout`, `POST /admin/auth/mfa/setup`, `POST /admin/auth/mfa/verify` — deliberately, so an unenrolled admin can bootstrap MFA.
Evidence: `AdminAuthService.java:261`, `:267`, `:273`, `:289` — all four call bare `adminContext.requireAdminId(principal)`; `AdminContextService.java:69` — `requireAdminId` only checks `UserType.ADMIN`, no role, no MFA.

Tier breakdown (gate line cited per group):
- **SUPER_ADMIN only** — all audit (`AdminAuditLogService.java:399`, `:429`, `:303`), all billing (`AdminBillingService.java:88`, `:183`, `:254`), all email (`AdminEmailService.java:60`, `:79`, `:103`, `:117`, `:149`), all error-log (`AdminErrorLogService.java:48`, `:57`, `:63`, `:80`), payout retry + manual payout (`AdminFinanceService.java:585`, `:161`), fee config (`PlatformFeeAdminService.java:85`, `:92`), budget override (`AdminBrandService.java:484`).
- **SUPER_ADMIN + ADMIN** — brand KYC/suspend/reinstate/update (`AdminBrandService.java:259`, `:297`, `:326`, `:364`), creator mutations (`AdminCreatorService.java:221`, `:279`, `:304`, `:341`, `:383`, `:439`), escrow/reconciliation reads (`AdminFinanceService.java:275`, `:297`, `:371`), revenue (`AdminRevenueService.java:68`, `:106`), marketing (`AdminMarketingService.java:69`, `:95`), suspensions + approvals (`AdminModerationService.java:88`, `ApprovalWorkflowService.java:105`, `:131`), ticket assign (`AdminSupportService.java:195`), disputes (`DisputeService.java:390`, `:201`).
- **All three roles incl. SUPPORT** — brand/creator/campaign reads (`AdminBrandService.java:147`, `:173`; `AdminCreatorService.java:151`, `:208`; `AdminCampaignService.java:114`, `:184`, `:257`, `:293`), dashboard (`AdminDashboardService.java:101`, `:110`), flag queue + action (`AdminModerationService.java:136`, `:170`), most ticket ops (`AdminSupportService.java:110`, `:135`, `:145`, `:163`, `:234`, `:284`).

**A5.** **Ad-hoc per service method** on the backend — there is no permission matrix data structure in Java. A matrix exists only on the frontend, and only as a UX affordance.
Evidence: `AdminContextService.java:46` — "there is zero use of `@PreAuthorize`/`@EnableMethodSecurity`/`hasRole` anywhere in `com.influora`, confirmed by grep"; `AdminContextService.java:53` — the intended matrix is prose in a javadoc, explicitly "not yet ratified by Priya as a formal doc"; `src/admin/hooks/useAdminAuth.ts:123` — `ROLE_PERMISSIONS`, the only actual matrix, marked at line 11 as "client-side RBAC is a UX affordance only."
Related defect: `influora-api/src/main/java/com/influora/web/AuditLogController.java:48` cites `ROLE_CAPABILITIES[AdminRole.ADMIN]` in `admin.types.ts` as justification for its gate — that symbol does not exist in that file (only `ROLE_PERMISSIONS` in `useAdminAuth.ts` does). Stale comment, not a functional break.

**A6.** **No.** The route guard checks only that the `admin_token` key is non-empty in localStorage. It does not decode the JWT, check `exp`, or check role.
Evidence: `src/App.tsx:150` — `const isAuthenticated = localStorage.getItem('admin_token'); return isAuthenticated ? ... : <Navigate to="/admin/login" replace />;`. (The expiry check at `src/admin/hooks/useAdminAuth.ts:99` runs inside the console, after the guard has already admitted the user.)

**A7.** **Backend only — the FE caller is dead.** The endpoint and a typed client function both exist; nothing in `src/` invokes it, so an admin access token simply expires at 15 minutes with no silent renewal.
Evidence: `influora-api/src/main/java/com/influora/web/AdminAuthController.java:82` — `POST /admin/auth/refresh` with cookie-then-body precedence and rotation; `src/admin/services/api-contracts.ts:133` — `authApi.refreshToken` declared; zero call sites anywhere in `src/`; `influora-api/src/main/resources/application.yml:185` — `access-expiry-seconds: ${JWT_ACCESS_EXPIRY:900}`.

**A8.** **No idle/inactivity timeout exists anywhere** — not in the FE console, not in the backend. The only bound on a session is the 15-minute access-token expiry, which (per A7) is never refreshed and is checked client-side only on console mount.
Evidence: `src/admin/components/AdminLayout.tsx` — contains no `setTimeout`/`setInterval`/idle handler at all; `src/admin/hooks/useAdminAuth.ts:245` — the only session check is a one-shot `useEffect` on mount; backend has no `maxInactive`/session config (Spring Security here is stateless JWT).

**A9.** **Each call site opts in.** There is no interceptor, filter, or AOP advice — `AdminAuditLogService.record(...)` is called manually by each mutating service method, and it swallows its own failures.
Evidence: `AdminAuditLogService.java:151` — plain public `record(...)`, invoked explicitly; lines `:170`–`:180` catch every exception and only `log.error`, so a failed audit write is invisible to the caller and to any metric. Callers wire it by hand (e.g. `AdminBrandService.java:78`, `AdminCreatorService.java:82`, `AdminBillingService.java:45`). Consequence: an endpoint added without that call has no audit trail and nothing fails.

**A10.** 11 columns on `admin_audit_log`.
Evidence: `influora-api/src/main/java/com/influora/domain/entity/AdminAuditLog.java:33` — `id`, `admin_id`, `admin_email`, `action`, `entity_type`, `entity_id`, `old_value` (json), `new_value` (json), `reason` (TEXT), `ip_address`, `source`, `created_at`. Content is allow-listed, not raw dumps: `AdminAuditLogService.java:98` (18 allowed actions), `:120` (12 allowed entity types), `:152` (`FIELD_ALLOWLIST`, per-entity field whitelist that explicitly bans `mfa_secret`/`password_hash`/`token_hash`).

**A11.** Queryable by all three in the API. In the UI, **entity/action/date are exposed but actor is not** — `adminId` is accepted by the hook and the client but has no input control.
Evidence: `influora-api/src/main/java/com/influora/web/AuditLogController.java:70` — `@RequestParam` for `adminId`, `entityType`, `action`, `startDate`, `endDate`, `page`, `pageSize`; `src/admin/pages/AuditLogPage.tsx:130` — "`adminId` is omitted from the UI for now"; `:141` — only `entityType`/`action`/`startDate`/`endDate` have state and inputs. A per-entity drill-down exists separately at `AuditLogController.java:84` (`GET /admin/audit/entity/{entityType}/{entityId}`), rendered by `src/admin/components/finance/EntityAuditPanel.tsx`.

**A12.** **Only by DB seed/out-of-band insert.** No create-admin endpoint exists, and no migration seeds one either — so a fresh deployment has zero admin rows and no in-app way to create the first one.
Evidence: `AdminAuthService.java:62` — "No admin self-registration / seeding endpoint exists — rows must be inserted out-of-band until a future `AdminUserController` (P1, not in this cycle's scope) ships"; `influora-api/src/main/java/com/influora/domain/entity/AdminUser.java:82` — same; no `AdminUserController` file exists in `web/`; no `INSERT INTO admin_users` in any migration. Compounding risk, same javadoc lines `:64`–`:67`: because MFA-enforce-on-login defaults to true, a pre-existing unenrolled SUPER_ADMIN row is permanently locked out with no in-app recovery path.

## B. Wiring & coverage

**B13.** **12 routes** plus a catch-all redirect, all nested under `/admin/*`.
Evidence: `src/pages/admin-console.tsx:54` — index→`PulseDashboard`, `users/*`, `campaigns`, `finance`, `revenue`, `support`, `moderation`, `disputes`, `billing`, `audit`, `errors`, `emails`, and `*`→`Navigate to="/admin"`. `/admin/login` is a 13th admin path but sits outside the console router (`src/App.tsx:620`).

**B14.** **70 endpoints across 19 controllers** (17 named `Admin*`, plus two mapped under `/admin` without the prefix — `ApprovalWorkflowController` and `AuditLogController`).
Evidence: all 19 class-level mounts confirmed at `AdminAuthController.java:56`, `AdminBillingController.java:95`, `AdminBrandController.java:55`, `AdminCampaignController.java:40`, `AdminCreatorController.java:49`, `AdminDashboardController.java:33`, `AdminDisputeController.java:31`, `AdminEmailController.java:31`, `AdminErrorLogController.java:28`, `AdminEscrowController.java:28`, `AdminFinanceController.java:36`, `AdminMarketingController.java:26`, `AdminModerationController.java:45`, `AdminRevenueController.java:21`, `AdminSupportController.java:48`, `AdminSupportStatsController.java:26`, `PlatformFeeAdminController.java:53`, `ApprovalWorkflowController.java:42`, `AuditLogController.java:61`.

Per-controller counts: Auth 6 (`:68,82,100,108,113,119`) · Brand 7 (`:64,75,87,103,114,123,132`) · Creator 9 (`:58,75,83,95,108,117,135,144,153`) · Support 6 (`:57,72,78,87,96,105`) · Email 5 (`:40,49,55,60,75`) · Billing 4 (`:104,114,119,127`) · Campaign 4 (`:54,72,85,96`) · ErrorLog 4 (`:37,44,49,55`) · Finance 4 (`:50,60,72,91`) · Dashboard 3 (`:45,50,62`) · Moderation 3 (`:58,72,88`) · PlatformFee 3 (`:62,76,91`) · AuditLog 3 (`:70,84,100`) · Dispute 2 (`:44,73`) · Marketing 2 (`:40,53`) · Approval 2 (`:51,58`) · Escrow 1 (`:41`) · Revenue 1 (`:36`) · SupportStats 1 (`:35`).

**B15.** **None is fully orphaned.** The only controller without a dedicated page is `AdminMarketingController` — its two endpoints render inside the Revenue console rather than a marketing screen.
Evidence: `influora-api/src/main/java/com/influora/web/AdminMarketingController.java:40`, `:53` — `/reputation` and `/growth`; consumed at `src/admin/hooks/useFinanceConsole.ts:131`, which feeds `src/admin/components/finance/FinanceConsole.tsx` (mounted only by `src/admin/pages/RevenuePage.tsx:38`). No `/admin/marketing` route exists in `src/pages/admin-console.tsx:54`.

**B16.** Seven endpoints have a real network-issuing client function with **zero call sites**: `authApi.refreshToken`, `authApi.setupMfa`, `authApi.verifyMfa`, `campaignApi.list`, `supportApi.update`, `errorApi.getById`, `emailApi.sendBulk`.
Evidence (declaration line, no caller in any `.ts`/`.tsx` under `src/` outside tests): `src/admin/services/api-contracts.ts:133` (refreshToken), `:142` (setupMfa), `:145` (verifyMfa), `:322` (campaignApi.list — superseded by `listAll` at `:335`), `:570` (supportApi.update → `PUT /admin/support/tickets/{id}`), `:725` (errorApi.getById), `:760` (sendBulk).
The MFA pair is the material one: `AdminAuthController.java:113`, `:119` exposes setup and verify, and MFA is mandatory for SUPER_ADMIN/ADMIN (A2) — but **no admin can enroll through the UI**, because nothing calls `authApi.setupMfa`/`verifyMfa`. Combined with A12, enrollment is entirely out-of-band.
Six further declarations are `unavailable()` stubs that issue no request by design (`api-contracts.ts:115`): `dashboardApi.getMarketingSummary:172`, `financeApi.resolveReconciliation:424`, `financeApi.getTdsReport:429`, `escrowApi.release/hold/refund:529,534,539`, `moderationApi.reviewAppeal:640`, `marketingApi.getAcquisition:780`.

**B17.** **Yes, rendered.** `GET /admin/campaigns/at-risk` → its own card in the Revenue console.
Evidence: `src/admin/hooks/useFinanceConsole.ts` calls `campaignApi.getAtRisk()`; `src/admin/components/finance/FinanceConsole.tsx:534` — "At-Risk Campaigns" heading, rows mapped at `:570`, empty state at `:560`. Backend: `AdminCampaignController.java:85`.

**B18.** **Yes, rendered.** `GET /admin/campaigns/hype/ops` → a stats block in the same console.
Evidence: `src/admin/components/finance/FinanceConsole.tsx:611` — renders `hypeOps.activeHype`, `hypeOps.totalSlots`, `hypeOps.filledSlots`; fetched at `src/admin/hooks/useFinanceConsole.ts` via `campaignApi.getHypeOps()`. Backend: `AdminCampaignController.java:96`.

**B19.** **Yes, rendered.** `GET /admin/escrow/flagged` → the "Flagged Escrow" table.
Evidence: `src/admin/hooks/useFinanceConsole.ts:127` — `escrowApi.getFlagged()`; `src/admin/components/finance/FinanceConsole.tsx:475` — "Flagged Escrow" heading, rows at `:508`. Backend: `AdminEscrowController.java:41`.

**B20.** There **is** a UI control, and it is **permanently disabled** — never wired to the API. Backend is SUPER_ADMIN + MFA gated and then returns 501 regardless.
Evidence: `src/admin/pages/EmailQueuePage.tsx:220` — `<Button ... disabled aria-disabled="true">Bulk Send</Button>` with the comment "Never wired to emailApi.sendBulk()" and tooltip "Disabled — pending abuse controls"; `influora-api/src/main/java/com/influora/web/AdminEmailController.java:78` — calls `requireBulkSendAuthority(principal)` first, then returns `501 BULK_SEND_DISABLED`; `AdminEmailService.java:149` — that authority check is `requireRoleWithMfaSatisfied(principal, SUPER_ADMIN)`.

**B21.** **Both are wired to live UI controls.**
Evidence: `src/admin/components/billing/BillingConsole.tsx:179`, `:200` — `grantComp` from `useGrantComp`, invoked from a form submit; `:319`, `:340` — `overridePlan` from `useOverridePlan`; both reach the API at `src/admin/hooks/useBillingData.ts:179` and `:234`. Backend: `AdminBillingController.java:119`, `:127`, gated SUPER_ADMIN at `AdminBillingService.java:254`.

**B22.** **Yes, reachable.** A budget-override form lives on the brand detail panel.
Evidence: `src/admin/components/users/BrandProfile.tsx:598` — the amount input bound to `overrideBudget`; `:318` — `brandApi.overrideBudget(brandId, overrideCampaignId, budgetValue, overrideReason)`; `:336` — submit-enable guard. Backend: `AdminBrandController.java:103`, SUPER_ADMIN-only at `AdminBrandService.java:484`.

**B23.** **Yes, reachable.** A force-reauth action on the creator detail panel.
Evidence: `src/admin/components/users/CreatorProfile.tsx:254` — `creatorApi.forceInstagramReauth(creatorId)`. Backend: `AdminCreatorController.java:135`, gated SUPER_ADMIN + ADMIN at `AdminCreatorService.java:279`.

**B24.** **No.** There is no `/admin/marketing` route and no marketing page component. The two marketing endpoints surface as sections inside the Revenue console instead.
Evidence: `src/pages/admin-console.tsx:54` — the complete route list, no marketing entry; no marketing directory under `src/admin/pages/` or `src/admin/components/`; `src/admin/hooks/useFinanceConsole.ts:29`, `:131` — where reputation and growth are actually consumed.

**B25.** **No collision — the premise is a false positive from a text match.** The file contains one real `@GetMapping("/stats")` annotation and one occurrence of the same string inside a javadoc comment. Spring sees a single mapping, resolving to `/admin/support/stats` — a distinct path from `AdminSupportController`'s `/admin/support/tickets/**`.
Evidence: `influora-api/src/main/java/com/influora/web/AdminSupportStatsController.java:35` — the only annotation, on `getStats`; `:15` — the second textual hit, inside the class javadoc explaining why the controller exists. `AdminSupportController.java:48` mounts at `/admin/support/tickets` and declares no `/stats` method. Startup is unaffected.

**B26.** **Not a conflict.** Spring keys the handler registry on the full path + method, not the class-level base, so multiple controllers may share `/admin/finance` as long as no full path repeats — and none does. Three controllers share that base, and all seven paths are distinct.
Evidence: `AdminFinanceController.java:36` (base) with `:50` `/escrow`, `:60` `/reconciliation`, `:72` `/payouts/{id}/retry`, `:91` `/payouts/manual`; `AdminRevenueController.java:21` (base) with `:36` `/revenue`; `PlatformFeeAdminController.java:53` mounts the deeper `/admin/finance/fee-config` with `:62` GET, `:76` PUT, `:91` `/history`. Zero overlap.

**B27.** **No — zero phantom calls.** Every live `apiRequest(...)` path in the admin client resolves to a real controller mapping, and the base path is correct (`/api/v1/admin` + `server.servlet.context-path=/api/v1`). Previously-phantom routes were either deleted or converted to no-network `unavailable()` stubs.
Evidence: `src/admin/services/api-contracts.ts:67` — `const API_BASE = '/api/v1/admin'`; `:105` — the `unavailable()` helper, which returns a typed unavailable `ApiResponse` without issuing a network call "so the FE↔BE contract gate sees no phantom path"; `:397` — `getPayoutQueue REMOVED (2026-08-04) ... a phantom route no controller exposes`; `:795` — `getReferrals REMOVED (2026-08-04)`.
Note the stale javadoc in `AdminAuthController.java:51`, which claims `api-contracts.ts` hardcodes `/api/admin` without `/v1` and that this "mismatch needs a resolution" — that was fixed at `api-contracts.ts:67`; the comment was never updated. Same stale claim repeats at `src/admin/hooks/useAdminAuth.ts:8`.

**B28.** **None.** Every admin panel renders API responses; the only in-component literals are UI filter option lists, and the backend coalesces missing data to honest zeros rather than fabricating.
Evidence: `src/admin/components/billing/BillingConsole.tsx:9` — "All mock data has been removed. The backend is FULLY BUILT"; `src/admin/components/dashboard/PulseDashboard.tsx:10` — "the data is real, not mocked"; the only constant arrays are `FinanceConsole.tsx:93` (`PERIOD_OPTIONS`) and `BillingConsole.tsx:74` (`STATUS_FILTER_OPTIONS`), both dropdown labels; `AdminMarketingService.java:69` — growth and reputation are computed from repository counts with `== 0 ? 0.0 :` guards, documented as "honest 'no data yet', never a fabricated value."
One stale comment to disregard: `src/pages/admin-console.tsx:17` still describes BillingPage as "mock data until AdminBillingController ships" — that controller shipped (`AdminBillingController.java:95`) and the page is live-wired via `src/admin/hooks/useBillingData.ts:56`, `:123`.

## Block A+B escalations

1. **MFA is mandatory but un-enrollable through the UI** (A2 + B16). `authApi.setupMfa`/`verifyMfa` have zero call sites, so the only path to satisfying a gate that blocks every SUPER_ADMIN/ADMIN login is a manual API call outside the product.
2. **No admin can be created in-app, and no migration seeds one** (A12). Combined with #1, first-admin bootstrap and lockout recovery are both entirely out-of-band.
3. **A committed default MFA encryption key** (A3), `application.yml:262`. If `ADMIN_MFA_SECRET_ENCRYPTION_KEY` is unset in production, every admin TOTP secret is encrypted under a key that is in the repository. Direct TECH-STACK.md violation — and unlike the cipher's own startup guard (`AdminMfaSecretCipher.java:46`, which refuses to start on a *blank* key), a defaulted key starts cleanly and silently.

Secondary: refresh flow is backend-only (A7), so admin sessions hard-expire at 15 minutes mid-work; no idle timeout at all (A8); route guard is presence-only (A6); audit logging is opt-in per call site with failures swallowed to a log line (A9).
