# Priya — Wave 1 Architecture Consistency Gate

> **Author:** Priya (CTO) · **Date:** 2026-07-11 · **Status:** RULING (binding)
> **Requested by:** Ash (AI reviewer), routed on Swapnil's org
> **Scope:** Consistency gate over the locked AI-features spec pack before build.
> **Sources verified (read, not summarized):** `00-AI-FEATURES-ARCHITECTURE.md` §3–§5;
> `vikram-backend-spec.md`; `meera-database-devops-spec.md`; `ananya-frontend-spec.md`;
> `ash-ai-spec.md`; `kabir-security-spec.md`; `kavya-qa-spec.md`;
> `influora-api/.../web/dto/meera/MeeraToolDtos.java`; `influora-ai/app/tools/schemas.py`;
> `docs/AI connect/backend/01-DATA-MODEL.md`; `.../db/migration/V13__campaign_intents.sql`;
> `.../db/migration/V30__campaigns_campaign_type.sql`;
> `.../domain/enums/CampaignIntentType.java`;
> `.../service/meera/tool/CreateCampaignExecutor.java`;
> `.../service/IntegrationHealthService.java`.

---

## DELIVERABLE 1 — CONTRACT-DRIFT AUDIT: `CreatorFitProfile`

### State of the three definitions (verified against source)

- **Java record** — does **not yet exist** in `MeeraToolDtos.java`. §4 of the architecture doc is
  the *proposed* record ("ADD to existing file"); Vikram writes it in Wave 2 (V2.3). The current
  file holds `CreatorSummary` etc. only. So §4's Java block is the spec, and it is what must be
  corrected here before Vikram types it.
- **TS interface** — exists in two places: the §4 embedded TS block **and** Ananya F1. They are
  **not identical** — the doc contradicts itself (see below).
- **Python `tool_result` shape** — has **no concrete definition**. `schemas.py` defines only
  `input_schema` per tool; the result shape is the JSON serialization of the Java record. It cannot
  "drift" on its own — but Meera's CI diff-check (T4) must pin a Python expected-shape fixture to
  the Java record field-for-field, including nullability.

**Field NAMES: zero drift.** All 15 fields spell identically across §4 Java, §4 TS, and Ananya F1
(camelCase matches). Every mismatch below is **nullability** or **`riskFlags` type/vocabulary**.

### Canonical rule

Java is canonical per §4. But the canonical *shape* is the DB-backed reality: `creator_scores`
columns and `creator_reliability_stats` (V48) are all nullable, and Meera's definitions are
emphatic — a new creator's `completion_rate` is **NULL, never 0.00** (`meera` T1). Ananya F1 and
Meera T1 encode reality. **The architecture-doc §4 Java record and its embedded TS block are the
ones that are wrong.** I am correcting §4, not Ananya.

### The mismatches (7)

| # | Field | §4 Java record | §4 embedded TS | Ananya F1 TS | DB reality | Ruling |
|---|---|---|---|---|---|---|
| 1 | `completionRate` | non-null | `number` (non-null) | `number \| null` | `completion_rate ... NULL` (V48) | **nullable** — Ananya correct; fix §4 Java + §4 TS |
| 2 | `onTimeRate` | non-null | `number` (non-null) | `number \| null` | `on_time_rate ... NULL` (V48) | **nullable** — fix §4 Java + §4 TS |
| 3 | `avgResponseMinutes` | `Integer`, no `@Nullable` | `number` (non-null) | `number \| null` | `avg_response_minutes ... NULL` (V48, likely underivable) | **nullable** — annotate §4 Java `@Nullable`; fix §4 TS |
| 4 | `qualityScore` | non-null | `number \| null` | `number \| null` | `creator_scores.quality_score` null pre-job | **nullable** — fix §4 Java only (§4 TS already right) |
| 5 | `fakeFollowerScore` | non-null | `number \| null` | `number \| null` | `creator_scores.fake_follower_score` null pre-job | **nullable** — fix §4 Java only |
| 6 | `riskFlags` **type** | `List<String>` | `string[]` | `RiskFlag[]` (5-value union) | n/a | **enum-backed on both sides** — Ananya + Vikram V2.1 + Kavya Q1.3 intent wins. `List<String>`/`string[]` are both wrong |
| 7 | `riskFlags` **value format** | example `["missed_deadline_1_of_8"]` | — | union token `'missed_deadline'` | n/a | **enum tokens only** — parameterized `_1_of_8` cannot satisfy Ananya's fixed union or Kavya Q1.3 exact-match |

