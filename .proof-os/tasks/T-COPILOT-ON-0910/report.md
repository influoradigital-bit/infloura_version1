# Step 1 — turn on the Creator AI Co-pilot: what the check found

## Verdict: the flag is NOT the blocker. Turning it on today ships a permanently silent feature.

## A. The chain that IS intact (verified)

Database tables exist:
influora-api/src/main/resources/db/migration/V51__trendspark.sql:4 "CREATE TABLE trends ("
influora-api/src/main/resources/db/migration/V20260721140000__creator_nudge_log.sql:17 "CREATE TABLE creator_nudge_log ("

The AI phrasing service is registered and its path matches the Java caller exactly:
influora-ai/app/routes/creator_suggestion.py:201 "/internal/creator-suggestion"
influora-ai/app/main.py:103 "app.include_router(creator_suggestion.router"
influora-api/src/main/java/com/influora/integration/ai/CreatorSuggestionAiClient.java:47 "/internal/creator-suggestion"

The page is mounted in the router:
src/App.tsx:80 "import CreatorCopilotPage from"

Oracles run over the feature: tsc exit 0; vitest 20/20 passed across 5 copilot test files;
surefire 4/4 CreatorCopilotConfigWiringTest and 9/9 CreatorMetaOAuthServiceTest, mvn exit 0.

## B. BLOCKER 1 (F-0774) — the trends table has no writer in production

The ONLY writer of a trends row in the entire system is an n8n node:
trendspark/n8n/trend-pull-workflow.json:267 "MySQL INSERT trends"

Nothing in influora-api or influora-ai inserts a trends row - a repo-wide search for
trendRepository.save and INSERT INTO trends returned zero hits in both src trees.

n8n is a service in the LOCAL compose only:
docker-compose.yml:74 "image: n8nio/n8n:1.62.1"

It is absent from both production compose files. The utho service list ends at
deploy/utho/docker-compose.utho.yml:274 "influora-ai:" plus frontend, with no n8n service;
deploy/hostinger/docker-compose.hostinger.yml:256 "influora-ai:" is the same shape.

Consequence: in production trends is empty, so
influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService.java:112
"trendRepository.findActive(Instant.now())" returns an empty list and every creator gets
no_suggestion_today forever, flag on or not.

## C. BLOCKER 2 — the three trend-source API keys exist in no environment

The workflow notes place them in the n8n credential store only:
trendspark/n8n/trend-pull-workflow.json:129 "cred store only"

A search for NEWSAPI, TMDB_API_KEY and YOUTUBE_API_KEY across every yml, env, example and
json in the main checkout returned no provisioning entry - only those workflow notes.

## D. BLOCKER 3 (F-0775) — the suggestion engine has no unit test

influora-api/src/test/java/com/influora/service/creatorcopilot/ contains exactly one class,
CreatorMetaOAuthServiceTest. The scoring, per-day cap, AI-fallback and ownership logic in
CreatorNudgeService is unexercised.

## E. Flag state today

influora-api/src/main/resources/application.yml:508 "enabled: ${CREATOR_COPILOT_ENABLED:false}"
deploy/utho/docker-compose.utho.yml:175 "CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}"
deploy/hostinger/docker-compose.hostinger.yml:154 "CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}"
