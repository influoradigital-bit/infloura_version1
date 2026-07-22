# influora-ai eval harness

Golden-set eval loop for the live AI features, so a prompt or model
change can't silently regress quality:

| Dataset | Feature | Route / contract | Provider |
|---|---|---|---|
| `brand_safety_garm` | GARM brand-safety classification | `POST /internal/brand-safety` — forced `analyze_creator_content` tool (`app/tools/schemas.py`, `app/prompt/brand_safety.py`) | Claude |
| `analyze_site_classify` | Website niche/tone classification | `POST /analyze-site` — `_CLASSIFY_SYSTEM_INSTRUCTION` JSON contract (`app/providers/gemini.py`) | Gemini |
| `analyze_site_extraction` | P1-B structured product-fact extraction (JSON-LD/OpenGraph/microdata) from raw HTML | `perform_site_analysis` (`app/routes/analyze_site.py`) — `app/prompt/structured_extract.py`'s deterministic parser | none (pure function, no provider call) |
| `trend_tag` | Trend-Spark closed-vocab recovery tagger | `POST /internal/trendspark/tag` — closed theme/campaign vocab (`app/prompt/trend_tag.py`) | Claude (Haiku-class) |
| `template_recommendation` | Platform-AI Phase 1 — campaign-template recommendation | `POST /chat` (`create_campaign.template_id`, `app/tools/schemas.py` + `app/prompt/assembler.py::build_block_b`'s `template_digest` rendering) — Ash's binding W2 eval gate | Claude |
| `outcome_recommendation` | Phase-2 moat — Meera's outcome/ROI **prose** grounded on the outcome digest + `get_campaign_performance` result | `POST /chat` over Block B's `outcome_digest` (`app/prompt/assembler.py::_render_outcome_digest`) — Ash's `provenance_exact_match` gate | Claude |
| `campaign_performance` | Phase-2 moat — `GetCampaignPerformanceExecutor` **structured** output (PLATFORM_VERIFIED-only filter, PII strip, 404-on-IDOR) | `POST /internal/meera/get_campaign_performance` (Java executor) | none (Java-executor determinism; fixtures recorded from the Spring integration test) |

The harness has two modes:

- **Offline** (default for CI): runs every golden case against recorded
  fixture responses in `evals/fixtures/<dataset>/`. No API keys, no network,
  fully deterministic. This is what `tests/evals/test_eval_harness_offline.py`
  runs on every CI pass.
- **Live** (manual, keyed): calls the real provider with the real prompt
  builders imported from `app/`. Skips with a clear message (exit 0) if the
  needed key (`ANTHROPIC_API_KEY` / `GEMINI_API_KEY`) is unset.

## Run offline

From `influora-ai/`:

```bash
PYTHONUTF8=1 python evals/run_eval.py --offline brand_safety_garm
PYTHONUTF8=1 python evals/run_eval.py --offline all
```

Exit code 0 = all thresholds met; 1 = a threshold failed, a fixture is
missing, or a case errored. The CI wrapper:

```bash
PYTHONUTF8=1 python -m pytest tests/evals -q
```

### Offline thresholds (the regression gate)

| Dataset | Metric | Bar |
|---|---|---|
| brand_safety_garm | unsafe/safe accuracy | >= 0.90 |
| brand_safety_garm | flagged-category F1 (categories at medium/high) | >= 0.85 |
| brand_safety_garm | unsafe->safe misses | **0 (hard veto)** |
| brand_safety_garm | malformed outputs (schema contract) | 0 |
| analyze_site_classify | niche-tag set-overlap F1 (open vocab, tolerant) | >= 0.60 |
| analyze_site_classify | tone bucket score (formality/energy terciles + emoji_ok) | >= 0.70 |
| analyze_site_extraction | scraped name+price+currency exact recall (facts, not tolerant F1) | 1.00 |
| analyze_site_extraction | fabricated products on a no-structured-data page | 0 (hard veto) |
| trend_tag | theme F1 (after the REAL closed-vocab validator) | >= 0.70 |
| trend_tag | campaign_type accuracy | >= 0.80 |
| trend_tag | drop agreement (recover vs drop decision) | 1.00 |
| template_recommendation | template-name accuracy (fail-blocks if <12/15 correct) | >= 0.80 |
| template_recommendation | campaign_type accuracy | >= 0.80 |
| template_recommendation | off-catalog `template_name` (malformed) | 0 (hard veto) |
| outcome_recommendation | provenance exact-match (every quoted ₹/ratio/% traceable) | >= 0.95 (>=14/15) |
| outcome_recommendation | cross-party / forbidden number leaks | 0 (hard veto) |
| campaign_performance | tool-result accuracy (verified-only executor output, exact) | 1.00 (10/10) |
| campaign_performance | PII leaks (creator name/handle/caption or forbidden data field) | 0 (hard veto) |

Notes on scoring:

- `trend_tag` raw model JSON is passed through the production validator
  (`app.prompt.trend_tag.parse_and_validate`), so invented/off-vocab themes
  never earn credit and "nothing valid survives" scores as a drop — identical
  to what n8n would see.
- `brand_safety_garm` "unsafe" is derived exactly as a brand would read it:
  any GARM category at `medium` or `high`. Outputs missing any of the 10
  categories are malformed per the route's `_validate_model_result` rules and
  score zero.

## Run live / record fixtures

```bash
# Live, current pinned models:
PYTHONUTF8=1 python evals/run_eval.py --live all

# Live + overwrite offline fixtures with real recorded responses:
PYTHONUTF8=1 python evals/run_eval.py --live --record brand_safety_garm
```

Fixtures are keyed by a sha256 of the case *input*, so editing a golden
case's input text invalidates its fixture (the offline run then fails with
`MISSING FIXTURE` instead of silently scoring a stale response). Re-record
with `--live --record`.

