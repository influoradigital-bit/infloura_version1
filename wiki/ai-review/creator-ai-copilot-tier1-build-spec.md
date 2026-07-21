# Build Spec: Creator AI Co-pilot — Tier-1 Guidance Pilot

**Status:** BUILD-READY DRAFT · **Date:** 2026-07-21 · **Gate:** DO NOT START until Priya
certifies money-path stability (escrow happy-path, payout idempotency, subscription webhook).
**Council:** Priya (arch) · Vikram (BE) · Ananya (FE) · Meera (DB/DevOps) · Kabir (sec) · Kavya (QA) · Ash (AI)
**Parents:** [proposal + CEO decision](creator-ai-copilot-proposal.md) · [why-the-gap review](how-ai-helps-creators-ai-review.md)

---

## 0. What we are building (one paragraph)
A creator links their Instagram → a nightly batch job tags their posts' captions into our
existing closed theme vocabulary → those themes are matched against the same `trends` table
brand-side Trend-Spark already uses → a cheap **Haiku 4.5** call phrases ONE suggestion per
creator per day ("your skincare + winter niche is trending — here's a 3-beat reel idea") →
shown on a new creator dashboard card. **This is Trend-Spark pointed at the creator.** No
money, no campaign-apply, no coaching, English-only — those are deferred (§8).

**Kill metric (owner: Tejas):** creator 7-day activation (link → suggestion → posts within 48h)
**< 25% after 4 weeks kills the feature.**

---

## 1. Architecture & data flow (owner: Priya)

```
Creator IG OAuth   → CreatorMetaOAuthService (NEW, forks MetaOAuthService)        [Vikram]
        ↓             creator-OWNED token (workspace_id = null), not workspace-scoped
Caption fetch      → InstagramMetricsFetcher (REUSE) → creator_caption_cache (NEW) [Vikram/Meera]
        ↓             captions already returned by Graph API today; just persist them
Theme-tag batch    → CreatorThemeTaggingJob (NEW nightly cron, @SchedulerLock)      [Meera/Vikram]
        ↓             ThemeMatchService.themesForText() per caption → closed vocab
Theme store        → CreatorProfile.theme_tags (NEW JSON column)                    [Meera]
        ↓
Suggestion request → CreatorNudgeService (NEW, FORK of TrendSparkNudgeService)      [Vikram]
        ↓             NO BrandProfile / catalog / gap-check / SNAPSBY
Theme match        → ThemeMatchService.score(trend, creatorThemeTagsJson) (REUSE)   [—]
        ↓             already parametric — takes any theme JSON as-is
AI phrasing        → /internal/creator-suggestion, Haiku 4.5, creator-tone prompt   [Ash/Vikram]
        ↓             captions wrapped <untrusted_> before the model
Log                → creator_nudge_log (NEW), PROMPT_VERSION stamped                [Meera]
        ↓
REST               → GET /api/creator/suggestion (+ dismiss/acted)                  [Vikram]
        ↓
Creator UI         → src/components/creator/copilot/* (NEW, 0% today)               [Ananya]
```

**Rule of thumb the whole council agreed on:** `TrendSparkNudgeService` is welded to
`BrandProfile`/catalog/`NudgeLog` — **FORK it, do not extend it.** `ThemeMatchService` is
already parametric (`score(Trend, themeTagsJson)` takes a JSON string) — **REUSE as-is.**

### Non-negotiable invariants (every engineer honors these)
1. **Per-creator/day cap enforced server-side** in `CreatorNudgeService` — never client, never
   trusted from the request. One suggestion/creator/day, hard.
2. **Creator captions wrapped `<untrusted_content>`** before ANY model call (both the tagging
   pass and the phrasing pass). Captions are attacker-controlled input.
3. **Tenant/ownership isolation** — every read/write filters by the authenticated creator's own
   subject; resolve-then-check (never trust a `creatorProfileId` from the request body).
4. **Deterministic templated fallback** on any AI/parse/timeout/spend-gate failure — HTTP 200,
   `message_source=FALLBACK`, never a 5xx for a phrasing miss.
