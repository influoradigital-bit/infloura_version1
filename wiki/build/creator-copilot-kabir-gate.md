# Creator AI Co-pilot Tier-1 — Kabir Security Gate

**Reviewer:** Kabir (Offensive Security / Red-Team) · **Date:** 2026-07-21
**Scope:** our own app (authorized). Final security gate on the reconciled, greenlit package.
**Verdict:** **PASS — no Critical/High blocker.** Six Medium/Low hardening items to fold into build (none block).

**Package audited (all read):** CHANGESET (34 files), priya-review-r1 (R1+R2), API-CONTRACT v1,
be-services-plan, ai-route-plan, build-spec §5, the 3 drafted migrations. **Also read the live tree**
for the reused OAuth surface (`MetaOAuthController.java`, `MetaOAuthStateStore.java`,
`MetaTokenStorage.java`, `MetaOAuthTokenRepository.java`) — the design *reuses* these, so I verified
them directly rather than trusting the plan's summary.

> **Design changed since my first review.** The two biggest deltas are both security-positive:
> (a) R2 dropped `caption_snippet` + cut the LLM caption-tag route → no creator free-text reaches any
> model in Tier-1 (P0 injection collapses to a single wrapped `trend_text`); (b) the OAuth work is now
> a *repair* of a live-broken creator path, not a fork. Both re-verified below against the new shape.

---

## Per-threat verdict table

| # | Original threat (spec §5) | Sev (orig) | Verdict | Residual sev |
|---|---|---|---|---|
| 1 | IDOR / token-ownership flip | P0 | **PASS** (+ Low hardening, F-1) | Low |
| 2 | Prompt injection via captions | P0 | **PASS — DOWNGRADED** (eliminated by scope) | Low |
| 3 | OAuth CSRF / redirect | P0 | **PASS** (verified in live tree) | — |
| 4 | Authz on dismiss/acted | P1 | **PASS** | — |
| 5 | Spend / DoS (per-day cap race) | P1 | **PASS** (DB constraint closes the write race) | Low (accepted) |
| 6 | PII at rest / in logs | P2 | **STILL-OPEN** (F-2..F-5, all Low/P2) | Low |

No verdict is STILL-OPEN at Critical/High. **Gate = PASS.**

---

## Threat-by-threat detail

### 1. IDOR / token-ownership flip — PASS
- **Resolve-then-check on creator principal:** all three public routes resolve identity via
  `creatorContext.requireCreatorProfile(principal)` (be-services §5) — never a param. Confirmed the
  pattern matches the shipped `TrendSparkController` discipline.
- **`creatorProfileId` from request body rejected:** the browser-facing endpoints take *no*
  creatorProfileId at all (GET has no body; dismiss/acted use path `{id}` = suggestion id only,
  ownership enforced in `findByIdAndCreatorProfileId`). The only `creator_profile_id` in a body is the
  **internal** Spring→influora-ai call, where it is server-set and cross-checked against the token
  claim (`verify_creator_token` asserts `token.creator_profile_id == body.creator_profile_id`). No
  browser can inject it. PASS.
- **`creator` scope added to service_token:** `SCOPE_CREATOR="creator"`,
  `ENDPOINT_SCOPES["creator_suggestion"]=(SCOPE_CREATOR,)`, and a `service`/`chat:stream` token is
  rejected `403 scope_mismatch`. Segregation is bidirectional. PASS.
- **OAuth-flip (nullable `workspace_id`) — no cross-tenant leak:** creator reads key on
  `findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)`; brand reads key on
  the untouched `findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(non-null, ...)`. The two
  key-spaces are **disjoint** (creator rows have NULL workspace, brand rows never do). No path returns
  another creator's or a brand's token. PASS.
- **Finding F-1 (Low, hardening):** making `workspace_id` NULL-able **silently removes the DB
  uniqueness guarantee** the brand path leaned on. The existing `UNIQUE(workspace_id,
  creator_profile_id)` does **not** constrain creator rows, because MySQL treats multiple NULLs as
  non-equal. So two concurrent *first-time* creator connects (double-submit / reconnect race) can
  insert two non-revoked NULL-workspace rows; `getValidCreatorToken`'s `Optional` query then throws
  `IncorrectResultSizeDataAccessException` and **self-DoSes that one creator** until a row is revoked.
  No cross-tenant impact, low likelihood — hence Low. **Fix:** inside the `@Transactional`
  `storeCreatorToken`, revoke any existing non-revoked creator NULL-workspace row(s) before insert (or
  add a generated-column uniqueness backstop, e.g. `UNIQUE(COALESCE(workspace_id,'creator:'||creator_profile_id), creator_profile_id)`).

