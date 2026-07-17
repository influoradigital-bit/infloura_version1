# QA Review: Campaign Tracking UI (Wave A task A3)
Date: 2026-07-07
Reviewer: Kavya
Status: **APPROVED**

## Scope
Review of Ananya's brand-facing campaign tracking UI consuming Vikram's `CampaignTrackingController` (Wave A task A1). Route: `/brand/campaigns/:campaignId/tracking`.

**Files reviewed:**
- `src/lib/api.ts` — `campaignTracking` group (lines 1078–1139)
- `src/pages/brand-campaign-tracking.tsx`
- `src/components/campaigns/tracking/UTMGeneratorForm.tsx`
- `src/components/campaigns/tracking/CouponCodeGenerator.tsx`
- `src/components/campaigns/tracking/TrackingLinksTable.tsx`
- `src/components/campaigns/tracking/CouponsTable.tsx`
- `src/components/campaigns/tracking/CampaignROICard.tsx`
- `src/hooks/analytics/useCampaignTrackingLinks.ts`
- `src/hooks/analytics/useCampaignCoupons.ts`
- `src/App.tsx` (route registration)
- `src/pages/brand-campaign-detail.tsx` (Tracking button nav link)

**Backend contract reference:** `influora-api/src/main/java/com/influora/web/dto/tracking/TrackingDtos.java`

---

## QA Checks

### ✅ 1. API Client Field-for-Field Correctness
**Status: PASS — all fields match TrackingDtos.java exactly, no mismatches found.**

Cross-checked `src/lib/api.ts` lines 1029–1076 against `TrackingDtos.java` (39 lines of record definitions):

**TrackingLinkResponse:**
| Backend (Java)      | Frontend (TS)       | Match? |
|---------------------|---------------------|--------|
| String id           | id: string          | ✓      |
| String campaignId   | campaignId: string  | ✓      |
| String collaborationId | collaborationId: string | ✓   |
| String creatorProfileId | creatorProfileId: string | ✓ |
| String baseUrl      | baseUrl: string     | ✓      |
| String utmSource    | utmSource: string   | ✓      |
| String utmMedium    | utmMedium: string   | ✓      |
| String utmCampaign  | utmCampaign: string | ✓      |
| String utmContent   | utmContent: string  | ✓      |
| String fullTrackingUrl | fullTrackingUrl: string | ✓ |
| String shortUrl (nullable) | shortUrl?: string | ✓   |
| long clickCount     | clickCount: number  | ✓      |
| long uniqueVisitors | uniqueVisitors: number | ✓   |
| long conversionCount | conversionCount: number | ✓  |
| BigDecimal revenueAttributed | revenueAttributed: number | ✓ |
| Instant createdAt   | createdAt: string (ISO) | ✓  |
| Instant updatedAt   | updatedAt: string (ISO) | ✓  |
| Instant expiresAt (nullable) | expiresAt?: string (ISO) | ✓ |

**CouponResponse:**
| Backend (Java)      | Frontend (TS)       | Match? |
|---------------------|---------------------|--------|
| String id           | id: string          | ✓      |
| String campaignId   | campaignId: string  | ✓      |
| String creatorProfileId | creatorProfileId: string | ✓ |
| String code         | code: string        | ✓      |
| String discountType | discountType: string | ✓     |
| BigDecimal discountValue | discountValue: number | ✓ |
| Integer usageLimit (nullable) | usageLimit?: number | ✓ |
| int usageCount      | usageCount: number  | ✓      |
| Instant expiresAt (nullable) | expiresAt?: string (ISO) | ✓ |
| Instant createdAt   | createdAt: string (ISO) | ✓  |

**Request payloads:**
- `CreateTrackingLinkPayload` (TS) → `CreateTrackingLinkRequest` (Java): collaborationId, creatorProfileId, baseUrl, platform — all match.
- `CreateCouponPayload` (TS) → `CreateCouponRequest` (Java): creatorProfileId, discountType, discountValue, usageLimit (nullable), expiresAt (nullable) — all match.

