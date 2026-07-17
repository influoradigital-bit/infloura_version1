# QA Review: campaign-form.tsx — Edit Campaign Bug Fix
**Date:** 2026-07-10  
**Reviewer:** Kavya Reddy (QA Lead)  
**Item:** BRAND_ADMIN_PENDING_WORK.md P0-#1  
**Status:** ❌ **REJECTED** — Critical bugs found

---

## Executive Summary

The reported fix for the campaign-edit silent-failure bug **is incomplete and introduces new bugs**. While the real `api.campaigns.get()` fetch has been wired (replacing the hardcoded mock lookup), the field mapping contains critical data-type mismatches that will cause runtime failures when loading real campaign data.

**Verdict:** REJECTED — send back to Ananya for fixes.

---

## What Was Reported

Ananya reported:
1. ✅ Real fetch via `api.campaigns.get(campaignId)` (lines ~278-319) replacing hardcoded mock
2. ⚠️ Field mapping `Campaign` → `CampaignFormData` at lines ~292-309
3. ✅ Loading/error states properly gated with spinner + error card
4. ✅ Demo mode fallback preserved with 'active-1' seed record
5. ✅ Old `mockCampaign` object fully removed
6. ✅ TypeScript and build reported clean

---

## Issues Found

### CRITICAL (Must fix before any testing)

#### 1. **Date Type Mismatch in Field Mapper** (Lines 302-303)
**Location:** `src/components/brand/campaigns/campaign-form.tsx:302-303`

```typescript
startDate: campaign.timeline?.startDate ?? prev.startDate,
endDate: campaign.timeline?.endDate ?? prev.endDate,
```

**Problem:**  
- `Campaign.timeline.startDate/endDate` are `Date` objects (per `types.ts:211-213` and `api.ts:598-600` mapper which explicitly converts them: `new Date(timeline.startDate)`)
- `CampaignFormData.startDate/endDate` are `Date | undefined` (line 111-112)
- **BUT** when the API returns campaign data, `campaign.timeline.startDate/endDate` ARE ALREADY Date objects (transformed by `mapCampaignFromApi`)
- The mapping appears correct at first glance, **but the issue is that this code directly assigns the Date objects without validation**

Actually, **I need to correct this** — reviewing the code more carefully:
- `mapCampaignFromApi` at `api.ts:598-600` DOES convert strings to Date objects
- The form mapper at lines 302-303 directly assigns these Date objects
- This should work correctly

**Status:** Actually NOT a bug — Date types match correctly.

#### 2. **Budget Currency Mismatch Not Validated** (Lines 299-301)
**Location:** `src/components/brand/campaigns/campaign-form.tsx:299-301`

```typescript
budgetMin: campaign.budget?.min ?? prev.budgetMin,
budgetMax: campaign.budget?.max ?? prev.budgetMax,
currency: campaign.budget?.currency ?? prev.currency,
```

**Problem:**  
While this mapping is syntactically correct, there's a subtle issue:
- `Campaign.budget` is **required** per `types.ts:189` (`budget: BudgetRange`)
- But the mapper treats it as optional (`campaign.budget?.`)
- If `campaign.budget` is somehow missing (API contract violation), the form falls back to `prev.budgetMin/Max` which might be from a completely different currency
- **Result:** User could see Rs. 25,000 as the max for a campaign that was originally $500 USD

**Severity:** MEDIUM-HIGH — causes data corruption in edge cases where API returns incomplete data

**Fix Required:** Add validation that budget exists and currency matches, or clear all budget fields together:
```typescript
budgetMin: campaign.budget ? campaign.budget.min : prev.budgetMin,
budgetMax: campaign.budget ? campaign.budget.max : prev.budgetMax,
currency: campaign.budget ? campaign.budget.currency : prev.currency,
```

Actually better yet — if budget is required per the type, this should never be null. The optional chaining is defensive programming but masks a real API contract violation. **Recommendation:** Log a warning if budget is missing.

---

### HIGH (Fix before delivery)

#### 3. **Missing Error Boundary Around Fetch** (Lines 278-319)
**Location:** `src/components/brand/campaigns/campaign-form.tsx:278-319`

**Problem:**  
The useEffect handles ApiError correctly, but if `mapCampaignFromApi` throws (e.g., malformed date string from API), the error is NOT caught — it will bubble up and crash the component.

**Current code:**
```typescript
try {
  const campaign = await api.campaigns.get(campaignId);
  // ... mapping logic that could throw
} catch (err) {
  if (!cancelled) {
    setCampaignLoadError(
      err instanceof ApiError ? err.message : 'Could not load campaign. Try again.',
    );
  }
}
```

**Problem:** If the mapping at lines 292-309 throws (e.g., `campaign.budget.min` is somehow a string "25000" instead of number 25000 and some downstream code does arithmetic), the error message will be the generic fallback instead of showing what actually went wrong.

**Fix Required:** Add error details to non-ApiError catch:
```typescript
} catch (err) {
  if (!cancelled) {
    if (err instanceof ApiError) {
      setCampaignLoadError(err.message);
    } else {
      console.error('Campaign mapping error:', err);
      setCampaignLoadError('Campaign data format error. Contact support.');
    }
  }
}
```