### 2. Prompt injection via captions — PASS, DOWNGRADED P0 → Low
R2 genuinely reduced this, not just re-labelled it. Verified end-to-end:
- LLM caption-tag route/prompt (`creator_caption_tag.py`) **not built** in Tier-1; tagging is
  deterministic Java (`ThemeMatchService.themesForText`) — **zero model exposure to caption text.**
- `caption_snippet` **dropped** from the phrasing request contract (ai-route §1.1) — no
  `wrap_untrusted("caption_snippet", …)` call to build.
- **The only untrusted free-text into the one Haiku call is now `trend_text`, and it stays wrapped**
  (`wrap_untrusted` + `neutralize_angle_brackets`, ai-route §2.2). `theme_matched` is closed-vocab
  validated *before* the prompt (off-vocab → treated as empty), so it is rendered as plain text, never
  wrapped-as-untrusted and never attacker-chosen.
- Defense-in-depth on output stands: `CreatorSuggestionAiClient` re-validates any echoed `theme`
  against `knownThemes()` (drops non-members), and `parse_and_validate` runs
  `_PRICE_RE`/`_has_forbidden_petname`/`_statement_count`/`_MARKETPLACE_RE` on `headline`+`content_idea`.
- **Confirm:** the P0 is legitimately reduced for Tier-1. It **reactivates as a real P0 in Tier-2**
  the moment a caption-touching route (LLM recovery tagger or caption-enriched phrasing) lands — the
  control must not be deleted, only deferred. Recorded so it is not lost.

### 3. OAuth CSRF / redirect — PASS (verified against live code, not just the plan)
The design reuses the existing `/meta/oauth/authorize|callback`, so I checked the actual
implementation rather than assuming the control survived the repurposing:
- **State userId-bound + single-use + TTL:** `MetaOAuthStateStore.issue(userId)` binds state to the
  user; `consume()` does `pending.remove(state)` (single-use) then rejects expired
  (`STATE_TTL = 10 min`) and rejects `found.userId != userId`. `MetaOAuthController.callback` calls
  `stateStore.consume(state, principal.getUserId())` and 400s on failure — i.e. rejects
  `state.userId != session principal`. All four required properties present. PASS.
- **`redirect_uri` server-config:** the callback accepts only `code` + `state` params — no
  request-supplied redirect_uri; the authorize URL is built server-side
  (`oAuthService.buildAuthorizationUrl(state)`). PASS.
- **Note (Info, not a finding):** the state store is an in-process `ConcurrentHashMap` — under
  horizontal scaling or instance restart a valid state can be lost (user must retry). Fails **closed**
  (rejects), so no security impact; flagged only because the creator flow is now this feature's entry
  point and its own comment already notes "move to Redis if/when horizontally scaled."

### 4. Authz on dismiss/acted — PASS
Both mutations resolve-then-check: controller resolves `principal → creatorProfile.getId()`, service
resolves the row via `findByIdAndCreatorProfileId(id, creatorProfileId)` and 404s
(`SUGGESTION_NOT_FOUND`) if the row is not the caller's. The path `{id}` is never trusted alone.
"Not found" and "not yours" return the **same 404** (no IDOR oracle, per API-CONTRACT §1.2). Idempotent
set-once stamps. Each route self-enforces (no reliance on a global guard). PASS.

### 5. Spend / DoS (per-creator/day cap race) — PASS; residual accepted
- **The cap is now a durable DB constraint, not just an app check.** Migration
  `V20260721140000` adds `shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED` +
  `UNIQUE uq_creator_nudge_day (creator_profile_id, shown_day)`. Two concurrent first-of-day requests
  both miss the idempotent read, both insert — the unique key lets exactly **one** through; the loser
  catches `DataIntegrityViolationException` and re-reads the winner's row (be-services §1 step 8, §5).
  **This closes the double-*write* race I originally flagged** (the app-level `existsBy…` check alone
  was racy). PASS.
