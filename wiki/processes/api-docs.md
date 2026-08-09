# API Documentation Log (Vikram — Backend)

New/changed endpoints logged here, newest first.

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