5. **`PROMPT_VERSION` stamped** on every `creator_nudge_log` row (money/behaviour audit trail).
6. **Zero money actions in Tier-1** — no wallet, payout, or spend surface touches this path.

---

## 2. Backend (owner: Vikram)

### 2.1 Schema changes → hand to Meera (§4 has the migrations)
- `CreatorProfile.theme_tags` (JSON) — copy the `BrandProfile.themeTagsJson` field + getter/setter;
  update via the existing null-guarded partial-update pattern (`applySelfEdit`), do NOT let it
  touch `applicationStatus`/`suspended`/`tierOverride`.
- `creator_caption_cache` (NEW table) — persists captions from `InstagramMetricsFetcher` so the
  batch tagger doesn't refetch IG live.
- `creator_nudge_log` (NEW table) — mirror of `NudgeLog` but keyed on `creator_profile_id`;
  drop `campaign_type`/`video_ids`/`purchased_*` (no catalog in Tier-1); keep `trend_id`,
  `match_score`, `message`, `message_source`; add `dismissed_at`, `acted_at`.

### 2.2 `CreatorNudgeService` (FORK `TrendSparkNudgeService`)
Orchestration order — mirror the brand service's fail-safe discipline (try/catch never throws):
1. Load `CreatorProfile` by authenticated userId → **fail-closed empty** if no `theme_tags` yet
   (mirrors `TrendSparkNudgeService:84-88` "no profile → stay silent").
2. **Per-creator/day cap check FIRST** (before any AI spend) — see §2.5.
3. Iterate active `Trend`s → `ThemeMatchService.score(trend, creatorThemeTagsJson)` (reuse) →
   pick best above `props.getScoreThreshold()`.
4. `callAiSafely()` → new `CreatorSuggestionAiClient` → AI message OR templated fallback.
5. Save `creator_nudge_log` (PROMPT_VERSION stamped) → return DTO (no `videoCards`).

### 2.3 IG OAuth ownership flip ⚠️ (flag Kabir — auth boundary)
`MetaTokenStorage` today keys on `workspaceId + creatorProfileId`
(`findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse`). Make it creator-owned WITHOUT breaking
the brand path:
- Make `workspaceId` nullable on `MetaOAuthToken`.
- ADD overloads `findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse` — **overload, don't
  rewrite** the brand signature.
- New `CreatorMetaOAuthService` path stores with `workspaceId = null`.
- Keep AES-256-GCM per-row encryption (already correct).

### 2.4 `CreatorSuggestionAiClient` + AI route
New Java client mirroring `TrendSparkAiClient`'s contract (**never throws, returns null on any
failure** → caller falls back to template). New AI-service route `/internal/creator-suggestion`
(Haiku 4.5), captions wrapped `<untrusted_>`, closed-vocab validated. Ash owns the prompt (§7).

### 2.5 REST endpoints + cap
- `GET /api/creator/suggestion` → today's suggestion or null.
- `POST /api/creator/suggestion/{id}/dismiss` · `POST /api/creator/suggestion/{id}/acted`.
- `POST /api/creator/ig/connect` → routes to the flipped OAuth path.
- All resolve creator identity from the **authenticated principal, never a path/body param**
  (same discipline as `requireOwnedNudge`, `TrendSparkNudgeService:188-195`).
- **Cap:** `existsByCreatorProfileIdAndCreatedAtAfter(startOfDay)` checked BEFORE the AI call.
  ⚠️ **Race (flag Kabir):** two concurrent requests before the day's row commits → back the cap
  with a **DB unique constraint or row lock**, not just the app-level check.
- **Batch tagger:** new `@Scheduled` job pulls uncached captions → `ThemeMatchService.themesForText`
  per caption → rolls up into `CreatorProfile.theme_tags`. Batch, not real-time.

---

## 3. Frontend (owner: Ananya)

### 3.1 New components → `src/components/creator/copilot/`
- `DailySuggestionCard.tsx` — props `{ suggestion: {id, theme, headline, contentIdea, expiresAt} | null;
  status: 'idle'|'loading'|'ready'|'dismissed'|'error'; onDismiss; onMarkActed }`. Model on
  `hype-inbox-card.tsx` (same Card shell + header badge + local status machine).
