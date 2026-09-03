# Swapnil -> Priya · 100 questions on the ADMIN surface, answered from code
Task T-QA-ADMIN-100 · 2026-09-02 · verdict BELIEVED (fresh-context, not oracle-proved)

Producer: priya, dispatched into 5 SEPARATE fresh contexts, each given only the artifact paths and the done_when — no producer reasoning, no prior verdict, no cross-talk between blocks.
Gate: gates/citations.py — **exit 1**. 203 full-path citations resolved (0 missing, 0 beyond EOF); 214 citations were written as bare basenames and are unverifiable as written (F-0425).
One answer was corrected after verification contradicted it — see A3 (F-0424).

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
# BLOCK C — Marketing data Tejas needs: is it STORED? (C29–C45)
Answered by priya · fresh-context · 2026-09-02

**C29. NOT STORED** — no signup-time UTM/traffic-source capture anywhere; `utm_campaigns` is a per-creator-per-campaign *outbound tracking link*, unrelated to signups.
Evidence: `influora-api/src/main/resources/db/migration/V23__utm_campaigns.sql:22` — keyed by `campaign_id` / `collaboration_id` / `creator_profile_id`, no `user_id`; `influora-api/src/main/java/com/influora/domain/entity/UtmCampaign.java:40` confirms the same three FKs. Grep for `signup_source|acquisition_channel` over `java/` and `db/migration/` returns zero hits.

**C30. NOT STORED** — no `Referral` entity, no referral/invite-attribution table, no `referred_by` column.
Evidence: `influora-api/src/main/java/com/influora/web/AdminMarketingController.java:20` — "no signup-source attribution, no Referral table". Grep for `referral|referred_by` across `db/migration/` returns zero hits. The one adjacent table, `workspace_member_invites` (`V59`), is intra-workspace seat invites, not acquisition attribution.

**C31. NOT STORED** — `users` has no channel/source column at all.
Evidence: `influora-api/src/main/resources/db/migration/V2__core_auth.sql:4` — full `users` column list is id, email, phone_number, password_hash, user_type, status, email_verified, phone_verified, onboarding_completed, display_name, first_name, last_name, avatar_url, timezone, last_login_at, created_at, updated_at. Later migrations add only `kyc_prompt_dismissed` (`V20260809120000`) and `deleted_at` (`V61`). `user_type` is BRAND/CREATOR/ADMIN — a role, not an acquisition channel.

**C32. BOTH — one flag STORED, the percentage COMPUTED.**
Evidence: `influora-api/src/main/resources/db/migration/V2__core_auth.sql:12` — `onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE` persisted on `users`, written at `influora-api/src/main/java/com/influora/service/OnboardingService.java:93` and `influora-api/src/main/java/com/influora/service/CreatorOnboardingService.java:128`.
Evidence: `influora-api/src/main/java/com/influora/service/CreatorProfileService.java:267` — `static int calculateCompleteness(...)` sums weights (displayName 10, username 10, bio 10, avatar 10, categories 15, city 10, rates 15, platforms 20) on every read; nothing persists it. Called inline at `CreatorProfileService.java:254`.

**C33. COMPUTED — 5 live COUNT queries across 4 tables, no stored aggregate.**
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminMarketingService.java:94` — the full trace:
- `signups` ← `userRepository.count()` (line 97) = **all** `users` rows, brands + creators + admins combined.
- `firstCampaign` ← `campaignRepository.countBrandWorkspacesWithCampaign()` (line 98), defined at `influora-api/src/main/java/com/influora/repository/CampaignRepository.java:63` as `SELECT COUNT(DISTINCT c.workspace_id) FROM campaigns c JOIN workspaces w ... WHERE w.type='BRAND'`.
- `repeatCampaign` ← `CampaignRepository.java:78`, same join with `HAVING COUNT(*) >= 2`.
- `creatorApplicationToApproval` ← `creatorProfileRepository.countByApplicationStatus(APPROVED) / creatorProfileRepository.count()` (lines 101–108).
- `brandSignupToFirstCampaign` ← `firstCampaign / workspaceRepository.countByType(BRAND)` (lines 110–112).
Both ratios guard the zero denominator to `0.0`. `calculatedAt` is `Instant.now()` (line 117) — nothing is persisted; every call re-runs the queries.

**C34. Populated: 6 of 6 served fields. Omitted from the DTO entirely: 3 fields the frontend type declares.**
Evidence: `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:73` — the record is only `funnel(signups, firstCampaign, repeatCampaign)`, `conversionRates(creatorApplicationToApproval, brandSignupToFirstCampaign)`, `calculatedAt`. All six populated; none ever null (primitives `long`/`double`).
Evidence: `src/admin/types/admin.types.ts:758` — the FE `GrowthMetrics` declares three extra members, all correctly marked optional (`profileComplete?`, `cohortRetention?`, `referralStats?`) with comments naming the backend omission. The FE/BE contract does not diverge dangerously here — the fields are `?`-optional, so no empty-state bug of the PHONE-0829 kind.

**C35. COMPUTED — 4 live aggregates over 2 tables (`reviews`, `disputes`).**
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminMarketingService.java:68`:
- `overall` ← `ReviewRepository.java:55` — `SELECT AVG(r.stars) FROM Review r WHERE r.hidden=false`.
- `creatorQualityAvg` / `brandSatisfactionAvg` ← `ReviewRepository.java:51`, same average filtered by `reviewerType = BRAND` / `CREATOR`.
- `disputeResolutionSpeed` ← `DisputeRepository.java:84` — `AVG(TIMESTAMPDIFF(SECOND, created_at, resolved_at)) FROM disputes WHERE resolved_at IS NOT NULL`, divided by 3600 at `AdminMarketingService.java:77`.
Nulls coalesce to `0.0` (line 120), so "no data yet" is indistinguishable from "genuinely zero" in the response.

**C36. NOT STORED** — no NPS, CSAT, or survey table of any kind.
Evidence: grep for `nps|csat|survey|satisfaction_score` across `influora-api/src/main/java/` and `influora-api/src/main/resources/` returns zero hits. What exists instead: `reviews` (`V43__reviews.sql`) — 1–5 star peer ratings tied to a completed collaboration, surfaced as `brandSatisfactionAvg` at `AdminMarketingService.java:72`. Transaction-level peer feedback, not a solicited platform survey; no unprompted-sentiment instrument.

**C37. NOT STORED** — email is send-side only; no per-recipient engagement.
Evidence: `influora-api/src/main/resources/db/migration/V18__email_outbox.sql:3` — `email_outbox` columns are status (`PENDING/SENT/FAILED`), `retry_count`, `next_retry_at`, `sent_at`, `error_message`. No `opened_at`, `clicked_at`, `bounced_at`. `sent_at` means "handed to MSG91", not delivered. No webhook table ingests ESP engagement events. (The `clicked_at` grep hits are unrelated: `nudge_log` at `V51__trendspark.sql:50` and `NudgeLog.java:57` are in-app nudges.)

**C38. PARTIAL — campaign→sales attribution is stored; channel→brand acquisition attribution is not.**
Evidence STORED: `influora-api/src/main/resources/db/migration/V23__utm_campaigns.sql:39` — `click_count`, `unique_visitors`, `conversion_count`, `revenue_attributed` per creator per campaign, written by `influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingService.java:102` (`recordConversion`) and `CampaignLinkService.java:160`. Plus `coupon_redemptions` (`V24`) and `affiliate_earnings` (`V28`).
Evidence NOT STORED: `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:9` — the `SourceAttribution` shape the FE declares at `src/admin/types/admin.types.ts:745` (source → brandSignups/creatorSignups/revenue) has no backing table and `/acquisition` is not served. Which *creator* drove which *sale* is measurable; which *channel* produced which *brand* is not.

**C39. DERIVABLE — yes, from two stored timestamps.**
Evidence: `influora-api/src/main/resources/db/migration/V2__core_auth.sql:37` — `workspaces.created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`; `influora-api/src/main/resources/db/migration/V4__campaigns.sql:23` — `campaigns.created_at`, joined via `campaigns.workspace_id` (`V4__campaigns.sql:3`, FK at line 27). `MIN(campaigns.created_at) − workspaces.created_at` grouped by `workspace_id` gives time-to-first-campaign. `users.created_at` (`V2:19`) is the alternative anchor. No query computes this today — `countBrandWorkspacesWithCampaign()` counts *whether*, never *when*.

