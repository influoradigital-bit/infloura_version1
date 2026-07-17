# AI Features — Master Architecture & Contract Chain

> **Author:** Priya (CTO) · **Date:** 2026-07-10 · **Status:** LOCKED
> **Inputs:** Swapnil (business), Arjun (sequencing), Ash (AI capability spec)
> **Grounded in:** actual source files, not specs. Every path below was read.

This is the connecting document. Each employee spec (`wiki/tech/employees/*.md`) implements
one horizontal layer of the same vertical slices defined here. **No employee spec introduces
a name that does not appear in this file.**

---

## 0. TWO CORRECTIONS TO THE RECORD

Before anyone builds, two things this team has said out loud that the code contradicts.

### 0.1 Creators DO apply. We said they don't.

`docs/creator-features.md` says *"No Discover — creators don't discover brands."* The schema
disagrees:

```sql
-- V6__creators_collaborations.sql:57-59
status  ENUM('INVITED','APPLIED','SHORTLISTED','IN_NEGOTIATION',...) NOT NULL DEFAULT 'INVITED',
source  ENUM('INVITATION','APPLICATION') NOT NULL,
```

Both paths exist and are persisted. **Ash and Swapnil both asserted invite-only in this
thread. That was wrong.** Any AI feature that assumes invite-only will silently ignore
`source = 'APPLICATION'` creators. Fix the doc or fix the schema — Swapnil rules, but nobody
builds against the false version.

### 0.2 The tier gate is NOT missing. It's asymmetric.

Ash filed P0-3: *"`TOOL_TIERS` is never called in `app/`."* True for Python. But Java has:

- `domain/enums/MeeraToolTier.java` — `R | D | C | FORBIDDEN`
- `service/meera/tool/ToolCallValidator.java` — name-whitelist + tier gate, audit-logged
- `web/MeeraInternalController.java` — every `/internal/meera/*` call passes the validator
- `service/AmountDerivationService.java` — server re-derives every amount

So the constraint **is** enforced — at the Spring boundary, correctly, with
`FORBIDDEN` meaning "no endpoint exists" rather than "soft-blocked."

**The accurate finding is:** enforcement is single-layer. Python forwards a `commit` tool with
no local check and relies entirely on Java to refuse. Kabir's injection chain still ends at a
Java wall — it just shouldn't get that far. P0-3 is **downgraded to P1** and re-scoped to
"mirror the Java tier gate in Python so the two agree." Ash overstated; Priya corrects the record.

---

## 1. WHAT ACTUALLY EXISTS (verified, not assumed)

| Layer | Reality |
|---|---|
| **Web** | Vite 6 + React 19 + react-router-dom 7, TS 5.7. **Not Next.js.** `next.config.mjs` is vestigial — `TECH-STACK.md` in the agent roster is stale and must be corrected by me. |
| **State** | TanStack Query 5, Zustand 5, react-hook-form + zod |
| **API** | Spring Boot 3.3.5, Java 21, JPA, Flyway (**47 migrations**, V1→V47), Redis, MySQL 8 |
| **AI** | FastAPI 0.115 — `influora-ai/app/**`. Claude + Gemini + Sarvam. Stateless. |
| **Money** | Razorpay, `wallet_transactions` (V8), `escrow_holds` (V9) |

### Data we already have and are not using

| Table | Migration | Populated by | Consumed by AI today |
|---|---|---|---|
| `creator_profiles` | V6 | onboarding | partially (`show_creators`) |
| `platform_stats` | V6 | Meta OAuth sync | partially |
| `collaborations` | V6 | campaign lifecycle | **no** |
| `creator_scores` | V22 | `ScoreCalculationJob` (daily) | **no** |
| `audience_demographics` | V25 | `AudienceDemographicsJob` (weekly) | **no** |
| `reviews` | V43 | brand post-campaign | **no** |
| `disputes` | V45 | admin | **no** |

**This is the finding that matters.** Swapnil asked "where do the audiences come from, we have
lots of data." We have it. `audience_demographics` stores Meta's `audience_city`,
`audience_country`, `audience_gender_age`, `audience_locale` as JSON bucket maps, immutable
snapshots, latest-by-`(creator_profile_id, time DESC)`. Frontend hooks already exist —
`src/hooks/analytics/useCreatorDemographics.ts`, `useCreatorScores.ts`, `useCreatorMetrics.ts`.

**None of it reaches Meera.** The `show_creators` executor returns `{creatorId, displayName,
followers, engagementRate}` and nothing else. Every "smart creator summary" idea in Swapnil's
list is blocked on one thing: *joining tables we already populate.*

### The broken link (highest-value, lowest-effort fix in the system)

```
influora-ai/app/routes/brand_safety.py     ✅ BUILT — GARM classifier, forced-tool JSON
influora-api/.../integration/ai/BrandSafetyAiClient.java  ✅ BUILT — client exists
creator_scores.brand_safety_score          ⚠️ COLUMN EXISTS, ALWAYS NULL
creator_scores.garm_flags                  ⚠️ COLUMN EXISTS, ALWAYS NULL
creator_scores.content_sentiment           ⚠️ COLUMN EXISTS, ALWAYS NULL
```

