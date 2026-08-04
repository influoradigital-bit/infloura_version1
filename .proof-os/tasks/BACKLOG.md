# proof-os backlog — "what to do next" (2026-08-03)

Ordered by severity × blast radius. Each item states its **done_when** (the exit test — the
gate must be able to declare it DONE) and its **gate** (the oracle). No item is DONE without
its gate green. Halt a queue on the first BROKEN. `fcntl` blocks the live ledger on Windows,
so this file IS the queue of record.

Legend — ceiling target: **proved** (deterministic oracle) · **believed** (test/model) ·
blocker tags: 🟢 none · 🟡 decision · 🔴 external epic.

---

## P0 · admin-finance-queue (Tier A) — ACTIVE, unblocked
Backend plumbing already exists; each is a read/aggregate over live tables. Gate pattern:
`mvn -o surefire:test -Dtest=<X>Test` green + module compiles; **each done_when MUST include a
runtime test that a SUPPORT/unauth caller is blocked before any read** (the item-1 pattern).

| id | deliverable | done_when | gate | reuse | 🚦 |
|----|-------------|-----------|------|-------|----|
| ~~F1~~ | ~~`GET /admin/finance/escrow`~~ | ✅ **DONE — commit ccc4f6f** (3 tests green) | mvn+surefire | — | 🟢 |
| ~~F2~~ | ~~`GET /admin/escrow/flagged`~~ | ✅ **DONE — commit 4717eb4** (7 tests green; campaign+dispute join, honest fallbacks, gate blocks SUPPORT) | mvn+surefire ✅ | `EscrowHoldRepository`,`DisputeRepository`,`CampaignRepository` | 🟢 |
| F3 | `GET /admin/finance/payouts` | **🔴 BLOCKED — reclassified 2026-08-03.** LOOKUP proved the FE `PayoutQueueItem` requires `grossAmount`/`tdsAmount`/`tdsSection('194C'\|'194R')`, but `Payout` stores only net `amount` and **no TDS engine exists** (no tds migration, no breakdown). Cannot satisfy the contract without fabricating tax figures. **Depends on T-TDS** (parked). | mvn+surefire | `PayoutService` (net amount only) | 🔴 T-TDS |
| F4 | `GET /admin/finance/reconciliation` | **🔴 BLOCKED — reclassified 2026-08-03.** No reconciliation entity/table/repo exists; `PayoutReconciliationService` is a write-side sweep (`confirmExecuted`/`reconcileOrphanedPendingPayout`, both `void`). FE `ReconciliationItem` (razorpay-vs-internal amounts + variance, by date) has no persisted source. Needs a **new reconciliation-ledger feature**. | mvn+surefire | (none — service is write-side) | 🔴 recon-ledger |
| F5 | `POST /admin/finance/reconciliation/{}/resolve` | **🔴 BLOCKED — depends on F4.** Can't resolve a reconciliation record that isn't persisted. | mvn+surefire | — | 🔴 F4 |
| F6 | `GET /admin/finance/revenue` | **🟡 PARTIAL — reclassified 2026-08-03.** `platformFees` (invoice `grossAmount` by `issuedAt`) + `gmv` (AdminDashboardService computes it) are sourceable, but the invoice repo has **no date-range/sum query** (new aggregation needed) and **`setupFees` has no obvious backend source**. Real build + a field-gap decision, not a reuse. | mvn+surefire | invoice/fee (partial) | 🟡 setupFees |

> **Meta-finding (2026-08-03):** F3–F6 were over-classified 🟢. Only F1/F2 had real queryable tables (`escrow_holds`). The payout/reconciliation/revenue read-models the FE contracts assume (TDS breakdown, reconciliation ledger, revenue aggregation with setup fees) **were never built** — "reuse `PayoutService`/`PayoutReconciliationService`" was wrong; those are write-side. The admin finance console is spec-first against an imagined backend, exactly as the original phantom audit warned.

