# INFLUORA — Master Implementation Plan

> **Approved by:** Swapnil (CEO)  
> **Architecture by:** Priya (CTO)  
> **Security by:** Kabir  
> **QA by:** Kavya  
> **Orchestration:** Arjun (COO)  
> **Date:** 2026-07-06

---

## Current State Summary

| Layer | Completion | Notes |
|-------|------------|-------|
| Auth & Workspaces | 95% | JWT, OTP, roles — done |
| Campaigns & Intents | 90% | Needs UTM integration |
| Contracts / Escrow / Payments | 90% | Razorpay integrated |
| Wallet / Ledger | 92% | Double-entry done |
| Meera AI Chat | 85% | Needs API keys only |
| **Meta API Integration** | **~50%** | **Phase 1 DONE 2026-07-06 (OAuth/encryption/clients/UI/rate-limiting, Kabir signed off). Phase 2's `MetricsPollingJob` now wired to these clients too (below). Remaining: `InstagramMetricsFetcher` orchestrator, background token refresh.** |
| **TimescaleDB Analytics** | **~40%** | **Phase 2 (Weeks 3-4) storage layer DONE 2026-07-06 — see CTO ruling: built MySQL-native, not TimescaleDB (`wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md`). `creator_metrics`/`media_metrics` (V21), repositories, `MetricsPollingJob`, `MetricsAuthorizationService` (Kabir-required workspace-isolation gate for future readers). 121 backend tests green (independently verified), Kabir signed off. **Bonus finding, independent of this feature:** a 4-pass live-MySQL verification (first ever in this repo — no integration-test infra existed) proved the entire V1-V21 schema now boots cleanly against real MySQL, after fixing 2 real pre-existing bug classes it uncovered (JSON `@Column` naming mismatches ×6, one `TINYINT`/`INTEGER` type mismatch) — see `SHARED_CONTEXT.md`. Remaining: `AudienceDemographicsJob`, `ScoreCalculationJob`-adjacent polling, Ananya's analytics dashboard UI (Phase 3 territory per spec).** |
| **Scoring Algorithms** | **~92%** | **Phase 3 backend + first-cut frontend DONE 2026-07-06. All Java-only work signed off: 3 scoring services, `ScoreCalculationJob`, migration V22, brand-facing **Analytics Read API** (`GET /analytics/creators/{id}/{metrics,scores}`, first real caller of `MetricsAuthorizationService` — Kabir traced the full chain, zero bypass path, SIGN-OFF). 189 backend tests green. **`Ananya's analytics dashboard UI shipped`** (`/brand/analytics`, `/brand/analytics/:creatorId` — real React Router routes, NOT the spec's assumed Next.js `src/app/` paths; that exact mismatch already broke this build once this session and was caught before Ananya touched a file). Kavya QA-approved, Meera verified live (build clean, 0 console errors, nav confirmed by click). Remaining: `BrandSafetyScoreService` — CTO ruling made (`wiki/decisions/2026-07-06-brand-safety-caption-storage.md`: persist captions during polling, not live-fetch), deliberately deferred behind Phase 4 as a multi-step epic (media polling is currently stubbed, needs implementation + caption column + cross-repo `influora-ai` endpoint + LLM GARM prompt). Also still open: `/demographics` endpoint (no `AudienceDemographics` entity exists).** |
| **UTM / Coupon Tracking** | **~75%** | **WAVE A (`wiki/tech/REMAINING_WORK_PLAN.md`) COMPLETE 2026-07-07 — Phase 4 shipped end-to-end. Backend: `CampaignLinkService`/`CouponCodeService`/`RedemptionService`/`ConversionTrackingService` (V23/V24), brand-facing `CampaignTrackingController`, public `ConversionWebhookController` (rate-limited, permitAll exact-path, Kabir load-bearing sign-off). 261 backend tests green. Frontend: brand campaign-tracking UI (`/brand/campaigns/:id/tracking` — UTM/coupon generators, ROI card, real React Router) + creator coupon dashboard (`/creator/coupons`, honest gap-handling for the not-yet-built creator-read endpoint) + affiliate-earnings placeholder (no fabricated data). **QA/security caught 2 real bugs this wave via genuine reject→fix→re-approve cycles** (coupon-collision retry gap; redemption TOCTOU race — fixed by reusing the existing `IdempotencyService.executeOnce` pattern; separately, Kavya rejected A2's webhook tests over a hardcoded-constant assertion that could have masked a real routing bug, also fixed and re-verified). Meera's end-to-end integration check confirmed all counter-update chains (click/usage/conversion) via live UI + traced source, honestly disclosing the sandbox's known Spring-Boot-boot limitation rather than fabricating a full live test. Remaining: creator-facing coupon-read endpoint (flagged as a concrete Vikram follow-up), Wave B (metrics pipeline completion), Wave C (BrandSafetyScoreService), Wave D (Shopify/WooCommerce/affiliate — significantly larger, separate efforts).** |

**Blended completion: ~55-60%**

---

## Spec Files Created

| File | Owner | Purpose |
|------|-------|---------|
| `wiki/tech/VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md` | Vikram | Complete backend specs for Meta API, TimescaleDB, algorithms, UTM |
| `wiki/tech/ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md` | Ananya | Complete frontend specs for analytics dashboard, tracking UI |
| `wiki/tech/KABIR_SECURITY_REQUIREMENTS.md` | Kabir | Security requirements, red team checklist, launch blockers |
| `wiki/tech/KAVYA_QA_TEST_PLAN.md` | Kavya | Test plan, coverage requirements, CI gates |

---

## 8-Week Sprint Schedule

### Phase 1: Foundation (Weeks 1-2) — ✅ DONE 2026-07-06

**Goal:** Meta OAuth + API client + token storage + basic polling

| Task | Owner | Dependency | Deliverable | Status |
|------|-------|------------|-------------|--------|
| Meta OAuth flow implementation | Vikram | None | `MetaOAuthService.java`, `MetaOAuthController.java` | ✅ DONE |
| Token encryption (AES-256-GCM) | Vikram | Kabir review | `MetaTokenStorage.java` | ✅ DONE — Kabir signed off |
| InstagramInsightsClient | Vikram | OAuth done | API client with rate limiting | ✅ DONE |
| FacebookPageClient | Vikram | OAuth done | API client with rate limiting | ✅ DONE |
| OAuth connect UI | Ananya | API ready | `connected-accounts.tsx` + `creator-meta-callback.tsx` | ✅ DONE |
| Security review: OAuth flow | Kabir | Code complete | Sign-off or blockers | ✅ SIGNED OFF (after 2 P0 + 1 P1 found and fixed — see `SHARED_CONTEXT.md`) |
| Unit tests: OAuth + clients | Kavya oversight | Code complete | 80% coverage | ✅ DONE — 48 new tests, 99 total backend tests pass |

**Note:** `PlatformConnectionController` (named in the original plan) doesn't exist — the OAuth endpoints live on `MetaOAuthController` instead; functionally equivalent. `MetaTokenEncryptionService` likewise ended up as `MetaTokenStorage` (encryption + persistence combined). Build verified by Meera: backend `mvn test` 99/99 pass, frontend `npm run build` clean, real browser walkthrough of the connect flow with zero console errors.

**Known deferred items (out of Week 1-2 scope, tracked for Phase 2+):** background token-refresh scheduler (`findTokensExpiringSoon` is ready for one), `InstagramMetricsFetcher` orchestrator, and a minor accepted UX limitation (creator's 15-min JWT can expire during the Meta consent-dialog detour, causing a generic "Connection failed" — ruled non-blocking by Kabir, no auth bypass or data exposure, recommended as a P2 backlog item).

**AI Integration:** None required — this phase is pure plumbing.

### Phase 2: Data Pipeline (Weeks 3-4)
**Goal:** ~~TimescaleDB hypertables~~ **MySQL-native time-series tables** + polling jobs + data storage

> **🔒 CTO RULING 2026-07-06 (Priya) — LOCKED:** Phase 2 is built on **MySQL, NOT TimescaleDB/PostgreSQL.** TimescaleDB is a Postgres extension and this codebase is MySQL end-to-end (incl. the working money-core). `creator_metrics`/`media_metrics` become ordinary InnoDB tables indexed on `(creator_profile_id, time)`, behind a repository interface so a TimescaleDB backend can slot in later at scale. No `org.postgresql` dependency — not approved. Full rationale + revisit trigger: `wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md`. This overrides `VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md` §2's Postgres/hypertable SQL.

**Migration numbering:** next available is **V22** (V20 = Phase 1 `meta_oauth_tokens`, V21 = Phase 2 `creator_metrics`/`media_metrics`). Ignore the spec's `V20__timescale_hypertables.sql` naming.

> **⚠️ Standing recommendation from Meera (repeated across 4 independent verification passes):** this repo has **zero integration-test infrastructure** (no `@DataJpaTest`/`@SpringBootTest`/Testcontainers anywhere) — only Mockito unit tests, which cannot catch entity-to-DDL mismatches. A 4-pass ad hoc live-MySQL check just caught 2 real bug classes (6 JSON `@Column` naming mismatches + 1 type mismatch) that had been sitting undetected, some since early migrations. Recommend the team add a permanent CI check (e.g. one `@SpringBootTest` that boots against a real/Testcontainers MySQL and lets Hibernate's `ddl-auto: validate` run) so this class of bug is caught automatically going forward instead of requiring a manual live-schema audit. This is a decision for Priya/Swapnil, not something further looping should silently decide.

| Task | Owner | Dependency | Deliverable |
|------|-------|------------|-------------|
| Migration (next available #): TimescaleDB hypertables | Vikram | Phase 1 complete | `creator_metrics`, `media_metrics` hypertables |
| Migration V21: audience_demographics | Vikram | V20 | Entity + repository |
| Migration V22: creator_scores | Vikram | V20 | Entity + repository |
| MetricsPollingJob | Vikram | Clients + tables | Scheduled job (every 6 hours) |
| AudienceDemographicsJob | Vikram | V21 | Weekly job |
| Meera DevOps monitoring dashboard | Meera | Jobs running | Rate limit alerts, job health |
| Integration tests: polling pipeline | Kavya | Jobs complete | WireMock + TestContainers |

**AI Integration:** influora-ai already has the `/analyze-site` endpoint. Add a new internal tool for Meera to query creator health: `check_api_health` tool in `tools/schemas.py`.

### Phase 3: Algorithms (Weeks 5-6)
**Goal:** Scoring algorithms + brand portal analytics

| Task | Owner | Dependency | Deliverable |
|------|-------|------------|-------------|
| FakeFollowerDetectionService | Vikram | Metrics data | Statistical anomaly scoring |
| QualityScoreService | Vikram | Metrics data | Engagement/consistency composite |
| BrandSafetyScoreService | Vikram + AI | Metrics data | GARM + NLP via influora-ai |
| RateEstimationService | Vikram | Scores done | Niche/tier multipliers |
| Migration V23: utm_campaigns | Vikram | Phase 2 | UTM tables |
| ScoreCalculationJob | Vikram | All services | Daily scoring job |
| Analytics dashboard UI | Ananya | API endpoints | Creator metrics cards, charts |
| Audience demographics panel | Ananya | API endpoints | Age/gender/location breakdown |
| Comparison view | Ananya | Analytics done | Side-by-side creator comparison |
| Security audit: scoring algorithms | Kabir | Code complete | No cross-workspace leaks |

**AI Integration:** 
- `BrandSafetyScoreService` calls `influora-ai /chat` with brand-safety analysis prompt
- Add new tool `analyze_creator_content` to influora-ai for NLP content analysis
- Vikram: create `BrandSafetyAiClient.java` in `integration/ai/`

### Phase 4: UTM & Coupons (Weeks 7-8)
**Goal:** Campaign attribution + conversion tracking + coupon system

| Task | Owner | Dependency | Deliverable |
|------|-------|------------|-------------|
| Migration V24: coupon_codes + redemptions | Vikram | V23 | Tables with idempotency |
| CampaignLinkService (UTM generation) | Vikram | V23 | Per-creator tracking links |
| CouponCodeService | Vikram + AI | Tables ready | AI-generated unique codes |
| ConversionTrackingService | Vikram | UTM done | Click → conversion → sale |
| RedemptionService | Vikram | Coupons done | Idempotent redemption |
| CampaignTrackingController | Vikram | Services done | REST endpoints |
| UTM generator UI | Ananya | API ready | Brand generates tracking links |
| Coupon generator UI | Ananya | API ready | AI coupon creation form |
| Conversion funnel UI | Ananya | API ready | Visual funnel + ROI card |
| Red team: coupon system | Kabir | Code complete | Brute-force, double-credit tests |
| E2E tests: full conversion flow | Kavya | All complete | Playwright scenarios |
| Load tests | Kavya | All complete | Performance benchmarks |

**AI Integration:**
- `CouponCodeService` calls influora-ai to generate creative, memorable coupon codes
- Add tool `generate_coupon_code` to influora-ai with inputs: creator_name, discount_percent, campaign_theme
- Vikram: create `CouponAiClient.java` in `integration/ai/`

---

## AI Integration Requirements

### New Tools for influora-ai (Vikram + existing AI codebase)

Add to `influora-ai/app/tools/schemas.py`:

```python
# 1. Brand Safety Analysis Tool
{
    "name": "analyze_creator_content",
    "description": "Analyze creator content for brand safety using GARM framework",
    "input_schema": {
        "type": "object",
        "properties": {
            "creator_id": {"type": "string"},
            "content_samples": {"type": "array", "items": {"type": "string"}},
            "brand_category": {"type": "string"}
        },
        "required": ["creator_id", "content_samples"]
    }
}

# 2. Coupon Code Generation Tool
{
    "name": "generate_coupon_code",
    "description": "Generate a unique, memorable coupon code for a creator campaign",
    "input_schema": {
        "type": "object",
        "properties": {
            "creator_name": {"type": "string"},
            "discount_percent": {"type": "integer"},
            "campaign_theme": {"type": "string"}
        },
        "required": ["creator_name", "discount_percent"]
    }
}

# 3. API Health Check Tool (for Meera monitoring)
{
    "name": "check_api_health",
    "description": "Check Meta API rate limit status and polling job health",
    "input_schema": {
        "type": "object",
        "properties": {
            "workspace_id": {"type": "string"}
        },
        "required": ["workspace_id"]
    }
}
```

### New Route in influora-ai

Add `/internal/brand-safety` endpoint for synchronous brand safety scoring (Vikram's `BrandSafetyAiClient` calls this).

---

## Launch Blockers (Must Complete)

| Blocker | Owner | Status |
|---------|-------|--------|
| OAuth token encryption implemented | Vikram | ✅ Done (Meta OAuth, AES-256-GCM, Kabir signed off 2026-07-06) |
| All idempotency keys on mutation endpoints | Vikram | ⬜ Pending (not yet audited plan-wide) |
| Workspace isolation tests passing | Kavya | 🟨 Partial — confirmed for Meta OAuth token repo; not yet plan-wide |
| Kabir red team sign-off | Kabir | 🟨 Partial — Meta OAuth Phase 1 signed off; remaining phases not yet built |
| 80% test coverage on new code | Kavya | 🟨 Partial — met for Meta OAuth Phase 1 (99 backend tests green); remaining phases not yet built |
| API keys configured in prod | Meera | ⬜ Pending |
| Priya architecture sign-off | Priya | ⬜ Pending |
| Swapnil final approval | Swapnil | ⬜ Pending |

---

## Team Communication

All task handoffs go through `SHARED_CONTEXT.md`:

```
FROM → TO | TASK | FILES | STATUS | NEXT
```

Daily standups: each agent updates their section.

Escalation path:
1. Working member → Arjun (orchestration)
2. Technical blockers → Priya (CTO)
3. Security concerns → Kabir
4. QA failures → Kavya
5. Business decisions → Swapnil (CEO)

---

## Success Criteria

**Week 8 Demo:**
1. Brand connects Instagram account → sees real metrics in dashboard
2. Brand compares 3 creators with fake follower scores
3. Brand creates campaign → generates UTM links → sees conversion funnel
4. Brand generates AI coupon codes → tracks redemptions
5. All security tests passing (Kabir approved)
6. Performance: dashboard loads < 500ms p99

**Swapnil sign-off required before production deployment.**

---

## Appendix: File References

- Backend spec: `wiki/tech/VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md`
- Frontend spec: `wiki/tech/ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md`
- Security spec: `wiki/tech/KABIR_SECURITY_REQUIREMENTS.md`
- QA spec: `wiki/tech/KAVYA_QA_TEST_PLAN.md`
- Tech stack: `TECH-STACK.md` (Priya locked)
- Blueprint reference: `Influencer_Analytics_Platform_Blueprint.docx`

---

# ADDENDUM: New Requirements (2026-07-06 Update)

> Per Swapnil's review session. Extends original 8-week plan.

---

## New Features Added

| Feature | Description | Owner |
|---------|-------------|-------|
| **Unique Coupons Per Creator** | Each creator gets unique code (RIYA_SUMMER25) for attribution | Vikram |
| **Free Shopify Integration** | OAuth + manual webhook, NO $99 fee | Vikram |
| **Free WooCommerce Integration** | Webhook-based, no plugin fees | Vikram |
| **Affiliate Campaigns** | Revenue share model, monthly settlements | Vikram |
| **AI-Guided Integration** | Meera helps brands connect stores | Vikram + AI |
| **Store Connection Check** | Block sale campaigns if store not connected | Vikram |
| **Creator Coupon Dashboard** | Simple copy-paste UI for creators | Ananya |
| **Affiliate Earnings View** | Creator sees commission per sale | Ananya |

---

## Revised Cost Summary (Rohan Approved)

| Item | Cost | Notes |
|------|------|-------|
| Shopify Partner Program | ₹0 | FREE (was incorrectly stated as $99) |
| WooCommerce Plugin | ₹0 | WordPress.org free listing |
| REST API | ₹0 | Existing infrastructure |
| JS Pixel CDN | ~₹400/month | Already in infra budget |
| **Total New Cost** | **₹0** | No new budget required |

---

## Updated Week 7-8 Tasks

### Week 7: Store Integrations + Unique Coupons

| Task | Owner | Priority |
|------|-------|----------|
| V24 migration: unique coupon per creator | Vikram | P0 |
| CouponCodeService auto-generation | Vikram | P0 |
| ShopifyOAuthService (free) | Vikram | P0 |
| ShopifyWebhookController | Vikram | P0 |
| WooCommerceWebhookController | Vikram | P1 |
| IntegrationHealthService | Vikram | P0 |
| CreatorCampaignCard UI | Ananya | P0 |
| StoreIntegrationSetup UI | Ananya | P0 |
| AI tool: check_store_integration | Vikram | P1 |
| AI tool: generate_integration_code | Vikram | P1 |

### Week 8: Affiliate Campaigns + Polish

| Task | Owner | Priority |
|------|-------|----------|
| V25 migration: affiliate tables | Vikram | P0 |
| AffiliateEarningsService | Vikram | P0 |
| AffiliateSettlementJob (monthly) | Vikram | P0 |
| CampaignTypeSelector UI | Ananya | P0 |
| AffiliateEarningsView UI | Ananya | P0 |
| Integration tests: full affiliate flow | Kavya | P0 |
| Security audit: affiliate payouts | Kabir | P0 |
| Load testing | Kavya | P1 |

---

## New AI Integration Tools

Added to influora-ai for Meera to help brands:

| Tool | Purpose |
|------|---------|
| `check_store_integration` | Check if brand's store is connected |
| `generate_integration_code` | Generate code snippets for any language |
| `generate_coupon_code` | AI-generated memorable coupon codes |

---

## Launch Blockers (Updated)

| Blocker | Owner | Status |
|---------|-------|--------|
| Unique coupon per creator | Vikram | ⬜ Pending |
| Store integration check for sale campaigns | Vikram | ⬜ Pending |
| Shopify OAuth flow working | Vikram | ⬜ Pending |
| Affiliate settlement job tested | Vikram + Kavya | ⬜ Pending |
| Kabir security sign-off on affiliate payouts | Kabir | ⬜ Pending |
| All previous blockers | Various | ⬜ Pending |

---

**End of Addendum**