From `V22__creator_scores.sql` header, verbatim:

> *"`BrandSafetyScoreService` requires cross-repo integration with influora-ai and is deliberately
> NOT built in this pass… Whoever builds BrandSafetyScoreService next should start populating
> exactly these 3 columns — no other schema change should be needed."*

Both ends are built. The wire between them is not. **This is Vikram's first ticket.**

---

## 2. THE FIVE VERTICAL SLICES

Every employee spec implements their layer of these five. Nothing else is in scope.

| # | Slice | Wave | Frontend | Backend | DB | AI |
|---|---|---|---|---|---|---|
| **S1** | Prompt-injection hardening | 1 | — | — | — | Ash spec → Vikram |
| **S2** | Python↔Java tier parity | 1 | — | Vikram | — | Ash spec |
| **S3** | Brand-safety score wire-up | 1 | Ananya (badge) | Vikram | Meera (backfill) | exists |
| **S4** | Creator fit summary | 2 | Ananya | Vikram | Meera (V48) | Ash prompt |
| **S5** | Campaign taxonomy + evals | 2 | — | — | — | Ash |

---

## 3. THE CONTRACT CHAIN — S4 (Creator Fit Summary)

This is the reference slice. **Every name below is normative.** If a name appears in two
employee specs, it must be spelled identically. Drift here is the six-month problem.