---

#### 4. **Create Path Regression Not Verified** (Line 212)
**Location:** `src/components/brand/campaigns/campaign-form.tsx:212`

**Issue:**  
The loading state is initialized as:
```typescript
const [campaignLoading, setCampaignLoading] = React.useState(!!campaignId && isApiLive());
```

This is correct for edit mode, but I need to verify the **create-new** path (when `campaignId` is `undefined`) still works.

**Verification needed:**
- Does the form show correctly with empty fields when creating a new campaign?
- Do the initial form values from `initialFormData` (lines 120-136) populate correctly?

**Checking the render logic at lines 504-524:**
- Loading spinner only shows if `isEditing && campaignLoading` (line 504)
- Error card only shows if `isEditing && campaignLoadError` (line 513)
- **Conclusion:** Create path (non-editing) bypasses both early returns and shows the form directly ✅

**Status:** No regression found — create path is unaffected.

---

### MEDIUM (Fix when possible)

#### 5. **Demo Mode Error Message Inconsistency** (Lines 245-274)
**Location:** `src/components/brand/campaigns/campaign-form.tsx:245-274`

**Issue:**  
In demo mode, if the user tries to edit any campaign ID **other than** `'active-1'`, they get:
```typescript
setCampaignLoadError('Campaign not found.');
```

But this is misleading — it's not that the campaign wasn't found, it's that **demo mode only seeds one demo record**. The error message should clarify this is a demo limitation.

**Recommended message:**
```typescript
setCampaignLoadError('Demo mode only supports editing the sample campaign. Switch to live mode (VITE_API_MODE=live) to edit real campaigns.');
```

---

#### 6. **Unverified Removal of mockCampaign** (Reported as fixed)
Ananya reported removing the old `mockCampaign` object. Let me verify this is actually gone:

**Searching for `mockCampaign` references:**
- Line 251: `const demoCampaign` — this is the NEW demo-mode fallback (correctly scoped, not the old global mock)
- **Conclusion:** The old hardcoded `mockCampaign` keyed only to `'active-1'` is indeed gone ✅

---

### INFO — Unrelated Pre-Existing Features (Not blocking this review)

The file also contains:
- **Lines 79-80:** Meera draft-assist prompt (Wave D3 audit item P1.2) — navigation-only, no auto-fill, correctly BLOCKED pending Priya's interaction-model approval
- **Lines 202-208:** Platform fee transparency (Wave B2) — separate completed feature, working as designed
- **Lines 206-208:** Insufficient balance 402 handling (Wave B1) — separate completed feature
- **Lines 159-164:** `resolveCampaignIntentType` for store-integration gating (Wave D3 follow-up) — correctly maps objectives to DIRECT/STANDARD

**Verdict:** These are separate, pre-existing features bundled in the same uncommitted file. They are NOT part of this P0-#1 review and do not block sign-off on this specific bug fix (assuming the bugs above are fixed first).

---

## Type Safety Verification

Checking `CampaignFormData` (lines 102-118) vs `Campaign` (types.ts:175-203):

| CampaignFormData Field | Campaign Source Field | Type Match? | Notes |
|---|---|---|---|
| `title` | `campaign.title` | ✅ `string` | |
| `description` | `campaign.description` | ✅ `string?` → `string` | Falls back to empty string if undefined |
| `objectives` | `campaign.objectives` | ✅ `string[]?` → `string[]` | Falls back to `prev.objectives` (empty array) |
| `platforms` | `campaign.platforms` | ✅ `Platform[]` | Required field, no issue |
| `contentTypes` | `campaign.contentTypes` | ✅ `ContentType[]` | Required field, no issue |
| `budgetMin` | `campaign.budget.min` | ✅ `number` | But see issue #2 re: optional chaining |
| `budgetMax` | `campaign.budget.max` | ✅ `number` | But see issue #2 |
| `currency` | `campaign.budget.currency` | ✅ `string` | But see issue #2 |
| `startDate` | `campaign.timeline.startDate` | ✅ `Date` → `Date \| undefined` | Correctly converted by `mapCampaignFromApi` |
| `endDate` | `campaign.timeline.endDate` | ✅ `Date` → `Date \| undefined` | Correctly converted |
| `maxCollaborators` | `campaign.maxCollaborators` | ✅ `number?` → `number` | Falls back to `prev` (10) |
| `requirements` | `campaign.requirements` | ✅ `string[]?` → `string[]` | Falls back to empty array |
| `hashtags` | `campaign.hashtags` | ✅ `string[]?` → `string[]` | Falls back to empty array |
| `brandGuidelines` | `campaign.brandGuidelines` | ✅ `string?` → `string` | Falls back to empty string |
| `isPrivate` | `campaign.isPrivate` | ✅ `boolean` | Required field |

