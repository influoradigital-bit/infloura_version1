# Security Review: Wave C Task C3 (BrandSafetyAiClient live wiring)

Date: 2026-07-07
Reviewer: Kabir (Red-Team / CISO)
Load-bearing: YES (C1 MED-1 condition — first live caption egress; workspace-isolation and
service-token minting correctness for the new Direction-2 credential surface)
Inputs: `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md` (LOCKED, auth
*strategy* already settled — not re-litigated here), `wiki/errors/wave-c-task-c3-java-brandsafety-client-qa-review.md`
(APPROVED, 386/386), `wiki/errors/caption-persistence-C1-security-review.md` (SIGN-OFF,
conditional on MED-1: caption egress to influora-ai must stay transient, no persistence there).

## Verdict: SIGN-OFF

0 CRIT / 0 HIGH / 0 MED / 2 LOW. C3's implementation is exactly what the C1 MED-1 condition
required and what the ADR's fail-closed posture assumed. No new finding blocks merge. Routing
to Meera for local build/dev verification (Java-only change, no new migration — no live-MySQL
check needed).

---

## 1. Caption transience (C1 MED-1 condition) — PASS

Traced the caption end-to-end: `media_metrics.caption` (DB, written by `MetricsPollingJob`) ->
`MediaMetric.getCaption()` -> `BrandSafetyScoreService.toContentItems()` (`ContentItem.caption`)
-> `BrandSafetyAiClient.classify()` request body -> HTTP POST to influora-ai -> influora-ai's
response (GARM flags + sentiment, no caption echoed back) -> `BrandSafetyScoreService.mapToResult()`
-> `CreatorScore.garmFlagsJson`.

- **No new persistence of the caption itself.** `garmFlagsJson` serializes `List<ClassifiedItem>`
  (`BrandSafetyScoreService.java:169-176`), which per `BrandSafetyDtos` and the influora-ai
  contract (`brand_safety.py:244-253`, `_validate_model_result`) carries only `content_id`,
  `garm_flags`, `content_sentiment`, `sentiment_score`, `brand_safety_score`,
  `overall_rationale` — the model's classification of the caption, never the caption text itself.
  Confirmed no field named `caption`/`text`/`content` carrying raw input exists anywhere in the
  classified-item shape that gets persisted.
- **No logging of caption anywhere in the new Java code.** `BrandSafetyAiClient` logs only
  `workspace_id`, `items.size()`, `statusCode`, extracted error `code` (lines 106-111, 117-123,
  136-140) — never `requestBody`, never `items`, never a single `ContentItem`. `BrandSafetyScoreService`
  logs only `creatorProfileId`, `items.size()`, `e.getMessage()` (lines 99-113) — the caught
  exception's message originates from `BrandSafetyAiException`'s own message strings
  (`"influora-ai brand-safety call failed"`, `"...returned status N (code)"`, `"...response
  shape was unexpected"`), none of which embed request/response bodies. Matches Kavya's
  independent audit (QA review §3) — I re-verified rather than took it on faith, same conclusion.
- **No caching.** `BrandSafetyAiClient` is stateless — one `HttpClient`, one `ObjectMapper`, no
  field ever retains a request/response between calls (constructor-injected dependencies only,
  no instance-level mutable state). Every `classify()` call is a fresh synchronous round-trip.
- **Python side stays transient too** (confirms the other half of MED-1's condition, even though
  out of C3's own diff): `brand_safety.py` holds no DB connection (module docstring, verified —
  no import of any DB/session dependency in that file), logs only `shape_of(captions)` never raw
  text (`log_event(... "captions": shape_of(...))`, line 287), and the module docstring states
  explicitly it "never fetches from Meta and holds no DB connection of its own (stateless)."
  Nothing added by C3 changes this.
- **Conclusion: MED-1 condition is met.** The caption crosses the process boundary exactly once
  per scoring run, is never written to a second at-rest location, never appears in a log line on
  either side, and never round-trips back into a persisted column. This is the "transient egress"
  the C1 sign-off required.

## 2. Workspace isolation — PASS, with one accepted design note

- `ScoreCalculationJob.scoreOne()` calls `brandSafetyScoreService.scoreCreator(creatorProfileId,
  recentMedia)` per-creator, inside the per-creator try/catch loop (`calculateScores()` lines
  126-143) — identical shape to `MetricsPollingJob`'s per-creator iteration
  (`pollOne(workspaceId, creatorProfileId)` called per token row, own try/catch per creator) and
  to the pattern I've held every other job to. No batch-wide shared mutable state that could leak
  one creator's data into another's row; `CreatorScore.Builder` is a fresh local per `scoreOne()`
  call.
