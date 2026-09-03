# 🧪 Test Report: field\-persistence\-brand\-onboarding

- **Date:** 2026\-09\-01
- **Target:** src/pages/brand\-onboarding\.tsx, src/components/brand/onboarding/onboarding\-steps\.tsx, src/lib/api\.ts, influora\-api OnboardingController/AuthService/CreatorProfileService, AdminCreatorDtos
- **Stages run:** Build, Functional, Security
- **Health:** 57%
- **Verdict:** FAIL ❌

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 3 |
| Medium | 3 |
| Low | 1 |

## Findings by tester

### Kavya — Functional

#### [High] Brand onboarding phone is still discarded \- PHONE\-0829 P2 is frontend\-only
- **Where:** influora\-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest\.java:9 \(vs src/pages/brand\-onboarding\.tsx:76\)
- **Issue:** The wizard renders Mobile number as Required and validates it against a strict 10\-digit Indian\-mobile regex, and brand\-onboarding\.tsx now sends phone in the register payload\. But BrandRegisterRequest has no phone component \(verified by reflecting over the compiled target/classes: 8 components, none named phone\), BrandCompanyRequest has none either, and AuthService\.brandRegister never calls Workspace\.updatePhone or User\.setPhoneNumber\. Jackson FAIL\_ON\_UNKNOWN\_PROPERTIES is unset, so Spring Boot's default of false silently drops the field \- no 400, no log\. Every brand's typed mobile is still lost, while the in\-code comment claims the bug was fixed\.
- **Fix:** Add a phone component to BrandRegisterRequest, then in AuthService\.brandRegister normalize and validate via IndianPhoneUtils and call workspace\.updatePhone\(normalized\) before workspaceRepository\.save \(TASK\_INBOX PHONE\-0829 P2 targets workspaces\.phone, the existing write path\)\. Add a service test asserting the saved Workspace carries the phone\.

#### [High] Admin still cannot see creator phone numbers \- PHONE\-0829 P3 is frontend\-only
- **Where:** influora\-api/src/main/java/com/influora/web/dto/admin/AdminCreatorDtos\.java:20
- **Issue:** This is the originating complaint of the whole ticket\. CreatorSummaryDto \(10 components\) and CreatorDetailDto contain no phone field\. The admin UI was written against an assumed contract \- admin\.types\.ts declares phone as string\-or\-null with its own FLAG FOR CONFIRMATION comment, and CreatorProfile\.tsx renders the value or falls back to a dash\. Since the server never sends the key, the panel renders the fallback for every creator even after the creator saves a phone in settings\. tsc cannot catch this because the Java DTO is invisible to it\.
- **Fix:** Add a phone component to CreatorSummaryDto and CreatorDetailDto, populate it from User\.getPhoneNumber\(\) in the AdminCreatorService mappers, then remove the FLAG FOR CONFIRMATION comments in admin\.types\.ts once the shape is confirmed\.

#### [Medium] companySize vocabulary divergence \- onboarding writes values admin rejects
- **Where:** src/components/brand/onboarding/onboarding\-steps\.tsx:145 \(vs AdminBrandService\.java:775\)
- **Issue:** Onboarding writes 12 display strings from '1\-10 employees' through '1000\+ employees' into workspaces\.company\_size, and brand\-onboarding\.tsx:68 sends a fourth vocabulary \('1\-5'\) as the register fallback\. AdminBrandService\.KNOWN\_SIZES is the closed set STARTUP/SMB/ENTERPRISE and update\(\) throws INVALID\_BRAND\_SIZE for anything outside it, so a real onboarded brand's size can never be re\-saved through admin\. normalizeSize passes the unknown value through raw into BrandDetailDto, which admin\.types\.ts:158 types as the closed union \- a type that lies about the data\. BrandProfile\.tsx's Select offers only the 3 options, so the value renders empty\.
- **Fix:** Pick one vocabulary\. Either map the onboarding dropdown to STARTUP/SMB/ENTERPRISE at write time, or widen KNOWN\_SIZES and admin\.types\.ts to the real set and backfill existing rows\. Do not leave the frontend union asserting a shape the database does not hold\.