**C40. NOT STORED** — no spend figure of any kind.
Evidence: grep for `ad_spend|marketing_spend|cac|customer_acquisition|acquisitionCost` across `java/` and `resources/` returns exactly one hit — the javadoc at `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:8` explaining the endpoint is unimplemented. The FE `AcquisitionMetrics.cac` shape (`src/admin/types/admin.types.ts:733`) splits paid/organic/referral for brands and creators; none of the three inputs exists. CAC is not merely uncomputed — both the numerator (spend) and the denominator's channel split are absent.

**C41. NEITHER — no cohort table, and no on-the-fly computation either.**
Evidence: grep for `cohort` across `java/` and `db/migration/` returns zero hits (the `retention` hits are all `MeeraInteractionLogRetentionPurgeJob` — a log-purge TTL, unrelated to user retention). `src/admin/types/admin.types.ts:775` declares `CohortRetention {cohort, day30, day60, day90}`, optional and never served. `users.last_login_at` (`V2:18`) is the only activity signal and is overwritten on each login — a single scalar, so no historical activity series exists to reconstruct retention from retroactively.

**C42. NOT STORED for the marketing site.** Creator *portfolio* pageviews are stored; the public marketing pages record nothing server-side.
Evidence NOT STORED: grep for `gtag|googletagmanager|posthog|mixpanel|plausible|analytics.track` across `src/` and `index.html` returns zero real hits. No endpoint ingests a pageview beacon.
Evidence STORED (app-side only): `influora-api/src/main/resources/db/migration/V20260718120000__portfolio_events.sql:24` — `portfolio_events(creator_profile_id, event_type, occurred_at, visitor_hash)` with `event_type` VIEW / MEDIA_KIT_DOWNLOAD / LINK_CLICK, counted at `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:412` (`computePageViews`). Scoped to one creator's portfolio page, not `/pricing`, `/features/*`, or the landing page.

**C43. YES — but it does not do what the name suggests to a CMO.**
Evidence: `influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:88` — `@Scheduled(cron = "0 45 3 * * *")`, daily at 03:45, ShedLock-guarded (`lockAtMostFor = PT20M`).
Writes `platform_stats` (upsert per creator per platform) and denormalized totals onto `creator_profiles` via `applyAggregatedStats`, reading the latest `creator_metrics` row per platform — `PlatformStatsAggregationJob.java:35`.
Scope note: `platform_stats` is **per-creator social-media stats** (followers, engagement rate, handle, verified) — `influora-api/src/main/java/com/influora/domain/entity/PlatformStat.java:18`. Discovery-ranking substrate, not platform-wide business metrics. `SUPPORTED_PLATFORMS = List.of("INSTAGRAM")` only (line 62), and `engagement_rate` is still never written because `MetricsPollingJob` does not populate `avgEngagementRate` (lines 47–51). There is no job aggregating marketing/growth metrics.

**C44. INFERRED FROM STATE — no churn event rows.**
Evidence: `influora-api/src/main/java/com/influora/repository/SubscriptionRepository.java:39` — churn is counted by `countByStatusAndPlanIdAndCompFalseAndUpdatedAtAfter(...)`, i.e. rows currently in `CANCELLED` whose `updated_at` falls in the window. Because `updated_at` is overwritten on every transition (`Subscription#setStatus` → `touch()`), a row that cancels and later changes again loses its cancellation date entirely.
Evidence of the resulting approximation: `influora-api/src/main/java/com/influora/service/admin/AdminBillingService.java:173` — `activeAtWindowStart` is approximated as `currentActivePro + churnedInWindow`, which the javadoc itself admits undercounts anyone who subscribed and churned inside the same 30-day window (`CHURN_WINDOW_DAYS = 30`, line 211). Creator dormancy: not stored and not computed — the only `DORMANT` constant is `ConversationStatus.DORMANT` (`influora-api/src/main/java/com/influora/domain/enums/ConversationStatus.java:6`), an AI-chat state, not a creator lifecycle state.

**C45. Only entity tables plus domain-specific logs — there is no generic event/telemetry table.** 80 tables total; the log/audit/event family:

| Table | Evidence | Scope |
|---|---|---|
| `audit_log` | `V15__audit_log.sql:9` | money-affecting Meera tool calls + auth rejections |
| `admin_audit_log` | `V34__admin_tables.sql:48` | admin-panel actions |
| `error_log` | `V20260718170000__admin_error_log.sql:7` | server errors |
| `portfolio_events` | `V20260718120000__portfolio_events.sql:24` | creator portfolio VIEW/DOWNLOAD/CLICK |
| `application_history_events` | `V69__application_history_events.sql:35` | fixed 14-value deal-lifecycle ENUM |
| `meera_interaction_log` | `V20260721160000__meera_interaction_log.sql:32` | AI chat, 180-day purge |
| `meera_tool_calls` | `V14__ai_credits_tool_calls.sql` | AI tool invocations |
| `nudge_log` / `creator_nudge_log` | `V51__trendspark.sql:39`, `V20260721140000:17` | in-app nudges |

Decisive constraint: `application_history_events.event_type` is a DB ENUM (`V69__application_history_events.sql:44`) — adding a marketing event type is a schema migration, not an app change. `audit_log.event_type` is a free VARCHAR (`V15:14`) but is insert-only for money/auth paths and carries no session, source, or visitor identity. Nothing in this list can absorb an arbitrary product-telemetry event.

## Block C summary

Transaction-side marketing data is real; acquisition-side marketing data does not exist. Once a brand is on the platform, the codebase can tell you what every creator sold (`utm_campaigns` clicks/conversions/revenue, coupons, affiliate earnings). It cannot tell you where a single user came from, what a signup cost, or whether a cohort stuck around.

Three flags raised by the answering context:

1. `AdminMarketingDtos.java:13` says "no profile-completion flag" — but `users.onboarding_completed` is persisted (`V2:12`) and `calculateCompleteness()` already returns a real 0–100 score (`CreatorProfileService.java:267`). `funnel.profileComplete` is the one omitted field buildable today with a single COUNT, no migration.
2. `src/pages/landing.tsx:169` renders "4,812 link clicks / 231 conversions / ₹3.4L revenue attributed" as a hardcoded scoreboard while the plumbing to produce real figures exists. Needs a deliberate ruling.
3. Cheapest unlock is a `utm_source` column on `users` plus one telemetry table — would immediately populate `SourceAttribution`, which the FE type already declares. Cohort retention is the harder gap: `last_login_at` is overwritten on every login, so **retention cannot be backfilled**. Every day without event capture is a cohort permanently lost.

No UNDETERMINED items in this block.
# BLOCK D — Marketing data: is it SHOWN in the UI? (D46–D57)
# BLOCK E — User base: what admin can see about a USER (E58–E67)
Answered by priya · fresh-context · 2026-09-02

## MARKETING IN THE UI

**D46.** The growth funnel is one card inside `FinanceConsole`'s Revenue tab, at route `/admin/revenue` — there is no dedicated marketing/growth page in the admin console.
Evidence: `src/admin/components/finance/FinanceConsole.tsx:428` — "Growth Funnel & Conversion" card heading; `src/pages/admin-console.tsx:58` — `<Route path="revenue" element={<RevenuePage />} />`; `src/admin/pages/RevenuePage.tsx:38` — `<FinanceConsole />`; `src/App.tsx:622` — `/admin/*` mounts `AdminConsolePage` behind `AdminProtectedRoute`.

**D47.** Platform reputation renders as a sibling card in the same Revenue tab (`overall`, `creatorQualityAvg`, `brandSatisfactionAvg`, `disputeResolutionSpeed` in hours + a "Calculated …" timestamp).
Evidence: `src/admin/components/finance/FinanceConsole.tsx:386` — "Platform Reputation" heading, values at `:396`, `:401`, `:407`, `:413`, timestamp at `:419`.

