# API Documentation Log (Vikram — Backend)

New/changed endpoints logged here, newest first.

## 2026-08-10 — `POST /me/portfolio/sync` behavior change (CR-84), `GET /me/portfolio/analytics` labeling (CR-71)

**Task:** CR-84 (Medium) — `PortfolioService.syncPlatforms()` was a documented no-op (validated the
profile existed, returned a fabricated `syncedAt` timestamp, touched no data). It now does a real
on-demand refresh reusing the existing creator-owned Meta OAuth pipeline (`MetaOAuthTokenRepository`
creator key-space, `MetaTokenStorage#getValidCreatorToken`, `InstagramInsightsClient#getProfile`):
fetches a live Instagram profile snapshot, writes a `creator_metrics` row, and upserts the
corresponding `platform_stats` row + `creator_profiles` denormalized totals — the same upsert shape
`PlatformStatsAggregationJob` performs on its nightly schedule, just synchronous here. No schema
change (no migration) — only new writers, via existing repositories, to existing tables.

CR-71 (Medium) — confirmed already fixed on this branch prior to this pass:
`PortfolioAnalyticsResponse.profileClicksEstimated` (always `true`, since `profileClicks` is a
`totalFollowers / 100` proxy with no real click-tracking event behind it) was already wired end to
end — server sets the flag, `creator-portfolio-editor.tsx`'s `Stat` component already renders an
"Estimated from follower count" note from it. No further backend change needed; verified only.

**Endpoint contract is unchanged** — same `SyncPlatformsResponse { syncedAt }` shape — but the
endpoint can now genuinely fail where it previously always returned 200:

| Code | Status | When |
|---|---|---|
| `NOT_CONNECTED` | 409 | No creator-owned Meta OAuth token row, or one with no `igBusinessAccountId` on file |
| `TOKEN_EXPIRED` | 409 | Token row exists but is expired/revoked |
| `META_RATE_LIMITED` | 429 | Pre-flight or live Meta rate-limit trip (`MetaRateLimitException`, existing global handler) |
| `META_TOKEN_EXPIRED` | 401 | Meta itself rejected the token (`MetaTokenExpiredException`, existing global handler) |
| `META_API_ERROR` | 502 | Any other Graph API failure (`MetaApiException`, existing global handler) |

**Files:**
- `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java` — `syncPlatforms()` rewritten; new `upsertPlatformStat` helper; 4 new constructor deps (`MetaOAuthTokenRepository`, `MetaTokenStorage`, `InstagramInsightsClient`, `CreatorMetricsRepository`).
- `influora-api/src/test/java/com/influora/service/portfolio/PortfolioServiceTest.java` — 3 new tests (`NOT_CONNECTED`, `TOKEN_EXPIRED`, real-fetch success path); constructor call site updated for the new deps.
- `src/pages/creator-portfolio-editor.tsx` — `handleSync` now maps the new error codes to honest messages instead of a one-size-fits-all "Sync limit reached", and re-fetches `page`/`analytics` after a real success.
- `src/pages/creator-profile.tsx` — stale CR-84 comment updated (no longer describes the endpoint as a no-op).
- No controller change needed — `PortfolioController#syncPlatforms` already just delegates, and `GlobalExceptionHandler` already maps `ApiException`/`MetaApiException` subclasses to their HTTP statuses.

**Test run:** `mvn -o -Dtest=PortfolioServiceTest,PlatformStatsAggregationJobTest test` → 19 run, 0 failures, 0 errors. `mvn -o compile` clean. `npx tsc --noEmit` clean (exit 0).

## 2026-08-10 — `GET /meta/oauth/status`, `POST /meta/oauth/disconnect` (CR-106)