#### [Medium] Company logo silently dropped if the user advances before the upload finishes
- **Where:** src/components/brand/onboarding/onboarding\-steps\.tsx:884
- **Issue:** handleLogoSelect uploads to R2 asynchronously and only sets logoUpload on completion\. CompanyDetailsStep\.validate\(\) does not check isUploadingLogo and StepFooter's submit button is not disabled while the upload is in flight\. saveBrandCompany then sends logoUrl from an optional chain that evaluates to undefined, and Workspace\.applyCompanyDetails assigns this\.logoUrl unconditionally, writing null\. The user sees their logo preview on screen, clicks Continue, and the logo is neither saved nor reported as lost\.
- **Fix:** Disable the Continue button while isUploadingLogo is true \- StepFooter already accepts a disabled prop that this call site does not pass \- or block in validate\(\) with a 'Logo still uploading' message\.

#### [Low] Dead fields still declared in OnboardingData
- **Where:** src/components/brand/onboarding/onboarding\-steps\.tsx:84
- **Issue:** teamMembers, walletAmount, gstin, pan and the four KYC document members remain in OnboardingData and initialData with zero readers since the wizard dropped from 6 steps to 3 \(KYC moved to brand\-kyc\-prompt\.tsx, which holds its own state\)\. No data loss, but the type reads as though the wizard still collects these, which is what makes a which\-fields\-are\-saved question hard to answer from the type alone\.
- **Fix:** Delete the unused members from OnboardingData and initialData; the deferred\-collection comment at brand\-onboarding\.tsx:22 already documents where they moved\.

### Kabir — Security

#### [High] Onboarding asserts terms consent the user never gave, and stores no consent record
- **Where:** src/pages/brand\-onboarding\.tsx:73
- **Issue:** acceptTerms and acceptPrivacy exist in OnboardingData and initialData but are rendered in neither live step \- grep shows zero readers outside the declaration\. brand\-onboarding\.tsx hardcodes acceptedTerms to true, so the backend AssertTrue on BrandRegisterRequest\.acceptedTerms is vacuous on this path: it can never fail because the client always sends true\. Separately, consent is never persisted \- grep for terms\_accepted, termsAccepted and accepted\_terms across influora\-api returns zero hits, so there is no column, timestamp, or policy version\. brand\-register\.tsx and creator\-register\.tsx do render real checkboxes; only the onboarding wizard skips it\.
- **Fix:** Render a real Terms and Privacy checkbox in AccountSetupStep, block the Send verification code button until it is checked, and pass the actual value\. Persist consent \(accepted\_at plus policy version\) on users so it is auditable, rather than validating a value nothing stores\.

### Meera — Build

#### [Medium] Every automated gate is green on a half\-applied feature
- **Where:** influora\-api/src/test/java/com/influora/service/CreatorProfileServiceTest\.java
- **Issue:** Verified by running them: mvn \-o compile exits 0, npm run typecheck exits 0 clean, and 21 backend tests pass \(CreatorProfileServiceTest 13, OnboardingServiceTest 6, MeCreatorProfileControllerTest 2, zero failures\)\. Not one of them asserts that a brand's phone reaches the database, and none covers the admin phone DTO\. The suite greens the creator half of PHONE\-0829 while both broken halves pass unnoticed, so CI cannot distinguish done from frontend\-done\-backend\-missing\.
- **Fix:** Add a brand\-register service test asserting the persisted Workspace carries the submitted phone, and an admin DTO test asserting phone is present in CreatorSummaryDto\. Both fail today and are what would have caught P2 and P3\.

## Next steps
- Route Critical/High blockers to Ananya (frontend) / Vikram (backend) via Arjun.
- Escalate Critical to Swapnil via Kavya/Priya.
- Re-run the same stage after fixes before final PASS.

_Generated by the `tester` skill._
---

## Field-by-field persistence matrix — Brand onboarding

Traced for every field: rendered input → `OnboardingData` → API payload → Java DTO → entity mutator → DB column.
`✅` = value reaches a column. `⚠️` = reaches a column but is unusable/at risk downstream. `❌` = never reaches any column.

### Step 1 — Account (`AccountSetupStep`)

