# Influora: what we can tell investors today

**Prepared by:** Riya (investor communication), with Rohan's CFO review
**Date:** 2026-09-19
**Subject:** Release candidate `115f698`, the go-live audit of 2026-09-19
**CEO decision on this RC:** **NO-GO**. Overall evidence level **E3**, overall confidence **75** (`final-decision.json`)
**Sources used:** only `audit-2026-09-19/_raw/*.json`: `merged-register.json` (EV IDs), `verification-and-review.json` (adversarial verdicts), `final-decision.json`, `inventory.json`, `q-answers.json`, the specialist and blueprint files. We spot-checked some details against the RC checkout (`rc-115f698`), namely fee basis points and plan seeds in `V41`, `V42` and `V55` and the AI ceiling values in the prod compose file.

---

## How to read this document

- **EV-xxx** is an entry in the deduplicated evidence register (`merged-register.json`).
- For EV-001 to EV-036 we use the adversarial verifier's **final** severity, evidence score and confidence (`verification-and-review.json`), not the original finder's. **EV-034 was refuted and is excluded** as a risk. **EV-026 is unresolved and out of RC scope.**
- **E (0-5)** is the register's evidence score. E5 means the issue was reproduced in code and at runtime. E4 means code plus an executed test, or two independent traces. E3 means a direct code trace. E2 or lower means partial, inferred or configuration-dependent evidence. **C (0-100)** is confidence.
- **"Register feature row"** points to the `features[]` table in `merged-register.json`. Those rows have no EV ID of their own, so we cite the row name and its E/C.
- **One caveat applies to everything below.** The backend in this RC **cannot boot** (EV-001, E5/C98, reproduced at runtime twice). So no capability listed here has served a real request on this RC. "Verified" means one of three things: executed tests, a clean build or migration, or a runtime check of a non-backend component. It never means a live end-to-end user journey.

### What the inputs do NOT contain (say this plainly to investors)

The audit inputs contain **no** revenue figures, customer or brand counts, creator counts, GMV, traction, growth rates, conversion rates, retention, market size, CAC/LTV or AI accuracy numbers. The CEO-lens audit states this explicitly: *"NOT INVENTED: no customer counts, revenue, traction or market size were assumed or reported"* (`swapnil.json`). None of these metrics may appear in investor material on the strength of this audit. Any such number would need its own evidence source outside this audit.

The inputs mention one live-money event: a Rs 1,000 wallet top-up credit on 2026-09-13. It appears only as a pointer to prior project memory (`blueprint-payouts-affiliate-commerce.json`, `rohan.json` ROH-02), and **this audit did not re-verify it**. It is not traction and must not be presented as traction.

---

## 1. Verified product capabilities

These capabilities are backed by executed tests, a runtime check, or a clean build or migration on this RC. We read each one against the NO-GO caveat above.

### 1a. Engineering foundation (runtime- or test-proven)

| Capability | What was actually proven | Evidence | E | C |
|---|---|---|---|---|
| Size of the built product | 91 REST controllers and 342 backend endpoints under `/api/v1`, 114 frontend routes, 10 AI-service routes and 24 scheduled jobs | `inventory.json` (controller, endpoint, route and job counts, from a regex scan cross-checked against the RC) | 3 | 90 |
| Backend unit-test suite | `mvn clean test`: 3,621 tests run, 0 failures, 22 skipped (all 22 are Docker/Testcontainers suites) | `final-decision.json` Backend and Testing matrix; register feature row "Backend build + unit/integration test suite" (priya) | 4 | 88 |
| Database schema | All 136 Flyway migrations applied cleanly to a real MySQL 8 instance | `final-decision.json` Database matrix and `can_launch` (meera-run, runtime) | 4 | 80 |
| Frontend build and type safety | `tsc --noEmit` is clean and runs as its own CI gate; `vite build` exits 0 | **EV-118** (positive); register feature row "Frontend production build (vite)" E4/C88 | 4 | 95 |
| Frontend-backend contract discipline | 194 distinct API calls in the main client were diffed against the backend route inventory: 0 mismatches | **EV-131** (positive) | 3 | 80 |
| Client-side money kill switch | With payments disabled, withdraw, escrow-fund and top-up never send a network request (9/9 runtime tests) | **EV-120** (positive) | 4 | 93 |
| AI service test suite | pytest 874/875. The one failure is a timing-flaky heartbeat test (EV-112) | register feature row "influora-ai test suite" E4/C82; **EV-112** | 4 | 82 |
| AI service route registration | Runtime import lists `/chat`, `/analyze-site`, `/voice/*`, brand-safety, creator-suggestion and TrendSpark routes | register feature row "influora-ai service routes" E4/C85 | 4 | 85 |
| Proxy-IP spoofing defence | The suspected rate-limit bypass (EV-034) was **refuted**: the fix is present and 3/3 regression tests were executed | **EV-034 REFUTED** (verifier E4/C90) | 4 | 90 |

