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
