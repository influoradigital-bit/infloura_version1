# A2 — Golden eval set for campaign-type recommendation

> **Owner:** Ash (data + spec, no code) · **Date:** 2026-07-11
> **Runner:** Kavya, Q3.4 — runs on every `PROMPT_VERSION` bump; a pass-rate drop **blocks the bump**.
> **Semantics grounded in:** Swapnil's briefing table `wiki/decisions/2026-07-10-ash-ai-capability-spec-briefing.md:43-47`.
> **Pairs with:** the A1 taxonomy (`wiki/ai-review/A1-campaign-taxonomy.md`). A1 defines the types; A2 proves the model applies them — and, critically, **refuses when the answer isn't there.**

## Why the 2 refusals are the whole point
A model that always returns a campaign type is guessing on the hard cases. Fixtures B9 and B10 have
**no correct type** — the honest output is "I need X before I recommend." If the model names a type
there, it failed, no matter how fluent. Assert the refusal as hard as the recommendations.

## Composition: 3 HYPE · 3 DIRECT · 2 REVIEW · 2 REFUSE

```json
[
  { "id": "B1", "expect": "HYPE",
    "brand": { "product_name": "TintPop lip balm", "price": 699, "goal_text": "launch our new flavor with a splash, want lots of buzz fast, 3-day window", "creator_appetite": "many", "timeline_days": 3 },
    "why": "low-price impulse SKU, launch splash, volume>depth, short burst" },
  { "id": "B2", "expect": "HYPE",
    "brand": { "product_name": "GripGo phone grip", "price": 499, "goal_text": "there's a trending audio right now, want max reach riding it this week", "creator_appetite": "many" },
    "why": "trend-jacking, cheap product, reach over story" },
  { "id": "B3", "expect": "HYPE",
    "brand": { "product_name": "Zip energy sachets", "price": 899, "goal_text": "new launch, want ~200 micro creators making buzz off one hero reel", "creator_appetite": "200" },
    "why": "micro-creator volume, derivative content off a hero reel" },

  { "id": "B4", "expect": "DIRECT",
    "brand": { "product_name": "Lumen vitamin-C serum", "price": 4999, "goal_text": "want ~10 mid-tier creators to tell a genuine story and give a real review", "creator_appetite": "10" },
    "why": "considered purchase >2k, storytelling, negotiated mid-tier" },
  { "id": "B5", "expect": "DIRECT",
    "brand": { "product_name": "Aroma espresso machine", "price": 12000, "goal_text": "high-consideration product, need in-depth long-form content, quality over quantity", "creator_appetite": "8" },
    "why": "expensive, long-form, quality>volume" },
  { "id": "B6", "expect": "DIRECT",
    "brand": { "product_name": "PostureFix chair", "price": 6500, "goal_text": "want a few creators, negotiated deals, milestone payouts, real demonstrations", "creator_appetite": "6" },
    "why": "negotiated, milestone payouts, considered purchase" },

  { "id": "B7", "expect": "REVIEW",
    "brand": { "product_name": "BioGut probiotic (new SKU)", "price": 1299, "goal_text": "new product, want honest structured reviews and a UGC library for our paid ads, affiliate links", "creator_appetite": "8" },
    "why": "new-SKU trust-building, structured review + UGC for paid, affiliate" },
  { "id": "B8", "expect": "REVIEW",
    "brand": { "product_name": "Pawful pet food (new SKU)", "price": 2199, "goal_text": "seeding product to a handful of creators for honest reviews, building trust for a brand nobody knows yet", "creator_appetite": "5" },
    "why": "product seeding, trust-building for unknown brand" },

  { "id": "B9", "expect": "REFUSE", "refuse_needs": ["product_name", "price", "goal"],
    "brand": { "goal_text": "we sell stuff, just make it go viral" },
    "why": "no product, no price, no real objective — cannot pick a type; must ask" },
  { "id": "B10", "expect": "REFUSE", "refuse_needs": ["goal", "timeline"],
    "brand": { "product_name": "unnamed skincare item", "price": 3000, "goal_text": "not sure what we want, maybe sales maybe awareness, whatever works" },
    "why": "price alone doesn't discriminate DIRECT vs REVIEW; objective is contradictory/absent — ask, don't guess" }
]
```

## Grading (Kavya wires as a hard assertion, not a warning)

For each fixture, run one Meera turn with the brand context and capture the `create_campaign` tool
call (if any) + the SSE narration.

- **Recommendation fixtures (B1–B8):** PASS iff the proposed `create_campaign.campaign_type` == `expect`.
  If the model narrates a type but proposes a different one (or vice-versa), FAIL — narration and
  action must agree (grounding, Kavya Q3.1).
- **Refusal fixtures (B9, B10):** PASS iff **no `create_campaign` call is emitted** AND the narration
  asks for at least one field in `refuse_needs`. FAIL if any campaign_type is asserted. Deny-list on
  confident-recommendation phrasing ("I recommend HYPE/DIRECT/REVIEW") when the required fields are absent.
- **Never `STANDARD`:** if the model ever proposes `STANDARD`, hard FAIL — it is Java-only and not
  proposable (Priya ruling; `schemas.py` doesn't expose it).

## Baseline & gate
- Record the pass rate at the current `PROMPT_VERSION` as the baseline (expect the refusals to be the
  weak spot pre-A1; that's the signal A1 is meant to move).
- On any `PROMPT_VERSION` bump: rerun. **A drop vs baseline blocks the bump.** 8/10 is not a ship bar
  to celebrate — track which 2 fail and whether they're the refusals (the ones that matter most).

## Data flywheel note
Log every real Meera campaign-type proposal + the brand's subsequent accept/edit/reject. The edits are
future A2 fixtures and the first fine-tuning signal — revisit fine-tuning only past ~10k labeled pairs.

`FROM Ash → TO Kavya | A2 golden set (10 fixtures, 2 refusals) | wiki/ai-review/A2-golden-eval-set.md | NEXT: wire as Q3.4, record baseline pass rate`
