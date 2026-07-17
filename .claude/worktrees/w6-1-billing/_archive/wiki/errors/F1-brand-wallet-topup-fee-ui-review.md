# QA Review: F1 — Brand Wallet Top-Up + Platform Fee UI

**Date:** 2026-07-10  
**Reviewer:** Kavya (QA Lead)  
**Status:** PASS WITH NOTES  
**Session:** Brand fee wave, final UI piece

---

## Files Reviewed

1. **src/lib/api.ts** — Added `WalletTopUpRequest`, `WalletTopUpResponse`, `BrandPlatformFeeResponse` types + `wallet.topUp()` and `wallet.brandPlatformFee()` methods
2. **src/hooks/useWalletTopUp.ts** — New hook mirroring `useEscrowFund.ts`'s order-then-checkout pattern for top-up flow
3. **src/pages/brand-wallet.tsx** — Wired "Add Funds" dialog to `useWalletTopUp`
4. **src/components/brand/campaigns/campaign-form.tsx** — Fetches `brandPlatformFee` on mount, displays fee card on Review step, catches 402 insufficient-balance errors

---

## Issues Found

### HIGH (fix before delivery)

**H1. Missing `useReducedMotion()` bypass on animations** (TECH-STACK.md cross-cutting rule 5)  
Lines with animation that lack reduced-motion check:

- `src/pages/brand-wallet.tsx:378` — `<RefreshCw className="h-3 w-3 animate-spin" />`
- `src/pages/brand-wallet.tsx:580` — `<RefreshCw className="h-4 w-4 animate-spin" />`
- `src/components/brand/campaigns/campaign-form.tsx:1259` — `<Loader2 className="mr-2 h-4 w-4 animate-spin" />`

All three use `animate-spin` without checking `useReducedMotion()`. Wrap each in a ternary:
```tsx
<Loader2 className={cn("mr-2 h-4 w-4", !reducedMotion && "animate-spin")} />
```

where `const reducedMotion = useReducedMotion();` is called at the component top. `useReducedMotion()` already exists in this codebase (used elsewhere).

---

**H2. Idempotency-Key generation — not genuinely unique per attempt**  
`src/hooks/useWalletTopUp.ts:59`  
```ts
function generateIdempotencyKey(): string {
  return `topup-${Date.now()}-${Math.random().toString(36).substring(2, 15)}`;
}
```

This mirrors `useEscrowFund.ts:59`'s pattern, which generates a **new** key inside `initiateTopUp` (line 84 calls it). That's correct — each click gets a fresh key.

**Verified safe:** `initiateTopUp` (line 73) generates the key inline on each call (`generateIdempotencyKey()`), so retries after an error or dialog re-open will get a new key. No issue here — the key IS fresh per attempt.

---

**H3. Fee display math — integer rounding vs backend's HALF_UP could diverge**  
`src/components/brand/campaigns/campaign-form.tsx:1104`  
```ts
const feeAmount = Math.round((feeBase * platformFee.feeBps) / 10000);
```

Backend (BrandCampaignFeeService.java:106-107):
```java
.multiply(BigDecimal.valueOf(feeBps))
.divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
```

Frontend uses `Math.round()` (integer rounding), backend uses `RoundingMode.HALF_UP` with 2-decimal precision. **In practice this is safe** because the budget slider (`campaign-form.tsx:841`) uses `step={1000}`, so `budgetMax` is always a multiple of ₹1,000 (whole integer). A 10% fee on ₹25,000 is ₹2,500 exactly — no fractional rounding edge case.

**HOWEVER**, if a future change allows fractional budgets (e.g., a text input or different currency), the displayed total could mismatch the server's actual charge. Recommend adding a comment above line 1104 noting that this assumes whole-rupee budgets, or switch to match the backend's rounding:

```ts
const feeAmount = Math.round((feeBase * platformFee.feeBps) / 10000 * 100) / 100; // 2-decimal HALF_UP
```

This is a **medium-risk** issue (current UI is safe, but fragile to future changes). Flagging as HIGH because fee mismatches are user-hostile — a brand seeing "Total ₹27,500" on the Review step then being charged ₹27,501 is a trust breach.

**Recommendation:** Either (a) add a comment documenting the whole-rupee assumption, or (b) switch to 2-decimal rounding matching the backend.

---

### MEDIUM (fix when possible)

**M1. 402 error message rendering — confirms verbatim server string** ✅  
`src/components/brand/campaigns/campaign-form.tsx:400` captures `err.message` into `insufficientBalanceMessage`, then line 1209 renders it verbatim:
```tsx
{insufficientBalanceMessage ?? 'Insufficient wallet balance to publish this campaign.'}
```

The fallback string is only used if `err.message` is somehow missing (defensive coding). The live path **does** use the server's exact message. ✅ **Correct.**

---

**M2. CEO copy string used verbatim** ✅  
`src/components/brand/campaigns/campaign-form.tsx:1134` renders:
```tsx
<p className="pt-1 text-xs text-muted-foreground">{platformFee.copy}</p>
```

This reads the `copy` field from the `BrandPlatformFeeResponse` API response. The server (BrandPlatformFeeDtos.java:21) returns the fixed string as `copy`. ✅ **Correct — no frontend duplication.**