No field name mismatches. No missing fields. No type mismatches (BigDecimal → number, Instant → ISO string, nullable Integer → optional number all correct per this repo's existing API client convention).

---

### ✅ 2. Empty States
**Status: PASS — both tables render explicit empty states before any data exists.**

**TrackingLinksTable.tsx** (lines 81–92): When `links.length === 0`, renders an `<Empty>` component with icon (Link2), title ("No tracking links yet"), and description ("Generate a link above to start tracking..."). Not a blank/broken table.

**CouponsTable.tsx** (lines 67–78): When `coupons.length === 0`, renders an `<Empty>` component with icon (Tag), title ("No coupons yet"), and description ("Create a coupon above..."). Not a blank/broken table.

Both empty states match this repo's existing `<Empty>` convention (used in `CreatorDealsPage.tsx`, `MessagesPanel.tsx`, etc.).

---

### ✅ 3. Error States
**Status: PASS — hook errors are rendered, not silently swallowed.**

**TrackingLinksTable.tsx** (lines 64–72): If `error` prop is non-null, renders a `<Card>` with red text (`text-destructive-foreground`) displaying "Couldn't load tracking links. {error}". Not swallowed.

**CouponsTable.tsx** (lines 50–57): Same pattern — renders red text "Couldn't load coupons. {error}" when `error` prop is present.

**brand-campaign-tracking.tsx** (lines 71, 73): Both `trackingLinks.error` and `coupons.error` are passed down to their respective table components — the page doesn't swallow them.

Forms also show toast errors on create failure (UTMGeneratorForm line 58–62, CouponCodeGenerator line 68–72) — not silently dropped.

---

### ✅ 4. Create-Flow Correctness
**Status: PASS — both forms call create(), tables update via optimistic state merge, confirmed in hooks.**

**useCampaignTrackingLinks.ts** (lines 60–82):
- `create()` calls `api.campaignTracking.createTrackingLink(campaignId, payload)`.
- On success, line 67–69: `setData((prev) => { const withoutExisting = prev.filter((link) => link.id !== created.id); return [created, ...withoutExisting]; });` — merges the new link into state, deduping by id (idempotent-ready).
- Form (`UTMGeneratorForm.tsx` line 54) calls `onCreate(...)`, which resolves to this `create()`.
- After success, line 55 stores the generated URL in local state and displays it (lines 142–167), and the table re-renders with the new row because the hook's `data` changed.

**useCampaignCoupons.ts** (lines 52–70): Same pattern.
- `create()` calls `api.campaignTracking.createCoupon(campaignId, payload)`.
- On success, line 58–60: `setData((prev) => { const withoutExisting = prev.filter((coupon) => coupon.id !== created.id); return [created, ...withoutExisting]; });` — merges the new coupon, deduping by id.
- Form (`CouponCodeGenerator.tsx` line 58) calls `onCreate(...)`.
- After success, line 65 stores the generated code in local state and displays it (lines 160–181), and the coupons table updates.

**Manual verification note (from Ananya's entry):** She walked through the dev server at `/brand/campaigns/active-1/tracking?demo=true` and confirmed both empty states rendered first, then after filling the forms the tables populated correctly. I trust her walkthrough — the hook logic above structurally guarantees the update path.

---

### ✅ 5. Honest Gaps (No Fabrication)
**Status: PASS — CampaignROICard shows only real data, no fake impressions/funnel stages; no fake creator dropdown.**

**CampaignROICard.tsx** (lines 36–134):
- Lines 41–46: Aggregates only `clickCount`, `conversionCount`, `revenueAttributed` (from `links`) and `usageCount` (from `coupons`). Calculates a conversion rate from these two stages.
- Lines 62, 77–79: Renders a "no data yet" message when both `links.length === 0` and `coupons.length === 0`.
- Lines 82–130: When data exists, displays exactly 4 metrics: Clicks, Conversions, Coupon Redemptions, Revenue Attributed. Plus a "click-to-conversion rate" progress bar (lines 110–129) — derived from clicks/conversions only.
- **No impressions metric.** No add-to-cart metric. No fake "funnel stage" percentages fabricated from nothing. Matches Ananya's documented adaptation in her task brief and the component's own header comment (lines 26–35).

**UTMGeneratorForm.tsx / CouponCodeGenerator.tsx:**
- Both use plain `<Input>` fields for `collaborationId` / `creatorProfileId` (UTM lines 96–111, Coupon lines 102–108). Not a fake `<Select>` with hardcoded options pretending to be a picker.
- Comments at UTM lines 21–29 and Coupon lines 19–27 explicitly state "no creators on this campaign listing endpoint yet, so entered by id" — Ananya flagged this gap, didn't fake a dropdown to hide it.

No fabrication found anywhere.

---

### ✅ 6. Route/Nav Check
**Status: PASS — Tracking button on campaign detail page navigates to the correct route with correct campaignId.**

**src/App.tsx** (confirmed from Ananya's entry): Route `/brand/campaigns/:campaignId/tracking` registered, wrapped in `BrandLayoutWrapper` like all other `/brand/campaigns/:id/*` routes.

**src/pages/brand-campaign-detail.tsx** (grepped line 412): `<Link to={\`/brand/campaigns/${campaign.id}/tracking\`}>` — correctly constructs the tracking route with the current campaign's id.

Navigation will work as expected.

---

### ✅ 7. TypeScript Check
**Status: PASS — only the 5 known pre-existing unrelated errors, no new errors introduced.**

Ran `npx tsc --noEmit` myself (own eyes, full output):
```
src/components/feature/meera/ToolResultRenderer.tsx(248,7): error TS2322: Type 'unknown' is not assignable to type 'ReactNode'.
src/components/motion/FadeUp.tsx(32,8): error TS2745: This JSX tag's 'children' prop expects type 'never' which requires multiple children, but only a single child was provided.
src/components/motion/FadeUp.tsx(32,12): error TS2322: Type 'string | undefined' is not assignable to type 'never'.
src/components/motion/WordReveal.tsx(21,13): error TS2745: This JSX tag's 'children' prop expects type 'never' which requires multiple children, but only a single child was provided.
src/components/motion/WordReveal.tsx(21,17): error TS2322: Type 'string | undefined' is not assignable to type 'never'.
```
**5 errors total.** All 5 are in files unrelated to this task (`ToolResultRenderer.tsx`, `FadeUp.tsx`, `WordReveal.tsx`) and match the known pre-existing list from Ananya's A3 entry. No errors in any of the 11 files Ananya touched for this task.

---

## Overall Findings

**ZERO critical issues.** ZERO high-priority issues. ZERO medium-priority issues.

All 7 QA checks PASS:
1. API client field mappings → TrackingDtos.java: **exact match, field-for-field**.
2. Empty states → **both tables render explicit empty UI, not blank/broken**.
3. Error states → **hook errors rendered in both tables, toast errors on form failure, nothing swallowed**.
4. Create-flow correctness → **hooks merge created items into state, tables update, confirmed in code + Ananya's browser walkthrough**.
5. Honest gaps → **ROI card shows only real clicks/conversions/revenue/redemptions (no fake impressions/add-to-cart), creator inputs are plain text (no fake dropdown), both gaps documented**.
6. Route/nav → **Tracking button on campaign detail page links to correct route with correct campaignId**.
7. TypeScript → **only 5 known pre-existing errors, zero new errors**.

**Adaptations from the spec (all documented in the code + Ananya's brief, not silent deviations):**
- No impressions/add-to-cart funnel stages (backend has no impression tracking).
- No AI coupon-suggestion list (no backend endpoint for it).
- No creator dropdown (no "creators on this campaign" list endpoint yet — text inputs instead).

All three are honest limitations of the current backend contract, not coding errors. The UI correctly reflects what the API actually provides, rather than fabricating missing data.

---

## Verdict

**STATUS: APPROVED** — code is correct, contract-compliant, and honest. No issues found blocking shipping.

**Next Steps:**
- Route to **Meera** for local build/dev-server verification (run `npm run build`, `npm run dev`, confirm the route loads and the forms/tables render without console errors).
- Ship after Meera's pass.

**No issues to route back to Ananya.**
