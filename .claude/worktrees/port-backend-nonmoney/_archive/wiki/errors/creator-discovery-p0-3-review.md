# QA Review: creator-discovery.tsx — P0-#3 Fix
**Date:** 2026-07-10  
**Reviewer:** Kavya  
**Task:** Fix `liveApi` fallback masking outages (P0-#3 from BRAND_ADMIN_PENDING_WORK.md)  
**Status:** ✅ **PASS**

---

## Summary
The fix correctly addresses silent-catch patterns that previously masked API failures as "genuinely empty" results. Two sections were properly instrumented with error states and retry UX, while preserving the main creator-search error handling that was already correct.

---

## Verification Results

### ✅ 1. Featured Creators Section (Lines ~393-397, 534-567, 1123-1146)

**State Management:**
- Line 395: `featuredError` state added ✓
- Line 394: `featuredLoading` state exists ✓

**Fetch Function:**
- Lines 536-567: `fetchFeatured` is a proper `useCallback` ✓
- Line 538-540: Demo mode (`!liveApi`) correctly bypasses API and clears error ✓
- Lines 545-560: API fetch properly handles success and error:
  - Success: sets `featuredSections` with filtered results ✓
  - Error: logs to console, clears list to `[]`, sets `featuredError` with message ✓
  - No silent swallowing ✓

**Error UI:**
- Lines 1133-1146: Error notice renders when `!featuredLoading && liveApi && featuredError` ✓
- Line 1141: Retry button calls `fetchFeatured()` correctly ✓
- Line 1148: Success render condition is `!featuredLoading && !featuredError && featuredSections.length > 0` ✓
  - This correctly excludes error state — no overlap possible ✓

### ✅ 2. Invite Dialog Campaign Dropdown (Lines ~397, 633-655, 1507-1521)

**State Management:**
- Line 397: `campaignsError` state added ✓
- Line 396: `inviteCampaigns` state exists ✓

**Fetch Function:**
- Lines 633-651: `fetchInviteCampaigns` is a proper `useCallback` ✓
- Line 634: Demo mode (`!liveApi`) returns early — no API call ✓
- Line 635: Clears `campaignsError` on retry ✓
- Lines 636-650: API fetch properly handles success and error:
  - Success: maps campaign data to invite format ✓
  - Error: logs to console, clears list to `[]`, sets `campaignsError` with message ✓
  - No silent swallowing ✓

**Error UI:**
- Lines 1507-1521: Error hint renders when `liveApi && campaignsError` ✓
- Line 1515: Retry button calls `fetchInviteCampaigns()` correctly ✓
- Retry resets error state (line 635) so successful retry clears notice ✓

### ✅ 3. Main Creator Search NOT Regressed (Lines 573-631, 1211-1226)

**Fetch Function:**
- Lines 573-623: `fetchCreators` properly handles errors:
  - Try block: sets `apiCreators` and `totalCount` on success ✓
  - Catch block: clears data, sets `apiError` with message ✓
  - Finally: clears `apiLoading` ✓
- Lines 602-607: Error sets both `apiCreators` to `[]` AND `apiError` to message ✓
  - NOT silent — error is exposed via state ✓

**Error UI:**
- Lines 1211-1226: Main error banner renders when `liveApi && apiError` ✓
- Line 1220: Retry button calls `fetchCreators()` ✓
- Line 1229: Loading skeleton shows when `apiLoading && liveApi` ✓
- Line 1235: Empty state shows only when `!apiError && filteredCreators.length === 0` ✓
  - Error state and empty state do NOT overlap ✓

**NO REGRESSION DETECTED** ✓

### ✅ 4. Retry Button Function Wiring

**Featured:**
- Line 1141: `onClick={() => fetchFeatured()}` ✓
- `fetchFeatured` is stable `useCallback` (line 536) ✓
- Retry clears `featuredError` (line 544), so success removes error notice ✓

**Campaigns:**
- Line 1515: `onClick={() => fetchInviteCampaigns()}` ✓
- `fetchInviteCampaigns` is stable `useCallback` (line 633) ✓
- Retry clears `campaignsError` (line 635), so success removes error hint ✓

**Main Search:**
- Line 1220: `onClick={() => void fetchCreators()}` ✓
- `fetchCreators` is stable `useCallback` (line 573) ✓
- Retry clears `apiError` (line 576), so success removes error banner ✓

### ✅ 5. Success-Path JSX Conditions

**Featured Section:**
- Line 1148: `{!featuredLoading && !featuredError && featuredSections.length > 0 && (` ✓
- Error block (1133-1146): `{!featuredLoading && liveApi && featuredError && (` ✓
- Conditions are mutually exclusive — cannot render both ✓

**Campaigns (in Select):**
- Error hint (1507-1521): Only shows when `liveApi && campaignsError` ✓
- Select dropdown (1491-1506): Always renders (shows empty if no campaigns) ✓
- Correct behavior: dropdown remains usable, error hint appears below ✓

**Main Search:**
- Error banner (1212-1226): `{liveApi && apiError && (` ✓
- Loading skeleton (1229-1234): `{apiLoading && liveApi ? (` ✓
- Empty state (1235-1249): `{!apiError && filteredCreators.length === 0 ? (` ✓
- Success grid (1250-1432): `{viewMode === 'grid' ? (` (renders when `filteredCreators.length > 0 && !apiError`) ✓
- Conditions are mutually exclusive ✓

### ✅ 6. Demo Mode (`!liveApi`) Unaffected

**Featured:**
- Lines 537-540: Demo mode sets featured sections from `buildDemoFeaturedSections(mockCreators)` ✓
- Clears `featuredError` (line 538) ✓
- Returns early (`return undefined`) — no API call ✓
- Demo featured sections render normally (line 1148 condition met) ✓

**Campaigns:**
- Line 634: Demo mode returns early — no API call ✓
- `inviteCampaigns` initialized to `mockCampaigns` (line 396) ✓
- Demo invite dialog shows mock campaigns ✓

**Main Search:**
- Line 574: `if (!liveApi) return;` — no API call in demo mode ✓
- Demo creators filtered client-side from `mockCreators` (lines 669-772) ✓
- Demo mode bypasses `apiError` entirely (only set in `fetchCreators` which doesn't run) ✓

**DEMO MODE FULLY FUNCTIONAL** ✓

### ✅ 7. Other Silent-Failure Patterns Checked

**Grep Results:** Only 2 `.catch()` occurrences in file:
- Line 552: Featured fetch — properly handled ✓
- Line 643: Campaigns fetch — properly handled ✓

**Other async operations:**
- Line 527: `toggleSaved` — has try/catch, shows toast on error (line 532) ✓
- Line 471: `handleInvite` — has try/catch, shows toast on error (line 490) ✓

**NO OTHER SILENT-CATCH PATTERNS FOUND** ✓

---

## Code Quality Checks

### TypeScript
- ✅ No `any` types introduced
- ✅ All error states properly typed (`string | null`)
- ✅ Callbacks properly memoized with `useCallback`

### Error Handling
- ✅ All fetch failures logged to console
- ✅ All error messages user-friendly
- ✅ `ApiError` instances properly checked with `instanceof`
- ✅ Generic fallback messages for non-`ApiError` exceptions

### UX
- ✅ Error states visually distinct (dashed borders, AlertCircle icons)
- ✅ Retry buttons clearly labeled and functional
- ✅ Loading states prevent error/success overlap
- ✅ Demo mode experience unchanged

### Accessibility
- ✅ Error messages have semantic meaning (not just color)
- ✅ Retry buttons keyboard-accessible (Button component)
- ✅ AlertCircle icon provides visual redundancy

---

## Verdict: ✅ **PASS**

**All checks passed.** The fix correctly addresses P0-#3:
1. ✅ Featured creators section now distinguishes fetch failure from empty results
2. ✅ Campaigns dropdown now shows retry UI on fetch failure
3. ✅ Main creator search error handling NOT regressed
4. ✅ All retry buttons correctly wired and functional
5. ✅ Success/error JSX conditions mutually exclusive
6. ✅ Demo mode fully functional
7. ✅ No other silent-failure patterns detected

**Ready for Meera's local verification.**

---

## Next Steps
1. Meera: Run `npm run build` to confirm TypeScript clean
2. Meera: Run `npm run dev` and test:
   - Toggle `VITE_API_MODE=demo` → verify featured sections render
   - Toggle `VITE_API_MODE=live` → kill backend → verify featured error notice + retry works
   - Test invite dialog campaign fetch failure → verify error hint + retry works
3. Mark P0-#3 as **VERIFIED** in `BRAND_ADMIN_PENDING_WORK.md`
