# Priya Code Review — Creator AI Co-pilot Tier-1 (post-build, against frozen plans + contract)

**Reviewer:** Priya (CTO) · **Date:** 2026-07-21 · **Status:** RULING — binding on all tracks
**Reviewed against:** [`creator-copilot-API-CONTRACT.md`](creator-copilot-API-CONTRACT.md) (frozen v1) +
[`creator-copilot-priya-review-r1.md`](creator-copilot-priya-review-r1.md) (R1 + R2 rulings).
**Method:** read the actual shipped code (not the plans) across all four tracks; spot-checked every
load-bearing file. This is a **static contract proof**, not a live E2E — no running round-trip was
executed (provider/Meta keys unprovisioned, per prior state), so the live handshake remains
unverified; the byte-identical wire match below is confirmed by direct inspection of both sides.

---

## Per-track verdict

| Track | Verdict | Why |
|---|---|---|
| **FE data-layer** (`useDailySuggestion.ts`, `api.ts` creatorCopilot + `MetaConnectionState.accountType`) | ✅ **GREENLIGHT** | Wire correct, status derivation correct, connection-state read correct. Zero changes. |
| **FE components** (`components/creator/copilot/*`, `creator-deals.tsx`) | ⚠️ **CHANGES-REQUESTED** | One binding violation: a rejected second-source-of-truth types file. Single mechanical fix; everything else green. |
| **BE Java** (controller, DTOs, `CreatorNudgeService`, `CreatorSuggestionAiClient`, F-5 minter, F-1 storage, OAuth flip, entity, migrations) | ✅ **GREENLIGHT** | Core logic, all invariants, both wires correct. 2 minor cleanups (non-blocking). |
| **BE Python** (`creator_suggestion.py`, prompt, `validators.py`, `service_token.py`, `trendspark.py` import) | ✅ **GREENLIGHT** | Wire + security + extract-first all correct. 2 fixes: 1 trivial (claim fallback), 1 tuning (marketplace regex) before the pilot exercises the AI path. |

---

## 1. Public FE↔BE wire — PROVEN byte-identical

| Element | FE (`src/lib/api.ts`) | BE (`CreatorCopilotController` + `CreatorCopilotDtos`) | Match |
|---|---|---|---|
| Base path | `API_BASE_URL` includes `/api/v1`; client passes `/creator/copilot/...` | `@RequestMapping("/creator/copilot")` + `context-path: /api/v1` | ✅ `/api/v1/creator/copilot/*` |
| GET today | `GET /creator/copilot/suggestion/today` `{role:'creator'}` (api.ts:3352) | `@GetMapping("/suggestion/today")` (controller:42) | ✅ |
| Dismiss | `POST /creator/copilot/suggestion/${id}/dismiss` (api.ts:3358) | `@PostMapping("/suggestion/{id}/dismiss")` (controller:54) | ✅ |
| Acted | `POST /creator/copilot/suggestion/${id}/acted` (api.ts:3364) | `@PostMapping("/suggestion/{id}/acted")` (controller:63) | ✅ |
| `DailySuggestion` | `{id, theme, headline, contentIdea, expiresAt}` (api.ts:3315) | `record SuggestionDto(String id, theme, headline, contentIdea, expiresAt)` plain camelCase (Dtos:16) | ✅ 5/5 fields byte-identical |
| Response | `{suggestion: DailySuggestion\|null, status}` (api.ts:3327) | `record SuggestionTodayResponse(SuggestionDto suggestion, String status)` (Dtos:22) | ✅ |
| `status` literals | `'pending_tagging'\|'ready'\|'no_suggestion_today'` (api.ts:3325) | `SuggestionResult` factories emit exactly those strings (service:67-79) | ✅ |
| Envelope | `http.request<T>` unwraps `{success,data}` | `ApiResponse.ok(...)` on every route | ✅ |
| dismiss/acted body | `http.request<void>` → `undefined` | `ApiResponse.ok(null)` → `{success:true,data:null}` | ✅ |
| Error code | generic `ApiError` | `SUGGESTION_NOT_FOUND` 404 (service:198), IDOR-safe (same 404 not-yours/not-found) | ✅ |

