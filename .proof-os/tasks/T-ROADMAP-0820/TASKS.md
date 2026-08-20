# T-ROADMAP-0820 — Build plan: current features, new features, and how Meta data is used

Opened 2026-08-20. Owner: swapnil. Reviewer: kabir. Marketing: tejas.

**done_when:** the roadmap doc exists and every item names what already exists in code, what
is new, its Meta dependency, and its own exit gate.

This is the sequencing doc. It does not re-spec work that already has a task:
`T-IGLOGIN-0820` (dual login path — SHIPPED, commit c186477), `T-IGDISCOVERY-0820`
(Meta-sourced discovery), `T-META-0820` (the Meta audit that opened F-0355/0356/0365/0366/0367).

---

## 0 · What is actually true today (verified 2026-08-20, not from wiki trackers)

| Fact | How it was established |
|---|---|
| 78 backend controllers, 31 brand pages, 24 creator pages | directory enumeration |
| 4 scoring services work: fake-follower, quality, rate-estimation, brand-safety | `mvn test` — 78 tests, 0 failures |
| Scoring runs daily, `@Scheduled(cron = "0 0 4 * * *")` UTC | `ScoreCalculationJob:135` |
| Scoring FAILS CLOSED — no metrics means skip, never a fabricated score | `ScoreCalculationJob:273` |
| Metric provenance enforced at Java AND DB layer | `CreatorMetric` + migration CR-119 |
| Brand-safety scoring OFF by default (Priya ruling) | `ScoreCalculationJob:203` |
| Only two writers of `CreatorMetric`: `MetricsPollingJob` (Meta) and `PortfolioService` (self-reported) | caller scan |
| Coupon attribution closes end-to-end; **UTM conversion attribution is NOT wired** | `ShopifyWebhookController:62-67` |
| `usage_rights` is a free-text TEXT column, not a structured term | `Collaboration:53` |
| Meta app 850102124044922 is `dev_mode` / `is_live: false`, permissions at STANDARD access | DevTools MCP |

**The one sentence that explains the whole roadmap:** the product is feature-complete against
market standard, but the data layer that powers half of it is dark, because no real creator can
connect Instagram. Scoring is not broken — it is starved, and today it scores numbers creators
typed about themselves.

---

## 1 · Swapnil — the ruling

Meta App Review is the critical path for *discovery and proof*. It is NOT the critical path for
the company. Everything in Tier 1 below ships without it, and Tier 1 is where the defensibility
is. Do not let the org sit idle waiting on Meta.

Order: unblock Meta in parallel (it is paperwork, not engineering), build Tier 1 now.

## 2 · Tejas — the positioning this plan serves

Sell the risk reversal we can already keep: **"Pay when they post. Not before."** Discovery is
commodity and eventually Meta's. Proof and payment are ours. Every item below is ranked by how
much it moves us toward selling outcomes rather than access.

Claim "guaranteed" only on the rung we can actually pay out. Rung ladder lives in
section 5.

---

## TIER 0 — Unblock (paperwork, no engineering, blocks everything Meta-shaped)

| # | Item | Exists | New | Meta dep | Exit gate |
|---|---|---|---|---|---|
| 0.1 | Real privacy-policy + ToS URLs on `influora.in` | app registered `app.influora.io`, **domain does not resolve** | correct URLs | — | `curl -sfI <url>` returns 200 for both, asserted by a gate over every externally-registered URL |
| 0.2 | Data Deletion + Deauthorize callbacks | absent (0 grep hits) | 2 endpoints | — | endpoints return Meta's required JSON shape; `MetaOAuthControllerTest` covers both |
| 0.3 | Verify contact email, set base domains | `contact_email_verified: false` | — | — | `devtools_app basic_settings` shows verified + base domains non-null |
| 0.4 | Submit App Review, obtain ADVANCED access, take app LIVE | STANDARD only | — | — | `devtools_app_review privileges` shows `access_level: advanced`; `is_live: true` |
| 0.5 | Enable App Secret Proof + send `appsecret_proof` | `require_app_secret: false`, client sends none | client change | — | `devtools_app security` shows true AND `MetaGraphApiClientTest` asserts the param |

Ledger: F-0356, F-0365, F-0366, F-0367.

---

## TIER 1 — Build now, zero Meta dependency (the moat)

