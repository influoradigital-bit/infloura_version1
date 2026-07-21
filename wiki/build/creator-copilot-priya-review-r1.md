# Priya Review R1 — Creator AI Co-pilot Tier-1 (Stage-2 reconciliation ruling)

**Reviewer:** Priya (CTO) · **Date:** 2026-07-21 · **Status:** RULING — binding on all tracks
**Reviewed:**
- Parent spec: [`wiki/ai-review/creator-ai-copilot-tier1-build-spec.md`](../ai-review/creator-ai-copilot-tier1-build-spec.md)
- Ananya (FE components): [`creator-copilot-fe-components-plan.md`](creator-copilot-fe-components-plan.md)
- FE data-layer: [`creator-copilot-fe-datalayer-plan.md`](creator-copilot-fe-datalayer-plan.md)
- Vikram (BE services): [`creator-copilot-be-services-plan.md`](creator-copilot-be-services-plan.md)
- BE AI-route: [`creator-copilot-ai-route-plan.md`](creator-copilot-ai-route-plan.md)

**Scope of this ruling:** reconcile the 7 surfaced cross-team conflicts, assess Vikram's live
pre-existing bug (§0), give a verdict per track, and set the ordered pre-conditions for code.
The parent **money-path gate still governs** — this ruling defines *what* to build the day that
signoff lands; it is not itself the signoff to start.

---

## Conflict rulings (one decision each — canonical, not "discuss")

### 1. Endpoint path scheme → `/api/creator/copilot/*` is CANONICAL

Spec §2.5 (`/api/creator/suggestion`) is **superseded**. Both the FE data-layer client
(datalayer §2.2/§4.1) and Vikram's controller (BE §5, `@RequestMapping("/creator/copilot")`)
already independently converged on the §3.5 `/copilot/*` namespace, and it is the better scheme:
it groups the feature under one prefix instead of polluting bare `/creator/`.

**Canonical routes** (deployed prefix is `/api/v1` per `application.yml:71` + `src/lib/api.ts:48`,
which the FE `API_BASE_URL` already includes — so FE code uses the `/creator/copilot/...` suffix):
```
GET  /api/v1/creator/copilot/suggestion/today
POST /api/v1/creator/copilot/suggestion/{id}/dismiss
POST /api/v1/creator/copilot/suggestion/{id}/acted
```
**Action:** whoever edits the spec strikes §2.5's `/api/creator/suggestion` paths and replaces
them with the above. FE data-layer §2.2 is already correct. This kills the FE↔BE two-URL problem
(datalayer §4.1) — the single biggest parallelization risk.

### 2. `creator_nudge_log` column list → Vikram's §2.1 shape (WITH `prompt_version`) is CANONICAL

Invariant #5 (spec §1) is non-negotiable: `PROMPT_VERSION` on every row. Meera's §4 migration
sketch omits it and collapses the columns (`theme_matched`/`suggestion_text`/`suggestion_source`/
`clicked_at`). **Rejected.** Canonical columns (mirror the shipped `NudgeLog` 1:1 → lower cognitive
load, one naming convention across both nudge-log tables):

```
id, creator_profile_id, trend_id, match_score, message, message_source,
prompt_version, shown_at, dismissed_at, acted_at, created_at
```
Keep `trend_id` + `match_score` (not a single `theme_matched`) — richer audit trail and it matches
`NudgeLog`. Keep `dismissed_at` + `acted_at` from day one (§7 flywheel).
**Action:** Meera builds `V20260721140000__creator_nudge_log.sql` against **this** list, not her §4
sketch. Vikram's entity (BE §2.3) is canonical.

### 3. IG connect route → REUSE the existing OAuth flow. NO new route.

Spec §2.5's `POST /api/creator/ig/connect` is **struck**. FE (components §3.2, "Do NOT fork the
OAuth logic") and Vikram (BE §3 reconciliation) both correctly refuse to build a redundant route.
Ownership is flipped **server-side inside the existing** `/meta/oauth/callback` (see the live-bug
fix below). FE keeps calling `api.metaOAuth.authorize()` → full-page redirect → existing callback.
This is Option A from FE data-layer §2.4. Zero new client surface, zero new controller route for
connect. (Status/disconnect surface is a separate item — see track verdicts.)

