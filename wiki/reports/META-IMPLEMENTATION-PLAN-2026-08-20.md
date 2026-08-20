# Meta build plan — what Influora must implement, as the business owner

Companion to [META-FEATURE-SURFACE-VS-CODE-2026-08-20.md](META-FEATURE-SURFACE-VS-CODE-2026-08-20.md).
Priorities are business-value judgements (BELIEVED). The permission/code columns are the
verified facts from that report (PROVED).

## 0 · The one fact that reframes the whole roadmap

`CreatorDiscoveryService.search()` queries our own `CreatorProfile` table behind
`CreatorProfileSpecifications.discoverable()`. **A brand on Influora can only discover
creators who already signed up with us.** Every Meta discovery product below attacks that
ceiling directly — that is why they outrank the polish items.

Second fact: notifications are **email-only** (`integration/msg91/Msg91EmailClient.java`).
No WhatsApp channel exists in an India-first product.

## 1 · Blockers that gate everything

| # | Blocker | Why it stops work | Owner |
|---|---|---|---|
| B1 | App is `dev_mode` / `is_live: false` | Only users with a role on app `850102124044922` can complete OAuth. No real creator can connect. | Business |
| B2 | ~~Privacy policy / business verification will fail App Review~~ **CORRECTED 2026-08-20** — Meta's own `devtools_app_review requirements` returns `has_privacy_policy: true`, `business_verification_passes: true`, and `devtools_compliance` returns `overall_status: compliant`, 0 violations. The real gate is `can_submit: false` ("a previous submission is in review") while `status` reports `UNSUBMITTED` / `is_pending: false` — the two endpoints disagree and a human must check the dashboard. Data Deletion Callback URL is still `null` and the ToS URL still points at `facebook.com`; neither blocks today. | Blocks the next submission, not the current permissions | Business |
| B3 | We store **user** tokens only (`MetaOAuthToken`) | Creator Marketplace and Creator Discovery both require **Page** access tokens | Backend |
| B4 | `pages_read_engagement` is REJECTED | Dead method `FacebookPageClient.getPage()` — ledger **F-0375** | Backend |
| B5 | Graph pinned `v25.0`, Meta on `v26.0` | Not broken yet; becomes a break at the next deprecation cycle | Backend |

## 2 · What to implement — ranked