- **Residual double-*spend*** (both requests may call Haiku before either INSERT resolves → ≤1 extra
  Haiku call, 1 row shown) is explicitly risk-accepted by Priya (R1 binding 3): self-heals, bounded,
  rounding-error cost; a distributed lock adds its own failure mode. Second independent layer: the
  Python route's `check_spend_gate` global hard cap. Keys are globally-unique ULIDs, so reusing the
  `workspace_id` slot for `creator_profile_id` cannot collide with a brand's spend key. Accepted.

### 6. PII at rest / in logs — STILL-OPEN (all Low/P2)
- **In-logs (mostly honored):** `_REDACT_KEYS` gains `caption`, `captions`, `ig_handle`;
  `creator_profile_id` logged in clear (internal tracing id — correct, matches `workspace_id`
  treatment). Every new-route log uses `shape_of()`, never raw text. Good.
- **Finding F-2 (P2, Low):** spec §5 P2 mandated **"encrypt captions at rest."** The final design
  stores `creator_captions.caption_text` as **plaintext TEXT** (migration `V20260721130000`, entity
  `CreatorCaptionCache`). Mitigating context: captions are the creator's own **public** IG post text,
  not audience/DM/PII data, and access tokens *are* encrypted (`MetaTokenStorage` AES-256-GCM,
  untouched). So confidentiality impact is low — but a control I mandated was dropped silently.
  **Action:** either implement column encryption, or record an explicit risk-acceptance ("public IG
  captions, no at-rest encryption") in the ship checklist. Do not let it lapse unremarked.
- **Finding F-3 (P2, Low):** spec §5 P2 listed `media` among the redact keys; the manifest adds
  `caption`/`captions`/`ig_handle` but **not `media`**. The new Python route no longer handles media
  objects, so harmless there — but see F-4.
- **Finding F-4 (Low):** the **Java** caption-sync path (`CreatorCaptionSyncJob` +
  `InstagramMetricsFetcher`) fetches captions/media from Meta and is a **separate log surface** from
  Python's `_REDACT_KEYS`. No plan specifies redaction/`shape_of` discipline for Java-side caption
  logging. **Action:** confirm the sync job logs ids/counts only, never raw `caption_text`/media
  payloads.
- **Finding F-5 (Medium, manifest gap — not a vuln):** the Python `verify_creator_token` is planned,
  but **no Java file in the 34-file manifest mints the creator-scoped token** (the brand analog is
  `BrandSafetyServiceTokenService.mint(workspaceId)`; there is no enumerated
  creator equivalent). Pre-condition #6 acknowledges the contract is unagreed. This fails **closed**
  if unbuilt (no token → 401) or misbuilt (wrong scope → 403), so it is not a live vulnerability — but
  it must be **enumerated and audited at code review**: the minter must emit `creator` scope (never
  `service`), include the `creator_profile_id` claim, and reuse the existing aud/iss/signing + a short
  TTL. Flagging so the segregation control has an owner.

---

## Blockers

**None.** No Critical or High finding. The two design changes since R1 (caption-free model input;
OAuth repair with verified state binding) removed the paths that carried real P0 risk.

## Findings to carry into build (all non-blocking)

| ID | Sev | Item | Owner |
|---|---|---|---|
| F-1 | Low | Nullable-workspace flip removes DB uniqueness for creator tokens → revoke-before-insert (or generated-column uniqueness) in `storeCreatorToken` | Vikram |
| F-2 | P2/Low | Captions stored plaintext — implement at-rest encryption **or** record explicit risk-acceptance (public IG data) | Vikram/Meera |
| F-3 | P2/Low | `media` redact key omitted from `_REDACT_KEYS` | Sonnet #2 |
| F-4 | Low | Java caption-sync logging must be `shape_of`/ids-only (separate surface from Python redaction) | Vikram |
| F-5 | Med | Manifest gap: enumerate + audit the Java creator-service-token minter (must mint `creator` scope + `creator_profile_id` claim) | Vikram + Sonnet #2 |
| F-6 | Info | OAuth state store is in-process (lost on scale-out/restart); fails closed — move to Redis before multi-instance | Meera |

## Tier-2 re-arm reminder
The P0 prompt-injection control is **deferred, not deleted.** When any Tier-2 route lets creator
caption text reach a model (LLM recovery tagger or caption-enriched phrasing), the
`wrap_untrusted`/`neutralize_angle_brackets` + closed-vocab-validate controls must be re-armed on that
path before it ships.

**FINAL GATE VERDICT: PASS.** Pipeline may proceed. F-1..F-6 tracked as build-time hardening, none gating.
