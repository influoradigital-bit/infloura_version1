# A1 — Campaign taxonomy (Block A / persona insert)

> **Owner:** Ash (prompt content, no code) · **Date:** 2026-07-11
> **Unblocked by:** Priya's `STANDARD` ruling (`wiki/architecture/priya-wave1-gate.md`)
> **Implements:** Vikram pastes into `app/prompt/persona.py` `MEERA_PERSONA`; bumps `PROMPT_VERSION`.
> **Semantics sourced from:** Swapnil's briefing table, `wiki/decisions/2026-07-10-ash-ai-capability-spec-briefing.md:43-47` — not from priors.

## Why this exists
`persona.py` lists the tools but never defines HYPE / DIRECT / REVIEW. Claude currently picks
among three uppercase words by English connotation. This is the F4 grounding gap: a recommendation
with no defined meaning behind the label. ~180 tokens, cached in Block A, tenant-agnostic.

## Record correction (file, don't silently resolve)
Swapnil's briefing (`...briefing.md:47`) calls `STANDARD` *"deprecated."* Priya's code-verified
ruling calls it the **live server-side default/fallback** (`CreateCampaignExecutor.parseCampaignType`:
null/unparseable → STANDARD; gated by `IntegrationHealthService`). Operationally identical for Meera
— she never proposes it — but the prompt line below uses Priya's accurate framing, not "deprecated."

## The text (paste verbatim into MEERA_PERSONA, after the "What you can do" block)

```
Campaign types — propose exactly one of these three. (A fourth, STANDARD, is a
server-side default the backend may assign; you never propose it.)
- HYPE: 100–500 micro-creators, flat ₹500–2K per reel, a 72-hour burst derived
  from one hero reel. Best for launches, trend-jacking, and viral spikes, where
  volume matters more than per-creator depth. Weak when duplicate low-effort
  posts get throttled and review load explodes — name that risk when you propose it.
- DIRECT: 5–20 mid-tier creators, negotiated rates, Deal Room chat, a 2-revision
  cap, milestone payouts. Best for considered purchases (₹2K+ products),
  storytelling, and long-form review, where quality beats volume. Weak when
  negotiation drags or a creator goes quiet after a partial payment.
- REVIEW: 3–10 creators, product seeding, a structured review format, affiliate
  tracking. Best for building trust around a new SKU and stocking a UGC library
  for paid ads. Weak when creators don't post or content drifts off-brand — ask
  for a clear brief and an approval gate.
```

## Constraints honored
- Sentence case, no exclamation marks, verb-first — matches the existing persona rails.
- No new tool, no new enum value (Priya rule 2; `STANDARD` stays server-only, F4).
- Every number here is a planning heuristic from Swapnil's table, not a claim about a specific
  creator — those still must trace to a tool result (Priya rule 4).

## Gate
Once merged + `PROMPT_VERSION` bumped, Kavya's A2 golden set (Q3.4) runs against it. The 2 "not
enough data" fixtures must still refuse — defining the types must not make Meera over-recommend.
