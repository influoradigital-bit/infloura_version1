# WAVE 0 — Coverage Delta (W0-6)

**Produced:** 2026-07-15 · **By:** Priya (CTO) + 6-agent adversarial delta pass
**Trunk of truth:** `integration/consolidate` (worktree `.claude/worktrees/integration-consolidate`) — supersedes the stale plan's "fresh `integration/prod-readiness`". It is strictly ahead of `feature/d14-invoicing` (8 ahead / 0 behind) and already ported most non-money remediation.
**Port source:** `claude/influora-prod-readiness-audit-bc5269` (worktree present).

## Baseline (measured, not claimed)
| Suite | Result |
|---|---|
| `mvn -o test-compile` (influora-api) | ✅ GREEN (exit 0) |
| `vite build` | ✅ GREEN (exit 0) |
| `vitest run` | ❌ 116 fail / 61 pass (FE↔BE api-contract divergence) |
| `pytest` (influora-ai) | ❌ collection fails: `ImportError: TRENDSPARK_MODEL` (fixed by A1/A2 port) |
| Backend integration tests | Runnable — Docker 29.5.3 + `mysql:8.0` cached (reserved for fix verification) |

## Status legend
CLOSED = fixed on trunk · PORT = ready fix exists verbatim on bc5269 · MERGE = fix on bc5269 but branches diverged (manual) · NET-NEW = must build (no branch has it)

## Wave 1 — Security
| Task | Status | Route |
|---|---|---|
| S1 secrets fail-closed | CLOSED (secrets) / OPEN (DB `root:root`) | NET-NEW for DB creds |
| S2 permitAll webhooks+JWKS | OPEN | **PORT** (SecurityConfig.java:114-141) |
| S3 permitAll admin login | OPEN | NET-NEW |
| S4 suspend/ban | CLOSED (login) / OPEN (discovery) | NET-NEW for discovery exclusion |
| S5 escrow-milestone IDOR | OPEN | NET-NEW (scope findById by workspace) |
| S6 XFF allowlist | OPEN | **MERGE** (bc5269 AuthRateLimitFilter diverged) |
| S7 malware scan non-dev | OPEN | NET-NEW (@Profile("dev") + ClamAV bean) |
| S8 /admin hasRole | OPEN | NET-NEW |

## Wave 2 — Money (checkpoint before commit)
| Task | Status | Route |
|---|---|---|
| B1 no double-charge | ✅ CLOSED | — |
| B2 provision Deliverable rows | OPEN | **PORT** (ContractService.materializeDeliverables) |
| B3 approve→escrow release | OPEN | NET-NEW (bc5269 self-documented incomplete) |
| B4 contract DTOs | ✅ CLOSED | — |
| B5 enforce release_condition | OPEN | NET-NEW (modeled, never enforced on either) |
| B6 deliverable REJECTED path | OPEN | **PORT** service + NET-NEW controller route |
| B7 persist Payout + confirmExecuted | OPEN | **PORT** (PayoutReconciliationService) |
| B8 invoice REQUIRES_NEW isolation | OPEN | NET-NEW (D14 postdates bc5269) |
| B9 invoice-number uniqueness | ✅ CLOSED | — |
| B10 withdrawal→RazorpayX | OPEN | **PORT** (WalletService+RazorpayXClient) |
| B11 webhook routing | OPEN | **PORT** (dispatchFundingEventIfResolvable) |

