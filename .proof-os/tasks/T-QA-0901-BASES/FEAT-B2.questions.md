# FEAT-B2 Quality Audit Questions
**Batch:** T-QA-0901-BASES  
**Date:** 2026-09-03  
**Role:** TESTER

---

## Feature: Campaigns

### 1. WORKS?
Does a brand publishing a DRAFT campaign to ACTIVE end-to-end today actually charge the 7%/10% publish fee from their wallet, write a commission invoice row, lock the campaign against further edits, and make it visible to creators browsing the marketplace?

### 2. HOW?
When `CampaignService.update` moves a campaign from DRAFT to ACTIVE, which transaction boundary contains the workspace VERIFIED check, the wallet debit via `BrandCampaignFeeService.chargeOnPublish`, the commission invoice mint, and the status write—is it all one pessimistic-locked transaction or are any steps deferred outside the lock?

### 3. WHY NOT AS ADVERTISED?
The doc says human create-form doesn't send `campaign_type` so it's null and ungated. When a brand creates a DIRECT campaign (which requires a connected store per line 21), does the UI enforce the store-integration check before letting them save the DRAFT, or can they publish a DIRECT campaign with `NO_STORE_INTEGRATION` only surfacing at publish-time as a 409?

### 4. WHEN DOES IT BREAK?
If two brand workspace members race to publish the same DRAFT campaign (two browser tabs, both click Publish within the pessimistic-lock window), does the second request see `CAMPAIGN_NOT_EDITABLE` or does the idempotent fee charge silently succeed and double-debit the wallet because the idempotency key is missing or scoped wrong?

### 5. WHAT IS MISSING?
The doc says Meera can draft campaigns with `budget null` (money not AI-writable) and later `confirm_launch` after DB-verified funded escrow. Where in the schema or campaign-publish flow is "funded escrow" enforced as a precondition for DRAFT→ACTIVE when the campaign was AI-drafted vs human-drafted?

---

## Feature: Collaborations & Deals

### 6. WORKS?
When a creator applies to an open campaign, does the POST create a `Collaboration` row in APPLIED status, trigger a `brand.new_application` notification to every workspace member with the right subscription, persist the application message into `deal_messages`, and surface the application in the brand's deals dashboard without a page refresh?