FE consumer is fully mounted: `creator-deals.tsx:417` renders `<DailySuggestionSection>` →
`useDailySuggestion()` → `api.creatorCopilot.*` → the frozen paths. **Connection proven end to end
by inspection.**

## 2. Internal Java→Python wire — PROVEN byte-identical

| Element | Java (`CreatorSuggestionAiClient` + `CreatorSuggestionAiDtos`) | Python (`creator_suggestion.py`) | Match |
|---|---|---|---|
| Request fields | `SuggestionRequest` `@JsonProperty("creator_profile_id"/"theme_matched"/"trend_text")` (Dtos:27-30) | `body.get("creator_profile_id"/"theme_matched"/"trend_text")` (route:190,214-215) | ✅ snake_case, exact |
| Response | reads `success`, `data.headline`, `data.content_idea` (`@JsonProperty`), ignores `message_source` (Dtos:33-35) | returns `{success, data:{headline, content_idea, message_source}}` (route:321) | ✅ |
| Auth scope | mints `scope="creator"` (`CreatorSuggestionServiceTokenService:74`) | `ENDPOINT_SCOPES["creator_suggestion"] = (SCOPE_CREATOR,)` (service_token:65) | ✅ |
| Tenant claim | mints `claim("creator_profile_id", ...)` (minter:73) | `verify_creator_token` reads `creator_profile_id` (service_token:345), asserts == body | ✅ |

### RESOLVED — canonical claim spelling (VERIFY item 2)
Java definitively mints **snake_case `creator_profile_id`** (matches the JSON body field and the wire
convention). Python currently accepts **both** `creator_profile_id` OR `creatorProfileId`
(`service_token.py:345`) "until Spring confirms." Spring has confirmed — it is snake_case.
**Ruling: canonical = `creator_profile_id`.** The camelCase fallback is now dead code and must be
removed so the contract is unambiguous → **Required fix #2** below. (Leave `verify_token`'s
workspace dual-spelling untouched — that is shipped, money-adjacent, out of scope.)

## 3. Invariants — ALL PASS

| Invariant | Evidence | Status |
|---|---|---|
| Per-creator/day cap = DB constraint (`uq_creator_nudge_day`) | Migration: `shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED` + `UNIQUE KEY (creator_profile_id, shown_day)` (migration:48-50). Enforced by idempotent-read-first (service:98-103) + `DataIntegrityViolationException` catch returning the race winner's row (service:162-170). No distributed lock (R1 binding #3 honored). | ✅ |
| Only `trend_text` wrapped untrusted; no caption anywhere | `build_user_message` wraps only `trend_text` via `wrap_untrusted`; `theme_matched` closed-vocab-validated + `neutralize_angle_brackets` (prompt:110-113). No `caption_snippet` in any request/prompt/DTO (Dtos javadoc:11-15; route:23-28). | ✅ |
| `PROMPT_VERSION` stamped on every `creator_nudge_log` row | Entity `prompt_version NOT NULL` (entity:55); service sets `.promptVersion(props.getPromptVersion())` (service:157); migration column present (migration:26). | ✅ |
| F-1 revoke-before-insert | `storeCreatorToken` revokes any existing non-revoked null-workspace row **first**, in-txn, before building the new row (`MetaTokenStorage:191-197`). Closes the multi-NULL uniqueness gap at the app layer. | ✅ |
| F-5 minter mints `scope='creator'` not `'service'` | `SCOPE_CREATOR="creator"`, `.claim("scope", SCOPE_CREATOR)` (minter:44,74). Bidirectional segregation intact. | ✅ |
| `toDto()` pure column read | `new SuggestionDto(row.getId(), getTheme(), getHeadline(), getContentIdea(), expiresAt(getShownAt()))` — no reconstruction, `expiresAt` computed from `shownAt` (service:252-267). R2 Option-b closed the split-gap. | ✅ |
| resolve-then-check authz on all 3 routes | GET resolves `requireCreatorProfile(principal)` then `getSuggestion(profile.getId())`; dismiss/acted resolve profile then `requireOwnedSuggestion` → `findByIdAndCreatorProfileId` (controller:45,57,66; service:192-199). Identity never trusted from path/body. | ✅ |