### 1b. Product features: code-complete with passing unit tests, but never exercised end to end on this RC

| Capability | Status | Evidence | E | C |
|---|---|---|---|---|
| Brand workspace team management (invite, accept, remove, revoke, switch) | WORKING (code plus tests) | register feature row "Team members" (blueprint-brand-workspace-team) | 5 | 92 |
| Creator deliverables (upload, submit, proof, mark-posted, verify) | WORKING (code plus tests) | register feature row "Creator deliverables" | 5 | 90 |
| Deal-room messaging with live stream | WORKING (code plus tests) | register feature row "Deal-room messaging incl. SSE stream" | 4 | 90 |
| Campaign templates (plan-gated) | WORKING | register feature row "Campaign templates" | 4 | 90 |
| Tracking links and campaign coupons | WORKING | register feature row "Tracking links + campaign coupons" | 4 | 90 |
| Signed-webhook verification (Razorpay HMAC plus amount check; Shopify, WooCommerce, conversion and Meta webhooks) | WORKING; verifier test suites green | register feature row "Razorpay webhook receiver + signature" E4/C83; `kabir.json` features (E3/C80) | 4 | 83 |
| Two-way reviews; dispute resolution (admin) | WORKING | register feature rows "Two-way reviews" and "Dispute resolution" | 4 | 80 |
| Admin operations (brand/creator management, moderation, finance console, audit log) | WORKING | register feature rows (blueprint-admin-moderation-ops) | 4 | 80-85 |
| Refresh-token rotation with HttpOnly cookie | VERIFIED WORKING. Reuse detection is missing (**EV-129**) | register feature row (kabir; AuthServiceTest 64/64) | 3 | 75 |
| File upload with type sniffing, size limits, malware scan and private KYC storage | VERIFIED WORKING | register feature row (kabir; UploadServiceTest, ClamAV tests) | 3 | 75 |
| Client crash reporting | WORKING | register feature row "Client crash reporting" | 5 | 92 |

### 1c. AI assistant (Meera) safety architecture: verified in code and tests only

| Capability | Status | Evidence | E | C |
|---|---|---|---|---|
| Tool schemas are valid for the model provider (no schema combinators) | VERIFIED WORKING | register feature row "Meera tool schemas" (ash) | 4 | 90 |
| Money-moving tools excluded from the AI; the AI acts only through a scoped on-behalf token | VERIFIED WORKING in unit tests. **However, EV-004 (P0) lets any logged-in user bypass the internal service gate**, so this control is not sound end to end on this RC | register feature row "Money-tool exclusion + on-behalf scoped token" E4/C85; **EV-004** (P0, E3/C82) | 4 | 85 |
| Prompt-injection isolation (untrusted text wrapped; replayed history cannot forge tool results) | VERIFIED WORKING in tests. Whether the model actually adheres is unmeasured | register feature row (ash) | 3 | 75 |
| Budget calculator tool (rate-band based; returns no numbers when data is insufficient) | VERIFIED WORKING (24/24 tests) | register feature row "calculate_budget tool" | 3 | 75 |

**What counts as verified here:** the product is broad, the codebase is type-clean and has large unit-test suites, the schema applies cleanly, and the AI layer has deliberate safety rails. **What does not count as verified:** that any user journey works in production today.

---

## 2. Partially built (code exists; broken, off by default, or unproven)

