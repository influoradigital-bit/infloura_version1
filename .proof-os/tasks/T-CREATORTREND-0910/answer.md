# Does a creator-facing news-to-trend AI already exist?

Yes. It is the Creator AI Co-pilot, built end to end, currently off by default.

## 1. It exists and is creator-scoped

influora-api/src/main/java/com/influora/web/CreatorCopilotController.java:26 "/creator/copilot"
influora-api/src/main/java/com/influora/web/CreatorCopilotController.java:42 "/suggestion/today"

That is a separate path from Trend-Spark, which is brand-only:
influora-api/src/main/java/com/influora/web/TrendSparkController.java:26 "/brand/trendspark"
influora-api/src/main/java/com/influora/web/TrendSparkController.java:43 "requireBrandWorkspace(principal)"

## 2. News is already a trend source

trendspark/n8n/trend-pull-workflow.json:112 "NewsAPI top-headlines (IN)"
trendspark/n8n/trend-pull-workflow.json:87 "https://newsapi.org/v2/top-headlines"
trendspark/n8n/trend-pull-workflow.json:267 "MySQL INSERT trends"

## 3. The creator side reads those same trends

influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService.java:14 "import com.influora.repository.TrendRepository;"
influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService.java:112 "trendRepository.findActive(Instant.now())"
influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService.java:116 "themeMatchService.score(trend, creatorThemeTags)"

The creator is shown a phrased card:
influora-api/src/main/java/com/influora/web/dto/creatorcopilot/CreatorCopilotDtos.java:17 "String id, String theme, String headline, String contentIdea, String expiresAt"

## 4. It matches on theme tags, not on a category field

The Trend entity has no category column:
influora-api/src/main/java/com/influora/domain/entity/Trend.java:46 "themes" (column name; no category column exists)
influora-api/src/main/java/com/influora/domain/entity/Trend.java:50 "campaign_type"

The creator half of the match is the profile theme rollup, blank until a nightly job fills it:
influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService.java:105 "String creatorThemeTags = profile.getThemeTagsJson();"

## 5. It is off by default in code and in both deploy files

influora-api/src/main/resources/application.yml:508 "enabled: ${CREATOR_COPILOT_ENABLED:false}"
influora-api/src/main/java/com/influora/config/CreatorCopilotProperties.java:26 "private boolean enabled = false;"
deploy/utho/docker-compose.utho.yml:175 "CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}"
deploy/hostinger/docker-compose.hostinger.yml:154 "CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}"