### 7. HOW?
The doc says `DealService.accept` has an "anti-self-accept" gate (can't accept your own last offer). How does the service distinguish "your offer" vs "their offer"—is it by comparing `lastProposedBy` (a user/workspace id field) against the authenticated principal, or by status enum, or message kind, and what happens if the collaboration status is IN_NEGOTIATION but the last message was a system message?

### 8. WHY NOT AS ADVERTISED?
Line 69 says `DealResponse.deliverablesDone/Total/nextDeadline` are hardcoded stubs that drift. When a brand views a deal in CONTRACTED or IN_PROGRESS status, does the UI display real deliverable counts from the database or does it show placeholder "2/5 deliverables done" that never updates as the creator submits actual deliverables?

### 9. WHEN DOES IT BREAK?
If a brand counters with `agreedRate` below the campaign's `budgetMin` or above `budgetMax`, does `DealService.counter` reject it with `AMOUNT_BELOW_BUDGET` / `AMOUNT_EXCEEDS_BUDGET`, or does it persist the out-of-range rate and only fail later when contract-generation tries to reconcile the agreed rate against campaign constraints?

### 10. WHAT IS MISSING?
The doc lists 13 `CollaborationStatus` states including DISPUTED. Where in `DealController` or `DealService` is the transition INTO the DISPUTED state—is there a `POST /deals/{id}/dispute` endpoint that creates both a `Dispute` entity and moves the collaboration to DISPUTED, or is DISPUTED an unreachable state with no entry path?

---

## Feature: Deliverables

### 11. WORKS?
When a creator uploads a 400MB video file to a PENDING deliverable, does the `CreatorDeliverableService` stream it to R2 without buffering the full file in memory, write the R2 key into `deliverables.files_json`, increment the version, set status to DRAFT, and return the updated deliverable with a presigned URL the creator can preview?

### 12. HOW?
The `DeliverableVerificationJob` runs every 6 hours at :30 and matches Instagram posts by shortcode pulled from the `postUrl`. When a deliverable is POSTED but its `postUrl` is a YouTube link (not Instagram), what does `PostUrlIdentifier` return and does the job skip it silently, or does it write a `FALLBACK_YOUTUBE_UNSUPPORTED` verification outcome, and where is that outcome persisted?

### 13. WHY NOT AS ADVERTISED?
The doc says a creator-reported metric can "never overwrite `PLATFORM_VERIFIED` data" (line 57). If a deliverable has `source=PLATFORM_VERIFIED` with 5000 reach and the creator later submits a metrics-report claiming 50000 reach, does `DeliverableMetricService` reject the write with an error, silently no-op it, or persist a second row with `source=CREATOR_REPORTED` and leave both in the table?

### 14. WHEN DOES IT BREAK?
When a brand approves a deliverable in SUBMITTED status and the approve request races with the creator re-uploading new files (which bumps version and resets to DRAFT), which transaction wins—does the brand see `INVALID_STATE` because the deliverable is no longer SUBMITTED, or does the approve succeed against a stale version and leave the deliverable showing APPROVED in the brand UI but DRAFT in the creator UI?

### 15. WHAT IS MISSING?
Line 70 says "`REJECTED` status never entered." What UI control or backend endpoint would move a deliverable into REJECTED status when a brand determines the content is unacceptable and wants to formally reject it (not request revision), and how would that differ from REVISION_REQUESTED in the contract/escrow flow?

---

## Feature: Marketplace / Creator Discovery

### 16. WORKS?
When a brand searches for creators with niche "Fashion" + city "Mumbai" + followers 50k-100k + verified=true, does the `CreatorDiscoveryService.search` return only creators whose `creator_profiles.discoverable=true`, whose `platform_stats` satisfy the follower range, who have Mumbai in their audience cities, who have Fashion in their niche array, and who have a verification badge, all in one query?

### 17. HOW?
The doc says facets are "computed over a bounded ≤5000-profile set" (line 62). Where in the code does the 5000 cap get enforced—is there a `LIMIT 5000` on the initial discoverable profiles query before applying specs, or are facets computed over the entire discoverable set and only the result page is clamped to 100?

### 18. WHY NOT AS ADVERTISED?
Line 68 says brand-safety score is "not wired (NULL)" and `audienceMatch` in quality score is "hardcoded." When a brand views a creator profile in discovery, does the UI display a brand-safety badge/score pulling from `creator_scores.brand_safety_score` (which is always null), show a placeholder "Not Available," or omit the brand-safety row entirely and only show quality/fake-follower/rate?

### 19. WHEN DOES IT BREAK?
If a brand workspace invites the same creator to the same campaign twice (different team members, both click Invite within seconds), does the second `POST /creators/{id}/invite` return `COLLABORATION_EXISTS` (409) or does the dedup check in `Collaboration.invite` fail to catch the race and create duplicate INVITED collaborations that both appear in the creator's inbox?

### 20. WHAT IS MISSING?
The doc says "suggestions are heuristic, not LLM" (line 69). What does `POST /creators/suggestions` actually do with the campaign brief text—does it keyword-match niches, does it run a vector-similarity search, or does it return a fixed algorithmic ranking, and where is the suggestion logic vs the doc's implication that AI suggestions would be more sophisticated?

---

## Feature: Creator Profiles & Portfolio

### 21. WORKS?
When a creator edits their username from "old_handle" to "new_handle" and saves, does the `CreatorProfileService` normalize it (lowercase, strip spaces), check uniqueness across all `creator_profiles.username`, update the row, recompute the completeness score, and make the portfolio immediately accessible at `/:new_handle` without requiring a logout or cache flush?

### 22. HOW?
The doc says portfolio stats like on-time delivery rate are "computed on read (bounded to 12 completed collaborations)" (line 62). Does `PortfolioService` load the creator's collaborations, filter to COMPLETED, check each one's deliverables for `submitted_at <= deadline`, compute the percentage, and return it every time `GET /portfolio/{username}` is called, or is there any caching?

### 23. WHY NOT AS ADVERTISED?
Line 38 says `PortfolioContactEvent` "has no listener currently." When a guest user fills out the public contact form on `/:username` and submits it, does the `POST /portfolio/{username}/contact` endpoint persist anything, send an email to the creator, create an in-app notification, or does it return 200 and silently drop the message because the event has no handler?

### 24. WHEN DOES IT BREAK?
The doc notes "the recurring scoping trap: `collaborations.creator_id → users.id` (not `creator_profiles.id`)" (line 59). If a portfolio query joins `creator_profiles` to `collaborations` via `creator_profiles.id = collaborations.creator_id`, does it return zero collaborations (wrong join key) or does the schema enforce that `collaborations.creator_id` actually points to `users.id` and the portfolio must join through `users` as an intermediate table?

### 25. WHAT IS MISSING?
The tax identity feature (`creator_invoice_code`, V20260715120000) is documented but the doc says invoice generation depends on it. Where in the creator onboarding or profile-edit flow is tax identity collection enforced as mandatory before a creator can accept their first paid deal or receive a payout, or is it optional and only invoked when generating an invoice?

---

## Feature: Reviews

### 26. WORKS?
When a brand submits a 5-star review with text for a COMPLETED collaboration, does `ReviewService.createReview` persist a `reviews` row with `reviewer_type=BRAND`, surface it on the creator's public portfolio under testimonials, update the creator's average rating displayed in discovery, and prevent the brand from submitting a second review for the same collaboration?

### 27. HOW?
The doc says the unique constraint is `UNIQUE(collaboration_id, reviewer_type)` (line 28). If a brand and a creator both review the same collaboration, do two rows get persisted (one with `reviewer_type=BRAND`, one with `reviewer_type=CREATOR`), and where in the portfolio does the creator's review of the brand get displayed vs the brand's review of the creator?

### 28. WHY NOT AS ADVERTISED?
Line 60 says average rating is "computed from BRAND reviews (null if none — not fabricated)." When a creator with zero completed collaborations (no reviews) appears in discovery, does their profile show "No reviews yet" or does it show a placeholder 0.0 stars or a hidden rating field, and does the backend return `avgRating: null` in the DTO or omit the field entirely?

### 29. WHEN DOES IT BREAK?
If a collaboration status is manually reset from COMPLETED to IN_PROGRESS by an admin (to allow a revision), does the existing review remain visible and count toward the average rating, or does the backend gate `POST /brand/reviews` on `collaboration.status = COMPLETED` at review-creation time only with no ongoing validation that the collaboration stays COMPLETED?

### 30. WHAT IS MISSING?
The doc says reviews can be flagged and go to an admin moderation queue via `ContentFlag`. Where is the admin UI or endpoint that lets an admin view flagged reviews, mark them as approved/hidden/deleted, and what happens to the review row and the creator's average rating when a review is moderated out?

---

## Feature: Affiliate Earnings & Coupons

### 31. WORKS?
When a shopper uses a creator's coupon code at a Shopify checkout, does the Shopify webhook reach `RedemptionService.redeem`, validate the code is not expired and under its usage limit, calculate the discount, persist a `CouponRedemption` row with the order amount, trigger the hourly reconciliation job to mint an `AffiliateEarning` row with commission = orderAmount * campaign.commissionRate, and surface the earning in the creator's affiliate earnings view?

### 32. HOW?
The doc says `AffiliateEarningReconciliationJob` runs hourly at :15 and sweeps "orphaned redemptions >30min" (line 53). What makes a redemption "orphaned"—is it a redemption with no corresponding `affiliate_earnings` row, and does the job scan all redemptions WHERE `created_at < NOW() - 30 MINUTES AND id NOT IN (SELECT redemption_id FROM affiliate_earnings)`, and why the 30-minute delay?

### 33. WHY NOT AS ADVERTISED?
Line 72 says "the advertised synchronous accrual does not exist — earnings are created only by the hourly backfill job (≥30min lag; WARNs every run)." When a redemption webhook arrives and `RedemptionService.redeem` returns 200, does the response body or a notification tell the creator "Your earning will appear within an hour" or does the UI imply instant accrual and the creator sees a ≥30min gap between redemption and earnings-list update?

### 34. WHEN DOES IT BREAK?
If the same Shopify order fires the webhook twice (network retry, idempotency failure at Shopify's end), does `RedemptionService.redeem` rely on `UNIQUE(redemption_id)` to block the duplicate, and is `redemption_id` the Shopify order id, and what happens if two different orders coincidentally generate the same id in different workspaces (the doc says idempotency keys are workspace-namespaced)?

### 35. WHAT IS MISSING?
Line 72 says settlement is "internal-ledger only (no real disbursement)." When `AffiliateSettlementJob` moves earnings from PENDING to SETTLED, does it create a wallet credit for the creator, trigger a payout API call, send an email, or simply mark the rows SETTLED and wait for a future manual payout flow that doesn't exist yet?

---

## Feature: Notifications

### 36. WORKS?
When a creator applies to a brand's campaign, does the `ApplicationCreatedEvent` fire, does `NotificationListener` handle it, does `NotificationService.notify` create an in-app notification row for the brand workspace owner, and does the brand see a red badge on the bell icon the next time they poll `GET /notifications`?

### 37. HOW?
The `EmailWorker` polls the `email_outbox` every 30 seconds and batches 50 (line 57). When a batch of 50 emails is sent to MSG91, does the worker mark them `SENT` immediately upon receiving a 200 response from MSG91, or does it wait for a delivery webhook from MSG91, and what happens if MSG91 returns 200 but the email bounces later?

### 38. WHY NOT AS ADVERTISED?
Line 73 says "most handlers pass `toEmail=null` so emails no-op." When a `DeliverableSubmittedEvent` fires and `NotificationListener` calls `notify(toEmail=null)`, does the notification appear in-app (the bell) but skip email, or does the blank-email guard (line 61) silently drop both channels, and why would handlers pass null if the user has an email in their profile?

### 39. WHEN DOES IT BREAK?
If an `email_outbox` row fails 5 times (backoff cap reached) and is marked terminal FAILED, does it stay in the table forever, does a manual-retry admin tool exist, or does the creator/brand simply never receive that email (e.g. a password reset) and the system gives them no feedback that delivery failed?

### 40. WHAT IS MISSING?
Line 74 says "5 events have no listener" and "frontend UI is mock and calls nonexistent endpoints (`/notifications/read-all`, `/preferences`)." Which 5 events have no listener, does the frontend UI's mock bell display fake notifications or real ones from `GET /notifications`, and what happens when the user clicks "Mark all as read"—does it 404 or silently no-op?

---

## Feature: Analytics & Creator Scoring

### 41. WORKS?
When a creator connects their Instagram account via Meta OAuth, does the `MetricsPollingJob` (runs every 6 hours) pull their follower count, engagement rate, and post reach, persist time-series rows into `creator_metrics`, and make the data visible to brands in `GET /analytics/creators/{id}/metrics` subject to the brand's plan-based usage cap?

### 42. HOW?
The `ScoreCalculationJob` runs daily at 04:00 and computes fake-follower, quality, and rate scores for each connected creator. Does it load the latest `creator_metrics` snapshot only (line 71 says "only latest snapshot available, growth-spike signal never fires") or does it query a time window, and where does the computed score get written—one row per creator in `creator_scores` with timestamp, or does it upsert the same row daily?

### 43. WHY NOT AS ADVERTISED?
Line 70 says brand-safety scoring is "not wired (NULL)" and `audienceMatch` is "hardcoded 50." When a brand views a creator's quality score, does the DTO include `qualityScore: { overall: 78, audienceMatch: 50, contentQuality: 82 }` with 50 always returned, and does the brand see a "Brand Safety: Not Available" field or is that column omitted entirely from the UI?

### 44. WHEN DOES IT BREAK?
The doc says brand analytics are usage-capped on Free plans and `AnalyticsUsageCapInterceptor` dedups "per creatorId (4 sub-endpoints = 1 unit)" (line 64). If a brand requests `/analytics/creators/123/metrics`, `/analytics/creators/123/scores`, `/analytics/creators/123/demographics` in quick succession, does the interceptor count it as 1 unit or 3 units, and if they hit the cap mid-sequence do later endpoints return 402 while earlier ones succeeded?

### 45. WHAT IS MISSING?
Line 70 says "per-post `media_metrics` polling not wired." The schema has a `media_metrics` table (V21/V26) but what would populate it—a separate job that fetches individual post performance, or an extension of `MetricsPollingJob`, and how would a brand or creator view per-post breakdowns vs aggregate metrics?

---

## Feature: Reports & Exports

### 46. WORKS?
When a Pro-plan brand requests `GET /campaigns/{id}/export?format=csv`, does the `ReportExportController` pass through the `@RequiresPlan(EXPORT)` gate, call `ReportExportService` to build CSV rows from `deliverable_metrics`, set `Content-Disposition: attachment; filename=campaign-{id}-report.csv`, and stream the bytes to the browser without requiring the brand to poll for a background job?

### 47. HOW?
The CSV format includes "creator-reported metrics + a SUMMARY row" (line 49). Does `ReportExportService` SUM the reach/impressions/engagements across all deliverables and write a final `SUMMARY` row with totals, or does it aggregate only PLATFORM_VERIFIED metrics and exclude CREATOR_REPORTED ones from the summary, and how does it handle deliverables with no metrics reported yet?

### 48. WHY NOT AS ADVERTISED?
Line 65 says "no frontend caller" for the export feature. Does a button or menu item exist in the brand campaign-detail page that triggers the export, or is the endpoint live and functional but unreachable from the UI, requiring a brand to manually construct the URL or use a browser dev console to download the report?

### 49. WHEN DOES IT BREAK?
If a campaign has 500 deliverables with metrics, does the CSV build and stream synchronously (potentially blocking the request for seconds), or does it fail with a timeout, or is there a row-count cap above which the export is rejected or deferred to a background job that emails the brand a download link?

### 50. WHAT IS MISSING?
The doc says "boolean plan-gate only (the `UsageMetric.EXPORT` counter isn't incremented here)" (line 65). If a Pro-plan brand is limited to 50 exports per month per their subscription, where would that quota enforcement live, and does the current implementation let a Pro user export unlimited reports or does the plan gate simply block Free users with no per-plan quota tracking?
