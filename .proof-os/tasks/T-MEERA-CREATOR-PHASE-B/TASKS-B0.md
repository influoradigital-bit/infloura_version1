# TASKS: Meera for Creators — Phase B0 "Paste and Read"

**For:** Swapnil
**From:** Priya (CTO), via /work
**Date:** 2026-09-08
**Branch at time of writing:** `feat/meera-creator-phase-e`, HEAD `7f48e8d`
**Sources:** `SPEC.md` §10, §12, §13.3, §14.5, §14.6, §15 · `PRIYA-COMPAT-0904.md` §7 · `DECISIONS-0904.md` · `.proof-os/registry.json` · the working tree at HEAD

**This document divides work and names owners. It builds nothing.** No task below is started.

---

## 1. Read this first

### 1.1 State

Nothing from Phase B exists in the tree. Verified today: `CreatorToolName.java`, `CreatorBrief.java`, `RateQuoteService.java`, `DealRiskService.java` and `creator_schemas.py` are all absent, and no migration in the `V20260910*` block has been written. The spec is complete and twice-reviewed; the code is untouched.

### 1.2 The spec's baseline has moved 29 commits

The spec is anchored to `143ca1e`. HEAD is `7f48e8d`. Four of those 29 commits touch surfaces Phase B extends:

| Commit | What it did | Why B0 cares |
|---|---|---|
| `7f48e8d` | creator conversation persistence (F-0751) | edits `MeeraSessionService`, which B0 Wave 2 modifies |
| `6d35240` | creator discovery without Meta OAuth | touches creator profile surfaces |
| `6a27bb6` | mobile number required at creator signup | the sibling of the brand-register constraint in §14.6 W9 |
| `fc8db88` | Festival Box + accumulated QA | broad |

Measured drift on the anchors B0 actually uses:

| Anchor | Spec says | Today | Δ |
|---|---|---|---|
| `api.ts` `export const api = {` | L6313 | **L6858** | +545 |
| `DealService.toMessageResponse` decl | L2214 | **L2290** | +76 |
| `MeeraSessionService` mint call | L397 | **L405** | +8 |
| `MeeraSessionService.isCreatorTurn` | L332 | **L340** | +8 |
| `MeeraSessionService` ctor | L102 | L103 | +1 |
| `DealService.createProposal` | L243 | L242 | −1 |
| `CreatorAgentPreferencesService.createWithComputedDefaults` | L103 | L103 | 0 |
| `CreatorContextResponse` components | 27 | **27** | 0 |
| `assembler.py build_block_a_creator` | L435 | L435 | 0 |
| `flyway out-of-order: true` | L61 | L61 | 0 |

The Python side has not moved at all. The frontend has moved a lot. Migration timestamps are still safe: the highest on disk is `V20260907120000`, below the `V20260910*` block B0 reserves.

### 1.3 FINDING-1 — the B0 split table contradicts itself, and B0 has no paste

**This blocks the division, so it is resolved here rather than left for an engineer to hit.**

`SPEC.md` §14.5.a assigns to B0's *Backend* cell "§10 days 1-3, plus day 5" — explicitly excluding day 4. The `AMEND-0904` pointer at §10 repeats it: "B0 (days 1-3 plus the draft_reply slice of day 5)". But §10's day-4 backend row reads, verbatim:

> `CreatorBriefService`, `MeeraBriefAiClient`, fallback extractor, `CreatorBriefController`, secure links and redemption, offer history

That row holds the entire paste backend. Meanwhile the same §14.5.a table puts in B0:

- **Jobs:** "B1 read half (**paste** → summary, flags, quote, draft)"
- **Frontend:** `PasteBriefCard` — day 4's frontend
- **AI service:** "brief extraction route" — which only a Java client calls
- **Migrations:** 2.2 `creator_briefs`
- **Tools:** `get_brief`, whose executor calls `CreatorBriefService.ensurePlatformBrief` (§3.6)

