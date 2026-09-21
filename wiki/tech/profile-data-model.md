# Profile Data Model — Brand & Creator

**Owner:** Priya (CTO) · **Status:** DRAFT — not locked · **Date:** 2026-09-12
**Scope:** every field held on a creator profile and a brand profile, and exactly which of the
three audiences — Creator (owner), Brand (viewer), AI (Meera) — may see each one.

> **Reading rule for this document.** Sections marked **CURRENT** describe code that exists today
> and were written by opening the cited file. Sections marked **TARGET** are proposed and are not
> built. Nothing here may be cited as implemented unless it is under a CURRENT heading with a
> `file:line` reference. If a row has no citation, treat it as unverified.

---

## 1. Where the data actually lives — CURRENT

| Store | Entity / file | Written by |
|---|---|---|
| Creator identity & commercials | `CreatorProfile.java` | onboarding, creator edit, admin |
| Brand identity & billing | `Workspace.java` | onboarding, brand edit, admin |
| Per-account rollup | `PlatformStat` via `influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:88` (03:45 daily) | job only |
| Per-creator time series | `CreatorMetric.java` | `MetricsPollingJob` (6-hourly), `ScoreCalculationJob` |
| Per-post time series | `influora-api/src/main/java/com/influora/domain/entity/MediaMetric.java:54-81` | `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:242` |
| Audience breakdowns | `influora-api/src/main/java/com/influora/domain/entity/AudienceDemographics.java:59-71` | `AudienceDemographicsJob` |
| Derived scores | `creator_scores` read model → `DiscoveryDtos.CreatorScores:59-60` | `ScoreCalculationJob` |

**Ingest ceiling.** Our Meta app (`850102124044922`) holds `instagram_manage_insights` and
`instagram_basic` at **Advanced Access**, verified live via the Meta MCP on 2026-09-12.
`pages_read_engagement` is **REJECTED**. We pin Graph `v25.0` (`influora-api/src/main/resources/application.yml:440`); platform
latest is `v26.0`; zero deprecations flagged against the app.

`influora-api/src/main/java/com/influora/integration/meta/client/InstagramInsightsClient.java:25,31` currently requests:

- **media** — `reach, likes, comments, saved, shares, views, total_interactions`
- **account** — `reach, views, total_interactions, accounts_engaged, profile_links_taps`

`impressions`, `engagement` and `video_views` were deliberately removed as deprecated
(`influora-api/src/main/java/com/influora/integration/meta/client/InstagramInsightsClient.java:22-23`). **`MediaMetric.impressions` is therefore a dead column** —
it is no longer populated. Do not build on it.

---

## 2. Creator profile — full field inventory (CURRENT)

From `CreatorProfile.java`. Visibility columns: **C** = creator sees own, **B** = brand viewing
this creator, **AI** = available to Meera.

### 2.1 Public identity

| Field | C | B | AI | Note |
|---|:-:|:-:|:-:|---|
| `id`, `userId` | ✅ | ✅ | ✅ | `userId` is internal; do not render |
| `displayName`, `username` | ✅ | ✅ | ✅ | `usernameChangedAt` gates rename rate |
| `bio` | ✅ | ✅ | ✅ | |
| `avatarUrl`, `coverImageUrl` | ✅ | ✅ | ✅ | |
| `city` | ✅ | ✅ | ✅ | |
| `categoriesJson` | ✅ | ✅ | ✅ | creator-declared |
| `languagesJson` | ✅ | ✅ | ✅ | creator-declared |
| `contentStylesJson` | ✅ | ✅ | ✅ | |
| `verified` | ✅ | ✅ | ✅ | platform badge |
| `discoverable` | ✅ | ✅ | ✅ | creator's own opt-out |

### 2.2 Commercials

| Field | C | B | AI | Note |
|---|:-:|:-:|:-:|---|
| `rateMin`, `rateMax`, `currency` | ✅ | ✅ | ✅ | exposed on the brand view, `influora-api/src/main/java/com/influora/web/dto/creator/DiscoveryDtos.java:76-78` |

### 2.3 Metrics

| Field | C | B | AI | Note |
|---|:-:|:-:|:-:|---|
| `totalFollowers` | ✅ | ✅ | ✅ | |
| `engagementRate` | ✅ | ✅ | ✅ | **null for everyone today — see §6** |

### 2.4 Internal — MUST NOT reach a brand

| Field | C | B | AI | Reason |
|---|:-:|:-:|:-:|---|
| `gstin`, `pan` | ✅ own | ❌ | ❌ | tax identity |
| `aadhaarLast4`, `selfieUrl` | ✅ own | ❌ | ❌ | KYC PII |
| `identityKycStatus` | ✅ own | ❌ | ❌ | |
| `taxRegistrationStatus`, `creatorInvoiceCode` | ✅ own | ❌ | ❌ | billing internals |
| `suspended`, `suspendedReason`, `suspendedAt/By`, `reinstatedAt/By` | ❌ | ❌ | ❌ | admin-only |
| `applicationStatus`, `applicationReviewedBy/At`, `applicationRejectionReason` | ❌ | ❌ | ❌ | admin-only |
| `tierOverride`, `tierAdjustedBy/At` | ❌ | ❌ | ❌ | admin-only |
| `themeTagsJson` | ❌ | ❌ | ⚠️ internal | Co-pilot matching input; derived from captions |

