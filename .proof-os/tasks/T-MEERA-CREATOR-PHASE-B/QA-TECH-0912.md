# Fifteen technical answers: is Meera Phase B0 actually wired end to end? (T-MEERA-CREATOR-PHASE-B)

**For**: Kavya (QA Lead)
**From**: Priya (CTO)
**Date**: 2026-09-12
**Branch**: `feat/meera-creator-phase-b0` @ `c22b00e` in the worktree `C:\Users\Sage world\Downloads\New Influora Ai\influora-b0` (read-only; no code or test was changed)
**Sources**: every file cited below was opened and read at this HEAD, not grepped. Java: `service/meera/CreatorToolScopes.java`, `service/meera/MeeraContextService.java`, `service/meera/MeeraSessionService.java`, `service/meera/OnBehalfTokenService.java`, `web/CreatorMeeraToolController.java`, `web/dto/meera/CreatorToolDtos.java`, `web/dto/meera/MeeraContextDtos.java`, all four `service/meera/tool/creator/*Executor.java`, `service/rates/RateQuoteService.java`, `service/risk/{DealRiskService,Floors,DealValue}.java`, `service/risk/rules/BelowFloorRule.java`, `service/admin/CreatorAgentRateCalibrationService.java`, `service/DealService.java`, `service/AuditLogService.java`, `security/{AuthRateLimitFilter,OnBehalfAuthResolver,InternalServiceTokenFilter}.java`, `common/{Rendered,GlobalExceptionHandler,ApiResponse}.java`, `config/SecurityConfig.java`, `domain/entity/{Collaboration,CreatorProfile,CreatorBrief,CreatorAgentPreferences}.java`, `repository/CollaborationRepository.java`, the four `V20260910*` migrations. Python: `app/tools/loop.py`, `app/tools/creator_schemas.py`, `app/prompt/assembler.py`, `app/routes/chat.py`, `app/clients/spring.py`. Frontend: `src/lib/meera-api.ts`, `src/lib/api.ts`, `src/components/creator/meera/CreatorToolResultRenderer.tsx`, `src/components/creator/MeeraCopilotChat.tsx`, `src/pages/creator-chat.tsx`, `src/App.tsx`. Tests: `CreatorToolScopesTest`, `MeeraContextServiceTest`, `CreatorMeeraToolControllerTest`, `AuthRateLimitFilterCreatorToolBucketTest`, `DealRiskServiceEvaluateDealTest`, `DealServiceRisksTest`, `InfoBarrierTest`, `MeeraPhaseB0BootValidationTest`, `tests/tools/test_loop_creator_dispatch.py`, `tests/prompt/test_creator_context_drift.py`, `tests/security/test_info_barrier.py`, `CreatorToolResultRenderer.test.tsx`. One run: `mvn -o -f influora-api/pom.xml test -Dtest='CreatorToolScopesTest,MeeraContextServiceTest,CreatorMeeraToolControllerTest,AuthRateLimitFilterCreatorToolBucketTest'` → exit 0, 55 tests, 0 failures, 0 skipped (surefire reports under `influora-api/target/surefire-reports/`).

Given facts not re-derived here: four of six creator tools have Spring routes; 2988 backend tests with 25 Docker-gated skips, 913 python, 1260 frontend; Docker is unavailable so `ddl-auto=validate` has never run on this wave.

---

## 1. What artefact proves the JSON leaving `GetMyDealsExecutor` is the JSON `isGetMyDealsPayload` accepts?

**Answer: none exists, and the situation is worse than you described. Nothing in this repository ever serialises a `CreatorToolDtos` record to JSON — not in a test, not in a fixture, not anywhere. Your premise that `CreatorMeeraToolControllerTest` "fakes the model with MockMvc" is wrong in the direction that matters: it uses no MockMvc at all. It is a plain Mockito test that calls `controller.getMyDeals(JWT, BODY)` as a Java method and inspects the returned `ResponseEntity<ApiResponse<GetMyDealsResult>>` as an object. Jackson is never invoked. So the snake_case `@JsonProperty` names, `@JsonInclude(NON_NULL)`'s omission behaviour, and the Python/TS readers are all unasserted on the Java side.**

And there is a live contract defect visible from the reading alone.