### 4. `onConnected` across a full-page OAuth redirect → callback-page writes connectionState; hook re-reads on remount. DROP the callback prop as the mechanism.

A JS callback **cannot survive a full-page redirect** — the component's JS context is destroyed on
navigation (Ananya correctly flags this, components §2.2). Do not try to make `onConnected` fire
across it. The correct architecture, already proposed by the FE data-layer agent (§6) and hereby
ADOPTED:

1. The OAuth callback page (`/creator/settings/meta/callback`, `src/lib/api.ts:2868`) calls
   `api.metaOAuth.setLocalConnectionState({ connected, accountType })` — the **existing** mechanism
   (`src/lib/api.ts:2889-2891`) — then `navigate()`s back to the dashboard.
2. On that remount, `useDailySuggestion`'s `enabled: isConnected` gate (datalayer §1.2) re-evaluates
   from `getLocalConnectionState()` and fetches. No cross-boundary callback needed.

**Consequences (binding):**
- `ConnectedAccounts.onConnected` (components §2.2) is **not required** for the co-pilot card. Do not
  add it for this feature. If Ananya wants it for other (non-remount) surfaces, it fires from the
  callback page reading connectionState — never from inside `handleConnect`.
- **`NO_BUSINESS_ACCOUNT` arrives as a response FIELD, not an HTTP error** (answers datalayer §5.4 /
  components' open Q). The callback returns `accountType: 'personal'` (or an explicit
  `NO_BUSINESS_ACCOUNT` code) in `MetaCallbackResponse`; the callback page writes it into
  connectionState. The hook collapses it to `requiresBusinessAccount: true` while `status` stays
  `'idle'`. It is a UX branch, never a toast/error. Vikram's `CreatorMetaOAuthService` already
  resolves this via `FacebookPageClient.resolveConnectedInstagram` (BE §3 item 8) — that resolution
  result MUST be carried on the callback response.

### 5. LLM caption-tagging recovery pass → OUT of Tier-1. Deterministic Java tagging only.

Spec §1 says tagging is Java-only/deterministic; §9's effort table has **no line item** for a second
AI route; §8 says "do not gold-plate." The AI-route plan's §3 recovery pass (forked from
`trend_tag.py`) is **DEFERRED to Tier-2**. Do not build `creator_caption_tag.py` (route or prompt)
in Tier-1. Tagging = `ThemeMatchService.themesForText` (pure Java keyword match), full stop.

**Addendum — this also resolves the caption-in-phrasing divergence (Vikram §4 vs AI-route §1.1/§2.2):**
The phrasing call in Tier-1 receives **`theme` (our closed vocab) + `trend_text` (our `trends`
table) ONLY. NO `caption_snippet`.** Drop `caption_snippet` from the request contract, drop its
`wrap_untrusted` call, drop the caption-injection concern from the phrasing route. Rationale: this
removes **all** creator-controlled free text from the **only** LLM call in Tier-1. Combined with
deterministic tagging, **no creator caption text reaches any model in Tier-1 at all.**

**Therefore the spec §5 P0 "prompt injection via captions" row must be reworded** (action for the
spec editor): *"In Tier-1 no creator caption text reaches any model — tagging is deterministic Java;
phrasing receives only server-owned `theme` + `trend_text`. `trend_text` (scraped/third-party) stays
`wrap_untrusted`. The caption-injection control activates in Tier-2 when the LLM recovery tagger
and/or caption-enriched phrasing land."* `wrap_untrusted` on `trend_text` **stays** — that field is
still untrusted. This narrows the P0 surface honestly instead of leaving a control with no code path
(which is what Vikram §4 correctly worried about).

### 6. Zero-posts / zero-themes copy ("silence" vs "post first") → PRODUCT DECISION, routed to Ash + Tejas. NOT mine.

Per Kavya (spec §6) this is a product call, and I am not overriding it. **Routed to Ash + Tejas.**
To keep the build unblocked: mechanically both teams treat zero-themes/zero-match as
`no_suggestion_today` → a **silent, inert card** (datalayer §1.3, components §1.4) — this is the safe
default that needs no new state and no 6th enum value. Code proceeds with **placeholder copy**; the
final copy is a one-line swap gated on the Ash+Tejas ruling, and it blocks Kavya's test *assertion*,
not the code. No 6th `status` value is introduced regardless of which way copy lands (see verdicts).