Taken literally, B0 ships a paste card with no endpoint, a `creator_briefs` table nothing writes to, a Python extraction route no client calls, and a `get_brief` tool that cannot compile. A phase named "Paste and Read" that cannot paste.

**Resolution applied below:** day 4 is split. Its paste half (`CreatorBriefService`, `MeeraBriefAiClient`, `BriefFallbackExtractor`, `CreatorBriefController`) and offer history are **B0, Wave 4**. Secure links and redemption stay B1. This is a sixth defect in §14.5 beyond the five §14.6 recorded; §14.5.a's Backend cell needs the correction in writing (task **B0-07**).

### 1.4 FINDING-2 — the CI gate that day 1 trips is still broken

`PRIYA-COMPAT-0904` §7 condition 6 named the `schema-check.yml` awk repair "the first thing to do", on 2026-09-04. Re-measured today, unchanged:

- the literal `{})` the awk range ends on occurs **0 times** in `MeeraContextDtos.java`
- so the range runs to end of file and extracts **42** `@JsonProperty` names
- Python's `CONTEXT_PAYLOAD_FIELDS` has **15**
- the workflow compares them as strings and `exit 1`s

Its `paths:` trigger covers both `MeeraContextDtos.java` and `assembler.py` — the two files Wave 1 must land in one commit. **Day 1 is the commit that trips it.** Task **B0-01**, owned by Meera, before any other work starts.

---

## 2. The team, and what a ceiling means

Owners below are services declared in `.proof-os/registry.json`. Each carries a `may_claim` ceiling that caps what a row it owns can ever score, however good the evidence:

| Service | Ceiling | Role in B0 |
|---|---|---|
| **meera** | **proved** | migrations, build/test gates, CI repair, Phase A deploy + smoke. **The only engineer whose sign-off can turn a row green.** |
| **neha** | **proved** | live E2E through the browser, after deploy |
| priya | believed | architecture, spec corrections, design review at each wave gate |
| vikram | believed | backend Java **and** the Python AI service |
| ananya | believed | frontend React/TS |
| arjun | believed | orchestration, sequencing, the board, unblocking |
| tara | believed | `B0-METRICS.md` and wave reports |
| rohan | believed | cost verification on the cap changes |
| kavya | echo | QA review before every gate |
| kabir | echo | info-barrier and security review |
| ash | echo | AI-service audit (prompts, schemas, tool loop) |
| swapnil | — | the four rulings in §12 |

**What `echo` means in practice:** a review by Kavya, Kabir or Ash is real safety and finds real defects, but it can never render a row green in a proof-os report — it contributes no score and is excluded from the denominator. Only a gate run by Meera (or a live E2E by Neha) proves anything. Every wave below therefore ends at Meera, not at a reviewer. This is not a demotion of QA; it is the registry enforcing the flow the team already uses.

---

## 3. The flow

Per task, without exception:

```
priya            vikram / ananya        kavya          meera            priya      arjun
design ────────► build ──────────────► QA ──────────► GATE ──────────► sign ────► close
  │                    │                (echo)        (proved)         (believed)
  │                    │
  │                    ├──► kabir (echo) ── on anything touching a floor, a brand-visible
  │                    │                    payload, or an audit row
  │                    └──► ash   (echo) ── on anything in influora-ai
  │
  └──► swapnil ── only for the four §12 rulings
```

Two standing rules:

1. **Nothing reaches Meera without Kavya first.** Kavya's pass is not scored but it is not optional.
2. **Meera's gate is the row.** If Meera cannot run the gate, the task is `unavailable`, not `done`.

---