`GetMyDealsExecutor.toSummary` can legitimately pass `null` for `brandName` (`GetMyDealsExecutor.java:138-143` — `brandName` starts null and stays null when the campaign row, its `workspaceId`, or the workspace row is missing; the javadoc at :133-137 says this is deliberate, chosen over `DealService#resolveCounterparty`'s `"Brand"` placeholder) and for `campaignTitle` (`:182`, `campaign != null ? campaign.getTitle() : null`). `DealSummary` is `@JsonInclude(NON_NULL)` (`CreatorToolDtos.java:28`), so both keys are **omitted from the wire**.

The TypeScript type declares both as **required, non-optional**:

```ts
// src/lib/meera-api.ts:412-432
export interface DealSummary {
  deal_id: string;
  brand_name: string;        // <- Java can omit this
  campaign_title: string;    // <- Java can omit this
  ...
  amount?: string;           // correctly optional
```

`tsc` therefore promises `MyDealsCard` a string it will not get, and `CreatorToolResultRenderer.tsx:173-176` renders `{deal.brand_name}` / `{deal.campaign_title}` — React renders `undefined` as nothing, so the creator gets a deal row with a blank brand line and no error anywhere. This is the same class as the F1/PHONE-0829 finding in memory: a TS type asserting a field the Java record never sends passes `tsc` and renders an empty state forever.

**Severity: MEDIUM.** Cosmetic in effect (a blank brand name), but it is the type system's only claim about this seam and it is false.

### Evidence

- `influora-api/src/test/java/com/influora/web/CreatorMeeraToolControllerTest.java:55-97` — `@ExtendWith(MockitoExtension.class)`, the controller is built with `new CreatorMeeraToolController(...)` and nine `@Mock`s; every test invokes handler methods directly (`:108-114`). No `MockMvc`, no `ObjectMapper`.
- A repo-wide search for tests referencing `CreatorToolDtos` returns ten files; the only one that also mentions `ObjectMapper`/`MockMvc` is `web/DealControllerTest.java`, and its own comment at `:87` states "no MockMvc harness in this codebase yet", with `:404` calling `controller.risks(principal, "deal1")` directly.
- `influora-ai/tests/tools/test_loop_creator_dispatch.py:70-79` — `_RecordingSpring` returns `{"deals": [{"id": "d-1", "status": "ACTIVE"}]}`. That is not the real shape: the real key is `deal_id`, not `id`. The fixture is both hand-written and wrong, and no test notices, because the loop passes `data` through opaquely (`loop.py:643`, `:649-656`).
- `src/components/creator/meera/CreatorToolResultRenderer.test.tsx:36,48-52` — fixtures are hand-typed object literals (`brand_name: 'Nykaa'`, `currency: 'INR'`).
- `isGetMyDealsPayload` is a one-field guard: `Array.isArray(d.deals)` (`src/lib/meera-api.ts:507-511`). Any object with a `deals` array passes.

### The artefact that would prove it (short of a live turn)

A golden-fixture contract in two halves, both cheap:

1. **Java half** — a test that runs `GetMyDealsExecutor.execute` against mocked repositories configured for the *degraded* cases (missing campaign, missing workspace, null `agreedRate`), serialises the result with the application's own `ObjectMapper` bean, and writes the JSON to a committed fixture file (e.g. `src/test/resources/contract/get_my_deals.degraded.json`). It must assert on absent keys, not just present ones.
2. **Frontend half** — a vitest case that imports that same file, asserts `isGetMyDealsPayload` accepts it, and renders `MyDealsCard` against it.

A schema-diff CI step (like the existing `.github/workflows/schema-check.yml` does for the brand tool names) comparing the `@JsonProperty` set and primitive-vs-boxed-ness of each `CreatorToolDtos` record against the corresponding `src/lib/meera-api.ts` interface's required/optional markers would catch the `brand_name` class of defect statically. Neither exists today.

---

## 2. If a stale or wider `tools_enabled` offers `get_brief`, what does Meera say, and does the 404 leave any audit row?

**Answer: Meera says whatever the model improvises around the literal string "The requested resource was not found", and the creator is separately shown that sentence in a red error box. `loop.py`'s `tool_not_routable` degrade never fires, because a route IS present in the map. And no — the 404 leaves no audit row of any kind. You are right about the audit hole, and right that the degrade is the wrong one.**

### The path, step by step

1. `CREATOR_TOOL_TO_SPRING_PATH` is a comprehension over all six names (`creator_schemas.py:59-61`), so `CREATOR_TOOL_TO_SPRING_PATH["get_brief"] == "/internal/meera/creator/get_brief"`.
2. `loop.py:530` finds that path, so the `if not path:` degrade at `:531-551` — the one that yields `tool_not_routable` — is skipped entirely. Its comment at `:521-529` describes exactly this scenario ("a schema shipped ahead of its endpoint") but the condition cannot fire for a creator tool, because the map is built from names, not from routes.
3. The forward happens. `AuthRateLimitFilter` passes (`creator-tool` bucket, `:144`, `:562-563`). `InternalServiceTokenFilter` verifies the service token and sets the security context; `SecurityConfig`'s `.anyRequest().authenticated()` (`SecurityConfig.java:282-283`) is satisfied, so the request reaches `DispatcherServlet`.
4. No `@PostMapping("/get_brief")` exists on `CreatorMeeraToolController` (the four are at `:97, :104, :112, :120`). Spring raises `NoResourceFoundException`, caught by `GlobalExceptionHandler.handleNoResourceFound` (`:134-138`), which returns **404** with the standard envelope `{"success":false,"error":{"code":"NOT_FOUND","message":"The requested resource was not found"}}`.
5. `app/clients/spring.py:180-188` maps `status_code >= 400` to `SpringCallError(404, "NOT_FOUND", "The requested resource was not found")`. (The envelope shape matters: because `GlobalExceptionHandler` returns an `ApiResponse` rather than Spring Boot's default body — whose `"error"` is a bare string — `parsed.get("error", {}).get("code", ...)` does not blow up. With Boot's default body this line would raise `AttributeError`, which is not a `SpringCallError` and would kill the SSE stream. Worth knowing; not reachable today.)
6. `loop.py:584-636`: `is_money_tool("get_brief")` is false, so the money-decline branch is skipped and the general path runs. `result_payload = {"error": "NOT_FOUND", "message": "The requested resource was not found"}` is appended as an `is_error` `tool_result` **and the loop continues** — Claude gets another turn and narrates something of its own choosing around the error. There is no fixed sentence; what Meera says is non-deterministic.
7. The creator also sees the raw server sentence. `MeeraCopilotChat.tsx:352` filters on `isCreatorToolName(event.name)` — and `get_brief` **is** in `CREATOR_TOOL_NAMES` (`meera-api.ts:545-552`) — so the event is kept; `:364` sets `errorMessage: toolErrorMessage(event.data)`, which returns `d.message` (`:74-81`) = "The requested resource was not found"; `CreatorToolResultRenderer.tsx:484-495` renders it in a `bg-destructive` box.

### The audit row

**Zero rows.** `CreatorMeeraToolController.handleRead` is the only writer of creator-tool audit rows (`:148-213`, plus its `recordRejected` helpers), and the controller is never entered. `InternalServiceTokenFilter` writes a row only on **rejection** (`:150-160` `reject(...)` → `auditLogService.recordAuthRejection`); a token that verifies successfully writes nothing. No filter or interceptor writes a per-request row for `/internal/**`.

So the only trace is: the influora-api container's HTTP access log (if enabled), and `loop.py`'s own logging — which on this branch is not even a `log_event`, just the generic `SpringCallError` surfacing with no dedicated warning. The `logger.error("no Spring route for known tool %s ...")` at `loop.py:532` that *would* have named the problem is on the unreachable branch.

**Severity: MEDIUM, and it is latent rather than live** — `tools_enabled` cannot contain `get_brief` today (see Q3), so this needs a deploy skew or a hand-edited row to reach. The fix is one line in `creator_schemas.py`: build `CREATOR_TOOL_TO_SPRING_PATH` from a wired-names tuple rather than from `CREATOR_TOOL_NAMES`, so an unrouted name hits `tool_not_routable` locally and the model gets a degrade it can narrate honestly.

---

## 3. How do we tell "no tools granted" from "the tool surface is down" from the outside?

**Answer: the "tool surface is down" case is cleanly distinguishable — it is an HTTP 503 `creator_context_unavailable` and no turn runs at all. Your premise that `chat.py` can "fail open" on the creator path is wrong: it fails closed, deliberately and on every failure mode. But the other half of your question exposes something sharper than you asked: in B0 there IS no "this creator has no tools granted" state. `tools_enabled` is the same four names for every creator, at every approval level, represented or not. So an empty `tools_enabled` in production can only mean a version skew — and nothing logs, counts, or reports it.**

### `tools_enabled` is a constant in B0

`CreatorToolScopes.toolNamesForLevel` intersects `WIRED_TOOL_NAMES` with the creator's scope (`:140-145`). `WIRED_TOOL_NAMES` is `List.of("get_my_deals", "estimate_my_rate", "get_my_metrics", "check_deal_risks")` (`:93-94`). Every scope string contains all four:

- `SCOPE_LEVEL_0` (`:30-32`) — contains all four.
- `SCOPE_LEVEL_1` = level 0 + `send_routine_reply` (`:35`) — contains all four.
- `SCOPE_LEVEL_2` = `SCOPE_LEVEL_1` (`:57`) — contains all four.
- `SCOPE_REPRESENTED` (`:65-67`) — `get_my_deals get_brief estimate_my_rate get_my_metrics check_deal_risks rank_open_campaigns` — contains all four.
- An out-of-range level falls to `SCOPE_LEVEL_0` (`:118-127`) — contains all four.

The intersection is therefore always exactly those four names. `approval_level`, `represented` and `negotiation_holdout` have **zero observable effect** on the offered tool set in B0. `MeeraContextServiceTest` asserts precisely this and passes (`:338` `assertEquals(WIRED_CREATOR_TOOLS, ...)` and `:389` the same assertion for a represented creator) — the test documents the behaviour rather than catching it.

### The three states, and what each looks like from outside

| State | Outside signal | Distinguishable? |
|---|---|---|
| Context fetch failed (timeout, 5xx, network, unparseable, empty body) | HTTP **503**, body code `creator_context_unavailable`, message "Meera can't reach your profile right now." No turn runs; the spend reservation is released. | Yes, cleanly |
| Context fetch 401/403 from Spring | HTTP **403**, code `context_unauthorized` | Yes |
| Spring returned a non-CREATOR audience | HTTP **403**, code `audience_mismatch` | Yes |
| Fetch succeeded, `tools_enabled` absent or `[]` | Turn runs normally in warn-only mode: Block A says `Available tools: none (warn-only mode)`, Block B omits the tools line, `tools = []`, and any tool the model names is refused locally as `tool_not_offered`. **No log event, no metric, no error.** | **No** |
| "This creator has no tools granted" | Unreachable in B0 | n/a |

So the answer to your question as asked: the surface being down is a 503 you can alert on; "no tools granted" does not exist; and the only remaining way to reach warn-only mode is an influora-api older than commit `e54c071`/`d851c87` talking to a B0 influora-ai — a state that produces a silently degraded product and emits nothing at all.

**Severity: LOW as a defect, MEDIUM as an observability gap.** `assemble_prompt` should `log_event(... "creator_tools_empty" ...)` when `audience == "CREATOR"` and `enabled_names == []`. That is the missing artefact.

### Evidence

- `influora-ai/app/routes/chat.py:277-334` — `_fetch_creator_context`. Docstring `:288-294`: "Unlike `_fetch_brand_context`, this FAILS CLOSED on EVERY failure". `except SpringCallError` → `:311-313`; `except Exception` (network/timeout) → `:314-320`; empty body → `:322-323`; audience disagreement → `:325-332`. Every branch returns `(None, code)`.
- `chat.py:490-511` — `creator_context is None` releases the spend hold and returns 403 (`audience_mismatch`, `context_unauthorized`) or 503 (`creator_context_unavailable`). Contrast `_fetch_brand_context:198-213`, which sets `context_data = {}` and carries on — the brand path is the one that fails open.
- `influora-ai/app/prompt/assembler.py:953-974` — the CREATOR branch. `enabled = creator.get("tools_enabled")`; `enabled_names = [...] if isinstance(enabled, list) else []` (`:970-971`); `get_creator_tool_schemas(enabled_names)` (`:972`). No logging on the empty path.
- `creator_schemas.py:391-394` — `if not tools_enabled: return []`. `None` and `[]` both yield no tools; the permissive read has its own name, `all_creator_tool_schemas` (`:357-370`).
- `assembler.py:522-527` — `Available tools: none (warn-only mode)` when `names` is empty. `:758-762` — the Block B tools line is simply omitted.
- `MeeraContextService.java:276-281, 327` — the single production call: `CreatorToolScopes.toolNamesForLevel(approvalLevel, represented, negotiationHoldout)`, with all three values hoisted from the one `prefs` read at `:226-227`.

---

## 4. Of the 32 components `CreatorContextResponse` carries, which are actually non-null for a real consenting creator, and which render as `not available`?

**Answer below as a table. Two corrections to your premise first. (a) `test_creator_context_drift.py` is not only a grep — it has three tests, and the third (`test_every_java_field_changes_the_rendered_creator_block`, `:103`) is behavioural: for each Java field it renders Block B with and without a distinctive value and asserts the output differs. (b) Your underlying point still stands completely: nothing on either side proves the *Java* side populates a field for a real creator. Every Java-side test of `assembleCreatorContext` stubs the repositories.**

A "real consenting creator" necessarily has a `creator_agent_preferences` row (consent is stored on it — `prefs.isConsentAccepted()`, `MeeraContextService.java:308`), so `prefs != null` throughout. That removes the whole `prefs == null` column of ternaries at `:292-327`.

### Always non-null for a consenting creator (20)

| Field | Construction site | Note |
|---|---|---|
| `workspace_id` | `:284` | the creator's `users.id` |
| `audience` | `:285` | constant `"CREATOR"` |
| `display_name` | `:286` | `creator_profiles.display_name` is `NOT NULL` (`CreatorProfile.java:29-30`) |
| `first_name` | `:287` | `firstNameOf(displayName)` (`:431-438`), non-null because its input is |
| `tier` | `:289` | override or `CreatorTiers.derive(...)`, which always returns a string |
| `categories` | `:290` | `JsonLists.stringListFromJson` returns an empty list, never null — renders `Categories: not set` when empty (`assembler.py:669-673`) |
| `creator_language` | `:291` | prefs value or `firstLanguageOrDefault` (`:426-429`) |
| `brand_tone` | `:292` | defaults to `TONE_FRIENDLY` |
| `floors` | `:293` | a `LinkedHashMap`, possibly **empty** — see below |
| `metrics_summary` | `:294` | a map, possibly **empty** — see below |
| `deals_summary` | `:295` | always three keys (`:419-423`) |
| `approval_level` | `:296` | `int` primitive |
| `represented` | `:297` | `boolean` primitive |
| `excluded_categories`, `blocked_brands` | `:299-300` | empty lists, never null |
| `working_hours_timezone` | `:303` | `DEFAULT_WORKING_HOURS_TIMEZONE` |
| `working_days` | `:304` | empty list, never null |
| `floor_currency` | `:306` | `DEFAULT_FLOOR_CURRENCY` |
| `identity` | `:307` | always both booleans (`:252-254`) |
| `consent_accepted` | `:308` | `boolean` primitive |
| `negotiation_holdout` | `:311` | `boolean` primitive |
| `rate_card_shareable` | `:316` | `boolean` primitive |
| `approved_draft_count` | `:317` | `int` primitive |
| `tools_enabled` | `:327` | always the same four names (Q3) |

### Nullable, and what the creator's Meera actually sees

| Field | Site | Rendered as |
|---|---|---|
| `city` | `:288` — `creator_profiles.city` is nullable (`CreatorProfile.java:45`) | `not available` via `_creator_str` (`assembler.py:543-547, 668`) |
| `agency_name` | `:298` | line omitted unless `represented`; falls back to the nameless "REPRESENTED by an agency" (`assembler.py:715-730`) |
| `working_hours_start` / `working_hours_end` | `:301-302` | handled in `_creator_rules_lines` |
| `weekly_sponsored_limit` | `:305` | `Weekly sponsored limit: not set` (`assembler.py:631`) |
| `consent_version` | `:309` | set for a creator who has consented; never rendered (`CREATOR_CONTEXT_FIELDS_NOT_RENDERED`, `assembler.py:208-223`) |
| `ai_monthly_cap_usd` | `:310` | null unless an admin set an override; read by `spend_tracker`, never rendered |
| `holdout_until` | `:315` | null unless held out; `Rendered.date(null, ...)` returns null (`Rendered.java:68-73`) |

### Three findings worth acting on

1. **An empty `floors` map makes the private-floors line vanish entirely.** `assembler.py:695-707`: the line renders only when `isinstance(floors, dict) and floors`, and the `elif currency != "INR"` fallback cannot fire for an INR creator. So a creator who has never set a floor gets **no floor line at all** in Block B — not "not set". Downstream that is consistent (`RateQuoteService.floorTotal` returns 0 and `BelowFloorRule` stays silent, `BelowFloorRule.java:31-35`), but Meera is given no language for "you have not told me your minimum yet", which is exactly the creator B0 most needs to prompt.
2. **An empty `metrics_summary` is the honest path, and it works.** `buildMetricsSummary` (`:379-406`) omits `followers` outright when there is neither a verified `CreatorMetric` nor a positive self-reported total, so `assembler.py:680-681`'s `Metrics: Instagram not connected yet (no verified numbers)` branch fires rather than a fabricated zero. This is the Q6/Q9.6 gate fix and it is intact.
3. **`rate_card` is migrated but never reaches Meera.** `V20260910100000__meera_phase_b_prefs.sql:36` adds `rate_card_json`, the entity carries it (`CreatorAgentPreferences.java:162, 334`), and it is surfaced on the creator's own settings API (`CreatorAgentPreferencesService.java:384` → `CreatorAgentDtos`), but there is **no `rate_card` component on `CreatorContextResponse`** — `MeeraContextDtos.java:166-...` has 32 components and none of them is it. `rate_card` appears in the Python brand-*forbidden* set (`assembler.py:119`) but not in `CREATOR_CONTEXT_PAYLOAD_FIELDS`. So Meera cannot see the creator's own asking prices in B0. Whether that is intended (B6) or an omission is a product call, not a code one — but the migration and the context contract currently disagree about whether this data exists.

**NOT VERIFIED**: that any of these 32 fields is non-null for a *real* creator in a *real* database. Every assertion above is derived from reading the construction site and the column nullability. The artefact that would prove it: a Testcontainers test that inserts one `users` + `creator_profiles` + `creator_agent_preferences` row set via Flyway-migrated SQL, calls `MeeraContextService.assemble(userId, "CREATOR")`, serialises the response, and asserts which keys are present. That test would also be the only thing in the tree that exercises `assembleCreatorContext` against real JPA.

---

## 5. On a creator turn where the model emits `get_brief`, what is our record that the refusal happened?

**Answer: a single unstructured log line in the influora-ai container's stdout, plus a transient SSE event the creator sees as a red error box. Nothing is persisted anywhere, and the log line is not a structured event — it has no `request_id` and no `workspace_id`, so it cannot be correlated with the turn it belongs to. You are right: there is no Spring audit row and no durable record.**

There is also a correction to the premise: `get_brief` is in `SCOPE_LEVEL_0` but it is *not* in `tools_enabled`, because `toolNamesForLevel` intersects the scope with `WIRED_TOOL_NAMES` (`CreatorToolScopes.java:140-145`) and `get_brief` is absent from the wired list (`:93-94`). So `get_creator_tool_schemas` never returns the `get_brief` schema, `offered_tool_names` never contains it, and the refusal is the `tool_not_offered` branch — which is the correct, fail-closed outcome. The defect is purely in what it records.

### What exists

```python
# influora-ai/app/tools/loop.py:386-410
if tool_name not in offered_tool_names and not is_money_tool(tool_name):
    logger.warning(
        "rejected tool call not offered this turn: %s (offered=%s)",
        tool_name, sorted(offered_tool_names),
    )
    result_payload = {"error": "tool_not_offered", "message": ...}
    ...
    continue
```

- `logger.warning`, not `log_event`. Compare `chat.py:306-310`, which uses `log_event(logger, logging.WARNING, "meera_creator_context_fetch_failed", workspace_id=..., request_id=..., fields={...})`. The refusal line carries neither identifier, so in aggregate it tells you a refusal happened *somewhere* and nothing more.
- The `tool_result` event (`:404-409`) reaches the browser. `MeeraCopilotChat.tsx:352` keeps it (`get_brief` passes `isCreatorToolName`), `:364` derives `errorMessage = "tool_not_offered"` — `toolErrorMessage` prefers `d.message`, which here is the full sentence `tool 'get_brief' was not offered on this turn` — and `CreatorToolResultRenderer.tsx:484-495` renders that internal string in a destructive box. The creator is shown implementation vocabulary.
- No socket opens, so no Spring row: `test_loop_creator_dispatch.py:341` asserts `spring.calls == []`.
- `loop.py` has a working write-back channel it does not use here — `spring.log_interaction(...)` at `:439-447` for `present_options`. A refusal write-back would be one call on the same path.

**Severity: LOW-MEDIUM.** The security boundary holds; the instrumentation does not. Two fixes: promote the line to a `log_event` with `workspace_id`/`request_id`, and map `tool_not_offered` to a creator-facing sentence in `CreatorToolResultRenderer` rather than echoing the internal code.

### Evidence

- `loop.py:189-191` (`offered_tool_names` built from `tools`), `:170-188` (the design note), `:354-368` (`is_known_tool` first), `:370-410` (the per-turn gate and its one money-tool exemption).
- `tests/tools/test_loop_creator_dispatch.py:328-343` and `:346-357` — both assert the refusal and `spring.calls == []`; neither asserts anything about a record.

---

## 6. Which brand-readable payload can transitively reach a `PackageQuote` today, and which test goes red the day someone adds one?

**Answer: none today — and no test goes red. The second half is the real finding: the barrier you are relying on does not cover this.**

### No path exists today

`PackageQuote` appears on exactly two wire-bound records (`CreatorToolDtos.java`):

- `EstimateMyRateResult` (`:94`), returned only by `POST /internal/meera/creator/estimate_my_rate`, which is gated by `requireCreatorPrincipal` — a BRAND-audience token is refused with `AUDIENCE_PRINCIPAL_MISMATCH` (`CreatorMeeraToolController.java:240-252`).
- `GetBriefResult` (`:130-138`), which has **no route at all**.

Everything else that touches the type is server-internal and never serialised: `RiskContext.quote` (`RiskContext.java:61`), read only by `Floors.totalFor` (`:38-44`) and `DealValue` (`:53`). I read every reference to `PackageQuote` under `influora-api/src/main/java` — there are fourteen and none is on a brand-facing DTO.

`creator_briefs.quote_json` is stored whole and un-stripped, exactly as the migration says (`V20260910100100__creator_briefs.sql:30-36`), but nothing reads it back into any response: `CreatorBriefService` does not exist yet, and `CreatorBrief.getQuoteJson()` has no reader in `src/main`. The B1 `createSecureLink` that is supposed to build a stripped `package_json` also does not exist. So the floor is at rest on disk with no read path — which is the only reason the deferred strip is safe.

### Nothing would catch the addition

`InfoBarrierTest` bans exactly one thing: an `import` of `CreatorAgentPreferencesRepository` from outside a three-name allow-list (`InfoBarrierTest.java:55-79`). It says nothing about `CreatorToolDtos.PackageQuote`, nothing about `floor_total`, and nothing about `creator_briefs`. A brand DTO could declare `@JsonProperty("quote") PackageQuote quote` and the test stays green — it imports `CreatorToolDtos`, not the repository.

`InfoBarrierRuntimeTest` is narrower still: its assertions are about the brand *context* payload and the `RATE_QUOTE_ISSUED` audit detail (`:151 brandContextNeverExposesCreatorFloor`, `:223`, `:281`, `:340 auditDetailCarriesNoFloor`). It does not scan other DTOs. The Python-side `tests/security/test_info_barrier.py:139-201` tests the brand Block B, not HTTP payloads.

**Severity: HIGH as a missing control** (the data is one field away from a brand response and nothing stands in the way), **LOW as a live defect** (no such field exists).

### The gate I want before Wave 4

A source scan in the same style as `InfoBarrierTest`, asserting that the identifier `PackageQuote` and the string literals `floor_total` / `floorTotal` / `anchor` appear only inside an allow-list of files (`CreatorToolDtos`, `RateQuoteService`, `Floors`, `DealValue`, `RiskContext`, `BelowFloorRule`, the creator executors), plus a runtime assertion that serialising every DTO reachable from a brand-authenticated controller produces no `floor_total` key. Wave 4 adds `CreatorBriefService` and the `get_brief` route; that is the change most likely to plumb a `GetBriefResult` somewhere convenient.

---

## 7. Does `quoteForRisk` writing no `RATE_QUOTE_ISSUED` row make the calibration report under-count, and is the ALLOWED tool-call row a substitute?

**Answer: yes to a narrow but real under-count, no to the substitute — and one correction: a creator cannot obtain an *anchor* through `check_deal_risks`. She can obtain a floor. No risk rule reads `quote.anchor()`; the only two readers of `ctx.quote()` in the whole `service/risk` tree are `Floors.totalFor` (floor only) and `DealValue` (the offer value).**

### What the creator does get from `check_deal_risks`, unaudited

`BelowFloorRule` renders the floor three times into creator-facing text: `detail` = `"Offer is 8,000 against your floor of 12,000."`, `action` = `"Counter at your floor of 12,000, unless..."`, and `data["floor_total"]` (`BelowFloorRule.java:60-74`). That number comes from `Floors.totalFor(ctx)` → `quote.floorTotalValue()` (`Floors.java:38-44`) → `RateQuoteService.floorTotal` inside `compute` (`:315`, `:338-339`) — reached via `quoteForRisk`, which passes `auditContext = null` (`:292`) so `compute`'s `if (auditContext != null)` guard at `:352` skips `recordQuoteIssued`.

So a creator can be told "counter at 12,000" with no pricing row anywhere.

### The under-count is real but narrow

`quotedTotalsByTier` reads **only** `RATE_QUOTE_ISSUED` rows and only their `tier` and `total` keys (`CreatorAgentRateCalibrationService.java:245-269`). The `quoted_*` column is therefore a median of *quote-card totals* — which is its stated purpose (`:42-45`: "`quoted_*` is what Meera has been saying… divergence between the second and third is the anchoring signal"). Measured against its own definition it does not under-count.

Measured against what it is *used for*, it does. §14.5.c metric 4 gates the start of Phase B1 on this report, and the anchoring signal is meant to answer "is Meera anchoring the cohort under market". A `BELOW_FLOOR` flag that tells a creator to counter at a specific number **is** an anchor in product terms, and it is invisible to the report: the resulting close lands in `realised_*` with no matching row in `quoted_*`. The more `check_deal_risks` is used relative to `estimate_my_rate`, the more the anchoring signal is understated. `quoteForRisk`'s javadoc reasoning (`:259-263` — "auditing those as issued quotes would flood the series with quotes nobody was ever shown") is correct for a deal page the creator merely opened, and wrong for a `BELOW_FLOOR` flag she was actually shown.

### The ALLOWED tool-call row is not a substitute, for two reasons

1. **It carries no numbers.** `AuditLogService.recordToolCall` persists `workspaceId`, `toolName`, `toolTier`, `outcome`, `reasonCode`, `idempotencyKey`, `serverAmount`, `detailJson` (`:44-67`), and `CreatorMeeraToolController` calls it with `serverAmount = null` and `detail = Map.of()` (`:202-210`). There is no tier and no total to aggregate.
2. **It is keyed in a different column.** `recordToolCall` writes the creator's `users.id` into **`workspaceId`** (`:56`, via `CreatorMeeraToolController:203` passing `ctx.userId()`); `recordCreatorEvent` — the `RATE_QUOTE_ISSUED` writer — leaves `workspaceId` null and writes the creator into **`actorId`** (`:141-152`, called from `RateQuoteService:918-922` with `profile.getUserId()`). The two series cannot be joined on one column, so even a per-creator count of `check_deal_risks` calls cannot be lined up against her quote history without a UNION over two columns.

**Severity: MEDIUM.** The minimal fix that preserves the stated design: have `BelowFloorRule` (or `DealRiskService.evaluate` when a `BELOW_FLOOR` flag survives) write a distinct `RATE_FLOOR_SHOWN` creator event carrying `tier` and `floor_total`, and add it as a fourth column to the report. Do not simply pass a non-null `auditContext` into `quoteForRisk` — that reintroduces exactly the flood the javadoc rules out.

---

## 8. Does the `creator-tool` bucket's degrade collapse the platform into one 60/window bucket, and what triggers it in production?

**Answer: yes, the collapse is real and reachable — but only through one of your two conditions. A null `onBehalfTokenService` cannot happen in production. The verify-failure budget can, and the most likely trigger is the platform's own expired tokens, not an attacker.**

### Condition 1 (null token service): not reachable in production

`AuthRateLimitFilter` is a `@Component` with a single constructor taking `JwtService` and `OnBehalfTokenService` (`:91-92`, `:185-190`), and `OnBehalfTokenService` is a `@Service` (`OnBehalfTokenService.java:40-41`). Spring constructor-injects the real bean; there is no `@Autowired(required=false)`, no `@Nullable`, and no `new AuthRateLimitFilter(...)` anywhere in `src/main`. The field's javadoc at `:177-182` says "Nullable, exactly like `jwtService`: the unit tests for other buckets construct this filter with nulls" — that is a test-only path. **You were half right; this half is not a live risk.**

### Condition 2 (verify-failure budget): reachable, and the collapse is exactly as you describe

```java
// AuthRateLimitFilter.java:785-789 (inside extractOnBehalfSubject)
if (verifyBudgetExhausted(request)) {
    return null;                       // -> rateLimitKey falls through to clientIp
}
```

- `rateLimitKey` returns `clientIp(request) + "|creator-tool"` whenever `extractOnBehalfSubject` yields null (`:660-672`).
- `clientIp` is `request.getRemoteAddr()` (`:1003-1005`), which for server-to-server `/internal/**` traffic is the single influora-ai container address. `creatorToolLimit` defaults to **60** per window (`:304-305`), `windowSeconds` to **60** (`:354-355`).
- `verifyBudgetExhausted` is charged per **source address** with budget **20** per window (`:325-326`, `:811-820`, `:842-844`), and — this is the load-bearing detail — the check sits *before* the signature verification for **every** request from that address, valid or not. Once influora-ai's own address has spent its 20 failures inside a 60-second window, **valid** tokens also return null and every creator on the platform shares one 60-requests-per-60-seconds bucket.

### What triggers it in production

1. **Expired on-behalf tokens.** `OnBehalfTokenService.MAX_TTL_SECONDS = 120` (`:43`). A creator turn mints the token at send time (`MeeraSessionService.java:427-429`), then the browser opens its own SSE connection, then Python fetches context, then up to six loop iterations each make a model call before any tool forward. A turn that takes longer than 120 seconds presents an expired token; `onBehalfTokenService.verify` throws, `chargeVerifyFailure` fires (`:800-803`). Twenty such turns in one minute is not a flood — it is a slow afternoon with a slow model.
2. **Signing-key rotation.** The budget's own javadoc names this: "20 leaves room for the brief overlap during a JWKS key rotation without tripping" (`:313-318`). During a rolling restart with a new signing secret, every in-flight token minted by an old instance fails on a new one. At any real concurrency 20 failures in 60 seconds is trivially exceeded, and the reward for exceeding it is that every creator is throttled together.
3. **A creator's browser replaying a stale token** after a tab sleep.

**Severity: MEDIUM (availability).** Not a security hole — no data crosses — but it inverts the exact property this bucket exists to provide, and it inverts it for *everyone* in response to a fault in the platform, not in a creator.

### What is NOT tested

`AuthRateLimitFilterCreatorToolBucketTest` (14 tests, all passing at this HEAD) covers the flood case (`:218-232`), the quarantined-attacker case (`:238-254`), and "valid traffic never spends the budget" (`:260-271`). It does **not** cover the **mixed** case: an address that presents mostly valid tokens and 20 invalid ones, then a *valid* token from a different creator. That is the production shape, and it is the only one that matters. **NOT VERIFIED by test**; verified by reading `:787-789` — the budget check is unconditional on the pre-crypto path and does not distinguish who is asking.

The artefact that would prove it: a case in that class that charges 20 failures with wrong-signature tokens, then asserts that `tokenFor(CREATOR_B)` — a fresh, valid token for a creator with an untouched window — lands in the IP-keyed bucket (observable as a 429 after the shared 60, or by asserting the verification count stays flat).

The mitigation I would take before Wave 4: exempt a token that fails on **expiry alone** from the budget (an `ExpiredJwtException` is a clock problem, not an attack), and keep charging genuine signature failures.

---

## 9. If approval level or `represented` changes between the two reads on one turn, which wins, and does anything detect the disagreement?

**Answer: the token's scope wins as the hard gate, `tools_enabled` wins as the offer, so in practice the narrower of the two wins and the creator loses the tool either way. Nothing detects the disagreement. But in B0 the race has no observable effect at all, because both readings always produce the same four tool names (Q3).**

### The two reads

| | Site | Source |
|---|---|---|
| Scope claim | `MeeraSessionService.java:422-429` — `creatorAgentPreferencesService.getOrCreatePreferences(userId)` then `CreatorToolScopes.scopeFor(prefs.approvalLevel(), prefs.represented())` | a service read that also *creates* the row if absent |
| `tools_enabled` | `MeeraContextService.java:226-227` then `:327` — `creatorAgentPreferencesRepository.findByCreatorId(profile.getId())` | a direct repository read |

They are separated by a full network round trip and a browser: the scope is minted when the creator hits send, and the context is fetched when influora-ai starts the turn. Different transactions, different instants, no shared snapshot.

### Who wins

- If the **scope** is wider than `tools_enabled`: the tool is not in `tools` → not in `offered_tool_names` → refused locally as `tool_not_offered` (`loop.py:386-410`). The narrower read wins.
- If **`tools_enabled`** is wider than the scope: the tool is offered, the model calls it, the forward reaches Spring, and `OnBehalfAuthResolver.requireScope` refuses with `ON_BEHALF_SCOPE_INSUFFICIENT` (`:212-222`). The narrower read wins again — at the cost of a wasted turn, a 403, and a `TOOL_CALL_REJECTED` audit row (`CreatorMeeraToolController:172-177`).

So the system fails closed in both directions, which is the right direction. What it does not do is notice.

### Nothing detects it

There is no correlation: `requireScope` compares the requested tool against the claim string and nothing else (`:212-215`); it never sees `tools_enabled`. The audit row for the mismatch case carries `reasonCode = "ON_BEHALF_SCOPE_INSUFFICIENT"` and `detail = {"stage": "on_behalf_resolve", "requestedWorkspaceIdVerified": false}` — indistinguishable from a genuine scope violation or a probe. A monitoring query cannot separate "this creator's level changed mid-turn" from "something forged a scope".

The only thing that *is* asserted is the consistency of the **three flags within one payload**: `MeeraContextServiceTest:366-372` re-computes `toolNamesForLevel(ctx.approvalLevel(), ctx.represented(), ctx.negotiationHoldout())` and asserts it equals `ctx.toolsEnabled()`. That is what the hoisted locals at `MeeraContextService:276-281` exist to guarantee, and it holds. It says nothing about the *other* read, in the other service, at the other time.

### Why it is inert today

`scopeFor` and `toolNamesForLevel` both funnel through the scope strings, and every scope string contains all four `WIRED_TOOL_NAMES` (enumerated in Q3). A creator who is promoted 0→1, demoted 1→0, or flipped `represented` between the two reads gets the identical four tools and the identical successful dispatch. There is no reachable disagreement in B0.

**Severity: safe today, fragile. The first asymmetric wave breaks it.** `draft_reply` (Wave 4) is in `SCOPE_LEVEL_0` but **not** in `SCOPE_REPRESENTED` (`CreatorToolScopes.java:65-67`) — so the moment `draft_reply` joins `WIRED_TOOL_NAMES`, flipping `represented` between the two reads becomes observable: the creator either gets a tool her scope refuses, or loses one her scope grants, with a wasted turn and an audit row that reads like an attack. The fix is to carry the minted scope into the context request and have `assembleCreatorContext` intersect against it (or simply return `tools_enabled` derived from the *token's* claim), so one reading governs the whole turn.

---

## 10. Which of `PackageQuote`'s 21 components can `compute` omit, and does a quote with no `lines`, no `total` and no `currency` render a silently empty card?

**Answer: seven can be omitted, and `compute` cannot produce the shape you describe — `lines`, `total`, `floor_total` and `currency` are unconditionally populated. But your second question is still a yes, and for a reason worse than the one you suspected: `isEstimateMyRatePayload` admits *any* object as a quote, so if that shape ever does arrive — a DTO change, a proxy rewrite, a 200 with a different body — the card renders empty with no error. The guard is the defect, not the producer.**

### What `compute` can legitimately omit

`compute` (`RateQuoteService.java:300-357`) passes all 21 arguments; `@JsonInclude(NON_NULL)` (`CreatorToolDtos.java:69`) then drops the null ones.

| Omittable | Why |
|---|---|
| `anchor`, `anchor_value` | `anchor(basis, total, prefs)` returns null under a negotiation holdout and on the provenance branch §14.1.e excludes (`:318`, `:336-337`) |
| `range_min`, `range_max` | `UnitBasis.rangeMin/rangeMax` are null on the own-history branch (`:433-434`) and the blended branch (`:447-453`); only the benchmark branch carries a range. `scaleToPackage(null, …)` → null → `Rendered.money(null, …)` → null (`Rendered.java:53-56`) |
| `recommended_move` | `recommendedMove` returns null when there is no brand budget (`:321`, `:820-827`) |
| `scope_down_offer` | null unless the move is `SCOPE_DOWN` (`:322-325`) |
| `withheld_reason` | `compute` always passes null (`:350`) |

**Never omittable**: `lines` and `add_ons` are lists from `renderMoney`, empty at worst — and `NON_NULL` does not drop an empty list, so they arrive as `[]`, not absent (the TS comments at `api.ts:6823-6826` are slightly wrong about this; harmless, both readers use `?? []`). `bundle_discount`/`_value` and `total`/`_value` come from non-null `BigDecimal` arithmetic (`:756`, `:785`). `floor_total`/`_value` come from `floorTotal`, which returns `scale(ZERO)` at worst (`:406-413`). `currency` comes from `currencyOf`, which defaults to `DEFAULT_CURRENCY` (`:1009-1014`). `payment_schedule` and `provenance` are constants/non-null. `revision_rounds`, `provenance_sample_size` and `withheld` are Java **primitives** — `NON_NULL` structurally cannot omit them; they always arrive as `2`, a number, and `false`.

The TypeScript type is correctly calibrated against this (`api.ts:6822-6860`) — `lines?`, `add_ons?`, `anchor?`, `range_min?`, `range_max?`, `recommended_move?`, `scope_down_offer?`, `withheld_reason?` optional; the rest required. `range_min`'s comment at `:6837-6844` records that it was corrected from required to optional for exactly this reason. This is the one DTO⇄type pair in B0 that is right.

### The card does render empty

`isEstimateMyRatePayload` is `!!d.quote && typeof d.quote === 'object'` (`meera-api.ts:519-523`). `{ quote: {} }` passes. Trace `PackageQuoteCard` (`CreatorToolResultRenderer.tsx:309-443`) with an empty quote:

- `lines = quote.lines ?? []` → no line list rendered (`:311`, `:347-375`)
- `quote.bundle_discount_value > 0` → `undefined > 0` is `false`, so no discount row (`:377`) — safe by accident, not by check
- `addOns = []` → no add-ons block (`:312`, `:381-391`)
- `money(quote.total, quote.currency)` → `!rendered` → `"Not available yet"` (`:71-74`, `:396`)
- `showAnchor = !quote.withheld && !!quote.anchor` → `false` → no anchor (`:319`, `:399`)
- `{quote.payment_schedule} · {quote.revision_rounds} revision{...}` → React renders `undefined` as nothing → the line reads `" · undefined revisions"` (`:418-419`) — `revision_rounds === 1` is false for `undefined`, so it pluralises
- `benchmark` is false, so the footnote renders `"Based on ."` (`:423-428`)

Result: a card headed **"Suggested package"** with a total of "Not available yet", a stray `· undefined revisions`, and "Based on ." — no error, no console warning, no telemetry. A creator reads it as "Meera has no idea what this is worth."

**Severity: MEDIUM as a latent defect, and it is the cheapest fix on this list.** Make `isEstimateMyRatePayload` assert the components `compute` cannot omit: `typeof q.total === 'string' && typeof q.currency === 'string' && typeof q.revision_rounds === 'number'`. A payload failing that returns `null` from the switch (`:516-522`), which is the file's own documented behaviour for a malformed payload (`:460-467`) and strictly better than a card that lies. The same one-field weakness applies to `isGetMyMetricsPayload` (`:525-529`) and `isCheckDealRisksPayload` (`:531-535`).

---

## 11. On which real deal shapes does `BELOW_FLOOR` in chat disagree with `BELOW_FLOOR` on the deal page?

**Answer: on none. The two surfaces call the same method with the same arguments, so they are identical by construction — your premise is wrong about *where* the two taxonomies meet. But two real divergences do exist, and both are inside chat.**

### Chat and the deal page are the same code path

| Surface | Entry | Lands on |
|---|---|---|
| Chat — `check_deal_risks` with `deal_id` | `CheckDealRisksExecutor.execute` → `:76` | `dealRiskService.evaluateDeal(profile.getId(), dealId)` |
| Deal page — `GET /deals/:id/risks` | `DealService.risksForCreator` → `:188` | `dealRiskService.evaluateDeal(profile.getId(), dealId)` |

Same method, same `creator_profiles.id`, same `RiskContext` built at `DealRiskService:190-204`, same `quoteFor(...)` at `:197`, same `packageOnTheTable(...)` at `:188`. `CheckDealRisksExecutor`'s javadoc states the intent (`:19-22`: "this class… holds no rule of its own, so the flags Meera reads in chat and the flags the deal page renders can never be two different opinions") and the code keeps it. They even share the frontend card: `DealRiskCard` is imported from `components/shared/` by both `CreatorToolResultRenderer.tsx:3` and `creator-chat.tsx:80`.

The only difference is the wrapper's `highestSeverity` helper — `CheckDealRisksExecutor:93-95` takes `flags.get(0).severity()` (relying on `evaluate`'s sort) while `DealService:190` calls `RiskSeverity.highest(flags)`. Both are computed over the same list; a divergence there would be a defect in one of the two helpers, not a taxonomy difference.

### The two divergences that are real

**(a) `estimate_my_rate`'s floor vs `check_deal_risks`'s floor, in one conversation.** These price *different packages*. `estimate_my_rate` prices what the model proposed (`EstimateMyRateExecutor:98-112`, defaulting to `ONE_REEL` when the model named nothing usable). `check_deal_risks` on a deal prices `packageOnTheTable` — the materialised `Deliverable` rows, or, pre-contract, the latest proposal card's slots (`DealRiskService:362-380`). On any pre-contract deal — `INVITED`, `APPLIED`, `IN_NEGOTIATION`, i.e. exactly the deals a creator asks Meera about — these two routinely differ. Meera can say "for this package your floor is 24,000" and then, two tool calls later, "your floor of 12,000". Both numbers are correct for their own package; the creator hears two floors and no explanation.

**(b) The quoted floor vs the `Floors` fallback, non-deterministically, on either surface.** `quoteFor` swallows every `RuntimeException` from `quoteForRisk` and returns null (`DealRiskService:316-334`), at which point `Floors.totalFor` falls through to `fromPreferences` (`Floors.java:38-44`). The two taxonomies genuinely differ there:

| Deliverable | `RateQuoteService.floorTotal` | `Floors.fromPreferences` |
|---|---|---|
| `REEL` | `floorFor(REEL, prefs)`, per-type weight (`:406-413`) | `reelFloor` (`:74`) |
| `SHORT` | its own weight | `reelFloor` |
| `YT_INTEGRATION` | its own weight | `reelFloor` |
| `YT_DEDICATED` | its own weight | `reelFloor` |
| `STATIC_POST`, `UGC_ONLY`, `OTHER`, unmapped | per-type | `postFloor` (`:77`) |
| no itemised deliverables | `normaliseSlots` substitutes one REEL | `reelFloor` for one unit (`:51-56`) |

`Floors`' own javadoc is honest about the direction (`:24-28`: folding video onto `reelFloor` "UNDER-states a dedicated YouTube video and so under-states the floor total — the safe direction, because it can only make `BELOW_FLOOR` fire less often, never falsely"). So the deal shapes where the two answers differ are **YouTube-heavy packages** (`YT_DEDICATED`, `YT_INTEGRATION`, `SHORT`) and any package whose type mix the per-type weights treat differently from the three-floor fold. What makes it a defect rather than a documented fallback is that **which branch ran is invisible**: nothing on the flag, nothing in the payload, and only a `log.warn` on the server (`:327-331`). The creator is told a floor and cannot know it came from the degraded taxonomy, and neither can we from the flag alone.

Note also `Floors.totalFor`'s gate: `quote != null && DealValue.positive(quote.floorTotalValue())`. A creator with no stored floors gets `floorTotal == 0` from the quote, which is not positive, so it falls to `fromPreferences`, which returns zero too — consistent, and `BelowFloorRule` stays silent (`:31-35`). That path is correct.

**Severity: MEDIUM for (a)** — two floors in one conversation is a trust problem, and the fix is product-level (Meera should say which package a floor belongs to, or `check_deal_risks` should return the package it priced). **LOW-MEDIUM for (b)** — add a `floor_source` field to the `BELOW_FLOOR` flag's `data` map (`BelowFloorRule:68-73` already carries five keys) so a degraded floor is labelled rather than disguised, the same discipline `extraction_source` applies to briefs.

---

## 12. Beyond the static column diff, what would the skipped boot test catch, and what is the go/no-go before Wave 1 is recorded as boot-verified?

**Answer: it would catch type coercions, the collation, the unique-key kind, and Flyway actually applying — but *not* three of the four things you named. `ON UPDATE` defaults are checked by nothing, in Docker or out; the `created_at DESC` index is only implicitly proven; and `VARCHAR(26)` vs `@Column(length=26)` is caught by `ddl-auto=validate`, not by the test's own assertions. The test's own javadoc is right that a skip proves nothing, and it names the go/no-go itself.**

### What a real run adds over a static diff

Reaching **any** method in the class already proves the two biggest things, because Spring Boot makes `entityManagerFactory` depend on Flyway: (1) every `V*` migration applied against a stock MySQL 8, and (2) `ddl-auto=validate` accepted every entity in the application against the live schema. The class javadoc says this explicitly (`:38-43`). That second item is the one no static text diff can reproduce — it is Hibernate comparing its own expected JDBC type against `information_schema`, which is what catches:

- `DECIMAL(12,2)` ⇄ `BigDecimal` (`meera_drafts.proposed_amount`, `deal_offer_history.amount`)
- `DATE` ⇄ `LocalDate` (`creator_agent_preferences.holdout_until` — the first `java.time.LocalDate` on that class, per the migration header at `:19-20`)
- `TIMESTAMP` ⇄ `Instant` (`creator_briefs.created_at`/`updated_at`, mapped at `CreatorBrief.java:104-108`)
- `TINYINT(1)` ⇄ `boolean` (`rate_card_shareable`, `negotiation_holdout`, `edited`, `meera_drafted`)
- **`VARCHAR(26)` ⇄ `@Column(length = 26)`** — yes, this is caught, but by `validate`, not by an assertion in the file. `CreatorBrief.java:56-72` declares `length = 26` on `id`, `creator_profile_id`, `collaboration_id`; `length = 16` on `source`, `status`, `extraction_source`; `length = 200` on `brand_name_guess`. All match the DDL as read. A mismatch would abort context refresh with "wrong column type".
- Reserved words: `meera_drafts.text` (`:58`) and `deal_offer_history.event` (`:47`) are both non-reserved in MySQL 8 but are the kind of name that only a real parse settles.

### What its own assertions add

- **Collation** (`:135-153`): `table_collation = utf8mb4_unicode_ci`, not the MySQL 8 server default `utf8mb4_0900_ai_ci`. This is the V73/V74 defect, and it is the one item on your list that the test genuinely pins. It is a **table-level** check, which is sufficient for the three `TEXT _json` columns because none of them declares a per-column `COLLATE` override — they inherit. A future column that overrode it would slip past.
- **The unique key's kind** (`:160-176`): `non_unique = 0` on `uk_doh_collab_seq`, in column order. A plain `INDEX` would serve the same ordered read and enforce nothing; this is PRIYA-COMPAT-0904 §7 condition 3 and the enforcement half of the count-under-row-lock sequence derivation described at `V20260910100500__deal_offer_history.sql:11-25`.
- **Column counts** (`:97-110`): 13 / 16 / 9. I counted the DDL by hand and all three match.
- **The `ALTER TABLE` really landed** (`:116-129`): all six new `creator_agent_preferences` columns present — a table-existence check cannot see a no-op `ALTER`.
- **No `CHAR(n)`** (`:182-192`) on any of the four tables.
- **Flyway applied these four versions specifically** (`:83-90`), not merely "≥132 migrations ran".

### What it does NOT catch — be honest about these

1. **`TIMESTAMP ... ON UPDATE CURRENT_TIMESTAMP`** on `creator_briefs.updated_at` (`:60`) is checked by **nothing**. `ddl-auto=validate` compares types, not defaults or `ON UPDATE` clauses, and no assertion in the file reads `column_default` or `extra`. The clause is also redundant: `CreatorBrief.touch()` (`:139`) sets `updatedAt` in Java, so the DB clause never decides the value. Harmless, but do not claim it is verified.
2. **The `created_at DESC` index** on `creator_briefs` and `meera_drafts` is not asserted anywhere — only `deal_offer_history`'s unique key is. A real run proves the DDL *parsed* (MySQL 8 supports descending index keys; 5.7 parses and silently ignores `DESC`), and the column-count assertion proves the table was created. Nothing proves the index exists or is descending. Given the container is stock MySQL 8, the risk is low, but the coverage claim would be false.
3. **Data-level behaviour.** No row is ever inserted. The FKs to `creator_profiles(id)` and `collaborations(id)` are declared and would fail at `CREATE TABLE` on a charset/type mismatch — so their *creatability* is proven — but `ON DELETE CASCADE` actually cascading is not.

### The go/no-go before Wave 1 is recorded as boot-verified

The file states it: *"Do not record this wave as boot-verified until the SKIPPED count for this class is 0"* (`:27-36`). Concretely, all three of:

1. `MeeraPhaseB0BootValidationTest` runs with **`Skipped: 0`** in its surefire report — on a machine with a Docker daemon or on the VPS. Read the skip count, never the exit code: `DockerAvailableCondition` **disables** the class rather than failing it, so `BUILD SUCCESS` with `Tests run: 0` is the false green here (this is the `reference_local_mvn_test_skips_docker_tests` trap, and all 25 Docker-gated skips share it).
2. The sibling `MeeraCreatorPhaseABootValidationTest` runs in the same pass — Phase A's schema is a precondition for `V20260910100000`'s `ALTER TABLE`, and a Phase-A schema defect would present as a Phase-B failure.
3. A **fresh** database, not a reused volume. Flyway skips versions already in `flyway_schema_history`, so a pre-migrated container would pass `:83-90` without executing this wave's DDL at all.

Until then the correct status line is "schema statically diffed, never boot-validated", and Wave 4 — which adds `CreatorBriefService` and starts writing `creator_briefs` rows for real — must not start on a schema nothing has booted against.

---

## 13. Which page is the creator's real Meera in B0, and is the other half a wiring gap or a deliberate split?

**Answer: `/creator/copilot` is the creator's Meera. `/creator/chat` is the brand⇄creator deal room — a different product surface that was never meant to host Meera. The split is deliberate and documented in three places. Not a wiring gap.**

Both routes are live and both are behind `CreatorProtectedRoute`: `/creator/copilot` → `CreatorCopilotPage` (`src/App.tsx:569-576`) and `/creator/chat` → `CreatorChatPage` (`:601-608`).

### `/creator/copilot` is Meera

`creator-copilot.tsx:11` imports `MeeraCopilotChat` and mounts it at `:154`. `MeeraCopilotChat.tsx:10` imports `CreatorToolResultRenderer` and renders it at `:525-531`, for every `toolResults` entry, after the message bubble it belongs to. It is the **only** importer of that component in `src/` — I checked, and the only other reference is the comment at `MeeraCopilotChat.toolcards.test.tsx:4` recording the earlier defect ("`CreatorToolResultRenderer` had no importer but its own test, so no tool card could ever reach a creator"), which was the seventh dead-symbol finding of this session and is now closed.

### `/creator/chat` is the deal room

`creator-chat.tsx` is brand⇄creator messaging: a conversation list, a composer with `Send`/`Paperclip`, `DealMessage`/`MessageKind`/`ContractApiRecord`/`ShipmentApiRecord` types, contract milestones, deliverable uploads, shipments (`:1-80`). It reads `GET /deals/:id/risks` at `:789` and renders `DealRiskCard` at `:2608` and `:2825`. No Meera stream, no `useMeeraStream`, no tool events, no `CreatorToolResultRenderer` — correctly, because it has no tool results to render.

### Why the shared card is the right shape

`DealRiskCard` lives in `src/components/shared/` and is imported by both (`CreatorToolResultRenderer.tsx:3`, `creator-chat.tsx:80`). That is §8.6's intent and it is what makes Q11's answer hold from the frontend side too: the same component renders the same `RiskFlag[]` whether it arrived over the REST route or as a `check_deal_risks` tool result, because both are the same Java record (`meera-api.ts:479-484` aliases `CheckDealRisksPayload = DealRisksResponse` rather than redeclaring it — "aliased rather than redeclared so the two stay in sync by construction").

### The one thing that is genuinely deferred, and is labelled

`MeeraCopilotChat` passes **neither** `onPrefillCounter` **nor** `onOpenDeal` (`:509-513`): *"this panel has no counter form and no deal navigation to hand them to (both live on the deal pages, §8.6), and every card hides the matching control when the callback is absent. A visible button wired to nothing would be worse than no button."* I verified the cards honour that: `MyDealsCard`'s "Open deal" button renders only `onOpenDeal ? … : null` (`:211-219`) and `PackageQuoteCard`'s "Use in counter" only `onPrefillCounter && !quote.withheld ? … : null` (`:431-440`). So no dead control ships — which is the `feedback_dead_controls_invisible_to_static_checks` class, handled correctly.

`get_brief` and `draft_reply` render `null` by name (`:533-536`), deliberately, until B0-44 and B0-49 land.

**Severity: none. This one you can close.** The only follow-up worth noting is a product one: a creator on `/creator/copilot` who asks about a deal gets a `MyDealsCard` with no way to open the deal. That is a decision, not a bug, and the comment records it.

---

## 14. What proves `findByIdAndCreatorId` is genuinely scoping rather than returning empty for everyone?

**Answer: the *positive* tests prove it, and the test whose name says it does — `"another creator's deal id is a 404"` — proves nothing. The id spaces are correct: `Collaboration.creatorId` really is a `users.id`, and `profile.getUserId()` really is one. What is unproven is that the generated SQL filters, which needs a database.**

### The id spaces are right

- `Collaboration.creatorId` is declared `@Column(name = "creator_id", nullable = false, length = 26)` (`Collaboration.java:30-31`) and **every** factory assigns it from a parameter named `creatorUserId` (`:115`, `:140`, `:336`). It is a user id.
- `CreatorProfile.getUserId()` is a `users.id`.
- `DealRiskService.evaluateDeal` takes a `creator_profiles.id`, loads the profile, and passes `profile.getUserId()` to the finder (`:170-177`). Its javadoc says exactly why (`:165-167`).
- `DealService.risksForCreator` resolves the profile from the authenticated principal and passes `profile.getId()` (`:187-188`) — the profile id, matching `evaluateDeal`'s contract.
- `CheckDealRisksExecutor` does the same with `profile.getId()` (`:76`), and its javadoc names the reason (`:24-28`).

So the "adjacent parameters in different id spaces" hazard you flagged is present in the signatures and is handled correctly at both call sites.

### What actually proves the argument is the right one

`DealRiskServiceEvaluateDealTest` is `@ExtendWith(MockitoExtension.class)` (`:69-70`), i.e. `STRICT_STUBS`, and every positive test stubs with **literal expected values**:

```java
when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID))
        .thenReturn(Optional.of(collaboration));            // :497-498, :540-541, :613-614, :704-705
```

If the service passed `profile.getId()` (the profile id) instead of `getUserId()`, that stub would not match, Mockito would return `Optional.empty()` for the unstubbed call, `evaluateDeal` would throw `DEAL_NOT_FOUND`, and every one of those flag assertions would fail. **That** is the artefact that pins the argument identity, and it is real. `MeeraContextServiceTest` gives the same proof for the other id space: `collaborationRepository.findByCreatorId(WORKSPACE_ID)` is stubbed on the user id (`:317`) while `creatorAgentPreferencesRepository.findByCreatorId("profile1")` is stubbed on the profile id (`:321`) — two different spaces, two exact stubs, both required for the test to pass.

### The test that looks like the proof, and is not

`"another creator's deal id is a 404, not someone else's risk flags"` (`:162-170`) stubs `findByIdAndCreatorId(DEAL_ID, USER_ID)` → `Optional.empty()` and asserts an `ApiException`. That is a tautology: it proves the service throws when the repository returns empty. It would pass identically against a finder that returns empty for **everyone**, which is precisely the failure mode you asked about. Reading the name alone would give false comfort. (The same shape appears in `DealServiceRisksTest:137-142`, "a clean deal returns an empty list" — also a stub of the service below it.)

### What is NOT VERIFIED

That the SQL Spring Data generates from the method name actually restricts rows by `creator_id`.

`findByIdAndCreatorId(String id, String creatorId)` is a derived query with no `@Query` (`CollaborationRepository.java:228`). Spring Data parses the method name at context startup against the `Collaboration` metamodel; both `id` and `creatorId` exist as properties, so the predicate is generated — and a typo'd property name would fail the context refresh, which is a form of proof. But nothing in the tree executes it against a database with two creators' rows present.

**The artefact that would prove it**: a Testcontainers test extending `AbstractIntegrationTest` that inserts two `creator_profiles` (each with its own `users` row) and one `collaborations` row per creator, then asserts `findByIdAndCreatorId(dealOfA, userIdOfB).isEmpty()` **and** `findByIdAndCreatorId(dealOfA, userIdOfA).isPresent()`. Both directions, in one test — the positive assertion is what stops a "returns empty for everyone" regression from passing. That test is Docker-gated like the rest, so it lands in the same go/no-go as Q12.

**Severity: LOW.** The mechanism is framework-generated and the call sites are pinned by strict stubs. But you are right that a 404 reading as "clean deal" is the shape this would fail in, and right that nothing today closes it.

---

## 15. Is there a written deploy-order rule for `send_routine_reply`, and does the wired-tool-names test catch a route with no executor?

**Answer: no, there is no deploy-order rule — the written rule says the opposite, and is the mechanism you are worried about. And the test catches a name/route mismatch in both directions, which means it goes green on the exact change that turns the send grant live. You are right, and this is the finding I would fix first.**

### `requireScope` is string membership, nothing more

```java
// security/OnBehalfAuthResolver.java:212-222
private void requireScope(Claims claims, String requiredTool) {
    String scope = claims.get("scope", String.class);
    boolean allowed =
            scope != null && java.util.List.of(scope.trim().split("\\s+")).contains(requiredTool);
    if (!allowed) { throw new ApiException("ON_BEHALF_SCOPE_INSUFFICIENT", ...); }
}
```

No registry, no enum parse, no cross-check against `CreatorToolName` or `WIRED_TOOL_NAMES`. `SCOPE_LEVEL_1` is `SCOPE_LEVEL_0 + " send_routine_reply"` (`CreatorToolScopes.java:35`), and `SCOPE_LEVEL_2 = SCOPE_LEVEL_1` (`:57`). So the string `send_routine_reply` is already in the claim of every level-1 and level-2 creator's token, minted per turn at `MeeraSessionService.java:422-429`.

### The written rule endorses this, and no rule governs deploy order

The rule that exists is SPEC.md W20 and its Phase table:

> "`SCOPE_LEVEL_* NEEDS NO B1 EDIT`… `requireScope` only asserts that the REQUIRED tool is present in the space-delimited claim — it never validates scope entries against a tool registry, so extra names with no backing executor are inert… So B0 ships the 8-name string verbatim and B1 appends nothing to `SCOPE_LEVEL_*`."
> — `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/SPEC.md:1790`, and `:1801` ("append to `CreatorToolName`, `CREATOR_TOOL_NAMES`, `tools_enabled` — **not** `SCOPE_LEVEL_*`")

`CreatorToolScopes`' class javadoc says the same thing in the source (`:12-17`: "Minting the full ceiling now means a later wave adds a route and an executor without also having to re-mint tokens or migrate a claim shape").

So the only written rules are: *don't touch the scope in B1*, and *`WIRED_TOOL_NAMES` and the `@PostMapping` set are one change* (`:78-86`, restated at `CreatorMeeraToolController.java:49-53`). **There is no rule about which container deploys first, no rule requiring a separate flag on the send route, and no rule that the send capability needs its own enable step.** The consequence is exactly what you describe, with one correction of scale: on-behalf tokens live 120 seconds (`OnBehalfTokenService.java:43`), so "already-minted tokens" is a two-minute window. The standing exposure is larger and simpler — **from the instant a `@PostMapping("/send_routine_reply")` reaches production, every level-1 and level-2 creator's next token already authorises it.** There is no intermediate state in which the route exists and the grant does not.

The one thing standing between that and an actual send is `WIRED_TOOL_NAMES`: until `send_routine_reply` is in it, `tools_enabled` does not name it, the assembler does not offer the schema, and `loop.py:386-410` refuses it locally. That is a real gate — but it is a gate the same commit will open, because `CreatorToolScopes`' own javadoc instructs the author to change both halves together.

### What the tripwire test does and does not catch

`MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames` (`:304-418`) makes four assertions. The load-bearing one:

```java
assertEquals(
        new java.util.TreeSet<>(CreatorToolScopes.toolNamesForLevel(2, false, false)),
        creatorMeeraToolRoutes(),                 // reflection over @PostMapping values, :400-418
        "the tools the assembler can offer and the routes the controller serves have diverged");
```

- **A route with no name in `WIRED_TOOL_NAMES`** → right side bigger → **RED**. ✓ (This is the Wave 2 defect: the class landed and the call site did not.)
- **A name in `WIRED_TOOL_NAMES` with no route** → left side bigger → **RED**. ✓
- **A route with no executor** → not really constructible: a `@PostMapping` handler must reference an executor to compile. A route with a **stub** executor that returns an empty shape → **GREEN**. The test proves the name/route pair exists, never that the executor does anything.
- **`send_routine_reply` landing correctly** (route + `WIRED_TOOL_NAMES` + executor, all three) → **GREEN**, as designed. The test is a wiring-consistency check, not a capability gate. It will not tell anyone that a send grant just went live for every level-1 creator.

The helper is also non-vacuous by construction (`:413-417` asserts the reflected route set is non-empty), and I confirmed the class passes at this HEAD (13 tests, 0 failures).

### What I am ruling, as the written deploy-order rule this answer is asked for

Before any `send_routine_reply` route is merged:

1. **The route ships behind its own property**, separate from `MEERA_CREATOR_ENABLED` — e.g. `influora.meera.creator-send-enabled`, default **false**, checked in `handleRead` alongside `requireFeatureEnabled` (`CreatorMeeraToolController.java:227-233`). The capability is then enabled by a config change that can be reviewed and reverted, not by a code deploy.
2. **Deploy order is influora-api first, influora-ai second**, and the flag stays false across both. The reverse order (ai first) offers a tool the api cannot answer and costs every level-1 creator a turn; the flag makes the order harmless either way.
3. **A new test asserts the grant is inert**: with the flag false, a level-1 token whose scope contains `send_routine_reply` is refused at the route with a code that is *not* `ON_BEHALF_SCOPE_INSUFFICIENT` — so the audit trail distinguishes "capability switched off" from "scope insufficient". `CreatorMeeraToolControllerTest` already has the shape for this (`:104-120`, the flag-off test).
4. **`CreatorToolScopes`' class javadoc is amended.** As written (`:12-17`) it tells the next author that minting the full ceiling is free. It is free only while no route exists. The sentence must say that adding the route converts every outstanding level-1 claim into a live send grant, and point at rule 1.

**Severity: HIGH as a process defect, not yet a code defect.** Nothing is exploitable today — `send_routine_reply` has no route, and `WIRED_TOOL_NAMES` holds the line. But this is the one item on the list where the *documented* rule is what creates the hazard, and it is the last moment at which fixing it costs a comment and a property instead of an incident.

---

## Appendix: what I could not verify, in one place

| Claim | Why not | Artefact that would settle it |
|---|---|---|
| The JSON leaving any creator executor matches its TS type | Nothing serialises `CreatorToolDtos` anywhere in the tree | Golden-fixture pair (Q1) |
| Any of `CreatorContextResponse`'s 32 fields is non-null for a real creator | Every Java test of `assembleCreatorContext` stubs the repositories | Testcontainers context test (Q4) |
| The verify-failure-budget collapse affects a *valid* token from another creator | No test covers the mixed case | One case in `AuthRateLimitFilterCreatorToolBucketTest` (Q8) |
| `ddl-auto=validate` accepts the four Wave 1 tables | Docker unavailable; `MeeraPhaseB0BootValidationTest` is disabled, not run | `Skipped: 0` on that class, fresh DB (Q12) |
| `ON UPDATE CURRENT_TIMESTAMP` on `creator_briefs.updated_at` | Checked by no test and not by `validate` | An `information_schema.columns.extra` assertion, if it is worth having |
| The `created_at DESC` indexes exist and are descending | Not asserted; only implied by the migration applying | An `information_schema.statistics` assertion alongside the existing unique-key one |
| `findByIdAndCreatorId` restricts rows in SQL | Framework-generated; never executed against two creators' rows | Two-direction Testcontainers repository test (Q14) |
| Any creator tool has ever executed end to end | No live turn on this branch | A live smoke test with Docker or on the VPS |