The seed fixtures committed here are hand-derived reference responses (a
"well-behaved model" baseline built from the golden labels, including a
couple of realistic imperfections such as an extra flagged category on the
scam case). Replace them with real recorded provider responses via
`--live --record` the first time a keyed environment runs this.

## The Sonnet -> Haiku GARM A/B (pending brand-safety model swap)

`POST /internal/brand-safety` currently runs `CLAUDE_MODEL`
(Sonnet-class). The proposed cost cut is a Haiku-class swap. To make that
defensible, run the golden set live against both models and compare:

```bash
# Run 1 — Sonnet (current pin):
BRAND_SAFETY_MODEL=claude-sonnet-4-5-20250929 \
  PYTHONUTF8=1 python evals/run_eval.py --live brand_safety_garm | tee /tmp/garm-sonnet.txt

# Run 2 — Haiku (candidate):
BRAND_SAFETY_MODEL=claude-haiku-4-5-20251001 \
  PYTHONUTF8=1 python evals/run_eval.py --live brand_safety_garm | tee /tmp/garm-haiku.txt

# Compare the two aggregate tables:
diff /tmp/garm-sonnet.txt /tmp/garm-haiku.txt
```

`BRAND_SAFETY_MODEL` is read by the eval's live caller (falling back to
`CLAUDE_MODEL`, then the current Sonnet pin), so the A/B needs no code
change. Both runs use the identical production prompt + forced-tool schema.

### Parity bar for approving the Haiku flip (Ash's recommendation)

Approve the swap **only if all four hold** on the same golden set, same day,
live mode:

1. **Flagged-category F1**: Haiku within **2 points** of Sonnet
   (`haiku_f1 >= sonnet_f1 - 0.02`).
2. **Unsafe/safe accuracy**: Haiku within **2 points** of Sonnet.
3. **Zero unsafe->safe misses** by Haiku on the golden set. This is a hard
   veto regardless of F1 — a cheaper model that lets one unsafe creator
   caption through as "safe" is not a cost saving, it is a brand-trust
   incident. (Safe->unsafe false alarms are tolerated within the F1 bar;
   they cost a manual review, not a brand placement next to hate speech.)
4. **Zero malformed outputs** from Haiku (forced-tool schema compliance must
   stay at 100% — the route 502s on malformed output, so schema drift is an
   availability regression, not just a quality one).

If Haiku fails only bar 1 or 2 by a hair, expand the golden set (12 cases is
a smoke-grid, not a benchmark) before re-running — do not lower the bar. If
it fails bar 3 even once, the flip is rejected until prompt or model changes
and a full re-run shows zero misses.

After an approved flip, immediately re-record fixtures
(`--live --record brand_safety_garm`) so the offline gate tracks the new
model's behavior.

## `provenance_exact_match` contract (Phase-2 moat — build plan §2.5)

The moat scorer (`evals/scorers.py::provenance_exact_match`) enforces SR-1 at the
presentation layer: **every currency/numeric figure Meera quotes must be traceable
to a value the tools actually returned, a declared deterministic calc of them, or a
config value — zero orphaned/hallucinated numbers, zero cross-party data.** It works
by *value traceability*, not by parsing English provenance words (stronger: a model
that mislabels a self-reported number as "verified" still fails, because the value
it quoted is not in the allowed set). Units are magnitude-only, so `₹2.5L`,
`2,50,000` and `250000` all canonicalize to the same magnitude — the dataset author
lists a plain magnitude and the model may render any surface form.

### `outcome_recommendation` case shape (prose eval — the `provenance_exact_match` target)