| Area | What exists | What blocks it | Evidence (final severity, E/C) |
|---|---|---|---|
| **Backend as a whole** | A full Spring backend | The app does not start: a bean-wiring error in TrendPullJob | **EV-001** P0, E5/C98 |
| **Escrow-style payment protection** | A real fund, release and refund state machine on an internal double-entry ledger (`tejas.json`, E3/C65) | Re-funding a funded or released milestone double-debits (**EV-002** P0, E4/C85). A brand can refund after approval, with no dispute, on 100% of milestones (**EV-015** P0, E4/C88). Approval never releases money (**EV-019** P1, E4/C93, runtime-confirmed). Any member, including VIEWER, can approve (**EV-021** P1, E3/C92). No record of which member moved money (**XR-KABIR-01** P1, E3/C82) | see row |
| **Campaign publish with fee** | 10% publish fee charged when a campaign goes ACTIVE | A campaign can be created directly as ACTIVE with no funded escrow and no fee | **EV-005** P0, E3/C88 |
| **Creator payouts** | RazorpayX withdrawal backend, bank accounts, manual admin payout with UTR | Every frontend withdrawal fails because the key is longer than VARCHAR(64) (**EV-014** P1, E4/C88). The gateway call runs inside the DB transaction (**EV-003** P1, E4/C75, evidence disputed). Payouts are manual and the flag is enforced only in the UI (**EV-020** P1, E3/C90) | see row |
| **Affiliate commissions** | Shopify/WooCommerce attribution, monthly settlement job | Every ledger posting fails on an ENUM (**EV-011** P1, E4/C90, runtime). Credits go to the wrong wallet ID (**EV-025** P1, E3/C85). Funded from the platform wallet with no brand-side debit, so the liability is uncapped (**EV-033** P1, E3/C82) | see row |
| **Meera brand funding flow** | Pay and confirm UI inside Meera | Hardcoded demo campaign ID, and a link to a route that does not exist | **EV-024** P1, E4/C90 |
| **Meera AI campaign creation and launch** | Draft tool, human-confirmation launch gate | Configuration-dependent. The launch path skips validator gates (**EV-074** P2). Writes are not atomic (**EV-037** P2, E5/C90) | register feature row E3/C69 |
| **Creator-verified analytics** | Meta-synced stats with a "verified" flag | Self-declared follower counts (up to 1B) were shown as Meta-verified. Fixed after the RC in `e67dc5b`/`1f1bf9f`; **not re-verified** | **EV-008** P0, E3/C82 |
| **Brand KYC activation** | KYC submission and admin approval gate | Admins cannot view uploaded documents and are not notified | **EV-016** P1, E3/C85 |
| **Creator identity KYC** | Fields exist | Self-declared; nothing ever sets VERIFIED | **EV-058** P2, E3/C88 |
| **User support** | Complete, tested support-ticket API | No UI: `/support` is a placeholder | **EV-012** P1, E4/C85 |
| **TrendSpark (trend ingestion)** | Java job plus AI tagger | Off everywhere, fails closed without config, and is also blocked by EV-001 | **EV-013** P1, E5/C92 |
| **Creator Copilot** | Daily suggestion with a content filter (454 tests) | Batch jobs off by default | register feature row E4/C63; **EV-068** P2 |
| **Brand-safety (GARM) scoring** | Scoring service and AI client | Off by default. The 25-item batch likely cannot fit the token and timeout budget | register feature row E3/C58; **EV-091** P2 |
| **Pro subscription billing** | Checkout, dunning, renewal jobs (68 subscription tests) | Billing controller has one unrelated test; money jobs untested | `swapnil.json` E3/C60; **EV-073**, **EV-048** P2 |
| **AI credit meter / paywall UI** | Fully built | Never rendered in the live Meera page | **EV-065** P2 |
| **Redis caching** | `@Cacheable` paths | Redis host never wired for the API in any prod compose file | **EV-017** P1, E3/C75 |
| **GST invoices** | Commission and campaign invoice PDFs, CGST/SGST/IGST split | Not traced end to end. Blank GSTIN defaults to IGST | `rohan.json` E2/C70; **EV-168** P3 |

---

## 3. Roadmap (as decided, with no dates)

The inputs give an order of work and gates but **no dates or durations**. Do not attach timelines without a separate plan.

1. **Immediately, independent of any release.** Rotate and purge every committed third-party key (**EV-006**). Correct the live marketing and legal copy: custody/RBI-PA claims, "guaranteed payment", TDS/Form 16A (**EV-007**, **XR-ROHAN-01**, **EV-031**). Source: `final-decision.json` `can_launch`, `must_fix_first` #2 and #8.
2. **Fix every P0, and the P1s in the decided order.** Boot fix plus a context smoke test (**EV-001**, **XR-PRIYA-02**). Milestone fund guard (**EV-002**). No refund after approval (**EV-015**). Role gate on approve **before** auto-release (**EV-021**, then **EV-019**). Gate the create path (**EV-005**). Internal-path decoding (**EV-004**, **XR-PRIYA-01**). Re-verify **EV-008**. Then KYC tooling, a server-side payouts flag, and disabling affiliate settlement until it is fixed. Source: `must_fix_first` #1-#13.
3. **Launch conditions.** Restore-tested ledger backups (**EV-093**). A clean RC cut from committed code (**EV-018**). A real green CI run that includes the Testcontainers suites (**EV-038**). One full fund, approve, release, refund cycle on staging, executed rather than reviewed. Source: `must_fix_first` #14.
4. **Limited invite-only beta**, only after steps 2-3. Hand-picked brands and creators, same-day ops SLA for KYC and payouts, creator withdrawals off server-side, affiliate off, TrendSpark out of scope, **no paid acquisition**. The beta exists to test the untested business assumptions: take rate, and the ops load of manual KYC and payouts. Source: `can_launch` #5, Business matrix.
5. **Before open launch.** Per-workspace AI cap (**EV-044**), gateway-fee accounting (**EV-090**), chargeback handling (**EV-094**), outbound HTTP timeouts (**EV-045**), code splitting (**EV-039**), dependency upgrades (**EV-054**, **EV-078**), and a decision on separating the Razorpay account from the other product (**EV-144**). Source: `can_wait`.