## 4. Wave 0 — pre-flight. Blocking. Nothing else starts.

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-01** | Repair `schema-check.yml`'s context-field awk range so it bounds `ContextResponse` only. Prove it emits exactly the 15 brand names before anything touches the two trigger files. **Ledger `F-0763`** — closes only by promotion to a gate. | **meera** | — | the workflow's extraction step prints 15 names matching `CONTEXT_PAYLOAD_FIELDS`, and a deliberate mismatch makes it exit 1 |
| **B0-02** | Deploy Phase A (`8c7b18b` or later, **never `1792c37` alone**) and run the live smoke on Docker or the VPS. | **meera** | — | `MeeraCreatorPhaseABootValidationTest` green on a real MySQL 8, creator Meera reachable on the deployed host |
| **B0-03** | Branch `feat/meera-creator-phase-b0` from a **clean** tree at current HEAD. Do not carry the money-flags or Festival Box working set. | **arjun** | — | `git status` clean on the new branch; no unrelated modified file |
| **B0-04** | Apply the eight §14.6 must-fixes into the spec body (edited column, spend gate, anchor formula, distinct-collab query, audit helper, brand form honesty, Flyway premise, §9 test row + anchor cells). | **priya** | — | each of the eight is resolved in the section it belongs to, not only in §14.6's list |
| **B0-05** | Re-anchor every line reference B0 touches to current HEAD (§1.2 table is the starting set; `api.ts` +504 is the big one). | **priya** | B0-03 | no B0 task cites a line number that has moved |
| **B0-06** | Take the four §12 rulings to Swapnil. Defaults ship if no answer by Wave 1. | **arjun** | — | four answers, or four defaults recorded as taken |
| **B0-07** | Correct §14.5.a's Backend cell per FINDING-1: day 4's paste half is B0, secure links are B1. Assign §2.7 to B1 (§14.6 W19). **Ledger `F-0764`.** | **priya** | — | the B0 column and the B0 job list agree |
| **B0-08** | Confirm the credits sub-track (§15, `CREDITS-SPEC.md`) stays **dark** in B0 — `CREATOR_CREDITS_ENABLED=false`, no `V20260912*` migration in this branch. | **priya** | — | written into the B0 scope; no credits code in the B0 diff |

**Wave 0 flow:** meera and arjun run in parallel with priya. Wave 1 does not open until B0-01, B0-03, B0-04, B0-05 and B0-07 are done. **B0-02 must complete before Wave 8, not before Wave 1** — it blocks measurement, not building.

---

## 5. Wave 1 — foundation. One merged commit across all three services.