**Nullability mismatches: 5 (items 1–5). Field-name mismatches: 0. `riskFlags` type/vocab: 2.
Total drift: 7.**

Note the internal contradiction: the §4 embedded TS already marks `qualityScore`/`fakeFollowerScore`
as `| null` but leaves `completionRate`/`onTimeRate`/`avgResponseMinutes` non-null — a third variant
inconsistent with its own Java record. The doc contradicts itself; that alone justifies the gate.

### RULING (binding — corrections to my own §4 before Wave 2 starts)

1. **§4 Java record** gains `@Nullable` on: `audienceCityPct`, `audienceTopCity`, `audienceFemalePct`,
   `audienceTopAgeBand`, `completionRate`, `onTimeRate`, `avgResponseMinutes`, `qualityScore`,
   `fakeFollowerScore`, `brandSafetyScore`. Non-null stay: `creatorId`, `followers`, `engagementRate`,
   `completedDeals`, `riskFlags` (empty list, never null).
2. **`riskFlags` is enum-backed on both sides.** Vikram creates a Java `enum RiskFlag` with **exactly**
   Ananya's five tokens: `missed_deadline`, `slow_responder`, `low_completion`, `high_revision_rate`,
   `unverified_audience`. Record field becomes `List<RiskFlag>`. **No parameterized tokens** — no
   `missed_deadline_1_of_8`. A count, if ever shown, is a separate field or UI-derived. The §4 example
   is corrected to `["missed_deadline"]`.
3. **§4 embedded TS block** is replaced by Ananya F1 verbatim (it is the correct mirror). Ananya F1 is
   the reference TS; do not "improve" it.
4. **Python side** is defined by Java serialization; Meera's T4 diff-check pins a fixture to the record
   incl. nullability. `@Nullable` fields serialize `null`, never `0`/`-1` (Vikram rule 3, my rule 5).

This is a **Wave 2 (S4)** correction. It does **not** touch Wave 1. I am ruling it now so Wave 2
opens clean; Vikram must not type the record until §4 carries the `@Nullable` set above.

---

## DELIVERABLE 2 — THE BLOCKING STANDARD QUESTION (Ash A1 / Kavya Q / Vikram V2.4)

**Question:** `01-DATA-MODEL.md:284` declares four campaign types
(`HYPE, DIRECT, REVIEW, STANDARD`); `schemas.py:102` exposes three (`HYPE, DIRECT, REVIEW`). Is
`STANDARD` deprecated, or is Meera structurally unable to propose a valid 4th type?

### VERDICT: `STANDARD` is **NOT deprecated**. It is the server-side default/fallback, and Meera is **deliberately, correctly** unable to propose it. The three-vs-four asymmetry is INTENTIONAL, not a drift bug.

Proof from source:

- `CampaignIntentType.java` — live enum, four values incl. `STANDARD`. Not `@Deprecated`.
- `CreateCampaignExecutor.parseCampaignType()` (lines 195–203): `campaign_type` **null → STANDARD**;
  **unparseable → STANDARD**; otherwise `valueOf(raw.toUpperCase())`. STANDARD is the value Spring
  assigns when the model proposes **nothing** or something invalid. It is the absence-of-proposal
  state, not a choice.
- `IntegrationHealthService.requiresStoreIntegration()` (line 66): only `DIRECT` requires a store;
  `HYPE/REVIEW/STANDARD` are awareness/relationship-shaped. STANDARD is a real, gated-through type.
- `V30__campaigns_campaign_type.sql:12–20`: legacy rows stay **NULL**, explicitly refusing to guess
  STANDARD because "guessing STANDARD would assert a fact ... the data doesn't support." STANDARD is
  a *neutral default*, so weak that even a backfill won't assert it.
- Persisted in `V13` and `V30` ENUMs; exercised in Java tests (`CreateCampaignExecutorTest`,
  `RequestPaymentExecutorTest`, `ConfirmLaunchExecutorTest`).