## 4. Extract-first refactor (VERIFY item 4) — VALIDATED behavior-preserving

`app/prompt/validators.py` holds the 5 regexes + `_has_forbidden_petname` + `_statement_count`,
byte-for-byte from trendspark's former inline copies. `trendspark.py` now **imports**
`_CODE_FENCE_RE, _has_forbidden_petname, _PRICE_RE, _statement_count` from that module (trendspark:50-55)
with **no local re-definitions remaining** (grep confirms usages only); it keeps its own
route-specific `_OWN_CONTENT_FORBIDDEN_RE` local. `creator_suggestion.py` imports the same module
from day one (route:90-95); its creator-specific `_MARKETPLACE_RE` stays local (route:109), per R1
Conflict 7. Python track reports **29/29 trendspark tests green**. The change is structurally a pure
symbol move → behavior-preserving by inspection, corroborated by the passing suite.

---

## Resolved open items

### A. Validators PR sequencing (one-PR vs split-first) — **one-PR ACCEPTED**
R1 required extract-first as a *separate* PR to isolate risk. The build did it in one pass. The
sequencing was a *means* (prove the trendspark refactor safe in isolation) to an *end* (a
behavior-preserving extraction). The end is achieved: the extraction is clean (no leaked local
defs), verified byte-preserving by inspection, and the trendspark suite is green. The risk the
sequencing existed to retire is already retired. **Accept one-PR — do not force a re-split.**
Merge condition: the trendspark suite must be a **blocking CI gate** on the combined change; a
belt-and-braces reviewer may keep the validators-extract as its own *commit* within the PR, but no
separate PR is required.

### B. `MetaConnectionService` §6.8 (getStatus/disconnect, workspaceId-scoped, no controller) — **OUT of Tier-1**
My R1 binding #5 required wiring a `GET /creator/copilot/ig/status` + disconnect. The as-built
architecture **supersedes** that, correctly: the OAuth **callback now carries `connected` +
`accountType`** (`CreatorMetaOAuthService.connect` → `ConnectResult` → `MetaCallbackResponse`,
controller:98-101), which is the exact thing my R1 said the FE needed to seed its localStorage
mirror ("must be fed by a genuine callback/status response, not invented"). The hook reads that
mirror on every render (`useDailySuggestion:115`). So the status endpoint's original justification
is met by the callback, at zero extra round-trips.

