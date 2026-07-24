# Open Defects — Remediation Plan
**Author:** Priya (CTO) · **Date:** 2026-07-24
**Scope:** the 5 open items remaining from the creator-flow live QA (after the 4 already deployed).

Grounded in code — file:line references below are verified.

---

## The 5 open items, triaged

| # | Item | Fix location | Effort | Risk | Blocker |
|---|------|--------------|--------|------|---------|
| A1 | Public-page `influora.com` label | FE only | ~30 min | Low | none |
| A2 | Coupon `redirectUrl` (C30) | BE (+FE consume) | ~0.5 day | Low | none |
| B1 | Counter-form fee 10% → 15% | FE only | ~30 min | Med | **finance sign-off** |
| C1 | Deal-room submit/counter stubs (C19) | FE only | ~1 day | Med | needs an in-progress deal to E2E-test |
| C2 | Tax identity (C32) | BE + FE | ~1 day | Med | migration + validation review |
| — | Co-pilot post-connect ideas (C8) | none | — | — | **not a bug** — needs a real IG connect |

---

## Batch A — quick wins (bundle into one deploy)

### A1 · Public-page URL label → derive from origin
- **Root cause:** hardcoded `influora.com/@handle` **display text** while the real domain is `influora.in` and the deployed host is `200.141.1.6`. The actual links are relative (`/@handle`) and work; only the shown/copied text is wrong.
- **Files:** `creator-dashboard.tsx:248` + `:186`, `creator-portfolio-public.tsx:499`, `creator-portfolio-editor.tsx:122` (fallback `'https://influora.com'`).
- **Fix:** show `window.location.origin + /@handle` (or a single `PUBLIC_BASE_URL` const) everywhere, matching what the portfolio editor already computes. Always correct + clickable regardless of domain.
- **No decision needed. No backend.**

### A2 · Coupon `redirectUrl` (C30)
- **Root cause:** backend returns only the raw `trackingUrl`; it does not yet build the `/track/click/{utmCampaignId}` redirect URL, so `redirectUrl` is always `undefined` (`api.ts:3132–3142`). Clicks via the raw URL aren't counted.
- **Fix:** backend adds the `redirectUrl` (built from config, never hardcoded) to the coupon DTO, and — if not already present — a `/track/click/{id}` redirect endpoint that 302s to the destination and increments the click counter. FE already has the optional field wired.
- **Files:** `CreatorCouponController` / coupon service + DTO; possibly a new `ClickTrackingController`.

**→ Batch A ships together: FE label + coupon backend. One image build + stack update.**

---

## Batch B — decision-gated

### B1 · Counter-form earnings 10% vs real 15%
- **Root cause:** `counter-proposal-form.tsx:56` hardcodes `platformFee = amount * 0.1` (10%) + 18% GST-on-fee + 10% TDS. The live platform fee is **15% flat** (`/creator/platform-fee` → `feeBps 1500`).
- **Blocker:** I won't change tax math without confirmation. **Need finance to confirm the correct creator-facing breakdown:** is it a flat 15% platform fee only, or 15% + GST-on-fee + TDS? The real escrow-release path deducts 15% platform fee and handles GST via invoices — so the counter form's TDS/GST lines may be wrong entirely.
- **Once confirmed:** ~30 min FE edit to match the real formula (ideally read the rate live from `/creator/platform-fee` instead of hardcoding).

---

## Batch C — real feature work (each its own PR + QA/E2E)

### C1 · Deal-room submit/counter/shipping/receipt (C19) — highest user value
- **Root cause:** `creator-chat.tsx` handlers are `setTimeout` stubs with `// In production:` comments — `handleSubmitDeliverableForm` (L836), `handleSubmitCounterForm` (L794), `handleConfirmReceipt` (L754), `handleStartRevision` (L855), accept/decline (L781/785).
- **Good news:** the real endpoints already exist in the api client — `creatorDeliverables.upload` + `.submit` (`api.ts:1691`, `:3350+`), `api.deals.accept/counter`. So this is **pure FE wiring, no backend.**
- **Nuance:** deliverable submit is two-step — `upload` (multipart) **then** `submit`. The form must upload files first, then call submit.
- **Effort:** ~1 day FE + QA. **Prerequisite for full E2E:** a deal in `IN_PROGRESS` (escrow funded). The demo deal is `CONTRACTED` (escrow not funded), so testing submit end-to-end needs the brand to fund escrow first.

### C2 · Tax identity (C32)
- **Root cause:** `creatorTaxIdentity.submit` always rejects `NOT_IMPLEMENTED` (`api.ts:2427`); no backend endpoint. Tax plumbing exists (`GstSplitUtil`, `CompanyTaxProperties`, `CreatorProfile` entity) but no creator GSTIN/PAN capture.
- **Fix:** new BE endpoint (e.g. `POST /me/tax-identity`) persisting GSTIN/PAN on `CreatorProfile` (likely a small migration for the columns + format validation), then point the FE hook at it (`useCreatorTaxIdentity.ts`).
- **Effort:** ~1 day (BE persistence + validation + FE wire) + QA. Needs a migration review.

---

## Recommended order
1. **Batch A now** (label + coupon redirect) — low risk, no decisions, one deploy.
2. **B1** the moment finance confirms the fee formula — trivial add-on to a deploy.
3. **C1 (C19)** — biggest creator-facing value (lets a creator actually submit work); schedule alongside funding an escrow on the demo deal so it's E2E-testable.
4. **C2 (C32)** — tax identity, own PR with migration review.
5. **C8** — no code work; just connect a real IG business account when ready.

**No new dependencies anywhere. A1/A2/B1 are same-day; C1/C2 are ~1 day each with QA/E2E.**
