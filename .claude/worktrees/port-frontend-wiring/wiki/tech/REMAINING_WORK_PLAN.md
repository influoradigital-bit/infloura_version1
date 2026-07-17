# INFLUORA — Remaining Work Plan (Brand Analytics + Tracking Platform)

> **Author:** Priya (CTO)
> **Date:** 2026-07-07
> **Status:** LOCKED sequencing; task assignments below
> **Baseline:** blended ~76% complete. New analytics/tracking platform ~59%. See `MASTER_IMPLEMENTATION_PLAN.md` Current State Summary.

---

## How to read this plan

Work is grouped into **5 waves**, sequenced by value-vs-risk and dependency order. Each task has an **owner**, **dependencies**, **acceptance criteria**, and a **review chain**. The review pipeline is unchanged and non-negotiable — it has caught real bugs this build:

> **Every backend task:** owner builds + writes tests → **Kavya** (test-quality, independent) → **Kabir** (security/workspace-isolation) → **Meera** (live build/schema verify) → Arjun independently re-runs `mvn test` before advancing. New migration ⇒ Meera does a live-MySQL throwaway-DB check. Money/webhook/public surface ⇒ Kabir review is load-bearing, not routine.

**Standing rules (locked this build, do not re-litigate):**
- MySQL only — never TimescaleDB/Postgres (`wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md`).
- Frontend is **Vite + React Router**, NOT Next.js. No `src/app/` directories, no `page.tsx`/`loading.tsx` conventions.
- Every brand-facing read/write of per-creator data routes through `MetricsAuthorizationService` or the `findByIdApiAndWorkspaceId` resolve-then-scope pattern. Public webhook/pixel endpoints are the only unscoped exceptions and must be justified in javadoc.
- Idempotency on any mutation reachable by retry/webhook uses the shared `IdempotencyService.executeOnce` pattern.

---

## WAVE A — Finish Phase 4 into an end-to-end demo (HIGHEST PRIORITY) — ✅ COMPLETE 2026-07-07

**Goal:** the UTM/coupon/redemption services (done, signed off) get a REST surface and a UI, so a brand can actually generate a tracking link + coupon and see a conversion funnel. Lowest risk, highest demo value — the services already exist and are reviewed.