---

## 4. Claims that must NOT be made to investors (or anywhere)

| Do not say | Why | Evidence |
|---|---|---|
| "We are live / launched / production-ready" (for this build) | This RC cannot boot. The CEO decision is NO-GO, and a limited beta is also unavailable on it | **EV-001** (E5/C98); `final-decision.json` |
| "Brand funds are held by a licensed, RBI-authorized Payment Aggregator" / "we never pool funds" | The code pools funds in an Influora-owned clearing wallet. There is no PA licence, and that decision is deferred | **EV-007** P0 (E3/C85; Rohan/Tejas argue E5); `final-decision.json` Business |
| "Guaranteed payment" / "creators are payment-protected" | A brand can refund a funded hold after approval, and a re-fund can double-debit | **XR-ROHAN-01**, **EV-015**, **EV-002** |
| "TDS deducted automatically" / "Form 16A issued" | TDS is set only on manual admin payouts, and no Form 16A code exists | **EV-031** P1 (E3/C88) |
| "Creators are paid within 24 hours" / "self-serve payouts" | No operational evidence. Withdrawals are off and payouts are manual | **EV-104**, **EV-020**, **EV-014** |
| "Payment releases automatically on approval" | Approval never releases; the brand must release separately | **EV-019** (runtime-confirmed) |
| "Meta-verified creator metrics" (without qualification) | On the RC, self-declared counts displayed as verified. The fix landed after the RC and is not re-verified | **EV-008** P0 |
| "Approved by Meta App Review" as a standing fact | Time-bound platform status this RC cannot confirm | **EV-085** |
| Any AI accuracy, quality, brand-safety precision or "AI-driven outcome" figures | No live-model evals. 2 of 7 datasets have 0 fixtures. Brand-safety fixtures look hand-authored. Brand-safety scoring is off by default | **EV-057** (E4/C80), **EV-091** |
| "Creator data stays in India" | The approved-regions config is not enforced; creator financial context goes to the provider's default endpoint | **EV-139** |
| "Enterprise-grade / horizontally scalable / monitored / backed up" | Single-instance design, one AI worker, no metrics or alerting, no DB backups | **EV-062**, **EV-157**, **EV-088**, **EV-093** |
| "Security-audited / secure" | Open P0s: committed live-format keys and an internal-gate bypass | **EV-006**, **EV-004** |
| "3,600+ tests pass, so the money flows are correct" | Several green money tests mock the exact layer where the bug lives. The 22 real-DB suites were skipped locally, and CI status was not observed | **EV-038**, **EV-011**, **EV-014**, **EV-025**; `final-decision.json` confidence limits |
| Any customer, brand, creator, revenue, GMV, growth or conversion number | None exists in the audit inputs. Earlier homepage figures (creator count, amount paid, payout time) were **removed from the site as unsupported** and must not be reintroduced | `swapnil.json`; `tejas.json` (proof-points removal) |
| Market size / TAM | Not evidenced anywhere in the inputs | none |
| "Fully automated / self-serve marketplace" | Three manual or trust gates on the core money journey. The marketplace starts empty and depends on manual admin import | Product matrix; **EV-016**, **EV-019**, **EV-020**, **EV-075** |
| "Social scheduling (Postiz)" / "newsletter" | Not implemented in code | **EV-107**, **EV-049** |
| "Our take rate is validated" | About 25% combined take and a non-refundable fee on max budget are **untested assumptions** | **EV-087**; Business matrix |
| "Dedicated payment infrastructure" | Razorpay account and VPS are shared with another product (severity disputed: P3 vs P1) | **EV-144** |

---

## 5. Technical moat: implemented vs asserted

**Honest framing.** What is implemented today is engineering depth and breadth of integration. The audit found **no proprietary data asset, network effect or AI performance advantage** that is evidenced. Present the moat as "a lot of hard, India-specific plumbing already built", not as defensibility we can prove.