**D48.** Hidden, not zeroed. `funnel.profileComplete`, `cohortRetention` and `referralStats` are declared optional on the TS type and are never referenced by any component — the UI renders a one-line prose disclaimer instead of a `0`.
Evidence: `src/admin/types/admin.types.ts:756` — `profileComplete?: number;` (also `:769` `cohortRetention?`, `:771` `referralStats?`); `src/admin/components/finance/FinanceConsole.tsx:463` — "Cohort retention and referral analytics are not tracked yet (no retention-event or referral data)"; grep for `profileComplete` across `src/admin/components/` returns zero hits.

**D49.** No. Nothing in the admin UI shows a signup-source breakdown, and nothing in the schema backs one. The `AcquisitionMetrics`/`SourceAttribution` types exist but the only client method is a non-network stub with zero callers.
Evidence: `src/admin/services/api-contracts.ts:780` — `getAcquisition: (startDate, endDate) => unavailable<AcquisitionMetrics>('acquisition metrics … — marketing analytics not built')`; `src/admin/types/admin.types.ts:742` — `sourceAttribution: SourceAttribution[]` (type only, no renderer); `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:8` — "no ad-spend and no signup-source attribution anywhere in the schema". `V23__utm_campaigns.sql` is per-campaign creator tracking links, not signup attribution.

**D50.** No. Email opens/clicks are neither stored nor shown. The email surface is a delivery queue only (`EmailStatus` = queued/sent/failed etc.), with retry — no engagement fields anywhere.
Evidence: `src/admin/types/admin.types.ts:66` — `export enum EmailStatus` (delivery states only); `src/admin/pages/EmailQueuePage.tsx` has no open/click reference (only `handleClick`/`onClick` DOM handlers at `:125`, `:137`). No `opens`/`clicks` field exists in `AdminEmailDtos.java`.

**D51.** No. `CohortRetention` is a declared-but-unserved optional field; no cohort or retention curve is rendered anywhere.
Evidence: `src/admin/types/admin.types.ts:769` — `/** Omitted by the backend — no retention/activity-event tracking exists yet */ cohortRetention?: CohortRetention[];`; `src/admin/components/finance/FinanceConsole.tsx:463` — the "not tracked yet" note stands in its place.

**D52.** Partially — but from the *dashboard* endpoint, not the marketing one. "Creator MAU" is a real KPI tile; there is no "new creators" metric. The creator list also shows a total count.
Evidence: `src/admin/components/dashboard/PulseDashboard.tsx:90` — `{ title: 'Creator MAU', value: formatCompactNumber(data.mauCreators), icon: 'creators' }`; `influora-api/src/main/java/com/influora/service/admin/AdminDashboardStatsCache.java:83` — `userRepository.countByUserTypeAndLastLoginAtAfter(UserType.CREATOR, mauSince)` (login-recency proxy). No `newCreators`/signup-rate field exists on `CeoPulseDataDto` (`AdminDashboardDtos.java:19`).

**D53.** No. There is no CSV or report download for any marketing metric anywhere in `src/admin/`. The only download-shaped thing in the admin client is a TDS tax stub that never issues a request.
Evidence: `src/admin/services/api-contracts.ts:430` — `unavailable<{ downloadUrl: string }>('TDS 26Q … — TDS engine unimplemented')`; grep for `csv|Blob|download` across `src/admin/` returns only that line plus unrelated `export` keywords. `influora-api/src/main/java/com/influora/web/ReportExportController.java:37` (`/{campaignId}/export`) is a brand-side campaign report with zero admin callers.

**D54.** Fixed window — there is no date-range selector on the marketing data. The period `<Select>` on that page only parameterizes the revenue trend; `getGrowth()` and `getReputation()` take no arguments and always return an all-time snapshot.
Evidence: `src/admin/components/finance/FinanceConsole.tsx:272` — `<Select value={period} onValueChange={…}>`; `src/admin/hooks/useFinanceConsole.ts:125` — only `dashboardApi.getFinancialSummary(period)` receives it, while `:132` is `marketingApi.getGrowth()` (no params); `influora-api/src/main/java/com/influora/web/AdminMarketingController.java:53` — `@GetMapping("/growth")` accepts only the principal.
Side effect worth noting: changing the period re-fetches growth (dep array `[period, reloadKey]` at `useFinanceConsole.ts:227`) but gets the identical all-time numbers back.

**D55.** Mount only. No polling interval, no WebSocket subscription — and the hook's `refresh()` is never destructured by the console, so there is not even a manual refresh button on this page.
Evidence: `src/admin/hooks/useFinanceConsole.ts:227` — `}, [period, reloadKey]);` with no `setInterval` in the file; `src/admin/components/finance/FinanceConsole.tsx:162` — the destructure omits `refresh`; no `useAdminSocket` import in either file.

**D56.** A 500 degrades gracefully and only affects the growth card: `Promise.all` still resolves (each call resolves to an `ApiResponse`, never rejects), `growth` is set to `null`, an error string is stored, and the UI renders both a red banner at the top of the console and "Growth metrics not available." inside the card. Every other panel keeps its data.
Evidence — the full path:
- `src/admin/services/api-contracts.ts:84` — `if (!response.ok) { … return { success: false, error: message }; }` (returns, does not throw; message from `body?.error?.message ?? body?.message ?? "Request failed (status)"`)
- `src/admin/hooks/useFinanceConsole.ts:198` — `if (growthRes.success && growthRes.data) { setGrowth(...) } else { setGrowth(null); nextErrors.growth = growthRes.error ?? 'Failed to load growth metrics'; }`
- `src/admin/components/finance/FinanceConsole.tsx:192` — `{errors.growth && <ErrorNotice message={...} />}`
- `src/admin/components/finance/FinanceConsole.tsx:432` — `) : !growth ? (<p …>Growth metrics not available.</p>`
The `.catch()` at `useFinanceConsole.ts:208` is a network-level fallback that blanks the whole console; an HTTP 500 does not reach it.

**D57.** No. Every marketing figure on screen is read straight off the endpoint. The only client-side arithmetic is presentational (`* 100` to render a 0..1 rate as a percentage, `.toFixed(1)`). The one derived value on that page — `latestBucket` — is revenue, not marketing.
Evidence: `src/admin/components/finance/FinanceConsole.tsx:452` — `{(growth.conversionRates.creatorApplicationToApproval * 100).toFixed(1)}%` (formatting only); the ratio itself is computed server-side at `influora-api/src/main/java/com/influora/service/admin/AdminMarketingService.java:102`. `FinanceConsole.tsx:176` — `const latestBucket = revenue.length > 0 ? revenue[revenue.length - 1] : null;` is the GMV/revenue KPI, sourced from `dashboardApi.getFinancialSummary`.

## THE USER BASE

**E58.** `User` is the root record. Identity fields: `id` (ULID, 26 chars), `email` (unique), `phoneNumber` (unique), `passwordHash`, `userType` (BRAND/CREATOR), `status`, `emailVerified`, `phoneVerified`, `onboardingCompleted`, `displayName`, `firstName`, `lastName`, `avatarUrl`, `timezone`, `lastLoginAt`, `createdAt`, `updatedAt`, `deletedAt`.
Evidence: `influora-api/src/main/java/com/influora/domain/entity/User.java:15` — `public class User` on `@Table(name = "users")`; fields at `:19`–`:90`. Brand-facing data hangs off `Workspace`, creator-facing off `CreatorProfile` — the admin brand/creator lists are keyed on those, joining back to `User` only for email/phone (`AdminCreatorService.java:183`).

**E59.** Stored and unique; **verified flag stored but never surfaced**; the number itself IS shown to admin for creators (list column + detail card) but NOT for brands.
Evidence: stored — `User.java:24` (`@Column(name = "phone_number", unique = true)`), verified flag at `User.java:41` (`phoneVerified`); served — `AdminCreatorDtos.java:28` (`String phone` on `CreatorSummaryDto`) and `:45` (on `CreatorDetailDto`), populated at `AdminCreatorService.java:523`; rendered — `src/admin/pages/UsersPage.tsx:440` (`<TableHead>Phone</TableHead>`) and `src/admin/components/users/CreatorProfile.tsx:396`. `AdminBrandDtos.java:52` (`BrandDetailDto`) has no phone field. `phoneVerified` appears in no admin DTO.