```
┌─ DB ─────────────────────────────────────────────────────────────┐
│ EXISTING (read, do not alter):                                   │
│   creator_profiles(id, city, engagement_rate, total_followers)   │
│   platform_stats(creator_profile_id, followers, engagement_rate) │
│   audience_demographics(creator_profile_id, time, audience_city, │
│                         audience_gender_age, ...) — latest row   │
│   creator_scores(creator_profile_id, time, quality_score,        │
│                  fake_follower_score, brand_safety_score)        │
│   collaborations(campaign_id, creator_id, status, agreed_rate)   │
│                                                                   │
│ NEW — V48__creator_reliability_stats.sql (Meera):                │
│   creator_reliability_stats(                                     │
│     creator_profile_id  VARCHAR(26) PK,                          │
│     completed_deals     INT,                                     │
│     completion_rate     DECIMAL(5,2),  -- COMPLETED / terminal   │
│     on_time_rate        DECIMAL(5,2),                            │
│     avg_response_minutes INT,                                    │
│     revision_rate       DECIMAL(5,2),                            │
│     avg_rate_per_deliverable DECIMAL(12,2),                      │
│     computed_at         DATETIME(6)                              │
│   )                                                              │
│   Recomputed nightly. Derived from collaborations + reviews.     │
└──────────────────────────────────────────────────────────────────┘
                              ↓ JPA
┌─ BACKEND (Vikram) ───────────────────────────────────────────────┐
│ entity/   CreatorReliabilityStats.java                           │
│ repository/ CreatorReliabilityStatsRepository                    │
│ service/  CreatorFitService.java   ← NEW, the join              │
│              .buildFitProfile(creatorId, campaignId)             │
│ job/      ReliabilityStatsJob.java ← nightly, mirrors            │
│              ScoreCalculationJob pattern                         │
│                                                                   │
│ MODIFIED: MeeraInternalController /internal/meera/show_creators  │
│   response gains `fitProfile` per creator (see §4 DTO)           │
│   Tier stays R. No new tool. No new endpoint.                    │
└──────────────────────────────────────────────────────────────────┘
                              ↓ HTTP (dual-auth mesh)
┌─ AI (Ash specs, Vikram implements) ──────────────────────────────┐
│ influora-ai/app/tools/schemas.py                                 │
│   show_creators input_schema: + campaign_id (optional)           │
│   ⚠️ CI shared-schema diff-check MUST be updated in the same PR  │
│ influora-ai/app/prompt/persona.py                                │
│   + "When a fitProfile is present, explain WHY this creator      │
│      fits THIS campaign. Never invent a number not in the tool   │
│      result."                                                    │
└──────────────────────────────────────────────────────────────────┘
                              ↓ SSE tool_result event
┌─ FRONTEND (Ananya) ──────────────────────────────────────────────┐
│ src/types/meera.ts        CreatorFitProfile (mirrors DTO 1:1)    │
│ src/hooks/brand/useCreatorFit.ts    TanStack Query               │
│ src/components/feature/meera/CreatorFitCard.tsx                  │
│ src/components/feature/meera/CreatorCompareTable.tsx             │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. THE DTO — single source of truth

Java record, TS interface, and Python's expected `tool_result` shape must be **byte-identical
in field names**. Java is canonical; TS and Python mirror it.

```java
// influora-api/.../web/dto/meera/MeeraToolDtos.java  (ADD to existing file)
public record CreatorFitProfile(
    String  creatorId,
    int     followers,
    BigDecimal engagementRate,        // 4.20
    Integer audienceCityPct,          // 18  — from audience_demographics, null if no snapshot
    String  audienceTopCity,          // "Mumbai"
    Integer audienceFemalePct,        // 82
    String  audienceTopAgeBand,       // "18-34"
    Integer completedDeals,           // 8
    BigDecimal completionRate,        // 100.00
    BigDecimal onTimeRate,            // 87.50
    Integer avgResponseMinutes,       // 240
    BigDecimal qualityScore,          // 78.40   — creator_scores
    BigDecimal fakeFollowerScore,     // 3.10    — creator_scores, LOW is good
    BigDecimal brandSafetyScore,      // 96.00   — NULL until S3 lands
    List<String> riskFlags            // ["missed_deadline_1_of_8"]
) {}
```

```ts
// src/types/meera.ts  — MIRROR. Same names. camelCase already matches.
export interface CreatorFitProfile {
  creatorId: string;
  followers: number;
  engagementRate: number;
  audienceCityPct: number | null;
  audienceTopCity: string | null;
  audienceFemalePct: number | null;
  audienceTopAgeBand: string | null;
  completedDeals: number;
  completionRate: number;
  onTimeRate: number;
  avgResponseMinutes: number;
  qualityScore: number | null;
  fakeFollowerScore: number | null;
  brandSafetyScore: number | null;   // null until S3
  riskFlags: string[];
}
```

**Nullability is load-bearing.** `brandSafetyScore` is `null` for every creator until S3
backfills. Ananya's card must render the null state; Ash's prompt must not claim a safety
score that doesn't exist. This is exactly the failure mode Ash's review was written to prevent.

---

## 5. ARCHITECTURAL RULES (I own these; no agent overrides)

1. **SQL matches. The model narrates.** Any creator ranking, fit score, or campaign match is
   computed in Java from persisted rows. The LLM receives the result and explains it in prose.
   A model that *chooses* creators can hallucinate a creator. A model that *explains* a chosen
   creator cannot.

2. **No sixth Meera tool.** `MeeraToolName` has exactly 5 entries and
   `ToolCallValidator.TIER_BY_TOOL` is an `EnumMap` over it. `show_creators` gains a richer
   *response*, not a sibling tool. Adding a tool means touching the Java enum, the Python
   schema, the CI diff-check, and the permissions matrix — four places, one PR, my sign-off.

3. **The AI never writes money.** `MeeraToolTier.FORBIDDEN` means *no endpoint exists*. Payment
   add/update/delete is absent, not blocked. This survives S1–S5 unchanged.

4. **Every new number the AI can say must trace to a column.** If Ash's prompt says "creators
   your size average ₹1,350," there must be a `avg_rate_per_deliverable` field in a tool result.
   If there isn't, the prompt doesn't get to say it.

5. **Nullable is a state, not an error.** Every new field ships nullable, renders a null state,
   and is described in the prompt as possibly-absent. We backfill after.

6. **Schema drift fails CI.** The `goal` ↔ `campaign_type` drift Ash found
   (`schemas.py:82` says `awareness|launch|conversion|review`; `02-API-CONTRACT-BRAND.md:156`
   shows `goal:"HYPE"`) exists because the shared-schema diff-check is not enforcing. Fixing the
   drift without fixing the check is not a fix.

---

## 6. STALE DOCS I AM CORRECTING

| Doc | Claim | Reality |
|---|---|---|
| `TECH-STACK.md` (agent roster) | Next.js 14, Prisma, NextAuth, Vercel | Vite 6 + React 19, JPA/Flyway, Spring Security, Docker |
| `docs/creator-features.md` | Creators don't apply | `collaborations.source = APPLICATION` exists |
| `wiki/ai-review/...ai-review.md` | P0-3 tier gate missing | Java enforces; Python doesn't mirror. P1. |

I will rewrite `TECH-STACK.md`. Nobody builds against the old one.

---

## 7. EMPLOYEE SPECS

| Agent | File | Owns |
|---|---|---|
| Ananya | `ananya-frontend-spec.md` | React components, hooks, types, null states |
| Vikram | `vikram-backend-spec.md` | Entities, services, jobs, executors, DTOs |
| Meera | `meera-database-devops-spec.md` | V48 migration, backfill, nightly jobs, CI |
| Kabir | `kabir-security-spec.md` | Injection regression, tier parity, PII boundary |
| Kavya | `kavya-qa-spec.md` | Gate criteria, contract tests, null-state coverage |
| Ash | `ash-ai-spec.md` | Prompts, tool schemas, eval sets. **Writes no code.** |

**Order:** Meera (V48) → Vikram (service+DTO) → Ash (prompt) → Ananya (UI) → Kabir → Kavya.
Arjun enforces. Nobody starts a downstream layer before the DTO above it is merged.
