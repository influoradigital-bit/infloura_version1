# AI Review: How our AI helps creators

**Reviewer:** Ash (AI/ML) · **Date:** 2026-07-21 · **Branch:** feat/portfolio-view-tracking
**Question asked:** "How is our AI helping the creator?"

## TL;DR
The honest answer: **almost not at all, directly.** Every generative-AI surface we
ship is built for the *brand*. Meera's own persona file says it in line 1 — "You
are Meera — the **brand's** influencer-marketing friend." The AI touches creators
in exactly two places, and both are *about* creators for a brand's benefit, not
*for* the creator:

1. **Brand-safety scoring** — AI reads the creator's captions and grades them
   (GARM + sentiment → `creator_scores.brand_safety_score`). This is evaluative,
   not assistive. A good score helps a creator surface in brand search; a bad one
   buries them. The creator never sees it, can't act on it, and didn't ask for it.
2. **`show_creators`** — Meera surfaces matched creators to a brand. Helps a
   creator get *discovered/hired*, but it's a brand tool; the creator is the
   product being matched, not the user being helped.

There is **zero creator-facing AI**: no creator-side Meera, no caption/pitch
helper, no "which briefs fit me" matcher, no brand-safety coaching. The Trend-Spark
"nudge" *looks* creator-facing but is delivered to the **brand's** workspace
(`TrendSparkNudgeService.getNudge(workspaceId)` → `BrandProfile`, "your niche") —
it nudges the brand to buy Snapsby videos or post, not the creator.

## How It Works (traced flow)

### AI surface inventory (all 6 routes in `influora-ai/app/routes/`)
| Route | Model | Actor served | Touches creator? |
|---|---|---|---|
| `chat` (Meera) | Sonnet 4.5 | **Brand** | Only via `show_creators` tool (matches creators into brand's view) |
| `voice` (Sarvam TTS) | Sarvam | **Brand** | No |
| `analyze_site` | Gemini 2.5 Flash | **Brand** (onboarding) | No |
| `brand_safety` | Sonnet 4.5 | **Brand** (scores creators) | **Yes — reads creator captions, grades them** |
| `trendspark/nudge` | Haiku 4.5 | **Brand** | No (nudge lands in brand workspace) |
| `trend_tag` | Haiku 4.5 | System (trend pipeline) | No |

### The one real creator-data flow — brand-safety
```
Java polls creator's Meta captions (C1)
  → POST /internal/brand-safety  {items:[{content_id, caption, media_type}]}
  → Sonnet 4.5, forced-tool GARM schema (app/prompt/brand_safety.py)
  → validate every GARM category present, score 0–100, sentiment
  → BrandSafetyScoreService → ScoreCalculationJob
  → CreatorScore.brandSafetyScore  (drives brand-side discovery ranking)
```
Files: `influora-ai/app/routes/brand_safety.py`, `app/prompt/brand_safety.py`,
`app/tools/schemas.py`, `influora-api/.../job/ScoreCalculationJob.java`,
`domain/entity/CreatorScore.java`.

### The indirect help — matching
`show_creators` (`app/tools/schemas.py:26`, `/internal/meera/show_creators`) is
read-only and renders matched creators into the brand's canvas. This is the main
mechanism by which the AI *does* help a creator: better matching → more relevant
brand invites. But it is invoked by and shown to the brand.

## Integration Map
- **Creator-data call sites:** only `brand_safety.py`. Captions are supplied by
  Java (never fetched here), never logged raw (`shape_of`), auth via Spring
  service-token, spend-gated.
- **Failure modes:** brand_safety returns typed 502 on provider/malformed output —
  so a creator's content simply goes unscored (no fabricated grade). Good.
- **Cost:** brand_safety runs on **Sonnet** (deliberate, per config.py:85), batched
  per creator per polling cycle. This is the most expensive creator-related AI call
  and scales linearly with creator count × content volume — watch it.

## Findings

**[P1] There is no creator-facing AI at all**
Where: whole `influora-ai` service + `src/components/creator/*` (no Meera).
Issue: The product is two-sided (brands + creators) but 100% of generative AI
serves brands. Creators get graded by AI but get no AI help in return. This is a
strategic product gap, not a bug — flagging it because the question assumes creator
help exists, and it largely doesn't.
Fix: Decide deliberately whether creators get an AI surface. Cheapest first step:
a creator-side "brief fit" explainer reusing the existing match signal + a Haiku
call ("why this brief fits you, what to pitch"). No new data infra needed.
Gain: Turns the creator from *scored object* into *served user*; likely lifts
application quality and retention.

**[P1] Brand-safety grades creators invisibly, with no recourse**
Where: `brand_safety.py` → `CreatorScore.brandSafetyScore`.
Issue: An AI (Sonnet) assigns a creator a brand-safety score that affects their
discoverability, but the creator can't see it, understand it, or contest it. The
`overall_rationale` field is captured but, as far as this trace shows, never
surfaced to the creator. Fairness + trust risk.
Fix: Expose a creator-facing, softened view ("your recent content reads as
brand-safe / mixed — here's why") sourced from the rationale we already store.
Gain: Transparency, creator trust, and a feedback loop that improves content.

**[P2] `show_creators` is the only creator-benefit lever and it's opaque**
Where: `app/tools/schemas.py`, `/internal/meera/show_creators`.
Issue: Matching is the real way AI helps creators (discovery), but there's no
signal captured on *why* a creator was matched/skipped — so we can't improve it or
explain it to either side.
Fix: Log match features + brand accept/reject as a data flywheel (see below).
Gain: Better matching over time = more relevant invites for creators.

## Data & Training Roadmap
- **Now:** Log `show_creators` match features + brand accept/reject/hire outcome.
  This is the single highest-value signal we're not capturing, and it's the one
  that most directly improves creator outcomes (relevant invites).
- **Now:** Surface the `overall_rationale` we already generate in brand_safety to
  the creator in softened form — zero new model cost, pure product win.
- **Next:** A creator-side Haiku "brief fit / pitch helper" seeded from match
  features + a few golden examples. Cheap model, bounded task, high perceived value.
- **Later:** Use accumulated hire outcomes as an eval/few-shot set to improve
  matching; revisit fine-tuning only past ~10k logged match→outcome pairs.

## Verdict: SHIP WITH P1 FIXES
Nothing here is broken code — the AI that exists is well-guarded. The finding is a
**product-shaped P1**: creators are graded by our AI but not *helped* by it. If
"help the creator" is a real goal, the two cheapest levers are (1) show creators
the brand-safety rationale we already compute, and (2) a creator-side brief-fit
helper on Haiku. Both reuse existing data/infra.