| Field | UI → payload | Server DTO accepts | Persisted to | Status |
|---|---|---|---|---|
| firstName | ✅ | `BrandRegisterRequest.firstName` | `users.first_name` via `User.newBrand` | ✅ SAVED |
| lastName | ✅ | `BrandRegisterRequest.lastName` | `users.last_name` via `User.newBrand` | ✅ SAVED |
| email | ✅ | `BrandRegisterRequest.email` | `users.email` (UNIQUE) | ✅ SAVED |
| password | ✅ | `BrandRegisterRequest.password` | `users.password_hash` (bcrypt) | ✅ SAVED |
| confirmPassword | client-only | n/a | n/a | ✅ N/A by design |
| emailOtpCode | ✅ verify call | `verifyBrandEmail` | `users.email_verified` when OTP gate on | ✅ SAVED |
| **phone** | ✅ sent | **❌ no component** | **nothing** | **❌ DROPPED** |
| **acceptTerms / acceptPrivacy** | **no checkbox rendered**, hardcoded `true` | `acceptedTerms` (validated only) | **nothing** | **❌ NOT COLLECTED, NOT STORED** |

### Step 2 — Company (`CompanyDetailsStep`)

| Field | UI → payload | Server DTO accepts | Persisted to | Status |
|---|---|---|---|---|
| companyName | ✅ | `BrandCompanyRequest.companyName` | `workspaces.name` | ✅ SAVED |
| companySlug | ✅ + live availability check | `companySlug` (+ `ensureSlugAvailable`) | `workspaces.slug` (UNIQUE) | ✅ SAVED |
| workspaceType | ✅ | `workspaceType` | `workspaces.type` | ✅ SAVED |
| industry | ✅ | `industry` | `workspaces.industry` | ✅ SAVED |
| companySize | ✅ | `companySize` | `workspaces.company_size` | ⚠️ SAVED, vocabulary unusable by admin |
| websiteUrl | ✅ | `websiteUrl` | `workspaces.website_url` (+ triggers site analysis) | ✅ SAVED |
| description | ✅ | `description` | `workspaces.description` (TEXT) | ✅ SAVED |
| logoUrl | ✅ if upload finished | `logoUrl` | `workspaces.logo_url` | ⚠️ RACE — lost if user advances mid-upload |

### Step 3 — Completion

| Field | Persisted to | Status |
|---|---|---|
| onboarding completion | `users.onboarding_completed` via `completeBrand` | ✅ SAVED |

### Deferred by design (not lost)

| Field | Where it moved | Status |
|---|---|---|
| gstin, pan, gstinDocUrl, panDocUrl | `brand-kyc-prompt.tsx` → `POST /onboarding/brand/kyc` → `Workspace.applyKyc` | ✅ SAVED |
| wallet funding | first deal acceptance → `api.wallet.topUp` | ✅ by design |
| team invites | `/brand/settings` post-onboarding | ✅ by design |
| `teamMembers`, `walletAmount` in `OnboardingData` | nothing reads them | ⚠️ dead type members |

### Score

**14 of 16 persisted fields reach a column. 12 are fully clean (75%), 2 are at-risk (⚠️), 2 are lost (❌).**

---

## Related surface — PHONE-0829 across all three phones

| Column | Write path | Status |
|---|---|---|
| `users.phone_number` | `CreatorProfileService.applyPhone` (normalize → validate → UNIQUE check) | ✅ **P1 DONE** — 13 tests pass |
| `workspaces.phone` | `WorkspaceService.updateMyWorkspace` (brand settings, post-onboarding) | ✅ works — but onboarding still cannot write it |
| brand onboarding → `workspaces.phone` | **none** | ❌ **P2 NOT DONE** (frontend half only) |
| admin read of creator phone | **not in `AdminCreatorDtos`** | ❌ **P3 NOT DONE** (frontend half only) |

## Verification actually run

| Check | Command | Result |
|---|---|---|
| Backend compiles | `mvn -o -DskipTests compile` | ✅ exit 0 |
| Frontend types | `npm run typecheck` | ✅ exit 0, clean |
| Touched backend tests | `mvn -o test -Dtest=CreatorProfileServiceTest,MeCreatorProfileControllerTest,OnboardingServiceTest` | ✅ 21/21 pass, 0 failures |
| DTO shape (bytecode) | reflection over `target/classes` record components | ❌ proved `phone` absent from both brand DTOs, present on creator DTO |

Note: the green suite is itself finding #6 — no test covers either broken path.