| Moat element | Status | Evidence |
|---|---|---|
| AI assistant (Meera) integrated with the transactional backend through typed tools, with money tools excluded and a human confirmation step before launch | **Implemented** (tests). Undermined on this RC by **EV-004** (P0) and **EV-074** | register feature rows (ash) E4/C85-90; `tejas.json` claim #9 (E3/C70) |
| Layered AI cost governance (global daily ceiling, kill switch, per-creator monthly cap, cache-aware pricing, single-worker guard) | **Implemented in code and config; not proven at runtime** | `rohan.json` (E3/C80 for the ceiling; E2 for the other parts) |
| Ledger-based escrow state machine with double-entry postings | **Implemented, with P0 integrity defects** | `tejas.json` feature (E3/C65); **EV-002**, **EV-015** |
| Server-authoritative HYPE campaign economics (slot x rate computed on the server) | **Implemented, not verified** | `tejas.json` (E3/C60) |
| Signature-verified store sales attribution (Shopify, WooCommerce, conversion webhooks) | **Implemented, tests green** | `kabir.json` (E3/C80); `tejas.json` claim #12 |
| Meta-synced creator stats as a trust layer | **Implemented but compromised on the RC**; fix not re-verified | **EV-008** |
| India compliance plumbing (GST split, KYC flows, DPDP consent gate for creator AI) | **Partial.** GST split implemented (E2/C70) with an IGST-default bug; KYC not reviewable or self-declared; consent gate present | `rohan.json`; **EV-168**, **EV-016**, **EV-058**, **EV-139** |
| "Learning" or "outcome-optimising" AI / data flywheel | **Asserted, not evidenced.** The outcome-recommendation and campaign-performance eval datasets have 0 fixtures | **EV-057** |
| Marketplace liquidity / network effects | **Not evidenced.** The marketplace starts empty; supply depends on manual import | **EV-075** |
| Trend intelligence (TrendSpark) | **Not operating.** Off everywhere and blocked by EV-001 | **EV-013** |

---

## 6. Unit-economics structure implemented in code (no volumes)

This is **the structure only**. It shows what the code charges and what it costs per unit where the code defines it. We have no volumes, so there are **no revenue, margin or GMV figures**. Nothing here was proven against a real transaction on this RC.

### 6a. Revenue levers in code

| Lever | Value in code | Where | Caveat |
|---|---|---|---|
| Brand publish fee | **10%** (1,000 bps) of the campaign's **budgetMax**, charged at the ACTIVE transition, **non-refundable** | `V42__platform_fee_config_brand_fee_razorpay.sql` (`brand_fee_bps` 1000); **EV-087** | Can be skipped entirely through the create path (**EV-005**) |
| Brand publish fee, Pro plan | **7%** (700 bps) | `V55__seed_billing_plans.sql` (PRO `fee_bps` 700) | Brand campaign-detail screen shows 10% to Pro brands (**EV-060**) |
| Creator commission | **15%** (1,500 bps) deducted at release | `V41__platform_fee_config.sql` (`default_fee_bps` 1500); `rohan.json` | Admin-editable, but the creator UI hardcodes 15% (**EV-060**) |
| Implied combined take | About 25% (10% + 15%) | **EV-087** (derived) | **Untested business assumption** (Business matrix) |
| Pro subscription | **Rs 4,999 / month**; 400 AI credits; 5 seats | `V55` (price 499900 in paise); **EV-100** (three copies consistent in this snapshot) | Razorpay plan config not independently checked |
| Free plan | Rs 0; 100 AI credits; 1 seat; 5 tracked creators | `V55` FREE row | none |
| AI credit plans | Present in code | `swapnil.json` revenue streams | Credit meter UI never rendered (**EV-065**) |

**Fee-consistency risk:** the platform fee resolves from four divergent sources: yml 15%, DB 10%, a Pro override, and a frontend hardcode of 15%. As a result, Meera quotes brands a 15% fee while the publish fee is 10% (**EV-060**, E3/C85). Fix this before any pricing is shown to investors as "the model".

### 6b. Cost structure in code