| # | Feature | Business unlock | Permissions | Have it? | Effort | Priority |
|---|---|---|---|---|---|---|
| 1 | **Instagram Business Discovery** | Look up **any** public IG business/creator by username — followers, media, engagement — with **no creator signup**. Turns discovery from "our registered creators" into "every IG creator in India". Also lets us pre-fill a profile before the creator ever joins. | `instagram_basic` + a connected IG business account | ✅ **already granted** | S · ~1 wk | **P0** |
| 2 | **Meta Webhooks** (IG `comments`, `mentions`, `story_insights`; Page `feed`) | Deliverable proof latency drops from ≤6h (`MetricsPollingJob` cron) to seconds. Kills the "did they actually post it" dispute class. Currently **0 subscriptions**. | app-level webhook config + `instagram_basic` | ✅ perms held; 0 subscriptions | M · ~2 wks | **P0** |
| 3 | **Conversions API** | We already have Shopify + WooCommerce + coupon + `ConversionWebhookController`. Sending campaign-driven purchases back to Meta closes the ROI loop and gives brands retargeting audiences. A CAPI app (`146672604305308`) already exists, unwired. | CAPI dataset token | ⚠️ app exists, zero code | M · ~2 wks | **P1** |
| 4 | **Instagram Creator Marketplace API** | Brand-side discovery on Meta **first-party** data: audience demographics, `reels_hook_rate`, `past_brand_partnership_partners`, branded-content history. Data no scraper can match. This is marketplace-grade supply. | `instagram_creator_marketplace_discovery` (Advanced, App Review) + `instagram_basic` ✅ + `pages_manage_metadata` + `pages_show_list` ✅ + `business_management` ✅ | ⚠️ **3 of 5 held** | L · ~4–6 wks + review | **P1** |
| 5 | **WhatsApp Business Platform** | India-first product with email-only notifications. Campaign invites, deliverable reminders, payout alerts on the channel creators actually read. Direct retention lever. | WhatsApp Business account + template approval | ❌ | M · ~3 wks | **P1** |
| 6 | **Content Publishing API** | Creator publishes the deliverable *through* Influora — scheduling, guaranteed proof-of-post, and we hold the posting relationship. Strong retention moat. | `instagram_business_content_publish` (IG-Login path) or `instagram_content_publish` | ❌ | M–L · ~3–4 wks | **P2** |
| 7 | **Partnership Ads via Marketing API** | Brands boost creator posts as partnership ads inside Influora. This is a **revenue line** (ad-spend management fee), not just a feature. | `ads_management` + branded-content handshake | ❌ | L · ~6 wks | **P2** |
| 8 | **Comment Moderation + Private Replies** | Managed-service layer on campaign posts — reply, hide, auto-DM commenters. Upsell for agency-tier brands. | `instagram_business_manage_comments` / `instagram_manage_comments` | ❌ | M · ~2 wks | **P2** |
| 9 | **Facebook Creator Discovery API** | Adds FB creator supply and returns `creator_email`. Same code shape as #4, so cheap once that exists — but IG dominates our market. | `facebook_creator_marketplace_discovery` + `pages_show_list` ✅, Business-type app, Advanced Access | ⚠️ 1 of 2 held | M · ~2 wks after #4 | **P3** |
| 10 | **oEmbed** | Legally correct embedding of creator posts in portfolios. Today `creator-portfolio-public.tsx` renders raw `embedUrl` strings. | `oembed_read` | ❌ | S · ~3 days | **P3** |
| 11 | **Collaboration Invites** | Brand + creator co-authored posts — the post lands on both grids. Genuinely differentiating for campaign reach. | `instagram_business_content_publish` (rides on #6) | ❌ | S after #6 | **P3** |
| 12 | Mentions · Hashtag Search · Product Tagging · Copyright Detection · Upcoming Events · Self Messaging · Threads API | Nice-to-have. None move acquisition, retention, or revenue today. | various | ❌ | — | **P4 — defer** |

## 3 · Suggested phasing

**Phase 1 — clear the runway (weeks 1–3)**
B1, B2, B4, B5. Then ship #1 Business Discovery and #2 Webhooks — both run on permissions
we already hold, so neither waits on App Review.

**Phase 2 — close the loop (weeks 3–7)**
#3 Conversions API and #5 WhatsApp. Independent of Meta review; both are retention/ROI.
Submit the App Review bundle for #4 at the *start* of this phase — review latency runs in
parallel with this work.

**Phase 3 — marketplace-grade supply (weeks 6–12)**
#4 Creator Marketplace, which needs B3 (Page tokens) built first. Then #9 Facebook Creator
Discovery on the same scaffolding.

**Phase 4 — monetise (weeks 12+)**
#6 Content Publishing, #7 Partnership Ads, #8 Comment Moderation, #11 Collaboration Invites.

## 4 · The App Review bundle

App Review is the long pole and Meta reviews a submission as a unit, so bundling matters.
Recommended single submission after B1/B2 are fixed:

`instagram_creator_marketplace_discovery` · `pages_manage_metadata` · `ads_management` ·
`instagram_content_publish` · `instagram_manage_comments`

Each needs a screencast of the feature working in the product, which means the UI must
exist before submission — that is the real sequencing constraint, not the review queue.

## NOT CHECKED
- Effort estimates are judgement, not measurement — no spike was run against any endpoint.
- Whether our Business/brand accounts pass Meta's Creator Marketplace **eligibility** check.
- WhatsApp Business template approval timelines for the India region.
- Whether `instagram_business_content_publish` (IG-Login path) covers Reels, or whether the
  Facebook-Login `instagram_content_publish` variant is required for our deliverable mix.