**E60.** Stored, but **not shown to admin for platform users**. `lastLoginAt` exists on `User` and is written on every login, and it is consumed server-side only to compute MAU. No admin brand/creator DTO carries it. The `lastLogin` in `admin.types.ts` is the *admin's own* session, not a platform user's.
Evidence: stored — `User.java:68` (`private Instant lastLoginAt;`), written at `User.java:265` (`markLogin()`); consumed — `AdminDashboardStatsCache.java:82` (`countByUserTypeAndLastLoginAtAfter`); absent — grep for `lastLogin` across `influora-api/src/main/java/com/influora/service/admin/` and `web/dto/admin/` hits only `AdminAuthDtos.java:29` (`Instant lastLogin` on the AdminUser record) and `AdminAuthService.java:331`.

**E61.** Stored, **not shown to admin**. `emailVerified` is a real column with real state transitions, but it appears in zero admin DTOs and zero admin components.
Evidence: stored — `User.java:38` (`@Column(name = "email_verified", nullable = false)`), transition logic at `User.java:278` (flipping it promotes `PENDING_VERIFICATION` → `ACTIVE`); absent — grep for `emailVerified|isEmailVerified` across `influora-api/src/main/java/com/influora/service/admin/`, `web/dto/admin/` and `src/admin/` returns zero hits. Admin sees only the derived `isSuspended` / KYC / application status (`AdminCreatorDtos.java:33`, `AdminBrandDtos.java:36`).

**E62.** No, on both counts. There is no per-user login history table and no user IP is ever recorded. The only IP in the system is the *acting admin's* IP on `admin_audit_log`, and the only IP read at request time is for auth rate-limiting (not persisted against the user).
Evidence: `influora-api/src/main/java/com/influora/domain/entity/AdminAuditLog.java:62` — `@Column(name = "ip_address", nullable = false, length = 45)`, sourced from the admin's request at `AdminAuditLogService.java:347` (`.ipAddress(clientIp(request))`) alongside `adminId`/`adminEmail` (`:36`) — an admin-action log, not a user-session log. `AuthRateLimitFilter.java:551` — `return request.getRemoteAddr();` used for throttling only. No `login_history`/`user_sessions` migration exists.

**E63.** Two separate lists. `/admin/users` is a tabbed page with an independent search box, filter set and pagination per tab, hitting two different endpoints. There is no unified cross-type search.
Evidence: `src/admin/pages/UsersPage.tsx:243` — brands box, `placeholder="Search brand name or email…"`; `:397` — creators box, `placeholder="Search creator name or email…"`; `:6` (header) — `GET /admin/brands -> PaginatedBrandResponse` and `GET /admin/creators -> PagedCreatorsDto`, backed by separate hooks `useBrandList` / `useCreatorList` (`:58`).
Caveat: the creator search placeholder promises email but the server-side spec matches `displayName` only — `influora-api/src/main/java/com/influora/repository/CreatorProfileSpecs.java:42` (`cb.like(cb.lower(root.get("displayName")), searchLower)`), documented as a known simplification at `:16`.

**E64.** No impersonation exists — so the logging question is moot. Grep for `impersonat|loginAs|actAs|sudo` across both `src/` and `influora-api/src/main/java/` returns zero hits.
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:255` — the only token-minting path is `jwtService.createAccessToken(admin.getId(), UserType.ADMIN, admin.getEmail(), null)`, i.e. a token for the admin as themselves; no path mints a token for another user's id. (Admin *actions on* a user — KYC verify, suspend, reinstate, tier adjust — are logged via `AdminAuditLogService`.)

**E65.** Suspended users: yes, visible and explicitly filterable. Soft-deleted users: yes, still visible — and this looks unintended. No admin query filters on `deletedAt`, so a soft-deleted account keeps appearing in the brand/creator lists with its PII nulled out (blank name, blank email).
Evidence: suspended — `src/admin/pages/UsersPage.tsx:173` ("Suspended Only" toggle), spec at `CreatorProfileSpecs.java:35` (`cb.equal(root.get("suspended"), suspended)`), field at `AdminCreatorDtos.java:33`. Soft-deleted — `User.java:302` `softDelete()` nulls `email`/`phoneNumber`/`displayName`/`firstName`/`lastName`/`avatarUrl` and stamps `deletedAt`; grep for `deletedAt|deleted_at` across `influora-api/src/main/java/com/influora/repository/` returns **zero** hits, and `CreatorProfileSpecs.withFilters` (`:26`) builds predicates only for applicationStatus/suspended/search. The list joins `User` purely for display values (`AdminCreatorService.java:183`), so those rows render as blanks rather than being excluded.

**E66.** Deletion: implemented, but **self-serve only** — there is no admin-initiated deletion. Data export/portability: **not implemented at all**.
Evidence: deletion — `influora-api/src/main/java/com/influora/web/AccountController.java:44` — `@DeleteMapping("/account")` under `@RequestMapping("/me")`, gated on `@AuthenticationPrincipal AuthPrincipal principal` and operating on `principal.getUserId()` (`:50`); FE at `src/pages/creator-settings.tsx:328` (`api.me.deleteAccount('creator')`). It is a soft delete that deliberately does not cascade (`AccountController.java:28` javadoc). Export — grep for `my-data|data-request|portability|/export` across `influora-api/src/main/java/com/influora/web/` returns only `ReportExportController.java:37` (a campaign performance report), nothing user-scoped. DPDP obligations are stated in policy text (`src/content/legal/privacy-policy.md:65` — "Your rights under the DPDP Act") with no code path behind the access/portability rights.

**E67.** Nowhere. There is no admin endpoint or UI control that changes a user's `userType` or workspace role. `userType` on `User` has no setter at all — it is only ever assigned in the two static factories.
Evidence: `User.java:30` — `private UserType userType;` with a getter at `:181` and **no setter**; assigned only at `:105` (`newBrand`) and `:131` (`newCreator`). Admin mutation DTOs deliberately exclude role: `AdminBrandDtos.java:131` — `UpdateBrandRequest(name, industry, size, email)`; `AdminCreatorDtos.java:115` — `UpdateCreatorRequest(name, niche)`, with `:112` noting "Any out-of-allow-list JSON property is dropped by Jackson — the record shape IS the allow-list." The only role surface is brand-side and invite/remove only, not change: `influora-api/src/main/java/com/influora/web/WorkspaceMemberController.java:51` (`POST /invite`, role set at creation, parsed at `:54`) and `:66` (`DELETE /{memberId}` deactivate) — no PATCH/PUT role endpoint exists. This matches the known operational workaround that a member's role is corrected by data fix, not by API.

## Block D+E flags raised beyond the questions asked

- **E65** — soft-deleted users leak into admin lists as blank rows, because no repository filters `deletedAt`. Reads as a real defect, not a design choice.
- **E66** — no DPDP data-access/portability path despite the published policy promising one. Compliance exposure, not just a missing feature.
# BLOCK F — Brand base (F68–F79)
# BLOCK G — Creator base (G80–G91)
Answered by priya · fresh-context · 2026-09-02

## BRAND

**F68.** 13 fields per list row: `id, name, email, industry, size, kycStatus, kycReviewedBy, kycReviewedAt, kycRejectionReason, campaignCount, totalSpend, isSuspended, createdAt`.
Evidence: `influora-api/src/main/java/com/influora/web/dto/admin/AdminBrandDtos.java:30` — `record BrandSummaryDto(...)` through `:43`; assembled at `AdminBrandService.java:817`.

**F69.** 6 extra: `gstNumber, panNumber, incorporationDoc, billingAddress, teamMembers[], campaigns[], paymentHistory[]` — but `incorporationDoc` is hardcoded `null`, never sourced.
Evidence: `AdminBrandDtos.java:66` — the extra fields; `AdminBrandService.java:700` — `null, // incorporationDoc — not modeled distinctly from kyc_gstin_doc_url/kyc_pan_doc_url yet`.