The record arities, `getByProfileId`, the `ENDPOINT_SCOPES` entry and the drift-test coupling are day-1 or nothing: the Python drift test parses the Java DTO, so Java and Python must land together or the Python suite is red for everyone.

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-09** | Migrations 2.1 prefs, 2.2 briefs, 2.4 drafts **+ `edited TINYINT(1) NOT NULL DEFAULT 0`**, 2.6 offer history **+ `UNIQUE KEY uk_doh_collab_seq`** and the row-lock derivation note. No `CHAR(n)`; every table ends with the InnoDB/utf8mb4 clause. | vikram | Wave 0 | four files written; `ddl-auto=validate` boots |
| **B0-10** | Entities + enums: `CreatorBrief`, `MeeraDraft`, `DealOfferHistory`, `CreatorAgentPreferences` +6 fields with `LocalDate` import, and their repositories. | vikram | B0-09 | compiles; every column has an explicit `@Column(name=…)` |
| **B0-11** | `com.influora.common.Rendered` (`money`, `date`), `CreatorTiers.derive`, `CreatorDealStatuses.ACTIVE`. Unify all three `deriveTier` copies — verified byte-identical, so this is a pure move. | vikram | Wave 0 | three call sites read the shared symbol; `RateEstimationServiceTest` still green |
| **B0-12** | `CreatorContextResponse` 27 → 32 components and its **one** construction site; `CreatorAgentPreferencesService.getByProfileId`; `PreferencesResponse` 18 → 23 (**4** sites); `UpdatePreferencesRequest` 16 → 18 (**9** sites). | vikram | B0-10 | every enumerated site updated; nothing else constructs these records |
| **B0-13** | `BriefDtos`, `CreatorToolDtos` — snake_case `@JsonProperty`, `@JsonInclude(NON_NULL)`, `_value` numeric siblings. | vikram | B0-10 | matches §3.5 and §2.11 field-for-field |
| **B0-14** | Python: the five snake_case names into `CREATOR_CONTEXT_PAYLOAD_FIELDS` **in sorted position**, two into `CREATOR_CONTEXT_FIELDS_NOT_RENDERED`, six into `_FORBIDDEN_BRAND_FIELDS`, renders written as `ctx.get(...)`, `_PREREQUISITES["holdout_until"]`. Bump `PROMPT_VERSION` to `meera-2026.09.10.1`. Register `ENDPOINT_SCOPES["brief_extract"]`. | vikram | B0-12 (same commit) | `test_creator_context_drift.py` green on all four assertions |
| **B0-15** | Widen `InfoBarrierTest` scan roots to `service/**` and `job/**`. The file is `com/influora/architecture/InfoBarrierTest.java` — **not** `service/meera`, which holds the different `InfoBarrierRuntimeTest`. **~8-line restructure of L69-79**, not a list edit — `candidateDirs` is consumed by index in a two-resource try-with-resources. | vikram | B0-10 | test green with the wider scan; no new violation |
| **B0-16** | Frontend types: the 19 new type names, `CreatorAgentPreferences` +5, widen the `CreatorAgentPreferencesUpdate` omit union, `toDraft()` produces the two non-omitted fields, `MeeraRole` gets `export`. | ananya | B0-13 | `tsc` clean; `MeeraSettingsSection` still compiles |
| **B0-17** | `MeeraPhaseB0BootValidationTest` — Testcontainers, four migrations on stock MySQL 8, `ddl-auto=validate` boots. | **meera** | B0-09 | green with Docker; skipped-not-failed without |
| **B0-18** | Wave 1 QA sweep. | kavya | B0-09→B0-16 | no standards or `TECH-STACK.md` violation |
| **B0-19** | Wave 1 gate: `mvn test` in `influora-api`, `pytest` in `influora-ai`, `tsc` + `vitest` in `src/`. Read the **Skipped** count, not the exit code. | **meera** | B0-18 | all three suites green; skip count explained |

**Wave 1 flow:** `vikram (B0-09→15) ∥ ananya (B0-16) → kavya (B0-18) → meera (B0-17, B0-19) → priya sign-off → arjun merges as ONE commit.`

---

## 6. Wave 2 — tools and scope

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-20** | `CreatorToolName` (**six** values in B0), `CreatorToolCallValidator`, `CreatorToolScopes` (ship the 8-name `SCOPE_LEVEL_0` verbatim — extra names are inert). | vikram | Wave 1 | `MeeraToolName`'s six values untouched |
| **B0-21** | `OnBehalfTokenService` six-arg `mint` overload; `MeeraSessionService` creator-turn scope resolution; ctor 10 → 11 params, one test construction site (`MeeraSessionServiceTest:82`). | vikram | B0-20 | five-arg `mint` still defaults to `SCOPE_DEFAULT` |
| **B0-22** | `CreatorMeeraToolController` with the five read executors; feature-flag helper copied (the original is private); rate bucket keyed on the on-behalf JWT `sub`, **never on IP**. | vikram | B0-20 | each route: 403 on brand principal, 403 without consent, 404 when flag off |
| **B0-23** | The five read executors: `GetMyDeals` (secured via `hasEscrowForCollaboration`, unread via the extracted public helper), `GetBrief`, `EstimateMyRate`, `GetMyMetrics`, `CheckDealRisks`. | vikram | B0-22 | `secured` true on a genuinely funded deal |
| **B0-24** | `creator_schemas.py` — **six** schemas, combinator-free, single concrete types, `items` on arrays. Extend `_all_tool_schemas()` (one edit covers three parametrised tests). | vikram | Wave 1 | schema validity test green over all six |
| **B0-25** | `assembler.py` tool selection + Block A/B; persona rewrite with the **six-tool** list, the §14.1.b provenance rail and the §14.3.c authorship rail. Both Phase-A strings die — one is in the persona, one in `assembler.py:444`. | vikram | B0-24 | the 13-row test-impact table in §7.4 resolved |
| **B0-26** | `loop.py`: widen `is_known_tool` **and** the path lookup in the same edit (the subscript at L452 sits outside the `try`), plus **both** idempotency sites. | vikram | B0-24 | a creator tool resolves to its Spring path; an unmapped name degrades to an error result, not a `KeyError` |
| **B0-27** | AI-service audit: prompts, schemas, tool loop, refusal paths. | ash | B0-26 | no combinator, no floor in any prompt string |
| **B0-28** | Info-barrier review of the tool surface. | kabir | B0-23 | no floor, preference or agency name on a brand-readable payload |
| **B0-29** | Wave 2 QA + gate. | kavya → **meera** | B0-27, B0-28 | suites green |

