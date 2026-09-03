# BLOCK C — Marketing data Tejas needs: is it STORED? (C29–C45)
Answered by priya · fresh-context · 2026-09-02

**C29. NOT STORED** — no signup-time UTM/traffic-source capture anywhere; `utm_campaigns` is a per-creator-per-campaign *outbound tracking link*, unrelated to signups.
Evidence: `influora-api/src/main/resources/db/migration/V23__utm_campaigns.sql:22` — keyed by `campaign_id` / `collaboration_id` / `creator_profile_id`, no `user_id`; `influora-api/src/main/java/com/influora/domain/entity/UtmCampaign.java:40` confirms the same three FKs. Grep for `signup_source|acquisition_channel` over `java/` and `db/migration/` returns zero hits.

**C30. NOT STORED** — no `Referral` entity, no referral/invite-attribution table, no `referred_by` column.
Evidence: `influora-api/src/main/java/com/influora/web/AdminMarketingController.java:20` — "no signup-source attribution, no Referral table". Grep for `referral|referred_by` across `db/migration/` returns zero hits. The one adjacent table, `workspace_member_invites` (`V59`), is intra-workspace seat invites, not acquisition attribution.

**C31. NOT STORED** — `users` has no channel/source column at all.
Evidence: `influora-api/src/main/resources/db/migration/V2__core_auth.sql:4` — full `users` column list is id, email, phone_number, password_hash, user_type, status, email_verified, phone_verified, onboarding_completed, display_name, first_name, last_name, avatar_url, timezone, last_login_at, created_at, updated_at. Later migrations add only `kyc_prompt_dismissed` (`V20260809120000`) and `deleted_at` (`V61`). `user_type` is BRAND/CREATOR/ADMIN — a role, not an acquisition channel.

**C32. BOTH — one flag STORED, the percentage COMPUTED.**
Evidence: `influora-api/src/main/resources/db/migration/V2__core_auth.sql:12` — `onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE` persisted on `users`, written at `influora-api/src/main/java/com/influora/service/OnboardingService.java:93` and `influora-api/src/main/java/com/influora/service/CreatorOnboardingService.java:128`.
Evidence: `influora-api/src/main/java/com/influora/service/CreatorProfileService.java:267` — `static int calculateCompleteness(...)` sums weights (displayName 10, username 10, bio 10, avatar 10, categories 15, city 10, rates 15, platforms 20) on every read; nothing persists it. Called inline at `CreatorProfileService.java:254`.

**C33. COMPUTED — 5 live COUNT queries across 4 tables, no stored aggregate.**
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminMarketingService.java:94` — the full trace:
- `signups` ← `userRepository.count()` (line 97) = **all** `users` rows, brands + creators + admins combined.
- `firstCampaign` ← `campaignRepository.countBrandWorkspacesWithCampaign()` (line 98), defined at `influora-api/src/main/java/com/influora/repository/CampaignRepository.java:63` as `SELECT COUNT(DISTINCT c.workspace_id) FROM campaigns c JOIN workspaces w ... WHERE w.type='BRAND'`.
- `repeatCampaign` ← `CampaignRepository.java:78`, same join with `HAVING COUNT(*) >= 2`.
- `creatorApplicationToApproval` ← `creatorProfileRepository.countByApplicationStatus(APPROVED) / creatorProfileRepository.count()` (lines 101–108).
- `brandSignupToFirstCampaign` ← `firstCampaign / workspaceRepository.countByType(BRAND)` (lines 110–112).
Both ratios guard the zero denominator to `0.0`. `calculatedAt` is `Instant.now()` (line 117) — nothing is persisted; every call re-runs the queries.

**C34. Populated: 6 of 6 served fields. Omitted from the DTO entirely: 3 fields the frontend type declares.**
Evidence: `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:73` — the record is only `funnel(signups, firstCampaign, repeatCampaign)`, `conversionRates(creatorApplicationToApproval, brandSignupToFirstCampaign)`, `calculatedAt`. All six populated; none ever null (primitives `long`/`double`).
Evidence: `src/admin/types/admin.types.ts:758` — the FE `GrowthMetrics` declares three extra members, all correctly marked optional (`profileComplete?`, `cohortRetention?`, `referralStats?`) with comments naming the backend omission. The FE/BE contract does not diverge dangerously here — the fields are `?`-optional, so no empty-state bug of the PHONE-0829 kind.

**C35. COMPUTED — 4 live aggregates over 2 tables (`reviews`, `disputes`).**
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminMarketingService.java:68`:
- `overall` ← `ReviewRepository.java:55` — `SELECT AVG(r.stars) FROM Review r WHERE r.hidden=false`.
- `creatorQualityAvg` / `brandSatisfactionAvg` ← `ReviewRepository.java:51`, same average filtered by `reviewerType = BRAND` / `CREATOR`.
- `disputeResolutionSpeed` ← `DisputeRepository.java:84` — `AVG(TIMESTAMPDIFF(SECOND, created_at, resolved_at)) FROM disputes WHERE resolved_at IS NOT NULL`, divided by 3600 at `AdminMarketingService.java:77`.
Nulls coalesce to `0.0` (line 120), so "no data yet" is indistinguishable from "genuinely zero" in the response.