| Cost line | What the code does | Evidence |
|---|---|---|
| Payment gateway fees (Razorpay, RazorpayX) | **Not modelled anywhere.** A CEO ruling says the platform absorbs them (a V42 flag), but no code nets them against revenue. On a refund, the original top-up fee is a sunk cost | **EV-090** (E2/C70); `rohan.json` |
| Global AI spend ceiling | **$15/day**, checked before every provider call on all AI routes, plus a kill switch. Set in all three prod compose files (confirmed at `docker-compose.hostinger.yml:418`). By construction this bounds AI spend to about $450 per 30 days while the gate holds (arithmetic on config, not an observed bill) | `rohan.json` (E3/C80, static); **not runtime-proven** |
| Behaviour when Redis is down | Degrades to per-process counters by design. A past incident allowed about $27 of spend against the $15 cap before the fix | `q-answers.json` (Redis question, E3/C85) |
| Per-creator AI cap | **$0.75 / creator / month**, blocking and checked before the provider call | `rohan.json` (E2/C70); `docker-compose.hostinger.yml:426` |
| Per-workspace AI cap | $3/day soft cap is **warning-only**. The blocking hard cap is **unset in every prod deploy**, so one workspace can exhaust the global ceiling | **EV-044** P2 (E5/C80) |
| Model mix | Chat and brand-safety run on a Sonnet-class model; nudges, tagging and copilot on a Haiku-class model; site analysis and voice cleanup on Gemini Flash; Sarvam for speech-to-text and text-to-speech | `ash.json` model inventory |
| Unit-cost illustrations | Simple text turn about $0.0064; worst-case 6-iteration turn about $0.02-0.04. These are **labelled ASSUMPTION by Rohan**, use list prices hardcoded in 2026-07, and are not measured | `rohan.json` cost-per-workflow table |
| Storage | Per-file 500 MB video cap only; no per-workspace quota (low severity) | **EV-174** (E1/C40) |

**CFO note (Rohan):** investors can see the *mechanics* of the model: fee on publish, commission on release, a subscription tier, and AI credits under a hard daily ceiling. We cannot show contribution margin. Gateway fees are unmodelled (**EV-090**), affiliate commissions currently create an uncapped platform liability (**EV-033**), and there is no transaction volume to apply the fees to.

---

## 7. Key risks and mitigation status

"Planned" means a fix is defined in `final-decision.json` `must_fix_first` or `required_action`, but nothing in the inputs shows it implemented. We do not claim any fix is done unless an input says so.

| Risk | Final severity (E/C) | Mitigation status |
|---|---|---|
| Backend cannot boot | P0 (E5/C98) **EV-001** | Planned: constructor fix plus a Docker-free context smoke test (**XR-PRIYA-02**) |
| Committed live-format third-party keys in git history | P0 (E3/C78) **EV-006** | Ordered as a same-day action, independent of release. **Completion not evidenced in the inputs** |
| Regulatory misstatement on custody (RBI-PA, "never pools") live on the site | P0 (E3/C85) **EV-007**; **XR-ROHAN-01** P1 | Ordered as a copy-only fix on the live site. Completion not evidenced. No PA licence (deferred by standing decision); legal opinion on stored-value wallet exposure recommended |
| Double-fund, self-refund after approval, publish without escrow/fee | P0 **EV-002** (E4/C85), **EV-015** (E4/C88), **EV-005** (E3/C88) | Planned, each with a regression test |
| Internal service gate bypass (any logged-in user) | P0 (E3/C82) **EV-004** + **XR-PRIYA-01** | Planned: shared path-decoding utility plus an architecture test |
| Self-declared metrics shown as verified | P0 (E3/C82) **EV-008** | **Fixed after the RC** (`e67dc5b`, `1f1bf9f`); **not re-verified** |
| Money-out failures (withdrawal key length, gateway call inside the transaction, UI-only payouts flag) | P1 **EV-014**, **EV-003**, **EV-020** | Mitigated operationally: withdrawals off and payouts manual. Server-side flag planned |
| Affiliate liability uncapped and posting broken | P1 **EV-011**, **EV-025**, **EV-033** | Planned: disable the settlement job until fixed |
| VIEWER-triggered release; no actor audit trail on money moves | P1 **EV-021**, **XR-KABIR-01** | Planned (sequenced before **EV-019**) |
| Ops dependency: manual KYC with unviewable docs, manual payouts | P1 **EV-016**, **EV-020** | Planned: KYC tooling plus a same-day ops SLA for the beta |
| No ledger backups; no metrics or alerting | P2 **EV-093** (CEO-elevated to a launch condition), **EV-088** | Planned |
| Release process: images published untested as `:latest`; live stack runs an untracked compose file; main is dirty under concurrent sessions | P2 **EV-063**; **EV-010** (downgraded to E2/C30); P1 **EV-018** (main-tree scope) | Planned: gated, SHA-tagged images; commit the live compose file; freeze main |
| Test evidence weaker than headline pass counts | P2 **EV-038** | Planned: attach a real CI run with the Testcontainers suites |
| AI tenant fairness, and AI quality unmeasured | P2 **EV-044**, **EV-057** | Deferred to before open launch |
| Data residency of creator data sent to AI providers | P3 **EV-139** | Not scheduled. Consent gate and PII allow-lists present |
| Shared Razorpay account and VPS with another product | P3 vs P1 (disputed) **EV-144** | Decision needed before scale or any PA application |
| Platform dependencies: Spring Boot out of OSS support; AI dependency advisories; Meta App Review standing | P2 **EV-054**, **EV-078**; **EV-085** | Upgrade scheduled "this quarter" per `can_wait`. Meta standing unverified |
| Business model assumptions (about 25% take; non-refundable fee; creators accept brand-click release) | Business matrix E2/C60 | Untested. The invite-only beta is designed to test them |