### 7. Validator reuse (5 regex validators) → EXTRACT to a shared module, via an extract-first PR. Do NOT duplicate.

The AI-route plan recommends duplicating `_STATEMENT_RE`/`_PETNAME_RE`/`_PRICE_RE`/`_CODE_FENCE_RE`/
`_LOVE_VOCATIVE_RE` verbatim into the new route (§2.3). **Rejected as a permanent state.** These are
**security controls**. Duplicated security validators are precisely the cross-file drift I exist to
prevent: a future petname/price-bypass fix applied to one copy silently misses the other. Security
controls get **one source of truth.**

The "zero risk to shipped trendspark" argument is real but is solved by **sequencing, not by
accepting duplication**:
1. **Extract-first PR (separate, lands BEFORE the creator AI route):** move the 5 regexes +
   `_has_forbidden_petname` + `_statement_count` into `app/prompt/validators.py` (or
   `app/routes/_validators.py`); refactor `trendspark.py` to import them; **Kavya re-runs the full
   trendspark test suite green** on that PR alone.
2. The creator route then imports the same module from day one.

`FORBIDDEN_PETNAMES` import (AI-route §2) is already fine — same principle, do that too. The new
`_MARKETPLACE_RE` is creator-specific → it lives in the creator route/prompt, not the shared module.

---

## Live pre-existing bug assessment (Vikram BE §0) — VERIFIED, real, contained P0

**Verified against the tree (not just the plan):**
- `web/MetaOAuthController.java:66-103` `/meta/oauth/callback` is creator-only (`requireCreator`,
  line 71), resolves `CreatorProfile` (78-86), and calls
  `tokenStorage.storeToken(creatorProfile.getId(), principal.getWorkspaceId(), ...)` (95-100).
- A CREATOR `AuthPrincipal` has a **null** `workspaceId` — codebase's own comment,
  `security/AnalyticsUsageCapInterceptor.java:42` ("a CREATOR principal has no `workspaceId`").
- `domain/entity/MetaOAuthToken.java:26` = `@Column(name = "workspace_id", nullable = false, ...)`.
- `db/migration/V20__meta_oauth_tokens.sql:7` = `workspace_id VARCHAR(26) NOT NULL`, **plus** an FK
  to `workspaces(id)` (line 19) and a `UNIQUE(workspace_id, creator_profile_id)` (line 16).

**Is it real?** Yes. Every creator who completes `/meta/oauth/callback` passes `null` into a
`NOT NULL` column → `DataIntegrityViolationException` on insert. This path **cannot ever have
succeeded** for a creator. Confirmed independently of the plan.

**Is it P0?** P0 in *severity on the affected path* (the creator IG-connect flow is 100% broken and
that flow is the literal entry point of this whole feature), but **contained**: it fails closed on a
single-statement insert — no partial write, no corruption, no money path, no cross-tenant leak. Blast
radius = however many creators have actually attempted IG-connect in prod to date, which is unknown.

**Does the OAuth-flip fix it?** Yes, fully. `ALTER TABLE meta_oauth_tokens MODIFY COLUMN workspace_id
VARCHAR(26) NULL` (BE §3.1) resolves it — and note MySQL exempts NULL from FK checks, so the existing
`fk_meta_oauth_workspace` stays satisfiable with a null workspace, and multiple NULLs don't collide
on the unique key (BE §3.1 is correct on both). The rest of the flip (entity `nullable`, repository
overload, `storeCreatorToken`, controller fix to stop passing `principal.getWorkspaceId()`) completes
it. `MetaConnectionService`'s `WHERE workspace_id = NULL` never-matches bug (BE §0) is fixed by the
same flip (drop the `workspaceId` param, use the new creator-keyed repository method).

