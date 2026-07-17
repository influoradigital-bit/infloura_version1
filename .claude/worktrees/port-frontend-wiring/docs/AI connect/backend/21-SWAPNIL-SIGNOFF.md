# 21 — SWAPNIL (CEO): SIGN-OFF ON REMAINING-WORK PACKETS + ROHAN'S COST REVIEW

> **Owner:** Swapnil Maruti (CEO) · **Date:** 2026-07-05
> **Reviewed:** `16-VIKRAM-REMAINING-TASKS.md`, `17-KABIR-REMAINING-TASKS.md`, `18-ANANYA-REMAINING-TASKS.md`, `19-AI-ARCHITECT-REVIEW.md`, `20-ROHAN-COST-REVIEW.md`
> **Verdict:** ✅ **APPROVED — build starts now.** Both of Rohan's proposed scope additions are approved as blocking corrections. One open item routed to Vikram + Ananya directly (not blocking start).

---

## THE DECISION

I approve all four remaining-work packets as the build plan for the rest of Influora's backend and the Meera frontend wire-up. Priya scoped these correctly and they match what I actually asked for four weeks ago: escrow works, Meera doesn't think yet, fix that first. Nothing here changes direction — it's execution detail, which is exactly what I want to see at this stage.

**What this gets us:** a working AI cofounder instead of an echo-bot, before we put a single paying brand in front of it. That's the whole business case for M2.5. Approved.

---

## RULING ON ROHAN'S TWO PROPOSALS

**1. Gemini model re-pin (`2.0-flash` → `gemini-2.5-flash-lite`) — APPROVED, folded into doc 16 as a blocking correction.**

This isn't a debate — it's a bug we caught before it shipped. I don't want to hear "we built it against a model that was already dead" in a retro. Zero cost impact, one line of config. Vikram fixes `app/config.py` before he writes a single other line of Domain D. Priya, add this as line 1 of doc 16's Domain D section, not a footnote.

**2. 500 actions/day hard cap on "unlimited while live" credits — APPROVED, added to `AICreditService` scope.**

Rohan and the outside AI architect (doc 19) independently found the same hole: our credit model bounds free-tier exposure but not live-tier exposure. That's a real gap — "unlimited" should never mean "uncapped," it should mean "generous enough nobody sane notices the ceiling." 500/day does that. I'd rather set this number now, while it costs nothing, than discover it during a scaling incident. Kabir red-teams the cap as part of his Phase C gate (doc 17) — same discipline as every other rate-limit control.

**No budget increase approved or needed.** Rohan's review is precision-tuning on a plan that was already sound. That's the review I want from my CFO — catch the model-string bug, size the risk, don't manufacture drama.

---

## ON THE FOUR PACKETS THEMSELVES

**Vikram (16):** Sequencing is right — Domain D (Python reasoner) and Phase 4 (Spring executors) in parallel, because that pair is the only thing that turns Meera from a stub into a product. Everything else (notifications, security net-new, tests, live DB) rides alongside or after. I don't need to see intermediate file-by-file progress — I need the `/internal/meera/*` contract working end to end. Report to me when Domain D + Phase 4 compile together and a real chat turn produces a real Claude reply, not before.

**Kabir (17):** Correctly identifies `RequestPaymentExecutor` + the `/internal/meera/*` boundary as the single highest-risk surface in the whole remaining scope. Agreed — that's where a prompt injection, a stolen token, and a hallucinated amount all converge on "does a rupee move." Your gate rule stands as written: no money file ships without your green re-test, full stop, no exceptions for schedule pressure. If Vikram is behind and asks you to soften the gate, the answer is no — bring it to me, not to him.

**Ananya (18):** Good catch flagging the endpoint-path discrepancy between doc 02 and doc 11, and good instinct not to guess. **Resolve this with Vikram before any real wiring starts** — I don't want two docs disagreeing on a contract that's about to carry money-adjacent traffic. This is not blocking her mock-SSE work in the meantime; build ahead on the adapter pattern as planned. Also: good that most of the Meera workspace UI is already built mock-first and QA'd — that de-risks the timeline more than I expected going into this review.

**AI Architect (19):** This is the review I actually wanted and didn't know to ask for. The eval-harness gap (R-1) is the one finding here that changes my mental model — I was tracking "is Meera secure" and not tracking "is Meera *right*," and those are different questions. **I want the eval harness built alongside Domain D, not after it ships.** Priya, own this — fold it into Vikram's Domain D scope as its own line item (golden test sets, tenant-isolation regression, prompt-injection evals) rather than a someday-task. I don't want to find out Meera is confidently wrong about reach numbers from a support ticket instead of a test.

---

## PRIORITY ORDER (my call, for anyone sequencing this week)

1. Gemini model re-pin (5 minutes, do it first, no reason to wait)
2. Domain D + Phase 4 in parallel (Vikram) — this is the whole point of M2.5
3. Eval harness stood up alongside Domain D (Vikram + Priya, per doc 19 R-1)
4. Ananya resolves the doc 02/11 endpoint discrepancy with Vikram, then continues mock-SSE build
5. Kabir's Phase A gate (SsrfGuard + service tokens) before Domain D touches the internet
6. Everything else in doc 16 (notifications, Domain E net-new, tests, live DB, Razorpay SDK swap) — parallel, no blockers, pick up as bandwidth allows

---

## WHAT I'M NOT APPROVING TODAY

Nothing in this batch requires it, but for the record: no new tool subscription, no budget ceiling change, no architecture override. If Domain D build surfaces a real infra cost (Python container hosting, the distributed rate-limiter Rohan flagged), bring me the actual number from Meera's provisioning — I'll approve against a real figure, not an estimate.

---

**Signed off. Build it.**

*Swapnil Maruti, CEO*