**Why Python exposes only three:** the tool schema lists the types Claude may *affirmatively choose*.
Exposing STANDARD would let the model assert a neutral no-signal type as a positive recommendation —
the exact "confident ungrounded answer" failure this whole review exists to prevent. Spring owns the
default; the model owns the three affirmative choices. Correct by construction.

### RULING (unblocks Ash A1)

- Do **NOT** add `STANDARD` to `schemas.py`'s `create_campaign` enum. The subset is by design.
- The shared-schema diff-check (Meera T4) must treat the Python `create_campaign` enum as a
  **proper subset** of `CampaignIntentType`, with `STANDARD` registered as **"server default, not
  model-proposable."** Drift = the Python enum containing a value absent from Java, or missing one of
  the three proposable types — **not** the mere absence of STANDARD.
- **Ash A1 taxonomy** defines exactly `HYPE`, `DIRECT`, `REVIEW` (the proposable three), sourced from
  Swapnil's table. Add one line: *STANDARD is the server's default when no type is proposed; Meera
  never names it.* Ash is unblocked — write the taxonomy.
- Separately, the `goal` enum drift (`schemas.py:84` `awareness|launch|conversion|review` vs
  `campaign_type` `HYPE|DIRECT|REVIEW`) is a **real** Wave 2 drift (V2.4) — two vocabularies, `review`
  meaning different things in each. That is NOT the STANDARD question and stays a Wave 2 item; fix the
  mapping AND make the diff-check enforce it (my rule 6). Does not block Wave 1.

---

## DELIVERABLE 3 — GO / NO-GO: WAVE 1

**Verdict: GO.** S1 (injection hardening), S2 (tier parity), S3 (brand-safety wire-up) are internally
consistent, touch no `CreatorFitProfile` contract, and depend on nothing the drift above blocks. The
DTO corrections and the `goal`/STANDARD taxonomy are all Wave 2.

Readiness confirmed against source:
- **S1** — `_neutralize_angle_brackets` precedent exists (`brand_safety.py`); both bypass payloads
  specified (Ash A4, Kabir K1). Clear scope. GO.
- **S2** — `TOOL_TIERS` + `is_money_tool()` already present (`schemas.py:40–46,157`); Java gate is the
  reference. Mirror-in-Python is well-defined. GO.
- **S3** — both ends built; three V22 columns exist and are NULL; no migration (V22 header + Meera T2).
  GO.

### P0s — none block Vikram from STARTING. All are pre-merge / pre-run gates:

- **P0-A (S3, operational — HARD RUN GATE).** No production brand-safety **backfill run** until, in
  writing: (1) Rohan spend ceiling, (2) kill switch, (3) Swapnil-approved 20-creator dry-run cost
  report. Building/wiring `BrandSafetyScoreService` into `ScoreCalculationJob` is unblocked; the
  loop-over-all-creators run is gated. (Meera T2, Kabir K3.)
- **P0-B (S3, correctness).** Python `ok=False` → the three columns stay **NULL**, never `0`. "A
  creator with no safety score is unscored, not unsafe." Enforce in code + Kavya null-state test.
- **P0-C (S1).** Kabir K1.1–K1.5 land **failing on `main` first**, pass after the fix. Both delimiter
  bypasses dead; Block-B `classify_site` injection (K1.3) neutralized + length-capped. Ship gate.
- **P0-D (S2).** `allow_commit_tools` sourced **only** from verified token claims, **never** request
  body (Kabir K1.5). Default `False`. Fail-closed on unknown/mis-tiered tools **before** the HTTP
  forward. `is_money_tool()` gets a production caller.

Not Wave 1 blockers (Wave 2 or non-blocking): CI shared-schema diff-check (Meera T4 — required before
S4/S5, not S1–S3), `CreatorFitProfile` `@Nullable` corrections, `goal` drift, STANDARD taxonomy line.

---

## Order stands

Meera (V48 + nullability report) → Vikram (service + corrected DTO) → Ash (prompt) → Ananya (UI) →
Kabir → Kavya → Priya sign-off. Nobody starts a downstream layer before the layer above it merges.
For Wave 1 specifically, S1/S2/S3 may proceed in parallel now under the four P0 gates above.

— Priya, CTO
