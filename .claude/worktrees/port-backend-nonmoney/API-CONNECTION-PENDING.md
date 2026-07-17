# Influora — API-Connection: PENDING Work

**Branch:** `feature/analytics-platform` · **Date:** 2026-07-14
**Owner:** Priya (CTO) · **Routed by:** Arjun · **Method:** code-verified.

> Companion: [`API-CONNECTION-COMPLETED.md`](./API-CONNECTION-COMPLETED.md).
> **Branch note:** fuller product branch (17 controllers). The leaner `claude/api-connection-workflow-b62285` tracks its own ledger in that worktree.

---

## P1 — Mock→live swap (the daily workspace still renders mock here) — **top code priority**

These pages are built + routed but read `mock*` constants; the backend controllers exist on this branch, so the swap is real (unlike the leaner branch where they'd 404).

| # | Surface | Swap to | Owner | Status |
|---|---|---|---|---|
| P1-1 | `creator-wallet` (`mockEarningsData`/`mockPayouts`) | `api.wallet` → `WalletController` | Ananya | 🔴 |
| P1-2 | `creator-profile` (`mockProfile`) | `MeCreatorProfile` facade | Ananya | 🔴 |
| P1-3 | brand `campaigns-list` (`[demoHypeCampaign,...mockCampaigns]`) | `api.campaigns.list` | Ananya | 🔴 |
| P1-4 | brand `campaign-detail` (`MOCK_CAMPAIGNS`, mock bids/collaborators) | `api.campaigns` + `api.deals` | Ananya | 🔴 |
| P1-5 | brand `contracts` (`mockContracts`) | `api.contracts` → `ContractController` | Ananya | 🔴 |
| P1-6 | brand `wallet` (`mockWalletData`) | `api.wallet` — **money, verify escrow shape** | Ananya + Vikram | 🔴 |
| P1-7 | brand `messages` (`mockConversations`) | `api.messages` → `DealController` | Ananya | 🔴 |

> ⚠️ Confirm the `dealId`↔`milestoneId` / wallet field-shape contract against the live controller before deleting each mock, or the swap 400s.

## P0 — Port from the leaner branch
| # | Item | Note |
|---|---|---|
| P0-5 | React error boundary — `ErrorBoundary` exists on `api-connection-workflow`, **absent here** | port `src/components/ErrorBoundary.tsx` + wrap `App.tsx` |
| — | `creator-dashboard` — real tested page, **unrouted and not redirected** | decide: route it or formally retire (Priya/Swapnil) |

## P2 / P4 / P5 — Backend verify + infra (human/toolchain-gated)
| # | Item | Status | Note |
|---|---|---|---|
| P2-4 | `mvn verify` + deployable jar | ⛔ | `git stash@{0}` still present (~40 compile errors from lost edits); **no `mvn`/`mvnw` in this env** — backend cannot be compiled/verified here |
| P4-1 | `VITE_API_BASE_URL` → prod URL (still `localhost:8080`) | 🟡 | needs prod URL |
| P4-3 | Wire prod secrets | 🔴 | credential-gated |
| P4-2 / P4-4 | Build artifacts + deploy + smoke | ⛔ | gated on P2-4, P4-3 |
| P3-2 | E2E provider-key verify + cost gate | ⛔ | prod keys |
| P5-2 | Rate limiting → Redis | 🔴 | needs Redis instance |
| P5-4 / P5-5 | Key rotation + pen-test | 🔴 | launch gate |

## 🔒 Security — act before any commit-to-remote
- `scripts/register-test-brand.sh:21` hardcodes a **real-looking personal password** (`Swapnil111ms$`). Left **uncommitted**. Replace with a throwaway test password and rotate if reused elsewhere.

---

*Maintained by Tara. This ledger is for `feature/analytics-platform` ONLY.*
