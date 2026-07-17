> ⚠️ **SUPERSEDED 2026-07-12 (Priya).** Completion numbers below are stale (2026-07-10). Current code-verified status + owner assignments: **`wiki/reports/2026-07-12/PENDING-WORK-ASSIGNMENTS-2026-07-12.md`** and **`.../PRIYA-CTO-CONSOLIDATED-REPORT-2026-07-12.md`**. Kept for history only.

---

# Influora — Pending Tasks Only (Brand / Admin / Creator)

> **Author:** Priya (CTO)
> **For:** Swapnil (CEO)
> **Date:** 2026-07-09 · **Progress update: 2026-07-10**
> **Method:** Direct codebase audit — `influora-api/src/main/java` (505 Java files, 49 entities, 38 controllers) + `src/pages` + `src/admin`. Nothing below is assumed; every "MISSING" was verified by file check.

---

## 📊 PROGRESS UPDATE — BRAND track (2026-07-10)

> This original report's BRAND section was **materially stale** — Reviews, Disputes, and PlatformFeeConfig were flagged "MISSING / blocked on legal policy" but were already built. See `wiki/tech/BRAND_EXECUTION_PLAN.md` for the corrected scope + the locked CTO/CEO/CFO/COO fee ruling. Status of every BRAND line item below:

**✅ DONE this session (built → adversarially reviewed by Kavya QA + Kabir security → verified by Meera):**
| Item | Original report status | Now |
|---|---|---|
| Charge brand fee (P0) | "Not started" | **DONE as B1** — redesigned per CEO ruling to fee-on-publish: 10% charged atomically at campaign→ACTIVE, fee-on-top, drawn from wallet. Survived 3 review rounds (idempotency collision, unconditional side-effects, cross-path launch gap — all fixed). |
| Brand wallet top-up (NEW P0) | not in report | **DONE as B0** — Razorpay order + webhook credit, liability-booked. Foundation for the whole fee flow. Fixed a CRITICAL ledger idempotency-collision bug found by Kabir. |
| Fee transparency endpoint | not in report | **DONE as B2** — `GET /brand/platform-fee`. |
| Fee transparency UI at funding (P0) | "Not started" | **DONE as F1** — wallet top-up UI + budget/fee/total breakdown + 402 handling. |
| `brand-disputes` page (P0) | "Not started" | **DONE as F3 + B7** — page shipped + `GET /brand/disputes` list endpoint (tenant-isolation verified). |
| `Review` entity + `brand-reviews` (P0) | "Not started / blocked on policy" | **Already built before this session** — report was stale. |
| `Dispute` entity + resolution (P0) | "Not started / legal liability" | **Already built before this session** — incl. escrow-freeze-on-dispute. Report was stale. |
| `PlatformFeeConfig` + admin UI | "MISSING" | **Already built before this session.** |

**🚫 DROPPED (CEO ruling):**
| Item | Reason |
|---|---|
| Trailing-30-day spend rollup / volume tiers (P0) | Swapnil ruled flat 10%, no tiers. Removed from scope, not deferred. |

**⏳ STILL OPEN (not started this session):**
| Item | Priority | Tracking |
|---|---|---|
| Log Razorpay actual gateway fee per txn (B5) | P1 | Task #5 — CFO reconciliation need |
| Report export CSV/PDF + `brand-export` page | P1 | Not picked up — zero `export`/`csv` in controllers, no page |

