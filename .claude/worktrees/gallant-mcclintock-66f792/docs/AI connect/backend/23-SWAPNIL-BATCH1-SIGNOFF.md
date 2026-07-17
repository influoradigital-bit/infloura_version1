# 23 — SWAPNIL (CEO): BATCH-1 SIGN-OFF (Critical-Path Build — P0/P1/P2/P3/P14 + partial P15)

> **Owner:** Swapnil Maruti (CEO) · **Date:** 2026-07-05
> **Reviewed:** `PENDING-INDEX.md` (all 18 items, P0–P18), `22-TARA-CRITICAL-PATH-RUN-REPORT.md`, cross-read against `21-SWAPNIL-SIGNOFF.md`
> **Verdict:** ⚠️ **CONDITIONAL GO — proceed on the parallel tracks, but P2 (Phase 4) is FROZEN as "not shippable" until it compiles, and P15 is a hard launch gate that has NOT run.**

---

## THE DECISION IN ONE PARAGRAPH

This was the best loop the team has run and it is also the loop where I learned the most about what we were not checking. Domain D (the Python reasoner) is genuinely done — 105/105 tests, senior-approved, Kabir's Phase A gate green. That is the thing that turns Meera from an echo-bot into a cofounder, and it exists now. But the Java side does not compile. Not "has a bug" — does not compile. Every senior approval on the Phase 4 money executors this session was signed against source code that had never once been through `mvn compile`, and the very last fix commit broke the build. So I am approving forward motion on everything that is real and blocking the one thing that is money-adjacent and unproven. That is the whole ruling.

---

## (1) OVERALL COMPLETION — THE FULL BACKLOG

**My number: 38%.**

Here is how I get there, and I am counting the full 18-item backlog, not just this run's slice.

**Genuinely done and defensible (counts full):**
- **P0** (Gemini re-pin) — done, cost-neutral, Rohan confirmed. ✅
- **P1** (Domain D Python service) — done, 105/105, senior-approved, Phase A gate green. ✅
- **P3** (eval harness) — stood up alongside P1 as I directed, running for real. ✅
- **P14** (Kabir Phase A gate) — green, found and fixed a real production crash bug. ✅

That is 4 of 18 fully closed = ~22%.

**Real but not closed (partial credit):**
- **P2** (Phase 4 executors) — logically complete, senior-approved on *logic*, but RED on compile. I give this **half credit at most**. Logic that does not build is not a shippable artifact; it is a promise. Call it ~0.5.
- **P5** (Domain E hardening) — the dual-credential stack, IdempotencyService, AuditLogService, NonceCache landed woven into P2, but not checked as a complete set against doc 16 §E and P16 hasn't run. ~0.4.
- **P7** (tests) — Domain D covered by P3/P14; zero Java-side money-executor tests, and couldn't run if they existed. ~0.25.
- **P15** (Phase B money gate) — a 4-check sanity spot pass ran; the launch-blocking battery (RT-G1..G6, MF-1..4, LB-1..9) did **not**. ~0.15.
- **P17** (cost lines) — Rohan flagged the container line, full costing open. ~0.2.

Add those partials (~1.5 item-equivalents) to the 4 clean items and I'm at roughly 5.5 / 18 = **~30% on a strict count.**

**Why I round up to 38% and not down:** raw item-count understates it. The critical-path spine — the reasoner, the eval harness, the security-gate infrastructure, the executor logic — is the hardest and highest-risk work in the whole backlog, and most of it is built and reviewed even where it isn't green. The remaining items (P6 notifications, P8 SDK swap, P10–P13 frontend, P9 live DB) are more numerous but individually lower-risk and largely unblocked. Weighting by difficulty and by "distance to a working money path," we've cleared more than a third of the real mountain. **38%.**

**Why it is not higher:** because the two things that gate *revenue* — a compiling, tested Phase 4 and a passed P15 — are both still open. Until a rupee can move safely and provably, I will not let this number drift into the 50s no matter how much code exists. Code that hasn't compiled doesn't count toward "done" in my ledger. That is the lesson of this loop and I'm pricing it in.

---

## (2) GO / NO-GO ON THE NEXT BATCH (P4–P18)

