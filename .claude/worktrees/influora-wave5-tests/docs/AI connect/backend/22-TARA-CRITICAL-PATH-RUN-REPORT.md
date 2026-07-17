# 22 — Tara Run Report: Critical-Path Build (P0, P1/Domain D, P2/Phase 4, P3 Eval Harness, P14 Gate, Partial P15 Sanity)

**Date:** 2026-07-05
**Scope:** The full critical path from `PENDING-INDEX.md` — P0 (Gemini re-pin) → P1 (Domain D Python AI service) ∥ P2 (Phase 4 tool executors) → P3 (eval harness, alongside P1 per CEO directive) → P14 (Kabir Phase A gate) → a partial P15 sanity pass (money-path spot-check only, not the full launch gate) → a Meera build-verify pass across both codebases.
**Scoped by:** Priya (CTO) / Swapnil (CEO), per `PENDING-INDEX.md`'s priority order and dependency graph.
**Reporter:** Tara (read-only; no code touched in producing this report; all claims below are drawn from the individual agents' own run outputs for this session, cross-referenced against `PENDING-INDEX.md`, `17-KABIR-REMAINING-TASKS.md`, and the git log)

---

## Executive Summary

This was the biggest single loop yet: two parallel tracks (Domain D Python service, Phase 4 Java executors) went from zero to a security-gated, senior-reviewed, build-verified state in one session. Both tracks hit real, non-trivial bugs at every gate — QA, red-team, and senior (opus) review each caught things the previous stage missed — and every must-fix raised was actually fixed and re-verified in source, not rubber-stamped. The one thing that did **not** go green is the Java compile itself: a pre-existing record-accessor bug in `InternalRequestVerifier.java` (landed in commit `eca06ea`, the Phase 4 fix commit) blocks `mvn compile` for the whole `influora-api` module. That is the single blocking finding of this report and it sits with Vikram before anything else on the Java side can be considered real.

**Current state in one line:** Python side (Domain D) is green — 105/105 tests pass, senior-approved, Kabir Phase A gate green. Java side (Phase 4) is senior-approved on logic but **does not compile** — a two-line record-naming bug, not a design flaw, but it means nothing in `influora-api` has actually been proven to build this session.

---

## Per-Person Work Report

### Vikram (Backend Dev) — via Track A build, Track B build, and both fix passes

**What he built:**
- **P0 (Gemini re-pin):** `GEMINI_MODEL` set to `gemini-2.5-flash-lite` in `influora-ai/app/config.py`, used consistently in `providers/gemini.py` for both `classify_site` and `cleanup_transcript`. Confirmed by QA as actually wired, not just declared. `PROMPT_VERSION` stamped as `meera-2026.07.05` everywhere a prompt is assembled.
- **P1 (Domain D, ~21 files):** The full `influora-ai/` FastAPI service — SSRF guard (DNS-first resolve, IP-pin against rebinds, private/metadata range blocks, capped redirects), JWKS-based service-token verification failing closed, redaction-backed structured logging with PAN/phone/bank regex backstop, the 5 tool schemas as source of truth, the tool-calling loop (idempotency keys, never trusts model-supplied amounts), an HMAC-signed Spring client, chat SSE route, analyze-site route, voice routes. Virtualenv built, all modules import clean.
- **P2 (Phase 4, ~23 files incl. supporting infra):** Replaced all 5 `MeeraInternalController` 501-stubs with real executors (`ShowCreatorsExecutor`, `CalculateBudgetExecutor`, `CreateCampaignExecutor`, `RequestPaymentExecutor`, `ConfirmLaunchExecutor`), `ToolCallValidator` (name-whitelist + R/D/C/Forbidden tier gate), the dual-credential internal auth boundary (`InternalServiceTokenFilter`, `InternalRequestVerifier` + `NonceCache`, `OnBehalfAuthResolver`), `AuditLogService`/`AuditLogEntry` + `V15__audit_log.sql`, `IdempotencyService` + `IdempotencyKeyRecord`. Correctly reverse-engineered the real Python wire contract (headers vs. body fields, flat snake_case payload, plain-concat hex-digest HMAC) instead of guessing, after discovering Domain D already existed from the parallel track.
- **Two fix passes**, both fully re-verified by senior review (see below):
  - Fix pass 1 (commit `a7124f9`): Anthropic `tool_use`/`tool_result` content-block translation (was emitting an OpenAI-style `tool_calls` field Anthropic ignores), and the stream-token `conversation_id` binding check in `chat.py`.
  - Fix pass 2 (commit `eca06ea`): built the missing `X-Meera-Service-Token` minter (`service_token_minter.py`), fixed the 60s-vs-5min TTL mismatch, fixed an uncaught NPE in `InternalServiceTokenFilter.parseServiceToken`, converted 3 executors from check-then-act to real insert-first `IdempotencyService.executeOnce`, and completed `ConfirmLaunchExecutor`'s DoD (real creator invites, escrow-hold binding, AI-credit reset) which had previously only flipped a status flag.

**Gate verdicts on Vikram's work:**
- Track A QA (Domain D structural QA): **not approved** — 1 blocking finding (`onbehalf_jwt` derivation bug), 2 non-blocking bugs (SSRF guard buffers full response before size-cap check; Playwright shipped-but-unused).
- Track B QA (Phase 4 structural QA): **not approved** — 2 blocking findings (idempotency check-then-act race; uncaught NPE on missing `iat`).
- Track A senior (opus) review: **request changes** — 2 must-fix (Anthropic tool-call format, missing conversation-id binding) → **fixed → re-reviewed → approve**.
- Track B senior (opus) review: **reject** — 5 must-fix (missing service-token minter, TTL mismatch, NPE, idempotency race, incomplete `ConfirmLaunchExecutor`) → **fixed → re-reviewed → approve**.
- P14 Kabir Phase A gate on Domain D: **green**, found+fixed one real bug of its own (see Kabir's section).
- Partial P15 money-sanity spot-check on Phase 4: **green** on the 4 targeted checks, explicitly **not** the full gate.
- Meera build-verify: Python **green** (105 passed). Java **BUILD FAILURE** — see Meera's section. This is the live, unresolved item sitting on Vikram's desk.

**Open issues still carried on Vikram's ledger:**
1. **Blocking:** `influora-api/src/main/java/com/influora/security/InternalRequestVerifier.java`'s `VerificationResult` record declares static factories named `accepted()`/`rejected(...)` that collide with the record's own auto-generated canonical accessor `accepted()` (must be public) — javac rejects the record, cascading to a compile error at `InternalServiceTokenFilter.java:107`. Whole `influora-api` module will not compile until this is renamed (e.g. `of(...)`/`ok()`/`reject(...)`).
2. `influora-ai/.env.example`'s `SPRING_INTERNAL_BASE_URL` omits the `/api/v1` context-path Spring requires — every real Python→Spring internal call would 404 until corrected (flagged independently by both the Track A senior reviewer and the Track B builder; still not fixed as of this report).
3. Playwright is in `requirements.txt`/`Dockerfile` but never invoked — `analyze_site.py` does raw `httpx` GET + regex-strip, not headless-Chromium DOM extraction as the spec and the file's own docstring claim. Arguably safer (avoids SSRF-guard bypass via a real browser), but an undocumented spec deviation; needs either a spec update or SSRF-safe Playwright wiring.
4. SSRF guard's `guarded_fetch()` still buffers the full response before enforcing the size cap (non-streaming `client.get()`) — a chunked-encoding response with no `Content-Length` can exhaust memory before the cap ever fires. Not covered by the P14 gate's test set.
5. `CLAUDE_MODEL` defaults to `claude-sonnet-4-5-20250929` — the spec never pinned an exact string; confirm before shipping.
6. CI shared-schema diff-check between `app/tools/schemas.py` and Spring's tool DTOs — not built.
7. No Spring-side proof that `service_token.py`'s real JWKS-fetch path (vs. local dev fallback) has been exercised against a live JWKS endpoint.
8. `pip-audit` not run against `requirements.txt`.
9. `ConfirmLaunchExecutor`'s multi-write sequence (status flip + N collaboration inserts + escrow bind + credit reset + ledger row) runs under `@Transactional` via a self-invocation (`Supplier` passed into `IdempotencyService.executeOnce`), which the service's own Javadoc admits makes Spring's proxy-based `@Transactional` a no-op in that call shape — a partial failure mid-sequence may not roll back. Flagged by senior recheck as a follow-up, not one of the 5 original must-fixes.
10. `meera_tool_calls.idempotency_key`/`idempotency_keys.idempotency_key` are `VARCHAR(64)`/`VARCHAR(128)`; Python's `idempotency_key_for()` (`f"{tool_use_id}:{workspace_id}"`) could theoretically exceed 64 chars once real Claude `tool_use.id` values are observed — unverified against live Claude output.
11. `POST /internal/meera/messages` ignores the `Idempotency-Key` (`turn_id`) header, so a retried write-back could duplicate an `ai_messages` row (senior review nice-to-have, not fixed this loop).
12. `NonceCache` for HMAC replay protection is per-instance in-memory — needs to move to Redis before multi-node deployment.
13. Java compile has never actually been run successfully this session (blocked by item 1) — none of the Phase 4 Java edits have been proven to build.

### Kavya (QA Lead) — via Track A QA and Track B QA

**What she did:** Did a genuine file-by-file inspection against the spec docs on both tracks, not a rubber stamp — cross-checked `influora-ai/`'s ~17 Domain D files against `10-VIKRAM-FILE-MANIFEST.md`'s exact file count, verified the 5 tool schemas match `04-AI-SERVICE-SPEC.md` §3 verbatim, cross-checked `TOOL_TO_SPRING_PATH` against the real `MeeraInternalController.java` route strings, ran the eval suite for real (47/47 passed at that point) and spot-read test bodies for substantive (non-tautological) assertions. On the Java side, verified all 5 executors' actual DB-read/business-logic behavior line-by-line (not just "a function exists"), confirmed `ToolCallValidator` is genuinely invoked before every executor call, and confirmed HMAC signing matches the documented `spring.py` contract.

**Verdicts:**
- Track A (Domain D): **not approved.** 1 blocking bug (the `onbehalf_jwt` fallback in `chat.py` forwarding the raw, prefixed `Authorization` header — potentially the wrong credential shape — to Spring's on-behalf-of re-authorization boundary), plus 2 non-blocking bugs (SSRF response-size buffering order; Playwright shipped-but-unused).
- Track B (Phase 4): **not approved.** 2 blocking bugs: (1) an uncaught NPE in `InternalServiceTokenFilter.parseServiceToken` when a service JWT omits `iat`, surfacing a raw 500 instead of a clean 401 and skipping the audit-rejection log; (2) a genuine idempotency TOCTOU race — the new `IdempotencyService` was built but never called by any of the 3 write executors, which instead did check-then-act directly against `meera_tool_calls`, so two concurrent same-key requests could both pass the pre-check and both execute the side effect before the DB unique constraint caught only the second ledger insert (by which point the duplicate effect had already happened).

**Net effect:** Both of Kavya's blocking Phase-4 findings were folded into the senior reviewer's must-fix list and are now fixed and re-verified (see Vikram's section / senior recheck). Her Domain D blocking finding (`onbehalf_jwt`) was **not** explicitly listed as fixed in either fix pass — it does not appear in the Track A senior review's must-fix list (that review focused on the Anthropic tool-call format and conversation-id binding) and should be treated as **still open** pending confirmation.

**Open issues she is still owed an answer on:**
- Confirmation that the `onbehalf_jwt` bug she flagged in `chat.py` (`body.get("onbehalf_jwt") or authorization`, forwarding a raw possibly-prefixed, possibly-wrong-audience token) was addressed — it is not mentioned in either subsequent Track A fix-pass summary and should be re-checked, not assumed closed.

### Kabir (Red-Team / Security) — via P14 Phase A gate and Track B partial P15 sanity

**P14 — Phase A gate (Domain D, launch-blocking per `17-KABIR-REMAINING-TASKS.md` before Domain D touches the internet): GREEN.**
Wrote and ran 46 real pytest tests under `influora-ai/tests/security/` (`test_ssrf_guard.py`, `test_service_token.py`, `test_redaction.py`) against real code paths — genuine RS256 keypairs for signature tests, monkeypatched `socket.getaddrinfo`/`httpx.Client.get` for DNS-rebind and redirect-chain simulation, real end-to-end drives through `RedactionJsonFormatter`. All 11 named test groups (RT-SSRF-1..5, RT-TOK-1..3, RT-PII-1) passed.

**Found and fixed one real production bug during the run:** `guarded_fetch()`'s redirect-following code called `httpx.URL(...).join(location).human_repr()` — `.human_repr()` does not exist on httpx 0.28.1. Any redirect response (301/302/303/307/308) from a scraped site crashed the SSRF guard with an unhandled `AttributeError` instead of safely enforcing the redirect cap — meaning the redirect-cap/per-hop re-validation logic (exactly what RT-SSRF-4 targets) was untested/unreachable code before this run. Fixed to `str(httpx.URL(...).join(...))`; the two RT-SSRF-4 tests that caught it now pass green.

Also corrected an over-specified test (bank-account regex label assumption) without weakening the underlying security property — confirmed zero raw PII bytes survive either way, added two new isolated test cases (9-digit, 18-digit) instead.

Ran the full test tree afterward: **93/93 passed**, no regressions. Committed via `git add -A && git commit`.

**Partial P15 sanity pass (Phase 4 money path) — explicitly NOT the full Phase B gate.** 4 targeted checks, all confirmed true in source:
1. `RequestPaymentExecutor` uses the AI's amount/`display_amount_hint` only for a 409 drift check; the persisted/returned amount is always server-derived. **Pass.**
2. `ConfirmLaunchExecutor` reads a fresh DB row and requires a real `FUNDED` `EscrowHold`; no AI-input field is consulted for that decision. **Pass.**
3. `meera_tool_calls` has a DB-level `UNIQUE KEY` on `idempotency_key`; replay returns the stored result, not a double effect. **Pass, with caveat:** the check-then-insert is not atomic, so a genuinely concurrent replay could 500 on the unique-constraint violation rather than gracefully returning the stored result — no double-effect occurs, but graceful-response-under-race wasn't demonstrated. (Note: this exact gap is what the subsequent fix pass converted to `IdempotencyService.executeOnce`; the partial-gate write-up predates confirmation that the fix landed cleanly against this specific check.)
4. No Forbidden-tier route (payout, payment-method, config) is reachable from `/internal/meera/*` — confirmed by grep; the only `/payout` route lives on the separate, human-JWT-gated `EscrowController`. **Pass.**

**Kabir's own explicit caveat, repeated here so it isn't lost:** *this was a 4-check spot-check, not RT-G1..G6, MF-1..4, or the full LB-1..9 battery.* Per `PENDING-INDEX.md` P15 and `17-KABIR-REMAINING-TASKS.md` §3.2 ("No money-tier or commit-tier file ships without Kabir's green re-test"), the full Phase B gate — including RT-B3..B10, B13, B14, B16, dual-credential RT-B12/RT-G2, cross-tenant RT-B9, envelope RT-B8, contract-sign RT-B13 — has **not** run, and cannot meaningfully run until there is a live datasource/deployed endpoint. **This remains launch-blocking and is the next thing on Kabir's desk (P15 proper).**

### Priya (CTO / senior-opus reviewer role in this loop)

Acting as the senior (opus-level) reviewer on both tracks, going file-by-file against the spec docs and the real counterpart code (not just the track's own summary):

- **Track A senior review (Domain D + P3 eval harness): request changes**, 2 must-fix:
  1. `assembler.py`'s `build_block_c_messages()` emitted an OpenAI-style top-level `tool_calls` field that Anthropic's Messages API ignores — would have broken every multi-turn chat that previously invoked a tool (the following `tool_result` block would have no matching `tool_use`). Not caught by QA or the P14 gate because the prompt-injection tests only exercised the plain-text path.
  2. `chat.py` validated the stream token but never asserted `verified.conversation_id == body.get('conversation_id')` — a stream token minted for one conversation could be replayed against a different conversation in the same workspace. The field was already parsed in `service_token.py`; this was an unwired assertion, not new plumbing.
  - Both fixed by Vikram, **re-reviewed and approved** — confirmed via grep that no `tool_calls` key is ever emitted as output, and that the conversation-id check is correctly gated to only apply when a stream token actually carries one (service tokens legitimately don't).
  - Niceties flagged, not blocking: a per-call local `import json` that could be hoisted; no normalization on the conversation-id string comparison.

- **Track B senior review (Phase 4): reject**, 5 must-fix (see Vikram's section for the list) — the four headline money-safety guarantees (server-side amount re-derivation, DB-verified FUNDED gate, whitelist/tier gate, dual-credential auth ahead of the public filter) were confirmed genuinely and correctly built, meaning the QA/gate-level claims held up, but 5 issues underneath them had been missed by both QA and the partial gate: no producer existed anywhere for the service token the whole boundary depends on, a TTL contract mismatch, an uncaught NPE, check-then-act idempotency, and an incomplete `ConfirmLaunchExecutor`.
  - All 5 fixed by Vikram, **re-reviewed and approved.** Confirmed each fix against the actual files (not the fix-pass's own description): minter claims match the filter's expected issuer/audience exactly; TTL clamps via `min()`; NPE null-check sits before the `exp-iat` computation and before the catch block; all 3 write executors now route through `IdempotencyService.executeOnce` with `AlreadyInProgress`/`AlreadyCompleted` replay handling; `ConfirmLaunchExecutor` now does real `Collaboration` invites, a real `EscrowHold.bindCollaboration()` setter, and calls the real `AICreditService.applyEscrowFundedReset`.
  - One follow-up flagged (not a must-fix): the self-invocation `@Transactional` gap inside `IdempotencyService.executeOnce`'s `Supplier`-wrapped call, per the service's own Javadoc admission — needs a self-proxy or a relocated transactional boundary before the multi-write `ConfirmLaunchExecutor` sequence can be trusted to roll back cleanly on partial failure.

Neither senior review could compile Java (no `mvn`/`javac` in-session at review time) — both were explicit that static inspection of signatures/imports/DI wiring is not a substitute for an actual compile, which is exactly what then failed in Meera's build-verify pass.

### Meera (DevOps / Build-Verifier)

Ran the formal build-verify pass this session, provisioning her own Maven since none was pre-installed:
- No `winget` package available; fell back to a direct Apache-archive-mirror binary download (`archive.apache.org`, Maven 3.9.9), unzipped locally under `.tools/`, added to session `PATH` only (no system-wide install).
- **`mvn -q clean compile` (influora-api): BUILD FAILURE.** Root cause isolated to one file: `com.influora.security.InternalRequestVerifier`'s `VerificationResult` record declares package-private static factories `accepted()`/`rejected(String)` that collide with the record's own mandatory-public canonical accessor `accepted()` — javac rejects the record outright, cascading to a second error at the caller site `InternalServiceTokenFilter.java:107` (`if (!verification.accepted())` — unary `!` on a type that failed to compile). Correctly identified this as pre-existing code from commit `eca06ea` (the Phase 4 fix commit), not something introduced by the build-verify pass itself, and correctly declined to fix it herself (outside build-verify scope; owned by whoever owns `InternalRequestVerifier.java`).
- **`mvn test`: not run**, per the task's own gating rule ("if compile succeeds, run test") — correctly skipped since compile failed.
- **Python: `.venv/Scripts/python.exe -m pytest -q`: 105 passed, 0 failed, 4.13s.**
- **Flyway migrations:** zero new files added this pass (build-verify only, no schema changes). Confirmed `V1..V15` run with no gaps. Documented (not executed — no live MySQL available) the exact `mvn flyway:migrate`/`flyway:info` invocation whoever runs this against a real dev/staging datasource should use.
- **Repo hygiene:** added `.tools/` to `.gitignore` (the locally-downloaded Maven binary is not a source deliverable) and committed that single change as `0e7d16e`. No other source files touched.

**Verdict: Java build is RED. Python build is GREEN.** This is the load-bearing finding of the whole session — every senior review and gate this loop ran against Java source that has never actually been compiler-checked, because the compile-breaking bug landed in the very last Java commit (`eca06ea`) and nobody ran a real `mvn compile` until this pass.

### Rohan (CFO)

Reviewed `20-ROHAN-COST-REVIEW.md` §1/§3/§4 against this session's actual changes. No file edits made (review-only, as scoped).
- **Gemini re-pin (P0): cost-neutral, confirmed.** `gemini-2.5-flash-lite` at $0.10/$0.40 per million tokens (input/output) is identical pricing to the deprecated `gemini-2.0-flash` it replaces, with a materially better output ceiling (65K vs. 8K tokens). One-line model-string swap, no cost or architecture implication. The PRD's "All Gemini Flash: ₹1/campaign-month" figure stands unchanged.
- **New infra line flagged, not yet costed (ties to P17):** the new `influora-ai/` Python service container (Domain D hosting) is a new recurring compute cost not yet reflected in `cost-log.json`. Already implied by architecture Priya/Swapnil already approved, so no new sign-off is required to proceed — but it needs to be added as a tracked line once Meera/DevOps provisions the container and real numbers exist. Explicitly scoped this pass to flagging the container line only; the full P17 costing (this line + Redis rate-limit store + MySQL dev/staging + MSG91 volume growth) is out of scope for this run.

---

## Cross-Cutting Pattern Worth Naming

Every gate this session caught something the previous gate missed, and nothing was rubber-stamped:
- QA caught 3 real bugs (2 on Java, 1 on Python) that the builders' own summaries didn't surface.
- Kabir's P14 gate caught a production crash bug (`human_repr()`) in code QA had already passed.
- The senior (opus) reviewer caught 7 more must-fix items (2 Python, 5 Java) across both tracks that QA *and* the respective security pass had both missed — including the single most consequential one (no component anywhere mints the service token the entire dual-credential boundary depends on).
- Meera's build-verify then caught the one thing no amount of static/manual review could: the Java module doesn't actually compile, because the last fix commit introduced a two-line record-naming collision.

The lesson for next loop: get a real `mvn compile` running *continuously* (even a minimal one, before Maven is "properly" provisioned), not just at the end — this session's Java review chain (QA → red-team spot-check → senior opus review, three real gates) ran entirely on manually-inspected, never-compiled code for the whole build-executors phase.

---

## What Remains — Status of Every Item in `PENDING-INDEX.md`

| # | Item | Status after this session |
|---|---|---|
| **P0** | Gemini re-pin | ✅ **Done.** Confirmed by QA, senior review, and Rohan's cost note. |
| **P1** | Domain D Python AI service | ✅ **Built, fixed, senior-approved, P14-gated green.** Open non-blocking items remain (see Vikram's list items 2–4, 6–8). |
| **P2** | Phase 4 tool executors | ⚠️ **Built, all must-fixes from QA + senior review resolved and re-verified in source — but the module does not compile.** Cannot be called "done" until the `InternalRequestVerifier` record bug is fixed and a real `mvn compile`/`mvn test` passes. |
| **P3** | Eval harness | ✅ **Built alongside P1 per CEO directive.** GS-3 (tenant isolation) and GS-4 (prompt injection) automated per the doc's own launch-blocking priority; a local GS-1-style golden-brands stand-in built too. GS-2 (tool-selection accuracy) and GS-5 (hallucinated-claim/amount) explicitly **not yet built** — in line with the doc's own sequencing ("GS-2/GS-5 can follow the first live week") but still open. CI wiring of the §5.3 gate contract (run on every `PROMPT_VERSION` bump, hard-block on GS-3/GS-4 regression, Priya sign-off on GS-2/GS-5 drift) also not built. Real 30–50-brand live GS-1 set still blocked on Playwright rendering being wired in. |
| **P4** | 500 actions/day hard cap | ❌ **Not addressed this session.** Still open. |
| **P5** | Domain E security hardening | ⚠️ **Partially woven in, not a dedicated pass.** `IdempotencyService`, `AuditLogService`/`AuditLogEntry`, the dual-credential internal-auth stack, and `NonceCache` all landed as part of P2, which is the intended "woven into P1/P2" pattern — but the full Domain E file list (~12 net-new + 4 modify + `idempotency_keys`/`audit_log` migrations) has not been checked off against `16-VIKRAM-REMAINING-TASKS.md` §E as a complete set, and P16 (Kabir Phase C gate) has not run at all. |
| **P6** | Domain B notifications (39 files) | ❌ **Untouched this session.** No blocker; still parallel-available. |
| **P7** | Automated tests (money/AI services) | ⚠️ **Partially covered.** The P3 eval harness + P14 security suite (93 tests before the fix-pass additions, 105 after) cover Domain D substantially. No dedicated Java-side test suite exists yet for `RequestPaymentExecutor`/`WalletService` as this item specifically calls out — and couldn't be run this session regardless, since the module doesn't compile. |
| **P8** | Razorpay SDK swap | ❌ **Not addressed this session.** Still hand-rolled `java.net.http.HttpClient`, per the prior Phase-1/2 report. |
| **P9** | Live MySQL migration execution | ❌ **Still not run.** No live datasource available in this environment this session either; Meera documented the exact `flyway:migrate`/`flyway:info` commands for whoever has DB access, but nothing has actually executed against MySQL. V1–V15 confirmed gap-free on disk only. |
| **P10** | Frontend real SSE client | ❌ **Untouched** (Ananya's track, not part of this session's scope). |
| **P11** | Frontend commit-tier confirm controls | ❌ **Untouched.** |
| **P12** | Frontend/backend endpoint-path discrepancy resolution | ❌ **Untouched this session** — and now compounded by the newly-confirmed `SPRING_INTERNAL_BASE_URL` missing `/api/v1` issue (a related but distinct path problem, backend-internal rather than frontend-facing). |
| **P13** | Frontend renderers/voice/credit meter/notifications UI | ❌ **Untouched.** |
| **P14** | Kabir Phase A gate | ✅ **Done, green.** 46 real tests, one production bug found and fixed (`human_repr()` crash), full suite 93/93 at gate time. |
| **P15** | Kabir Phase B gate (full launch-blocking money gate) | ⚠️ **Still open — only a 4-check partial sanity pass ran this session, explicitly not the full gate.** RT-G1..G6, MF-1..4, the full LB-1..9 battery (incl. RT-B3..B10, B13, B14, B16, dual-credential RT-B12/RT-G2, cross-tenant RT-B9, envelope RT-B8, contract-sign RT-B13), and re-test against a live datasource/deployed endpoints all remain to be run. **This is launch-blocking per `PENDING-INDEX.md`'s own "gate that cannot be softened."** It also cannot proceed meaningfully until P2 actually compiles (P15 is gated on P2 per the dependency graph, and P2 is currently red on build). |
| **P16** | Kabir Phase C gate (Domain E hardening + live JWT + distributed rate limiter) | ❌ **Not started.** Depends on P5 being complete, which it is only partially. |
| **P17** | Infra cost lines (Python container, Redis, MSG91 volume) | ⚠️ **Partially flagged, not costed.** Rohan flagged the new `influora-ai/` container line this session as the one in-scope item; the full P17 costing (container + Redis rate-limit store + MySQL dev/staging + MSG91 volume growth) remains open and explicitly out of scope for this pass. |
| **P18** | Cap TTS spoken-reply length (~200 chars) | ❌ **Not addressed this session** — rides with voice, which exists as routes in Domain D but this specific cap was not verified as implemented or tested by any track this loop. |

---

## Current Build Status

**Python (`influora-ai/`): GREEN.** 105/105 tests pass (`tests/eval/` + `tests/security/` + `tests/routes/`). Senior-approved. P14 gate green.

**Java (`influora-api/`): RED.** `mvn clean compile` fails with 2 compiler errors, both from one root cause (`InternalRequestVerifier.VerificationResult`'s record-accessor name collision) in the last-committed Phase 4 fix commit (`eca06ea`). No Java test run was possible this session as a direct consequence. This is a small, mechanical fix (rename two static factory methods) but it is a hard blocker on every downstream claim about the Java side — including the senior-opus "approve" verdict on Track B, which was necessarily based on static inspection, not a real compile, and is the exact failure mode the process is supposed to catch before sign-off.

## Explicitly Still Open / Blocking, In Priority Order

1. **Fix `InternalRequestVerifier.java`'s `VerificationResult` record so `influora-api` compiles.** Nothing else on the Java side can be re-verified until this lands and a real `mvn compile` + `mvn test` pass is run.
2. **Run the actual P15 Phase B gate** (not the partial sanity pass) once Java compiles — this remains the single launch-blocking item on the whole critical path per `PENDING-INDEX.md`.
3. **Confirm or fix the `onbehalf_jwt` bug** Kavya flagged in `influora-ai/app/routes/chat.py` — not confirmed closed by either Python fix pass.
4. **Fix `influora-ai/.env.example`'s `SPRING_INTERNAL_BASE_URL`** to include `/api/v1` — flagged twice, fixed zero times.
5. Provision a live MySQL datasource so P9 can actually run (blocks real verification of V1–V15 DDL correctness under load).
6. Everything else in the table above rides in parallel per Swapnil's approved priority order: P4, P5 (finish), P6, P7 (Java-side), P8, P16, P17 (full costing), P18, and the whole Ananya frontend track (P10–P13).

---

**Ready for Priya sign-off: PARTIAL.** P0, P1, P3, and P14 are genuinely done and defensible. P2 (Phase 4) is logically complete and senior-approved but **not build-verified** — it must not be treated as shippable until the compile bug is fixed and a green `mvn compile`/`mvn test` exists. P15 (the actual launch gate) has only had a 4-check spot-check, not a real pass, and remains the hard blocker on any real-money traffic per the CEO-approved plan. Recommend: Vikram fixes the record-accessor bug immediately (small, mechanical), Meera re-runs build-verify to get a real Java compile+test result, and only then does Kabir's full P15 gate become meaningful to run.