**Verify at queue end:** FE↔BE DTO parity — `PayoutQueueItem`/`ReconciliationItem`/`RevenueSnapshot`
in `admin.types.ts` must match the new DTOs field-for-field (the one contract the compile gate can't see).

## P1 · remaining admin-console backends — unblocked, one queue each
Same gate pattern (mvn+surefire, authz test per endpoint). Sequence after P0.

| id | queue | endpoints | reuse / net-new | 🚦 |
|----|-------|-----------|-----------------|----|
| ~~E*~~ | ~~admin-errorlog~~ | ✅ **DONE — backend was already built (`9d22e4c`) + wired (useErrorLog/ErrorLogPage). Added test coverage (`a5f10dd`, 6 tests green), verified FE↔BE parity, pruned stale baseline.** | mvn+surefire+vitest ✅ | `ErrorLog`/`error_log` | 🟢 |
| M* | admin-emails | `emails/queue`, `queue/{}/retry`, `templates`, `send-bulk`, `stats` (5) | email send infra exists (notifications); net-new: queue/template admin surface | 🟢 |
| K* | admin-marketing | `marketing/acquisition`, `growth`, `referrals`, `reputation` (4) | net-new analytics aggregations over signup/referral/review tables | 🟢 |
| O* | admin-moderation | `moderation/suspensions`, `suspensions/{}/appeal` (2) | `AdminModerationController` + `ApprovalWorkflowController` exist → extend | 🟢 |
| U* | admin-users | `brands/{}/campaigns/{}/budget-override`, `creators/{}/tier`, `creators/applications/pending` (3) | reuse Campaign + CreatorProfile services | 🟡 budget-override touches money → confirm authz model |
| D* | admin-dashboard | `dashboard/financial`, `dashboard/marketing` (2) | **depends on F* + K*** — these are dashboard rollups of finance/marketing | 🟡 dep |
| C* | admin-campaigns | `campaigns/at-risk`, `campaigns/hype/ops` (2) | `AdminCampaignController` exists → add derived queries | 🟢 |
| S* | admin-support | `support/stats` (1) | `AdminSupportService` exists → add stats aggregate | 🟢 |

## P2 · small FE wiring — no backend build
| id | deliverable | done_when | gate | 🚦 |
|----|-------------|-----------|------|----|
| N1 | Wire `notifications/preferences` UI | tsc 0 + component calls existing `api.notifications.getPreferences/setPreference`; guardrail green | tsc+vitest | 🟢 |
| A1 | `analytics/creators/{}/media` — decide build-or-remove | either FE caller wired to a real route, or the dead export removed + baseline shrunk | tsc+vitest | 🟡 product |

## PARKED — blocked, do NOT build blind
| id | item | why parked | unblock condition |
|----|------|------------|-------------------|
| B-ESCROW | `escrow/{}/hold\|release\|refund` (3) | direct-manipulation vs audited dispute-mediated model undecided (Tier B) | security-reviewed design decision |
| R-PAYOUT | `payouts/{}/retry` | Razorpay Route: per-payment transfers + creator KYC linked accounts | Razorpay Route epic (weeks) |
| T-TDS | `finance/tds/26q` | no TDS engine exists at all | TDS calculation + 26Q export epic |

## Cross-cutting (applies to every P0/P1 item)
- **Authz gate is a test, not a comment:** each endpoint's done_when includes a surefire test that
  `requireRoleWithMfaSatisfied` is invoked AND a rejected caller reads/writes nothing.
- **Native SQL blind spot:** MySQL-native queries (`TIMESTAMPDIFF`, etc.) are mocked in unit tests;
  H2 can't verify them. A Testcontainers/`@DataJpaTest` harness is its own infra task — until it
  exists, "SQL runs on real MySQL" stays a declared NOT-CHECKED on every finance item.
- **Observability:** revenue/reconciliation totals ride on the invoice-backfill gap (CR-51).

## Sequencing
1. **P0 F2→F6** (finish the finance console — highest FE unblock).
2. **P1** error-log + emails + moderation + campaigns + support (reuse-heavy, low risk), then marketing.
3. **P1 dashboard** (D*) last in P1 — it depends on F* and K*.
4. **P2** FE wiring anytime (parallel-safe).
5. **PARKED** items are separate epics with their own confirm — never folded into a console queue.