| # | Item | Exists | New | Exit gate |
|---|---|---|---|---|
| 1.1 | **Structured usage rights + exclusivity** | `Collaboration.usage_rights` free text | term model: channel (organic/paid/web/in-store/OOH), duration, territory, exclusivity + lockout window, whitelisting permission, price delta per dimension | migration applies; contract PDF renders each term; a dispute can cite a term id; `build.mvn.sh` exits 0 |
| 1.2 | **Wire UTM conversion attribution** | coupon path live; UTM path explicitly NOT wired | carry the UTM campaign ULID through checkout to the store webhook | a seeded order with a UTM param increments `UtmCampaign.conversionCount` and `revenueAttributed`; test asserts it |
| 1.3 | **Mandatory coupon per campaign** | `CouponCodeService` exists, optional | make it a campaign invariant | campaign create without a coupon is rejected; test asserts the 400 |
| 1.4 | **Bulk cohort operations** | single-creator flows only | hire N, one brief, one approval queue, one escrow, one consolidated invoice, N payouts | a 30-creator cohort completes end-to-end in test; one escrow row, 30 payout rows |
| 1.5 | **Guaranteed Delivery packaging** | escrow + contract + verification all exist | product tier + copy + refund/replace rule | policy encoded, not prose: a missed deliverable triggers refund-or-replace automatically; test asserts |
| 1.6 | **Creator substitution bench** | none | replace a ghosting creator mid-campaign without voiding the contract | substitution preserves escrow and contract; test asserts no orphaned milestone |
| 1.7 | **Rate benchmarking (free hook)** | transacted rates exist in deals | aggregate by follower band / niche / city, publish | aggregation never exposes an individual deal; k-anonymity gate |
| 1.8 | Threads API | none | publish + insights + reply moderation | separate product, separate permissions; `build.mvn.sh` exits 0 and a live Threads read succeeds |

---

## TIER 2 — Unlocked the moment Tier 0 lands (no new build, data starts flowing)

| # | Item | What changes | Exit gate |
|---|---|---|---|
| 2.1 | Fake-follower detection becomes real | already built and tested; starts receiving `META_API` metrics instead of `CREATOR_REPORTED` | a scored creator's `CreatorMetric.dataSource == META_API` |
| 2.2 | Quality + rate estimation become real | same | same |
| 2.3 | Verified badge means something | `isPlatformVerified()` already derives from provenance | a brand-visible "Verified" is provably backed by a META_API row |
| 2.4 | **F-0355 remainder — audience demographics migration** | `audience_*` metrics dead; needs `follower_demographics` + breakdown + timeframe. **Decision pending: `locale` has no replacement and age/gender split into two breakdowns** | job persists a demographics snapshot from a live account; `AudienceDemographicsJobTest` asserts the new shape |
| 2.5 | Meta-sourced discovery | `T-IGDISCOVERY-0820` | that task's own gate |

**Open decision blocking 2.4:** drop `localeBreakdownJson`, and store age and gender separately?
That is a schema and product change, not a rename. Swapnil to rule.

---

## TIER 3 — Needs transaction history (the business)

| # | Item | Prerequisite | Exit gate |
|---|---|---|---|
| 3.1 | **Influora Score** — rank by outcome, not audience | Tier 1.2/1.3 running at volume | score reproducible from journal data; no model in the computation path |
| 3.2 | Predicted CPA per creator | 3.1 | back-test: predicted vs actual CPA within a stated error band on held-out campaigns |
| 3.3 | Mid-campaign budget reallocation | 3.1 + cohorts | reallocation improves cohort CPA vs a control split in test |
| 3.4 | **Outcome underwriting** — guarantee conversions | 3.2 error band tight enough to price | a written pricing model with a stated loss ratio, approved by rohan. NOT a model opinion |
| 3.5 | Whitelisting / Partnership Ads | Tier 1.1 (needs the usage-rights grant) + Facebook-Login path | ad created from a creator handle in test |

---

## 3 · How Meta data is used, for now

Precisely, so nobody over-claims:

- **Today:** no creator can connect. Every metric in the product is `CREATOR_REPORTED` and marked
  unverified. Scoring runs and correctly skips creators with no metrics.
- **After Tier 0:** `MetricsPollingJob` populates `META_API` rows; the four scoring services light
  up with no code change; the Verified badge becomes meaningful.
- **Meta is an INPUT, never the moat.** Everything Meta returns, Meta returns to every licensee.
  The proprietary layer is what Meta never sees: transacted rate, delivery, dispute, redemption,
  repeat-hire.
- **The DevTools MCP is internal tooling, not a product feature.** Put it on a cron for
  deprecation early-warning (it would have caught F-0355) and the Data Access Renewal clock.
  It never appears on a roadmap slide.

## 4 · Ranked next three

1. **1.1 structured usage rights** — pure backend, no Meta, unlocks revenue immediately,
   prerequisite for 3.5
2. **Tier 0** — paperwork, in parallel, unblocks an entire tier at once
3. **1.2 + 1.3 attribution** — without these the Score in Tier 3 has one channel of truth

## 5 · The guarantee ladder (Tejas — do not skip rungs)

| Rung | Promise | Buildable |
|---|---|---|
| 1 | The post happens, on time, on brief | **now** (1.5) |
| 2 | Real audience, no fake followers | after Tier 0 |
| 3 | Clicks / redemptions | mostly now (1.2/1.3) |
| 4 | Conversions / CPA | Tier 3 only |

## 6 · NOT CHECKED

Whether discovery RANKING respects the verified/unverified provenance flag — scoring labels it
correctly, but if ranking ignores the label an unverified creator can outrank a verified one.
Whether the four scoring services produce *useful* scores on real Meta data, as opposed to
running without error — 78 passing tests prove they compute, not that the numbers are good.
Market-standard comparison in section 0 is against named competitors from memory, not a
structured competitive audit.
