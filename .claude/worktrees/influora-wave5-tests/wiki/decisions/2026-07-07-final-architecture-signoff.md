# Final Architecture Sign-Off — INFLUORA Analytics/Tracking Platform build

> **Decision by:** Priya Sharma (CTO) — final architecture authority (`wiki/tech/REMAINING_WORK_PLAN.md` E6)
> **Date:** 2026-07-07
> **Status:** LOCKED
> **Scope:** the entire `feature/analytics-platform` build across this session — Wave A (tracking/coupons), Wave B (Meta metrics pipeline), Wave C (BrandSafety AI epic), Wave D (Shopify/WooCommerce/affiliate), Wave E (workspace-isolation + idempotency audits, CI infra).
> **This is the holistic capstone review**, not a repeat of the point-in-time ADRs already locked this session (`2026-07-07-spring-python-service-auth-jwks-gap.md`, `2026-07-07-d3-campaign-gating-scope.md`).

---

## VERDICT

**The architecture is SOUND and CONSISTENT. Engineering work is APPROVED as complete and ready for Swapnil's E7 launch-approval review — GO-WITH-CONDITIONS.**

The conditions below are **not architectural defects.** They are known, deliberately-scoped, documented-in-code boundaries plus the two pre-existing human gates (E5 credentials, E7 CEO approval) and the one agent-buildable launch blocker (E-JWKS) that the plan already carries. Nothing in this build needs to be re-architected before launch. The review pipeline held — it caught real bugs (cross-tenant coupon redemption, `@Transactional` self-invocation, sendTurn replay-correctness, ContractService cross-workspace, plus E4's two capstone-only catches: the un-HMAC'd conversion webhook and the tracking-link open redirect) rather than rubber-stamping, and every one was closed through the full chain. **Kabir's E4 capstone red-team has LANDED (SIGN OFF WITH CONDITIONS); no un-closed net-new CRIT/HIGH survives it** (§3).

---

## 1. Standing rules — spot-checked in code, not assumed

I verified each standing rule directly against the current tree rather than trusting the wave-completion notes. All four hold throughout, not just on the reviewed hot paths.

| Standing rule | Verdict | Evidence (independently checked) |
|---|---|---|
| **Vite + React Router, NOT Next.js** | ✅ HELD | No `src/app/**/page.tsx`, no `loading.tsx`, zero `next`/`next/*` imports across `src/`. |
| **MySQL only (no Postgres/Timescale)** | ✅ HELD | `application.yml`: `jdbc:mysql://…` + `org.hibernate.dialect.MySQLDialect`. The only "postgres/timescale" strings in the backend are ADR-citation *comments affirming the rule* (e.g. `V27__shopify_integrations.sql:13`), never a datasource. |
| **Resolve-then-scope on brand-facing per-creator data** | ✅ HELD | `CampaignTrackingController` derives workspace from `BrandContextService.requireBrandWorkspace(principal)` server-side, never a caller-supplied `campaignId`→repo. 43 workspace-scoping occurrences across the 5 tracking services. D-wave webhooks resolve the caller identifier (shop domain) → workspace server-side with enumeration-safe 404s. |
| **`IdempotencyService.executeOnce` on retry/webhook-reachable mutations** | ✅ HELD | Present on all 21 relevant surfaces incl. both new store webhooks, `ConversionWebhookController`, escrow executors (`ConfirmLaunch`/`RequestPayment`), `PayoutService`, `AffiliateSettlementJob` + `AffiliateEarningReconciliationJob`, `RedemptionService`, `ContractService`, `MeeraSessionService`. |