**Outcome:** All 5 tasks (A1–A5) shipped and independently verified end-to-end. 261 backend tests green. Two genuine QA/security reject→fix→re-approve cycles this wave (coupon-collision retry gap; A2's webhook-test hardcoded-constant assertion) — the review pipeline caught real issues, not rubber-stamped. Meera's A5 integration check confirmed the full chain (click→conversion→coupon-usage counters) via live UI + traced source, honestly flagging the sandbox's Spring-Boot-boot limitation rather than fabricating a live end-to-end curl test. One concrete follow-up identified during A4: no creator-facing coupon-read endpoint exists yet (`GET /creator/coupons` or `/me/coupons`) — logged for Vikram, not silently worked around.

| # | Owner | Task | Depends on | Acceptance |
|---|-------|------|-----------|------------|
| A1 | **Vikram** | `CampaignTrackingController` — REST endpoints wrapping `CampaignLinkService` (`POST /campaigns/{id}/tracking-links`, `GET` list) + `CouponCodeService` (`POST /campaigns/{id}/coupons`, `GET` list). Brand-facing, workspace-authed via the existing resolve-then-scope. DTOs in `web/dto/tracking/`. | — (services exist) | Endpoints exist, workspace-scoped, `mvn test` green + new controller/service tests. Kabir confirms a brand cannot generate links/coupons for another workspace's campaign. |
| A2 | **Vikram** | `ConversionWebhookController` — **public** endpoints for `RedemptionService.redeem` (`POST /webhooks/redemption`) and `ConversionTrackingService.recordConversion` + `CampaignLinkService.recordClick` (pixel/redirect). No workspace principal — auth is the idempotency key + unguessable ULID, per the services' existing design. **Kabir load-bearing review** (public attack surface + money-adjacent). | A1 (shared DTO package) | Endpoints exist; idempotency enforced on redemption; Kabir signs off on the public surface (rate-limiting, no enumeration, no unauth data leak). |
| A3 | **Ananya** | Campaign tracking UI: UTM generator form + coupon generator + conversion funnel + ROI card + creator-attribution/redemption tables (`ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md` §1.5, §2.2). Real React Router route `/brand/campaigns/:id/tracking`. Consumes A1/A2 endpoints. | A1 | `tsc` clean; live browser walkthrough; empty/loading/error states graceful; no fabricated data. |
| A4 | **Ananya** | Creator-side coupon dashboard + affiliate-earnings view stubs (`ANANYA` spec §15, §17) — copy-paste coupon UI for creators. | A1 | As A3. |
| A5 | **Meera** | Live verification of the full Wave-A flow end-to-end (generate link → coupon → simulate redemption webhook → funnel updates). | A1–A4 | Documented walkthrough, no console/API errors. |

---

## WAVE B — Complete the Meta/metrics data pipeline (unblocks real data + BrandSafety)

**Goal:** the metrics pipeline currently polls only creator-level profile numbers; per-post media polling is **stubbed**. Finish it so scores run on real content and BrandSafety has captions to analyze.

| # | Owner | Task | Depends on | Acceptance |
|---|-------|------|-----------|------------|
| B1 | **Vikram** | Implement the stubbed `media_metrics` per-post polling in `MetricsPollingJob` — map `InstagramInsightsResponse` → `MediaMetric`, handle per-media-type metric availability (the reason it was deferred). Respect the rate-limit tracker. | — | Media rows persist; `mvn test` + new job tests; Kabir confirms no cross-workspace leak in the batch. |
| B2 | **Vikram** | `MetaTokenRefreshService` + `StaleTokenCleanupJob` — background refresh of long-lived tokens before expiry (`findTokensExpiringSoon` is already wired for this). | — | Scheduled job refreshes tokens; encrypted-storage discipline preserved; Kabir sign-off. |
| B3 | **Vikram** | `InstagramMetricsFetcher` orchestrator — high-level compose of profile+media+insights fetch (spec §1.1 package diagram). | B1 | Orchestrator tested against mocked clients. |
| B4 | **Vikram** | `AudienceDemographics` entity + migration (V26+) + `AudienceDemographicsJob` (weekly) + the missing `GET /analytics/creators/{id}/demographics` endpoint (currently returns "coming soon"). | B1 | Migration live-verified by Meera; endpoint workspace-authed; dashboard demographics panel can light up. |
| B5 | **Ananya** | Wire the dashboard's demographics panel + content-performance panel to B4's real endpoint (replace the "coming soon" placeholder). | B4 | `tsc` clean, live walkthrough. |

---

## WAVE C — BrandSafetyScoreService (the deferred cross-repo AI epic)

**Goal:** the 4th scoring service. CTO decision already locked (`wiki/decisions/2026-07-06-brand-safety-caption-storage.md`): persist captions during polling, not live-fetch. This is a **chain**, not one task.

| # | Owner | Task | Depends on | Acceptance |
|---|-------|------|-----------|------------|
| C1 | **Vikram** | Migration (V27+): add nullable `caption` text column to `media_metrics`; wire caption persistence into B1's media polling (caption is already in the fetched payload — zero extra API cost). Retention/redaction per the ADR. | B1 | Live-verified; caption never surfaced in brand-facing DTOs; Kabir sign-off on the PII/retention posture. |
| C2 | **Vikram + AI** | `influora-ai` `/internal/brand-safety` endpoint (Python/FastAPI) + GARM/NLP prompt design + `analyze_creator_content` tool in `app/tools/schemas.py`. Cross-repo; internal-service-token auth per the existing `/internal` pattern. | — | Endpoint returns GARM flags + sentiment for supplied captions; internal-auth enforced. |
| C3 | **Vikram** | `BrandSafetyAiClient` (Java, `integration/ai/`) + `BrandSafetyScoreService` + wire into `ScoreCalculationJob` to populate the currently-nullable `brand_safety_score`/`garm_flags`/`content_sentiment` columns on `creator_scores`. | C1, C2 | Job populates the columns; `mvn test`; Kabir sign-off; graceful degradation if influora-ai is unreachable. |
| C4 | **Ananya** | `BrandSafetyBadge` / letter-grade UI — the dashboard's "not yet available" placeholder becomes a real score. | C3 | `tsc` clean, live walkthrough. |

---

## WAVE D — Store integrations + affiliate (largest, separate efforts)

**Goal:** free Shopify/WooCommerce integration + affiliate revenue-share. Per `MASTER_IMPLEMENTATION_PLAN.md` addendum. Biggest remaining chunk — each is its own mini-project.

| # | Owner | Task | Depends on | Acceptance |
|---|-------|------|-----------|------------|
| D1 | **Vikram** | `ShopifyOAuthService` + `ShopifyConnectController` (free OAuth, no $99 fee) + `ShopifyWebhookController` (order webhooks → `RedemptionService`/`ConversionTrackingService`). | Wave A | Webhook signature verified (mirror `RazorpayWebhookController`'s HMAC pattern); Kabir load-bearing. |
| D2 | **Vikram** | `WooCommerceWebhookController` (webhook-based, no plugin). | D1 | As D1. |
| D3 | **Vikram** | `IntegrationHealthService` + block sale-campaign creation when store not connected. | D1 | Sale campaign creation rejects with a clear error if no active store integration. |
| D4 | **Vikram** | Migration (V28+) affiliate tables + `AffiliateEarningsService` + `AffiliateSettlementJob` (monthly). **Money — Kabir load-bearing + Rohan cost review.** | Wave A | Idempotent settlement; double-payout impossible; live-verified. |
| D5 | **Ananya** | `StoreIntegrationSetup` UI + `CampaignTypeSelector` + `AffiliateEarningsView` (`ANANYA` spec §16, §18). | D1, D4 | `tsc` clean, walkthrough. |

---

## WAVE E — Launch hardening (the Launch Blockers)

**Goal:** everything in `MASTER_IMPLEMENTATION_PLAN.md`'s Launch Blockers table that's still ⬜/🟨. Some are agent work; some are **human-gated and cannot be done by any agent**.

| # | Owner | Task | Human-gated? |
|---|-------|------|--------------|
| E1 | **Kavya** | Plan-wide workspace-isolation test audit (currently proven only for the new analytics/tracking work). | No |
| E2 | **Vikram** | Plan-wide idempotency-key audit on all mutation endpoints. | No |
| E3 | **Meera** | Stand up CI integration-test infra (`@SpringBootTest` + Testcontainers MySQL) — the standing recommendation. This repo has **zero** integration tests today; that's why 3 pre-existing entity/DDL bugs sat undetected until the manual live-schema checks. | No |
| E4 | **Kabir** | Full red-team sign-off across ALL phases (currently partial). | No |
| E5 | **Meera + human** | Real Meta app credentials + prod API keys (Razorpay, MSG91, R2, Meta). | **YES — needs Swapnil** |
| E6 | **Priya** | Final architecture sign-off. | Mine |
| E7 | **Swapnil** | Final production-launch approval. | **YES — Swapnil only** |

---

## Sequencing rationale (CTO)

- **A before everything:** the Phase-4 backend is built and paid-for; without controllers + UI it delivers zero user value. Finishing it turns sunk work into a demo. Lowest risk (services reviewed), highest value.
- **B before C:** BrandSafety literally cannot work without media polling (currently stubbed) producing captions. C depends on B1.
- **C is an epic, not a task:** cross-repo Python + LLM prompt design. Give it a focused pass, don't squeeze it between smaller work.
- **D is the largest bucket:** two payment-provider-shaped integrations + a money-moving settlement job. Own planning pass; Rohan cost review on D4.
- **E runs partly in parallel** but E5/E7 are hard human gates — no amount of agent work substitutes for real credentials and CEO sign-off. Flag these to Swapnil early.

## What I am NOT approving without a further decision

- Any new npm/Maven dependency → logged in `wiki/tech/approved-deps.md` first (my sign-off).
- Any deviation from the MySQL / Vite-React-Router / resolve-then-scope rules above.
- D-wave store integrations touching real payment webhooks go through the same discipline as the existing Razorpay integration — no shortcuts on signature verification.

---

## Immediate next action

Wave A, task A1 (Vikram: `CampaignTrackingController`). Seeded into the live task tracker. Recommend committing the current `feature/analytics-platform` increment (150 uncommitted files, 7 commits, master untouched) before A1 starts.