**C36. NOT STORED** — no NPS, CSAT, or survey table of any kind.
Evidence: grep for `nps|csat|survey|satisfaction_score` across `influora-api/src/main/java/` and `influora-api/src/main/resources/` returns zero hits. What exists instead: `reviews` (`V43__reviews.sql`) — 1–5 star peer ratings tied to a completed collaboration, surfaced as `brandSatisfactionAvg` at `AdminMarketingService.java:72`. Transaction-level peer feedback, not a solicited platform survey; no unprompted-sentiment instrument.

**C37. NOT STORED** — email is send-side only; no per-recipient engagement.
Evidence: `influora-api/src/main/resources/db/migration/V18__email_outbox.sql:3` — `email_outbox` columns are status (`PENDING/SENT/FAILED`), `retry_count`, `next_retry_at`, `sent_at`, `error_message`. No `opened_at`, `clicked_at`, `bounced_at`. `sent_at` means "handed to MSG91", not delivered. No webhook table ingests ESP engagement events. (The `clicked_at` grep hits are unrelated: `nudge_log` at `V51__trendspark.sql:50` and `NudgeLog.java:57` are in-app nudges.)

**C38. PARTIAL — campaign→sales attribution is stored; channel→brand acquisition attribution is not.**
Evidence STORED: `influora-api/src/main/resources/db/migration/V23__utm_campaigns.sql:39` — `click_count`, `unique_visitors`, `conversion_count`, `revenue_attributed` per creator per campaign, written by `influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingService.java:102` (`recordConversion`) and `CampaignLinkService.java:160`. Plus `coupon_redemptions` (`V24`) and `affiliate_earnings` (`V28`).
Evidence NOT STORED: `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:9` — the `SourceAttribution` shape the FE declares at `src/admin/types/admin.types.ts:745` (source → brandSignups/creatorSignups/revenue) has no backing table and `/acquisition` is not served. Which *creator* drove which *sale* is measurable; which *channel* produced which *brand* is not.

**C39. DERIVABLE — yes, from two stored timestamps.**
Evidence: `influora-api/src/main/resources/db/migration/V2__core_auth.sql:37` — `workspaces.created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`; `influora-api/src/main/resources/db/migration/V4__campaigns.sql:23` — `campaigns.created_at`, joined via `campaigns.workspace_id` (`V4__campaigns.sql:3`, FK at line 27). `MIN(campaigns.created_at) − workspaces.created_at` grouped by `workspace_id` gives time-to-first-campaign. `users.created_at` (`V2:19`) is the alternative anchor. No query computes this today — `countBrandWorkspacesWithCampaign()` counts *whether*, never *when*.

**C40. NOT STORED** — no spend figure of any kind.
Evidence: grep for `ad_spend|marketing_spend|cac|customer_acquisition|acquisitionCost` across `java/` and `resources/` returns exactly one hit — the javadoc at `influora-api/src/main/java/com/influora/web/dto/admin/AdminMarketingDtos.java:8` explaining the endpoint is unimplemented. The FE `AcquisitionMetrics.cac` shape (`src/admin/types/admin.types.ts:733`) splits paid/organic/referral for brands and creators; none of the three inputs exists. CAC is not merely uncomputed — both the numerator (spend) and the denominator's channel split are absent.

**C41. NEITHER — no cohort table, and no on-the-fly computation either.**
Evidence: grep for `cohort` across `java/` and `db/migration/` returns zero hits (the `retention` hits are all `MeeraInteractionLogRetentionPurgeJob` — a log-purge TTL, unrelated to user retention). `src/admin/types/admin.types.ts:775` declares `CohortRetention {cohort, day30, day60, day90}`, optional and never served. `users.last_login_at` (`V2:18`) is the only activity signal and is overwritten on each login — a single scalar, so no historical activity series exists to reconstruct retention from retroactively.

**C42. NOT STORED for the marketing site.** Creator *portfolio* pageviews are stored; the public marketing pages record nothing server-side.
Evidence NOT STORED: grep for `gtag|googletagmanager|posthog|mixpanel|plausible|analytics.track` across `src/` and `index.html` returns zero real hits. No endpoint ingests a pageview beacon.
Evidence STORED (app-side only): `influora-api/src/main/resources/db/migration/V20260718120000__portfolio_events.sql:24` — `portfolio_events(creator_profile_id, event_type, occurred_at, visitor_hash)` with `event_type` VIEW / MEDIA_KIT_DOWNLOAD / LINK_CLICK, counted at `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:412` (`computePageViews`). Scoped to one creator's portfolio page, not `/pricing`, `/features/*`, or the landing page.

