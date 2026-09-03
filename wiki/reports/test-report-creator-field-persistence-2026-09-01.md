# Test Report — Creator field persistence & the "verified vs pending" mismatch

**Date:** 2026-09-01 · **Branch:** `fix/f0390-money-flags-build-pipeline` (working tree, PHONE-0829 in progress)
**Stages run:** Build (Meera) · Functional/QA (Kavya) · deep backend trace (Priya)
**Target:** creator onboarding → `/onboarding/creator/*`, `/me/creator-profile`, `/onboarding/creator/kyc`, admin `/admin/creators/*`
**Verdict: FAIL — 3 High, 2 Medium, 1 Low** (F1 was Critical at first pass; fixed mid-audit, see below)

> **Amended 18:58 IST.** F1 (admin creator phone) was Critical when first traced at ~18:30. A
> concurrent session landed the backend half — "PHONE-0829 Gap B" — at 18:47/18:48, while this
> report was being written. Re-verified and **F1 is resolved**; a separate brand-side gap (F7) was
> found in its place. Everything else in this report still stands.

---

## Build stage (Meera) — PASS

| Check | Result |
|---|---|
| `npx tsc --noEmit -p tsconfig.json` | **exit 0** — clean |
| `mvn -o -q compile` (influora-api) | **exit 0** — clean, includes the Gap B DTO change |

Note: the typecheck passing is what let F1 exist at all — the admin phone field was typed as present
but never sent on the wire, so `tsc` could not see the break. That hazard is generic, not specific to
this ticket: **a green `tsc` proves nothing about a contract that spans TypeScript and Java.**

---

## Part 1 — Does every creator onboarding field reach the database?

Traced field → payload → DTO → entity → column for every creator capture surface.

### `/creator/onboarding` Step 2 "Build profile" — ALL 7 FIELDS PERSIST

`creator-onboarding.tsx:184` → `api.onboarding.saveCreatorProfile` → `POST /onboarding/creator/profile`
→ `CreatorOnboardingService.saveProfile` → `CreatorProfile.applySelfEdit` → `creator_profiles`

| UI field | Payload key | Column | Status |
|---|---|---|---|
| Display name | `displayName` | `display_name` | saves |
| Bio | `bio` | `bio` | saves |
| Content verticals (max 3) | `verticals` | `categories_json` | saves |
| Languages | `languages` | `languages_json` | saves |
| City | `city` | `city` | saves |
| Rate min | `rateMin` | `rate_min` | saves (rateMin>rateMax → 400) |
| Rate max | `rateMax` | `rate_max` | saves |

### `/creator/onboarding` Step 1 "Connect socials"

- **Instagram** — real Meta OAuth (`MetaOAuthController` / `MetaConnectionService`). Works.
- **YouTube / TikTok / Twitter** — `POST /onboarding/creator/socials` returns **501
  `SOCIAL_OAUTH_NOT_IMPLEMENTED`** on purpose (`CreatorOnboardingService.connectSocial`); the UI shows
  an honest "coming soon" toast. Nothing written, nothing fabricated. Correct-by-design.

### Step 3 "Complete"

`POST /onboarding/creator/complete` → `users.onboarding_completed = true`. Works.

### Settings → Mobile Number (PHONE-0829 P1, uncommitted)

`creator-settings.tsx` dialog → `PATCH /me/creator-profile {phone}` →
`CreatorProfileService.applyPhone` → normalize (`IndianPhoneUtils`) → validate → `users.phone_number`.
Round-trips on `GET /me/creator-profile`. Duplicate → 409 shown inline. **Chain is complete and sound.**

### Settings → Identity KYC

`POST /onboarding/creator/kyc` → `pan`, `aadhaar_last4`, `selfie_url`, `identity_kyc_status = PENDING`.
Writes correctly — but is **never readable again**, see **F2**.

### Settings → Tax Identity (GSTIN/PAN)

Persists via `CreatorProfile.applyTaxIdentity`. Works.

### Dead / partial paths

- `POST /onboarding/creator/payout` — backend live, frontend wrapper deleted 2026-08-04 (0 callers). Dead endpoint.
- Inside it, `accountName` is accepted and **not persisted** — no column on `CreatorBankAccount` (already flagged in code).

---

## Part 2 — Why the screen says verified/approved when the DB says pending

**There are two different columns, and the UI shows the wrong one.**