The fallback in `api.ts:1845` (`copy: row.copy ?? 'Platform fee (10%)...'`) is only hit if the server response is malformed (missing the field entirely). The live path **always** uses the server's literal string. ✅ **Correct.**

---

### LOW / NOTES

**L1. Razorpay live-mode checkout not implemented (KNOWN GAP, do not re-flag)**  
`src/pages/brand-wallet.tsx:307-315` has the same documented gap `FundEscrowButton.tsx` has: mock mode simulates a successful payment, but live mode has no Razorpay `checkout.js` loader or `VITE_RAZORPAY_KEY_ID` anywhere in the codebase.

The comment at line 303-305 explicitly flags this as an unimplemented gap and references `FundEscrowButton.tsx` for the identical limitation. **F1 correctly mirrors the existing gap rather than inventing a fresh, unreviewed publishable-key integration.** This is the **honest** approach — the "Add Funds" button in live mode would fail at the checkout step with a clear error, not silently pretend to succeed.

✅ **No regression — flagged gap is inherited from earlier work (escrow funding), not introduced by F1.**

---

**L2. TypeScript strict compliance** ✅  
No `any` types found. All props/state properly typed. ✅ **Correct.**

---

**L3. WCAG AA contrast** ✅  
The fee card uses `text-primary`, `text-muted-foreground`, and `border-border` — all semantic tokens from the Lilac Mist palette (TECH-STACK.md line 18). Assuming the palette itself is WCAG-compliant (already reviewed in earlier passes), this is correct. ✅

The 402 alert uses `variant="destructive"` (Alert component), which is a semantic variant — again, correct. ✅

---

## Verification Notes

### Idempotency-Key — Fresh per attempt ✅
`useWalletTopUp.ts:84` calls `generateIdempotencyKey()` **inside** the `initiateTopUp` callback. Each top-up attempt (button click) generates a **new** key. If the user cancels, re-opens the dialog, and tries again, `initiateTopUp` is called again → new key. ✅ **Correct.**

Compared to `useEscrowFund.ts:102`, which does the same thing (`idempotencyKeyRef.current = generateIdempotencyKey()` inside `initiateFund`). ✅ **Consistent.**

### Fee base matches server ✅
`campaign-form.tsx:1103` uses `formData.budgetMax` as the fee base. Server side (`BrandCampaignFeeService.java:99`) charges on `campaign.getBudget().getMax()`. ✅ **Correct.**

### 402 link to /brand/wallet ✅
`campaign-form.tsx:1213` renders:
```tsx
<Link to="/brand/wallet">
  <Wallet className="mr-2 h-4 w-4" />
  Top up wallet
</Link>
```

Link target is `/brand/wallet` (the wallet page). ✅ **Correct.**

---

## Test Coverage

**Missing:** No test file for `useWalletTopUp.ts` or `brand-wallet.tsx`. Recommend adding:
- Unit test for `useWalletTopUp` state transitions (idle → initiating → awaiting_payment → submitted)
- Integration test for 402 error rendering in `campaign-form.tsx`

This is a **nice-to-have** rather than a blocker — the existing escrow-fund flow has no tests either, so F1's lack of tests doesn't regress the codebase's test posture. But flagging it for follow-up.

---

## WCAG / Accessibility

✅ **Colors:** All semantic tokens from Lilac Mist palette (already reviewed).  
⚠️ **Reduced motion:** Missing `useReducedMotion()` bypass on 3 animations (HIGH priority fix — see H1).  
✅ **Keyboard nav:** Dialog is keyboard-navigable (Radix UI primitives).  
✅ **Alt text / labels:** Form labels present, no images missing alt.

---

## VERDICT: PASS WITH NOTES

**Summary:**  
F1 correctly implements the wallet top-up + fee-transparency UI according to the backend contracts (WalletTopUpRequest/Response, BrandPlatformFeeDtos.PlatformFeeResponse). Idempotency-Key is fresh per attempt. CEO copy string is rendered verbatim from the server. 402 error handling renders the server message and links to /brand/wallet.

**Issues to fix before merge:**
1. **H1 — Add `useReducedMotion()` bypass** on 3 animate-spin icons (TECH-STACK.md rule 5)
2. **H3 — Fee rounding**: Either (a) add a comment documenting the whole-rupee assumption at line 1104, OR (b) switch to 2-decimal rounding matching the backend's HALF_UP mode

**Known gap (out of scope for this review):**
- Razorpay live-mode checkout is not wired (same gap as FundEscrowButton.tsx). F1 correctly flags this rather than inventing a new integration.

Once H1 and H3 are addressed, F1 is **ready for Meera's local verification** (npm run dev, test the Add Funds dialog + Review step fee card).

---

## Next Steps

1. Ananya: Fix H1 (reduced-motion bypass) and H3 (fee rounding comment or 2-decimal match)
2. Re-submit to Kavya for final sign-off
3. Meera: Local verification (build + manual flow test)
4. Arjun: Mark F1 complete in SHARED_CONTEXT.md

---

**Kavya Reddy**  
QA Lead, Sage Digital