## Wave 3 — AI
| Task | Status | Route |
|---|---|---|
| A1 register routers | OPEN | **PORT** (in progress) |
| A2 config symbols | OPEN | **PORT** (in progress) |
| A3 complete_text | OPEN | **PORT** (in progress) |
| A4 real Meera LLM turn | OPEN | NET-NEW (Java) |
| A5 stream token iss+scope | OPEN | NET-NEW (Java) |
| A6 browser stream transport | OPEN | NET-NEW (TS) |
| A7 chat cost-gate + durable spend | OPEN | **PORT** (in progress) |
| A8 wire injection hardening | OPEN | NET-NEW (wire existing untrusted.py) |
| A9 sarvam base64 | OPEN | NET-NEW |
| A10 analyze-site client | ⛔ **CORRECTION 2026-07-15: NOT closed — was wrongly marked ✅** | The delta agent saw `AnalyzeSiteTriggerService` internally wired (`trigger()` → `AnalyzeSiteRequestedEvent` → `@TransactionalEventListener(AFTER_COMMIT)` → `brandProfileRepository.save`) and concluded "wired end-to-end". **It is wired to nothing:** grep shows every reference to `AnalyzeSiteTriggerService` outside its own file is a javadoc mention — no controller exposes it, no service calls `trigger()`, the FE never calls it. So no `BrandProfile` is ever created, which is why TrendSpark fail-closes forever (`TrendSparkNudgeService:84-86`). **The fix is ONE WIRE, not new code:** call `analyzeSiteTriggerService.trigger(workspaceId, websiteUrl)` from brand onboarding (`OnboardingDtos` already carries `websiteUrl`) and/or workspace website update. `trigger()` already no-ops on blank input and opens its own tx, so it is safe to call from anywhere. Unblocks A10 + W4-2 + TrendSpark together. (voice client = still descoped) |

## Wave 4 — Notifications / jobs / infra
| Task | Status | Route |
|---|---|---|
| D1 event publish/listen | OPEN (4/6 unbuilt both) | PORT 2 + NET-NEW 4 |
| D2 @EnableAsync + AFTER_COMMIT | OPEN | **PORT** @EnableAsync / NET-NEW AFTER_COMMIT |
| D3 ShedLock 16 jobs | OPEN | **PORT** (missing only AffiliateSettlementJob) |
| D4 MSG91 fail-fast | OPEN | **PORT** |
| D5 EmailWorker SKIP LOCKED | OPEN | **PORT** |
| D6 orphan tables | OPEN (`file_uploads` only; `payouts` NOT orphan) | NET-NEW/decision |
| D7 prod datasource hardening | OPEN | NET-NEW (overlaps S1-DB) |

## Wave 5 — Frontend
| Task | Status | Route |
|---|---|---|
| F1a creator login | OPEN | **PORT** (auth → checkpoint) |
| F1b creator register | OPEN | NET-NEW (mock on both) |
| F2 onboarding | ✅ CLOSED | — |
| F3a campaigns-list | PARTIAL | finish live path |
| F3b brand-campaign-detail | OPEN | NET-NEW (all mock) |
| F4 deal rooms | OPEN | PORT ([v0] log strip) + NET-NEW persistence |
| F5a creator-wallet | PARTIAL | **PORT** (money → checkpoint) |
| F5b brand-wallet | OPEN | **PORT** (money → checkpoint) |
| F6 useNotifications | OPEN | **PORT** (partial) + fix base URL |
| F7 401→refresh interceptor | OPEN | **PORT** (auth-adjacent → checkpoint) |
| F8 creator-dashboard route | OPEN | NET-NEW (wire or delete) |
| F9 deliverable multipart | OPEN | NET-NEW |
| F10 Meera history GET | OPEN | NET-NEW |
| F11 server-side filters | PARTIAL | NET-NEW (brand-side pagination) |

## Wave 6 — Net-new backend
| Task | Status | Route |
|---|---|---|
| N1 CreatorOnboardingController | OPEN | NET-NEW |
| N2 generic POST /uploads | OPEN | NET-NEW |
| N3 /wallet/payout-methods | OPEN | NET-NEW (thin controller over existing encrypted service) |

## Totals
- **CLOSED already:** B1, B4, B9, F2, A10, S1-secrets, S4-login (~7 tasks + 2 halves)
- **PORT (fast, from bc5269):** S2, B2, B6-svc, B7, B10, B11, A1, A2, A3, A7, D2-async, D3, D4, D5, F1a, F5a, F5b, F6, F7 (~19)
- **MERGE (manual):** S6
- **NET-NEW (must build):** S1-DB, S3, S4-disc, S5, S7, S8, B3, B5, B8, A4, A5, A6, A8, A9, D1×4, D2-aftercommit, D6, D7, F1b, F3b, F4-persist, F8, F9, F10, F11, N1, N2, N3 (~28)

**Audit corrections confirmed (feeds Wave 7 C1):** `payouts` table is mapped (not orphan); Idempotency/money compile-blocker is stale; several F-items are partially-wired not fully-mock.
