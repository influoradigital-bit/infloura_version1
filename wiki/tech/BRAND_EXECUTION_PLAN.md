# Brand Track — Corrected Pending Tasks + Execution Plan

> **Author:** Priya (CTO) — full-codebase audit
> **For:** Swapnil (CEO), Arjun (orchestrator), Vikram, Ananya, Kavya, Meera
> **Date:** 2026-07-09
> **Supersedes:** the BRAND section of `PENDING_TASKS_REPORT.md` (that report is materially stale)

---

## ⚠️ The report I handed Swapnil was stale. Correcting the record.

`PENDING_TASKS_REPORT.md` flagged Reviews and Disputes as **"❌ MISSING, no spec, 0%, legal liability."** A direct file read shows **both are built and wired.** I audited from the spec/task list again instead of the filesystem — same mistake I made on the creator estimate. Corrected below, verified by file.

| Report claim | Actual state (verified) | Evidence |
|---|---|---|
| `Review` entity MISSING / no spec / P0 blocked on policy | **BUILT** | `V43__reviews.sql`, `Review.java`, `ReviewService`, `BrandReviewController`, `CreatorReviewController`, `ReviewerType` |
| `brand-reviews` page MISSING (P0) | **BUILT** (POST live) | `src/pages/brand-reviews.tsx` → `CollaborationReviewsPanel` |
| `Dispute` entity MISSING / legal liability / P0 blocked on policy | **BUILT + escrow-integrated** | `V45__disputes.sql`, `Dispute.java`, `DisputeService`, `AdminDisputeController`, `DealController POST /deals/{id}/disputes`, `EscrowService.freezeUnreleasedForDispute` + `assertEscrowNotBlockedByDispute` |
| `PlatformFeeConfig` MISSING | **BUILT** (brand+creator bps, admin CRUD, optimistic lock) | `V41/V42/V44`, `PlatformFeeConfig.java`, `PlatformFeeAdminController` |
| Creator platform-fee transparency endpoint "Not started" | **BUILT** | `CreatorPlatformFeeService`, `CreatorPlatformFeeController` |

**Consequence for Swapnil:** the "What I Need From You" list in the old report (dispute policy, review policy) is **already resolved** — §1.2 (reviews) and §1.3 (disputes) rulings are implemented in code. Fee % is approved (10% brand / 15% creator). **No policy decision blocks brand work today.**

---

## ✅ The ONE real P0 that remains: brand fee is configured but never charged

`brandFeeBps` (=1000, 10%, admin-approved) exists in `platform_fee_config` and is editable in the admin panel — but **no code deducts it.** `EscrowService.confirmFunded()` moves the full amount brand→clearing wallet with zero platform-fee leg. The creator side is correctly charged at release (`PlatformFeeService.deductAtRelease`); **the brand leg was left out** — confirmed by grep: `brandFeeBps` appears only in admin-config surfaces, never in the escrow/ledger path.

**This gates 100% of brand-side fee revenue.** It is unblocked except for one design decision (below).

---

## 🔒 LOCKED — Leadership ruling 2026-07-09 (CTO/CEO/CFO/COO panel)

Supersedes the "Decision required" section below. Panel converged unanimously.

1. **Fee trigger:** charge 10% **atomically at the campaign DRAFT/PENDING→ACTIVE ("publish") transition** — same DB transaction as the status flip (CFO hard constraint: not a follow-up job). No upfront charge, no fee-refund path. If a campaign never activates, no fee.
2. **Fee-on-top:** brand pays **budget + 10%**; creator receives the full budget. Platform revenue sits on top.
3. **Money flow (NEW true P0 — "B0"):** brand tops up wallet via **Razorpay payment link → brand wallet (booked as customer-deposit LIABILITY, not revenue) → escrow + fee draw from wallet.** Build the wallet top-up endpoint; it does not exist today.
4. **Flat 10%, no volume tiers.** B6 dropped (not deferred).
5. **Brand-facing copy:** "Platform fee (10%) — charged only when your campaign goes live."
6. **CFO constraints the build MUST honor:** no GST/invoice trigger at top-up; GST/TDS attach only at the ACTIVE fee-cut; capture PAN/GSTIN at top-up for TDS reconciliation; enforce a 3-way ledger match (Razorpay settlement → wallet credit → escrow debit/fee cut); gateway-fee logging (B5) ships in the revenue wave.
7. **COO gate (non-negotiable):** Kabir OWASP PASS on the wallet foundation (B0) before ANY fee-cut code (B1) ships.

