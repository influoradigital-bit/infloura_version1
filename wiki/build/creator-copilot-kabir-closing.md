# Creator AI Co-pilot Tier-1 — Kabir CLOSING Security Pass

**Reviewer:** Kabir (Offensive Security / Red-Team) · **Date:** 2026-07-21
**Scope:** our own app (authorized). Final pipeline gate — verifies the ACTUAL shipped code, not the plan.
**Method:** spot-read the security-load-bearing files in the live tree
(`influora-api/src/main/...`, `influora-ai/app/...`, `db/migration/`), cross-checked against my
design-gate verdict (F-1..F-6) and Priya's 5 applied fixes.
**Verdict:** **SHIP-READY (security).** No Critical/High. Nothing regressed since my design-stage PASS.

---

## Per-item CONFIRMED / REGRESSED table

| # | Item verified | File(s) read | Result |
|---|---|---|---|
| 1 | **F-1 revoke-before-insert** — in-txn revoke of existing non-revoked NULL-workspace creator row before insert | `MetaTokenStorage.storeCreatorToken` :184-197 | **CONFIRMED** — `@Transactional`; `findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(...).ifPresent(revoke+save)` runs *before* the new row is built (:191-197). At most one non-revoked creator row can exist. `getValidCreatorToken` `Optional`-self-DoS closed at source. |
| 2 | **F-5 creator-scoped minter** — exists, mints `scope='creator'` not `'service'`; `creator_profile_id` claim; no `workspace_id`; short TTL; reuses aud/iss/signing | `CreatorSuggestionServiceTokenService.java` (whole) | **CONFIRMED — BUILT** (my gate flagged it MISSING from the manifest; it now exists). `SCOPE_CREATOR="creator"` :44; `.claim("scope", SCOPE_CREATOR)` :74; `.claim("creator_profile_id", …)` :73; TTL `min(props.ttl, MAX_TTL)` :60; ES256 via `SpringJwksKeyService` :63,77. Bidirectional segregation intact. |
| 3 | **Injection surface** — no creator caption text to any model; only `theme_matched` (closed-vocab, plain) + `trend_text` (wrapped untrusted) | `creator_suggestion.py` :204-284, `creator_suggestion.py` (prompt) :105-119, `CreatorSuggestionAiDtos.java` :22-30 | **CONFIRMED** — Java request DTO has exactly 3 fields (`creator_profile_id/theme_matched/trend_text`), no `caption_snippet` anywhere (:11). Python wraps only `trend_text` via `wrap_untrusted` (prompt :117); `theme_matched` closed-vocab-validated `_normalize_theme` → `THEME_SET` (:138-148, fail-closed to "") then `neutralize_angle_brackets` (:116). System prompt tags `trend_text` UNTRUSTED (:97-98). |
| 4 | **Authz** — resolve-then-check on all 3 REST routes (creator principal, never a body param); IDOR-safe 404 on dismiss/acted | `CreatorCopilotController.java` :42-69, `CreatorNudgeService.java` :176-199 | **CONFIRMED** — every route resolves `creatorContext.requireCreatorProfile(principal)` first (:45,57,66); no creatorProfileId in any body/path. dismiss/acted → `requireOwnedSuggestion` → `findByIdAndCreatorProfileId` → same `SUGGESTION_NOT_FOUND` 404 for not-found and not-yours (:192-198). No IDOR oracle. |
| 5 | **Per-day cap DB constraint** `uq_creator_nudge_day` present in shipped migration | `V20260721140000__creator_nudge_log.sql` :48-50 | **CONFIRMED** — `shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED` + `ADD UNIQUE KEY uq_creator_nudge_day (creator_profile_id, shown_day)`. Service catches `DataIntegrityViolationException` and returns race-winner's row (`CreatorNudgeService` :162-170). Write race closed. |
| 6 | **Marketplace-regex fix (#3)** and **claim-spelling fix (#2)** didn't open anything | `creator_suggestion.py` :122,194; `service_token.py` :345 | **CONFIRMED — NO REGRESSION.** (#3) `_MARKETPLACE_RE = \bsnapsby\b` narrowed from `snapsby\|buy\|videos?` — this is an *output content-quality* control, NOT the injection defense (that is `wrap_untrusted` + closed-vocab, untouched). Narrowing removes false-positive fallbacks only; system-prompt tone control remains. (#2) `token_creator_id = claims.get("creator_profile_id")` — camelCase fallback removed; the tenant-claim assert `token_creator_id != body_creator_profile_id → 403 creator_mismatch` (:351-356) is intact and now unambiguous. |
| 7 | **Test-harness fix** `@ActiveProfiles("dev")` on `AbstractIntegrationTest` does NOT weaken any production security path | `AbstractIntegrationTest.java` :62-119, `SecretsStartupValidator.java` :210-283 | **CONFIRMED — NOT A FOOTGUN.** See analysis below. |

**Regressions found: NONE.** All 7 items CONFIRMED against live code.

---

## Item 7 — test-harness footgun analysis (the one that needed real scrutiny)

`@ActiveProfiles("dev")` is a **`org.springframework.test.context`** annotation — it applies **only**
to the Spring TestContext framework and only to classes that extend `AbstractIntegrationTest`. It
cannot influence a production boot: production never runs test classes, and it selects its profile
from `SPRING_PROFILES_ACTIVE`, not from a test annotation.

Production fail-closed behavior is **unchanged** and correctly hardened. `SecretsStartupValidator`
computes warn-only mode as:

```
isDev = ("dev".equalsIgnoreCase(env)) && influoraEnvironment.isDev()   // BOTH required (:212-214)
```

`influoraEnvironment.isDev()` is keyed on the **active Spring profile** (`spring.profiles.active`),
not a hand-set var. And `validateEnvConsistency` (:273-283) **aborts** if the profile is not `dev`
while `influora.env` resolved to `dev` (the "forgot `SPRING_PROFILES_ACTIVE=prod`" footgun — closed
by the I8 fix). So the only route to warn-only is *both* the `dev` Spring profile active *and*
`influora.env=dev` — a combination a real deploy never sets. The test using the `dev` profile to run
validators in warn-only is exactly the intended, scoped behavior (its job is DB/migration/constraint
verification, not secret hygiene), and it changes nothing about how a non-dev box evaluates the
validators. **No weakening of any production security path.**

---

## Design-gate findings F-1..F-6 — closing status

| ID | Sev | Design-gate status | Closing status |
|---|---|---|---|
| F-1 | Low | revoke-before-insert recommended | **CLOSED** — implemented in `storeCreatorToken` (item 1). |
| F-2 | P2/Low | captions plaintext-at-rest | **RISK-ACCEPTED** (Priya §C) — creator's own public IG text; OAuth token still AES-256-GCM. Caption-cache/sync surface not exercised in shipped Tier-1 path. Residual → Tier-2. |
| F-3 | P2/Low | `media` redact key omitted | **RESIDUAL (non-blocking)** — new Python route handles no media objects; harmless in Tier-1. Track for Tier-2 if a media-bearing route lands. |
| F-4 | Low | Java caption-sync logging discipline | **DEFERRED** — `CreatorCaptionSyncJob` NOT built in Tier-1 (only `CreatorThemeTaggingJob`, OFF by default). No live caption-log surface ships. Re-arm when the sync job lands. |
| F-5 | Med | minter must be built + audited | **CLOSED** — minter built, `creator` scope + `creator_profile_id` claim confirmed (item 2); Python `ENDPOINT_SCOPES["creator_suggestion"]=(SCOPE_CREATOR,)` (service_token :65) enforces bidirectional segregation. |
| F-6 | Info | OAuth state store in-process | **UNCHANGED (fails closed)** — move to Redis before multi-instance. Non-blocking. |

---

## Residuals to carry into Tier-2 (none gating)

1. **P0 prompt-injection control is deferred, not deleted.** The moment any Tier-2 route lets creator
   caption text reach a model (LLM recovery tagger or caption-enriched phrasing), `wrap_untrusted`
   + `neutralize_angle_brackets` + closed-vocab-validate MUST be re-armed on that path. The
   `_MARKETPLACE_RE` narrowing (#3) is a content-quality relaxation and does NOT affect this.
2. **F-2 / F-3 / F-4** re-activate the moment caption/media data is stored, synced, or logged
   (caption at-rest encryption decision; `media` redact key; Java-side `shape_of`/ids-only logging).
3. **F-6** Redis-backed OAuth state store before horizontal scale-out.
4. Non-security carry-forward (Priya §B): `MetaConnectionService` left unwired — if a creator
   ig-status/disconnect is ever built it MUST use the `...CreatorProfileIdAndWorkspaceIdIsNull...`
   creator repository methods, never the workspace-scoped ones (else it resurrects the
   `WHERE workspace_id = NULL` bug).

---

## FINAL CLOSING VERDICT: **SHIP-READY (security).**

No Critical/High. All 7 checklist items CONFIRMED against the actual shipped code; zero regressions
from the design-stage PASS. F-1 and F-5 (the two items my gate flagged as open/missing) are both
CLOSED in code. F-2..F-4/F-6 are risk-accepted or deferred-with-no-live-surface, none gating.
Security does not block the pipeline. (Non-security ship gates — money-path merge gate, live
Meta/Anthropic E2E, Ash+Tejas zero-state copy, 8-test pytest fast-follow — remain owned by their
respective gates and are out of my scope.)