`MetaConnectionService.getStatus/disconnect` are **dead on the creator path** and, critically, still
**workspace-scoped** (`findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, ...)`,
service:53,109) — a creator's `workspaceId` is null, and a derived `...WorkspaceId(null)` query emits
`WHERE workspace_id = ?`/null which **never matches** a `workspace_id IS NULL` row. Wiring it as-is
would resurrect exactly the `WHERE workspace_id = NULL` bug R1 §0 flagged. **Ruling: leave it
unwired (inert), OUT of Tier-1.** Tech-debt logged: if a creator ig-status/disconnect is ever built,
it MUST use the `...CreatorProfileIdAndWorkspaceIdIsNull...` creator repository methods (like
`MetaTokenStorage`'s creator overloads), never the workspace-scoped ones. Tier-1 has no disconnect
user story on the card, and a cleared-localStorage creator simply re-runs OAuth, which is idempotent
(`storeCreatorToken` revokes-then-inserts) — acceptable pilot degradation.

### C. Vikram's F-2 risk-accept (plaintext IG captions) — **ACCEPTED for the pilot**
Captions are the creator's **own public Instagram content**, not bearer secrets — unlike the OAuth
access token, which is correctly AES-256-GCM encrypted (`MetaTokenStorage`). Encrypting public post
text is gold-plating with no threat model. **Accept, with three standing caveats** (all already
satisfied by the current design): (1) caption reads stay creator-scoped (same IDOR discipline as the
nudge log); (2) captions must **never** enter an LLM prompt in Tier-1 — guaranteed by R1 Conflict 5
(deterministic Java tagging only, phrasing gets `theme`+`trend_text` only); (3) revisit if Tier-2's
caption-enriched phrasing lands. Note: the caption-cache/sync surface was outside the files in this
review's scope — this is a policy ruling, not a code finding on those files.

---

## Required fixes (route via Arjun)

1. **[FE components — CHANGES-REQUESTED, blocks merge, not QA]** Delete `src/types/creator-copilot.ts`.
   It re-declares `DailySuggestion` — a **frozen wire type** — as a second source of truth, which R1
   FE-components binding #4 explicitly rejected ("Reject the new `src/types/creator-copilot.ts`; import
   from `@/lib/api`"). `DailySuggestionCard.tsx:9` imports both types from it. Structural typing keeps
   the build green today, which is *precisely* the silent-drift trap the ruling forbids: a future v2
   field on `api.ts`'s `DailySuggestion` would never reach the card.
   **Fix:** `DailySuggestionCard.tsx:9` → `import type { DailySuggestion } from '@/lib/api';` +
   `import type { SuggestionStatus } from '@/hooks/useDailySuggestion';`, then delete
   `src/types/creator-copilot.ts`. (The hook already owns+exports `SuggestionStatus`; api.ts owns
   `DailySuggestion`.)

2. **[BE Python — trivial]** `service_token.py:345` — drop `or claims.get("creatorProfileId")`. Canonical
   claim is snake_case `creator_profile_id` (Java minter confirmed). Removes the ambiguity flagged in
   the code's own comment.

3. **[BE Python — tuning, required before the pilot exercises the AI path]** `creator_suggestion.py:109`
   `_MARKETPLACE_RE = \bsnapsby\b|\bbuy\b|\bvideos?\b` rejects any model output containing "video(s)"
   or "buy" — **unconditionally**. But a creator content idea is a *reel/post idea*; the model will
   very frequently say "video"/"a quick video"/"BTS video" — every such (perfectly good) output trips
   validation → forced templated fallback. This silently defeats the purpose of the one AI call
   (near-always-fallback). Correctness is safe (fallback never breaks), so this is not a merge blocker,
   but it must be tuned before the pilot so the AI path is actually usable. **Route to Ash** for the
   exact list; recommend dropping `videos?` (and reconsidering `buy`), keeping `snapsby`. Keep the
   system-prompt instruction as the primary control.

4. **[BE Java — minor consistency]** `CreatorNudgeLog.java:45` `@Column(length = 300)` vs migration
   `headline VARCHAR(255)` (migration:23). Align entity to `255`. No runtime impact (Python caps
   headline at 120), but schema-of-record and entity should agree.

5. **[Migration hygiene — Meera]** `V20260721140000__creator_nudge_log.sql:1` carries a
   "DRAFT — reviewable artifact only, NOT applied" banner, yet it sits in the live Flyway path (and is
   compiled into `target/classes`). Flyway **will** execute it — the banner is misleading (the sibling
   `V20260721150000` correctly has none). Strike the banner before merge; Meera confirms the intended
   column list (it matches R2's canonical list — verified).

## Notes (non-blocking, for awareness / Tier-2)
- `MetaOAuthController.callback` requires `code` (`@RequestParam String code`). A user-denied OAuth
  (Meta redirects `error=access_denied`, no `code`) yields a generic Spring 400, not a clean
  "cancelled" branch. Pre-existing pattern; acceptable for pilot, tidy in Tier-2.
- `no_suggestion_today` collapses to UI `status='dismissed'` with `suggestion=null`; the card's
  guard (`DailySuggestionCard.tsx:41`) then renders **nothing** (silent inert) — correct per R1
  Conflict 6. Distinct copy for "nothing today" vs "you dismissed" remains the open Ash+Tejas call
  (API-CONTRACT §6.1); no wire change.
- `metaOAuth.callback` mock (`api.ts:2889`) returns `connected:true` without `accountType`, so demo
  mode never exercises the personal-account branch. Cosmetic.

---

## Bottom line
Both wires are proven byte-identical by inspection; all seven invariants pass; the extract-first
refactor is behavior-preserving. **BE Java, BE Python, FE data-layer: GREENLIGHT.** **FE components:
CHANGES-REQUESTED for one mechanical fix** (delete the rejected types file). The Python
`_MARKETPLACE_RE` tuning must land before the pilot is meaningful. **No blocker to Meera/Kavya
verification** — everything compiles green. **MERGE remains gated on the money-path gate** (unchanged),
plus required fix #1 landed.