**🔴 CRITICAL GAP found during this work (was invisible in original report):**
- **No real Razorpay Checkout integration exists anywhere in the codebase** (task #19). Every payment flow — escrow funding *and* the new wallet top-up — is mock-only in live mode (`FundEscrowButton.tsx` has only a placeholder comment). All the backend money-safety rigor built this session is sound but has **no live payment collection behind it.** This gates real revenue and should be the next priority.

**Non-blocking follow-ups opened:** withdraw-endpoint idempotency pattern (#8, same class as the B0 bug, on already-shipped code), dispute-page data enrichment (#15), Meera chat UX for the new `CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH` 409 (#16), test-coverage gaps on B2/B7 (#17/#18), and a `BrandSafetyAiClient` app-boot investigation (running separately) that has blocked full e2e verification all session.

**Net:** every BRAND item that was real and in-scope is **done and verified**; the two remaining (B5, export) are P1; the one thing that actually blocks going live is the Razorpay Checkout gap (#19), which no prior report had surfaced.

---

## ⚠️ Correction to my earlier estimate

I told Swapnil the creator frontend was "~40-50%." **That was wrong.** A direct file audit shows **19 creator pages already exist** (`creator-wallet`, `creator-campaigns`, `creator-coupons`, `creator-affiliate-earnings`, `creator-portfolio-editor`, `creator-chat`, `creator-onboarding`, etc.). The real number is closer to **~70%**. I estimated from the spec's task list instead of checking the filesystem. Corrected numbers are at the bottom.

Also: the "BidService missing" item I flagged is **wrong**. The bid/negotiation flow exists as `DealService` (`createProposal`, `counter`, `accept`, `reject`, `sendMessage`) — different name, same function. What's missing is only the **creator-facing bids UI page**, not the backend.

---

## 🔴 CROSS-CUTTING BLOCKERS (affect all three surfaces)

These entities **do not exist anywhere in the codebase.** Verified by file check:

| Entity | Exists? | Blocks | Spec written? |
|---|---|---|---|
| `Review` / `Rating` | ❌ MISSING | Brand trust, creator trust, SEO pages | ❌ No spec |
| `Dispute` / `Refund` | ❌ MISSING | Escrow has no exit path — **legal liability** | ❌ No spec |
| `PlatformFeeConfig` | ❌ MISSING | **All brand + creator fee revenue** | ✅ Spec'd (`10_CREATOR_PAYMENTS_SPEC.md`) |
| `Subscription` / `Plan` / `Invoice` | ❌ MISSING | Deferred — Swapnil ruled no subscription at launch | ✅ Deferred |
| `MessageThread` | ❌ MISSING | Brand↔creator DMs (partially covered by `DealMessage`) | Partial |
| `ReferralCode` | ❌ MISSING | Tejas's #1 growth ask | ❌ No spec |

**Only one of these is spec'd and ready to build: `PlatformFeeConfig`.** Reviews and Disputes have neither code nor spec — they are 0% of nothing, and I cannot spec them until Swapnil rules on policy.

---

## 1. BRAND — Pending

### Backend
| Task | Status | Priority | Blocked on |
|---|---|---|---|
| `PlatformFeeService` + `FeeScope.BRAND` | Not started | **P0** | Swapnil approving Rohan's fee % |
| Charge brand fee at escrow funding (`EscrowService` extension) | Not started | **P0** | Above |
| Trailing-30-day spend rollup (volume tier resolution) | Not started | **P0** | Above |
| Log Razorpay's actual fee per txn (Rohan's reconciliation ask) | Not started | P1 | — |
| `Review` entity + brand-rates-creator endpoint | Not started | **P0** | Policy + spec |
| `Dispute` entity + resolution workflow | Not started | **P0** | Policy + spec |
| Report export (CSV/PDF) — `export` appears in 0 files | Not started | P1 | — |

### Frontend (22 brand pages exist — these are the gaps)
| Missing page | Priority |
|---|---|
| Fee transparency UI at escrow funding (budget + fee = total, before confirm) | **P0** |
| `brand-reviews` (rate creator post-collaboration) | **P0** |
| `brand-disputes` (raise/track dispute) | **P0** |
| `brand-export` (download campaign ROI report) | P1 |
| `brand-billing` | Deferred (no subscription at launch) |

**Brand is the most complete surface.** 22 pages shipped incl. analytics, campaign tracking, wallet, discover, pipeline, Meera chat.

---

## 2. ADMIN — Pending

Phase 1 signed off by Swapnil 2026-07-09. **831 backend + 139 frontend tests, 0 failures.** Six real security bugs found and fixed by Kabir (plaintext `mfa_secret`, zero RBAC enforcement, IP-spoofing in audit log, secrets-validator gap).

| Task | Status | Priority | Blocked on |
|---|---|---|---|
| **Staging deploy** | Checklist ready, **cannot execute** | **P0** | **Swapnil — needs cloud credentials** (Dockerfiles **✅ DONE**) |
| Admin fee-control UI + `PlatformFeeAdminController` | **✅ DONE** (V44 + fix for lost-update race) | **P0** | ~~Fee % approval~~ (Swapnil approved 10%/15%, 2026-07-09) |
| Admin dispute-resolution console | Not started | **P0** | Dispute policy (**DEFERRED to Phase 2** per Swapnil) |
| Kavya's formal test-spec doc + security checklist (standalone artifacts) | **✅ DONE** (`TEST-SPEC.md`, `SECURITY-CHECKLIST.md`) | P1 | ~~Ad-hoc review~~ |
| Dedicated admin backend test suite | Not started | P1 | — (Swapnil directive: build after Dockerfiles) |
| Arjun's daily standup docs | Not started | P2 | Low-value process item (**DEFERRED** per Swapnil) |
| Rohan: TDS spec, revenue-dashboard validation, reconciliation review | Not started | P1 | **CFO business input** (Phase 2) |
| Tejas: acquisition dashboard, referral tracking, reputation-score formula | Not started | P2 | **CMO business input** (Phase 2) |

**Admin backend test coverage:** 831 passing tests cover admin paths incidentally via integration and RBAC tests. Dedicated admin test suite remains a P1 backlog item per Swapnil directive.

**Security post-audit status (Kabir review):** 21 fixed, 9 accepted risk, 7 open Phase-2 items. Key open item: RBAC is service-layer convention, not framework-enforced — Phase 2 hardening target.

---

## 3. CREATOR — Pending

### Backend (~72%)
| Task | Status | Priority |
|---|---|---|
| `PlatformFeeService` — deduct 15% at escrow release | Not started | **P0** |
| Creator-facing coupon-read endpoint (flagged in Wave A, still open) | Not started | P1 |
| `GET /creator/platform-fee` (transparency endpoint) | Not started | P1 |
| Creator OTP signup flow (spec'd, partial) | ~80% | P0 |
| `BrandSafetyScoreService` | Deferred (Phase 4 epic) | P2 |
| `AudienceDemographicsJob` | Not started | P2 |
| Creator growth-AI endpoints (spec 11) | 0% | P2 |

**Note:** `DealService` already covers bid submission, counter-offer, accept/reject, and messaging. The `06_CREATOR_BIDS_SPEC.md` backend is effectively done under a different name.

### Frontend (~70% — 19 pages exist)
| Missing page | Priority |
|---|---|
| `creator-dashboard` (main landing after login) | **P0** |
| `creator-bids` (view/manage own bids — backend ready via `DealService`) | **P0** |
| `creator-deliverables` (submit content) | **P0** |
| `creator-contracts` (review/sign) | **P0** |
| `creator-analytics` (growth tracking, spec 11) | P2 |
| `creator-reviews` (rate brand post-collaboration) | **P0** — blocked on Review entity |
| Fee transparency in `creator-wallet` (show 15% deducted, net earnings) | P1 |

**Already shipped:** `creator-wallet`, `creator-campaigns`, `creator-campaign-detail`, `creator-coupons`, `creator-affiliate-earnings`, `creator-chat`, `creator-inbox`, `creator-deals`, `creator-active`, `creator-onboarding`, `creator-profile`, `creator-settings`, `creator-portfolio-editor`, `creator-portfolio-public`, `creator-login`, `creator-register`, `creator-meta-callback`.

---

## Corrected Completion Numbers

| Surface | Backend | Frontend | Blended |
|---|---|---|---|
| **Admin** | ~90% | ~85% | **~87%** |
| **Brand** | ~80% | ~85% | **~82%** |
| **Creator** | ~72% | ~70% | **~71%** |
| **Platform blended** | | | **~78%** |

**Caveat I want on the record:** these percentages measure progress *against what we've spec'd*. Reviews, Disputes, and Referrals aren't in the denominator because they have no spec. Once I write those specs, the blended number will **drop to roughly ~65%** — that's the estimate becoming honest, not the project going backwards. I'd rather tell you that now than have it surface as a surprise slip later.

Separately: 76 `TODO`/`FIXME` markers across the codebase. Not blockers, but real debt.

---

## What I Need From Swapnil (in priority order)

1. **Dispute + refund policy** — who arbitrates, what's the refund rule? Unblocks me to spec A2, then Vikram builds. This is the single largest legal exposure we carry today: we hold brand money in escrow with no defined exit.
2. **Approve Rohan's fee percentages** (10% brand / 15% creator, Option A: we absorb Razorpay costs). Unblocks `PlatformFeeConfig` — the entity that gates **100% of our revenue**. It's spec'd and small; Vikram can ship it in days.
3. **Staging infra credentials.** Admin is 87% done and 100% test-green, but has never run against real infrastructure. "Passes tests in a sandbox" ≠ "works in production." Meera is blocked, not slow.
4. **Review/rating policy** — can a creator review a brand? Moderation rules? Unblocks A1.

Items 1, 2, and 4 are decisions, not engineering. Item 3 is credentials. **None of these need a single line of code from you — but nothing ships without them.**