- **The one deliberate deviation from the norm:** `BrandSafetyScoreService` resolves
  `workspaceId` via `MetaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalse(creatorProfileId)`
  — **not** workspace-scoped, by design, per its own javadoc (`MetaOAuthTokenRepository.java:27-40`).
  I verified this is safe and correctly reasoned, not a shortcut:
  - `creator_scores` has no `workspace_id` column at all (confirmed — this is a creator-level
    table, "shared row across authorized brands," consistent with my own prior B4 review per that
    javadoc's citation).
  - `workspace_id` in the outbound call exists **solely** to satisfy influora-ai's per-call
    token-binding requirement (`verify_token` pins `token.workspace_id == body.workspace_id`) —
    it is not a data-scoping boundary for this particular call, because the classification result
    (GARM flags on a caption) does not vary by which brand's workspace happens to be attached to
    the request. The same caption produces the same classification regardless of which of a
    creator's connected workspaces authorizes the call.
  - This means an arbitrary-but-stable workspace pick cannot cause cross-workspace **data leakage**
    (no brand-specific data is being fetched or returned — the request only carries the creator's
    own already-public caption, and the response only carries a classification of it). It could
    theoretically look odd in an influora-ai-side audit log ("workspace X's token used to score
    content while brand X wasn't the one triggering the run"), but that's a bookkeeping nuance, not
    an isolation violation — no brand ever sees another brand's data as a result of this pick.
  - **This is architecturally different from, and does not violate, the workspace-isolation
    standard I've held for AudienceDemographicsJob/MetricsPollingJob**, where workspace_id gates
    *which brand's data a query returns*. Here workspace_id gates *which token authenticates an
    outbound call whose result is creator-level, not brand-level*. Correct call by Vikram; the
    javadoc reasoning is accurate and I independently confirm it holds.
- **No cross-creator leak in the batch:** `toContentItems()` builds a fresh `List<ContentItem>`
  from exactly the `recentMedia` passed in for *this* creator (`mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(creatorProfileId, ...)`,
  `ScoreCalculationJob.java:165-167`) — no shared/static list, no accumulation across the loop.
  Each `classify()` call's request body is scoped to one creator's own captions only.

## 3. Service-token minting correctness — PASS

Cross-checked `BrandSafetyServiceTokenService.mint()` claims against Python's
`verify_token`/`_decode_and_verify` requirements line by line:

| Python requires | Java mints | Match |
|---|---|---|
| `alg` in `ALLOWED_ALGS` (RS256/ES256) OR HS256-if-`StaticDevJwksSource` | HS256 always (`Keys.hmacShaKeyFor`) | Matches the accepted dev-fallback-only path per the ADR; correctly NOT attempting RS256 (no keypair exists) |
| `aud` == `expected_aud` tuple (`service_token_aud`, `stream_token_aud`) | `audience=props.getAudience()` default `influora-internal` == Python's `SERVICE_TOKEN_AUD` default | Match |
| `iss` == `settings.spring_expected_iss` | `issuer=props.getIssuer()` default `influora-api` == Python's `SPRING_JWT_ISSUER` default | Match |
| `exp`, `iat` present | `.issuedAt(now).expiration(exp)` | Match |
| `scope` claim, must equal `SCOPE_SERVICE` for `ENDPOINT_SCOPES["brand_safety"] = (SCOPE_SERVICE,)` | `.claim("scope", SCOPE_SERVICE)` where `SCOPE_SERVICE = "service"` | Match, exact string |
| `workspace_id` claim == `body_workspace_id` | `.claim("workspace_id", workspaceId)` — same `workspaceId` string passed into `classify()` and serialized into `BrandSafetyRequest.workspace_id` | Match — same variable, no transformation/case difference possible |
| TTL should be short | `ttl = min(props.getTtlSeconds(), MAX_TTL_SECONDS=60)` — hard ceiling regardless of config | Correct, matches `InternalServiceTokenProperties` convention |
| no `sub`/`user_id` (this is service-to-service, not user-delegated) | No `.subject(...)` call anywhere in `mint()` | Correct — token cannot be mistaken for a user-delegated credential |

- **No over-broad claims.** The minted token carries exactly `jti`, `iss`, `aud`, `workspace_id`,
  `scope`, `iat`, `exp` — nothing else. No `role`, no `permissions` array, no wildcard scope.
- **Replay-against-a-different-scope check:** `ENDPOINT_SCOPES` maps `SCOPE_SERVICE` to
  `("chat", "analyze_site", "voice_transcribe", "voice_speak", "brand_safety")` — i.e. Python's
  `scope=service` is intentionally shared across multiple internal endpoints, not brand-safety-exclusive.
  This means a brand-safety token, if intercepted before expiry (60s TTL ceiling), **could** be
  replayed against `/analyze-site` or `/voice/*` too, since Python's scope check only asks "is this
  scope allowed for this endpoint," not "was this token minted for this specific endpoint." This is
  **not a new gap introduced by C3** — it's the pre-existing `SCOPE_SERVICE` design already shared
  by the Meera stream token's sibling flows, and every `SCOPE_SERVICE` token already carries this
  same blast radius regardless of which Java call site mints it. Given the binding 60-second TTL
  ceiling and that all `SCOPE_SERVICE` endpoints are equally internal-only/Spring-authenticated
  (none is more sensitive than brand-safety in a way that would make cross-replay meaningfully
  worse), I am not gating C3 on this. Flagging as **LOW-1** below for a possible future tightening
  (per-endpoint scope granularity) rather than a blocker — it would require a coordinated
  Python+Java change across all Direction-2 token issuers, out of scope for a single-endpoint task.
- **`workspace_id` binding integrity:** confirmed the exact same `workspaceId` variable flows from
  `BrandSafetyScoreService.scoreCreator()` -> `brandSafetyAiClient.classify(workspaceId, items)` ->
  `tokenService.mint(workspaceId)` (signs the claim) AND -> `new BrandSafetyRequest(workspaceId, items)`
  (the body Python compares against) — same Java `String` reference, no risk of the token's claim
  and the body's field ever diverging within one call (`BrandSafetyAiClient.java:89-90`).

## 4. Graceful degradation / fail-safe on error — PASS

- **No stale/wrong data written.** `BrandSafetyScoreService.scoreCreator()` returns
  `Optional.empty()` on `BrandSafetyAiException` (transport failure, non-200, malformed shape) and
  on any unexpected `Exception` (defensive catch-all) — never a partial/best-effort result.
  `ScoreCalculationJob.scoreOne()` uses `.ifPresent(...)` (lines 209-216), so on failure the 3
  brand-safety builder calls are simply skipped and the new `CreatorScore` row is written with
  those columns `null` — it does **not** carry forward a stale prior score under a false
  freshness claim, since `CreatorScore` rows are new per run (`Ulids.newUlid()`,
  `ScoreCalculationJob.java:188`) rather than updated in place. A brand reading the latest row
  after a failed influora-ai call sees `null` brand-safety fields, not silently stale ones.
- **No exception detail leaked brand-facing.** Traced every path an exception's message could
  travel: `BrandSafetyAiException` messages (transport error text, HTTP status/code, shape
  mismatch) are only ever passed to SLF4J (`log.warn`/`log.error`) inside
  `BrandSafetyScoreService` and `BrandSafetyAiClient` — never rethrown to a controller, never
  attached to any DTO returned by `AnalyticsController`/`AnalyticsDtos` (which, per my C1 review
  §1c, expose zero per-media records and are structurally incapable of carrying this data anyway).
  `GlobalExceptionHandler`'s catch-all (`"An unexpected error occurred"`, fixed string) is the
  backstop even in the hypothetical case something did propagate, consistent with my C1 finding.
  On the Python side, `ClaudeProvider` failures degrade to fixed-string errors (`"provider_error"`,
  `"circuit_open: ..."`, `"no_tool_use_in_response"`) — no raw exception text, no caption content —
  and even that string never reaches Java's brand-facing surface since `BrandSafetyAiClient` only
  extracts a `code` field from the 4xx/5xx JSON body, not the `message`, and that `code` only ever
  reaches a server-side log line.
- **Batch blast radius confirmed:** one creator's brand-safety failure is caught inside
  `scoreCreator()` itself (first safety net) and again by `calculateScores()`'s per-creator
  try/catch (second safety net) — matches the `MetricsPollingJob` resilience convention exactly.
  Test coverage (`ScoreCalculationJobTest`, per Kavya's QA review §2/§4) already proves isolation.

## 5. HMAC/secret handling — PASS

- `BrandSafetyServiceTokenProperties.signingSecret` is registered in `SecretsStartupValidator`
  (`SecretsStartupValidator.java:44,53,73-74`) alongside every other credential surface (JWT
  access/refresh, Meera stream, internal-service-token HMAC) and is subject to the same three
  checks: **minimum 32 bytes**, **not the committed dev default**
  (`"dev-brand-safety-service-token-secret-change-in-production-min-32-chars"` is in
  `KNOWN_DEV_DEFAULTS`), and **distinctness** (the `seen`/`LinkedHashSet` de-dup check at lines
  89-96 flags it if it duplicates any other secret's value). Fails closed (throws
  `IllegalStateException`, aborts boot) in any non-`dev` `influora.env`; warns only in `dev` — same
  policy as every sibling secret, no special-casing or exemption for this one.
- This closes the loop on the ADR's binding condition #2 ("key gets boot-time protection... when
  JWKS lands") one layer early: the **current** HS256 secret already has that protection today: it
  is the future asymmetric **private key** that will need the equivalent treatment when Direction-2
  eventually moves to JWKS (tracked as task E-JWKS, not C3's problem).

---

## Findings (non-blocking)

### LOW-1 — `SCOPE_SERVICE` is shared across all Direction-2 endpoints, no per-endpoint scope granularity
A brand-safety-minted token (or any `SCOPE_SERVICE` token) is valid for `chat`, `analyze_site`,
`voice_transcribe`, `voice_speak`, and `brand_safety` alike (`service_token.py` `ENDPOINT_SCOPES`).
Not introduced by C3 — pre-existing design shared by every `SCOPE_SERVICE` issuer. Bounded by the
60-second hard TTL ceiling and the fact all these endpoints are equally internal/Spring-only.
**Recommendation (tracked, non-gating):** if Direction-2 ever grows more endpoints with materially
different sensitivity, revisit `ENDPOINT_SCOPES` granularity (e.g. `scope=brand_safety` instead of
a shared `service` scope) as part of the E-JWKS work, since that task already touches every
Direction-2 token issuer.

### LOW-2 — `findFirstByCreatorProfileIdAndRevokedFalse` javadoc could be misread as a scoping bug by a future reviewer
The javadoc is actually correct and thorough (see §2 above), but this is exactly the kind of
"looks like a workspace-isolation violation at a glance" pattern that invites an incorrect fix
later. **Recommendation:** add a one-line pointer comment at the `scoreCreator()` call site
(`ScoreCalculationJob.java` near line 209) cross-referencing the repository javadoc, so a future
reader auditing job-level workspace isolation doesn't have to chase into the repository interface
to find the reasoning. Matches Kavya's own LOW finding (QA review §7 "Workspace Resolution Could
Be More Explicit") — I concur with her recommendation, downgrading this from a security question
to a documentation nit since I've now independently verified the underlying design is sound.

---

## Files reviewed
- `influora-api/src/main/java/com/influora/integration/ai/BrandSafetyAiClient.java`
- `influora-api/src/main/java/com/influora/service/scoring/BrandSafetyScoreService.java`
- `influora-api/src/main/java/com/influora/service/integration/BrandSafetyServiceTokenService.java`
- `influora-api/src/main/java/com/influora/config/BrandSafetyServiceTokenProperties.java`
- `influora-api/src/main/java/com/influora/config/SecretsStartupValidator.java`
- `influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java`
- `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java` (workspace-isolation baseline comparison)
- `influora-api/src/main/java/com/influora/repository/MetaOAuthTokenRepository.java`
- `influora-ai/app/auth/service_token.py`
- `influora-ai/app/routes/brand_safety.py`
- `influora-ai/app/providers/claude.py` (error-path leak check)
- `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md` (auth strategy, already locked, not re-litigated)
- `wiki/errors/wave-c-task-c3-java-brandsafety-client-qa-review.md` (Kavya, APPROVED)
- `wiki/errors/caption-persistence-C1-security-review.md` (my own C1 sign-off, MED-1 condition being checked here)

## Verdict: SIGN-OFF
C3 ships. Caption egress is transient on both sides (MED-1 satisfied). Workspace isolation is
correct per-creator with one deliberately-scoped, well-reasoned deviation (creator-level table,
no cross-workspace data exposure possible). Service-token claims match Python's verifier exactly,
no over-broad grant. Failure paths fail safe: no stale data, no exception detail leak. Secret is
under full `SecretsStartupValidator` protection matching every other credential surface. LOW-1 and
LOW-2 are non-gating, tracked for the E-JWKS follow-up and a documentation nit respectively.

**Route to:** Meera for local build verification (Java-only change, no new migration — no
live-MySQL check warranted). No further Kabir gate before merge.

**Signed:** Kabir, Red-Team Lead
**Date:** 2026-07-07
