# CEO Verdict — Brand-Side AI Review

**Date:** 2026-09-12
**Questions:** `CEO-AI-BRAND-50Q-0912.md` (50)
**Answers:** `CTO-AI-BRAND-50A-0912-PART1.md` (Q1–28) · `CTO-AI-BRAND-50A-0912-PART2.md` (Q29–50)
**Verification:** 48 of 50 answered from opened code. 2 scoped-unverified, both stated.

---

## Headline

**72% of the brand-side AI is live (13 of 18 named capabilities).** That number is real and
defensible — Priya showed her denominator, capability by capability.

**But not one of the 13 has a live proof newer than 2026-07-23.** We are 7 weeks from our last
evidence that any of this works for a real brand. The percentage measures code, not customers.

---

## ACT TODAY (Swapnil, human — cannot be delegated)

**1. Rotate the Anthropic API key.**
A live-format key sits in the tracked file `influora-ai/env.example`, present across at least five
commits of git history (`8c7b18b`, `1792c37`, `e3e59d0`, `2d0e1bd`, `b63ccf3`). Verified by me
directly. Rotation is the fix — deleting the file does nothing, history retains it.
Same file class was flagged 2026-09-04 and was not rotated. This is the second miss.

**2. Ruling needed: the landing-page matching claim.**
We tell brands we match creators "by niche, city, and engagement." The implementation is a SQL
`LIKE '%word%'` over bio and name, sorted by follower count, with **no engagement term at all**.
That is not a bug — it is a claim we cannot support. Either the copy changes or the algorithm does.
Routed to Tejas for copy, Priya for the algorithm. My call required on which.

---

## THE THREE DEFECTS THAT MATTER

**D1 — Meera can never report on a campaign.** *(queued: task_ad069e1c)*
`get_campaign_performance` is structurally uncallable. The tool requires a `campaign_id` from an
`[id=...]` context marker; neither `PastCampaignEntry` nor `CampaignOutcomeEntry`
(`MeeraContextDtos.java:52,76`) carries an id, so the marker never renders. Verified personally.
Every brand asking "how did my campaign do?" is told Meera can't pull verified numbers — forever.
The F-18 fix landed on the Python side; the Java DTO was never widened. CI's drift check compares
top-level names only and cannot see it.
*The bitter irony: we did the hard integrity work — `verified_reach` draws exclusively from
`PLATFORM_VERIFIED` rows, self-reported numbers omitted not flagged — and no brand has ever seen it.*

**D2 — Two of five Living Canvas stages can never advance in production.**
And a failed spend gate still burns the brand's AI credit while showing "Didn't catch that — try
again?" The customer pays for the failure and is told it was their fault. This is what a customer
hits first.

**D3 — The invented-price kill-switch is not wired to brand chat.**
It guards two creator routes only. The brand conversation — the one where a fabricated price becomes
a commercial expectation — is unprotected.

---

## FALSE COMFORT (things we would have cited as safeguards)

- **`ToolCallValidator` blocks nothing at runtime.** All six call sites pass compile-time enum
  constants (`MeeraInternalController.java:198-307`), so every branch is dead code.
- **There is no feature flag on brand-side Meera at all.** The only brake is a global kill switch
  (default `false`). If Meera misbehaves tomorrow, our sole option is to turn everything off — no
  per-tool, no per-workspace, no gradual rollback.
- **Utho compose pins mutable `:latest`.** We cannot state which image digest is live. Every
  "is the fix deployed?" question is unanswerable by construction.

---

## CORRECTED FOR THE RECORD

Priya reported "42 commits unpushed." That is vs `origin/main` and is normal for an unmerged feature
branch. **The true unpushed count is 8**, on `feat/meera-creator-phase-e`. The substance holds — the
brand-signup mobile-number fix (`c12ecde`, F-0780) exists only on this machine.

Resolved from prior concerns: the on-behalf JWT scope bug **was** genuinely fixed and is on `main`
(`706b60f`). Campaign analytics are **not** being passed off as verified — that was built honestly.

---

## MY ORDERS

| # | Action | Owner |
|---|--------|-------|
| 1 | Rotate the Anthropic key; audit what else is in history | Swapnil (today) |
| 2 | Ruling on the matching claim — copy or algorithm | Swapnil → Tejas/Priya |
| 3 | Fix D1, with a test at the seam not on the DTO | queued task_ad069e1c |
| 4 | Push the 8 commits | Meera |
| 5 | Stop burning credits on failed spend gates (D2) | Priya to scope |
| 6 | Wire the price kill-switch to brand chat (D3) | Priya to scope |
| 7 | Pin image digests, kill `:latest` | Meera |
| 8 | One live brand journey, end to end, with evidence | Neha |

**Item 8 is the one I care most about.** Seven weeks without a live proof is how 72% becomes a number
we believe instead of a number we know.
