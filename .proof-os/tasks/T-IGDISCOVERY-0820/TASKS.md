# T-IGDISCOVERY-0820 — Meta-sourced creator discovery (Business Discovery + Creator Marketplace)

Opened 2026-08-20. Owner: vikram (backend) + ananya (frontend). Reviewer: kabir.

## Why

`CreatorDiscoveryService` searches ONLY Influora's own `creator_profiles` table
(`CreatorProfileSpecifications` — name / city / followers / engagement / verified / rate /
platform / category / language, all local columns). A creator who has not signed up does not
exist to a brand browsing Influora, and a creator who signed up but never connected Meta has no
real metrics. That is the marketplace cold-start problem, and no amount of UI work fixes it.

Meta exposes THREE different primitives here. They are not interchangeable, and the difference
matters — one of them is not discovery at all.

| Primitive | Answers | Creator must sign up? |
|---|---|---|
| Business Discovery | "tell me about @username" | No — but you must already know the handle |
| Hashtag Search | "who posts about #skincare" | No |
| Creator Marketplace API | "find creators matching these filters" | No — Meta-side roster |

## The rule, verified 2026-08-20

- **Business Discovery** (Facebook-Login path ONLY): follower count, media count, and per-media
  likes / comments / views for any Instagram professional account, by username. Age-gated
  accounts return nothing. A `GET` on a returned media id fails — the nested read is the only
  way in.
- **Hashtag Search**: returns MEDIA, not accounts, so creator identity must be resolved from the
  media. Hard cap: **30 unique hashtags per IG account per rolling 7 days** — a design
  constraint, not a rate limit. A naive "search whatever the brand types" UI burns the quota in
  an afternoon. Needs the `Instagram Public Content Access` feature plus `instagram_basic`.
- **Instagram Creator Marketplace API** (live since 2025-10-01, opened to agencies and
  developers; eligibility widened to Professional Mode profiles in Jan 2026): real filtered
  discovery — age, gender, country / US-state, follower count, engagement rate, 19 interest
  categories (max 5), posting activity, follower growth, audience composition, similar-creator
  lookup (max 5 usernames, onboarded creators only), custom audiences. Returns creator identity,
  verification, bio, follower count, engagement, **partnership experience and past
  collaborations**, plus media and audience insights (reach, interaction / hook rates for Reels).
  Discovery and evaluation ONLY — it cannot send a partnership proposal.

## Requirements that gate each one

- Business Discovery / Hashtag Search: Advanced Access on `instagram_basic`; Hashtag Search also
  needs the Public Content Access feature.
- Creator Marketplace API: `instagram_creator_marketplace_discovery` (**advanced access, app
  review**), `instagram_basic`, `pages_manage_metadata`, `pages_show_list`, `business_management`;
  `ads_management` only for custom audiences. **Page access token**, app registered as
  **Business type**, and the BRAND must itself be eligible for and onboarded (or onboarding) to
  Instagram's creator marketplace. Standard access returns TEST DATA ONLY.
  Rate limits: 1,000 requests/user/hour; app level 1000 x effective users.

## done_when

`gates/build.mvn.sh` and `gates/frontend.sh` exit 0 AND a brand search in Discover returns at
least one creator who has never signed up to Influora, with metrics sourced from Meta and
visibly labelled as such. (The live half is blocked on F-0365 plus the App Review chain.)

## Code changes

### Phase 1 — Business Discovery (smallest, unblocks enrichment)
1. New `integration/meta/client/BusinessDiscoveryClient` — nested read
   `/{ig-user-id}?fields=business_discovery.username({handle}){followers_count,media_count,media{...}}`.
   Facebook-Login tokens only; refuse an `INSTAGRAM_LOGIN` token rather than calling and 400ing.
2. It needs ONE connected app-user IG account to call through. Decide whose: a platform-owned
   account is a single point of failure and a single rate-limit bucket. Prefer the requesting
   brand's own connected account, fall back to platform.
3. Cache aggressively — public data that moves slowly, against a shared quota.

### Phase 2 — Creator Marketplace API (the real discovery win)
4. New `integration/meta/client/CreatorMarketplaceClient` plus DTOs for the creator, media and
   audience-insight shapes.
5. Map Meta's filter vocabulary onto `CreatorProfileSpecifications`' existing one. They do NOT
   line up: Influora has city / language / rate; Meta has US-state / interest-category /
   device-type and no rate at all. Do not pretend one is the other in the UI.
6. Merge strategy — the hard part. A creator can arrive from Meta, from the local table, or both.
   Dedupe on IG account id, prefer local rows (they carry rate, portfolio, past deals) and enrich
   with Meta metrics. Never show the same human twice.
7. Provenance on every row: Meta-sourced vs Influora member. A brand must know whether "message
   this creator" will actually reach anyone.

### Phase 3 — Hashtag Search (optional, quota-bound)
8. Only behind a curated hashtag set the platform controls, never free text from a brand.
   30 unique tags per 7 days is a product constraint; spending it on typos is not viable.

### Frontend
9. `creator-discovery.tsx` — a source toggle (Influora members vs all of Instagram), provenance
   badges, and an invite CTA for non-members. The invite path is the point: discovery without one
   just shows brands creators they cannot hire.

## Open decisions for Swapnil

- **Whose token calls Business Discovery** — a platform account (simple, one bucket, one point of
  failure) or the brand's own connected account (scales, but only works for connected brands)?
- **Do we onboard as a Creator Marketplace partner at all?** It needs Business-type app
  registration and per-brand marketplace eligibility. It is the highest-value item here AND the
  clearest strategic tension: Meta announced at Cannes 2026 that Creator Marketplace and the
  Partnership Ads Hub merge into a single "Meta Creator Marketing Hub" later in 2026. That is
  Influora's core surface, first-party. Building ON it is defensible; competing head-on with it
  on discovery alone is not. This deserves an explicit decision rather than drift.
- Should a Meta-sourced, non-member creator appear in search results at all, given they never
  consented to being listed by us?

## Blocked by / related

- **F-0365** — app in Development Mode; nothing here can be verified live.
- App Review chain — Marketplace discovery needs its own advanced-access permission on top of the
  Instagram permissions already pending.
- **T-IGLOGIN-0820** — Business Discovery and the Marketplace API are Facebook-Login only. This is
  the second concrete reason to keep that path rather than moving wholesale to Instagram Login.

## NOT CHECKED

Whether Influora's Meta app can register as Business type without disrupting the existing app
(app type is fixed at creation for some products); whether a brand NOT onboarded to Instagram's
creator marketplace can be onboarded by us or must do it themselves; and the field-level response
shapes, which were read from the API overview rather than from a live call.
