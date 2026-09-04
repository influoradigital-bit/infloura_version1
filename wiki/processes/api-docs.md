# API Documentation Log (Vikram — Backend)

New/changed endpoints logged here, newest first.

## 2026-09-04 — PHONE-0904 sign-off items 1 (Q1) + 2 (Q8): brand phone required + self read/edit

**Task:** Swapnil ruling on `wiki/reports/phone-0904-signoff-qa.md` Q1/Q8 (both blocking items).
No migration — `users.phone_number` stays nullable (legacy brands with `NULL` are unaffected).

**Item 2 (Q8) — `POST /auth/brand/register` (`AuthController` → `AuthService#brandRegister`):**
brand `phone` is now REQUIRED, enforced as its own service-level check (deliberately NOT
`@NotBlank` on `BrandRegisterRequest` — that would collapse the failure into Bean Validation's
generic field-errors 400 instead of a code the client can branch on). Three distinct,
machine-readable error codes on this endpoint now:

| Code | Status | Meaning |
|---|---|---|
| `PHONE_REQUIRED` | 400 | phone missing/blank |
| `INVALID_PHONE` | 400 | phone present but fails `IndianPhoneUtils` (unchanged, pre-existing) |
| `PHONE_ALREADY_EXISTS` | 409 | phone (post-normalize) collides with another account — message text names "phone number" explicitly, never confusable with the sibling `EMAIL_ALREADY_EXISTS` 409 the same endpoint also throws |

Creator registration (`CreatorRegisterRequest`) has no `phone` field at all and is untouched —
creator phone capture stays optional everywhere, per Priya's standing ruling.

**Item 1 (Q1) — `GET`/`PATCH /users/me` (`UserController` → `UserService`), the generic
authenticated-self endpoint (not creator-specific `/me/creator-profile`):**
- `UserDtos.UserProfileDto` gained `phone` — populated from `User.phoneNumber`. Self-only: guarded
  by `@AuthenticationPrincipal`, so this can only ever be the caller's own row. Re-grepped every
  `getPhoneNumber()`/`phoneNumber` reference in `influora-api/src/main/java` after this change —
  still exactly the same set sign-off Q10 already cleared (admin, creator-self, this new
  user-self, the `existsByPhoneNumber` boolean check, and `BrandContextAssembler`'s
  never-included comment) plus this one new self-read site. No public/brand-facing-creator/
  discovery/AI/Meera serializer touches it.
- `UserDtos.UpdateProfileRequest` gained `phone` (null = leave unchanged, same convention as its
  other fields). Honored for `BRAND` callers only — routes through the EXISTING
  `UserPhoneService#applyPhone` (no new phone-writing logic; normalize/`INVALID_PHONE`/
  `PHONE_ALREADY_EXISTS`/`saveAndFlush`+TOCTOU all reused as-is). A `CREATOR`/`ADMIN` caller
  sending `phone` here has it silently ignored — creators keep their own write path
  (`CreatorProfileService#patchMyProfile`, different intentional blank-means-clear semantics for
  an optional field) rather than gaining a second, conflicting one.
- Null/blank decision for the BRAND branch: a blank string is rejected with `PHONE_REQUIRED`
  (same code as registration), NOT treated as "clear the field" — unlike creator Settings' own
  blank-clears-it path. Reasoning: brand phone is now mandatory (Item 2), so there is no valid
  cleared state for a brand to fall back into on this endpoint.

**Files:**
- `influora-api/src/main/java/com/influora/service/AuthService.java` — `brandRegister` `PHONE_REQUIRED` guard.
- `influora-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest.java` — javadoc only (required-vs-optional contract, no annotation change).
- `influora-api/src/main/java/com/influora/web/dto/user/UserDtos.java` — `UserProfileDto.phone`, `UpdateProfileRequest.phone`.
- `influora-api/src/main/java/com/influora/service/UserService.java` — reads/writes `phone` via `UserPhoneService`, injected dependency.
- New test: `UserServiceTest` (10 cases: self read incl. legacy NULL, valid/blank/malformed/duplicate/raced-duplicate/re-save-own-number on the BRAND write path, CREATOR phone-ignored regression guard).
- Extended `AuthServiceTest`: `REQUEST` fixture now carries a phone (brand phone is no longer
  optional); replaced `testBrandRegisterSucceedsWithoutPhone` with
  `testBrandRegisterRejectsMissingPhone` + `testBrandRegisterRejectsBlankPhone`.

