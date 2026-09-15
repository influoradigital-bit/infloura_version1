# Feature: Analytics & Creator Scoring

**Business Purpose** — Gives brands vetting/measurement data on creators and gives creators a self-service view. Three data planes: platform-pulled Instagram time-series, computed scores (fake-follower / quality / estimated rate), and creator-reported deliverable metrics. This powers discovery ranking and brand confidence.

**Who uses it** — Brands (view creator analytics, usage-capped), creators (self analytics), the scoring/polling jobs.

## User Roles
Brand (creator analytics, capped by plan), Creator (self analytics, uncapped).

## Permissions
Brand analytics require authorization to view the creator (`MetricsAuthorizationService`) and are usage-capped on Free. Creator-self is not capped.

## Business Flow
```
Meta OAuth connect → MetricsPollingJob (6h) → creator_metrics
  AudienceDemographicsJob (weekly) → audience_demographics
  ScoreCalculationJob (daily) → creator_scores (fake-follower, quality, rate; brand-safety NULL)
Brand views analytics (capped) / Creator views self analytics / deliverable metrics (creator-reported)
```

## Frontend
- **Pages**: `brand-analytics`, `brand-creator-analytics`, `creator-analytics`.
- **Components**: `analytics/*` (`CreatorMetricsCard`, `MetricsTrendChart`, `EngagementRateGauge`, `FakeFollowerIndicator`, `QualityScoreDisplay`, `BrandSafetyBadge`, `AudienceDemographicsPanel`, `ContentPerformancePanel`).
- **Hooks**: `analytics/*`.

## Backend
- **Controllers**: `AnalyticsController` (`/analytics/creators`), `CreatorAnalyticsController` (`/creator/analytics/me`).
- **Services**: `service/analytics/AnalyticsService`, `service/scoring/*` (`FakeFollowerDetectionService`, `QualityScoreService`, `RateEstimationService`, `BrandSafetyScoreService`), `UsageCounterService`.
- **Jobs**: `MetricsPollingJob`, `AudienceDemographicsJob`, `ScoreCalculationJob`.

## Database
`creator_metrics` (V21), `media_metrics` (V21/V26), `audience_demographics` (V25), `creator_scores` (V22), `deliverable_metrics` (V19), usage counters (V16/V54/V58). [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../database.md]" link — docs/docs/ contains only features/, so that file never existed].

## APIs
`GET /analytics/creators/{id}/{metrics,scores,demographics}`, `GET /creator/analytics/me/{metrics,scores,demographics,media}`.

## AI
Scores are computed by pure-function scoring services (not the LLM). Brand-safety (GARM) comes from `BrandSafetyAiClient` via `BrandSafetyScoreService`, called from `ScoreCalculationJob` (ScoreCalculationJob.java:344) [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0585: this is wired, not absent — it is gated OFF by default behind `influora.brand-safety-scoring.enabled` (BrandSafetyScoringProperties.java), so the 3 columns stay NULL until that flag is turned on].

## Notifications
None.

## Dependencies
- **Depends on**: Meta integration (source data), billing (usage caps).
- **Depended on by**: discovery (ranking, scores), reports.

## Connected Files
`AnalyticsController`, `CreatorAnalyticsController`, `AnalyticsService`, `service/scoring/*`, `AnalyticsUsageCapInterceptor`, `job/{MetricsPollingJob,AudienceDemographicsJob,ScoreCalculationJob}`.

## Execution Flow
```
Brand: GET /analytics/creators/{id}/metrics → AnalyticsUsageCapInterceptor (dedup per creatorId, cap check → 402)
  → AnalyticsController → AnalyticsService (latest tile + trend) → response (hasData=false if empty)
Score job: ScoreCalculationJob (daily 04:00) → per connected creator → scoring services → creator_scores row
```

## Error Handling
`INVALID_DATE_RANGE` (400), `FORBIDDEN` (403), `SCORE_NOT_FOUND` (404), `UPGRADE_REQUIRED` (402 cap). Empty data returns typed-empty (`hasData=false`), never fabricated.

## Security
Brand analytics authorized per creator; media captions never brand-facing; creator-self requires CREATOR type.

## Performance
Time-series indexed by `(entity, time)`; usage-cap dedup per creator (4 sub-endpoints = 1 unit); Meta pre-flight rate-limiting.

## Testing
Scoring + analytics tests. Regression risks: cap dedup, score math, empty-shape honesty.

## Production Readiness
- **Health**: 6/10 · **Completion**: ~70%
- **Known issues**: [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0585: brand-safety scoring is wired but disabled by default (see AI section above), not "not wired"]; `audienceMatch` hardcoded 50; [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0608: per-post `media_metrics` polling IS wired — `MetricsPollingJob.pollRecentMedia` (MetricsPollingJob.java:278-322), gated by `influora.meta.media-metrics-enabled` (default true)]; only latest snapshot available to the score job (growth-spike signal never fires); no YouTube. [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../known-limitations.md]" link — docs/docs/ contains only features/, so that file never existed].
- **Last verified**: 2026-07-15