**F70.** Yes, stored as `workspaces.verification_status` with 4 values UNVERIFIED / PENDING / VERIFIED / REJECTED. The admin API collapses it to 3 (PENDING/APPROVED/REJECTED) — UNVERIFIED and PENDING both render as PENDING, so admin cannot tell "never started" from "awaiting review".
Evidence: `influora-api/src/main/java/com/influora/domain/entity/Workspace.java:47` — `@Column(name = "verification_status", nullable = false)`; `influora-api/src/main/java/com/influora/domain/enums/VerificationStatus.java:4` (4 values); `AdminBrandService.java:792` — `case UNVERIFIED, PENDING -> KycStatusView.PENDING`.

**F71.** `POST /admin/brands/{id}/verify-kyc` with `action` = APPROVE|REJECT plus mandatory reason; SUPER_ADMIN/ADMIN, MFA-gated. UI control exists but is rendered ONLY when status is PENDING — an already-REJECTED brand has no button to re-approve.
Evidence: `influora-api/src/main/java/com/influora/web/AdminBrandController.java:114` — `@PostMapping("/{id}/verify-kyc")`; `AdminBrandService.java:277` — `workspace.applyKycDecision(...)`; `src/admin/components/users/BrandProfile.tsx:707` — `{brand.kycStatus === KycStatus.PENDING && (` gating both dialogs; handlers at `BrandProfile.tsx:203` and `:219`.

**F72.** Yes — stored and shown, in plaintext, unmasked. The KYC *document* URLs are stored but never surfaced.
Evidence: `Workspace.java:65` — `gstin` / `pan` columns; `AdminBrandDtos.java:66` — `String gstNumber, String panNumber`; `BrandProfile.tsx:499` and `:503` render them raw. `Workspace.java:71` — `kyc_gstin_doc_url` / `kyc_pan_doc_url` exist but no DTO field carries them (see F69).

**F73.** Persisted, but only partly shown. The onboarding step-2 payload has 8 fields; all 8 are written to `workspaces`. Admin sees 3 of them (name, industry, size). `companySlug`, `workspaceType`, `websiteUrl`, `description`, `logoUrl` are persisted and NOT on the admin brand detail. Signup phone is also persisted but never surfaced on the brand DTO (the creator DTO does surface phone).
Evidence: `influora-api/src/main/java/com/influora/web/dto/onboarding/OnboardingDtos.java:14` — `BrandCompanyRequest(companyName, companySlug, workspaceType, industry, companySize, websiteUrl, description, logoUrl)`; `influora-api/src/main/java/com/influora/service/OnboardingService.java:62` — `workspace.applyCompanyDetails(...)` writes all 8; `AdminBrandDtos.java:52` — the detail record has no websiteUrl/description/logoUrl/slug/phone field. Phone: `influora-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest.java:27` → `users.phone_number`, and a separate `Workspace.java:59` business `phone` column, neither in the DTO.
Note: `BrandProfile.java` (the entity) is NOT the brand record the admin panel reads — it is the website-analysis cache (`brand_profiles`: websiteUrl, analysisStatus, productCatalog, brandAesthetic, toneProfile, nicheTags, competitorUrls, themeTags). Evidence: `influora-api/src/main/java/com/influora/domain/entity/BrandProfile.java:15`; no admin service imports it (`AdminBrandService.java:4` imports `Workspace`, not `BrandProfile`).

**F74.** Yes — exactly 4 fields: `name`, `industry`, `size` (validated against STARTUP/SMB/ENTERPRISE), `email` (maps to `workspaces.billing_email`). Everything else is unbindable by DTO shape.
Evidence: `AdminBrandDtos.java:131` — `record UpdateBrandRequest(name, industry, size, email)`; `AdminBrandService.java:373` — size union check; `AdminBrandService.java:405` — `workspace.applyAdminProfileEdit(...)`; UI at `BrandProfile.tsx:289`.

**F75.** Endpoint and UI are wired; the *effect* is largely missing. `suspend` sets `workspaces.is_suspended`. Login gates on `users.status`, which this write never touches — so a suspended brand can still log in and keep operating in its current workspace. The only enforcement anywhere is workspace *switching*.
Evidence: endpoints `AdminBrandController.java:123` and `:132`; service `AdminBrandService.java:306` — `workspace.suspend(reason, admin.getId())`; UI `BrandProfile.tsx:808`. Enforcement: `influora-api/src/main/java/com/influora/service/WorkspaceService.java:189` — `if (workspace.isSuspended())` in `switchWorkspace` is the ONLY non-admin read of the flag. Login checks a different field: `influora-api/src/main/java/com/influora/service/AuthService.java:234` — `if (user.getStatus() == UserStatus.SUSPENDED || ... DEACTIVATED)`.

**F76.** No wallet balance, no escrow holdings. The API returns the last 20 wallet transactions, and even those are never rendered.
Evidence: `AdminBrandDtos.java:52` — no balance/escrow field on `BrandDetailDto`; `AdminBrandService.java:754` — `paymentHistory(...)` caps at 20 txns; `src/admin/types/admin.types.ts:176` declares `paymentHistory` but grep for it across `src/admin/components/` returns zero renders. Escrow admin views are platform-wide, not brand-scoped: `influora-api/src/main/java/com/influora/web/dto/admin/AdminFinanceDtos.java:39` — `EscrowSummaryDto(totalLocked, pendingRelease, flaggedTransactions, averageReleaseTime)`.

**F77.** Not on the brand screen. Subscription data exists only on a separate platform-wide billing page, keyed by workspaceId.
Evidence: `AdminBrandDtos.java:52` — no plan/billing field; `influora-api/src/main/java/com/influora/web/dto/admin/AdminBillingDtos.java:24` — `AdminSubscriptionRowDto(...)` served by `influora-api/src/main/java/com/influora/web/AdminBillingController.java:104` — `@GetMapping("/subscriptions")`, a different page with no link from brand detail.

**F78.** The data is returned, but there is no campaign list on the screen — campaigns appear only as an ACTIVE count tile and as options in the budget-override `<select>`.
Evidence: `AdminBrandDtos.java:71` — `List<CampaignSummaryDto> campaigns`; `BrandProfile.tsx:486` — `<KpiCard title="Active Campaigns" .../>`; `BrandProfile.tsx:582` — `{brand.campaigns.map((c) => (` inside the "Override Campaign Budget" card (`:549`), not a table.

**F79.** Computed per request — no `total_spend` column exists. It is summed from FUNDED + RELEASED escrow holds on every list and detail call.
Evidence: `AdminBrandService.java:226` — `if (hold.getStatus() == EscrowStatus.FUNDED || hold.getStatus() == EscrowStatus.RELEASED)` in `list()`; same sum re-done per campaign at `AdminBrandService.java:656`. Cost note: the list path does an O(holds × campaigns) inner scan at `AdminBrandService.java:227`.

## CREATOR

**G80.** 10 fields: `id, name, email, phone, instagramHandle, followers, applicationStatus, tier, isSuspended, createdAt`.
Evidence: `influora-api/src/main/java/com/influora/web/dto/admin/AdminCreatorDtos.java:20` — `record CreatorSummaryDto(...)`; assembled at `influora-api/src/main/java/com/influora/service/admin/AdminCreatorService.java:505`.

**G81.** Yes — `creator_profiles.application_status`, values PENDING / APPROVED / REJECTED, with a review trail (`application_reviewed_by`, `application_reviewed_at`, `application_rejection_reason`).
Evidence: `influora-api/src/main/java/com/influora/domain/entity/CreatorProfile.java:122`; `influora-api/src/main/java/com/influora/domain/enums/CreatorApplicationStatus.java:11` (3 values).

**G82.** `POST /admin/creators/{id}/review-application`, action APPROVE|REJECT + mandatory reason. UI exists, gated on PENDING only. There is also a dedicated queue endpoint `GET /admin/creators/applications/pending`.
Evidence: `influora-api/src/main/java/com/influora/web/AdminCreatorController.java:117`; `AdminCreatorService.java:242` — `profile.applyApplicationDecision(...)`; queue at `AdminCreatorController.java:75` / `AdminCreatorService.java:478`; UI `src/admin/components/users/CreatorProfile.tsx:702`, handlers at `:210` and `:230`.

