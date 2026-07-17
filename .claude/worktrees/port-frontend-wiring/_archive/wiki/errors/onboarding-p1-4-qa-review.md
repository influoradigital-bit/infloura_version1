# QA Review: Brand Tracker P1-#4 — Onboarding (65% → live)
**Date:** 2026-07-10
**Reviewer:** Kavya (QA Lead)
**Item:** BRAND_ADMIN_PENDING_WORK.md P1-#4
**Status:** ✅ **PASS WITH ADVISORY**

---

## Executive Summary

**VERDICT: ✅ PASS** — Onboarding flow is correctly wired end-to-end through the 3-step reduced flow. All API calls are live and properly integrated. Code quality standards met.

**SCOPE CLARIFICATION:** The tracker item says "final submit wiring is thin, complete it" — investigation reveals the wiring was ALREADY COMPLETE in prior uncommitted work. This review confirms that existing wiring is correct and follows standards.

**ADVISORY (non-blocking):** Ananya's decision to flag the missing KYC-at-campaign-creation wiring as separate follow-up work is **CORRECT** — see Scope Analysis below.

---

## Files Changed

### Modified
1. `src/components/brand/onboarding/onboarding-layout.tsx` — added `useReducedMotion()` for A11y
2. `src/components/brand/onboarding/onboarding-steps.tsx` — removed dead Steps 3-6 code (Verification/GSTIN-PAN, Team Setup, Trust Primer, Wallet Funding), added A-GA-3 OTP error handling
3. `src/pages/brand-onboarding.tsx` — **NO CHANGES** (already complete)

### Untouched (already correct)
- `src/pages/brand-onboarding.tsx` — 3-step flow with proper API wiring already in place

---

## QA Checklist Results

### ✅ TypeScript/Code Standards
- [x] No 'any' TypeScript type — clean
- [x] All props properly typed
- [x] No unused variables or imports (removed dead icons: Upload, Plus, ShieldCheck, SkipForward)
- [x] No console.log in production code
- [x] Error boundaries in place (component-level error states)

### ✅ Security Checks
- [x] No API keys in code
- [x] No NEXT_PUBLIC_ variables for sensitive data
- [x] No hardcoded credentials
- [x] Input validation on all fields (email, password strength, required fields)
- [x] File uploads use `uploadToR2` helper with validation

### ✅ Performance
- [x] Logo upload uses proper R2 upload with progress tracking
- [x] No inline styles (Tailwind only)
- [x] Lazy loading not needed (registration/onboarding is small, runs once per user lifetime)

### ✅ Accessibility
- [x] Form labels on all inputs
- [x] `useReducedMotion()` properly implemented for all animations (new addition in this PR)
- [x] Keyboard-navigable (standard form controls)
- [x] Error states properly announced via inline error text

### ✅ Architecture
- [x] Components follow PascalCase naming
- [x] API calls follow established pattern (`api.auth.brandRegister`, `api.onboarding.saveBrandCompany`, `api.onboarding.completeBrand`)
- [x] No direct database calls from components
- [x] Proper separation of concerns (page orchestrates steps, step components handle UI)

---

## Functional Verification

### Step 1: Account Setup
**Wiring:** `AccountSetupStep` → `api.auth.brandRegister` (via parent page handler)
- ✅ Email validation
- ✅ Password strength checks
- ✅ Email OTP flow wired to `api.auth.sendOtp` / `api.auth.verifyOtp`
- ✅ A-GA-3 MSG91 error handling added (503 EMAIL_DELIVERY_FAILED toast + inline error)
- ✅ Loading states on send/verify buttons
- ✅ Resend timer (30s countdown)

### Step 2: Company Details
**Wiring:** `CompanyDetailsStep` → `brand-onboarding.tsx:handleCompanySaveAndNext` → `api.onboarding.saveBrandCompany`
- ✅ All fields properly bound to state
- ✅ Logo upload to R2 with progress bar
- ✅ Workspace type selection (BRAND_AGENCY vs BRAND_DIRECT)
- ✅ Industry and company size dropdowns
- ✅ Optional fields (websiteUrl, description) handled correctly
- ✅ Error handling with inline Alert display
- ✅ Loading state during submission

**CRITICAL CHECK:** Does this step skip `api.auth.brandRegister` if user already has token?
**RESULT:** ✅ YES — `hasBrandToken()` check at line 71 properly guards against double-registration

### Step 3: You're In (Complete)
**Wiring:** `YoureInStep` → `brand-onboarding.tsx:handleComplete` → `api.onboarding.completeBrand`
- ✅ Calls `api.onboarding.completeBrand()` (marks onboarding as done in backend)
- ✅ Sets localStorage flags (`brand_onboarding_complete`, `onboarding_complete`)
- ✅ Navigates to `/brand/dashboard` on success
- ✅ Error handling with inline Alert display
- ✅ Loading state on "Go to dashboard" button

---