| Column | Signup value | Who can change it | Shown in admin? |
|---|---|---|---|
| `creator_profiles.application_status` | **`APPROVED`** | admin review endpoint | **YES** — the green "Application Approved" pill |
| `creator_profiles.identity_kyc_status` | `UNVERIFIED` → `PENDING` on KYC submit | **nobody — no code path exists** | **NO** — not on any DTO |

1. `V38__creator_profile_moderation.sql` declares `application_status ... NOT NULL DEFAULT 'APPROVED'`,
   and `CreatorProfile.newForUser:215` sets `APPROVED` in code too. Every creator is "approved" the
   instant they register. `application_reviewed_by` / `application_reviewed_at` stay `NULL` — nobody reviewed anything.
2. `CreatorProfile.tsx:136-146` maps that column to a green `BadgeCheck` **"Application Approved"** pill
   (rendered at `:375` / `:414`).
3. The column that actually means "identity verified" — `identity_kyc_status` — is **PENDING**, and is
   **not a field on `CreatorDetailDto` or `CreatorSummaryDto` at all**, so the admin panel never displays it.

So: the pill you are reading is a column that is auto-approved for everyone and was never reviewed;
the `PENDING` you are seeing in the database is a different column the UI does not render.

**Two consequences that follow directly:**

- The **"Pending Applications" tab is permanently empty.** `AdminCreatorService.listPendingApplications:478`
  filters `application_status = 'PENDING'`, and no code path ever writes `PENDING`.
- **`identity_kyc_status` can never become `VERIFIED`.** Repo-wide, the only writes are `UNVERIFIED`
  (`CreatorProfile.java:217`) and `PENDING` (`:408`). `WalletService.java:334-352` documents the same finding.

---

## Findings

### F1 — ~~CRITICAL~~ **RESOLVED 18:48 IST** · admin creator Phone column

**Where:** `AdminCreatorDtos.java` (`CreatorSummaryDto`, `CreatorDetailDto`); `AdminCreatorService.java`;
consumed at `src/admin/pages/UsersPage.tsx:490` and `src/admin/components/users/CreatorProfile.tsx:396`.