```jsonc
{
  "id": "or-00X-...",
  "input": {
    "user_message": "How did my Diwali Reels campaign perform?",
    "context":   { "outcome_digest": { "campaign_outcomes": [...], "niche_rate_band": ... },
                   "template_digest": [ ... ] },        // optional (CONFIG_VALUE cases)
    "tool_result": { "name": "get_campaign_performance", "output": { ... } } // optional
  },
  "expected": {
    "case_type": "adversarial_self_reported_omitted",
    "provenance": {
      "allowed_values": [
        { "value": 45000, "source": "TOOL_RETURNED",     "field": "tool_result.output.spendInr" },
        { "value": 82000, "source": "DETERMINISTIC_CALC", "field": "sum(...)", "formula": "30000 + 52000" },
        { "value": 15000, "source": "CONFIG_VALUE",       "field": "template_digest[0].budget_band" }
      ],
      "forbidden_values": [ 500000, "5,00,000", "*any_number*" ],
      "requires_omission": true,
      "omitted_fields": ["verifiedReach"],
      "notes": "..."
    }
  }
}
```

- The scored model output (fixture) is Meera's **prose**: `{"output": {"response": "<text>"}}`
  (any of `card`/`bubble`/`narrative`/`response`/`text` keys is read — card + bubble
  are both scored, per Ash's §2.5 ruling that every quoted number counts wherever it lands).
- `allowed_values[].value` — the union the model MAY quote. Only `.value` is read by
  the scorer; `source`/`field`/`formula` are provenance documentation for the reviewer.
- `forbidden_values[]` — concrete magnitudes that must NEVER appear (another party's
  number, a self-reported figure that must stay omitted). A `*wildcard*` token
  (`*any_number*`, `*any_currency_number*`, `*any_profit_or_revenue_number*`) means
  "any untraceable number here is a leak" — it escalates **every** orphan from a soft
  provenance miss to a hard-veto cross-party leak (used by the below-k-floor and IDOR
  cases where `allowed_values` is empty).
- Scoring: `provenance` = 1.0 iff every quoted figure ∈ allowed; `cross_party_leak`
  = 1.0 if any orphan matches a forbidden literal or any orphan exists under a wildcard.

### `campaign_performance` case shape (structured eval — executor determinism)

This set verifies the **Java `GetCampaignPerformanceExecutor`**, not Meera prose. The
fixture is the recorded executor tool-result; the scorer compares it field-for-field
to `expected.tool_result` (numeric compared by magnitude) and asserts no PII.

```jsonc
{
  "id": "cp-00X-...",
  "input": { "requesting_workspace_id": "...", "campaign": {...}, "collaborations": [...],
             "deliverable_metrics": [ {"source": "PLATFORM_VERIFIED"|"CREATOR_REPORTED", ...} ],
             "escrow_holds": [...], "utm_campaigns": [...], "affiliate_earnings": [...],
             "creator_scores": [...] },
  "expected": {
    "tool_result": { "campaignId": "...", "verifiedReach": 90000, "roi": 1.7778,
                     "deliverables": [ {"milestoneId": "ms_1", "reach": 50000, ...} ], ... },
    "tool_error": { "status": 404, "code": "CAMPAIGN_NOT_FOUND" },   // IDOR case; tool_result absent
    "pii_fields_must_be_absent": ["creator_name", "creator_ig_handle", "creator_caption"],
    "platform_verified_only": true
  }
}
```

- `fields_match` = 1.0 iff every `expected.tool_result` field is present and equal in
  the recorded output (so a `verifiedReach` that wrongly folds in a CREATOR_REPORTED
  row fails). For the IDOR case (`tool_result` absent, `tool_error` present) the output
  must be an error carrying no campaign data.
- `pii_leak` = 1.0 if any name in `pii_fields_must_be_absent` appears as a key anywhere
  in the output (the executor's `DeliverablePerformanceEntry` is opaque-id + numbers only).

### Recording the fixtures (the remaining step before offline-green)

`outcome_recommendation` fixtures are recorded live (`--live --record outcome_recommendation`
in a keyed env — a well-behaved Meera baseline may also be hand-seeded per the seed-fixture
convention above). `campaign_performance` fixtures are the executor outputs dumped by the
Spring integration test (there is no Python provider to call). Until fixtures land, the CI
gate `tests/evals/test_eval_harness_offline.py` **skips** the end-to-end run for these two
(cases committed, fixtures pending) while the scorer unit tests in that file fully exercise
the scoring logic and prove the gate can go red.

## Layout

```
evals/
  run_eval.py       # CLI runner; injectable model-caller; offline/live modes
  scorers.py        # dataset-agnostic scoring primitives (stdlib only)
  datasets/*.jsonl  # golden sets: one {id, input, expected} JSON per line
  fixtures/<dataset>/<input-hash>.json   # recorded responses for offline mode
tests/evals/test_eval_harness_offline.py # the CI gate
```

Adding a golden case: append a line to the dataset JSONL, then record its
fixture (`--live --record <dataset>`), then run the offline gate. Datasets
deliberately include edge cases the routes' contracts call out: empty/
emoji-only captions, romanized Hinglish (both benign and unsafe), a
prompt-injection caption, a borderline mild-profanity caption, and a
trend_tag case whose correct answer is "drop the row".
