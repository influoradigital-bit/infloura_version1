# Plan: Creator AI Co-pilot Tier-1 — AI-Service (Python) Route

**Author:** Sonnet 5 (Backend #2, paired with Vikram) · **Date:** 2026-07-21
**Status:** REVISED post-Priya R1 ruling — see
[creator-copilot-priya-review-r1.md](creator-copilot-priya-review-r1.md).
No code written yet, per instruction.
**Parent spec:** [wiki/ai-review/creator-ai-copilot-tier1-build-spec.md](../ai-review/creator-ai-copilot-tier1-build-spec.md)
(§2.4 route, §5 security, §7 AI design). **Gate:** same as parent spec — do not
start until Priya certifies money-path stability. This is the AI-service half
only; Vikram owns the Java `CreatorNudgeService`/`CreatorSuggestionAiClient`
side and the DB/OAuth work — not planned here.

**R1 changes applied in this revision (Priya's binding ruling, CHANGES-REQUESTED → addressed):**
1. **Cut** the caption recovery-tag route/prompt entirely (old §3) — LLM
   caption-tagging is OUT of Tier-1; tagging stays deterministic Java-only
   (`ThemeMatchService.themesForText`). See §3 below (now a scope note, not a design).
2. **Dropped `caption_snippet`** from the `/internal/creator-suggestion` request
   contract, its `wrap_untrusted` call, and the caption-injection framing —
   the phrasing call now receives ONLY `theme` + `trend_text`. No creator
   caption text reaches any model in Tier-1.
3. **Validators: extract-first, not duplicate** (§2.3) — imports from a new
   shared `app/prompt/validators.py`, landed by a separate PR before this route.
4. **`PROMPT_VERSION`:** confirmed — reuse the single existing global, no split
   constant (§5.1).
5. Canonical route-naming ruling (`/api/v1/creator/copilot/*`) governs
   Vikram's/Ananya's **public REST** surface (`GET .../suggestion/today` etc.);
   it does not rename this plan's **internal** service-to-service route
   (`/internal/creator-suggestion` stays as-is, same naming family as
   `/internal/trendspark/nudge`) — noted for clarity, no change needed here.

Mirrors, byte-for-byte where possible, the existing guarded Haiku route:
`influora-ai/app/routes/trendspark.py` + `influora-ai/app/prompt/trendspark.py`.
Per the parent spec's own rule of thumb ("FORK it, do not extend it" — applied
there to `TrendSparkNudgeService`), this plan **forks** the prompt/tone content
but (per Priya's ruling) **imports the shared security validators** rather than
duplicating them — see §2.3.

---

## 1. New route `/internal/creator-suggestion`

**New file:** `influora-ai/app/routes/creator_suggestion.py` (sibling of
`trendspark.py`, not an edit to it).

### 1.1 Request/response contract (draft — freeze with Vikram before code)

```
POST /internal/creator-suggestion
Authorization: Bearer <Spring-issued creator-scoped service token>

request:
{
  "creator_profile_id": "<uuid, required>",   // the tenant key (analog of workspace_id)
  "theme_matched": "self-care",                // ONE closed-vocab theme, from ThemeMatchService.score() — server-derived enum
  "trend_text": "winter skincare routines are trending"  // scraped/third-party, UNTRUSTED
}

response (success or fallback, always HTTP 200):
{
  "success": true,
  "data": {
    "headline": "<short line, e.g. 'Your skincare + winter niche is trending'>",
    "content_idea": "<3-beat reel idea, <=2 sentences>",
    "message_source": "AI" | "FALLBACK"
  }
}
```

**Per Priya R1 ruling (Conflict 5 addendum): `caption_snippet` is REMOVED from
this contract.** The phrasing call receives ONLY `theme_matched` + `trend_text`
— no creator-authored free text of any kind. This is a deliberate scope cut,
not an oversight: combined with deterministic-only tagging (§3), **no creator
caption text reaches any model in Tier-1 at all.** `trend_text` stays
UNTRUSTED and wrapped (§2.2) — that field alone carries the injection surface
for this route, and it is neutralized by design, not merely mitigated.

Two output fields (`headline` + `content_idea`) instead of trendspark's single
`message`, because §3.5 of the parent spec freezes the FE contract as
`{theme, headline, contentIdea, expiresAt}` — `expiresAt`/`id`/`theme` are
Java-computed, not model output. Priya's R1 ruling confirms the response is
exactly these 2 fields (BE-services verdict, "confirmed defaults"); field
CASING here (`content_idea` snake_case, matching trendspark's internal
`message`/`video_ids` convention) is this service's internal wire format —
Vikram's Java DTO maps it to the FE's camelCase `contentIdea`, same as it
already does nothing special for trendspark's snake_case `video_ids` today.

### 1.2 Orchestration order (mirrors `trendspark.py:178-319` exactly)

1. Parse body; require `creator_profile_id` → 400 `missing_fields` if absent
   (same as trendspark's `workspace_id` check, `trendspark.py:184-188`).
2. `verify_creator_token(bearer, endpoint="creator_suggestion", body_creator_profile_id=...)`
   → `AuthError` → 401/403, **no provider call, no spend** (§4 below — this is
   the new auth path, not `verify_token`).
3. Normalize inputs defensively (length-cap by truncation, never 400 — a
   suggestion must still be produced, same philosophy as
   `trendspark.py:196-204`):
   - `theme_matched` — validate against the SAME closed vocab
     (`app.prompt.trend_tag.THEME_SET`, reused not re-embedded) before it ever
     reaches the prompt. Off-vocab → treat as empty (fail closed to a
     genericfallback, never pass an invented theme string to the model).
   - `trend_text` — cap length (new setting, §5). This is the ONLY untrusted
     free-text field in this route post-R1 (`caption_snippet` removed — see
     §1.1); it is still wrapped (§2.2), since scraped/third-party trend text
     remains attacker-influenceable even with captions out of scope.
4. `check_spend_gate(workspace_id=creator_profile_id)` — **reusing the
   existing generic hard-cap plumbing** by passing `creator_profile_id` into
   the `workspace_id` parameter slot; `spend_tracker`'s Redis/in-memory keys
   are opaque strings, so this works with zero changes to `gate.py` or
   `spend_tracker.py`. Gate trip → `_fallback_response()`, still 200.
5. Build system/user prompt (`app.prompt.creator_suggestion`, §2) → Haiku call
   via the existing shared `ClaudeProvider.complete_text(system=, user=,
   model=CREATOR_COPILOT_MODEL, max_tokens=...)` (same client object pattern,
   `trendspark.py:80-84`).
6. Record spend (`estimate_cost_usd` + `record_spend(cost_usd,
   creator_profile_id)`) — same try/except ValueError-log pattern as
   `trendspark.py:269-288`. Needs a `PRICING_TABLE` row for
   `CREATOR_COPILOT_MODEL` (§5) or it inherits `TRENDSPARK_MODEL`'s rate via
   the same fallback trick `_resolve_rate` already does for `TREND_TAG_MODEL`
   (`pricing.py:150-157`) **if** `CREATOR_COPILOT_MODEL` defaults to the exact
   `TRENDSPARK_MODEL` string (recommended — see §5).
7. `result.ok`/`result.text` falsy → fallback (`trendspark.py:290-291`
   pattern).
8. `parse_and_validate()` (new, route-local, §2) → `None` → fallback + WARNING
   log; success → PII-free INFO log (`shape_of()` on every string field, never
   raw) → return `{"success": true, "data": {...}}`.
9. **Every branch returns HTTP 200.** No 5xx for a phrasing miss, same
   invariant as trendspark (`trendspark.py:19-20` docstring rule).

### 1.3 What's explicitly NOT in this route (per parent spec §8, don't gold-plate)
- No `video_ids` / catalog / SNAPSBY mode — Tier-1 creator has no marketplace.
- No per-creator/day cap logic here — that's Java's `CreatorNudgeService`
  (§2.5 parent spec: DB-constraint-backed, checked *before* this route is ever
  called). This route does NOT re-implement the cap; it only adds the spend
  gate as a second, independent layer (defense-in-depth, not a substitute).
- No mutation of `creator_nudge_log` — Java writes that row after this
  response returns (mirrors `TrendSparkNudgeService` step 5 in parent spec §2.2).

---

## 2. New creator-tone prompt file — `app/prompt/creator_suggestion.py`

Forked from `app/prompt/trendspark.py` (not imported — see rationale in
header). Structure:

```python
from app.prompt.trendspark import FORBIDDEN_PETNAMES  # reuse the one list verbatim,
                                                          # do not re-type it a second time
from app.prompt.untrusted import neutralize_angle_brackets, wrap_untrusted

# NEW reject list (parent spec §5 P0 / §7): creator-facing tone must never
# surface the marketplace at all, since Tier-1 creators have no SNAPSBY mode
# and no OWN_CONTENT/SNAPSBY branch — there is only ONE creator voice.
FORBIDDEN_MARKETPLACE_WORDS = ("snapsby", "buy", "video", "videos")
# NEW: creator-tone is a peer/friend voice, not a brand-vendor voice — pet-name
# rejection reuses FORBIDDEN_PETNAMES as-is (imported, not copied: it's a
# content-policy constant, not route logic, so importing it doesn't couple
# route modules the way importing regexes/validators would).

def build_system_prompt() -> str: ...
def build_user_message(*, theme_matched, trend_text) -> str: ...  # no caption_snippet param (R1 cut)
def fallback_message(*, theme_matched, trend_text) -> tuple[str, str]: ...  # (headline, content_idea)
```

### 2.1 System prompt differences from trendspark's (tone-guide fork, §7 parent spec)
- Voice: **warm, peer-to-peer creator-facing** ("like a friend who's clocked
  your niche is trending"), NOT brand-vendor tone. No "brand name" concept —
  address the creator's niche/theme instead.
- No mode branch (OWN_CONTENT/SNAPSBY) — deleted entirely, there's one path.
- Forbidden: `FORBIDDEN_PETNAMES` (reused), price tokens (reuse `_PRICE_RE`
  shape — see §2.3), **NEW** marketplace words `snapsby`/`buy`/`video`/`videos`
  — creators must never see marketplace language, this is stricter than
  trendspark's OWN_CONTENT-only restriction (parent spec §7: "Forbidden:
  marketplace words... price tokens, pet-names").
- Invent-no-facts rule carries over verbatim (never invent post counts,
  engagement numbers, or claim a specific trend duration not given).
- Untrusted-data framing sentence carries over verbatim (`trendspark.py:81-82`
  wording), now covering exactly ONE wrapped field (`trend_text`) — R1 removed
  `caption_snippet`, so there is no second untrusted field to frame.
- Output contract: `{"headline": "...", "content_idea": "..."}`, two fields
  instead of trendspark's one (`message`/`video_ids`) — no `video_ids` key at
  all (nothing to hallucinate into it).

### 2.2 `build_user_message` — wrap the one untrusted input
Trendspark wraps `brand_name` + `trend_text` (2 untrusted fields). Creator
suggestion, post-R1, has exactly **one**:
- `wrap_untrusted("trend_text", trend_text)` — same as trendspark (scraped/
  third-party). This is now the ONLY wrapped field in the whole route.
- `theme_matched` is closed-vocab-validated before it ever gets here (§1.3),
  so it's rendered as plain text (`theme: {theme_matched}`), not wrapped —
  same treatment `trendspark.py:105` gives `mode` (a validated enum, not
  wrapped) vs. `brand_name`/`trend_text` (wrapped).
- `caption_snippet` / `wrap_untrusted("caption_snippet", ...)` is **REMOVED**
  per Priya's R1 ruling (Conflict 5 addendum) — no creator-authored text
  reaches this or any prompt in Tier-1. Do not build this parameter.

### 2.3 Reused validators — EXTRACT-first, imported (not duplicated)

**Per Priya's R1 ruling (Conflict 7):** the 5 reused regex validators are
**security controls**, and duplicating them across route files is the exact
cross-file drift the ruling exists to prevent (a future petname/price-bypass
fix applied to one copy would silently miss the other). Duplication is
**rejected as a permanent state**. Sequencing, not code organization, is the
fix:

1. **Precondition — a separate extract-first PR lands BEFORE this route is
   built:** `_CODE_FENCE_RE`, `_PETNAME_RE`, `_LOVE_VOCATIVE_RE`, `_PRICE_RE`,
   `_STATEMENT_RE` (today route-local in `app/routes/trendspark.py:60-77`),
   plus `_has_forbidden_petname()` and `_statement_count()`, move into a new
   `app/prompt/validators.py`. `trendspark.py` is refactored to import them
   from there instead of defining them locally. **Kavya re-runs the full
   trendspark test suite green on that PR alone** before this creator route's
   PR is opened — this is item 4 of Priya's ordered pre-conditions, and it is
   NOT this route's PR to bundle.
2. `app/routes/creator_suggestion.py` then **imports** the same module from
   day one:

```python
from app.prompt.validators import (
    _CODE_FENCE_RE,
    _has_forbidden_petname,
    _PRICE_RE,
    _statement_count,
)
```

   (Names shown with their current leading-underscore spelling for continuity
   with `trendspark.py`; the extract-first PR may choose to make them public
   — e.g. drop the underscore — since they now live in a shared module. Not
   this plan's call; follow whatever the extract-first PR lands with.)

3. `_MARKETPLACE_RE` is **creator-specific**, per Priya's ruling ("lives in
   the creator route/prompt, not the shared module") — it stays local to
   `app/routes/creator_suggestion.py` (or `app/prompt/creator_suggestion.py`,
   whichever module ends up owning `parse_and_validate` for this route):

```python
_MARKETPLACE_RE = re.compile(
    r"\bsnapsby\b|\bbuy\b|\bvideos?\b", re.IGNORECASE,
)
```

`parse_and_validate()` in the new route checks BOTH `headline` and
`content_idea` against: non-empty, length cap, `_statement_count(...) <= 2`
each, `_has_forbidden_petname(...)` (imported), `_PRICE_RE.search(...)`
(imported), AND `_MARKETPLACE_RE.search(...)` (local, unconditional — creator
tone has no OWN_CONTENT/SNAPSBY branch to gate it on, unlike trendspark's
`mode == MODE_OWN_CONTENT` conditional at `trendspark.py:161`).

**Dependency, not an open question anymore:** this route cannot be coded
until the extract-first `app/prompt/validators.py` PR has merged and Kavya
has signed off on the trendspark regression run (Priya's ordered
pre-condition #4). Flag to Vikram/Meera when scheduling so it lands first.

---

## 3. Theme-tagging for creator captions — OUT of Tier-1, no AI-service route

**Per Priya's R1 ruling (Conflict 5): CUT.** The prior revision of this plan
proposed a `/internal/creator-caption-tag` recovery route/prompt, forked from
`trend_tag.py`, for captions the deterministic Java mapper couldn't classify.
Priya's ruling is explicit: *"Spec §1 says tagging is Java-only/deterministic;
§9's effort table has no line item for a second AI route; §8 says 'do not
gold-plate.' ... DEFERRED to Tier-2. Do not build `creator_caption_tag.py`
(route or prompt) in Tier-1."*

**Tier-1 theme-tagging is 100% Java, 100% deterministic:**
`CreatorThemeTaggingJob` → `ThemeMatchService.themesForText()` (pure keyword
match, the same pattern as `trendspark/n8n/theme-tagger.js`). **Zero Python
involvement, zero AI cost, zero model exposure to caption text** — this
AI-service repo gains NO new file, NO new route, and NO new prompt module for
caption tagging in Tier-1.

**Combined with §1's `caption_snippet` cut from the phrasing request, this
means no creator caption text reaches any model in Tier-1 at all** — both
halves of the parent spec's P0 "prompt injection via captions" threat row are
eliminated by scope, not mitigated by a control. Per Priya's ruling, the spec
editor should reword that P0 row to: *"In Tier-1 no creator caption text
reaches any model — tagging is deterministic Java; phrasing receives only
server-owned `theme` + `trend_text`. `trend_text` (scraped/third-party) stays
`wrap_untrusted`. The caption-injection control activates in Tier-2 when the
LLM recovery tagger and/or caption-enriched phrasing land."* That's a spec-doc
edit, not an AI-service code change — noting it here so it isn't lost.

**Deferred to Tier-2 (not designed further here — revisit if/when Tier-2 is scoped):**
the recovery-tagger route this section used to describe. If Tier-2 picks it
back up, the design sketch from this plan's prior revision (import
`THEME_SET`/`validate_themes` from `app.prompt.trend_tag` rather than
re-embedding the vocabulary a third time; `SCOPE_SERVICE` auth since it's a
Java batch caller, not n8n) is a reasonable starting point, but should be
re-validated against whatever Tier-2 actually needs rather than built
speculatively now.

---

## 4. Security controls mapped to Kabir's P0/P1 list

| Parent spec sev | Control | AI-service-side implementation |
|---|---|---|
| **P0** IDOR / token-ownership flip | `creator` principal type + tenant check keyed on `creator_profile_id`, not `workspace_id` | New `SCOPE_CREATOR = "creator"` in `app/auth/service_token.py`; new `ENDPOINT_SCOPES["creator_suggestion"] = (SCOPE_CREATOR,)`; new `verify_creator_token()` (see §4.1) asserts `token.creator_profile_id == body.creator_profile_id`, mirroring `verify_token`'s `token_workspace_id != body_workspace_id` check (`service_token.py:255-260`) exactly, just on a different claim name. A brand-side `SCOPE_SERVICE` token can never satisfy `creator_suggestion`'s scope requirement even if replayed here (403 `scope_mismatch`) — segregation is bidirectional, same reasoning `trendspark`'s own `ENDPOINT_SCOPES` entry already relies on. |
| **P0** Prompt injection via captions | **Eliminated by scope in Tier-1, not merely mitigated** — no creator caption text reaches this service at all | Per Priya's R1 ruling (§3 above): tagging is deterministic Java (`ThemeMatchService.themesForText`, no Python route); phrasing (§1.1) receives only `theme_matched` + `trend_text`, `caption_snippet` removed. The ONE remaining untrusted field, `trend_text` (scraped/third-party), stays `wrap_untrusted`-wrapped (§2.2) — delimiters + `neutralize_angle_brackets` together, per `app/prompt/untrusted.py`'s own docstring (neither alone is sufficient). This control reactivates in Tier-2 if/when a caption-touching route is built. |
| **P0** OAuth CSRF/redirect | N/A to this service | Java-only (Spring OAuth callback) — nothing for the AI-service to implement. Noted here only so the table stays complete against the parent spec's row list. |
| **P1** Authz on dismiss/acted | N/A to this service | These are Java REST routes (canonical `POST /api/v1/creator/copilot/suggestion/{id}/dismiss` per Priya's Conflict-1 ruling), not AI-service routes — out of this plan's scope, flagged so it isn't silently dropped. |
| **P1** Spend/DoS | Durable per-creator/day cap is Java's (DB constraint); AI-service adds a second, independent layer | `check_spend_gate(workspace_id=creator_profile_id)` (§1.2 step 4) reuses the existing opt-in `WORKSPACE_DAILY_HARD_CAP_USD` hard-cap mechanism keyed by the creator's id string — zero code changes to `gate.py`/`spend_tracker.py` needed, since both already treat their id parameter as an opaque string. This is defense-in-depth, NOT a substitute for Java's DB-constraint-backed cap (parent spec §2.5's race-condition fix is Vikram's, not mine to satisfy). |
| **P2** PII in logs | Add caption/IG-handle keys to `_REDACT_KEYS`; log `creator_profile_id` in the clear | **Resolved by Priya's R1 ruling:** log `creator_profile_id` unredacted (it is an internal id, the tracing correlation key — same treatment `workspace_id` gets unredacted throughout `trendspark.py`); redact only `caption*`/`ig_handle` text fields. No `caption_snippet` key is needed in Tier-1's `_REDACT_KEYS` (the field no longer exists on this route — §1.1), but add `caption`, `captions`, `ig_handle` to `app/security/redaction.py:_REDACT_KEYS` anyway as forward cover for Tier-2 and for any other creator-flow logging (Vikram's Java side / future routes) that might touch caption text. Every log line in the new route uses `log_event(..., fields={"trend_text": shape_of(trend_text), ...})` — shape only, never raw text, same discipline as `trendspark.py:247-248`. |

### 4.1 `verify_creator_token()` — concrete sketch for `service_token.py`

Additive only — does not modify `verify_token()`'s existing body/behavior
(that function is exercised by `tests/security/test_service_token*.py` today
and by the money-adjacent brand path; touching it is out of scope for this
feature).

```python
SCOPE_CREATOR = "creator"

ENDPOINT_SCOPES: dict[str, tuple[str, ...]] = {
    ...  # existing entries unchanged
    "creator_suggestion": (SCOPE_CREATOR,),
}

@dataclass(frozen=True)
class VerifiedCreatorToken:
    creator_profile_id: str
    scope: str
    subject: str | None
    claims: dict[str, Any]

def verify_creator_token(
    token: str, *, endpoint: str, body_creator_profile_id: str,
) -> VerifiedCreatorToken:
    """Sibling of verify_token(), keyed on creator_profile_id instead of
    workspace_id -- creator tokens carry no workspace claim. Shares
    _decode_and_verify (same JWKS/alg/aud/iss pipeline) so signature
    validation is identical; only the tenant-claim name and dataclass differ.
    """
    settings = get_settings()
    expected_aud = (settings.service_token_aud, settings.stream_token_aud)
    claims = _decode_and_verify(token, expected_aud=expected_aud)

    scope = claims.get("scope")
    if not scope:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "missing_scope", "token has no scope claim")
    allowed_scopes = ENDPOINT_SCOPES.get(endpoint, ())
    if scope not in allowed_scopes:
        raise AuthError(status.HTTP_403_FORBIDDEN, "scope_mismatch",
                         f"scope {scope!r} cannot call endpoint {endpoint!r}")

    token_creator_id = claims.get("creator_profile_id") or claims.get("creatorProfileId")
    if not token_creator_id:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "missing_creator_claim",
                         "no creator_profile_id in token")
    if token_creator_id != body_creator_profile_id:
        raise AuthError(status.HTTP_403_FORBIDDEN, "creator_mismatch",
                         "token.creator_profile_id does not match request body creator_profile_id")

    now = int(time.time())
    exp = claims.get("exp")
    if exp is not None and exp < now:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "expired_token", "token expired")

    return VerifiedCreatorToken(
        creator_profile_id=token_creator_id, scope=scope,
        subject=claims.get("sub"), claims=claims,
    )
```

Needs from Vikram/Spring side (see §6): the token-minting side must add a
`creator_profile_id` claim and a `creator` scope value to whatever mints
`ENDPOINT_SCOPES`-checked tokens today (Spring's internal service-token
issuer). This is the one piece of this plan that requires coordinated
non-Python work before it can be tested end-to-end.

---

## 5. `PROMPT_VERSION` handling + config keys

### 5.1 `PROMPT_VERSION`
`app/config.py:69` — `PROMPT_VERSION = "meera-2026.07.21.6"` is a **single
global constant**, already shared across chat/trendspark/trend_tag/brand_safety
(`app/security/redaction.py:150` stamps it on every log line via the
formatter default, not per-route).

**Resolved by Priya's R1 ruling: reuse the single global constant, no separate
`CREATOR_PROMPT_VERSION`.** Rationale given: "the per-row column already gives
audit granularity" — `creator_nudge_log.prompt_version` (Vikram's canonical
column list, parent spec Conflict 2) captures the value at write time per row
regardless of whether the constant is global or split, so a second constant
would add a second source of truth for no additional audit power. Bump the
trailing counter when the creator-tone prompt ships (same convention already
used for every other prompt change in this file's history). That stamping
happens on the **Java side** (`creator_nudge_log.prompt_version` column), not
in the Python response body — the AI-service route does not need to return
`prompt_version` in its JSON response.

### 5.2 New config keys (`app/config.py`, follow the exact `_get_int`/env-default
pattern already used for `trendspark_max_*` at `config.py:230-247`)

```python
CREATOR_COPILOT_MODEL = os.getenv("CREATOR_COPILOT_MODEL", TRENDSPARK_MODEL)
# Defaults to the EXACT TRENDSPARK_MODEL string (not a separate literal) so it
# shares TRENDSPARK_MODEL's PRICING_TABLE row automatically via the same
# _resolve_rate fallback pricing.py already has for TREND_TAG_MODEL
# (pricing.py:150-157) -- add a PRICING_TABLE fallback branch for
# CREATOR_COPILOT_MODEL the same way, so an override to a distinct id doesn't
# silently under-record spend (pricing.py:113-168's existing two-fallback
# pattern extends to a third, same shape).

# Settings dataclass additions:
creator_copilot_max_trend_text_chars: int = _get_int("CREATOR_COPILOT_MAX_TREND_TEXT_CHARS", 200)  # mirror trendspark_max_trend_text_chars
creator_copilot_max_headline_chars: int = _get_int("CREATOR_COPILOT_MAX_HEADLINE_CHARS", 120)        # NEW
creator_copilot_max_content_idea_chars: int = _get_int("CREATOR_COPILOT_MAX_CONTENT_IDEA_CHARS", 300) # NEW
creator_copilot_max_tokens: int = _get_int("CREATOR_COPILOT_MAX_TOKENS", 300)                         # mirror trendspark_max_tokens
```
(No `CREATOR_COPILOT_MAX_CAPTION_CHARS` — `caption_snippet` was cut from the
request contract in R1, §1.1; nothing left to cap.)

Java/Meera's `application.yml` block (parent spec §4) already defines
`creator-copilot.model` / `.max-suggestions-per-creator-per-day` /
`.theme-tag-batch-cron` on the Spring side — these Python env vars are a
**separate, independent set** (this service never reads Spring's YAML), so
naming them consistently (`CREATOR_COPILOT_*` prefix on both sides) is a
convention alignment, not a shared value — confirm with Vikram there's no
env-var collision in whatever deploy-manifest/`.env` templating glues the two
services together (Meera's deploy-config territory per
`project_influora_deploy_config` memory).

---

## 6. Resolved by Priya's R1 ruling + remaining dependencies on Vikram's contract

**All 4 questions previously open for Priya are now resolved by the R1 ruling**
(see the review doc + summary at the top of this file):
1. Caption-theme-tagging LLM recovery pass → **OUT of Tier-1, cut** (§3).
2. Validator duplication vs. extraction → **extract-first, imported** (§2.3),
   gated on a separate PR + Kavya's trendspark regression pass landing first.
3. Single global vs. split `PROMPT_VERSION` → **single global, no split**
   (§5.1).
4. `creator_profile_id` redaction policy → **log in the clear**, redact only
   caption/IG-handle text (§4 P2 row).

Approved as designed, no change requested: the new `SCOPE_CREATOR` +
`verify_creator_token()` mechanism (§4.1).

**Still needed from Vikram's Java `CreatorSuggestionAiClient` contract
(blocking before either side finalizes code, same "freeze `API-CONTRACT.md`
first" discipline the parent spec applies to Ananya's FE contract, and per
Priya's ordered pre-condition #2 — Vikram drafts, Priya blesses):**
1. Exact request JSON Vikram's client will POST — confirm field names
   (`creator_profile_id` vs `creatorProfileId`, camelCase vs snake_case —
   trendspark's contract is snake_case end-to-end per `trendspark.py:9`'s
   docstring, recommend the same here for consistency). Field list itself is
   now settled by R1: `{creator_profile_id, theme_matched, trend_text}` only —
   no `caption_snippet`.
2. Exact response JSON shape Vikram's client deserializes — Priya's ruling
   confirms the 2-field content shape (`headline`, `contentIdea`); confirm the
   wire-format CASING this route actually returns
   (`content_idea` snake_case, per §1.1's recommendation) matches what
   `CreatorSuggestionAiClient`'s Java DTO expects to deserialize, or whether
   Vikram wants this route to emit camelCase directly instead.
3. Confirm `CreatorSuggestionAiClient` follows the same "never throws,
   returns null on any failure" contract stated for it in parent spec §2.4 —
   this plan's "always HTTP 200, even on fallback" design assumes the Java
   client treats a non-200 as equivalent to null, not as an exception to
   propagate.
4. Token shape → **resolved by Priya's ruling** ("service token = new
   `creator` scope — preserves Kabir's bidirectional scope segregation"),
   confirming this plan's §4.1 recommendation. Still needed: the actual claim
   name Spring will mint (`creator_profile_id` vs `creatorProfileId` — this
   plan's `verify_creator_token()` sketch accepts either via `or`, but
   Vikram/Spring should confirm which one is canonical so the Python side can
   drop the fallback once confirmed) — this is item 6 of Priya's ordered
   pre-conditions ("Spring↔influora-ai token contract agreed"), needed before
   any E2E round-trip test, not before code is written.

---

## Summary of new/touched files (this plan only — no code written yet)

**Post-R1: one route, one prompt module — the caption recovery-tag route is
cut (§3). No `creator_caption_tag.py` files in Tier-1.**

| File | Status |
|---|---|
| `app/prompt/validators.py` | NEW, **precondition PR, lands first** — extracted from `trendspark.py` (Priya Conflict 7); Kavya's trendspark suite must pass green on this PR alone before the row below is built |
| `app/routes/trendspark.py` | EDIT (precondition PR only: refactor to import from `app/prompt/validators.py` instead of defining the 5 regexes locally) |
| `app/routes/creator_suggestion.py` | NEW — depends on the precondition PR above |
| `app/prompt/creator_suggestion.py` | NEW |
| `app/auth/service_token.py` | EDIT (additive: `SCOPE_CREATOR`, `ENDPOINT_SCOPES` entries, `VerifiedCreatorToken`, `verify_creator_token`) |
| `app/config.py` | EDIT (additive: `CREATOR_COPILOT_MODEL` + `Settings` fields) |
| `app/costs/pricing.py` | EDIT (additive: `PRICING_TABLE` row or fallback branch for `CREATOR_COPILOT_MODEL`) |
| `app/security/redaction.py` | EDIT (additive: `_REDACT_KEYS` entries — `caption`, `captions`, `ig_handle`) |
| `app/main.py` (router registration) | EDIT (register new router, same as trendspark's registration — mirror `tests/routes/test_trendspark_registration.py` with a new `test_creator_suggestion_registration.py`) |
