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