**As first traced (~18:30):** PHONE-0829 **P3** had shipped the frontend half only — the Phone column,
the detail row, and `phone: string | null` on `Creator`/`CreatorSummary` — while neither backend DTO
record had a `phone` component and `AdminCreatorService` never called `User.getPhoneNumber()`. At
runtime `creator.phone` was `undefined`, so the cell rendered "— Not provided" for every creator
permanently. The frontend author had flagged exactly this risk (`admin.types.ts:233-241`: *"FLAG FOR
CONFIRMATION … Confirm against the real DTO before treating this as final"*).

**Now (re-verified 18:58):** a concurrent session landed "PHONE-0829 Gap B" and the chain is complete:

- `CreatorSummaryDto:28` and `CreatorDetailDto:45` both declare `String phone`, positioned after `email`.
- `AdminCreatorService:178-186` batch-loads `phonesByUserId` in the same `findAllById` loop as emails —
  no N+1 — using a plain `HashMap` so null phones don't trip `Collectors.toMap`.
- `toSummaryDto:502,509` and `toDetailDto:523,553` both pass it through; `toDetailDto` reuses the
  `User` row already loaded for email rather than issuing a second query.
- `mvn -o -q compile` exits 0.

Jackson serializes records by component name, so the frontend's `creator.phone` now receives the real
value. **No action needed.** Expect "— Not provided" to persist for pre-existing creators regardless —
`users.phone_number` had no write path before this ticket, so it is `NULL` for everyone who has not
since set one in Settings. That is correct behaviour, not a residual bug.

### F2 — HIGH · Functional · creator KYC is write-only; the creator can never see they submitted

**Where:** `CreatorProfileDtos.java:14-35`; `src/components/creator/KycIdentityForm.tsx:120-135`.

`identity_kyc_status` is persisted but exposed by no GET. `CreatorProfileSelfResponse` carries
`verified` (the unrelated `is_verified` discovery flag) and not `identityKycStatus`.
`KycIdentityForm` shows the status only from the in-memory result of the current submit — reload the
page or reopen the dialog and the creator sees a blank form as if they never submitted, so they
re-enter PAN/Aadhaar and re-upload the selfie every time.

**Fix:** add `identityKycStatus` to `CreatorProfileSelfResponse`, and have `KycIdentityForm` seed its
`saved` state from `GET /me/creator-profile` on mount.

### F3 — HIGH · Functional · admin shows the auto-approved column, hides the pending one

**Where:** `CreatorProfile.java:215` + `V38__creator_profile_moderation.sql`; `AdminCreatorDtos.java:34-61`;
`src/admin/components/users/CreatorProfile.tsx:136-146,375,414`; `AdminCreatorService.java:478`.

Full explanation in Part 2. Net effect: a green "Application Approved" badge that means nothing was
reviewed, an empty Pending Applications queue, and the real verification state invisible to admins.

**Fix (smallest honest change):** surface `identityKycStatus` on `CreatorDetailDto`/`CreatorSummaryDto`
and render it as its own pill; relabel the `application_status` pill so it cannot be read as identity
verification. If pre-approval review is actually wanted, that is a product decision — it means flipping
`newForUser` to `PENDING` **and** backfilling, which V38's own comment warns about.

### F4 — HIGH · Security/Compliance · payouts run on KYC nobody reviewed

**Where:** `WalletService.java:355-371`.

`requireIdentityKycSubmitted` gates withdrawal on *submitted* (`!= null`, `!= UNVERIFIED`, `!= REJECTED`),
not `VERIFIED`. This is deliberate and thoroughly documented — gating on `VERIFIED` would block every
withdrawal forever, since nothing can set it. The consequence stands regardless: **money leaves the
platform to creators whose PAN/Aadhaar/selfie has never been reviewed by anyone.**

**Fix:** build the admin KYC review endpoint (`PENDING → VERIFIED/REJECTED`) already logged as an open
follow-up in `TASK_INBOX.md`, then tighten this gate to `VERIFIED`.

### F5 — MEDIUM · Functional · brand "Pending" KYC filter hides the brands it labels Pending

**Where:** `AdminBrandService.java:792-798` (display) vs `:804-811` (filter).

Display collapses `UNVERIFIED, PENDING → PENDING`. The reverse filter maps the string `"PENDING"` to
`VerificationStatus.PENDING` only. `Workspace.java:126` defaults every new workspace to `UNVERIFIED`,
so **most brands are labelled "Pending" in the table and then disappear when you filter for "Pending".**

**Fix:** make the filter map `"PENDING"` to `IN (UNVERIFIED, PENDING)` so it matches the display collapse.

### F6 — LOW · Dead code · unreachable branch in creator settings

**Where:** `src/pages/creator-settings.tsx:580` — `item.status === 'verified'` renders a green check, but
no `settingsGroups` item ever sets `status`. Dead branch; also the only lowercase `'verified'` literal
in a codebase whose statuses are all uppercase.

### F7 — MEDIUM · Functional · the admin panel cannot see any brand's phone number

**Where:** `AdminBrandDtos.java`, `AdminBrandService.java`, `src/admin/components/users/BrandProfile.tsx`,
and the `Brand`/`BrandDetail` types in `src/admin/types/admin.types.ts` — grep for `phone` returns
**zero hits in all four**.

Brands have *two* populated phone columns and the admin panel exposes neither:

| Column | Written by | In an admin DTO? |
|---|---|---|
| `workspaces.phone` | `WorkspaceService.java:122` via `PATCH /workspaces/me` (brand settings) | No |
| `users.phone_number` | `AuthService.brandRegister` (PHONE-0829 Gap A, uncommitted) | No |

PHONE-0829 delivered creator-side admin visibility (F1) but left the brand side untouched. Swapnil's
original complaint — "admin cannot see mobile numbers" — is therefore only half closed.

**Fix:** mirror Gap B exactly on the brand side. `AdminBrandService` already resolves the owner `User`
for `resolveEmail`, so the owner's `users.phone_number` costs no extra query; `workspaces.phone` is on
the `Workspace` entity already loaded. Add both to `BrandSummaryDto`/`BrandDetailDto`, render in
`BrandProfile.tsx` next to the billing email.

---

## Gate

- **F1 — resolved mid-audit.** No longer blocks PHONE-0829; re-verified and compiling.
- **F7 is what remains of the original ask** — the admin panel still cannot see brand phone numbers.
- F2 / F3 / F4 → open tickets; F3 + F4 share one root cause (no creator KYC review workflow).
- F5 / F6 → this sprint, non-blocking.

## Note on concurrency

Two of the files in scope were rewritten by another session at 18:47/18:48 while this report was being
drafted. Any finding here older than that timestamp should be re-grepped before being actioned —
this repo has more than one agent writing to it.
