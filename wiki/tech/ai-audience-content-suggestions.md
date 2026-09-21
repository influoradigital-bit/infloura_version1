# AI Content Suggestions, Grounded in the Creator's Own Audience

**Owner:** Priya (CTO) · **Status:** DRAFT — not locked · **Date:** 2026-09-13
**Ask:** let the AI help a creator with content ideas based on their audience — not just on
news trends (that is the separate Co-pilot / TrendPullJob track, still stopped by Swapnil).

> **Reading rule.** CURRENT = read from code, cited `file:line`. TARGET = proposed, not built.

---

## 0. The headline finding — this is cheaper than it looks

I went looking for a permission gap and found a **wiring gap instead.**

`CreatorAnalyticsController` (`influora-api/src/main/java/com/influora/web/CreatorAnalyticsController.java:28-66`)
already serves, self-scoped, gated on nothing but the creator's own login:

| Endpoint | Returns |
|---|---|
| `GET /creator/analytics/me/metrics` | `CreatorMetricsResponse` |
| `GET /creator/analytics/me/scores` | `CreatorScoresResponse` |
| `GET /creator/analytics/me/demographics` | `CreatorDemographicsResponse` |
| `GET /creator/analytics/me/media` | `List<ContentPerformanceResponse>` — **per post** |

`CreatorAnalyticsService.getMyMetrics/getMyScores/getMyDemographics/getMyContentPerformance`
(`influora-api/src/main/java/com/influora/service/CreatorAnalyticsService.java:33-55`) resolve every
one of these through `creatorContext.requireCreatorProfile(principal).getId()` — the creator's own
identity, nothing else. This is **not** the brand-viewing-creator gate
(`MetricsAuthorizationService.resolveAuthorizedCreatorProfileId`, which 403s until a
`MetaOAuthToken` pairing exists). A creator looking at their own data needs no such pairing.

`ContentPerformanceResponse` (`influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java:167-179`)
already carries, per post: `mediaId, mediaType, permalink, impressions, reach, engagement, likes,
comments, saves, shares, videoViews, avgWatchTimeSeconds`. **No caption field** — clean of the
LOCKED brand-safety boundary by construction, not by filtering.

**Nothing in `influora-ai` calls any of these four routes.** Verified by search — zero hits.
`build_block_b_creator` / `buildMetricsSummary` never reference `ContentPerformanceResponse` or
`CreatorDemographicsResponse`. **F-0798.**

So the honest framing of this feature is: **the data pipeline is done. Wire it in.**

---

## 1. What already exists that this builds on (CURRENT)

- **Self-scoped read access** — §0, all four routes, all four already permission-clean.
- **Meera creator context** — `build_block_b_creator` (`influora-ai/app/prompt/assembler.py:541`),
  27 allow-listed fields, 24 rendered, enforced by a Java↔Python drift test
  (`tests/prompt/test_creator_context_drift.py`).
- **`metrics_summary` map** — `buildMetricsSummary`
  (`influora-api/src/main/java/com/influora/service/meera/MeeraContextService.java:353-380`) is
  where a new signal would land if it followed the existing shape. Today it emits at most
  `followers`, and — per F-0793/F-0797 — `reach_30d`/`engagement_rate` are always empty because
  nothing computes `CreatorMetric.avgEngagementRate`/`avgReachPerPost`.
- **Tool permission model** — `OnBehalfTokenService.SCOPE_DEFAULT`
  (`influora-api/src/main/java/com/influora/service/meera/OnBehalfTokenService.java:68-69`) is
  brand-only, four tools. **Zero creator-side tool executors exist.** A creator's Meera session is
  conversational only — it can read context, it cannot call a tool.
- **The caption boundary** — `MediaMetric.caption` may never reach a brand-facing DTO
  (`wiki/decisions/2026-07-06-brand-safety-caption-storage.md`, LOCKED, enforced by
  `NoBrandFacingCaptionExposureTest`). This feature is creator-facing, so the boundary does not
  apply to serving the creator their *own* captions back — but it does apply the moment this
  feature's output is ever shown to a brand (§5).
- **The separate, stopped Co-pilot** — `CreatorNudgeService` matches NEWS trends to a creator's
  theme tags. Swapnil has stopped its enablement (category promise undelivered — F-0782 — and
  language coverage — F-0783). **This spec is a different signal (the creator's own audience and
  post history), not a re-enablement of that track**, and should not be confused with it in
  planning. They may eventually share a delivery surface (§4) but not before both are correct on
  their own.

---

## 2. What "audience-informed content suggestion" concretely means