**Directive:**
1. **Meera runs a READ-ONLY prod check BEFORE the OAuth-flip migration is written** (BE §6.3):
   count `meta_oauth_tokens` rows with `creator_profile_id` set, and whether any creator shows
   "connected." This sizes the blast radius and tells us whether any cleanup/backfill is needed
   (likely none, since failed inserts leave no rows — but confirm, don't assume). Read-only. No
   writes to prod.
2. The fix ships **as part of the Tier-1 OAuth-flip work** (it is the same code change), but it is
   **logged as its own bug finding** so it isn't buried if Tier-1 slips — reference this ruling. It
   is not a separate parallel workstream; it *is* the flip.
3. Because inserts failed cleanly (no rows), there is no data-migration/backfill obligation. Meera's
   check confirms this; if any anomalous rows exist, escalate before migrating.

---

## Verdict per track

| Track | Verdict | Gating conditions |
|---|---|---|
| **FE-components (Ananya)** | ✅ **GREENLIGHT** | with the 4 bindings below |
| **FE-data-layer** | ✅ **GREENLIGHT** | with the 3 bindings below |
| **BE-services (Vikram)** | ✅ **GREENLIGHT** | with the 5 bindings below + live-bug directive |
| **BE-AI (Sonnet #2)** | ⚠️ **CHANGES-REQUESTED** | scope must shrink before code (below) |

### FE-components (Ananya) — GREENLIGHT
Strong plan; the self-corrections (mount site is the dashboard page, not the `creator-layout` shell)
are correct. Bindings:
1. **`DailySuggestionSection.tsx` orchestrator (§1.6): APPROVED.** Keep the single `switch(status)`
   site out of the layout shell.
2. **Nav entry (§2.1 / §6.3): DROP it.** Tier-1 co-pilot is a **dashboard-mounted card, not a new
   route/page** (spec §3.2 "mount card atop the creator dashboard"). No `/creator/copilot` route, no
   `navItems` diff. The Sparkles icon lives on the card header only.
3. **`onConnected` prop: DROP** per Conflict 4 — the hook re-reads connectionState on remount.
4. **Shared types location: `src/lib/api.ts`** (see FE-data-layer binding 2) — import
   `DailySuggestion`/`SuggestionStatus` from `@/lib/api`. Reject the new `src/types/creator-copilot.ts`.

### FE-data-layer — GREENLIGHT
1. **Paths: §3.5 canonical** (Conflict 1). Client already correct.
2. **Types live inline in `src/lib/api.ts`; namespace is FLAT `api.creatorCopilot.*`** (not nested
   `api.creator.copilot.*`). Matches every existing resource (`trendspark`, `metaOAuth`) and
   `useTrendSparkNudge.ts:18`'s import precedent. This is the answer to components §6.5.
3. **`acted` collapses into the `'dismissed'` bucket — NO 6th `status` value** (datalayer §5.2). Keep
   the 5-state enum frozen; retain the interaction reason internally
   (`CreatorSuggestionInteraction`) if Ananya wants different collapsed copy. Day-rollover: **no
   `setInterval` watcher** in Tier-1 (datalayer §5.7) — remount refresh is fine for a once-a-day card.
   `GET .../today` need not filter dismissed rows in Tier-1; the `sessionStorage` marker
   (datalayer §1.3) is an acceptable same-day hide for the pilot (durable truth is
   `creator_nudge_log.dismissed_at`).

### BE-services (Vikram) — GREENLIGHT
1. **`creator_nudge_log` columns: your §2.1 shape is canonical** (Conflict 2).
2. **No `ig/connect` route** (Conflict 3); complete the OAuth flip inside the existing callback.
3. **AI double-spend under the race (§6.6): DB constraint only. Do NOT add a distributed lock in
   Tier-1.** The generated-column unique key (`uq_creator_nudge_day`) closes the double-*write* for
   certain — ship it. The residual double-*spend* is bounded to at most one extra Haiku call under a
   genuine concurrent first-of-day race, self-heals (only one row shows), and is a rounding-error
   cost. An advisory lock adds its own failure mode (acquire-timeout + fallback) for negligible
   benefit. Revisit in Tier-2 only if metrics show abuse.
4. **`CreatorCaptionSyncJob` split (§6.9): APPROVED.** Two single-responsibility jobs
   (sync-from-Meta, tag-from-cache). Do not overload one job.
5. **`MetaConnectionService` needs a controller (§6.8): YES, in Tier-1 scope.** The FE needs a real
   connection-status + `accountType` read to seed `connectionState` (the localStorage mirror must be
   fed by a genuine callback/status response, not invented). Wire a minimal
   `GET /creator/copilot/ig/status` (connected + accountType) and the disconnect path, and fix the
   `WHERE workspace_id = NULL` bug there as part of the flip. `accountType`/`NO_BUSINESS_ACCOUNT` must
   also ride the callback response (Conflict 4).

Confirmed defaults (stop asking, these are ruled): suggestion AI response = **2 fields
`{headline, contentIdea}`** (matches FE §3.5); service token = **new `creator` scope** (not reused
`service` scope — preserves Kabir's bidirectional scope segregation); `expiresAt` = **end of the
creator's current UTC day** (ties to the cap window).

### BE-AI (Sonnet #2) — CHANGES-REQUESTED
Thorough and largely correct, but Tier-1 scope must shrink before code. Required changes:
1. **Remove the caption recovery-tag route + prompt from Tier-1** (`creator_caption_tag.py` route and
   prompt) — deferred to Tier-2 (Conflict 5).
2. **Drop `caption_snippet` from the phrasing request contract**, drop its `wrap_untrusted` call and
   the caption-injection concern from the phrasing route (Conflict 5 addendum). Keep
   `wrap_untrusted` on `trend_text`.
3. **Validators: extract-first, not duplicate** (Conflict 7) — the creator route imports
   `app/prompt/validators.py`; do not copy the 5 regexes.
Approved as designed (no change): new `SCOPE_CREATOR` + `verify_creator_token` (§4.1); reuse the
single global `PROMPT_VERSION` stamped Java-side into `creator_nudge_log` (do **not** create a
separate `CREATOR_PROMPT_VERSION` — the per-row column already gives audit granularity); log
`creator_profile_id` in the clear (it is an internal id, the tracing correlation key), redact only
`caption*`/`ig_handle` text.

Re-review is fast: once the two routes become one and the phrasing contract drops the caption, this
flips to GREENLIGHT.

---

## R2 micro-ruling (2026-07-21) — DTO ↔ entity inconsistency + config-bind fix

**Source:** Meera verification ([`creator-copilot-meera-verify.md`](creator-copilot-meera-verify.md)) found a P0
that survived R1: my R1 **Conflict 2** ruling made `creator_nudge_log` mirror `NudgeLog` 1:1 — a
single `message` TEXT column, no `theme` — but the frozen API-CONTRACT.md `DailySuggestion` DTO is
`{ id, theme, headline, contentIdea, expiresAt }`, three distinct display fields. On the idempotent
same-day re-read path, `toDto()` reads the persisted row and **cannot** split one `message` column
back into `theme`/`headline`/`contentIdea`. Meera is correct; this is a real gap I introduced.

### Ruling: **Option (b) — ADD columns to `creator_nudge_log`. The frozen contract wins; the entity catches up.**

Option (a) (shrink the DTO+card to one `message` field) is **rejected**: it breaks a *frozen* v1
contract, forces FE rework on `DailySuggestionCard`, and throws away the structured output the AI
route already produces. Option (b) contains the fix entirely to BE entity+migration.

My R1 "mirror `NudgeLog` 1:1 for low cognitive load" rationale is **overridden here**: the DTO is the
requirement, and the persisted row must be able to reconstruct it losslessly. The rest of Conflict 2
stands (keep `trend_id`, `match_score`, `message_source`, `prompt_version`, `dismissed_at`,
`acted_at`) — only the single `message` column is replaced by three typed columns.

**Canonical `creator_nudge_log` column list (supersedes R1 Conflict 2's `message` column):**
```
id, creator_profile_id, trend_id, match_score,
theme, headline, content_idea,        -- REPLACES the single `message` column
message_source, prompt_version,
shown_at, dismissed_at, acted_at, created_at
```
- **`theme`** — sourced from `theme_matched` (the closed-vocab theme `ThemeMatchService.score`
  already picks), stamped **server-side at write time. NOT AI output.**
- **`headline`** + **`content_idea`** — the AI phrasing route's **structured output**, which it
  **already returns** post-R1: `{ headline, content_idea, message_source }`
  ([AI-route §1.1](creator-copilot-ai-route-plan.md)). The templated fallback already returns a
  `(headline, content_idea)` tuple (`fallback_message`), so both the AI and FALLBACK paths populate
  these two columns identically. No new AI-return shape to define — it exists.
- Length caps: `headline` ≤ `creator_copilot_max_headline_chars` (120), `content_idea` ≤
  `creator_copilot_max_content_idea_chars` (300) — already in AI-route §5.2.

`toDto()` becomes a pure column read → `DailySuggestion{ id, theme, headline, contentIdea, expiresAt }`
where **`id`** = row id, **`theme`/`headline`/`contentIdea`** = the three columns, and **`expiresAt`**
= end of the creator's current UTC day **computed from `shown_at`** (R1 confirmed default) — no
`expires_at` column needed. All five DTO fields reconstruct on the same-day re-read. P0 closed.

### Re-freeze question: **NO. API-CONTRACT.md stays v1 — no re-freeze.**

Option (b) leaves **every** contract field byte-identical (`{id, theme, headline, contentIdea,
expiresAt}`). The change is confined to: (1) `CreatorNudgeLog` entity, (2) the
`V20260721140000__creator_nudge_log.sql` migration column list, (3) `toDto()` mapping. FE is
untouched; the AI-return shape is unchanged (it already emits the two structured fields). No v2.
(This is the decisive advantage of (b) over (a): (a) would have forced a v2 re-freeze + FE rework.)

### P2 folded in: `CreatorCopilotProperties` config binding

`dailyCap` will **not** bind to the yaml key `max-suggestions-per-creator-per-day` — Spring's relaxed
binding maps `daily-cap`↔`dailyCap`, not the descriptive key. **Ruling: rename the Java field to
`maxSuggestionsPerCreatorPerDay`** (binds to `max-suggestions-per-creator-per-day` via relaxed
binding), and **keep the yaml key + env var `CREATOR_COPILOT_DAILY_CAP` unchanged** — those are
already documented in spec §4 and Meera's config block, and the field is documentation-only (the DB
unique constraint is what actually enforces the cap, per BE §2.5), so a longer field name costs
nothing and avoids editing the spec's yaml block. Update `CreatorCopilotProperties.java` (BE §2.5)
accordingly.

### Routing
- **Vikram:** entity (`CreatorNudgeLog` — 3 columns replace `message`) + `toDto()` + the field rename
  in `CreatorCopilotProperties`. Confirm `CreatorSuggestionAiClient`/`CreatorNudgeService` persist
  `theme` from `theme_matched` (server), `headline`/`content_idea` from the AI/fallback tuple.
- **Meera:** rebuild `V20260721140000__creator_nudge_log.sql` to the column list above (still incl.
  `prompt_version`, invariant #5). Then re-verify.
- **AI-return owner:** no change — the 2-field structured output already matches. Confirm only.
- **Contract owner:** no re-freeze; API-CONTRACT.md v1 stands.

Verdict unchanged for all 4 tracks (still GREEN); this is a bounded BE entity/migration correction,
not a re-open.

---

## Ordered pre-conditions before ANY code starts

1. **Money-path gate holds** — the parent gate (spec header) is unchanged and still governs. Nothing
   below starts until that signoff lands.
2. **`API-CONTRACT.md` frozen** with: canonical `/api/v1/creator/copilot/*` paths (Conflict 1);
   2-field `{headline, contentIdea}` suggestion; `accountType`/`NO_BUSINESS_ACCOUNT` carried on the
   Meta callback response (Conflict 4). Vikram drafts, Priya blesses. **This unblocks all four tracks
   in parallel.**
3. **Meera's read-only prod check** on `meta_oauth_tokens` (live-bug directive #1) — reported before
   the OAuth-flip migration is written.
4. **Extract-first validators PR** merged (Conflict 7): `app/prompt/validators.py` + trendspark
   refactor, Kavya's trendspark suite green — **before** the creator AI route is built.
5. **`creator_nudge_log` migration built to Vikram §2.1 columns incl. `prompt_version`** (Conflict 2)
   — Meera confirms she is building the canonical list, not her §4 sketch.
6. **Spring↔influora-ai token contract agreed**: `creator` scope + `creator_profile_id` claim
   (AI-route §4.1, Vikram §6.4) — before any E2E round-trip test.
7. **Ash + Tejas rule on zero-state copy** (Conflict 6) — NON-blocking for code (placeholder copy
   proceeds); blocks the final copy swap and Kavya's zero-state test assertion only.

Items 2-6 are the hard technical gates. Item 1 governs all. Item 7 runs in parallel and blocks only
the final copy + one test assertion, not the build.
