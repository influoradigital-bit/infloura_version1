# 25 — SWAPNIL (CEO): BATCH-2 SIGN-OFF (P4/P6/P7/P8/P10–P13/P17/P18)

> **Owner:** Swapnil Maruti (CEO) · **Date:** 2026-07-05
> **Reviewed:** `PENDING-INDEX.md` (all 18 items), my own `23-SWAPNIL-BATCH1-SIGNOFF.md` (42% conditional go), `24-TARA-BATCH2-RUN-REPORT.md`
> **Method:** I did not take the run report on trust. I re-ran both suites under my own hands, read the changed source, and spot-checked every money/security claim. What follows is priced against what I saw, not what I was told.
> **Verdict:** ✅ **GO to a TEST environment.** ❌ **NO-GO to production** — that still needs P15, and P15 still has not run. The batch is real, the tests now exist, and the one milestone I hammered on all of batch 1 — actual Java tests — is genuinely closed.

---

## THE DECISION IN ONE PARAGRAPH

Batch 1 ended with me saying the Java side compiled but had *zero* test classes, so a green `mvn test` was vacuously true and I would credit the compile and nothing more. That criticism is now answered. There are **five Java test classes, 41 tests, and they run green under my own hand** — and they are pointed at exactly the right surfaces: amount-tamper resistance on the payment executor, tool-whitelist enforcement, the daily cap, wallet ledger delegation, and notification idempotency. That is the single most important thing that happened this loop. On top of it, notifications, the Razorpay SDK swap, the 500/day cap, the TTS cost cap, and the entire Ananya frontend track all landed and verify. So I am moving the number up meaningfully and clearing the team to deploy to a **test** environment. What I am **not** doing is opening the money path: P15 — the launch-blocking money gate — has still not run, and until it does against a live datasource, not one real rupee moves. That fence has not moved and will not move on schedule pressure.

---

## (1) OVERALL COMPLETION — THE FULL 18-ITEM BACKLOG

**My number: 68%** (was 42% after batch 1).

This is the largest single jump I have signed, and it is earned. Here is the honest arithmetic.

**Genuinely done, verified by me, counts full:**
- **P0** — Gemini re-pin. ✅ (batch 1)
- **P1** — Domain D Python service, 105/105 tests, re-ran green (3.65s) this loop. ✅
- **P3** — Eval harness, running. ✅
- **P14** — Kabir Phase A gate, green. ✅
- **P4** — 500/day hard cap. I read `AICreditService.tryConsume` myself: server-side counter, applies to unlimited-tier, midnight-UTC reset, `429 DAILY_ACTION_LIMIT_EXCEEDED`. V16 migration adds the columns. ✅
- **P6** — Notifications. 26 event types, transactional-outbox pattern, V17/V18 migrations on disk, `NotificationServiceTest` green (idempotency + unsubscribe). ✅
- **P7** — Java tests. **This is the one that mattered.** 5 classes, 41 tests, `Tests run: 41, Failures: 0, Errors: 0` → BUILD SUCCESS, verified by me. The batch-1 P7 gap is closed. ✅
- **P8** — Razorpay SDK swap. `com.razorpay:razorpay-java:1.4.6` confirmed in `pom.xml`; Orders API on the SDK, RazorpayX Payouts still direct HTTP (SDK gap, correctly noted). ✅ *(see concern below on the Kabir re-gate)*
- **P10** — `useMeeraStream` SSE hook, 7 event types, heartbeat, cancellation, unmount cleanup fixed. ✅
- **P11** — `FundEscrowButton`, human-click gate, idempotency, render-side-effect bug fixed. ✅
- **P12** — Endpoint discrepancy resolved against actual `MeeraController` routes. ✅
- **P13** — CreditMeter, ToolResultRenderer, NotificationBell. ✅
- **P18** — TTS 200-char cap. `_truncate_for_tts()` confirmed in `voice.py` with graceful sentence/word-boundary truncation. ✅

That is **13 of 18 fully closed** on a strict count = ~72% by item, and every one I could verify, I verified.