**C43. YES — but it does not do what the name suggests to a CMO.**
Evidence: `influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:88` — `@Scheduled(cron = "0 45 3 * * *")`, daily at 03:45, ShedLock-guarded (`lockAtMostFor = PT20M`).
Writes `platform_stats` (upsert per creator per platform) and denormalized totals onto `creator_profiles` via `applyAggregatedStats`, reading the latest `creator_metrics` row per platform — `PlatformStatsAggregationJob.java:35`.
Scope note: `platform_stats` is **per-creator social-media stats** (followers, engagement rate, handle, verified) — `influora-api/src/main/java/com/influora/domain/entity/PlatformStat.java:18`. Discovery-ranking substrate, not platform-wide business metrics. `SUPPORTED_PLATFORMS = List.of("INSTAGRAM")` only (line 62), and `engagement_rate` is still never written because `MetricsPollingJob` does not populate `avgEngagementRate` (lines 47–51). There is no job aggregating marketing/growth metrics.

**C44. INFERRED FROM STATE — no churn event rows.**
Evidence: `influora-api/src/main/java/com/influora/repository/SubscriptionRepository.java:39` — churn is counted by `countByStatusAndPlanIdAndCompFalseAndUpdatedAtAfter(...)`, i.e. rows currently in `CANCELLED` whose `updated_at` falls in the window. Because `updated_at` is overwritten on every transition (`Subscription#setStatus` → `touch()`), a row that cancels and later changes again loses its cancellation date entirely.
Evidence of the resulting approximation: `influora-api/src/main/java/com/influora/service/admin/AdminBillingService.java:173` — `activeAtWindowStart` is approximated as `currentActivePro + churnedInWindow`, which the javadoc itself admits undercounts anyone who subscribed and churned inside the same 30-day window (`CHURN_WINDOW_DAYS = 30`, line 211). Creator dormancy: not stored and not computed — the only `DORMANT` constant is `ConversationStatus.DORMANT` (`influora-api/src/main/java/com/influora/domain/enums/ConversationStatus.java:6`), an AI-chat state, not a creator lifecycle state.

**C45. Only entity tables plus domain-specific logs — there is no generic event/telemetry table.** 80 tables total; the log/audit/event family:

| Table | Evidence | Scope |
|---|---|---|
| `audit_log` | `V15__audit_log.sql:9` | money-affecting Meera tool calls + auth rejections |
| `admin_audit_log` | `V34__admin_tables.sql:48` | admin-panel actions |
| `error_log` | `V20260718170000__admin_error_log.sql:7` | server errors |
| `portfolio_events` | `V20260718120000__portfolio_events.sql:24` | creator portfolio VIEW/DOWNLOAD/CLICK |
| `application_history_events` | `V69__application_history_events.sql:35` | fixed 14-value deal-lifecycle ENUM |
| `meera_interaction_log` | `V20260721160000__meera_interaction_log.sql:32` | AI chat, 180-day purge |
| `meera_tool_calls` | `V14__ai_credits_tool_calls.sql` | AI tool invocations |
| `nudge_log` / `creator_nudge_log` | `V51__trendspark.sql:39`, `V20260721140000:17` | in-app nudges |

Decisive constraint: `application_history_events.event_type` is a DB ENUM (`V69__application_history_events.sql:44`) — adding a marketing event type is a schema migration, not an app change. `audit_log.event_type` is a free VARCHAR (`V15:14`) but is insert-only for money/auth paths and carries no session, source, or visitor identity. Nothing in this list can absorb an arbitrary product-telemetry event.

## Block C summary

Transaction-side marketing data is real; acquisition-side marketing data does not exist. Once a brand is on the platform, the codebase can tell you what every creator sold (`utm_campaigns` clicks/conversions/revenue, coupons, affiliate earnings). It cannot tell you where a single user came from, what a signup cost, or whether a cohort stuck around.

Three flags raised by the answering context:

1. `AdminMarketingDtos.java:13` says "no profile-completion flag" — but `users.onboarding_completed` is persisted (`V2:12`) and `calculateCompleteness()` already returns a real 0–100 score (`CreatorProfileService.java:267`). `funnel.profileComplete` is the one omitted field buildable today with a single COUNT, no migration.
2. `src/pages/landing.tsx:169` renders "4,812 link clicks / 231 conversions / ₹3.4L revenue attributed" as a hardcoded scoreboard while the plumbing to produce real figures exists. Needs a deliberate ruling.
3. Cheapest unlock is a `utm_source` column on `users` plus one telemetry table — would immediately populate `SourceAttribution`, which the FE type already declares. Cohort retention is the harder gap: `last_login_at` is overwritten on every login, so **retention cannot be backfilled**. Every day without event capture is a cohort permanently lost.

No UNDETERMINED items in this block.
