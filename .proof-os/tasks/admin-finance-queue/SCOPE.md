# Admin Finance/Escrow console — backend scope (as of 2026-08-03)

## What's already built (this branch, UNCOMMITTED — `??` untracked)
vikram opened `admin-finance-queue`; **item 1 of 11 done + gated**:
- `AdminFinanceController` @ `/admin/finance` — `GET /escrow` → `EscrowSummaryDto{totalLocked,pendingRelease,flaggedTransactions,averageReleaseTime}`
- `AdminFinanceService.getEscrowSummary()`, `AdminFinanceDtos.EscrowSummaryDto`, `EscrowHoldRepository`
- Gate: `build.mvn.sh` compiles (believed, mvn). **Open blind spots:** no runtime DataJpaTest; MFA/role enforcement not runtime-verified; `pendingRelease` amount-vs-count unconfirmed.
- FE contract match: `escrowApi.getEscrowSummary()` → `GET /admin/finance/escrow` ✅

## Reusable backend primitives (already exist — strong foundation)
- **`EscrowService`** — `release()`, `refund()`, `getStatus()`, `listForWorkspace()`, plus **dispute-mediated** admin ops: `adminReleaseForDispute(collaborationId)`, `adminRefundForDispute`, `adminSplitForDispute`, `freezeUnreleasedForDispute`. No direct `hold()` (funding = `initiateFund`/`confirmFunded`).
- **`PayoutService` / `PayoutRepository` / `Payout`** — payout list + state.
- **`PayoutReconciliationService` / `AffiliateEarningReconciliationJob` / `PayoutOrphanedDebitSweepJob`** — reconciliation engine.
- **`EscrowHoldRepository`** — hold queries.
- **No TDS service exists** anywhere (confirms TDS unimplemented).

## Pending — 10 endpoints, tiered by buildability

### Tier A — buildable now (existing primitives, no blocker) — 5 items
| # | Endpoint | Reuse | Net-new |
|---|----------|-------|---------|
| 2 | `GET /admin/escrow/flagged` | EscrowHoldRepository | flagged-holds query + list DTO |
| 3 | `GET /admin/finance/payouts` | PayoutService/PayoutRepository | filter/paginate → PayoutQueueItem DTO |
| 4 | `GET /admin/finance/reconciliation` | PayoutReconciliationService | expose recon items read |
| 5 | `POST /admin/finance/reconciliation/{}/resolve` | PayoutReconciliationService | resolve mutation + idempotency |
| 6 | `GET /admin/finance/revenue` | fee/invoice ledger | RevenueSnapshot aggregation query |

### Tier B — needs a DESIGN DECISION (model mismatch) — 3 items
`POST /admin/escrow/{}/hold`, `/release`, `/refund` — the FE wants **direct escrow manipulation by escrowHoldId**. The backend deliberately adopted a **dispute-mediated model** (`adminReleaseForDispute(collaborationId)` via `AdminDisputeController`), which a prior security review cleared as well-guarded. Direct manipulation bypasses that audit trail. → **decision required before building** (see question below).

### Tier C — blocked by known hard dependencies — 2 items
| # | Endpoint | Blocker |
|---|----------|---------|
| 10 | `POST /admin/finance/payouts/{}/retry` | **Razorpay Route** — needs per-payment transfers + creator KYC linked accounts; weeks, not a flag flip [[project_razorpay_route_zero_rework_false]] |
| 11 | `GET /admin/finance/tds/26q` | **TDS engine unimplemented** — no service exists; needs full TDS calc + 26Q export |

## Cross-cutting (applies to every item)
- **AuthZ:** OWNER/ADMIN role + MFA gating — item 1 flagged this as compiled-but-not-runtime-verified. Bake a runtime test into each item's `done_when`.
- **Observability:** revenue/reconciliation totals are affected by the invoice backfill gap [[project_escrow_invoice_backfill_gap]] (CR-51) — a release can silently skip its GST invoice.
- **Gate per item (vikram's pattern):** `build.mvn.sh` compiles + endpoint returns live-computed data, no fabricated arrays. Add a `@DataJpaTest`/`@WebMvcTest` where a repository query or authz path is the risk.

## DECISION (2026-08-03, human): defer Tier B, build Tier A now.
Tier B (`escrow/{}/hold|release|refund`) is **parked** pending a dedicated, security-reviewed
design pass — direct-manipulation-vs-dispute-mediated is not decided. Tier C stays parked as
separate epics. **Active queue = commit item 1, then items 2–6.**

## Active queue (approved)
| step | item | done_when (per vikram's pattern) |
|---|---|---|
| 0 | ✅ **DONE (commit ccc4f6f)** — item 1 committed + `AdminFinanceServiceTest` (3 tests green via surefire: gate fires, unauth reads nothing, null aggregates → 0.0). Native `avgReleaseSeconds` SQL still needs a Testcontainers harness (declared). | build.mvn exit 0 + surefire 3/3 ✅ |
| 1 | #2 `GET /admin/escrow/flagged` | mvn exit 0; returns flagged holds live from escrow_holds; no fabricated data |
| 2 | #3 `GET /admin/finance/payouts` | mvn exit 0; live from PayoutRepository; paginated |
| 3 | #4 `GET /admin/finance/reconciliation` | mvn exit 0; live from PayoutReconciliationService |
| 4 | #5 `POST /admin/finance/reconciliation/{}/resolve` | mvn exit 0; idempotent resolve mutation |
| 5 | #6 `GET /admin/finance/revenue` | mvn exit 0; RevenueSnapshot aggregated live; note CR-51 invoice-gap caveat |

Each item: OWNER/ADMIN + MFA gate present AND a runtime test that proves a SUPPORT/unauth caller is blocked.

## PARKED
- **Tier B** — escrow direct-control (3): needs security-reviewed model decision.
- **Tier C** — `payouts/{}/retry` (Razorpay Route epic), `tds/26q` (TDS engine epic).
