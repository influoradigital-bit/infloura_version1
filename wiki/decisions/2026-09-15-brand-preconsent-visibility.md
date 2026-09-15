# Ruling: what a brand sees before a creator connects

> **Decision by:** Swapnil Maruti (CEO) — final authority on business direction
> **Advised by:** Priya (CTO)
> **Date:** 2026-09-15
> **Status:** LOCKED
> **Unblocks:** Task 2.5b (`wiki/processes/task-creator-profile-and-copilot.md`), therefore Tasks
> 2.5c/d/e

---

## The question

Every rich creator metric — audience demographics, per-post performance — is gated behind
`MetricsAuthorizationService.resolveAuthorizedCreatorProfileId`
(`influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java:65-75`), which
403s unless a `MetaOAuthToken` pairing already exists for that `(workspace_id,
creator_profile_id)` — i.e. unless the creator has already connected to that specific brand
(**F-0796**). That is correct as a consent gate. The gap is that nothing replaces it *before*
consent, so a brand evaluating a creator for the first time — the actual moment of the buying
decision — sees almost nothing: followers, an (until Task 2.1) null engagement rate, a rate range,
and a completed-campaigns count.

## Decision: coarse aggregate bands pre-consent, full detail post-connection. LOCKED.

**Before a collaboration exists**, a brand sees:

- Exact `followers`, `engagementRate` (once Task 2.1 ships), `rateMin`/`rateMax`, `scores`,
  `completedCampaigns`, `avgRating` — everything already in `CreatorPublicProfileResponse` today
  (`influora-api/src/main/java/com/influora/web/dto/creator/DiscoveryDtos.java:62-83`). No change
  here.
- Audience demographics as **bands, not numbers** — an age-skew description ("mostly 18–34"), a
  gender-majority description ("majority women"), and a **top-3 cities list with no percentages**.
  Never an exact age/gender/city breakdown.
- Portfolio thumbnails **without** per-post performance numbers overlaid.

**After a collaboration exists** (the `MetaOAuthToken` pairing gate fires open), the brand gets
everything: exact demographic percentages via `CreatorDemographicsResponse`
(`influora-api/src/main/java/com/influora/web/AnalyticsController.java:84-89`), per-post
performance via `ContentPerformanceResponse`
(`influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java:167-179`), and
performance numbers overlaid on the portfolio grid.

**Why bands, not zero and not exact:**

- Zero pre-consent detail leaves brands picking creators on follower count and star rating alone —
  the marketplace's core job (matching a brand to the right audience) barely functions during
  discovery, which is exactly when the decision gets made.
- Exact pre-consent detail turns creator audience data into a free dataset any brand account can
  scrape without a creator ever agreeing to work with that brand — the opposite of what the
  `MetaOAuthToken` consent gate exists to prevent.
- Bands give a brand enough to shortlist ("skews younger, mostly Mumbai/Pune/Bengaluru — worth a
  look") without disclosing the creator's actual audience composition to someone they have not
  agreed to work with.

This is the exact split the `Creator Profile Lens` demo already visualizes
(`wiki/tech/creator-profile-lens-demo.html` — "Brand · Discovery" vs. "Brand · Connected" tabs) and
formalizes what that page was built to illustrate.

## Consequence

- Task 2.5a (wire the existing demographics endpoint) proceeds, rendering **bands** in the
  pre-connection view and full detail only once `MetricsAuthorizationService` clears.
- Task 2.5c (portfolio widening) ships performance-number overlays gated the same way.
- Task 2.5d/2.5e (work-quality metrics, past brands/reviews) follow the same split by default
  unless a specific field warrants a different line — flag it to Priya rather than assuming.

**Not decided here:** the exact wording of the band descriptions, or whether "top 3 cities" should
be 3 or some other small N. Ananya's call at implementation, consistent with the spirit above —
identify, don't quantify, pre-consent.