**Task:** CR-106 (Medium) — `MetaConnectionService.getStatus()`/`disconnect()` existed but had no
HTTP route (dormant), and `disconnect()` called the workspace-scoped `MetaTokenStorage#revoke`
instead of the creator-scoped `revokeCreatorToken` — a silent no-op for every real creator row
(creator Meta connections always have `workspace_id IS NULL`, per the Creator AI Co-pilot Tier-1
OAuth flip; `MetaOAuthController#callback`'s own javadoc: "a CREATOR-type principal has no
workspaceId").

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/meta/oauth/status` | Creator (`requireCreator`, profile resolved from principal) | — | `ApiResponse<MetaConnectionStatusResponse>` — `{connected, handle, followers, connectedAt, grantedScopes}` |
| POST | `/meta/oauth/disconnect` | Creator (`requireCreator`, profile resolved from principal) | — (no body; principal-scoped) | `ApiResponse<MetaDisconnectResponse>` — `{disconnected: true}` |

**Fix:** `MetaConnectionService.getStatus`/`disconnect` were rewritten to drop the `workspaceId`
parameter entirely and operate purely on the creator-owned key-space (`workspace_id IS NULL`) —
`MetaOAuthTokenRepository#findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse`,
`MetaTokenStorage#getValidCreatorToken`, `MetaTokenStorage#revokeCreatorToken` — matching exactly
what `CreatorMetaOAuthService#connect` writes. This was broader than the ticket's one-line "wrong
revoke query" framing, but leaving `getStatus()`'s read on the workspace-scoped query while wiring
a real route would have shipped a status endpoint that always reports "disconnected" for every
actual creator (the two key-spaces are disjoint by construction per `MetaOAuthTokenRepository`'s
own doc comment).

**Files:**
- `influora-api/src/main/java/com/influora/service/MetaConnectionService.java` — `getStatus(CreatorProfile)` and `disconnect(String creatorProfileId)` signatures dropped `workspaceId`; both now creator-scoped.
- `influora-api/src/main/java/com/influora/web/MetaOAuthController.java` — new `status()`/`disconnect()` routes, both resolving `CreatorProfile` from `@AuthenticationPrincipal` via `creatorProfileRepository.findByUserId` (never a client-supplied id) — added `requireCreatorProfile` helper shared by both.
- Tests: `influora-api/src/test/java/com/influora/service/MetaConnectionServiceTest.java` (rewritten for the new signatures; explicit `verify(tokenStorage, never()).revoke(...)` guard), `influora-api/src/test/java/com/influora/web/MetaOAuthControllerTest.java` (5 new cases: status happy-path, status non-creator 403, disconnect happy-path, disconnect non-creator 403, status 404 no-profile).

**Verified:** `mvn -o clean test -Dtest=MetaConnectionServiceTest,MetaOAuthControllerTest` → 15/15 passed. `mvn -o compile test-compile` (whole module) → no other caller of the old signatures existed.

## 2026-08-09 — `POST /onboarding/brand/kyc-prompt-dismissed` (OB-1), `GET /onboarding/brand/status` extended

**Task:** OB-1 (`BrandF.md` §105/§91) — the KYC prompt (`brand-kyc-prompt.tsx`) tracks "skip for
now" in `localStorage` only, so a brand that dismisses it on one device is re-prompted on every
other device/browser/private window. This needed a server-side home for the dismissal.

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/onboarding/brand/status` | Brand (`brandContext.requireBrand`) | — | `ApiResponse<OnboardingStatusResponse>` — `{onboardingCompleted: boolean, kycPromptDismissed: boolean}` — **field added this pass** |
| POST | `/onboarding/brand/kyc-prompt-dismissed` | Brand (`brandContext.requireBrand`) | — (no body; principal-scoped) | `ApiResponse<KycPromptDismissedResponse>` — `{kycPromptDismissed: true}`. Idempotent — calling it twice is a no-op write, still 200. |

**Design decision — where dismissal lives:** on `users.kyc_prompt_dismissed` (new column), not on
`workspaces`. This is deliberately a *different* signal from `workspaces.verification_status`
(already readable via `GET /workspaces/me`, per BrandF.md §91's "that endpoint already exists"
finding): `verificationStatus` means "KYC was actually submitted/approved"; `kycPromptDismissed`
means "this person clicked skip and doesn't want to see the nag again," which can be true for a
brand that never submits KYC at all. **Frontend should hide the prompt when EITHER is true** —
`kycPromptDismissed === true` OR `verificationStatus !== 'UNVERIFIED'` — not just one.

Scoped per-user (matches `onboarding_completed`'s existing precedent on the same `users` table),
not per-workspace: this is a personal "don't nag me" UX preference, not a workspace verification
fact. A teammate on a different account in the same workspace will still see the prompt until they
dismiss it themselves — intentional, same as any other per-user notification-dismissal pattern.

**Files:**
- `influora-api/src/main/resources/db/migration/V20260809120000__brand_kyc_prompt_dismissed.sql` — new `users.kyc_prompt_dismissed BOOLEAN NOT NULL DEFAULT FALSE`.
- `influora-api/src/main/java/com/influora/domain/entity/User.java` — `kycPromptDismissed` field + `isKycPromptDismissed()`/`dismissKycPrompt()`.
- `influora-api/src/main/java/com/influora/web/dto/onboarding/OnboardingDtos.java` — `OnboardingStatusResponse` gained `kycPromptDismissed`; new `KycPromptDismissedResponse`.
- `influora-api/src/main/java/com/influora/service/OnboardingService.java` — `dismissBrandKycPrompt`; `getBrandOnboardingStatus` now returns the new field; shared `requireBrandUser` helper.
- `influora-api/src/main/java/com/influora/web/OnboardingController.java` — new `POST /onboarding/brand/kyc-prompt-dismissed`.
- Tests: `influora-api/src/test/java/com/influora/service/OnboardingServiceKycPromptTest.java` (new — 3 cases: default-undismissed, dismiss-persists-and-reflects-in-status, dismiss-is-idempotent).

**Not done (frontend):** `src/components/brand/campaigns/brand-kyc-prompt.tsx` is untouched —
Ananya's follow-up. It should call `GET /onboarding/brand/status` on mount (or reuse a call
already made for OB-2's dashboard guard) to read `kycPromptDismissed`, keep `localStorage` only as
a same-session/optimistic cache, and call `POST /onboarding/brand/kyc-prompt-dismissed` from the
existing `rememberDismiss()` callback (both the "Skip for now" paths and the post-submit path
already call `rememberDismiss()` — add the POST call there, fire-and-forget is fine since the
prompt already hides optimistically via local state).

---

## 2026-07-18 — `GET /workspaces/me`, `PATCH /workspaces/me`

**Task:** I7 — brand Settings > General > Workspace Information had no persistence endpoint
(`src/pages/brand-settings.tsx:38-46` flagged this; frontend Save button was disabled).

**Finding:** `WorkspaceService.getMyWorkspace`/`updateMyWorkspace` and the
`WorkspaceReadResponse`/`WorkspaceUpdateRequest` DTOs already existed (an earlier pass, L-9 in
`INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md`), but `WorkspaceController` never exposed
them — only `GET /workspaces/slug-check` was mounted. This pass wires the two missing routes and
extends the existing shape by one field (`email`).

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/workspaces/me` | Brand, any active member (`BrandContextService.requireBrandWorkspace`) | — | `ApiResponse<WorkspaceReadResponse>` — `{id, name, slug, email, industry, companySize, websiteUrl, logoUrl, verificationStatus}` |
| PATCH | `/workspaces/me` | Brand, OWNER/ADMIN only (`requireMember` + `requireRole`) | `WorkspaceUpdateRequest` — `{name*, email?, industry?, companySize?, websiteUrl?, description?, logoUrl?}` (`*`=required, full-replace: omitted/null clears) | `ApiResponse<WorkspaceReadResponse>` (same shape as GET) |

**Field mapping / persistence status** (brand-settings.tsx's 4 General fields):

| Frontend field | Backend column | Persists? |
|---|---|---|
| `workspaceName` | `workspaces.name` | ✅ Yes |
| `website` | `workspaces.website_url` | ✅ Yes (loose `@Pattern` sanity check, no protocol required) |
| `email` | `workspaces.billing_email` | ✅ Yes — reused, NOT a new column. Same field `AdminBrandDtos.UpdateBrandRequest.email` already maps to server-side (`AdminBrandService.update` → `Workspace.applyAdminProfileEdit`). Semantically this is the workspace's billing/contact email, not a personal user email. |
| `phone` | — | ❌ No column anywhere (`workspaces` or `users`). Not persisted, not fabricated. Needs a migration decision from Priya before it can wire — flagged, not built. |

**Validation:** `name` non-blank (DTO `@NotBlank` + service-level check, both return `VALIDATION_ERROR`/400 — service-level check exists specifically because this codebase's controller tests never exercise Spring bean validation, see `AuthControllerTest`'s "no MockMvc harness" note); `email` format (`@Email` DTO annotation + service-level regex, same reasoning); `websiteUrl` loose `@Pattern` (optional protocol + domain.tld shape, empty string allowed to clear).

**Files:**
- `influora-api/src/main/java/com/influora/web/WorkspaceController.java` — added `getMyWorkspace`/`updateMyWorkspace` endpoints.
- `influora-api/src/main/java/com/influora/service/WorkspaceService.java` — `updateMyWorkspace` gained an `email` param + blank-name/bad-email validation.
- `influora-api/src/main/java/com/influora/domain/entity/Workspace.java` — added `updateContactEmail(String)`.
- `influora-api/src/main/java/com/influora/web/dto/workspace/WorkspaceMemberDtos.java` — `WorkspaceReadResponse`/`WorkspaceUpdateRequest` gained `email`; `websiteUrl` gained a sanity `@Pattern`.
- Tests: `WorkspaceControllerTest.java` (new), `WorkspaceServiceTest.java` (+5 tests: happy path w/ email, blank name, bad email, non-OWNER/ADMIN role, not-a-member), `WorkspaceServiceAnalyzeSiteTest.java` (updated call sites for the new signature — no behavior change).
- Docs: `docs/api.md`, `docs/docs/api.md`, `docs/features/workspaces-members.md`, `docs/docs/features/workspaces-members.md`.

**Not done (frontend):** `src/pages/brand-settings.tsx` and `src/lib/api.ts` are untouched — that's Ananya's wiring task once this clears QA. The frontend's `phone` field should stay disabled/local-only; `email`/`workspaceName`/`website` can wire to `PATCH /workspaces/me`.

**Test run:** `mvn -o test` (full suite) → 1343 run, 0 failures, 0 errors, 3 skipped (pre-existing, unrelated). `mvn -o compile`/`test-compile` clean.