**CONDITIONAL GO.** Proceed — but with a hard fence around the Java money path.

- **GO immediately, in parallel, no blocker:** P6 (notifications), P8 (Razorpay SDK swap), P9 (Meera provisions live MySQL — do this now, it unblocks real verification), P17 (Rohan's full costing once container numbers exist), P18 (TTS cap), and the entire Ananya frontend track (P10–P13, with P12 endpoint-discrepancy resolved first per doc 21). None of these wait on the compile bug. Bandwidth permitting, pick them up.

- **GO but sequenced behind the compile fix:** P4 (500/day cap — rides with credit logic, which lives in the Java side that must build first), P5 finish, P7 Java-side tests, P16.

- **NO-GO — frozen until green:** treating **P2 as shippable**, and **any real-money traffic**. P2 is not done, it is "logic-approved, build-broken." It does not advance to launch-eligible until: (a) the record-accessor collision is fixed, (b) `mvn clean compile` is green, (c) `mvn test` runs for real, and (d) Kabir's full P15 gate passes. No exceptions, no schedule-pressure softening — that ruling from doc 21 stands.

So: the team keeps moving on everything parallel-available. Nobody sits idle waiting on the compile fix. But the money path is fenced.

---

## (3) HARD BLOCKERS I AM FLAGGING

Four, in priority order. The first is drop-everything.

**BLOCKER 1 — Java does not compile. `InternalRequestVerifier.VerificationResult`.**
The record's static factories `accepted()`/`rejected(...)` collide with the auto-generated canonical accessor `accepted()`, javac rejects the record, and it cascades to `InternalServiceTokenFilter.java:107`. The whole `influora-api` module is dark. **This is Vikram's, this hour.** It's a two-line rename (`of(...)`/`ok()`/`reject(...)`), not a redesign — but nothing on the Java side is real until it lands and Meera re-runs a green `mvn compile` **and** `mvn test`. Every Phase 4 approval this session was static-inspection-only; I am not counting any of it as verified until there's a real build.

**BLOCKER 2 — P15 Phase B money gate has NOT run. This is THE launch blocker.**
Only a 4-check partial sanity pass happened. The full RT-G1..G6 / MF-1..4 / LB-1..9 / Rulings A/B/C battery — the one that stands between us and moving real money — is unrun and can't even start until Blocker 1 clears. No paying brand, no live rupee, until Kabir's full gate is green. Kabir's own caveat is correct and I'm ratifying it: partial sanity ≠ the gate.

**BLOCKER 3 — Real infra not provisioned (P9 live MySQL).**
V1–V15 are gap-free on disk only. Nothing has run against a live datasource. Meera provisions MySQL now — this is on the critical path to proving the schema, not a nice-to-have. Same for the NonceCache: it's per-instance in-memory today and must move to Redis before any multi-node deploy (carried, not blocking single-node verification).

**BLOCKER 4 — Kavya's `onbehalf_jwt` bug in `chat.py` is unconfirmed-closed.**
She flagged it as blocking; neither Python fix pass mentions closing it; it did not appear on the senior reviewer's must-fix list. An open on-behalf-of re-authorization bug on a money-adjacent boundary is not something I let ride on "probably fine." **Confirm it's fixed or fix it, and put the answer in writing** before P15 runs.

**Below-the-line, not blockers but on the record (fix in-loop, don't let them fester):** `SPRING_INTERNAL_BASE_URL` missing `/api/v1` (flagged twice, fixed zero times — Vikram, close it); the SSRF guard buffering full responses before the size cap (real memory-exhaustion vector, not in the gate's test set); the `@Transactional` self-invocation gap in `ConfirmLaunchExecutor` (silent partial-failure risk on a multi-write money sequence — I want this genuinely fixed before P15, not follow-up'd); Playwright shipped-but-unused (update the spec or wire it); `pip-audit` not run; the idempotency-key VARCHAR-length question against real Claude `tool_use.id` values; and the `/internal/meera/messages` ignored `Idempotency-Key` header. None of these block the next batch. All of them are the kind of thing that becomes a Blocker later if ignored now.

---

## ON THE PATTERN TARA NAMED — I'M RATIFYING IT AS PROCESS

Every gate this loop caught something the previous gate missed, and the last gate — a real compile — caught the thing no human review could. That is not a failure of the reviewers; it is proof the layered gate works. But the lesson is non-negotiable going forward: **`mvn compile` runs continuously, not once at the end.** A review chain that runs on never-compiled code is measuring the wrong thing. Priya, bake a green-compile precondition into the QA gate itself — no senior review starts on Java that hasn't built this session. I don't want to relive this.

And credit where it's due: QA caught 3 bugs the builders' summaries buried, Kabir's gate caught a production crash, the senior review caught the missing service-token minter that the entire dual-credential boundary depended on, and Meera caught the compile break. Nobody rubber-stamped. Every must-fix was actually fixed and re-verified in source. That is the review culture I want. The system worked — it just told us the truth, which is that we're not done.

---

## WHAT I'M NOT APPROVING TODAY

No budget change, no new tool subscription, no architecture override — consistent with doc 21. When Meera's live-MySQL and Python-container numbers are real, Rohan brings me the actual figure and I'll approve against it, not an estimate. And I am explicitly **not** approving P2 as shippable or any real-money launch — see Blockers 1 and 2.

---

**Batch-1: conditional go. Fix the compile bug, prove the build, run the full P15 gate, then come back to me for the money-path green light. Everything parallel-available — keep building.**

*Swapnil Maruti, CEO*

---
---

# ADDENDUM — POST-FIX RE-VERIFICATION (2026-07-05, later same day)

> **Owner:** Swapnil Maruti (CEO) · **Date:** 2026-07-05 (addendum, appended after the original sign-off above — original text unchanged)
> **Trigger:** Coordinating engineer landed three targeted fixes and asked for re-verification, not trust. I did my own independent checks — read the current files myself, ran both builds myself, ran the Python suite myself. This addendum records what I saw with my own eyes and re-prices the backlog against it.
> **Verdict shift:** ⚠️ CONDITIONAL GO → ⚠️ **STILL CONDITIONAL GO, but the drop-everything Blocker 1 is now CLOSED and two below-the-line items are closed too. P2 is no longer "build-broken" — it is "builds green, gate-unrun." That is a real, earned upgrade, but P2 is still NOT shippable, because P15 has still not run.**

---

## WHAT I VERIFIED MYSELF (not repeated back — actually checked)

**1. The record-accessor collision is genuinely gone.** I read `influora-api/src/main/java/com/influora/security/InternalRequestVerifier.java` at its current state. The `VerificationResult` record is `record VerificationResult(boolean accepted, String rejectionReason)` — its two canonical accessors are `accepted()` and `rejectionReason()`. The static factories are now named `ok()` and `reject(String)`. **Neither factory name collides with either accessor name.** The old bug — a static factory `accepted()` clashing with the auto-generated accessor `accepted()` — no longer exists. Every call site inside `verify()` uses `VerificationResult.reject(...)` / `VerificationResult.ok()`. The rename is real and complete, not partial.

**2. The Java module compiles — I ran it, I did not take it on faith.** From inside `influora-api/`, with the session-local Maven at `.tools/apache-maven-3.9.9/bin/mvn.cmd`, I ran `clean compile` twice (once quiet to confirm exit 0, once verbose to read the banner). Result: **`[INFO] Compiling 165 source files with javac [debug parameters release 21]` → `[INFO] BUILD SUCCESS` → Total time ~14.7s.** This is the first genuine green Java compile of `influora-api` on record for this build. Blocker 1 is closed by evidence, not by report.
> **The caveat I am holding firm on (and it is load-bearing):** there are still **zero Java test classes** (P7 gap). `mvn test` passing on this module would be trivially green because there is nothing to run — it is not evidence of behavior. I am crediting the compile and nothing more from the Java side. A green compile proves the code is well-formed and wires together; it proves nothing about whether the money executors *behave* correctly under load, replay, or partial failure. That proof is what P15 exists to produce, and P15 has still not run.

**3. The `onbehalf_jwt` Bearer-prefix bug (Kavya's Blocker 4) is really fixed, and I confirmed it is a real fix by checking both ends.** In `influora-ai/app/routes/chat.py`, line 72 now reads `onbehalf_jwt = body.get("onbehalf_jwt") or _strip_bearer(authorization)` — the raw prefixed header is no longer forwarded. The new `_strip_bearer()` helper strips a case-insensitive `Bearer ` prefix before the token is put into `ToolLoopContext` and `persist_assistant_message`, which are the paths that reach Spring. I did not stop at the Python side: I read `influora-api/.../security/OnBehalfAuthResolver.java` and confirmed `parseOrReject()` calls `jwtService.parseAccessToken(onBehalfJwt)` **directly on the raw value, with no prefix-stripping of its own** — so a `"Bearer eyJ…"` string really would have thrown and produced an `ON_BEHALF_JWT_INVALID` 401 on every header-fallback call. The bug was real; the fix is real and correct; and it is now in writing, which is what I demanded.

**4. `SPRING_INTERNAL_BASE_URL` now carries the `/api/v1` context-path.** I read `influora-api/src/main/resources/application.yml` and confirmed `server.servlet.context-path: /api/v1` applies globally to every controller (including `MeeraInternalController`'s `/internal/meera/*`). I then confirmed both Python defaults were corrected to match: `app/config.py` defaults `SPRING_INTERNAL_BASE_URL` to `http://localhost:8080/api/v1`, and `.env.example` sets `http://influora-api.internal:8080/api/v1`. The path mismatch that would have 404'd every real Python→Spring internal call — flagged twice in prior loops, fixed zero times — is now closed.

**5. Python suite still green — re-ran it myself.** From `influora-ai/`, `.venv/Scripts/python.exe -m pytest -q` → **105 passed** (~13s). No regression from the `chat.py` change.

---

## THE FOUR ORIGINAL HARD BLOCKERS — WHERE THEY STAND NOW

- **BLOCKER 1 — Java does not compile (`InternalRequestVerifier.VerificationResult`). → ✅ CLOSED.** Collision renamed to `ok()`/`reject(...)`, `mvn clean compile` is BUILD SUCCESS on 165 files, verified by me. This was the drop-everything item and it is done.
- **BLOCKER 2 — P15 Phase B money gate has NOT run. → ❌ STILL OPEN. UNCHANGED. This is now the sole remaining launch blocker.** Nothing in these three fixes touches the gate. The full RT-G1..G6 / MF-1..4 / LB-1..9 / Rulings battery still has not run. It is now *unblocked* to run (the compile that gated it is green), but "unblocked" is not "passed." No live rupee until Kabir's full gate is green against a live datasource.
- **BLOCKER 3 — Real infra not provisioned (P9 live MySQL). → ❌ STILL OPEN. UNCHANGED.** No fix in this batch provisions a datasource. V1–V15 remain gap-free on disk only; nothing has run against live MySQL. NonceCache is still per-instance in-memory (Redis before any multi-node deploy). Carried.
- **BLOCKER 4 — Kavya's `onbehalf_jwt` bug. → ✅ CLOSED.** Fixed in `chat.py` via `_strip_bearer()`, correctness confirmed against `OnBehalfAuthResolver`'s direct-parse behavior, and now documented. This is exactly the "confirm it's fixed or fix it, and put the answer in writing" ruling satisfied.

**Below-the-line item now closed:** `SPRING_INTERNAL_BASE_URL` missing `/api/v1` — ✅ closed in `config.py` and `.env.example`. **Still on the record and NOT closed:** the SSRF full-response buffering before size-cap, the `@Transactional` self-invocation gap in `ConfirmLaunchExecutor` (I still want this genuinely fixed before P15, not follow-up'd), Playwright shipped-but-unused, `pip-audit` unrun, the idempotency-key VARCHAR-length question against real Claude `tool_use.id`, and the ignored `Idempotency-Key` on `/internal/meera/messages`. None block; all are the kind of thing that becomes a blocker if left to fester.

---

## UPDATED OVERALL COMPLETION — THE FULL 18-ITEM BACKLOG

**My updated number: 42%** (was 38%).

Here is the honest arithmetic, and why the move is small on purpose.

**What actually changed since 38%:**
- **P2** moves from "~0.5, logic-approved/build-broken" to **~0.7**. A genuine green compile is a real state change — the module is now a buildable artifact, not a promise, and every senior approval this loop was signed against source that now provably compiles. But it does *not* go higher than 0.7, because "compiles" ≠ "shippable": there are zero Java tests (P7), the P15 money gate is unrun, and the `@Transactional` self-invocation risk is still live on the multi-write `ConfirmLaunchExecutor` path. Compile is necessary, nowhere near sufficient, for a money executor.
- **P4/P5/P7/P15/P17** — unchanged in credit. None of these three fixes advanced them. P15 in particular is exactly where it was.
- The two closed blockers (onbehalf_jwt, context-path) were already *below* the item-scoring line — they're correctness bugs inside P1/P2, not separate backlog items — so they raise my confidence in the P1/P2 credit rather than adding a new fraction. That's real value, but it shows up as "the P1/P2 credit is now better-earned," not as a fresh 5%.

**The math:** 4 clean items (P0, P1, P3, P14) + P2 at ~0.7 + the same partials as before (P5 ~0.4, P7 ~0.25, P15 ~0.15, P17 ~0.2) ≈ 5.7/18 on a strict count ≈ **~32% strict**, and I round to **42%** on the same difficulty/distance-to-money weighting I used for 38% — the critical-path spine is now not just built and reviewed but *proven to build*, which is the single hardest-to-fake milestone in the whole backlog.

**Why only +4 points and not more.** I told the team a green compile alone would not push this number past what the evidence supports, and I am holding that line against myself. The two things that gate *revenue* — a **tested** Phase 4 and a **passed** P15 — are both still open. A compile with zero tests behind it is the floor of "done," not the ceiling. If I let a green build alone drag this into the high-40s or 50s, I'd be repeating the exact mistake this whole loop taught us: crediting code that hasn't been proven to *behave*. 42% says "the hardest structural risk is retired; the revenue-proving risk is not."

**What would move this number materially next:** P15 passing (the big one — that alone justifies a jump into the 50s because it retires launch risk on the money path), real Java-side tests for `RequestPaymentExecutor`/`ConfirmLaunchExecutor` (P7), and P9 live MySQL so the schema and idempotency behavior are proven under a real datasource. Those three, not another compile.

---

## UPDATED GO / NO-GO ON P2 BEING SHIPPABLE

**Still NO-GO on shippable. But the fence has moved, and I want the movement on the record.**

Before this batch, P2 failed my four-part shippable test at step (b): it didn't compile. Now:
- (a) record-accessor collision fixed — ✅ **met.**
- (b) `mvn clean compile` green — ✅ **met, verified by me.**
- (c) `mvn test` runs for real — ⚠️ **technically vacuously true (compiles, nothing to run), which I am explicitly NOT counting as met** — there are no Java test classes yet, so a green `mvn test` is meaningless here. This condition is only genuinely satisfied when P7 lands real executor tests.
- (d) Kabir's full P15 gate passes — ❌ **not met. Unrun.**

Two of four met, a third vacuous, the fourth — the launch-blocking one — still open. **So P2 is not shippable and no real-money traffic is approved.** What changed is the *character* of the block: it is no longer "the code doesn't even build," it is "the code builds but its money behavior has not been proven by the gate that exists precisely to prove it." That is a healthier place to be blocked, and it means P15 can finally run for real. Run it. Bring me a green P15 against a live datasource and *then* I give the money-path green light — not before.

---

**Addendum bottom line:** Blocker 1 closed, Blocker 4 closed, context-path closed — all verified by me, not taken on report. Both builds green under my own hands (Java: BUILD SUCCESS on 165 files; Python: 105 passed). Backlog moves 38% → **42%**, deliberately restrained because a green compile with zero tests is the floor of done, not the ceiling. **P2 stays NO-GO on shippable until P15 runs and passes.** Everything parallel-available — keep building. The one thing standing between us and a money-path green light is now a single item: P15.

*Swapnil Maruti, CEO — 2026-07-05 (addendum)*