---

## 8. Data-room checklist

Status means: **Exists** (in the audit inputs; redact before sharing), **Produce** (needs work before it can go in the room), **None** (does not exist per the inputs). Items the audit did not cover are marked "Out of audit scope". This document does not assert their state.

| # | Item an investor will ask for | Status | Source / what is needed |
|---|---|---|---|
| 1 | Independent technical audit with findings register | **Exists** | `merged-register.json`, `verification-and-review.json`, `final-decision.json`. **Redact first:** EV-006 evidence describes secret shape metadata. No values are in this memo, but review the raw files before sharing |
| 2 | Go/no-go decision and readiness matrix | **Exists** | `final-decision.json` (NO-GO, E3/C75) |
| 3 | Architecture overview (current vs recommended) | **Exists** | `priya.json` extra |
| 4 | Test-run evidence | **Exists (partial)** | meera-run: 3,621 Java; vitest 1,348/2; pytest 874/875; 136 migrations. **Produce:** a real CI run with the Testcontainers suites (**EV-038**); no agent pulled GitHub Actions history |
| 5 | Security review and remediation proof | **Produce** | Kabir review exists. Proof of key rotation and history purge (**EV-006**) and a post-fix re-test of **EV-004** are needed |
| 6 | Staging proof of the full money cycle (fund, approve, release, refund) | **None** | Required launch condition (`must_fix_first` #14) |
| 7 | Payment-custody legal opinion; PA licensing position | **None** | Recommended in the Business matrix. Must align with corrected copy (**EV-007**) |
| 8 | Final (non-draft) Terms and refund policy | **Produce** | Refund policy is a visible v0 draft (**EV-028**, P2) |
| 9 | Razorpay / RazorpayX agreements; entity separation from the other product | **Out of audit scope / decision pending** | **EV-144** |
| 10 | Meta App Review approval record (current) | **Produce** | **EV-085**; live standing unverified |
| 11 | Unit-economics model with gateway fees | **Produce** | Fee structure in §6. Gateway fees unmodelled (**EV-090**) |
| 12 | Actual AI provider bills vs the $15/day ceiling | **Out of audit scope** | Not in the inputs; the ceiling is proven only statically |
| 13 | AI evaluation results (quality, safety, rails adherence) | **Produce** | Offline evals: 5 of 7 datasets pass; 2 have 0 fixtures; no live-model evals (**EV-057**) |
| 14 | Backup and restore test for the wallet/escrow ledger | **None** | **EV-093** |
| 15 | Monitoring, alerting and incident runbook | **None** | **EV-088** |
| 16 | Reproducible deployment (committed live compose file, SHA-pinned images) | **Produce** | **EV-010**, **EV-063** |
| 17 | Customer, traction, revenue, cohort and pipeline data | **None in the inputs** | Must come from a separate, evidenced source, if any exists |
| 18 | Market sizing | **None in the inputs** | External research required |
| 19 | Cap table, incorporation, IP assignment, team | **Out of audit scope** | Not covered by this audit |
| 20 | DPDP / privacy compliance pack (consent, data residency, processors) | **Produce** | Consent gate exists; data-residency gap (**EV-139**); plaintext creator GSTIN/PAN (**EV-069**) |

---

## 9. Suggested 10-slide investor narrative

**Positioning:** pre-launch, product substantially built, with an honest readiness picture. Every slide below names its evidence. Wherever a slide would normally carry a number the inputs do not have, the slide says so. **Do not fill those gaps with estimates.**

**Slide 1: The problem we are solving**
- Content: brand-creator collaborations in India need negotiation, contracts, payment protection and proof of results in one place. Describe the mechanism, not the market.
- Evidence: the product scope in `inventory.json` (domains) and the brand/creator journeys in `swapnil.json` extra.
- Leave out: market size or TAM. None is evidenced; add an external source or omit it.

**Slide 2: The product loop**
- Content: brand signup, KYC, wallet top-up, publish, discover or invite, deal room, e-signed contract, milestone funding, deliverable, approval, release. Label the three manual steps: KYC approval, a separate release click, and manual payout.
- Evidence: `swapnil.json` extra (code-verified journey); Product matrix; **EV-016**, **EV-019**, **EV-020**.
- Leave out: "fully automated" or "self-serve end to end".

**Slide 3: Breadth already built**
- Content: 91 controllers, 342 endpoints, 114 frontend routes, 10 AI routes, 24 scheduled jobs. Team workspaces, deal room, deliverables, reviews, disputes, admin console, Shopify/WooCommerce attribution.
- Evidence: `inventory.json` (E3/C90); register feature rows in §1b.
- Leave out: "live" or "in production" for this build (**EV-001**).

**Slide 4: Meera, the AI inside the workflow**
- Content: an assistant that drafts campaigns and calculates budgets through typed tools. Money tools are excluded from the AI, a human confirms money steps, prompt-injection isolation is in place, and creator AI requires DPDP consent.
- Evidence: register feature rows (ash) E3-E4/C75-90; `tejas.json` claim #9. Known gaps: **EV-004**, **EV-024**, **EV-057**.
- Leave out: any accuracy or outcome metric, "learns from outcomes", or "data flywheel".

**Slide 5: How money moves (the honest version)**
- Content: payments are collected through Razorpay. Influora records funds on an internal double-entry ledger and acts as a facilitator. The commission is taken at release.
- Evidence: **EV-007** (what the code actually does); `tejas.json` claim #22 (facilitator language); `final-decision.json` Business matrix (no PA licence, deferred).
- Leave out: RBI-PA custody, "never pools", "guaranteed payment", or automated TDS (**EV-007**, **XR-ROHAN-01**, **EV-031**).

**Slide 6: Business model as coded**
- Content: 10% publish fee on max budget (7% on Pro); 15% creator commission at release; Pro at Rs 4,999/month with 400 AI credits and 5 seats; AI credit plans.
- Evidence: `V41`, `V42` and `V55` migrations; **EV-087**; **EV-100**; `swapnil.json`.
- Label clearly: the roughly 25% combined take is an **untested assumption**, and the gateway fee is unmodelled (**EV-090**). Show no revenue, GMV or margin figures; none exist.

**Slide 7: Cost discipline built in**
- Content: a $15/day global AI ceiling with a kill switch, a $0.75/creator/month cap, and cache-aware cost estimation. Past cost-control incidents were fixed and are documented in code.
- Evidence: `rohan.json` (E3/C80 static); `q-answers.json` (Redis degrade behaviour); **EV-044** (open fairness gap).
- Label clearly: statically verified and not runtime-tripped. Per-turn costs are Rohan's ASSUMPTION estimates.

**Slide 8: Where we are, with an independent audit**
- Content: RC `115f698` is NO-GO. It does not boot, has 8 surviving P0s, and its test coverage is strong in unit tests but weak on real-DB money paths. Show the readiness matrix from `final-decision.json`.
- Evidence: `final-decision.json` (E3/C75; NO-GO confidence about 97); **EV-001**, **EV-002**, **EV-004**, **EV-005**, **EV-006**, **EV-007**, **EV-008**, **EV-015**.
- Why include it: a sophisticated investor will find these problems in diligence. Disclosing them first, with a fix plan, builds credibility.

**Slide 9: Path to a controlled beta**
- Content: fix the P0s and the sequenced P1s, cut a clean RC, prove boot and one full money cycle on staging, then run an invite-only beta with money-out and affiliate off and no paid acquisition. The beta tests take rate and ops load.
- Evidence: `final-decision.json` `must_fix_first` and `can_launch` #5; Business matrix.
- Leave out: dates or launch timelines. None are in the inputs.

**Slide 10: What we need, tied to the risks**
- Content: map the use of funds to evidenced gaps. Ops staffing for KYC and manual payouts (**EV-016**, **EV-020**). Legal counsel on custody, the refund policy and the PA route (**EV-007**, **EV-028**). Infrastructure for backups, monitoring and a reproducible deploy (**EV-093**, **EV-088**, **EV-010**). A payments and security hardening pass (**EV-002**, **EV-015**, **EV-004**).
- Evidence: §7 of this document.
- Leave out: an ask amount or runway, unless the founders supply them from their own financials. The inputs contain none.

---

## Sign-off notes

- **Riya:** every figure in this document traces to a named input file or EV ID. Where the inputs are silent, the document says so rather than estimating.
- **Rohan (CFO):** the fee and plan values were spot-checked in the RC migrations. The AI ceiling and creator cap were spot-checked in the prod compose file. Nothing here is a financial projection. Before any external use, three things need fixing: (1) the live custody copy (**EV-007**) must be corrected first, because investor material must not contradict the public site in either direction; (2) the fee-source divergence (**EV-060**) must be resolved; (3) gateway fees (**EV-090**) must be modelled.
- **Scope limits (from `final-decision.json`):** no end-to-end runtime proof of any money, auth or API flow exists for this RC. Live production env values were not inspected. The main working tree differs from the RC (**EV-018**), so the next RC's contents are not yet defined.