**Test run:** `mvn -o -q compile` / `mvn -o -q test-compile` clean. `mvn -o surefire:test
-Dtest='AuthServiceTest,UserServiceTest,CreatorProfileServiceTest,CreatorOnboardingServiceTest,AdminCreatorServiceTest'`
→ 81 run, 0 failures, 0 errors (39+10+13+15+4), reproduced on a clean re-run.

**Not done (explicitly out of scope for this pass, per the ticket):**
- Frontend wiring (`brand-onboarding.tsx` inline `PHONE_REQUIRED`/`PHONE_ALREADY_EXISTS` mapping,
  a brand Settings phone read/edit UI against the new `/users/me` fields) — Ananya, separate ticket
  per the sign-off report's own owner split.
- Q3's `IndianPhoneUtilsTest.java` gap and Q6's enumeration-oracle ruling — separate, non-blocking
  items from the same sign-off report, not part of Items 1/2.

## 2026-09-03 — T-MEERA-CREATOR-PHASE-A backend (A1/A2/A3/A4/A6/A7/A8/A9)

**Task:** SPEC at `.proof-os/tasks/T-MEERA-CREATOR-PHASE-A/SPEC.md`. Full backend scope, migrations
V72-V74 logged in `wiki/processes/schema-changes.md`. All paths below omit `/api/v1` (the
context-path already supplies it, same as every sibling controller).

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/admin/creator-agent/baselines` | Admin (`/admin/**` `hasRole(ADMIN)`) | Raw DTO, no envelope (admin console convention). Creators-by-tier (incl. MEGA), briefs/creator/month percentiles, Meta connect rate, median reply hours; `sample_label_compliance` is a hand-sample placeholder per SPEC.md §2.1, not automated. |
| GET | `/creator/agent-preferences` | Creator | Computes+persists defaults on first call (floor = last COMPLETED deal's rate, else `RateEstimationService.estimate().min()`, else ₹500/300/600 fallback). |
| PUT | `/creator/agent-preferences` | Creator | Full replace; `represented=true` requires non-blank `agency_name`. |
| POST | `/creator/agent-preferences/consent` | Creator | Sets `consent_accepted_at` (idempotent). |
| GET | `/creator/agent-preferences/conversations` | Creator | Ownership via `meera_creator_conversations`, not `AiConversation.workspaceId`. |
| GET | `/creator/agent-preferences/conversations/{id}/export` | Creator | JSON dump of the conversation's messages. |
| DELETE | `/creator/agent-preferences/conversations/{id}` | Creator | Deletes `ai_messages`, `ai_conversations`, and the tracking row. |
| GET | `/public/creators/{username}/verified` | None (`permitAll`) | 404 unless `discoverable=true` AND an active Meta token exists. NO rates/floors/PAN/GSTIN. |
| POST | `/internal/meera/context` | Dual-credential mesh (unchanged) | Now branches on `audience`: `CREATOR` → `MeeraContextService.assembleCreatorContext`, `BRAND` → unchanged path. Return type is now `Object` (Jackson serializes whichever concrete record). |

**Also touched (existing endpoints, additive fields only):**
- `POST /campaigns`, `PATCH /campaigns/{id}` — `endBrandName`/`endBrandCategory` (required on create).
- `POST /deals`, `POST /deals/{id}/counter` — `dealTerms` (nested `DealTermsDto`: usageMonths, usagePerpetual, usageChannels, exclusivityDays, exclusivityScope, exclusivityBrands, maxRevisions). `GET /deals`/`/deals/{id}` responses now include `dealTerms` (null when never set).

**Info barrier (A7):** `CreatorAgentPreferencesRepository` (the floors) may only be read from
`CreatorAgentPreferencesService`/`MeeraContextService.assembleCreatorContext` — enforced by
`InfoBarrierTest` (source scan: no Brand-named class under `service/meera`/`web` may import it) and
`InfoBarrierRuntimeTest` (runtime: BRAND context assembly never even touches the repository;
two creators' floors never cross-contaminate). `CreatorContextResponse.identity` carries ONLY
`kyc_done`/`gstin_present` — no PAN, GSTIN value, or Aadhaar digit ever leaves `CreatorProfile`
through this path.

**Cross-stream verification:** every new DTO's wire field names were diffed against BOTH concurrent
sessions' actual consumers, not just SPEC.md's JSON examples: influora-ai's
`app/prompt/assembler.py::CREATOR_CONTEXT_PAYLOAD_FIELDS` (exact match, 16/16 fields incl.
`consent_accepted`, which the spec's own record listing omits but `chat.py`'s consent gate reads —
already flagged as a gap by dev's own TASKS.md notes and closed here) and `src/lib/api.ts`/
`src/lib/types.ts` (`CreatorAgentPreferences`, `DealTerms`, `PublicCreatorVerifiedResponse` — exact
field-name matches).

**Known gaps** (full detail in TASKS.md's "Known gaps / deviations" section): metrics come from
`creator_metrics`/`CreatorProfile` (this codebase has no `instagram_insights` table, unlike the
spec's assumption); `meera_creator_conversations` has no write-side hook yet (belongs on the
CREATOR-audience chat-turn persistence path, outside this task's file ownership); on-behalf token
minting for CREATOR turns (workspace_id claim = creator's user id) was not touched/verified; A9's
`reach_30d`/`engagement_rate` are omitted (not fabricated as 0) when no metric row exists yet, which
disagrees with the frontend's non-nullable TS types.

**Test run:** `mvn -o compile`/`test-compile` clean. Targeted suite (`InfoBarrierTest`,
`InfoBarrierRuntimeTest`, `MeeraContextServiceTest`, `DealServiceTest`, `DealControllerTest`,
`CampaignServiceTest`, `MeeraInternalController*Test`) — 118/118 green, re-confirmed after a
concurrent session's own `mvn` process transiently locked/deleted files under `target/` mid-run
(see TASKS.md for the exact symptom). Full-suite `mvn clean install` not personally re-run in this
pass — re-run once no other session is building against the same `target/` directory.

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

---

## 2026-09-03 — Gate fix round 1, T-MEERA-CREATOR-PHASE-A (backend area)

**Task:** Priya's tester-question gate-fix pass on the Meera-for-Creators Phase A build. Seven
findings touched my area (Java/Spring). Fixed each in code, with tests where feasible.

**Q1 (day-one onboarding turn).** `MeeraSessionService#startOrResumeForCreator` — new
CREATOR-specific overload of `startOrResume`. On a genuinely NEW conversation, persists SPEC.md
4.7's greeting ("Hi {first_name}! I'm Meera...") as a real ASSISTANT `ai_messages` row (never on a
resumed conversation) and bumps the `meera_creator_conversations` rollup, so the greeting a
creator reads is now the one the DPDP conversation export actually contains — previously it was a
client-only string in `MeeraCopilotChat.tsx` that never touched the backend.
`CreatorMeeraController#startSession` now calls this instead of the generic `startOrResume`.

**Q2 (test coverage gaps + AI-created campaigns bypassing end-brand validation).**
- New `CreatorAgentPreferencesServiceTest` (17 cases) and `CreatorAgentControllerTest` (7 cases) —
  previously zero coverage on either class.
- `CreateCampaignExecutor` (Meera's `create_campaign` tool) now resolves and applies
  `endBrandName`/`endBrandCategory` — the same fields `CampaignService#create` hard-requires for
  every NEW campaign on the human write path — defaulting from the workspace's own `name`/
  `industry` when the AI doesn't supply them (the current tool schema has no such input yet), so an
  AI-drafted campaign is never born with `NULL` end-brand fields indistinguishable from a
  pre-V72 legacy row. New constructor param `WorkspaceRepository`.

**Q3/Q5 (creator-route authorization not structurally enforced).** Added an explicit
`.requestMatchers("/creator/**").hasRole("CREATOR")` matcher in `SecurityConfig` (mirrors the
existing `hasRole("ADMIN")` pattern for `/admin/**`) — previously a BRAND/ADMIN JWT on any
`/creator/**` route was rejected only by each service's own `findByUserId`-returns-empty 404, an
accident of the current method signatures, not an enforced invariant. 7 new cases in
`SecurityConfigMatcherTest` pin the matrix (BRAND/ADMIN denied, CREATOR permitted, the unrelated
plural `/creators/**` and `/public/creators/**` routes unaffected).

**Q6/Q9 (unconnected creator gets a fabricated "0 followers").**
`MeeraContextService#buildMetricsSummary` no longer emits a `followers` key from
`CreatorProfile.totalFollowers` (self-reported at onboarding, 0 for a brand-new creator) as if it
were Meta-verified. A verified `CreatorMetric` row formats normally; a nonzero self-reported total
is now labelled "(self-reported, not verified)"; absent both, the key is omitted so
influora-ai's honest "Instagram not connected yet" branch fires instead. 2 new
`MeeraContextServiceTest` cases.

**Q7 (spend-cap override has no write side).** New nullable
`creator_agent_preferences.ai_monthly_cap_usd DECIMAL(6,2)` column
(`V20260903150000__creator_agent_preferences_ai_monthly_cap.sql`) + admin-only
`PUT /admin/creator-agent/creators/{creatorId}/monthly-cap` (`CreatorAgentPreferencesService#adminSetMonthlyCapOverride`)
— closes the write side of the `ai_monthly_cap_usd` key influora-ai's
`spend_tracker.creator_cap_override_from_context` already read but nothing ever populated.
Surfaced on the CREATOR context payload (`MeeraContextService`) as a 2-decimal string, omitted
when unset. Not reachable from the creator's own `PUT /creator/agent-preferences` — deliberately
admin-only. 6 new service tests, 2 new controller tests, 1 new context-service test.

**Q10 (public verified page has no cache-control header).**
`PublicCreatorController#getVerifiedMetrics` now sets `Cache-Control: no-store, private` — an
opt-out/suspension must not be servable from any intermediary. New `PublicCreatorControllerTest`
also pins the exact 7-key + 4-metric-key JSON allow-list via serialization, so a future field
added to `VerifiedProfileResponse`/`VerifiedMetrics` fails the test loudly instead of silently
leaking onto a public, unauthenticated page.

**Files:**
- `influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java` — `startOrResumeForCreator`.
- `influora-api/src/main/java/com/influora/web/CreatorMeeraController.java` — calls the new method.
- `influora-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor.java` — end-brand defaulting, `WorkspaceRepository` dependency.
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — `/creator/**` `hasRole("CREATOR")` matcher.
- `influora-api/src/main/java/com/influora/service/meera/MeeraContextService.java` — `buildMetricsSummary` honesty fix, `ai_monthly_cap_usd` surfacing.
- `influora-api/src/main/java/com/influora/domain/entity/CreatorAgentPreferences.java` — `aiMonthlyCapUsd` field.
- `influora-api/src/main/java/com/influora/service/CreatorAgentPreferencesService.java` — `adminSetMonthlyCapOverride`.
- `influora-api/src/main/java/com/influora/web/AdminCreatorAgentController.java`, `influora-api/src/main/java/com/influora/web/dto/admin/AdminCreatorAgentDtos.java` — new PUT endpoint + DTOs.
- `influora-api/src/main/java/com/influora/web/PublicCreatorController.java` — `Cache-Control` header.
- `influora-api/src/main/resources/db/migration/V20260903150000__creator_agent_preferences_ai_monthly_cap.sql` — new migration.
- New tests: `CreatorAgentPreferencesServiceTest`, `CreatorAgentControllerTest`, `PublicCreatorControllerTest`, `AdminCreatorAgentControllerTest`; extended `MeeraContextServiceTest`, `MeeraSessionServiceTest`, `SecurityConfigMatcherTest`, `CreateCampaignExecutorTest`, `CreatorMeeraControllerTest` (constructor/call-site update).

**Not done (out of my area or lower-priority, flagged not silently skipped):**
- Q2/Q9 frontend: `dealTerms` never rendered in the deal room; withdraw-consent UI action — Ananya.
- Q2 creator voice route on `CreatorMeeraController` — larger surface (Sarvam wiring), not attempted this pass.
- Q9 `MEERA_CREATOR_ENABLED` flag (SPEC's named rollback mechanism) — not implemented; Phase A still has no rollback beyond "leave the additive columns."
- Q9 JUnit coverage for a pre-V72 Collaboration/Campaign through list/detail/edit/mapper — not added this pass.

**Addendum (same pass):** also added `CampaignServiceTest` coverage for `create()`'s
END_BRAND_NAME_REQUIRED/END_BRAND_CATEGORY_REQUIRED 400s (4 new cases) and `DealServiceTest`
coverage for `createProposal()` actually persisting a `dealTerms` block onto the Collaboration (2
new cases — the happy path through `createProposal` was previously deliberately left unasserted,
per that file's own comment: "Cover it when the suite can actually be executed").

**Test run:** `mvn -o test` (full suite) → 1343 run, 0 failures, 0 errors, 3 skipped (pre-existing, unrelated). `mvn -o compile`/`test-compile` clean.