**G83.** Partly stored: only the *override* is a column (`creator_profiles.tier_override`). When it is null the tier is derived from follower count at read time. Admin can change it from the UI.
Evidence: `CreatorProfile.java:141` — `@Column(name = "tier_override")`; `AdminCreatorService.java:612` — `resolveTier` returns override else `deriveTier(followers)`; `AdminCreatorService.java:599` — the follower thresholds; endpoint `AdminCreatorController.java:108` — `@PutMapping("/{id}/tier")`; UI `CreatorProfile.tsx:594` / handler `:337`.

**G84.** Connection state IS stored (`meta_oauth_tokens`), but admin sees only a first-connect timestamp. The field labelled `instagramVerified` is Instagram's blue-check flag, not connection state, and `instagramUserId` is hardcoded null. Worse, the "Force Instagram Re-auth" button is gated on that wrong signal.
Evidence: `influora-api/src/main/java/com/influora/domain/entity/MetaOAuthToken.java:80` — `encrypted_access_token`, `expires_at`, `revoked`, `last_refreshed_at`; `AdminCreatorService.java:543` — only `MetaOAuthToken::getCreatedAt` is exposed, as `instagramOauthAt`; `AdminCreatorService.java:558` — `instagram != null && instagram.isVerified()` sourced from `influora-api/src/main/java/com/influora/domain/entity/PlatformStat.java:33` (`is_verified`); `AdminCreatorService.java:566` — `null, // instagramUserId — never persisted anywhere in this schema`. UI defect: `CreatorProfile.tsx:804` — `{!creator.instagramVerified && (` hides the revoke control from every blue-check creator.

**G85.** Stored as `creator_profiles.total_followers`. Refreshed once daily at 03:45 by `PlatformStatsAggregationJob` off `creator_metrics` (itself polled every 6h), plus opportunistically by `PortfolioService`. Worst-case staleness is roughly 24h + poll lag, and admin is shown no freshness timestamp.
Evidence: `CreatorProfile.java:92` — `@Column(name = "total_followers", nullable = false)`; `influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:88` — `@Scheduled(cron = "0 45 3 * * *")` and `:183` — `creator.applyAggregatedStats(...)`; upstream poll `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:95` — `@Scheduled(cron = "0 0 */6 * * *")`; second writer `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:357`. No freshness field on `AdminCreatorDtos.java:40`.

**G86.** Stored and encrypted, with a mask column for display — but admin has no access to it at all. No admin controller, service, or DTO references it.
Evidence: `influora-api/src/main/java/com/influora/domain/entity/CreatorBankAccount.java:25` — `account_ciphertext`, `ifsc_ciphertext`, `display_mask`; grep for `CreatorBankAccount` returns `CreatorOnboardingService`, `CreatorTaxIdentityService`, `payout/*`, `WalletService`, `WalletController` — zero files under `service/admin/` or `web/dto/admin/`. The masking question does not arise on the admin surface: nothing is shown.

**G87.** Stored, not shown. `creator_profiles` carries `gstin`, `pan`, `tax_registration_status`, plus identity KYC (`identity_kyc_status`, `aadhaar_last4`, `selfie_url`). None reach the admin DTO. This is asymmetric with brands, where GST/PAN are shown in the clear (F72).
Evidence: `CreatorProfile.java:155` (gstin/pan/taxRegistrationStatus) and `:182` (identityKycStatus/aadhaarLast4/selfieUrl); absence confirmed against `AdminCreatorDtos.java:40`.

**G88.** Stored as a column, but in a time-series table, written nightly by a job — not computed per request. Admin reads the newest row and falls back to `0` when none exists, so a never-scored creator displays as score 0 rather than "not scored".
Evidence: `influora-api/src/main/java/com/influora/domain/entity/CreatorScore.java:40` — `@Table(name = "creator_scores")` and `:60` — `quality_score`; writer `influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:135` — `@Scheduled(cron = "0 0 4 * * *", zone = "UTC")`; reader `AdminCreatorService.java:577` — `findFirstByCreatorProfileIdOrderByTimeDesc(...).orElse(BigDecimal.ZERO)`.

**G89.** No. There is no earnings total and no pending-payout view per creator. The closest thing is a per-collaboration agreed rate list. The platform-level finance console is date-keyed, not creator-keyed.
Evidence: `AdminCreatorDtos.java:40` — no earnings/payout field; `AdminCreatorService.java:701` — `collab.getAgreedRate()` inside `CollaborationRecordDto`; `AdminCreatorDtos.java:87` — that record's shape. `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:60` — `@GetMapping("/reconciliation")` takes a date, not a creator id.

**G90.** Endpoints and UI exist; the effect is limited to marketplace visibility, not account access. `creator_profiles.is_suspended` excludes the creator from discovery and from deal assignment. It does not block login — login gates on `users.status`, which this write never touches.
Evidence: endpoints `AdminCreatorController.java:144` and `:153`; service `AdminCreatorService.java:314` — `profile.suspend(reason, admin.getId())`; UI `CreatorProfile.tsx:899` / `:850`. Real effects: `influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:900`, `:918`, `:929` — `.filter(p -> !p.isSuspended())`, and `influora-api/src/main/java/com/influora/service/DealService.java:223` — `.filter(profile -> !profile.isSuspended())`. Login unaffected: `AuthService.java:349` checks `user.getStatus()`.

**G91.** 13 creator-facing fields are absent from the admin detail: `username`, `avatarUrl`, `coverImageUrl`, `languages`, `contentStyles`, `platforms[]` (the full per-platform stat list — admin gets only the single Instagram handle), `rateMin`, `rateMax`, `currency`, `discoverable`, `verified` (the profile-level flag, distinct from the Instagram one admin does see), `onboardingComplete`, `profileCompleteness`. The commercially significant gaps are the rate card (`rateMin`/`rateMax`/`currency`) and `discoverable` — admin cannot see what a creator charges, and cannot see or fix whether they have hidden themselves from discovery.
Evidence: `influora-api/src/main/java/com/influora/web/dto/creator/CreatorProfileDtos.java:13` — `CreatorProfileSelfResponse(...)` versus `AdminCreatorDtos.java:40`, which carries only `bio`, `location` (=`city`), `niche` (=`categories`), `followers`, `engagementRate`, `phone` from that set. Backing columns exist: `CreatorProfile.java:32` (username), `:38` (avatar/cover), `:52` (languages/contentStyles), `:74` (rateMin/rateMax/currency), `:86` (`is_discoverable`).

## Block F+G defects pulled out

1. **Suspension is cosmetic for account access (F75, G90).** Both suspend flows write a profile/workspace flag that no authentication path reads. `AuthService.java:234`/`:349`/`:407` gate on `users.status`; nothing in `AdminBrandService`/`AdminCreatorService` writes it. A suspended brand keeps full access to its current workspace; a suspended creator keeps logging in and keeps existing deals.
2. **`CreatorProfile.tsx:804` gates the revoke control on the wrong flag.** `!creator.instagramVerified` is Instagram's blue check (`PlatformStat.is_verified`), not connection state, so "Force Instagram Re-auth" is permanently hidden from verified creators — exactly the accounts most likely to need it.
# BLOCK H — Money, moderation, ops (H92–H100)
Answered by priya · fresh-context · 2026-09-02