### Revised backlog (money path)
| # | Task | Priority | Owner | Gate |
|---|---|---|---|---|
| B0 | Brand wallet top-up: `POST /wallet/topup` → Razorpay order/link; webhook credits wallet via `WalletLedgerService` (liability booking, idempotent per order id, PAN/GSTIN capture) | **P0 (foundation)** | Vikram | Kavya → **Kabir PASS** → Meera |
| B1 | Fee-on-publish: at campaign→ACTIVE, debit brand wallet 10% of budget → platform revenue wallet, atomic with status flip; block activation if wallet < fee ("top up ₹X to publish"); idempotent per campaign | **P0** | Vikram | Kavya → **Kabir PASS** → Meera |
| B2 | `GET /brand/platform-fee` transparency endpoint (bps + copy) | P1 | Vikram | Kavya → Meera |
| B5 | Persist Razorpay captured gateway fee per txn (reconciliation) | P1 | Vikram | Kavya → Meera |
| F1 | Wallet UI: balance, Razorpay top-up link, "budget + 10% at publish" breakdown + CEO copy | **P0** | Ananya | Meera e2e |

### Execution order (gated)
- **Wave 1A (safe, parallel, now):** B3 (reviews-received endpoint), B4 (CSV export) — Vikram ‖ F3 (disputes-track page) — Ananya.
- **Wave 2A:** B0 wallet top-up → Kavya → **Kabir PASS** → Meera. *(foundation — blocks all fee code)*
- **Wave 2B:** B1 + B2 + B5 → Kavya → **Kabir PASS** → Meera.
- **Wave 2C:** F1 wallet UI → Meera e2e.

---

## 🔴 (SUPERSEDED by the locked ruling above) Decision required from Swapnil/Rohan

1. **Fee charging model.** When a brand funds escrow for a ₹100,000 budget at 10%:
   - **(A) Fee on top:** brand pays ₹110,000; ₹100,000 to escrow, ₹10,000 to platform revenue. (The report's "budget + fee = total" UI implies this.)
   - **(B) Fee from budget:** brand pays ₹100,000; ₹90,000 to escrow, ₹10,000 to platform revenue. Creator-visible budget shrinks.
   - Also: charge at **funding** (money captured up front) or at **release** (mirrors creator side)?
2. **Volume tiers?** The report lists a "trailing-30-day spend rollup for volume-tier resolution." The config is a **flat singleton — no tiers exist.** Build tiers only if we actually want volume-based discounts. Otherwise this line item is dropped, not deferred.

---

## Corrected Brand Backlog

### Backend
| # | Task | Priority | Owner | Blocked on |
|---|---|---|---|---|
| B1 | Charge brand fee at escrow funding — `PlatformFeeService.deductAtFunding` (brandFeeBps) + wire into `EscrowService` fund path; ledger leg → platform revenue wallet | **P0** | Vikram | Decision #1 only |
| B2 | `GET /brand/platform-fee` transparency endpoint (mirror creator's) | P1 | Vikram | B1 shape |
| B3 | Reviews-**received**-about-brand list endpoint (the honest gap in `brand-reviews.tsx`) | P1 | Vikram | — |
| B4 | Campaign ROI report export (CSV first, PDF later) — no `csv`/`export` in any controller today | P1 | Vikram | — |
| B5 | Persist Razorpay's actual captured gateway fee per txn (Rohan reconciliation) | P1 | Vikram | — |
| B6 | Trailing-30-day spend rollup + volume tiers | P2 | Vikram | Decision #2 (drop if no tiers) |

### Frontend
| # | Task | Priority | Owner | Blocked on |
|---|---|---|---|---|
| F1 | Fee-transparency UI at escrow funding (budget + fee = total, before confirm) | **P0** | Ananya | B1/B2 |
| F2 | `brand-export` page (download campaign ROI report) | P1 | Ananya | B4 |
| F3 | `brand-disputes` tracking page (open already works from deal room; this is the list/track view) | P1 | Ananya | **DONE** — needs B7 below |
| B7 | `GET /brand/disputes` — list endpoint (id, collaborationId, openedByType/UserId, reason, status, resolution fields), scoped via BrandContextService, newest first. Confirmed missing by grep — only `POST /deals/{id}/disputes` (open) and admin resolve exist. F3 currently runs off `GET /deals` filtered to DISPUTED as a stopgap (loses reason/lifecycle detail). | P1 | Vikram | — |
| F4 | Surface "reviews received" in `brand-reviews.tsx` | P1 | Ananya | B3 |

### Already done — do NOT rebuild
Review entity+UI, Dispute entity+open+resolve+escrow-freeze, PlatformFeeConfig+admin UI, creator fee transparency, 22 brand pages (analytics, campaign-tracking, wallet, discover, pipeline, Meera chat).

---

## Execution Waves (pipeline: gated by Kavya QA → Meera verify)

- **Wave 1 (decision-independent, start now):** B3, B4, B5 (Vikram) ‖ F3 (Ananya). No dependency on the fee model.
- **Wave 2 (after Decision #1):** B1 → B2 (Vikram, payments — Kabir security pass required, it touches the ledger) → F1 (Ananya).
- **Wave 3:** F2 (needs B4), F4 (needs B3).
- **Every slice:** Kavya QA (TECH-STACK + security) → Meera build/verify before merge. B1/B2 additionally require **Kabir** (ledger/money path).

**Gate:** B1 touches the escrow ledger — the platform's highest-stakes path. No merge without Kabir sign-off, per the same discipline that caught the V44 lost-update race.