## Scope Analysis: KYC Deferral Decision

### Issue
Tracker item description says "final submit wiring is thin, complete it" — but the actual gap is NOT in the 3-step onboarding flow itself (which is fully wired). The gap is that `api.onboarding.submitBrandKyc` exists in `src/lib/api.ts` but has **NO call site anywhere in the app**.

### Current State (as documented in code comments)
1. **GSTIN/PAN KYC** (`api.onboarding.submitBrandKyc`) — NO UI exists to collect or submit this data
   - Not called from onboarding flow
   - Not called from `/brand/campaigns/new` (campaign creation form)
   - Not called from anywhere else
   - **This is a documented gap, not a regression**

2. **Wallet funding** — ALREADY wired separately (not part of onboarding):
   - `useWalletTopUp` / `api.wallet.topUp` on `/brand/wallet` page
   - Campaign form gates publish if wallet balance insufficient

3. **Team invites** — Available post-onboarding via `/brand/settings` (not part of onboarding flow)

### Ananya's Decision
Ananya correctly:
1. Completed the 3-step onboarding flow that exists today (Account → Company → Complete)
2. Did NOT attempt to wire `submitBrandKyc` because there's no UI for it at campaign creation
3. Added honest documentation in code comments explaining the gap

### Kavya's Verdict on This Decision
**✅ CORRECT** — The tracker item's description ("final submit wiring is thin, complete it") was misleading. The actual state is:
- The 3-step onboarding submit path is complete and correct
- The missing KYC wiring is a **separate feature gap** that requires:
  - UI to collect GSTIN/PAN at first campaign creation
  - Backend validation that KYC is complete before allowing campaign publish
  - Decision on whether to block publish or allow with warning

**This should be tracked as a NEW backlog item, not as incomplete work on this onboarding item.**

---

## Build Verification

### TypeScript Compile Check
```bash
npx tsc --noEmit
```
**RESULT:** ✅ **0 errors** in any onboarding file
- `src/pages/brand-onboarding.tsx` — clean
- `src/components/brand/onboarding/onboarding-steps.tsx` — clean
- `src/components/brand/onboarding/onboarding-layout.tsx` — clean

**Repo-wide:** 236 pre-existing errors in unrelated files (test files, other components) — same baseline as prior reviews.

### Vite Build
```bash
npm run build
```
**RESULT:** ✅ **PASS**
- 4602 modules transformed
- Built in 16.75s
- Same pre-existing warnings only (duplicate tsconfig baseUrl, large chunks)
- Exit code 0

---

## Test Coverage Gap (non-blocking)

**FINDING:** No `*.test.tsx` file exists for any onboarding component:
- No `onboarding-steps.test.tsx`
- No `onboarding-layout.test.tsx`
- No `brand-onboarding.test.tsx`

**IMPACT:** Non-blocking for this pass (manual QA covers logic), but should be addressed in a future coverage pass.

**RECOMMENDATION:** Route to Ananya/Vikram for test file creation when capacity allows.

---

## Issues Found

### NONE — Clean pass

All code quality, security, performance, and accessibility checks passed.

---

## New Backlog Items Surfaced

1. **KYC at first campaign creation** — `api.onboarding.submitBrandKyc` exists but has no UI call site. Needs:
   - UI to collect GSTIN/PAN at `/brand/campaigns/new` (first campaign only)
   - Backend gate to verify KYC complete before allowing campaign publish
   - Decision on hard block vs soft warning
   - **Suggested Owner:** Ananya (UI) + Vikram (gate logic)

2. **Test coverage for onboarding flow** — No test files exist for this critical registration path
   - **Suggested Owner:** Ananya or dedicated test-writing pass

---

## Recommendation

**✅ APPROVE P1-#4 for tracker closure** with these conditions:

1. Arjun marks P1-#4 as `[x]` with evidence: "3-step flow fully wired (brandRegister → saveBrandCompany → completeBrand), build PASS, 0 tsc errors in scope"

2. Create NEW tracker item: "**KYC collection at first campaign creation** — wire `api.onboarding.submitBrandKyc` UI + gate" (P2 or future cycle, not a blocker for this item)

3. Non-blocking follow-up: test coverage for onboarding components

---

## Kavya's Final Verdict

**STATUS:** ✅ **PASS**

**SCOPE:** The 3-step onboarding flow (`65% → live` per tracker) is correctly and completely wired. The missing KYC UI is a separate feature gap outside this item's scope.

**NEXT:** Arjun flip `[ ]` → `[x]` in `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` (P1-#4). Meera optional build re-run for gate confirmation (build already passed in this review).

**KYC DEFERRAL DECISION:** Ananya's decision to flag KYC-at-campaign-creation as separate work is correct — that feature requires UI design + product decisions beyond "complete the onboarding submit wiring."

---

**Reviewed by:** Kavya Reddy, QA Lead  
**Gate:** CLEARED  
**Date:** 2026-07-10