**Wave 2 flow:** `vikram → ash ∥ kabir → kavya → meera → priya.`

---

## 7. Wave 3 — pricing and risk

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-30** | `QuoteDeliverableType` (new name — `DeliverableType` already exists as a persisted platform taxonomy), `RateAddOns`, `RateTierProperties`. | vikram | Wave 2 | legacy and platform names both parse |
| **B0-31** | `RateQuoteService` with the §14.1 corrections: **midpoint** not 0.65; shrinkage at n=1,2 with the same rounding rule; distinct-workspace floor; **distinct-collaboration** Meera-anchored share; anchor `×1.15` uncapped for own/tier and `min(×1.10, rangeMax)` for benchmark. | vikram | B0-30 | the three provenance branches and four recommended moves tested |
| **B0-32** | Widen `findRateBandCandidates` with `updated_at` and `id`. **Kabir signs off on the query change before it merges**, not after — its javadoc carries a k-anonymity gate. | vikram → kabir | B0-31 | no row serialised; aggregate only behind the ≥5 floor |
| **B0-33** | `RATE_QUOTE_ISSUED` audit row via a `recordAdminAction`-shaped helper with an explicit outcome. Never `recordAuthRejection` — it hard-codes REJECTED. | vikram | B0-31 | detail map carries no floor key |
| **B0-34** | `DealRiskService` + **fourteen** rule classes (§1 and §11 say "ten"; §5.2 lists fourteen — fix the count). `GET /deals/{id}/risks`, creator-only. | vikram | B0-30 | one firing and one non-firing test per rule = 28 tests |
| **B0-35** | Calibration report `GET /admin/creator-agent/rate-calibration` + admin table using a new `RATE_TIER_ORDER` (the page's existing `TIER_ORDER` is BRONZE/SILVER/GOLD/PLATINUM — loyalty tiers, wrong vocabulary). | vikram + ananya | B0-33 | `realised_median` null below n=5 |
| **B0-36** | Run the calibration query against the production replica; set yml tier overrides for any tier with n ≥ 20; record what was set and why. | **meera** + rohan | B0-35, B0-02 | overrides recorded in `REPORT.md`, or "benchmark mode, n<20" recorded per tier |
| **B0-37** | `DealRiskCard`, `PackageQuoteCard` (provenance as **subtitle** in benchmark mode), `MetricsCard`, `MyDealsCard`. | ananya | B0-34 | `bg-destructive` uses `text-destructive-foreground` |
| **B0-38** | Wave 3 QA + gate. | kavya → **meera** | B0-36, B0-37 | suites green |

**Wave 3 flow:** `vikram → kabir (B0-32, mandatory pre-merge) → ananya → kavya → meera → priya ∥ rohan.`

---

## 8. Wave 4 — paste and briefs *(day 4's paste half, per FINDING-1)*

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-39** | `brief_extract.py` route: `ENDPOINT_SCOPES` entry, `verify_creator_token(token, *, endpoint, body_creator_profile_id)` offloaded through `anyio.to_thread.run_sync`, `await complete_with_forced_tool(...)` keyword-only reading `.tool_input`. `BRIEF_EXTRACT_MODEL` defaults to `TRENDSPARK_MODEL` (a priced row). | vikram | Wave 2 | every failure path returns a deterministic body; auth is the only non-200 |
| **B0-39a** | AI-service audit of the brief-extraction route: auth path, forced-tool call shape, validation of the model's output, every failure path's body. Same standing rule as Wave 2's B0-27 — Ash reviews anything in `influora-ai`. *(Inserted after the first draft of this board; every other id is unchanged.)* | ash | B0-39 | no combinator in the extraction schema; no floor or banned word in any prompt string; each failure path returns its documented deterministic body |
| **B0-40** | `MeeraBriefAiClient` mirroring `CreatorSuggestionAiClient` — a **creator**-scoped token passing `creator_profiles.id`, not a service token. | vikram | B0-39 | a service token is rejected by the route, as designed |
| **B0-41** | `BriefFallbackExtractor` — deterministic regex path so a paste survives an AI outage. | vikram | B0-40 | raw text persists with `extraction_source = FALLBACK` |
| **B0-42** | `CreatorBriefService` (`paste`, `ensurePlatformBrief`, `get`, `list`, `dismiss`) + `CreatorBriefController` + the `creator-brief-paste` rate bucket. **No secure-link methods in B0.** | vikram | B0-41 | paste → summary, flags, quote in one request |
| **B0-43** | Offer history writes at the four `DealService` points, sequence derived under the existing row lock. | vikram | B0-09 | four write points tested |
| **B0-44** | `PasteBriefCard` + `BriefCard`; mount on the co-pilot page; replace the Phase A card description. | ananya | B0-42, B0-37 | paste renders summary, flags, quote; error inline, no toast |
| **B0-45** | Wave 4 QA + gate. | kavya → **meera** | B0-43, B0-44 | suites green |

**Wave 4 flow:** `vikram (B0-39→43) → ash (B0-39a) → ananya (B0-44) → kavya → meera (B0-45) → priya.`

---

## 9. Wave 5 — drafts and approvals

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-46** | `DraftReplyExecutor`: floor-leak 422, below-floor 422 with strategic override + audit row, holdout withheld, represented 403, **DECLINE capped at 500 chars** (`RejectRequest` is `@Size(max=500)` while drafts allow 2000). | vikram | Wave 4 | each guard tested |
| **B0-47** | `CreatorMeeraDraftController`: list, approve, discard, level-up-seen (**add the fourth route to §3.7's table**). Approve routes REPLY → `sendMessage` (3 args), COUNTER → `counter` (4 args), DECLINE → `reject` (4 args). `sent_message_id` is **REPLY-only and nullable**; the replay guard keys on `status != PENDING`. Set `edited` on approve. | vikram | B0-46 | double-tap does not double-send |
| **B0-48** | `CounterRequest` 6 → 7 components and **all 14** construction sites (9 in `DealServiceTest`, 5 in `DealControllerTest`; re-derive line numbers, the test file has moved). | vikram | B0-47 | compiles; `doCounter` records `MEERA_COUNTER` |
| **B0-49** | `DraftCard` + approve flow; counter prefill wiring — `handleSubmitCounterForm` must actually pass `dealTerms` and `meeraDraftId`. `CounterProposalFormData` lives in `counter-proposal-form.tsx`; plain `useState`, no zod schema to change. | ananya | B0-47 | "Use in counter" carries structured terms |
| **B0-50** | Wave 5 QA + gate. | kavya → **meera** | B0-48, B0-49 | suites green |

**Wave 5 flow:** `vikram (B0-46→48) → ananya (B0-49) → kavya → meera (B0-50) → priya.`

---

## 10. Wave 6 — cap, authorship, chat surface

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-51** | Brief-extraction cap: **`check_creator_spend_gate(f"{pid}:brief", "CREATOR", cap_usd=…)`** — the monthly creator gate with a cap override, **not** `check_spend_gate(workspace_id=…)`, which is the daily workspace gate and accepts no override. New `BRIEF_EXTRACT_MONTHLY_CAP_USD = 0.25`. | vikram | Wave 4 | a creator at the chat cap can still extract; at the brief cap gets the `cap` body |
| **B0-52** | `degraded_reason` on `BriefAnalysisResponse` (`cap` \| `ai_unavailable` \| null) — needed because the real Python invariant returns `success: true` with `message_source: FALLBACK` for both, so the two are otherwise indistinguishable. | vikram | B0-51 | fallback output is labelled, not disguised |
| **B0-53** | Raise `AI_CREATOR_MONTHLY_CAP_USD` to `2.00`; add `resets_on` to the cap error body (today the reset date is prose inside the message). | **meera** + rohan | B0-06 | env set in all declaring files; Rohan confirms exposure |
| **B0-54** | Cap-message block in `MeeraCopilotChat` naming the three surfaces that still work — **both** cap sites (stream path and `.catch` path), and a message variant that can hold links, since today both push a plain string. | ananya | B0-53 | three links render on `CREATOR_MONTHLY_CAP_REACHED` |
| **B0-55** | Brand-visible metadata split: keep `agent` and `auto_sent`, strip `send_log_id`, `draft_id`, `intent`, `edited`. Implement as **`brandVisibleMetadata`** applied at `listMessages`, `sendMessage` and `publishToStream` — **not** a signature change to `toMessageResponse`, which has **nine** call sites. Property `influora.meera.brand-facing-stamp` in all **four** declaring files. | vikram | B0-47 | brand sees the label, never the ids |
| **B0-56** | Correct §9's `DealMessageMetadataStripTest` row — as written it asserts the opposite of §14.3. | priya | B0-55 | §9 and §14.3 agree |
| **B0-57** | Brand-chat stamp render: one muted line under a Meera-authored bubble. | ananya | B0-55 | `brand-chat.meera-stamp.test.tsx` green |
| **B0-58** | Tool events in `MeeraCopilotChat` — the hook already declares and dispatches `onToolStart`/`onToolResult`; the component simply stops ignoring them. Deal-page risks with the supersede-token guard; 403 ignored. | ananya | B0-37 | card attaches to the right bubble |
| **B0-59** | Wave 6 QA + barrier re-review + gate. | kavya → kabir → **meera** | B0-57, B0-58 | suites green; no leak |

**Wave 6 flow:** `vikram ∥ ananya → kavya → kabir → meera → priya ∥ rohan.`

---

## 11. Wave 7 — verify

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-60** | Full backend suite; run `mvn` with cwd **inside** `influora-api` (there is no aggregator pom, so `-pl` runs nothing); never pipe through grep (it masks BUILD FAILURE). | **meera** | Wave 6 | green; Skipped count read and explained |
| **B0-61** | Full `pytest`; full `tsc` + `vite build` + `vitest`. | **meera** | Wave 6 | green |
| **B0-62** | `InfoBarrierRuntimeTest` extension: no `CreatorToolDtos.PackageQuote` on any brand payload carries a floor; brand context never carries `tools_enabled`; `RATE_QUOTE_ISSUED` detail carries no floor key. | kabir → **meera** | B0-60 | zero leaks |
| **B0-63** | Answer the ten §11 acceptance questions from code, adjusted to B0's six tools. | priya | B0-62 | ten answers, each citing file and line |
| **B0-64** | Live E2E on the deployed build: paste a real brief, read the flags, approve a draft. | **neha** | B0-02, B0-63 | the journey completes in a real browser |
| **B0-65** | Wave report. | tara | B0-64 | `REPORT.md` in this folder, Phase A header format |

**Wave 7 flow:** `meera → kabir → priya → neha → tara.`

---

## 12. Wave 8 — measure. Runs after B0 is live; gates B1.

| ID | Task | Owner | Dep | Done when |
|---|---|---|---|---|
| **B0-66** | Instrument the five §14.5.c metrics. Metric 3 reads `status = SENT` and `edited = 0` — **not** the `APPROVED`/`EDITED` enum values, which nothing writes. | vikram | B0-64 | all five computable from real rows |
| **B0-67** | Hand-review the first 50 briefs carrying a flag; judge CRITICAL precision and the two false-positive rates. | kavya + priya | 50 briefs exist | a judged sample, not an estimate |
| **B0-68** | `B0-METRICS.md` — five metrics, Phase A `REPORT.md` header block (For / From / Date / Branch / Sources). | tara | B0-66, B0-67 | one page, to Swapnil |
| **B0-69** | B1 go / no-go. | **swapnil** | B0-68 | a decision on the record |

**Wave 8 flow:** `vikram → kavya ∥ priya → tara → swapnil.`

---

## 13. Swapnil's four rulings, and what each blocks

| Ruling | Default if silent | Blocks |
|---|---|---|
| Brand-facing Meera label | shown | B0-55, B0-57 |
| Referral attribution | column recorded, unpaid | nothing in B0 (B1 only) |
| Creator cap → $2.00 | yes | B0-53 |
| B0 → B1 gate thresholds | the five in §14.5.c | B0-69 |

A fifth question surfaced by §14.6 W9 and not yet on the list: **brand registration requires an Indian mobile number.** Every non-Indian brand a creator brings through a secure link is blocked at sign-up. That is B1's problem, but the ruling should be taken now, alongside the four.

---

## 14. Dependency map

```
B0-01 (CI gate)  ─┐
B0-03 (branch)   ─┼─► WAVE 1 ─► WAVE 2 ─┬─► WAVE 3 ─┐
B0-04,05,07,08   ─┘   foundation  tools  │   pricing │
                                          └─► WAVE 4 ─┴─► WAVE 5 ─► WAVE 6 ─► WAVE 7 ─► ship
B0-02 (Phase A deploy) ───────────────────────────────────────────────┘         │
                                                                                 ▼
                                                                    WAVE 8 ─► B1 go/no-go
```

Critical path: **B0-01 → Wave 1 → Wave 2 → Wave 4 → Wave 5 → Wave 6 → Wave 7**. Wave 3 runs parallel to Wave 4 once Wave 2 lands. **B0-02 is on nobody's build path but gates Wave 8 entirely** — start it now, not in week two.

Rough sizing: Wave 1 ≈ 2.5d, Wave 2 ≈ 2d, Wave 3 ≈ 2.5d, Wave 4 ≈ 1.5d, Wave 5 ≈ 1.5d, Wave 6 ≈ 1.5d, Wave 7 ≈ 1d. **Total ≈ 12.5 engineer-days, slightly above §14.5's ≈10-12 estimate** — the difference is the §14.6 corrections and the FINDING-1 day-4 split, neither of which §14.5 costed. Waves 0 and 8 are not engineering days.

---

## 15. What this document does not do

- It does not build, edit or test any source file. Nothing in B0-01 … B0-69 has been started.
- It does not re-verify §1-13 of the spec. `PRIYA-COMPAT-0904.md` settled that surface against `143ca1e`; §1.2 above measures what has moved since, and **B0-05 is the task that closes the rest**. Line numbers in B0 tasks are the spec's, not re-verified today except the ten in §1.2.
- It does not size tasks by calendar date, only by dependency and rough days.
- It does not cover Phase B1, and it does not cover the credits sub-track (§15 / `CREDITS-SPEC.md`), which stays dark in B0 by B0-08.
- FINDING-1 and FINDING-2 are stated but **not fixed** — they are tasks B0-07 and B0-01, owned and unstarted.