- `IGConnectPrompt.tsx` — reuses `connected-accounts.tsx` connect logic (`api.metaOAuth.authorize()`),
  co-pilot copy. Do NOT fork the OAuth logic.
- `BusinessAccountRequired.tsx` — the personal-vs-Business drop-off state (§3.3).
- `SuggestionEmptyState.tsx` — "linked, tagging in progress."
- `useDailySuggestion.ts` (`src/hooks/`) — fetch/cache today's suggestion + status enum.

### 3.2 Reuse / modify
- `connected-accounts.tsx` — reuse `handleConnect` as-is; add optional `onConnected` callback so
  the co-pilot reacts without polling.
- `creator-layout.tsx` — add nav entry (Sparkles icon via `IconBadge`) + mount card atop the
  creator dashboard, above `HypeInboxCard`.
- `feature/meera/{MessageBubble,ThinkingState}.tsx` — borrow the "AI phrased this" visual accent +
  thinking shimmer for the loading state (a card, not a chat).

### 3.3 IG-connect UX (Business-account drop-off)
Backend can't detect "personal IG" pre-authorize — only post-callback. After OAuth, if the
co-pilot API returns a distinct `NO_BUSINESS_ACCOUNT` code, render `BusinessAccountRequired`:
plain-language explainer ("your IG is Personal — co-pilot needs a Business/Creator account linked
to a Facebook Page") + a 3-step "switch in the Instagram app" disclosure + a "skip for now"
secondary CTA. **Never block the rest of the dashboard on this.**

### 3.4 States (all 5 driven by the `status` prop)
`idle` → `IGConnectPrompt` · `loading` → `SuggestionEmptyState` ("usually ready within a day") ·
`ready` → full card + Dismiss/Mark-done · `dismissed` → collapsed row ("next one tomorrow") ·
`error/offline` → toast (API errors are toast-only per repo convention) + inline retry.

### 3.5 API contract Ananya needs (freeze in `API-CONTRACT.md` so FE parallelizes)
```
GET  /api/creator/copilot/suggestion/today
  → { suggestion: {id, theme, headline, contentIdea, expiresAt} | null,
      status: 'pending_tagging' | 'ready' | 'no_suggestion_today' }
POST /api/creator/copilot/suggestion/:id/dismiss
POST /api/creator/copilot/suggestion/:id/acted
IG connectionState must expose accountType: 'personal' | 'business'   (extend getLocalConnectionState)
```

### 3.6 Design/a11y
Strong WCAG-AA CTAs from the brand palette (no pale pastel — solid `Button`, not ghost). State
text uses `text-success-foreground` / `text-destructive-foreground` (NOT `text-destructive`,
per this repo's pale-bg/strong-fg token convention). Icons `aria-hidden`, `useReducedMotion` on
arrival animation, Tailwind only, typed props (no `any`).

---

## 4. DB migrations & DevOps (owner: Meera)

Next V-numbers follow the post-V68 **timestamp** convention (latest is `V20260718190000`):

- **`V20260721120000__creator_profile_theme_tags.sql`**
  `ALTER TABLE creator_profiles ADD COLUMN theme_tags JSON NULL;` (mirrors `brand_profiles.theme_tags`).
- **`V20260721130000__creator_captions.sql`** — `creator_captions(id, creator_profile_id FK,
  ig_media_id, caption_text TEXT, tagged_themes JSON, tag_status VARCHAR(16) DEFAULT 'PENDING',
  posted_at, tagged_at, created_at)`, `UNIQUE(creator_profile_id, ig_media_id)`,
  `INDEX idx_captions_status(tag_status)`.
- **`V20260721140000__creator_nudge_log.sql`** — `creator_nudge_log(id, creator_profile_id FK,
  theme_matched, suggestion_text TEXT, suggestion_source VARCHAR(16), shown_at, clicked_at NULL,
  created_at)`, `INDEX idx_cnl_creator_day(creator_profile_id, shown_at)`.

**Indexes matter for two hot paths:** `idx_cnl_creator_day` makes the per-day cap a range scan
(not table scan); `idx_captions_status` drives the tagger's "find PENDING" query.

**Config/env** (mirror the `brand-safety-scoring` yaml block):
```yaml
creator-copilot:
  enabled: ${CREATOR_COPILOT_ENABLED:false}          # OFF by default, like brand-safety
  model: ${CREATOR_COPILOT_MODEL:claude-haiku-4-5}   # proposal rules out Sonnet
  max-suggestions-per-creator-per-day: ${CREATOR_COPILOT_DAILY_CAP:1}
  theme-tag-batch-cron: ${CREATOR_COPILOT_TAG_CRON:0 0 3 * * *}
```

**Scheduler:** `CreatorThemeTaggingJob` in `com/influora/job/`, skeleton of `ScoreCalculationJob`
+ `@SchedulerLock(name="CreatorThemeTaggingJob", lockAtMostFor="PT30M")` on the existing
`shedlock` table (V68). In-process batch, **no n8n**. OFF by default.

**Verification before ship:** `npm install && npx tsc --noEmit && npm run build` (0 errors) ·
`mvn -q -o compile && mvn -q -o test` · `mvn flyway:info` shows the 3 new versions pending →
`flyway:migrate` local → `SHOW COLUMNS FROM creator_profiles LIKE 'theme_tags'` · `curl
localhost:8080/api/creator/suggestion` → 200, second call same day → cap-blocked (no dup row).

---

## 5. Security (owner: Kabir) — controls are mandatory, not advisory

| Sev | Threat | Required control |
|-----|--------|------------------|
| **P0** | **IDOR / token-ownership flip** — creator-owned token has no workspace tenant to lean on; cross-creator read of tokens/captions/suggestions | Authenticated creator's subject **IS** the ownership key. Resolve row → assert `row.creatorProfileId == principal.creatorProfileId` server-side. Never accept `creatorProfileId` from request body as authz. Add a `creator` principal type to `service_token.py ENDPOINT_SCOPES` so suggestion routes reject `service`/`chat:stream` tokens. Keep AES-256-GCM per-row. |
| **P0** | **Prompt injection via captions** — captions + tagging pass are attacker-controlled text into Haiku | Every caption/theme string passes `wrap_untrusted()` / `neutralize_angle_brackets()` before ANY prompt role (both tagging AND phrasing). Tagging output closed-vocab validated (`parse_and_validate`) so injected themes are stripped. Both layers — delimiters alone are bypassable. |
| **P0** | **OAuth CSRF / redirect** | Keep state store userId-bound/single-use/10-min TTL; `redirect_uri` stays server-config (never request-supplied); reject callbacks where `state.userId != session principal`. |
| **P1** | **Authz on dismiss/acted** (ownership-mutating) | Resolve-then-check on every route (not just GET); 403 if the row isn't the caller's. No global guard — each route self-enforces. |
| **P1** | **Spend / DoS** — bot hammering generation burns Haiku + brute-forces | The 1/creator/day cap is a **security** control — durable server-side counter (+ the DB constraint that closes Vikram's race), plus per-process rate-limit + global spend-gate. Fail-closed to fallback. |
| **P2** | **PII in logs** — captions, IG handles, audience data | Add `caption`, `captions`, `ig_handle`, `media` to `_REDACT_KEYS`; log `shape_of()` only. Encrypt captions at rest. |

---

## 6. QA & acceptance (owner: Kavya) — gate to SHIP

### Acceptance criteria
- **AC-1 IG link** — OAuth succeeds, token row persists with creator ownership, scope includes IG read, live `/me` call works.
- **AC-2 theme tag** — batch assigns ≥1 theme from the closed vocab; zero themes → silence.
- **AC-3 suggestion** — ONE Haiku suggestion/day; ≤2 statements, no pet-names, niche present, **no price/marketplace words** (creator-facing).
- **AC-4 per-day cap** — 2nd call same creator/day → no model call, spend tracker untouched, deterministic "already had today" response.
- **AC-5 fallback** — provider down / malformed / spend-gate → templated fallback, 200, `message_source=FALLBACK`.
- **AC-6 vernacular** — Hindi/Tamil captions must NOT break tagging; themes still map to the English closed vocab.

### Tests to write (mirror `influora-ai/tests/`)
- `tests/routes/test_creator_copilot.py` (clone `test_trendspark_nudge.py`): suggestion returned · provider-fail→fallback · malformed→fallback · per-day cap · spend-gate→no call · no-token 401 / wrong-scope 403 · invented-theme dropped.
- `tests/routes/test_creator_copilot_registration.py` — route registered on app (prevents silent unregister).
- `tests/security/test_creator_caption_injection.py` (**HIGH**) — "IGNORE PREVIOUS INSTRUCTIONS" caption → wrapped, no echo; `system:`/`assistant:` text → no leakage.
- `tests/eval/test_creator_copilot_edges.py` — zero posts · private/personal IG (403 graceful) · non-English captions · zero matches · zero themes.

### Guardrail tests that MUST exist (mirror `trendspark.py:60-77`)
closed-vocab drop · defensive JSON parse · ≤2 statements · no pet-names · no price echo · **no
marketplace words** (creator tone) · PII-free logs (`shape_of`).

### Eval set — `influora-ai/evals/creator_copilot.json`, seed 10 golden pairs (reuse `run_eval.py`):
cc-001 skincare+winter · cc-002 food+Mumbai · cc-003 zero-match · cc-004 Hindi→English themes ·
cc-005 provider-fail · cc-006 malformed · cc-007 injection · cc-008 2nd-call-same-day ·
cc-009 private IG · cc-010 zero posts.

### Blocks SHIP
AC-1…AC-5 green in CI · injection guardrail green · vernacular safety (AC-6) · per-day cap proven
(2nd call never hits Anthropic) · eval harness green offline · route-registration test · **⚠️ one
product decision needed: zero-posts / zero-themes UX = "silence" vs "post first" message** (Ash +
Tejas to align — blocks the test assertion, not the code).

---

## 7. AI design (owner: Ash)
- **Model:** Haiku 4.5 for phrasing (NOT Sonnet). Theme matching is deterministic Java, not a model call.
- **Prompt:** new creator-tone system prompt (warm, creator-facing; NOT the brand-facing Trend-Spark
  tone). Forbidden: marketplace words (`snapsby`/`buy`/`video`), price tokens, pet-names. Reuse the
  Trend-Spark validators verbatim (`_STATEMENT_RE`, `_PETNAME_RE`, `_PRICE_RE`) + a new marketplace-word reject.
- **Guardrails:** closed-vocab tagging, defensive JSON parse, deterministic fallback, spend gate,
  `<untrusted_>` wrap on captions, PII-free logs. All reused from `trendspark.py` / `trend_tag.py`.
- **Data flywheel (deferred build, but the ONE thing worth not cutting later):** the `dismissed_at`/
  `acted_at` columns exist from day one so we can start logging suggestion→action the moment Tier-2 opens.

---

## 8. Explicitly OUT of Tier-1 scope (do not gold-plate)
Campaign discovery (invert `show_creators`) · coaching / brand-safety-rationale surfacing ·
flywheel action-logging analytics · any creator-initiated spend · real-time per-post tagging ·
**vernacular (Hindi/Tamil/Telugu)** — English-only for the invite-only pilot; vernacular is a
**hard launch-blocker for public release** (CEO ruling), not a pilot requirement.

---

## 9. Effort & gate (owner: Priya)
| Workstream | Owner | Dev-days |
|---|---|---|
| Creator OAuth + token model | Vikram | 3 |
| Caption persist + theme-tag batch + migrations | Vikram/Meera | 4 |
| CreatorNudgeService + Haiku prompt + REST | Vikram/Ash | 3 |
| Creator UI | Ananya | 3 |
| Security audit | Kabir | 1.5 |
| QA | Kavya | 2 |
| **Total** | | **~16.5 dev-days** |

**Critical path:** creator OAuth/token → caption persist → theme-tag batch → theme store →
`CreatorNudgeService`. **Parallelizable off a frozen `API-CONTRACT.md`:** Ananya (UI on fixtures),
Meera (migrations), Ash (prompt file).

**GATE:** nothing starts until Priya certifies money-path stability. This spec is the plan to
execute the day that signoff lands — not permission to pre-start.
