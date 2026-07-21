# Creator AI Co-pilot Tier-1 — CHANGE-SET MANIFEST

**Author:** Tara (Operations & Reporting) · **Date:** 2026-07-21 · **Status:** Definitive file map for build
**Purpose:** the single document listing every file each team will CREATE or MODIFY, mapped from the
finalized, greenlit planning package. This goes to Kabir for the security gate before code starts.

**Source package (all read for this manifest):**
- [`creator-copilot-priya-review-r1.md`](creator-copilot-priya-review-r1.md) — R1 + R2 rulings (binding)
- [`creator-copilot-API-CONTRACT.md`](creator-copilot-API-CONTRACT.md) — frozen v1
- [`creator-copilot-fe-components-plan.md`](creator-copilot-fe-components-plan.md) (Ananya)
- [`creator-copilot-fe-datalayer-plan.md`](creator-copilot-fe-datalayer-plan.md)
- [`creator-copilot-be-services-plan.md`](creator-copilot-be-services-plan.md) (Vikram)
- [`creator-copilot-ai-route-plan.md`](creator-copilot-ai-route-plan.md) (Sonnet #2)
- [`creator-copilot-meera-verify.md`](creator-copilot-meera-verify.md) (✅ PASS) + `migrations-draft/*.sql`
- [`creator-copilot-kavya-verify.md`](creator-copilot-kavya-verify.md) (CONDITIONAL PASS, 2 minor doc fixes)

**Roll-up:** **34 files total — 25 CREATE, 9 MODIFY.**
FE 8 (6 create / 2 modify) · BE-Java 18 (14 create / 4 modify) · AI-service (Python) 8 (2 create / 6 modify — the 2 create counted in the "AI" row, the 6 edits distributed) · DB migrations 4 (3 drafted create + 1 OAuth-flip to draft).
See per-area counts under each table; the OAuth-flip live-bug row is called out separately.

> **Paths:** FE paths are relative to repo root (`src/...`). BE-Java paths are relative to
> `influora-api/src/main/java/com/influora/` unless noted. AI-service paths are relative to
> `influora-ai/`. DB migrations live in `influora-api/src/main/resources/db/migration/`.

---

## 1. Frontend (React / TypeScript) — 6 CREATE, 2 MODIFY

| File path | C/M | Owner | What changes | Source plan § |
|---|---|---|---|---|
| `src/components/creator/copilot/DailySuggestionCard.tsx` | CREATE | Ananya | The `ready` + `dismissed` card bodies (Sparkles header, theme badge, headline, contentIdea, Dismiss/Mark-done CTAs); framer-motion arrival w/ `useReducedMotion` bypass; local button submit sub-state. WCAG-AA solid CTAs. | fe-components §1.1 |
| `src/components/creator/copilot/IGConnectPrompt.tsx` | CREATE | Ananya | Slim dashboard connect nudge (option b); calls `api.metaOAuth.authorize()` directly — does NOT fork OAuth. **No `onConnected` prop** (dropped per R1 Conflict 4). | fe-components §1.2; priya R1 §200-bind 3 |
| `src/components/creator/copilot/BusinessAccountRequired.tsx` | CREATE | Ananya | `NO_BUSINESS_ACCOUNT` drop-off explainer (warning IconBadge, 3-step switch-to-Business disclosure, reconnect + skip CTAs). Never blocks the dashboard. | fe-components §1.3, §4 |
| `src/components/creator/copilot/SuggestionEmptyState.tsx` | CREATE | Ananya | Inert "pending_tagging" / "no_suggestion_today" card; pulse icon gated on reduced-motion. Placeholder copy (Ash+Tejas gate). | fe-components §1.4 |
| `src/components/creator/copilot/DailySuggestionSection.tsx` | CREATE | Ananya | Orchestrator — the single `switch(status)` site; calls `useDailySuggestion()`, routes the 5 UI states to the components above. Mounts atop the dashboard, NOT in the layout shell. | fe-components §1.6, §3; priya R1 (bind 1) |
| `src/hooks/useDailySuggestion.ts` | CREATE | FE data-layer | react-query hook: `getTodaySuggestion` keyed on calendar day, `enabled: isConnected` gate, 5-state derivation, dismiss/markActed optimistic mutations, `requiresBusinessAccount` collapse, `error` string (component toasts, not the hook). | fe-datalayer §1 |
| `src/components/creator/creator-layout.tsx` | MODIFY | Ananya | **Nav entry DROPPED per R1 (bind 2)** — Tier-1 is a dashboard-mounted card, no `/creator/copilot` route, no `navItems` diff. Net change may be zero; retained here only to record the ruling. Actual mount is the dashboard page rendering `<HypeInboxCard />` (located at build time), where `<DailySuggestionSection/>` is mounted above it. | fe-components §2.1; priya R1 (bind 2) |
| `src/components/creator/connected-accounts.tsx` | MODIFY | Ananya | **`onConnected` prop DROPPED per R1 Conflict 4** — hook re-reads connectionState on remount; no callback survives the full-page redirect. Change reduces to none for the co-pilot feature (retained only if another non-remount consumer needs it). | fe-components §2.2; priya R1 Conflict 4, API-CONTRACT §5 |
| `src/lib/api.ts` | MODIFY | FE data-layer | Add flat `creatorCopilot` resource (`getTodaySuggestion`/`dismissSuggestion`/`markSuggestionActed`, `role:'creator'`, `isLive()/mockOr()`) + inline types `DailySuggestion`/`CreatorCopilotWireStatus`/`CreatorSuggestionTodayResponse` + `MOCK_CREATOR_SUGGESTION`. **Extend `MetaConnectionState` with `accountType: 'personal'\|'business'\|null`**; thread it through `get/setLocalConnectionState` + `MetaCallbackResponse`. | fe-datalayer §2.1-§2.5; API-CONTRACT §3, §4.2 |

> Shared types live **inline in `src/lib/api.ts`** (imported as `@/lib/api`), NOT a new
> `src/types/creator-copilot.ts` — R1 rejected the separate module (bind 4 / datalayer §0).
> The callback page (`/creator/settings/meta/callback`) already exists; it writes connectionState
> then navigates back — no new page file.

---

## 2. Backend — Java (`influora-api`) — 14 CREATE, 4 MODIFY

### 2a. New service / AI-client / entity / repo / config / job / controller — CREATE

| File path | C/M | Owner | What changes | Source plan § |
|---|---|---|---|---|
| `service/creatorcopilot/CreatorNudgeService.java` | CREATE | Vikram | Forks `TrendSparkNudgeService`; idempotent-read-first (= the cap), theme-match loop, `callAiSafely()`, `SuggestionResult` (pending_tagging/ready/no_suggestion_today), `DataIntegrityViolationException` race re-read, dismiss/acted resolve-then-check. `toDto()` = pure column read + computed `expiresAt`. | be-services §1; priya R2 (routing) |
| `service/creatorcopilot/CreatorMetaOAuthService.java` | CREATE | Vikram | Thin orchestrator wrapping token exchange + `storeCreatorToken` + `FacebookPageClient.resolveConnectedInstagram` for `NO_BUSINESS_ACCOUNT`; persists `ig_business_account_id`. One seam for Kabir to audit. | be-services §3.5, §3.8 |
| `integration/ai/CreatorSuggestionAiClient.java` | CREATE | Vikram | Sibling of `TrendSparkAiClient`; `PATH=/internal/creator-suggestion`, never throws (null→fallback), closed-vocab re-validation of any echoed `theme`. `SuggestionCopy(headline, contentIdea)` record. | be-services §4 |
| `integration/ai/dto/CreatorSuggestionAiDtos.java` | CREATE | Vikram | `SuggestionRequest`/`SuggestionResponse`/`ErrorResponse` records mirroring `TrendSparkAiDtos`. **Internal wire casing to freeze snake_case** (`theme_matched`/`trend_text`) — Kavya minor #2. | be-services §4; kavya §1.2, §4.2 |
| `domain/entity/CreatorNudgeLog.java` | CREATE | Vikram | Entity to R2 canonical columns: `id, creator_profile_id, trend_id, match_score, theme, headline, content_idea, message_source, prompt_version, shown_at, dismissed_at, acted_at, created_at` + Builder + idempotent `markDismissed`/`markActed`. Reuses `NudgeMessageSource`. | be-services §2.3; priya R2 |
| `domain/entity/CreatorCaptionCache.java` | CREATE | Vikram | `@Table(creator_captions)`; id, creatorProfileId, igMediaId, captionText, taggedThemesJson, tagStatus, postedAt/taggedAt/createdAt; `applyTagResult(...)`. | be-services §2.2 |
| `domain/enums/CaptionTagStatus.java` | CREATE | Vikram | `{ PENDING, TAGGED, FAILED }`. | be-services §2.4 |
| `repository/CreatorNudgeLogRepository.java` | CREATE | Vikram | `findByCreatorProfileIdAndShownAtAfter`, `findByIdAndCreatorProfileId` (IDOR-safe ownership). | be-services §2.3 |
| `repository/CreatorCaptionCacheRepository.java` | CREATE | Vikram | `findByCreatorProfileIdAndIgMediaId`, `findByTagStatusOrderByCreatedAtAsc(status, Pageable)`. | be-services §2.2 |
| `config/CreatorCopilotProperties.java` | CREATE | Vikram | Prefix `influora.creator-copilot`; `enabled`, `scoreThreshold`, **`maxSuggestionsPerCreatorPerDay`** (renamed per R2 item 3, binds `max-suggestions-per-creator-per-day`), `promptVersion`. | be-services §2.5; priya R2 (P2) |
| `config/CreatorSuggestionAiProperties.java` | CREATE | Vikram | Prefix `influora.creator-copilot-ai`; `baseUrl`/`connectTimeoutSeconds`/`requestTimeoutSeconds` (mirrors `TrendSparkAiProperties`). | be-services §4 |
| `job/CreatorThemeTaggingJob.java` | CREATE | Vikram | `@Scheduled` + `@SchedulerLock`; page PENDING captions → `themeMatchService.themesForText` (pure Java, NO model) → `applyTagResult` → union into `CreatorProfile.theme_tags`. OFF by default. | be-services §2.6 |
| `job/CreatorCaptionSyncJob.java` | CREATE | Vikram | Second single-responsibility job: fetch captions from Meta into `creator_captions` (split from tagging per R1 BE bind 4 / §6.9 APPROVED). | be-services §2.6, §6.9; priya R1 (BE bind 4) |
| `web/CreatorCopilotController.java` | CREATE | Vikram | `@RequestMapping("/creator/copilot")` — GET `/suggestion/today`, POST `/suggestion/{id}/dismiss`, POST `/suggestion/{id}/acted`. Identity via `creatorContext.requireCreatorProfile(principal)`, never a param. **No `ig/connect` route.** | be-services §5; API-CONTRACT §1 |
| `web/dto/creatorcopilot/CreatorCopilotDtos.java` | CREATE | Vikram | `SuggestionDto(id, theme, headline, contentIdea, expiresAt)` + `SuggestionTodayResponse(suggestion, status)` — camelCase, plain Jackson, wire-identical to FE. | API-CONTRACT §2 |

### 2b. OAuth ownership-flip + theme_tags — MODIFY (additive; brand path unchanged)

| File path | C/M | Owner | What changes | Source plan § |
|---|---|---|---|---|
| `domain/entity/CreatorProfile.java` | MODIFY | Vikram | Add `theme_tags` JSON column + plain getter/setter (system/batch-written, mirrors `BrandProfile.themeTagsJson`; `touch()` on set; NOT routed through `applySelfEdit`). | be-services §2.1 |
| `domain/entity/MetaOAuthToken.java` | MODIFY | Vikram | **Live-bug flip:** drop `nullable=false` on `workspace_id` → `@Column(name="workspace_id", length=26)`. | be-services §0, §3 item 2 |
| `repository/MetaOAuthTokenRepository.java` | MODIFY | Vikram | Add `findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(...)` (brand method untouched). | be-services §3 item 3 |
| `integration/meta/oauth/MetaTokenStorage.java` | MODIFY | Vikram | Add creator-owned siblings `storeCreatorToken`/`getValidCreatorToken`/`revokeCreatorToken` (reuse AES-256-GCM `encrypt/decrypt` unchanged; builder omits `.workspaceId`). | be-services §3 item 4 |

### 2c. Live-bug fix — its own row (P0, contained)

| File path | C/M | Owner | What changes | Source plan § |
|---|---|---|---|---|
| `web/MetaOAuthController.java` **+** `service/MetaConnectionService.java` | MODIFY | Vikram | **THE LIVE OAUTH FLIP FIX (P0).** Controller `/meta/oauth/callback` stops passing `principal.getWorkspaceId()` (null for creators → `DataIntegrityViolationException` today; the creator IG-connect flow — this feature's entry point — is 100% broken). Route through `CreatorMetaOAuthService.connect(...)`. `MetaConnectionService`: drop the `workspaceId` param, fix the `WHERE workspace_id = NULL` never-match bug, switch to `getValidCreatorToken`/`revokeCreatorToken`; wire a minimal `GET /creator/copilot/ig/status` + disconnect (BE bind 5). Logged as its own bug finding, ships as part of the flip. | be-services §0, §3 items 6-7; priya R1 live-bug §140-181, BE bind 5 |

> **Note — `MetaConnectionService` needs a controller** (currently orphaned; R1 BE bind 5 pulls the
> minimal `ig/status`+disconnect into Tier-1 scope). If Vikram adds a dedicated
> `web/CreatorMetaStatusController.java` rather than folding the two routes into
> `CreatorCopilotController`, that is one additional CREATE — left to Vikram's structural call at
> build time; counted here under the MetaConnectionService fix row, not as a separate guaranteed file.

---

## 3. AI service — Python (`influora-ai`) — 2 CREATE, 6 MODIFY

| File path | C/M | Owner | What changes | Source plan § |
|---|---|---|---|---|
| `app/routes/creator_suggestion.py` | CREATE | Sonnet #2 (Ash reviews) | `POST /internal/creator-suggestion` (internal name unchanged by the `/copilot/*` public ruling). Orchestration mirrors `trendspark.py`: `verify_creator_token` → normalize (closed-vocab `theme_matched`, cap `trend_text`) → `check_spend_gate(workspace_id=creator_profile_id)` → Haiku → `parse_and_validate` → always HTTP 200. **No `caption_snippet`** (R1 Conflict 5). `_MARKETPLACE_RE` local (unconditional). | ai-route §1, §2.3 |
| `app/prompt/creator_suggestion.py` | CREATE | Sonnet #2 (Ash) | Forked creator-tone prompt: warm peer voice, no OWN_CONTENT/SNAPSBY branch, `FORBIDDEN_MARKETPLACE_WORDS`, imports `FORBIDDEN_PETNAMES`. `build_system_prompt`/`build_user_message(theme_matched, trend_text)`/`fallback_message(...)→(headline, content_idea)`. Wraps ONLY `trend_text`. | ai-route §2 |
| `app/prompt/validators.py` | CREATE | Sonnet #2 | **Extract-first PR (lands BEFORE the creator route).** Move `_CODE_FENCE_RE`/`_PETNAME_RE`/`_LOVE_VOCATIVE_RE`/`_PRICE_RE`/`_STATEMENT_RE` + `_has_forbidden_petname`/`_statement_count` out of `trendspark.py` into this shared module (security controls = one source of truth, R1 Conflict 7). | ai-route §2.3; priya R1 Conflict 7 |
| `app/routes/trendspark.py` | MODIFY | Sonnet #2 | **Extract-first PR only:** import the 5 regexes + 2 helpers from `app/prompt/validators.py` instead of defining locally. Zero behavior change; Kavya re-runs full trendspark suite green on this PR alone. | ai-route §2.3; kavya §3.1 |
| `app/auth/service_token.py` | MODIFY | Sonnet #2 (Kabir gate) | Additive: `SCOPE_CREATOR="creator"`, `ENDPOINT_SCOPES["creator_suggestion"]=(SCOPE_CREATOR,)`, `VerifiedCreatorToken`, `verify_creator_token(...)` asserting `token.creator_profile_id == body.creator_profile_id`. Does NOT touch `verify_token`. Bidirectional scope segregation. | ai-route §4.1 |
| `app/config.py` | MODIFY | Sonnet #2 | Additive: `CREATOR_COPILOT_MODEL` (defaults to `TRENDSPARK_MODEL` string) + Settings fields `creator_copilot_max_trend_text_chars` (200), `_max_headline_chars` (120), `_max_content_idea_chars` (300), `_max_tokens` (300). **Reuse global `PROMPT_VERSION`** — no `CREATOR_PROMPT_VERSION` (R1). | ai-route §5.1, §5.2 |
| `app/costs/pricing.py` | MODIFY | Sonnet #2 | Additive: `PRICING_TABLE` row or `_resolve_rate` fallback branch for `CREATOR_COPILOT_MODEL` so an override to a distinct id doesn't under-record spend. | ai-route §5.2 |
| `app/security/redaction.py` | MODIFY | Sonnet #2 | Additive `_REDACT_KEYS`: `caption`, `captions`, `ig_handle` (forward cover — `caption_snippet` no longer exists). `creator_profile_id` logged in clear (tracing key). | ai-route §4 (P2), §5.1 |
| `app/main.py` | MODIFY | Sonnet #2 | Register the new router (mirror trendspark registration; add `test_creator_suggestion_registration.py`). | ai-route summary table |

> **Redaction keys**, **service_token creator scope**, and **config keys** are the three P0/P1
> security seams Kabir must sign off. `creator_caption_tag.py` (route + prompt) is **NOT built** in
> Tier-1 (R1 Conflict 5 — deferred to Tier-2).

---

## 4. DB migrations (Flyway, `influora-api/.../db/migration/`) — 3 drafted CREATE + 1 to draft

| File path | C/M | Owner | What changes | Source plan § |
|---|---|---|---|---|
| `V20260721120000__creator_profile_theme_tags.sql` | CREATE | Meera | Additive `ALTER` — `creator_profiles.theme_tags JSON NULL` (precedent: `brand_profiles.theme_tags`, V51). Drafted, NOT applied. | meera-verify §3; migrations-draft |
| `V20260721130000__creator_captions.sql` | CREATE | Meera | New `creator_captions` table (id, creator_profile_id, ig_media_id, caption_text, tagged_themes JSON, tag_status, postedAt/taggedAt/createdAt; `UNIQUE(creator_profile_id, ig_media_id)`, `idx_captions_status`). Drafted, NOT applied. | meera-verify §3; migrations-draft |
| `V20260721140000__creator_nudge_log.sql` | CREATE | Meera | New `creator_nudge_log` to R2 columns (`theme`/`headline`/`content_idea` split, `prompt_version`, `dismissed_at`/`acted_at`) + `idx_cnl_creator_day` + generated-column `uq_creator_nudge_day` daily-cap backstop. R2-rebuilt, P0 closed. Drafted, NOT applied. | meera-verify §4.1; priya R2; migrations-draft |
| `V20260721150000__meta_oauth_workspace_nullable.sql` *(proposed number — NOT yet drafted)* | CREATE | Meera | **OAuth-flip live-bug migration:** `ALTER TABLE meta_oauth_tokens MODIFY COLUMN workspace_id VARCHAR(26) NULL`. Existing `uq_meta_oauth_workspace_creator` + FK stay (MySQL exempts NULL from FK, multiple NULLs don't collide). **Gated on Meera's read-only prod check first.** | be-services §3 item 1; priya R1 live-bug directive #1 |

> **V-numbering verified** (meera §1.3): highest existing migration is `V20260718190000`; all four
> proposed numbers are higher and collision-free. The three timestamped files are drafted in
> `wiki/build/migrations-draft/`; the OAuth-flip ALTER is specified in be-services §3.1 but **not yet
> written as a numbered file** — Meera drafts it only after the read-only prod check on
> `meta_oauth_tokens` (below).

---

## 5. Sequencing & gates (footer)

1. **Money-path merge gate (governs all).** The parent money-path signoff still governs — nothing
   in this manifest starts until that lands. This manifest defines *what* to build the day it does;
   it is not itself the signoff. (priya R1 pre-condition #1.)

2. **Extract-first validators PR gate (blocks the creator AI route).** `app/prompt/validators.py`
   + the `trendspark.py` refactor must merge as a **separate PR**, with **Kavya's full trendspark
   test suite green on that PR alone**, BEFORE `app/routes/creator_suggestion.py` is written.
   Security controls get one source of truth; this is priya R1 Conflict 7 + ordered pre-condition #4
   (kavya §3.1). `creator_suggestion.py`, `creator_suggestion` prompt, and the `_MARKETPLACE_RE`
   local validator all depend on this landing first.

3. **OAuth-flip read-only prod check (blocks the flip migration).** Meera runs the read-only
   `meta_oauth_tokens` count SQL (meera §2) BEFORE `V20260721150000` is written — sizes blast radius,
   confirms no anomalous rows (failed inserts left none). Read-only, no writes to prod. If any
   creator-linked / non-revoked rows appear, escalate before migrating. (priya R1 live-bug directive #1;
   meera §2 flags the failed-insert history is NOT in the queryable `error_log`, only server logs.)

4. **Ash + Tejas zero-state copy — OPEN ITEM (non-blocking for code).** The "silence" vs
   "post-first" copy for `no_suggestion_today` / zero-themes is a product decision routed to Ash +
   Tejas (priya R1 Conflict 6). Code proceeds with **placeholder copy**; the final copy is a one-line
   swap. It blocks **Kavya's zero-state test assertion and the ship**, NOT the build. No 6th `status`
   value is introduced whichever way it lands. (fe-components §6.2; kavya §3.2 item 7.)

5. **Two pre-code doc corrections (Kavya CONDITIONAL PASS, cosmetic, non-blocking):**
   (a) be-services §2.5 line 103 stale path `GET /api/creator/suggestion` → canonical
   `GET /api/v1/creator/copilot/suggestion/today`;
   (b) freeze the internal `/internal/creator-suggestion` request DTO casing (recommend snake_case
   `theme_matched`/`trend_text`) between Vikram + the AI-route agent. (kavya §4.2.)

**Verification status carried into this manifest:** Meera ✅ PASS (package code-ready, P0 closed
under R2); Kavya CONDITIONAL PASS (2 minor doc fixes, no build blockers); all 4 tracks GREEN under
Priya R1+R2. This manifest → **Kabir security gate** next.
