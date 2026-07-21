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