Not: "here is a trending topic" (that's the Co-pilot). This is: **"here is what has actually worked
for you, and who it worked with."** Three source signals, all already on disk:

1. **Per-post performance** — `ContentPerformanceResponse` rows. Which `mediaType` gets more
   `saves` vs `shares`? Which posts have unusually high `avgWatchTimeSeconds`? These are patterns
   in the creator's own history, not a model's opinion.
2. **Audience composition** — `CreatorDemographicsResponse`, sourced from `AudienceDemographics`
   (`age_gender_breakdown_json, country_breakdown_json, city_breakdown_json, locale_breakdown_json`,
   `influora-api/src/main/java/com/influora/domain/entity/AudienceDemographics.java:59-71`).
3. **Trajectory** — `CreatorMetric` time series, once F-0793 is fixed. Is engagement rising or
   falling? A suggestion that ignores trend direction is a snapshot, not an insight.

**SR-1 applies in full** (`influora-api/src/main/java/com/influora/web/dto/meera/MeeraContextDtos.java:81`):
every number this feature puts in front of a creator must be system-computed from these tables.
Never let a creator's own claim about their audience ("my followers are mostly Gen-Z") substitute
for `AudienceDemographics`. If the table is empty, say "not enough data yet" — never fabricate.

---

## 3. TARGET — three build options, in increasing cost

### Option A — widen `metrics_summary` (cheapest, ships inside Phase 2)
Add to `buildMetricsSummary`: `post_count` (already populated, `mediaCount`, one line — see
F-0797), then `avgReachPerPost`/`avgEngagementRate` once F-0793 computes them, then a compact
top-line from `ContentPerformanceResponse` ("your Reels average 2.3x the saves of your Feed
posts"). This is a **conversational** improvement only — Meera can *talk about* the pattern, she
cannot yet proactively surface one. No new tool, no new permission, additive to the existing
drift-tested map.
**Estimate:** 1-1.5 days, most of which is Phase 2's F-0793 work already planned.

### Option B — a creator-side read tool
Register a new on-behalf scope entry, e.g. `get_content_insights`, following the exact
`OnBehalfAuthResolver.resolveForWorkspaceRequiringScope` pattern already proven for
`get_campaign_performance`. Lets the creator *ask* ("what should I post about this week?") and get
a structured answer computed from live data at that moment, rather than a static context snippet.
Read-only — same tier as `show_creators`, no elevated-role check needed.
**Estimate:** 2-3 days. Needs a creator-side executor package (`service/meera/tool/creator/` —
does not exist today) and its own scope constant, tested the same way `SCOPE_DEFAULT` is.

### Option C — proactive suggestion (the daily-nudge shape)
A scheduled job scores "worth surfacing" patterns (a format outperforming another by some
threshold, a demographic shift, a stalled metric) and writes a suggestion row a creator sees
without asking — structurally identical to `CreatorNudgeService`'s daily-cap pattern
(`creator_nudge_log`, one-per-day, idempotent same-day read), but scored on **the creator's own
history**, not on external trends. This is new surface, new tables or a new `nudge_source`
discriminator on the existing ones, and a real design pass.
**Estimate:** 1-1.5 weeks. Not a Phase 2 add-on — treat as its own phase.

**Recommendation: build A now (it's nearly free given Phase 2 is already planned), and decide B vs
C only after A ships and someone has actually seen what Meera says with real numbers in hand.**
Building C before A ships risks re-discovering, in a new proactive surface, the exact category and
language gaps that stalled the Co-pilot.

---

## 4. Hard boundaries this feature inherits

1. **No caption text to any model beyond what the creator already sees of their own content.**
   `ContentPerformanceResponse` already omits it — keep any new DTO the same way.
2. **Never let this feature's output reach a brand.** The moment "audience-informed content
   suggestions" becomes something shown on a creator's *public* or *brand-visible* profile, the
   LOCKED caption boundary and the Phase 2.5 pre-consent ruling (`wiki/tech/profile-data-model.md`
   §8.2) both apply. This spec is creator-private by default; making any part of it visible to a
   brand is a **separate decision**, not an extension.
3. **SR-1** — every number is system-computed. No self-reported audience claims.
4. **Honest-null discipline** — an unmeasured demographic must render as "not enough data," never
   as `0`. This repo has already shipped and fixed the fabricated-zero version of this bug twice
   (F-0295, F-0260, BR-18).
5. **Do not conflate this with the stopped Co-pilot track.** Different signal, different ticket
   family, should not inherit F-0774/F-0778/F-0782's blockers by association — but should learn
   from them (§3, Option C note).

---

## 5. Open questions — need a ruling before Option B or C starts

1. Does this ship as a widened `metrics_summary` conversational feature only (Option A), or does
   the roadmap actually want a proactive daily surface (Option C)? Different cost, different phase.
2. If Option C: does it share `creator_nudge_log`/the daily cap with the existing Co-pilot, or get
   its own table? Sharing risks re-entangling two tracks that should stay independently correct.
3. Language coverage — `ContentPerformanceResponse` has no language field, so unlike the Co-pilot's
   theme-matching (F-0783), this feature's *data* is language-agnostic. The *AI's commentary on it*
   is not — same `hi-IN` default question from `wiki/tech/profile-data-model.md` §4.4 applies here.

---

## 6. What I did not verify

- No live data — no creator has real rows in `media_metrics`/`AudienceDemographics` observed this
  session. Every claim here is a code read of the pipeline's shape, not its output.
- I did not confirm the other five Meera tool executors' repository injections beyond
  `ShowCreatorsExecutor` — Option B's `OnBehalfAuthResolver` pattern is verified against that one
  and against the resolver source directly, not against all six.
- ~~I did not check whether `getMyContentPerformance` paginates~~ — checked after the first draft:
  `AnalyticsService.buildContentPerformanceResponse`
  (`influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java:336-339`) is
  bounded — `PageRequest.of(0, CONTENT_PERFORMANCE_LOOKBACK)` with `CONTENT_PERFORMANCE_LOOKBACK =
  100` (`:56`), deduped to the latest poll per distinct `mediaId`. Safe to feed a prompt without an
  extra cap in Option A/B — 100 rows is still too many to paste in raw; a build should aggregate
  (top N by a chosen metric) rather than pass all 100 rows verbatim.
