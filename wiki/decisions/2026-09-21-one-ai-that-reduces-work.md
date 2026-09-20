# Ruling: one AI that reduces work for both sides, not a smarter one

> **Decision by:** Swapnil Maruti (CEO) — 2026-09-21
> **Recorded by:** Priya (CTO)
> **Status:** LOCKED. This is the standing product test for every AI feature.

---

## The decision, in Swapnil's words

> "I need system AI that help both creator & brands not smarter one system that help them
> reduces there work."

One assistant, serving both audiences, judged by **how much work it removes** — not by how clever
it sounds, how many tools it can call, or how large a model it runs.

## The test every AI feature must pass

Before any AI feature is built, its ticket states:

1. **Whose work, and which task.** A named person (creator or brand) and a task they do today.
2. **What they do today, step by step** — the real steps, counted.
3. **What they do after** — the steps that remain.
4. **The saving**, as steps removed or a decision no longer needed, not as "AI-powered".
5. **What the AI never does for them**, so trust is not the price of the saving.

A feature that cannot fill 2 and 3 with real steps is not approved. "Smarter answers" is not a
saving. A feature that adds a step (one more screen to check, one more thing to approve) must
remove at least two.

## What this means for the two sides

**Creators.** The work is: reading a brief, deciding a price, spotting a bad clause, replying,
chasing money. Today's paste-a-brief removes the reading and the pricing research
(`influora-ai/app/routes/brief_extract.py`, `service/rates/RateQuoteService.java`,
`service/risk/DealRiskService.java`). The reply is still theirs to write, so the next real saving
is the draft (Wave D), not a better summary of the same brief.

**Brands.** The work is: writing a brief, finding creators, comparing them, funding, chasing
deliverables, reporting. Meera already creates and edits campaigns and reads performance
(`service/meera/tool/CreateCampaignExecutor.java`, `GetCampaignPerformanceExecutor.java`). The
next real saving is the weekly report a human writes by hand, not more chat.

**Both sides, one system.** The architecture is already one service with one chat route that
switches on audience (`influora-ai/app/routes/chat.py:192`, `app/auth/audience.py`), with a
persona per side (`app/prompt/persona.py` for brands, `app/prompt/creator_persona.py` for
creators) and its own tool set and spend cap per side. Keep it that way: one service, one set of
guards, two personas. Do not build a second AI stack for either audience.

## What does NOT count as reducing work

- A longer or more confident answer to a question the user did not need to ask.
- A feature that needs the user to check the AI's work every time, unless checking is cheaper
  than doing.
- Anything that moves work from the user to our support inbox.
- A number with no provenance. An unlabelled price or metric creates work: the creator now has to
  verify it. Every figure keeps its label ("your floor", "benchmark, not market data",
  "imported, not verified").

## Standing constraints this ruling does not relax

- The AI never sends a message, accepts a deal or moves money on someone's behalf without the
  person's action. Sending stays behind `creator-send-enabled` (default false).
- Screening stays where it is: trend headlines are screened at ingest by the word filter and the
  GARM classifier, and fail closed (`wiki/decisions/2026-09-18-trend-headline-screening.md`).
- Spend stays capped per creator and per brand, and credits stay on the clocks set in
  `wiki/decisions/2026-09-18-ai-credit-clock.md`.

## How we will know it is working

Per feature, measured after launch, not asserted before it:

- **Creators:** briefs pasted per active creator per week; share of pasted briefs that reach a
  sent reply; time from paste to reply.
- **Brands:** campaigns created through Meera versus by hand; time from draft to funded.
- **Both:** repeat use in week two. A tool that removes real work gets used again without a
  prompt.

Until a feature has those numbers, its ticket says UNPROVEN, whatever the tests say.