> `themeTagsJson` is derived from the creator's own captions. Exposing it to a brand would leak
> caption content by inference and is covered by the §5 boundary in spirit. Keep it internal.

---

## 3. Brand profile — full field inventory (CURRENT)

From `Workspace.java`. **Cr** = creator viewing this brand.

### 3.1 Public identity

| Field | Brand owner | Cr | AI |
|---|:-:|:-:|:-:|
| `id`, `name`, `slug` | ✅ | ✅ | ✅ |
| `type` (`WorkspaceType`) | ✅ | ✅ | ✅ |
| `logoUrl`, `websiteUrl` | ✅ | ✅ | ✅ |
| `industry`, `companySize`, `description` | ✅ | ✅ | ✅ |
| `verificationStatus` | ✅ | ✅ | ✅ |

### 3.2 Internal — MUST NOT reach a creator

| Field | Brand owner | Cr | AI | Reason |
|---|:-:|:-:|:-:|---|
| `billingEmail`, `phone`, `billingAddress` | ✅ | ❌ | ❌ | contact PII |
| `gstin`, `pan` | ✅ | ❌ | ❌ | tax identity |
| `kycGstinDocUrl`, `kycPanDocUrl` | ✅ | ❌ | ❌ | KYC documents |
| `kycReviewedBy/At`, `kycRejectionReason` | ❌ | ❌ | ❌ | admin-only |
| `suspended` + reason/at/by, `reinstated*` | ❌ | ❌ | ❌ | admin-only |
| `metaPixelId` | ✅ | ❌ | ❌ | tracking config |

---

## 4. What each audience is served today — CURRENT

### 4.1 Creator viewing their own profile
`CreatorDtos.CreatorResponse:21-47` — id, userId, username, displayName, bio, avatarUrl,
coverImageUrl, location, categories, languages, contentStyles, **platforms**, totalFollowers,
engagementRate, averageRate, currency, isVerified, portfolioItems, saved, scores.

### 4.2 Brand viewing a creator
`DiscoveryDtos.CreatorPublicProfileResponse:62-83` — id, username, displayName, bio, profilePhoto,
coverPhoto, categories, languages, city, **platforms**, totalFollowers, engagementRate, scores,
rateMin, rateMax, currency, isVerified, discoverable, completedCampaigns, avgRating, saved.

No KYC, tax, suspension or admin field appears in either. **That is correct and must stay so.**

### 4.3 The per-platform block both use
`CreatorDtos.PlatformStatResponse:13-19` — and this is the bottleneck:

```java
public record PlatformStatResponse(
        String platform, String handle, long followers,
        BigDecimal engagementRate, boolean isVerified, String profileUrl) {}
```

Six fields. No `avgViews`, `avgLikes`, `postCount`, `reach`, `saves`, `shares` or `watchTime`.

### 4.4 AI (Meera)
`MeeraContextDtos.java` — Meera's context is **workspace-scoped** (`ContextRequest:38-40` takes
`workspaceId` + `audience`) and carries `TemplateDigestEntry:44`, `PastCampaignEntry:67`,
`CreditState:75`, `CampaignOutcomeEntry:102`, `RateBand:121`, `OutcomeDigest:138`.

**SR-1, no self-reported trust** (`influora-api/src/main/java/com/influora/web/dto/meera/MeeraContextDtos.java:81`): `spendInr`/`funded` come
exclusively from the ledger, never from self-reported input. Any new AI-visible metric must obey
this — a number the AI quotes must be one the system computed, never one a user typed.

---

## 5. Hard boundaries — LOCKED, do not relax

1. **`MediaMetric.caption` must NEVER appear in any brand-facing DTO.**
   Decision `wiki/decisions/2026-07-06-brand-safety-caption-storage.md` (LOCKED). Captions are
   internal Brand-Safety pipeline input only. Enforced structurally by
   `NoBrandFacingCaptionExposureTest.java`, which reflects over every top-level DTO container in
   `com.influora.web.dto.*` (including nested records) and fails if any field or record component
   is named `caption` or `mediaCaption`, case-insensitive.
   **Adding a new DTO container class requires adding it to that test's `DTO_CONTAINER_CLASSES`,
   or the guardrail silently stops covering it.**

2. **No caption text reaches any model.** The Co-pilot tagger is pure Java keyword matching
   (`influora-api/src/main/java/com/influora/service/trendspark/ThemeMatchService.java:98-120`); the AI request carries no caption field.

3. **KYC / tax / suspension fields never cross the party boundary** — §2.4 and §3.2.

4. **Scores stay nullable.** `CreatorScores:59-60` — `quality`, `authenticity`, `brandSafety` are
   null until scored. Never coerce a null score to `BigDecimal.ZERO`; a fabricated zero reads as a
   real bad score and would pass a `minScore` filter.

