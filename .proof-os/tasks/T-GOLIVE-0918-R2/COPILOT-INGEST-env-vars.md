# COPILOT-INGEST lane — env vars for DEPLOY (T-GOLIVE-0918-R2)

Kabir's repair-round-1-on-31f2351 review (MEDIUM #2) found the env-var list in the 31f2351
commit message incomplete: it named 3 new vars and said "the four already present" without
naming them (there are actually five), and left out the two brand-safety classifier
prerequisites entirely. This file is the complete list, superseding that commit message.

Every env var `TrendPullJob` needs to run end-to-end, as bound in
`influora-api/src/main/resources/application.yml` (`influora.trend-ingest` block, lines
569-577, plus the `influora.brand-safety-ai` / `influora.brand-safety-service-token` blocks the
classifier call depends on). Binding for all eight `trend-ingest` vars plus the go-live default
values is proven by `TrendIngestConfigWiringTest` (new this round, red-then-green against
mutated `application.yml`).

| Env var | Purpose | Already forwarded by DEPLOY compose? |
|---|---|---|
| `TREND_INGEST_ENABLED` | turns the whole job on (default `false`) | yes (round 3 / 31f2351) |
| `NEWSAPI_KEY` | NewsAPI source key | yes |
| `TMDB_API_KEY` | TMDB source key | yes |
| `YOUTUBE_API_KEY` | YouTube Data API source key | yes |
| `TREND_INGEST_PULL_CRON` | schedule override (default `0 0 5 * * *` UTC) | yes |
| `TREND_INGEST_MAX_ROWS_PER_RUN` | per-run row cap (default `100`) | new this round (31f2351) |
| `TREND_INGEST_REGION` | region tag stamped on every row (default `IN`) | new this round (31f2351) |
| `TREND_INGEST_CLASSIFIER_WORKSPACE_ID` | workspace billed for GARM classification; blank = job fails closed and stores nothing (HIGH, see `TrendIngestProperties#classifierWorkspaceId` javadoc — which real workspace pays is a business decision this lane is not authorized to make) | new this round (31f2351); **value still needs an ops/Swapnil-Priya decision, wiring alone is not enough** |
| `BRAND_SAFETY_AI_BASE_URL` | base URL `BrandSafetyAiClient` calls for every classify request `TrendPullJob` makes | already present per `application.yml:243` — confirmed present in `deploy/hostinger/docker-compose.hostinger.yml` and `deploy/utho/docker-compose.utho-shared.yml` by grep, not modified by this lane |
| `BRAND_SAFETY_SERVICE_TOKEN_SECRET` | signs the service JWT `BrandSafetyAiClient` mints before every classify call — without it, minting throws and every headline in the run is rejected as `classifier_error` (see the repair-round-2 LOW fix on the widened `catch (RuntimeException e)`) | already present, same as above |

Not a `trend-ingest` env var but relevant to this lane's go-live checklist: with
`TREND_INGEST_MAX_ROWS_PER_RUN` set to an EMPTY string (present but blank) rather than unset,
Spring's `${VAR:100}` default does not apply and the Binder throws
`BindException: Failed to bind properties under 'influora.trend-ingest.max-rows-per-run' to
int`, which would fail the whole API boot (`TrendIngestProperties` is
`@EnableConfigurationProperties`-registered). Flagging for DEPLOY: any compose/env-file
generation must either omit the var entirely when unset, or always forward a non-blank integer
— never `VAR=` with nothing after `=`.