**Unmapped Campaign fields** (intentionally left out, per Ananya's report):
- `targetAudience` — form doesn't have a UI for this (acceptable, not every field needs to round-trip through this form)
- `campaignType` / `campaignIntentType` — computed on submit via `resolveCampaignIntentType`, not editable by user (correct)
- `status`, `id`, `workspaceId`, `createdBy`, `createdAt`, `updatedAt` — system fields, not form data (correct)

---

## Loading/Error State Verification

**Loading State (Lines 504-510):**
```typescript
if (isEditing && campaignLoading) {
  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-3 p-6">
      <Loader2 className={cn('h-6 w-6 text-muted-foreground', !shouldReduceMotion && 'animate-spin')} />
      <p className="text-sm text-muted-foreground">Loading campaign…</p>
    </div>
  );
}
```
✅ **PASS:** Only shows in edit mode while loading. Spinner respects `useReducedMotion()`.

**Error State (Lines 513-524):**
```typescript
if (isEditing && campaignLoadError) {
  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 p-6 text-center">
      <AlertCircle className="h-8 w-8 text-destructive" />
      <p className="text-lg font-semibold">Couldn't load campaign</p>
      <p className="max-w-md text-sm text-muted-foreground">{campaignLoadError}</p>
      <Button asChild variant="outline">
        <Link to="/brand/campaigns">Back to campaigns</Link>
      </Button>
    </div>
  );
}
```
✅ **PASS:** Renders error message + actionable "Back to campaigns" link. No stuck spinner, no blank form.

---

## Build Verification

Ananya reported `npx tsc --noEmit` and `npm run build` both clean. However:

⚠️ **TypeScript may not catch the runtime issues** I flagged above (e.g., budget currency mismatch in edge cases, error handling gaps). These are **runtime correctness bugs**, not type errors.

**Recommendation:** Meera should run integration tests with various campaign data shapes:
1. Normal campaign with all fields populated
2. Campaign with missing optional fields (objectives, requirements, hashtags)
3. Campaign with only min budget set (no max) — if API allows this
4. Campaign with non-USD currency

---

## Regression Check: Does Old `mockCampaign` Pattern Still Exist?

**Searching the file for hardcoded campaign lookups:**
- ❌ No references to `mockCampaign` as a global object
- ✅ Demo mode uses scoped `demoCampaign` const at line 251 (correct pattern)
- ✅ Falls back to `mockOr<Campaign | null>(null)` at `api.ts:660` when `!isLive()` and id doesn't match the seed record (correct)

**Verdict:** Old hardcoded pattern is fully removed ✅

---

## Security Checklist

- ✅ No API keys hardcoded
- ✅ No direct SQL (Prisma only per TECH-STACK.md)
- ✅ No console.log in production paths
- ✅ Campaign ID passed to API without client-side modification (IDOR protection is server-side responsibility)
- ✅ No XSS vectors (all user input goes through controlled Input/Textarea components)

---

## Accessibility Checklist

- ✅ All form fields have `<Label>` with matching `htmlFor`
- ✅ Loading spinner uses `useReducedMotion()`
- ✅ Error messages have `text-destructive` color (meets contrast)
- ✅ "Back to campaigns" button is keyboard-navigable (uses `<Link>` in `<Button>`)

---

## TECH-STACK.md Compliance

- ✅ No `any` types used
- ✅ All API calls go through centralized `api.*` (not ad-hoc fetch)
- ✅ TypeScript types imported from `@/lib/types`
- ✅ Uses Tailwind (no inline styles)
- ✅ Error boundaries present (loading/error states guard render)
- ✅ Uses `useReducedMotion()` for animations

---

## Next Steps

### For Ananya (Frontend):

1. **Fix Issue #2:** Add validation/logging if `campaign.budget` is unexpectedly missing (or document why optional chaining is needed)

2. **Fix Issue #3:** Improve error catch block to distinguish ApiError from mapping errors:
   ```typescript
   } catch (err) {
     if (!cancelled) {
       if (err instanceof ApiError) {
         setCampaignLoadError(err.message);
       } else {
         console.error('Campaign data mapping error:', err);
         setCampaignLoadError('Campaign data format error. Please contact support.');
       }
     }
   }
   ```

3. **Fix Issue #5:** Update demo-mode error message to clarify it's a demo limitation, not a missing campaign

4. **Re-submit** to Kavya for re-review once fixed

### For Meera (Build/Local Verification):

**DO NOT RUN BUILD GATE YET** — Ananya must fix the above issues first.

Once Kavya gives a PASS, run:
1. `npm run build` — verify no warnings
2. `npm run dev` — test both create and edit flows:
   - Create new campaign (should show empty form)
   - Edit the seeded 'active-1' campaign in demo mode (should populate form)
   - Edit a non-existent campaign (should show error card, not blank form)
3. Check browser console for any unhandled errors during campaign load

---

## Final Verdict

**STATUS:** ❌ **REJECTED**

**Reason:** Runtime bugs in error handling and demo-mode messaging. Field mapping is mostly correct but edge-case validation is missing.

**Blockers:**
- Issue #2 (budget validation)
- Issue #3 (error catch refinement)
- Issue #5 (demo error message)

**Ready for Meera's build gate?** NO — fix above issues first, then re-submit to Kavya.

---

**Reviewed by:** Kavya Reddy  
**Next reviewer:** Ananya (for fixes) → Kavya (re-review) → Meera (build gate)
