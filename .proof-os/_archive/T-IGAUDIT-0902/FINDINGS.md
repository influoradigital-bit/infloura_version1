# Instagram/Meta feature claims — T-IGAUDIT-0902

Repo root: C:/Users/Sage world/Downloads/New Influora Ai/New Influora
SRC = influora-api/src/main/java

Each row is a claim. Verify each independently against the cited file and line.
Record CORRECT / INCORRECT / IMPRECISE per row id.

| id | claim | citation |
|----|-------|----------|
| C1 | `MetricsPollingJob` is scheduled every 6h and writes CreatorMetric rows from the Meta API | SRC/com/influora/job/MetricsPollingJob.java:95 |
| C2 | `MetricsPollingJob` does NOT persist per-post media insights; its javadoc states this is intentionally not wired up | SRC/com/influora/job/MetricsPollingJob.java:44-50 |
| C3 | `MediaMetric` rows are NEVER written anywhere in main or test source; `mediaMetricsRepository.save` has zero call sites | SRC (repo-wide grep), influora-api/src/test/java |
| C4 | `ScoreCalculationJob` READS media_metrics, so `recentMedia` is always an empty list at runtime | SRC/com/influora/job/ScoreCalculationJob.java:279-281 |
| C5 | `QualityScoreService.calculate` guards `latestMetric.isEmpty()` but has NO guard for `recentMedia.isEmpty()`; it proceeds and returns a score | SRC/com/influora/service/scoring/QualityScoreService.java:44-47 |
| C6 | With an empty recentMedia list the four components are deterministically engagement=0, consistency=50, frequency=0, audienceMatch=50 | QualityScoreService.java:82, 101, 121, 65 |
| C7 | Therefore every connected creator receives a composite quality score of exactly 20.00/100, computed as (0*0.40)+(50*0.25)+(0*0.20)+(50*0.15) | QualityScoreService.java:68-73 |
| C8 | `getAccountInsights` has zero production callers outside its own declaration | SRC/com/influora/integration/meta/client/InstagramInsightsClient.java:115 |
| C9 | `getMediaInsights` IS called by exactly two production callers | SRC/com/influora/integration/meta/service/InstagramMetricsFetcher.java:209 and SRC/com/influora/service/verification/DeliverableVerificationService.java:244 |
| C10 | No code anywhere calls the Instagram `/stories` edge; the only STORY tokens are a DeliverableType enum value and a Meera campaign-type set | SRC/com/influora/domain/enums/DeliverableType.java:7, SRC/com/influora/service/meera/tool/CreateCampaignExecutor.java:87 |
| C11 | The only controller under integration/meta/webhook exposes exactly two POST mappings, both Meta compliance callbacks (deauthorize, data deletion). There is no webhook subscription/verify endpoint | SRC/com/influora/integration/meta/webhook/MetaPlatformCallbackController.java:137, 206 |
| C12 | `instagram_manage_comments` appears zero times in the Java source | SRC (repo-wide grep) |
| C13 | `creator_marketplace` / `marketplace_discovery` appear zero times in the Java source | SRC (repo-wide grep) |
| C14 | REQUIRED_SCOPES contains exactly instagram_basic, instagram_manage_insights, pages_show_list | SRC/com/influora/integration/meta/oauth/MetaOAuthService.java:39-46 |
| C15 | `pages_read_engagement` was deliberately REMOVED from REQUIRED_SCOPES under CR-115 | SRC/com/influora/integration/meta/oauth/MetaOAuthService.java:30-38 |
| C16 | The creator discovery search endpoint exposes minEngagementRate/maxEngagementRate filter params | SRC/com/influora/web/CreatorController.java:53-54 |
| C17 | The creator discovery search endpoint exposes ZERO audience/demographic filter params (no audience, gender, age-range or country filters) | SRC/com/influora/web/CreatorController.java:43-58 |
| C18 | `PlatformStat.verified` is set from `metric.isPlatformVerified()`, not hardcoded | SRC/com/influora/job/PlatformStatsAggregationJob.java:226 |
| C19 | `CreatorMetric`'s builder defaults a missing dataSource to CREATOR_REPORTED (fail-closed), not META_API | SRC/com/influora/domain/entity/CreatorMetric.java:38-48 |
| C20 | `AudienceDemographicsJob` is scheduled weekly (Sundays 03:30) and calls getAudienceDemographics | SRC/com/influora/job/AudienceDemographicsJob.java:96, 188 |
| C21 | `CreatorCaptionSyncJob` is scheduled daily and is gated on influora.creator-copilot.enabled | SRC/com/influora/job/CreatorCaptionSyncJob.java:85, 89 |
| C22 | Meta OAuth is OFF unless META_APP_ID and META_APP_SECRET are both non-blank; application.yml supplies blank defaults deliberately | influora-api/src/main/resources/application.yml:342-350 |