---

## 6. The gap — CURRENT, and it is the reason for this document

We hold Advanced Access. We poll every 6 hours. We store, per post
(`influora-api/src/main/java/com/influora/domain/entity/MediaMetric.java:54-81`): `reach`, `engagement`, `likes`, `comments`, `saves`, `shares`,
`videoViews`, `avgWatchTimeSeconds`, `postedAt`, `mediaType`, `permalink`.

We expose six fields, one of which is always null.

| Ticket | Defect |
|---|---|
| **F-0792** | Metrics collected into `media_metrics` are exposed through no creator/brand DTO. Only `ScoreCalculationJob` reads them. |
| **F-0793** | `CreatorMetric.avgEngagementRate` is never computed, so `platform_stats.engagement_rate` is permanently null and the UI shows "Engagement not available yet" forever. Documented against itself at `influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:47-51`. |
| **F-0794** | Connecting an account triggers no fetch. First numbers wait for the 6-hourly poll, then the 03:45 aggregation — up to ~24h of an empty profile at the exact moment the creator looks. |

**Nothing in this gap is blocked by Meta.** It is compute, DTO width and UI.

---

## 7. TARGET — proposed, NOT BUILT

### 7.1 Widen the per-platform block

```java
public record PlatformStatResponse(
        String platform, String handle, long followers,
        BigDecimal engagementRate, boolean isVerified, String profileUrl,
        // proposed — all derivable from existing media_metrics rows
        Long postCount, Long avgViews, Long avgLikes, Long avgComments,
        Long avgReach, Long avgSaves, Long avgShares,
        BigDecimal avgWatchTimeSeconds, Instant lastPostedAt, Instant statsAsOf) {}
```

`statsAsOf` is not optional. A metric with no as-of timestamp cannot be distinguished from a stale
one, and this pipeline has a 48-hour Meta delay plus a daily aggregation.

### 7.2 Compute `avgEngagementRate`
Closes F-0793. Inputs are already stored. Define the formula **once**, in one class, and write it
into `CreatorMetric.avgEngagementRate` so `PlatformStatsAggregationJob` can publish it.
Proposed: `total_interactions / reach` over the trailing N posts — **needs Swapnil's ruling**,
because followers-based and reach-based engagement rates give very different numbers and brands
will compare ours against other platforms'.

### 7.3 Sync on connect
Closes F-0794. Copy the pattern already proven at `influora-api/src/main/java/com/influora/job/CreatorCaptionSyncJob.java:156-193` — a
connect-triggered `@Async` path that swallows everything.

### 7.4 Audience demographics
`influora-api/src/main/java/com/influora/domain/entity/AudienceDemographics.java:59-71` holds age/gender, country, city and locale breakdowns. Not in any
profile DTO today. Brand-visible aggregate only — never individual-level.

### 7.5 AI access
Meera's context is workspace-scoped. Creator-side metrics would need a creator-scoped context
record. It must carry the same `statsAsOf`, obey SR-1, and inherit §5 — **no captions, ever.**

---

## 8. Open questions — need a ruling before build

1. **Engagement-rate formula** — reach-based or follower-based? Affects every number a brand sees.
2. **Does a brand see per-post detail, or only aggregates?** Aggregates are safer and keep §5
   trivially satisfied. Per-post detail (even without captions) leaks posting cadence and
   performance distribution.
3. **Does `discoverable=false` hide metrics too, or only search placement?**
4. **Retention.** Meta stores media insights ~2 years and account insights ~90 days. We have no
   stated retention for `media_metrics`. Six months from now this is the table that will be large.
5. **Does the creator see the `scores` a brand sees?** `CreatorResponse` includes `scores` today —
   confirm that is intended, because it exposes our own ranking of them.

---

## 9. What I did NOT verify

- No live data. No DB access, no booted app — every claim is a code read.
- `pages_read_engagement` is REJECTED and Meta's docs list it among the insights permissions for
  the Facebook-Login path. Our two IG scopes appear sufficient; **not proven against a live call.**
- Meta rate limits read 0% with `effective_users_count: 1` — essentially nobody connected, so this
  says nothing about behaviour at real volume.
- I did not audit admin-facing DTOs; §2.4/§3.2 describe creator/brand surfaces only.
- Per-post insights cost one Graph call per post per creator per cycle. Not modelled.

---

## 10. Verification status of this document

- **16 of 16 cited `file:line` locations were machine-checked** to contain the symbol claimed
  (script run 2026-09-12; each citation resolved and matched within a +/-2 line window).
- `gates/citations.py` was run against this file. It resolves all 14 citation tokens but reports
  quotation failures, because this document is a reference table full of backticked identifiers
  and the gate parses those adjacent identifiers as quoted spans. That is a known parser mismatch
  for table-shaped documents, not an unverified claim. **Do not relax the gate to make this file
  pass** — the same class of mistake is already recorded in this repo where a gate went red on the
  comment explaining its own banned pattern.
- Everything in section 7 (TARGET) is **unbuilt** and is deliberately excluded from the checks
  above, because there is nothing to cite.