**Architectural highlight — the D-wave webhook surface is exemplary, not merely acceptable.** `ShopifyWebhookController` demonstrates every rule at once: signature-verify-before-parse (fail-closed if the secret is unconfigured), resolve-then-scope (shop domain → workspace, never caller-supplied), *dual-layer* idempotency (webhook-delivery dedup + `RedemptionService`'s own coupon-row dedup sharing one derived key), enumeration-safe 404s, and the D1 cross-tenant fix in place with load-bearing provenance javadoc. This is the level of discipline I want the pattern library to be judged against going forward.

---

## 2. Tracked-but-open items — architectural judgment on each

### E-JWKS (Spring→Python auth, Direction 2 staging-only) — **my ruling HOLDS. Confirmed final.**
Nothing in the holistic view changes the `2026-07-07-spring-python-service-auth-jwks-gap.md` decision. The state fails **closed** (Python's `ALLOWED_ALGS` rejects HS256 on the JWKS path via a type check, not a config flag; `SecretsStartupValidator` refuses non-dev boot without real distinct secrets) — no prod endpoint works, therefore no prod endpoint is exploitable. It remains a **hard E7 launch blocker**, agent-buildable (Vikram), and must be asymmetric (the HARD CONDITION against a shared HS256 secret stands, CTO+CISO-gated). **Confirmed as my final position.**

### D5 backend gaps (no integration status/disconnect endpoint; no affiliate-earnings read endpoint) — **ACCEPTABLE TO SHIP.**
Grep-confirmed both genuinely absent from `web/`. This is **"UI built against honest gaps,"** the same discipline Wave A used for `creatorCoupons`/`contentPerformance` — the frontend renders amber informational Alerts explaining the gap, connect actions are real and ungated, and **zero fabricated data** is shown (Kavya verified). Architecturally these are *additive read surfaces*, not missing write paths: the write side (connect, settle, track) is complete and correct; only the read-back convenience endpoints are deferred. No data-integrity or security consequence to shipping without them. **Ship as-is; logged as post-launch follow-ups for Vikram.** They do not gate E7.

### D4 ledger-only settlement (no RazorpayX disbursement wiring) — **view UNCHANGED; correct boundary.**
Seeing the whole system reinforces rather than changes Rohan/Kabir's call. `AffiliateSettlementJob` (and `AffiliateEarningsService`) contain **zero** RazorpayX/disbursement code — grep-confirmed. The job's own javadoc (lines 29–40) makes the boundary explicit: `SETTLED` = "creator becomes *entitled* to be paid," an internal ledger action, deliberately NOT a money movement. Double-payout is structurally impossible (`UNIQUE(redemption_id)`/`UNIQUE(idempotency_key)` proven live; server-derived idempotency key). The hard gate — **`SETTLED` must never surface as "paid"** — is honored in code AND UI (Kavya: word "Paid" appears nowhere in user-visible text; `SETTLED` renders as "Confirmed" with an explicit "not yet disbursed" tooltip). This is the *right* way to ship a money-moving schema without a rushed second payment-gateway integration on the critical path. **The ledger-only boundary is a sound architectural decision, not debt to pay down before launch** — layering the actual disbursement on top later does not reopen this class's idempotency guarantees. It stays a tracked product/Rohan decision (how affiliate payouts disburse: same wallet vs. separate RazorpayX contact vs. batched wire), not an engineering blocker.

### E2 `result_digest` column reuse — **SOUND. One lightweight follow-up, non-blocking.**
Vikram/Kavya/Kabir's judgment holds from my vantage point. `VARCHAR(128)` carries the `userMessageId:assistantMessageId` pair (53 chars, ample headroom); git history confirms the column existed since V15 but was never populated (Vikram is the first legitimate caller), so there is no collision with a prior meaning. The replay reads the exact pair by PK — the "latest 2 messages" query that caused the replay-correctness bug is deleted. **No architectural concern.** My only forward-looking note: `result_digest` is a *generic per-idempotency-record result pointer for callers that lack a natural terminal-state key* (`sendTurn` is exactly that case). That's the correct mental model — but it is now an implicit convention. **Follow-up (non-blocking, before the pattern is reused a third time):** add a one-paragraph contract note to `IdempotencyService`'s javadoc stating what `result_digest` is for and is not for (it is a *pointer to reconstruct a result*, not an integrity hash; callers with a natural terminal-state marker — like escrow `/release`/`/refund` — should short-circuit on that instead). This prevents the next engineer from cargo-culting it as a hash or reaching for it when a domain natural key exists. Logged, not gating.

### Wave E3 CI infra — **my condition STANDS, verbatim.**
Testcontainers MySQL + base integration-test class + one proof-of-concept test are **built and wired**, and I approved the (test-scope-only, transitively version-pinned) dependency. **The condition is unchanged: this is "built & wired, not yet proven" until one green CI run against a real Docker daemon confirms it.** The recurring `DatabaseConstraintIntegrationTest » Could not find a valid Docker environment` error in every Meera/Kavya/Kabir suite run (580–581 functional tests pass, 1 environmental Docker error) is the *expected* signature of this condition, not a regression. **Do NOT cite E3 as a working safety net until that first green Docker run happens.** This does not block E7 — it is a standing-recommendation deliverable whose value is realized in CI, not a launch gate — but it must not be misrepresented as active coverage in any launch materials.

---

## 3. Kabir's E4 full red-team sign-off — LANDED, reconciled

E4 (Kabir's cross-phase red-team capstone) is the one input to this verdict that is **security-findings domain (his), not architecture domain (mine).** Per the plan, "No one overrides her tech decisions except Swapnil" — but on *security findings* Kabir's verdict is load-bearing and I factor it in rather than override it. I did not wait-block the architecture review on it — the two are separable — so I ran his E4 pass in parallel with my own spot-checks.

**E4 outcome: SIGN OFF WITH CONDITIONS (Kabir's framing).** Every CRIT/HIGH across the entire build (Meta OAuth P0s, Phase 2 workspace-isolation groundwork, D1 cross-tenant coupon, D4 self-invocation commission-loss, E1 ContractService cross-workspace, E2 payout-wedge + sendTurn replay, C2's two-round delimiter breakout) traces to a closed, adversarially re-confirmed fix — nothing silently dropped. Wave D webhooks confirmed structurally disjoint from Wave C BrandSafety scoring; Phase 1–3 prior sign-offs still hold.

**The capstone surfaced two NEW gaps that task-scoped reviews structurally could not see — both now FIXED through the full Vikram → Kavya → Kabir re-confirm → Meera live-verify pipeline. I independently confirmed both fixes exist in code before folding them into this verdict:**

1. **HIGH re-escalation — `/webhooks/conversion` had no HMAC.** Confirmed fixed: `ConversionWebhookController` now requires an `X-Influora-Signature` HMAC-SHA256 (base64 over raw body) verified against a **per-workspace, server-generated secret** (`ConversionWebhookSecretService`, brand fetches once via an authed endpoint), rejecting uniformly on unknown workspace / no-secret / bad-signature. Migration **V31** (`V31__conversion_webhook_secrets.sql`) live-verified with a UNIQUE-per-workspace secret constraint. Body binding changed to raw string so HMAC sees exact bytes. This is the correct trust model for a public money-adjacent webhook and matches the discipline of the D-wave webhooks.

2. **MEDIUM — open redirect on public `GET /track/click/{utmCampaignId}`.** Confirmed fixed: `CampaignLinkService.validateBaseUrl` is now the single choke point, rejecting non-`http(s)` schemes (`javascript:`/`data:`/`file:`) and scheme-less values at *creation* time with a typed `INVALID_BASE_URL` (400). Reasoned decision to allow plain `http` (same-scheme redirect, no mixed-content concern) — the property enforced is exactly the one that matters: no non-http(s) scheme can reach the `Location` header. Kabir empirically probed ~25 adversarial URIs for bypass; none found.

**Architectural verdict on the two new fixes:** both are sound and correctly placed (validation at the write boundary for the redirect; signature verification before dispatch for the webhook). They do not change the shape of the system — they close two holes in the *existing* public surface. **My GO-WITH-CONDITIONS stands as final; no NEW un-closed CRIT/HIGH survives E4.**

### The one systemic lesson worth recording (debt CLASS, not debt item)

Finding #1's root cause is architecturally more instructive than the fix. The E2 review *downgraded* the conversion-webhook amount-entropy risk on an **explicit condition** — "D1's HMAC lands before this endpoint feeds any payout/ranking decision; re-escalates to HIGH if D1 slips." D1 then hardened only its *own new* Shopify route and never touched the pre-existing `ConversionWebhookController`. The condition silently went unmet, and only the cross-phase capstone caught it. **This is a debt class: a conditional accepted-risk whose precondition is owned by a *different, later* task that has no obligation to know the condition exists.** Accepting a risk "conditional on future task X" creates an invisible coupling that no single task-scoped review can see. **Standing process change (my ruling, binding going forward):** any accepted-risk downgrade that is *conditional on a future task* MUST (a) name the specific task/PR that satisfies it, and (b) be logged as an explicit checklist item on that task's acceptance criteria — not left as prose in a review note. A conditional downgrade with no tracked satisfaction hook is not an acceptable close. This is now the second capstone-only catch this build (alongside C4's write/read-shape retrospective) that justifies the holistic pass as a permanent gate, not a one-off.

---

## 4. Conditions on the GO (for Swapnil's E7 review)

None of these require re-architecting anything. They are the honest gate list.

1. **E-JWKS (agent-buildable, hard E7 blocker):** Spring asymmetric service-token signing must land before launch, covering BOTH C3 brand-safety AND the pre-existing Meera stream-token flow, asymmetric-only (no HS256 shared secret). Owner: Vikram, Kabir load-bearing.
2. **E5 (human gate — Swapnil):** real Meta/Razorpay/MSG91/R2 credentials. No agent substitute exists.
3. **E4 — SATISFIED.** Kabir returned SIGN OFF WITH CONDITIONS with no un-closed net-new CRIT/HIGH; the two capstone-only findings (conversion-webhook HMAC, tracking-link open redirect) are both fixed and closed through the full pipeline (V31 live-verified). No residual E4 blocker (§3).
4. **E3 CI must get one green run against a real Docker daemon** before it is described anywhere as an active safety net. Not an E7 blocker; a truth-in-labeling condition.
5. **D-wave/D4 disbursement, D5 read endpoints, and the E2 `result_digest` javadoc note** are tracked post-launch follow-ups — explicitly NOT E7 blockers, but they must stay on the backlog and not be silently dropped.

---

## 5. What I am NOT signing off on (unchanged from the plan)

- Any new npm/Maven dependency without logging in `wiki/tech/approved-deps.md` (my sign-off). E3's Testcontainers dep is the last one I approved, conditionally.
- Any deviation from MySQL / Vite-React-Router / resolve-then-scope / `executeOnce` — all four verified held; any future deviation needs a fresh CTO decision.
- Relaxing `ALLOWED_ALGS` or making `StaticDevJwksSource` reachable outside local dev — CTO+CISO-gated (per the JWKS ADR).

---

## Bottom line

The engineering across all five waves is **architecturally sound, internally consistent, and disciplined** — the standing rules were genuinely honored throughout (I checked the code, not just the notes), the deferred boundaries are deliberate and documented-in-code rather than accidental, and the review pipeline demonstrably caught real defects. Subject to §4's conditions — chiefly E-JWKS, E5 credentials, and a clean E4 — **this build's engineering work is complete and ready for Swapnil's E7 launch-approval review.**