**H92. No — admin sees an aggregate summary plus the FROZEN rows only. There is no endpoint that lists the full escrow ledger row-by-row.**
Exactly two escrow reads exist across all `Admin*Controller` classes: `GET /admin/finance/escrow` → `EscrowSummaryDto` = 4 scalars only; `GET /admin/escrow/flagged` → `List<FlaggedEscrowDto>`, `EscrowStatus.FROZEN` rows only. FUNDED and RELEASED holds are never enumerated per-row anywhere in the admin surface.
Evidence: `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:50` — `@GetMapping("/escrow")` returns a single `EscrowSummaryDto`, not a list.
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminFinanceService.java:277` — the summary is 4 aggregates (`sumAmountByStatusIn(FUNDED)`, `countByStatus(FUNDED)`, `countByStatus(FROZEN)`, `avgReleaseSeconds()`).
Evidence: `influora-api/src/main/java/com/influora/web/AdminEscrowController.java:41` — the only list endpoint; `AdminFinanceService.java:300` `findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN)` hard-codes FROZEN.
Evidence: `AdminEscrowController.java:18` — class javadoc states this controller "intentionally exposes ONLY the read (`GET /flagged`)"; the `/{id}/hold|release|refund` routes `escrowApi` declares are deliberately not built.

**H93. Real, not static. The reconciliation panel is mounted and calls a live endpoint that does a per-row internal-ledger vs Razorpay/RazorpayX diff.**
Evidence: `src/admin/components/finance/ReconciliationPanel.tsx:104` — `financeApi.getReconciliation(forDate)` on form submit, into `setRows(res.data)`.
Evidence: `src/admin/components/finance/FinanceConsole.tsx:723` — `<ReconciliationPanel />` rendered in the `reconciliation` tab (trigger at `FinanceConsole.tsx:254`), reachable at `/admin/revenue` via `src/pages/admin-console.tsx:58`.
Evidence: `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:60` — `GET /admin/finance/reconciliation?date=`.
Evidence: `AdminFinanceService.java:384` — iterates `payoutRepository.findByCreatedAtBetween` + `walletTopUpRepository.findByCreatedAtBetween` for the day and reconciles each.
Evidence: `AdminFinanceService.java:518` / `:540` — live `razorpayXClient.fetchPayout(...)` / `razorpayClient.fetchOrder(...)` when no stored webhook payload exists.
Caveat: the panel is read-only. `financeApi.resolveReconciliation` is a stub — `src/admin/services/api-contracts.ts:424` returns `unavailable<ReconciliationItem>(...)`, no backend route.

**H94. Yes — manual payout is wired end-to-end and does move the creator's ledger balance. Authorization is SUPER_ADMIN + satisfied MFA, plus a mandatory `Idempotency-Key` header.**
Chain: `ManualPayoutPanel` form → `financeApi.recordManualPayout` → `POST /admin/finance/payouts/manual` → `AdminFinanceService.recordManualPayout` → locked wallet debit via `WalletLedgerService.post` + terminal `Payout` row + `admin_audit_log` entry.
Evidence: `src/admin/components/finance/ManualPayoutPanel.tsx:75` — `financeApi.recordManualPayout({creatorUserId, amount, bankReference, tdsAmount, note}, key)`; key held across retries (`:70`, `:89`).
Evidence: `src/admin/components/finance/FinanceConsole.tsx:728` — `<ManualPayoutPanel />` in the `manual-payout` tab (trigger `FinanceConsole.tsx:258`), so the control is genuinely reachable.
Evidence: `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:91` — `@PostMapping("/payouts/manual")` with `@RequestHeader("Idempotency-Key")` required (not optional).
Evidence: `AdminFinanceService.java:161` — `adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)`.
Evidence (ledger effect): `AdminFinanceService.java:198` `walletRepository.findByOwnerIdForUpdate(creatorUserId)` (row lock) → `:216` `ledgerService.post(...)` → `:228` `Payout.createManualPaid(...)` → `:242` `adminAuditLogService.record(..., "PAYOUT_RECORDED_MANUAL", ...)`.
Evidence (duplicate guard): `AdminFinanceService.java:189` — `findByBankReference` present → 409 `DUPLICATE_BANK_REFERENCE`.
The sibling `POST /admin/finance/payouts/{id}/retry` is also wired (`AdminFinanceController.java:72`, UI at `ReconciliationPanel.tsx` retry buttons) and is likewise SUPER_ADMIN-only — `AdminFinanceService.java:585`.

**H95. There is NO fee-history table. History is derived by replaying `admin_audit_log` rows for `entity_type = 'PLATFORM_FEE_CONFIG'`. It IS displayed in the UI.**
Evidence (no table): `influora-api/src/main/resources/db/migration/V41__platform_fee_config.sql:11` — `platform_fee_config` is a singleton row with `updated_at`/`updated_by` only; no history table. Grep for `fee_config_history`/`fee_history` across the migration dir returns nothing.
Evidence (derived): `influora-api/src/main/java/com/influora/service/admin/PlatformFeeAdminService.java:144` — `adminAuditLogService.getByEntity(principal, ENTITY_TYPE, PlatformFeeConfig.SINGLETON_ID)`, with `ENTITY_TYPE = "PLATFORM_FEE_CONFIG"` at `:64` and the write side at `:129`.
Evidence (reshaping): `PlatformFeeAdminService.java:241` — `toHistoryEntryDto(AuditLogEntryDto entry)` maps each audit entry's before/after JSON snapshot into a `PlatformFeeHistoryEntryDto`.
Evidence (backing table): `influora-api/src/main/resources/db/migration/V34__admin_tables.sql:48` — `admin_audit_log` with `old_value`/`new_value` JSON, `reason`, `admin_email`, `created_at`.
Evidence (endpoint): `influora-api/src/main/java/com/influora/web/PlatformFeeAdminController.java:91` — `GET /admin/finance/fee-config/history`.
Evidence (UI): `src/admin/components/finance/FeeControlPanel.tsx:631` — "Change History" section rendering `history.map(...)`, fed by `useFeeConfig()` at `FeeControlPanel.tsx:230`; the hook fetches it at `src/admin/hooks/useFeeConfig.ts:91`. Reachable at `/admin/finance` per `src/pages/admin-console.tsx:57`.
Consequence: history is only as complete as `admin_audit_log`. Any fee change not made through `PlatformFeeAdminService.update` (e.g. direct SQL) leaves no history row.

**H96. The queue reads `content_flags`. In production today the ONLY writer of new rows is `ReviewService` — user-submitted review flags. No AI classifier and no admin-initiated flag creation exists.**
Evidence (table): `influora-api/src/main/resources/db/migration/V34__admin_tables.sql:102` — `CREATE TABLE content_flags` with `flagged_by ENUM('AI','USER','ADMIN')` and `status ENUM('PENDING','REVIEWED','ACTIONED')` (ESCALATED added by `V20260715210000__content_flag_escalated_status.sql`).
Evidence (read path): `influora-api/src/main/java/com/influora/service/admin/AdminModerationService.java:140` — `contentFlagRepository.findPendingReviewQueue(pageRequest)`; JPQL at `influora-api/src/main/java/com/influora/repository/ContentFlagRepository.java:53` selects `status IN (PENDING, ESCALATED)`, ESCALATED sorted first.
Evidence (sole writer): `influora-api/src/main/java/com/influora/service/ReviewService.java:195` — `ContentFlag.userFlag(...)` then `contentFlagRepository.save(flag)`. Only `new ContentFlag` construction site in the codebase (`influora-api/src/main/java/com/influora/domain/entity/ContentFlag.java:83`, reached only via the single factory `userFlag` at `:76`).
Evidence (who triggers it): `influora-api/src/main/java/com/influora/web/BrandReviewController.java:51` and `influora-api/src/main/java/com/influora/web/CreatorReviewController.java:51` — `POST /{reviewId}/flag`.
Evidence (hard-coded source): `ContentFlag.java:89` — `flag.flaggedBy = ContentFlagSource.USER`. `AI` and `ADMIN` are declared enum values with zero producers.
Evidence (UI): `src/admin/hooks/useFlagQueue.ts:77` → `moderationApi.getContentFlags(filters.status)` → `GET /admin/moderation/flags` (`influora-api/src/main/java/com/influora/web/AdminModerationController.java:58`), rendered by `src/admin/components/moderation/FlagQueue.tsx` inside `src/admin/pages/ModerationPage.tsx`.
Practical read: the DELIVERABLE / PROFILE / MESSAGE content types in the schema can never appear — only `REVIEW` flags reach this queue.

**H97. Yes, wired end-to-end with a persisted outcome, and it settles the escrow inside the same transaction.**
Columns: `status` (`RESOLVED_BRAND` / `RESOLVED_CREATOR` / `RESOLVED_SPLIT`), `resolved_by_admin_id`, `resolution_notes`, `resolved_at`.
Evidence (schema): `influora-api/src/main/resources/db/migration/V45__disputes.sql:11` — the `status` ENUM plus `resolved_by_admin_id VARCHAR(26) NULL`, `resolution_notes TEXT NULL`, `resolved_at TIMESTAMP NULL`.
Evidence (write): `influora-api/src/main/java/com/influora/domain/entity/Dispute.java:110` — `resolve(resolution, adminId, notes)` sets all four.
Evidence (endpoint): `influora-api/src/main/java/com/influora/web/AdminDisputeController.java:73` — `POST /admin/disputes/{disputeId}/resolve`.
Evidence (money actually moves): `influora-api/src/main/java/com/influora/service/DisputeService.java:248` — `RESOLVED_CREATOR → escrowService.adminReleaseForDispute`, `RESOLVED_BRAND → adminRefundForDispute`, `RESOLVED_SPLIT → adminSplitForDispute`, each with a matching `ESCROW_RELEASE`/`ESCROW_REFUND` audit action.
Evidence (settlement invariant): `DisputeService.java:240` counts frozen holds before settling, and `:295` throws `DISPUTE_SETTLEMENT_EMPTY` (409) if fewer holds moved than were frozen — a dispute cannot be marked resolved on a movement that did not happen.
Evidence (authorization): `DisputeService.java:200` — SUPER_ADMIN + ADMIN, MFA-satisfied.
Evidence (UI): `src/admin/hooks/useDisputeResolve.ts:48` — `disputeApi.resolve(disputeId, resolution)`; control in `src/admin/components/disputes/DisputeList.tsx`'s row-click dialog, mounted by `src/admin/pages/DisputesPage.tsx:32`, routed at `src/pages/admin-console.tsx:61`.

**H98. Server only. The admin error-log console shows nothing from the client-side crash reporter — the two write paths do not converge.**
Path A (server, persisted): uncaught exception → `GlobalExceptionHandler.handleGeneric` → `ErrorLogService.record` → `error_log` table → `AdminErrorLogController` reads.
Evidence: `influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java:201` — `errorLogService.record(ErrorLogSeverity.ERROR, ex, requestURI, method, 500, currentUserId())`.
Evidence: `influora-api/src/main/java/com/influora/service/ErrorLogService.java:50` — `@Transactional(REQUIRES_NEW)`, builds a redacted+truncated `ErrorLog` and calls `errorLogRepository.save(entry)`.
Evidence (500s only): `GlobalExceptionHandler.java:194` — "Only this catch-all 500 path is captured; the 4xx/409 handlers above are expected/validation outcomes."
Path B (client, NOT persisted): React crash → `POST /api/v1/client-errors` → `ClientErrorController` → slf4j logfile only.
Evidence: `src/components/ErrorBoundary.tsx:69` and `src/lib/api.ts:5756` — `fetch(...'/client-errors'...)`.
Evidence: `influora-api/src/main/java/com/influora/web/ClientErrorController.java:80` — the class's only injected dependency is `ObjectMapper`; no repository field, no `save(...)` anywhere in the file.
Evidence: `ClientErrorController.java:134` — terminal action is `log.warn("[CLIENT_ERROR_REPORT] pathname={} ... ", ...)`, returning `202 Accepted` (`:97`).
Evidence (schema intent): `influora-api/src/main/resources/db/migration/V20260718170000__admin_error_log.sql:2` — "Backing store for handled **server** errors."
A browser crash is retrievable only by reading the VPS logs for `[CLIENT_ERROR_REPORT]`; it never appears at `/admin/errors`.
Secondary gap: `critical24h` is structurally always 0 — `influora-api/src/main/java/com/influora/service/admin/AdminErrorLogService.java:70` explains the only producer always writes `ErrorLogSeverity.ERROR`, so no `CRITICAL` row can exist.

**H99. Cached. Redis, 45-second TTL, set in `RedisCacheConfig`. The RBAC check is not cached.**
Evidence (TTL): `influora-api/src/main/java/com/influora/config/RedisCacheConfig.java:50` — `private static final Duration ADMIN_PULSE_TTL = Duration.ofSeconds(45);` applied at `:56` and registered at `:57` for cache name `adminPulse` (`:45`).
Evidence (annotation): `influora-api/src/main/java/com/influora/service/admin/AdminDashboardStatsCache.java:71` — `@Cacheable(cacheNames = RedisCacheConfig.ADMIN_PULSE_CACHE, key = "'pulse'")`; fixed literal key, so all admins share one entry.
Evidence (auth not cached): `influora-api/src/main/java/com/influora/service/admin/AdminDashboardService.java:100` — `requireRoleWithMfaSatisfied(...)` runs uncached on every call, then `return statsCache.pulseStats();`.
Evidence (endpoint): `influora-api/src/main/java/com/influora/web/AdminDashboardController.java:45` — `GET /admin/dashboard/pulse`.
Honesty notes in the same code: change-deltas are deliberately `null`, not `0.0` — `AdminDashboardStatsCache.java:99` ("A fabricated 0 would read as 'measured, and flat'"), rendering `--` in the UI; revenue is `SUM(wallet_transactions.amount) WHERE type = PLATFORM_FEE` per `AdminDashboardStatsCache.java:88`.

**H100. Six admin endpoints are live on the backend with no reachable UI control or display.**
(Verified by enumerating every `@*Mapping` in `Admin*Controller` + `ApprovalWorkflowController` + `AuditLogController`, then grepping every `src/admin/services/api-contracts.ts` function for call sites outside that file and outside tests.)

1. `POST /admin/auth/mfa/setup` — no UI. `influora-api/src/main/java/com/influora/web/AdminAuthController.java:113`; wrapper `src/admin/services/api-contracts.ts:142` has zero callers. Admins cannot enrol MFA from the console, yet every finance/moderation service calls `requireRoleWithMfaSatisfied`.
2. `POST /admin/auth/mfa/verify` — no UI. `AdminAuthController.java:119`; `api-contracts.ts:145`, zero callers.
3. `POST /admin/auth/refresh` — no UI. `AdminAuthController.java:82`; `api-contracts.ts:133`, zero callers. `src/admin/hooks/useAdminAuth.ts` calls only `getCurrentUser` (`:229`) and `logout` (`:271`) — the admin session is never refreshed.
4. `GET /admin/errors/{id}` — no UI. `influora-api/src/main/java/com/influora/web/AdminErrorLogController.java:49`; `api-contracts.ts:725`, zero callers. `src/admin/hooks/useErrorLog.ts:65` uses only `getRecent` + `getStats`, and `:104` `resolve` — no single-error detail view.
5. `PUT /admin/support/tickets/{id}` — no UI. `influora-api/src/main/java/com/influora/web/AdminSupportController.java:87`; `api-contracts.ts:570`, zero callers. `src/admin/components/support/TicketList.tsx` wires only `reply` (`:244`), `assign` (`:263`) and `escalate` (`:282`) — an admin cannot change a ticket's status or priority from the console.
6. `POST /admin/emails/send-bulk` — no UI, and deliberately inert. `influora-api/src/main/java/com/influora/web/AdminEmailController.java:75` — gated SUPER_ADMIN+MFA then returns `501 BULK_SEND_DISABLED`; `src/admin/pages/EmailQueuePage.tsx:220` confirms "Never wired to emailApi.sendBulk()". Intentional, not an oversight.

Partial gap (endpoint reachable, capability not): `GET /admin/campaigns` accepts `page`/`pageSize`/`status`/`search` filters (`influora-api/src/main/java/com/influora/web/AdminCampaignController.java:54`), but the UI only ever calls the unfiltered variant — `src/admin/hooks/useCampaignList.ts:60` `campaignApi.listAll()`; the filtered wrapper at `api-contracts.ts:322` has zero callers. Server-side campaign filtering and pagination are unusable from the console.

Not counted (declared client-side but no backend route exists — the inverse problem): `financeApi.resolveReconciliation` and `getTdsReport` (`api-contracts.ts:424`, `:429`), `escrowApi.release`/`hold`/`refund` (`:529`, `:534`, `:539`), `moderationApi.reviewAppeal` (`:640`), `dashboardApi.getMarketingSummary` (`:172`), `marketingApi.getAcquisition` (`:780`) — all `unavailable()` stubs.
