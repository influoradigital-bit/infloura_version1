# Proposal: Creator AI Co-pilot ("Trend-Spark, pointed at the creator")

**Author:** Ash (AI/ML) · **Date:** 2026-07-21 · **Status:** DECIDED — BUILD AFTER MONEY-PATH (Tier-1 pilot). See decision at bottom.
**Predecessor:** [how-ai-helps-creators-ai-review.md](how-ai-helps-creators-ai-review.md)
(finding: today 100% of our AI serves brands; creators are *graded*, never *helped*)

## The one-line idea
Creator links Instagram → we read their real niche/themes → match against the SAME
`trends` table the brand-side Trend-Spark already uses → a cheap AI layer suggests
"this theme is trending in your niche, here's a content idea," AND surfaces the
brand campaigns that fit them. A guidance + discovery co-pilot for the creator.

## Why this is cheap to build (reuse map — almost nothing is new)
| Need | Already exists | New work |
|---|---|---|
| Creator links Instagram | `MetaOAuthService`, `MetaOAuthToken` | route creator → same OAuth |
| Know creator's niche/themes | `InstagramMetricsFetcher`, `InstagramMediaResponse` (already pulls their posts) | tag their own themes (reuse `trend_tag` vocab) |
| Trend feed (news + other APIs) | `trends` table, n8n `trend-pull-workflow.json`, `theme-tagger.js` | none — just read it creator-side |
| Match theme → creator | `ThemeMatchService` (brand-side today) | mirror it for creator themes |
| Cheap AI phrasing | `trendspark/nudge` (Haiku 4.5, guarded, fallback) | new creator-tone prompt, same guardrails |
| Find campaigns for creator | `show_creators` (brand→creator today) | invert: creator→open campaigns |
| Content safety of suggestions | `brand_safety` GARM guardrails | reuse for outbound suggestions |

**Net:** ~1 new prompt file + 1 creator-side service that mirrors `TrendSparkNudgeService`
+ 1 UI surface in `src/components/creator/`. The hard parts (IG data, trend pipeline,
guarded cheap-AI phrasing, spend gates) are DONE.

## What the creator gets (three tiers)
1. **Guidance** — "You post skincare + GRWM. There's a trending 'winter barrier repair'
   theme peaking in ~6 days. Here's a 3-beat reel idea." (Haiku phrasing over trend match.)
2. **Discovery** — "3 open brand campaigns match your niche right now." (invert `show_creators`.)
3. **(Later) Coaching** — surface the brand-safety rationale we ALREADY compute, softened:
   "your recent captions read brand-safe — that's why brands can find you."

## AI design (Ash's lens)
- **Model:** Haiku 4.5 for suggestion phrasing (bounded, high volume, cheap — same class as
  Trend-Spark). NOT Sonnet. Trend/theme matching is deterministic Java, not a model call.
- **Guardrails (reuse Trend-Spark's):** closed-vocab themes, defensive JSON parse, forbidden-
  content kill-switch, deterministic templated fallback on any failure, spend gate, PII-free logs.
- **Prompt-injection surface:** creator's own IG captions become model input → wrap as
  `<untrusted_>` exactly like brand site content (`app/prompt/untrusted.py`).
- **Data flywheel (the real prize):** log suggestion → creator action (made it? / dismissed?)
  and campaign-match → applied/hired. This is the signal we have ZERO of today and it improves
  both matching and suggestion quality over time.

## Open questions for the debate
1. **Strategy (Swapnil):** does serving creators deepen the two-sided flywheel (better creators
   → better campaigns → more brands), or does it dilute focus while brand monetization is unproven?
2. **Sequencing (Priya):** build now (reuse is cheap) or after the brand money-path is fully
   verified? What's the smallest shippable slice?
3. **Positioning (Tejas):** is "AI co-pilot for creators" a wedge that pulls creators onto the
   platform (supply-side growth), and how does it read against competitors?
4. **Cost/abuse (Ash):** high-volume Haiku suggestions across all creators — capped per creator/day?

---

## DECISION (Swapnil, CEO — 2026-07-21)

**THE CALL: BUILD AFTER MONEY-PATH.** Start the Tier-1 pilot the week Priya certifies
money infra stable. Tejas is right that supply constrains revenue; Priya is right that
chasing supply before brands can reliably *pay* creators is backwards. Sequencing, not
rejection.

- **Authorized scope — Tier-1 Guidance ONLY:** creator links IG → captions→theme tagging
  (batch job, not per-post real-time) → theme match → ONE Haiku suggestion/creator/day,
  hard-capped, templated fallback.
- **Deferred until Tier-1 proves engagement:** coaching, flywheel logging, campaign
  discovery, any creator-initiated spend.
- **Vernacular ruling:** English-only OK for the invite-only pilot (50–100 creators). It is
  a **launch blocker for public release** — Hindi + Tamil + Telugu must work end-to-end
  before any rupee of creator acquisition spend.
- **Kill metric:** creator 7-day activation (link → suggestion → posts within 48h, manually
  sampled) **< 25% after 4 weeks kills it.** Tejas owns measurement.
- **Next step / owner:** **Priya** delivers money-path stability signoff (escrow happy-path,
  payout idempotency, subscription webhook), target ~2 weeks. On her signoff, **Ash** has
  greenlight for Tier-1 build. Tejas preps the 50-creator pilot cohort + activation tracker
  now (zero-eng).

## Debate positions (archived)
- **Priya (CTO):** BUILD AFTER MONEY-PATH. "Cheap reuse" overstated — creator theme-tagging
  doesn't exist, Meta OAuth is brand/workspace-scoped, `TrendSparkNudgeService` is welded to
  BrandProfile. MVP = Tier-1 only; per-creator/day Haiku cap non-negotiable.
- **Tejas (CMO):** YES, growth wedge — the supply gap IS the money-path blocker. Target
  nano/micro (10K–100K) in beauty/fashion/food. First-mover: brand-side co-pilots exist,
  none serve creators. RISK: vernacular-first market vs English-biased pipeline. Metric =
  7-day activation ≥30%.
- **Ash (AI/ML):** Haiku for phrasing (not Sonnet), reuse Trend-Spark guardrails, wrap creator
  captions as untrusted, log suggestion→action as the data flywheel. Cost cap per creator/day.