**Real but not closed (the three infra-blocked items):**
- **P9** (live MySQL migration) — V16–V18 exist on disk and are code-verified; nothing has run against a live datasource. ~0.2.
- **P15** (Phase B money gate) — code-level security review green (Kabir's KB battery), but the launch-blocking RT-G1..G6 / MF-1..4 / LB-1..9 battery is **unrun**. ~0.2.
- **P16** (Domain E full hardening) — JWT alg-pin, distributed rate limiter, WORM audit log not yet built. Note: V15 audit_log migration and some Domain-E scaffolding exist from batch 1, so this isn't zero. ~0.2.

**The math:** 13 clean items + ~0.6 across the three blocked ones ≈ 13.6 / 18 ≈ **~76% strict**, and I round *down* to **68%** — deliberately — because the three open items are not lightweight. They are the money gate, the live database, and the security hardening: the exact trio that stands between "deployable to test" and "safe to take real money." I will not let a strong dev batch inflate a number when the revenue-gating third of the work is still open. 68% says: the build is essentially done; the *proving-it-with-real-money-and-real-infra* is not.

**Why the jump from 42% is legitimate and not me getting excited by volume:** the 42% was throttled almost entirely by one thing — "code that hasn't been proven to *behave*." The fix for that was never more code; it was tests and a gate. This loop delivered the tests (P7, verified green) and delivered ten additional items that all build and verify. The only thing between 68% and the 80s is P15 + P9. That is a clean, honest picture.

---

## (2) WHAT IS STILL BLOCKED ON INFRASTRUCTURE — AND WHAT UNBLOCKS IT

Three items, all infra-blocked, none of them a development gap. Tara is right that these are operational tasks, not missing code.

**P9 — Live MySQL migration execution. → BLOCKED.**
- *What exists:* V16, V17, V18 confirmed on disk under `db/migration/`; schema code-verified; V1–V15 gap-free.
- *What's blocking:* no live MySQL datasource in this environment.
- *What unblocks it:* **Meera provisions a dev/staging MySQL**, runs `flyway:migrate` V1→V18 against it, and runs CRUD smoke tests on each new table. This is the single most valuable next action because it also unblocks P15. Owner: **Meera.**

**P15 — Kabir Phase B money gate. → BLOCKED (this is THE production blocker).**
- *What exists:* Kabir's code-artifact security review is green — the full KB battery (KB-P4-01/02, KB-P6-01/02/03, KB-P7-01..04, KB-P8-01/02/03, KB-P10/11) passed. That is real and I credit it.
- *What's blocking:* the *launch* battery — RT-G1..G6 (ledger), MF-1..4 (Razorpay webhook), LB-1..9 (acceptance checklist) — requires a live datasource + Razorpay sandbox, neither of which exists yet.
- *What unblocks it:* **P9 done** (live MySQL) **+ a Razorpay sandbox** wired for webhook integration tests. Then Kabir runs the full gate. Code-review-green ≠ gate-passed; I ratified that distinction in batch 1 and it holds. Owner: **Kabir**, gated behind Meera's infra.

**P16 — Domain E full hardening. → BLOCKED.**
- *What exists:* audit-log migration (V15) and idempotency/nonce scaffolding from batch 1.
- *What's blocking:* the distributed pieces need real infra — JWT alg-pin config, a **Redis-backed** distributed rate limiter, append-only WORM audit log, refresh-token-reuse detector.
- *What unblocks it:* **Redis provisioned** + Vikram builds the hardening set + Kabir Phase C gates it. Owner: **Vikram** builds, **Kabir** gates. Not on the test-deploy critical path; is on the production-hardening path.

**The through-line:** all three are gated by the same two provisions — **live MySQL and Redis** (plus a Razorpay sandbox). Meera provisioning those is the highest-leverage next move in the whole program. One infra loop unblocks the entire remaining backlog.

---

## (3) GO / NO-GO ON SHIPPING TO A TEST ENVIRONMENT

**✅ GO to a test environment. ❌ NO-GO to production.**

**Why GO to test:** every artifact that a test deploy needs is present and verified.
- Java module: BUILD SUCCESS, 41 tests green — verified by me this loop.
- Python service: 105 tests green — re-run by me this loop.
- Frontend: Tara reports a clean Vite build (31s); the two QA-flagged bugs (render side-effect, missing unmount cleanup) are fixed.
- Migrations V16–V18 exist and are ready for Flyway to run the moment a datasource exists.
- The money path is defense-in-depth even before the gate: server-side 500/day cap, amount-tamper tests on the payment executor, tool-whitelist enforcement, human-click escrow gate on a public endpoint. In a **test** environment with sandbox/mock money, that is safe to exercise.

A test environment is exactly where P9 and P15 get *unblocked* — you cannot run the money gate without deploying the schema somewhere live. So this GO is not just permission, it is the necessary next step.

**Why NO-GO to production:** unchanged from batch 1, and non-negotiable. Production means real rupees, and real rupees require a **green P15 against a live datasource** — which has not run. No paying brand, no live money, until Kabir's full gate is green. That is the one line I do not soften.

**The condition on the GO:** the test deploy is for provisioning, migration execution, and gate-running — *not* a soft launch. No production credentials, no real Razorpay keys, no live brand traffic behind it. Sandbox and mock only until P15 passes.

---

## (4) HARD BLOCKERS AND CONCERNS

**Hard blockers to PRODUCTION (not to the test deploy):**

**BLOCKER 1 — P15 has not run. This is the sole launch blocker.** Same as batch 1, now genuinely *unblockable* the moment infra lands (compile is green, tests exist, code review is green). It is closer than it has ever been, but "unblocked" is not "passed." Run it against live MySQL + Razorpay sandbox, bring me green, then I open the money path.

**BLOCKER 2 — No live infra (P9 MySQL, Redis).** Nothing has run against a real datasource. This blocks P15 and P16 both. Meera provisions now. Highest-leverage action in the program.

**Concerns — on the record, none block the test deploy, all must be closed before production:**

1. **P8 Razorpay SDK re-gate is still owed.** The SDK swap changed webhook/response shapes, and per doc 16 §T7 Kabir owes a re-gate of `WebhookSignatureVerifier` against the SDK's actual response objects. Tara's own open-issues list flags this. Code review touched it (KB-P8-02, constant-time HMAC) but the SDK-shape integration re-test needs the Razorpay sandbox — fold it into the P15 infra loop. **Do not ship money on the new SDK without this.**

2. **Daily-cap counter inflates on credit-exhausted requests.** I read it: `AICreditService.tryConsume` increments `dailyActionsUsed` (line 92) *before* the credit-decrement check (line 100), so a request that clears the 500 cap but fails `CREDITS_EXHAUSTED` still burns a daily count. Priya flagged this as nice-to-have and she's right — it's **conservative** (over-counts toward a safety cap, never under-counts), so it fails safe. Non-blocking, but tidy it when the executor path is next touched.

3. **P18 truncation has no test.** Kavya flagged that `_truncate_for_tts()` has no coverage; I confirmed — zero tests reference it. It's a cost-control helper, not a money path, so it's below the line, but it's an honest gap in a batch that otherwise tested the right things. Add one test.

4. **Frontend is standalone, not wired.** New components (CreditMeter, ToolResultRenderer, NotificationBell) aren't yet integrated into MeeraChatPanel/MeeraWorkspace; escrow checkout, notifications endpoint, and voice endpoints are still mocked. Fine for a test deploy; must be real before production. Owner: Ananya.

5. **Carried from batch 1, still not closed:** SSRF full-response buffering before the size cap, the `@Transactional` self-invocation gap in `ConfirmLaunchExecutor` (I said before I want this *genuinely* fixed before P15, not follow-up'd — it is still open, and it is a silent partial-failure risk on a multi-write money sequence), `pip-audit` unrun, NonceCache still in-memory. **The ConfirmLaunchExecutor `@Transactional` gap must be closed before P15 runs** — it's directly in the money path P15 tests.

---

## WHAT I VERIFIED MYSELF THIS LOOP (not repeated back)

1. **41 Java tests exist and pass.** `mvn test` → `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS. Five classes on the right surfaces (RequestPaymentExecutor amount-tamper, ToolCallValidator whitelist, WalletService ledger, AICreditService daily cap, NotificationService idempotency). **The batch-1 P7 gap is closed — verified, not reported.**
2. **Python still green.** `pytest -q` → 105 passed (3.65s). No regression.
3. **Migrations on disk.** V16 (daily_action_cap), V17 (notifications), V18 (email_outbox) all present under `db/migration/`.
4. **Razorpay SDK real.** `razorpay-java 1.4.6` in `pom.xml`.
5. **500/day cap real and server-side.** Read `AICreditService.tryConsume` — correct gate, correct reset, correct exception. Also spotted the pre-check increment order (concern 2).
6. **TTS cap real.** `_truncate_for_tts` + `TTS_MAX_CHARS = 200` in `voice.py`, applied at the speak path.

---

## WHAT I'M NOT APPROVING TODAY

No production launch. No real-money traffic. No production Razorpay credentials. No budget change — Rohan's $15–35/month estimate is fine as an estimate; I approve against real numbers once the container and email volumes are live, not before. And per the standing ruling: no softening of the P15 gate under any schedule pressure — if pressure builds, it comes to me, not to a workaround.

---

**Batch-2: GO to test, NO-GO to production. The tests I demanded now exist and pass. Provision live MySQL + Redis + Razorpay sandbox, run migrations (P9), close the ConfirmLaunchExecutor `@Transactional` gap and the P8 SDK re-gate, then run the full P15 money gate. Bring me a green P15 against a live datasource and I open the money path. Backlog: 42% → 68%. Everything parallel-available — keep building.**

*Swapnil Maruti, CEO — 2026-07-05*
