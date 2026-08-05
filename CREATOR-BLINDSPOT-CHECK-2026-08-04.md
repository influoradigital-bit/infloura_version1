# Influora Creator — Blind-Spot Check (vs PROJECT-DEEP-AUDIT-2026-08-04.md)

**Date:** 2026-08-04
**Scope:** Creator domain **only**. This report re-checks every Creator claim in
`PROJECT-DEEP-AUDIT-2026-08-04.md` against code truth, using the same method the Brand
blind-spot audit used — deterministic **call-site reachability** greps — to find the class
of error that audit missed for Brand: a route + typed wrapper that exist but that **no
creator UI ever calls** (a WORKING row that is actually unreachable).
**Method:** Precise api-wrapper extraction from `src/lib/api.ts` (122 real method
signatures) → zero-caller sweep across all of `src/**` (excluding the definition file and
tests) → per-claim source reads. Every finding re-verified by grep. Nothing run live.
**Verdict ceiling:** BELIEVED (static analysis; deterministic oracle greps, no live HTTP).

---

## 1. Bottom line — the audit's Creator section is accurate

The Brand blind-spot audit found **7 WORKING rows mislabeled** (route + wrapper exist, zero
UI callers). Running the identical zero-caller oracle over the **Creator** surface finds
**no such mislabels**: the only two creator-domain orphan wrappers
(`submitCreatorKyc`, `saveCreatorPayout`) are **already honestly labeled MISSING** in the
deep audit. Every specific Creator claim in the `.md` reproduces against code.

| Deep-audit Creator claim | Where in `.md` | Code-truth verdict |
|---|---|---|
| Deal-room shipment `items`/`estimatedDelivery` are demo placeholders even in live | §4 Partial | ✅ **CORRECT** |
| Creator KYC — `submitCreatorKyc` exists (`api.ts:1036`) but never called; no KYC UI | §5 Missing | ✅ **CORRECT** |
| Creator payout — `saveCreatorPayout` (`api.ts:1047`) never invoked | §5 Missing | ✅ **CORRECT** |
| Portfolio-public `toFixed` crash FIXED (null-guarded, `:446,761`) | §6 Bugs | ✅ **CORRECT** |
| Creator = 0 broken rows | §1 tally | ✅ **CORRECT** (zero-caller sweep finds no mislabeled-WORKING creator row) |

**Creator tally stands: 46 ✅ / 1 🟡 / 0 🔴 / 10 ⬜.** No correction needed — unlike Brand
(which the blind-spot pass corrected 53→47 working).

---

## 2. Evidence, claim by claim

### C-1 · §4 Partial — deal-room shipment placeholder ✅ CORRECT
`src/pages/creator-chat.tsx` carries an explicit code comment: *"Priya's Shipment entity
has no `items`/`estimatedDelivery` fields at all … those two stay demo placeholders in both
modes."* `shipmentDisplay` (~L1137) hardcodes `items: [{ name: liveShipment?.productName …
quantity: 1 }]` and the ShipmentCard render (~L2302) repeats the comment. The audit's
substance is right; its line cites (`1135,2302`) drift ~2 lines — negligible.

### C-2 · §5 Missing — Creator KYC ✅ CORRECT
`submitCreatorKyc` is defined at `api.ts:1036`. The **only** other reference in `src/` is a
doc-comment at `creator-onboarding.tsx:35` (`… → first withdrawal (api.onboarding.submitCreatorKyc)`)
— **not a call**. Zero call-sites. No KYC capture UI. Audit label MISSING is correct.

### C-3 · §5 Missing — Creator onboarding payout method ✅ CORRECT
`saveCreatorPayout` defined at `api.ts:1047`. Only other reference is a doc-comment at
`creator-onboarding.tsx:36`. Zero call-sites. Audit label MISSING is correct.

### C-4 · §6 Bugs — portfolio `toFixed` crash FIXED ✅ CORRECT
`creator-portfolio-public.tsx:446` → `page.stats.avgRating != null ? …toFixed(1) : '—'`;
`:761` → `stats.engagementRate != null ? …toFixed(1)% : '—'`. Both null-guarded. FIXED is
correct.

### C-5 · Blind-spot sweep — no mislabeled-WORKING creator rows ✅ CORRECT
Zero-caller oracle over 122 extracted api wrappers → 8 orphans:
`cancelSubscription`, `checkSlug`, `initiateCheckout`, `releasePayout` (all **Brand**,
already F-0070/71/73/69), `targetAudience` + `onMessage` (false positives — DTO field
access + SSE callback type, not endpoints), and `submitCreatorKyc` + `saveCreatorPayout`
(**Creator, already MISSING**). `releasePayout` self-documents as *"brand releases a funded
milestone's escrow to the creator"* → brand-domain, out of scope. **No creator WORKING row
is unreachable.**

---

## 3. Only nit found in the Creator section

- **Line drift:** §4 shipment cite `creator-chat.tsx:1135,2302` → actual placeholder code
  at ~L1137 / ~L2302. Substance correct; ~2-line drift, not a defect.

## 4. What this check could NOT see (law 5)

- **No live HTTP.** Call-site reachability is proven by grep; runtime responses are not.
- Creator orphan-route names in §5 (`deliverable metrics/status/proof/mark-posted`,
  `/creator/reviews/{id}/flag`, `/creator/analytics/me/media`) were spot-checked as
  zero-caller but not each mapped to its exact wrapper name — they are labeled
  orphan/MISSING (conservative direction), so no WORKING→broken risk regardless.
- Backend service *logic* behind verified routes was not re-audited line-by-line.
- `TaxIdentityForm` **is** rendered (`creator-settings.tsx:450`); the audit makes no
  contrary claim, so it is not a blind spot — noted only for completeness.

---

*Produced under proof-os task `creator-blindspots-0804`. Oracle: deterministic call-site
greps over `src/**`. No agents (CHECK/AUDIT verb). Verdict ceiling: BELIEVED — static, no
live HTTP exercised.*
