"""Golden-set eval runner for influora-ai's three live AI features.

Usage (from the influora-ai/ directory):

    # Offline (CI, no API keys, runs against recorded fixtures):
    PYTHONUTF8=1 python evals/run_eval.py --offline brand_safety_garm
    PYTHONUTF8=1 python evals/run_eval.py --offline all

    # Live (manual, requires provider API keys; skips cleanly if unset):
    PYTHONUTF8=1 python evals/run_eval.py --live brand_safety_garm
    BRAND_SAFETY_MODEL=claude-haiku-4-5-20251001 \
        PYTHONUTF8=1 python evals/run_eval.py --live brand_safety_garm

    # Live + record responses as offline fixtures for future runs:
    PYTHONUTF8=1 python evals/run_eval.py --live --record brand_safety_garm

Design notes:
- The model call is an injectable callable `caller(case_input: dict) -> dict`
  (raw model output in the same shape the real route validates). Offline mode
  injects a fixture-backed caller; live mode injects a real provider caller;
  tests inject fakes. The runner/scorers never know which.
- Offline fixtures live in evals/fixtures/<dataset>/<input-hash>.json, keyed
  by a sha256 of the canonical-JSON case input, so a fixture silently stops
  matching (and the run fails loudly with missing_fixture) the moment anyone
  edits a golden case's input without re-recording.
- Deliberately imports ONLY pure app modules (app.tools.schemas,
  app.prompt.brand_safety, app.prompt.trend_tag, app.prompt.untrusted) at
  module level. app.config / app.providers.* are imported lazily inside the
  live callers only — offline mode must run with zero env vars set and must
  not race concurrent edits to config/provider files.
- Exit codes: 0 = all thresholds met (or live mode skipped for missing keys),
  1 = threshold failure / missing fixtures, 2 = usage error.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

EVALS_DIR = Path(__file__).resolve().parent
SERVICE_ROOT = EVALS_DIR.parent  # influora-ai/
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

# Pure imports only (no env reads, no provider SDKs) — see module docstring.
from app.prompt.trend_tag import parse_and_validate  # noqa: E402
from app.tools.schemas import GARM_CATEGORIES, GARM_RISK_LEVELS  # noqa: E402
from evals.scorers import (  # noqa: E402
    exact_match,
    mean,
    provenance_exact_match,
    set_overlap_f1,
    tone_bucket_score,
)

DATASETS_DIR = EVALS_DIR / "datasets"
FIXTURES_DIR = EVALS_DIR / "fixtures"

_GARM_CATEGORY_SET = frozenset(GARM_CATEGORIES)
_GARM_RISK_SET = frozenset(GARM_RISK_LEVELS)
_UNSAFE_RISKS = frozenset({"medium", "high"})
_TREND_TAG_MAX_THEMES = 6  # mirrors Settings.trend_tag_max_themes default

# ---------------------------------------------------------------------------
# template_recommendation — Platform-AI Phase 1 (Ash's binding eval gate).
#
# The 4 SYSTEM campaign templates (influora-api
# V20260714150000__campaign_templates.sql — fixed ULIDs, seeded once, never
# edited by a migration since). This is the SAME catalog `template_digest`
# surfaces to Meera in Block B (app/prompt/assembler.py::build_block_b) for a
# real brand; here it stands in for "what a real workspace's SYSTEM digest
# looks like" so the eval exercises the actual recommendation judgment
# (product description -> which template + why) without needing a live
# Spring-backed workspace. Keep in sync with the migration if the SYSTEM
# presets ever change.
# ---------------------------------------------------------------------------
TEMPLATE_RECOMMENDATION_CATALOG: tuple[dict[str, str], ...] = (
    {
        "name": "Brand Awareness",
        "campaign_type": "HYPE",
        "budget_band": "₹10,000–₹50,000",
        "key_requirements": "Reach-oriented — maximize impressions and new-audience reach with Reels and Stories.",
    },
    {
        "name": "Sales & Conversions",
        "campaign_type": "DIRECT",
        "budget_band": "₹15,000–₹75,000",
        "key_requirements": "Conversion-oriented — drive clicks and sales via Reels and link-in-bio placements.",
    },
    {
        "name": "UGC Content Pack",
        "campaign_type": "STANDARD",
        "budget_band": "₹5,000–₹20,000",
        "key_requirements": "Content-generation — raw video/photo assets for brand reuse, lower budget band.",
    },
    {
        "name": "Affiliate / Revenue Share",
        "campaign_type": "REVIEW",
        "budget_band": "₹0–₹30,000",
        "key_requirements": "Performance — revenue-share creators post reviews/demos with a commission structure.",
    },
)
_TEMPLATE_NAME_SET = frozenset(t["name"] for t in TEMPLATE_RECOMMENDATION_CATALOG)


# ---------------------------------------------------------------------------
# Model-caller abstraction
# ---------------------------------------------------------------------------

ModelCaller = Callable[[dict[str, Any]], dict[str, Any]]


class FixtureMissingError(Exception):
    def __init__(self, fixture_path: Path):
        self.fixture_path = fixture_path
        super().__init__(f"missing fixture: {fixture_path}")


def case_input_key(case_input: dict[str, Any]) -> str:
    """Stable content-hash key for a golden case's input. Any change to the
    input text invalidates the fixture, which is the point."""
    canonical = json.dumps(case_input, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def make_offline_caller(dataset_name: str) -> ModelCaller:
    fixture_dir = FIXTURES_DIR / dataset_name

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        path = fixture_dir / f"{case_input_key(case_input)}.json"
        if not path.exists():
            raise FixtureMissingError(path)
        with path.open(encoding="utf-8") as fh:
            recorded = json.load(fh)
        return recorded["output"]

    return caller


def record_fixture(dataset_name: str, case_id: str, case_input: dict[str, Any], output: dict[str, Any]) -> Path:
    fixture_dir = FIXTURES_DIR / dataset_name
    fixture_dir.mkdir(parents=True, exist_ok=True)
    path = fixture_dir / f"{case_input_key(case_input)}.json"
    payload = {"case_id": case_id, "input": case_input, "output": output}
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    return path


# ---------------------------------------------------------------------------
# Live callers (lazy imports; guarded by required_env_key at the CLI level)
# ---------------------------------------------------------------------------


def make_live_brand_safety_caller() -> ModelCaller:
    import anthropic  # lazy

    from app.prompt.brand_safety import build_system_block, build_user_message
    from app.tools.schemas import get_analyze_creator_content_schema

    # The pending Sonnet->Haiku A/B swaps this env var between two runs.
    model = os.getenv("BRAND_SAFETY_MODEL") or os.getenv("CLAUDE_MODEL") or "claude-sonnet-4-5-20250929"
    client = anthropic.Anthropic()
    tool_schema = get_analyze_creator_content_schema()

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        item = {
            "content_id": "eval-item-1",
            "caption": case_input.get("caption") or "",
            "media_type": case_input.get("media_type"),
            "posted_at": None,
        }
        response = client.messages.create(
            model=model,
            max_tokens=4096,
            system=[build_system_block()],
            messages=[build_user_message([item])],
            tools=[tool_schema],
            tool_choice={"type": "tool", "name": tool_schema["name"]},
        )
        tool_use = next(b for b in response.content if getattr(b, "type", None) == "tool_use")
        items = tool_use.input.get("items") or []
        return items[0] if items else {}

    return caller


def make_live_analyze_site_caller() -> ModelCaller:
    from google import genai  # lazy
    from google.genai import types as genai_types

    # Import for the REAL system instruction — do not fork/duplicate it here,
    # or the eval would silently test a stale prompt.
    from app.providers.gemini import _CLASSIFY_SYSTEM_INSTRUCTION

    try:
        from app.prompt.assembler import wrap_untrusted_scrape
    except Exception:  # pragma: no cover - assembler shape may change
        wrap_untrusted_scrape = lambda text: text  # noqa: E731

    model = os.getenv("GEMINI_MODEL") or "gemini-2.5-flash"
    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        payload = wrap_untrusted_scrape(str(case_input.get("page_text") or "")[:20000])
        response = client.models.generate_content(
            model=model,
            contents=payload,
            config=genai_types.GenerateContentConfig(
                system_instruction=_CLASSIFY_SYSTEM_INSTRUCTION,
                temperature=0.2,
                max_output_tokens=1024,
                response_mime_type="application/json",
            ),
        )
        return json.loads(response.text or "{}")

    return caller


def make_live_analyze_site_extraction_caller() -> ModelCaller:
    """P1-B eval: exercises the REAL, deterministic structured-extraction
    parser (app/prompt/structured_extract.py) against real-HTML-shaped
    fixtures -- Shopify-style JSON-LD @graph on a sparse SPA body, a plain
    static-site JSON-LD Product, and a page with zero structured data. No
    provider call, no API key needed -- "live" and "offline" produce
    identical output since the function is pure, but the harness still needs
    a caller wired through the same Feature machinery as the other datasets
    (an --offline fixture is recorded once so CI never needs network either
    way). This is the DoD scorer that previously didn't exist: today's
    analyze_site_classify eval only checked niche_tags/tone on pre-cleaned
    text and never exercised whether scraped products/prices are actually
    recovered from raw HTML.
    """
    from app.prompt.structured_extract import extract_structured_facts

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        products = extract_structured_facts(str(case_input.get("raw_html") or ""))
        return {
            "scraped_products": [
                {"name": p.name, "price": p.price, "currency": p.currency} for p in products
            ],
            "source": products[0].source if products else "none",
        }

    return caller


def make_live_template_recommendation_caller() -> ModelCaller:
    """Drives the REAL persona + a synthetic Block B carrying the SYSTEM
    template digest (`TEMPLATE_RECOMMENDATION_CATALOG`), exactly the shape
    `build_block_b` renders for a real brand, then forces a single
    `recommend_template` tool call so the response is structured JSON instead
    of prose. Mirrors `make_live_brand_safety_caller`'s forced-tool pattern.
    Uses the persona's real system prompt (`get_persona_block` + Block B) —
    not a bespoke eval-only prompt — so a persona wording change that breaks
    template recommendations shows up here.
    """
    import anthropic  # lazy

    from app.prompt.assembler import build_block_a, build_block_b

    model = os.getenv("TEMPLATE_RECOMMENDATION_MODEL") or os.getenv("CLAUDE_MODEL") or "claude-sonnet-4-5-20250929"
    client = anthropic.Anthropic()

    recommend_tool = {
        "name": "recommend_template",
        "description": "Recommend the ONE best-fit campaign template for this product/goal.",
        "input_schema": {
            "type": "object",
            "properties": {
                "template_name": {"type": "string", "enum": sorted(_TEMPLATE_NAME_SET)},
                "campaign_type": {"type": "string", "enum": ["HYPE", "DIRECT", "STANDARD", "REVIEW"]},
                "budget_band": {"type": "string"},
            },
            "required": ["template_name", "campaign_type", "budget_band"],
        },
    }

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        block_a = build_block_a()
        block_b = build_block_b(
            {
                "workspace_id": "eval-workspace",
                "brand": {"template_digest": list(TEMPLATE_RECOMMENDATION_CATALOG)},
            }
        )
        response = client.messages.create(
            model=model,
            max_tokens=512,
            system=[block_a, block_b],
            messages=[
                {
                    "role": "user",
                    "content": str(case_input.get("product_description") or ""),
                }
            ],
            tools=[recommend_tool],
            tool_choice={"type": "tool", "name": "recommend_template"},
        )
        tool_use = next((b for b in response.content if getattr(b, "type", None) == "tool_use"), None)
        return dict(tool_use.input) if tool_use is not None else {}

    return caller


def make_live_trend_tag_caller() -> ModelCaller:
    import re

    import anthropic  # lazy

    from app.prompt.trend_tag import build_system_prompt, build_user_message

    model = os.getenv("TREND_TAG_MODEL") or os.getenv("TRENDSPARK_MODEL") or "claude-haiku-4-5-20251001"
    client = anthropic.Anthropic()
    fence_re = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        user = build_user_message(
            trend_text=str(case_input.get("trend_text") or ""),
            source=case_input.get("source"),
            category=str(case_input.get("category") or ""),
        )
        response = client.messages.create(
            model=model,
            max_tokens=80,
            system=[{"type": "text", "text": build_system_prompt()}],
            messages=[{"role": "user", "content": user}],
        )
        text_block = next((b for b in response.content if getattr(b, "type", None) == "text"), None)
        text = fence_re.sub("", (getattr(text_block, "text", "") or "").strip()).strip()
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = {}
        return parsed if isinstance(parsed, dict) else {}

    return caller


def make_live_outcome_recommendation_caller() -> ModelCaller:
    """Live caller for the `outcome_recommendation` provenance set.

    Drives the REAL Meera persona (`build_block_a`) + a synthetic Block B carrying
    the case's `context.outcome_digest` (and `context.template_digest` when the
    case exercises a CONFIG_VALUE budget band) — exactly the shape `build_block_b`
    renders for a real brand via `_render_outcome_digest` — then replays the
    optional `tool_result` as an assistant/tool turn and lets Meera respond in
    prose. Provenance risk is precisely what she QUOTES in free text, so no tool
    is forced; the returned `{"response": text}` is what `provenance_exact_match`
    scores. Offline mode never calls this (fixtures back it) — it exists so a
    keyed environment can record real fixtures with `--live --record`."""
    import anthropic  # lazy

    from app.prompt.assembler import build_block_a, build_block_b

    model = os.getenv("MEERA_MODEL") or os.getenv("CLAUDE_MODEL") or "claude-sonnet-4-5-20250929"
    client = anthropic.Anthropic()

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        context = case_input.get("context") or {}
        brand: dict[str, Any] = {"outcome_digest": context.get("outcome_digest") or {}}
        if context.get("template_digest"):
            brand["template_digest"] = context["template_digest"]
        block_a = build_block_a()
        block_b = build_block_b({"workspace_id": "eval-workspace", "brand": brand})

        user_message = str(case_input.get("user_message") or "")
        # A tool_result, when present, is the ground-truth number set the model may
        # quote — surface it as a system addendum so the eval mirrors the real SSE
        # tool-call round-trip without needing a live Spring backend.
        tool_result = case_input.get("tool_result")
        system_blocks = [block_a, block_b]
        if tool_result:
            system_blocks.append(
                {
                    "type": "text",
                    "text": "get_campaign_performance tool result (authoritative numbers): "
                    + json.dumps(tool_result, ensure_ascii=False),
                }
            )
        response = client.messages.create(
            model=model,
            max_tokens=600,
            system=system_blocks,
            messages=[{"role": "user", "content": user_message}],
        )
        text = "".join(
            getattr(b, "text", "") for b in response.content if getattr(b, "type", None) == "text"
        )
        return {"response": text}

    return caller


def make_live_campaign_performance_caller() -> ModelCaller:
    """`campaign_performance` verifies the Java `GetCampaignPerformanceExecutor`'s
    deterministic output (PLATFORM_VERIFIED-only filter, PII strip, 404-on-IDOR) —
    there is NO Python provider to call. Its offline fixtures are the recorded
    executor tool-results (produced by the Spring integration test that dumps
    `GetCampaignPerformanceResult` per case input, or the browser SSE round-trip),
    same idea as `analyze_site_extraction` being a pure-function eval. A live
    Python caller is therefore not meaningful; this raises so `--live` fails loudly
    rather than silently scoring nothing. Run this dataset with `--offline`."""

    def caller(case_input: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError(
            "campaign_performance is a Java-executor determinism eval — record its "
            "fixtures from the Spring integration test, then run with --offline."
        )

    return caller


# ---------------------------------------------------------------------------
# Per-feature scorers: raw model output + expected -> {metric: 0..1}
# ---------------------------------------------------------------------------


def _meera_response_text(raw: dict[str, Any] | str) -> str:
    """Pull the assistant text out of a model-output record for the provenance
    scorers. Accepts a bare string, or a dict carrying the response under any of
    the conventional keys (card + bubble are BOTH scored per Ash's §2.5 ruling —
    every quoted number counts, whichever surface it lands on). Falls back to
    concatenating every string value so a fixture can't hide a number in an
    unexpected key."""
    if isinstance(raw, str):
        return raw
    if not isinstance(raw, dict):
        return ""
    preferred = ("card", "bubble", "narrative", "response", "text", "assistant_text", "message")
    parts = [str(raw[k]) for k in preferred if isinstance(raw.get(k), str)]
    if not parts:
        parts = [v for v in raw.values() if isinstance(v, str)]
    return "\n".join(parts)


def _garm_flagged_categories(raw: dict[str, Any]) -> list[str] | None:
    """Categories the model scored at medium/high ("unsafe" per the GARM
    floor contract). Returns None when the output is malformed by the same
    rules the route's _validate_model_result enforces (all 10 categories
    present, enum-valid risks) — a malformed output is scored as a hard miss,
    never quietly patched over."""
    flags = raw.get("garm_flags")
    if not isinstance(flags, list):
        return None
    seen: dict[str, str] = {}
    for flag in flags:
        if not isinstance(flag, dict):
            return None
        category, risk = flag.get("category"), flag.get("risk")
        if category not in _GARM_CATEGORY_SET or risk not in _GARM_RISK_SET:
            return None
        seen[category] = risk
    if set(seen) != _GARM_CATEGORY_SET:
        return None
    return [c for c, r in seen.items() if r in _UNSAFE_RISKS]


def score_brand_safety(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    flagged = _garm_flagged_categories(raw)
    exp_unsafe = bool(expected["unsafe"])
    if flagged is None:
        # Malformed = model failed the schema contract entirely.
        return {
            "unsafe_acc": 0.0,
            "category_f1": 0.0,
            "unsafe_to_safe_miss": 1.0 if exp_unsafe else 0.0,
            "malformed": 1.0,
        }
    pred_unsafe = bool(flagged)
    overlap = set_overlap_f1(flagged, expected.get("flagged_categories") or [])
    return {
        "unsafe_acc": exact_match(pred_unsafe, exp_unsafe),
        "category_f1": overlap.f1,
        # The one unforgivable direction: expected unsafe, model said safe.
        "unsafe_to_safe_miss": 1.0 if (exp_unsafe and not pred_unsafe) else 0.0,
        "malformed": 0.0,
    }


def aggregate_brand_safety(per_case: list[dict[str, float]]) -> tuple[dict[str, float], list[str]]:
    agg = {
        "unsafe_acc": mean([s["unsafe_acc"] for s in per_case]),
        "category_f1": mean([s["category_f1"] for s in per_case]),
        "unsafe_to_safe_misses": sum(s["unsafe_to_safe_miss"] for s in per_case),
        "malformed_outputs": sum(s["malformed"] for s in per_case),
    }
    failures = []
    if agg["unsafe_acc"] < 0.90:
        failures.append(f"unsafe/safe accuracy {agg['unsafe_acc']:.2f} < 0.90")
    if agg["category_f1"] < 0.85:
        failures.append(f"flagged-category F1 {agg['category_f1']:.2f} < 0.85")
    if agg["unsafe_to_safe_misses"] > 0:
        failures.append(f"{agg['unsafe_to_safe_misses']:.0f} unsafe->safe miss(es) (hard veto: must be 0)")
    if agg["malformed_outputs"] > 0:
        failures.append(f"{agg['malformed_outputs']:.0f} malformed output(s) (must be 0)")
    return agg, failures


def score_analyze_site(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    niche_tags = raw.get("niche_tags")
    tone_dial = raw.get("tone_dial")
    if not isinstance(niche_tags, list) or not isinstance(tone_dial, dict):
        return {"niche_f1": 0.0, "tone_score": 0.0, "malformed": 1.0}
    overlap = set_overlap_f1([str(t) for t in niche_tags], expected.get("niche_tags") or [])
    return {
        "niche_f1": overlap.f1,
        "tone_score": tone_bucket_score(tone_dial, expected.get("tone") or {}),
        "malformed": 0.0,
    }


def aggregate_analyze_site(per_case: list[dict[str, float]]) -> tuple[dict[str, float], list[str]]:
    agg = {
        "niche_f1": mean([s["niche_f1"] for s in per_case]),
        "tone_score": mean([s["tone_score"] for s in per_case]),
        "malformed_outputs": sum(s["malformed"] for s in per_case),
    }
    failures = []
    # niche_tags is an OPEN vocabulary (unlike trend_tag) — exact tag wording
    # varies legitimately, so the bar is set-overlap-tolerant and lower.
    if agg["niche_f1"] < 0.60:
        failures.append(f"niche-tag F1 {agg['niche_f1']:.2f} < 0.60")
    if agg["tone_score"] < 0.70:
        failures.append(f"tone bucket score {agg['tone_score']:.2f} < 0.70")
    if agg["malformed_outputs"] > 0:
        failures.append(f"{agg['malformed_outputs']:.0f} malformed output(s) (must be 0)")
    return agg, failures


def score_analyze_site_extraction(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    """Exact name+price+currency recall against the deterministic structured
    extractor's output. Unlike niche_f1 (open vocabulary, tolerant), a
    scraped price is a FACT -- there is no partial credit for a wrong price,
    since a wrong "scraped" price is exactly the money-path hallucination
    risk P1-B exists to close."""
    expected_products = expected.get("scraped_products") or []
    actual_products = raw.get("scraped_products") or []
    actual_by_name = {p.get("name"): p for p in actual_products if isinstance(p, dict)}

    if not expected_products:
        # A page with no structured data must recover nothing -- recovering
        # a phantom product here would itself be a bug (fabrication).
        return {"recall": 1.0 if not actual_products else 0.0, "false_positive": 1.0 if actual_products else 0.0}

    hits = 0
    for exp in expected_products:
        act = actual_by_name.get(exp["name"])
        if act is not None and act.get("price") == exp.get("price") and act.get("currency") == exp.get("currency"):
            hits += 1
    return {"recall": hits / len(expected_products), "false_positive": 0.0}


def aggregate_analyze_site_extraction(per_case: list[dict[str, float]]) -> tuple[dict[str, float], list[str]]:
    agg = {
        "recall": mean([s["recall"] for s in per_case]),
        "false_positives": sum(s["false_positive"] for s in per_case),
    }
    failures = []
    # Exact-match, not F1-tolerant like niche tags: a scraped price/name is a
    # verified fact, so every golden case must recover it exactly.
    if agg["recall"] < 1.0:
        failures.append(f"structured-extraction recall {agg['recall']:.2f} < 1.00 (must recover every expected fact exactly)")
    if agg["false_positives"] > 0:
        failures.append(f"{agg['false_positives']:.0f} fabricated product(s) on a no-structured-data page (must be 0)")
    return agg, failures


def score_trend_tag(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    # Run the model's raw JSON through the REAL closed-vocab validator the
    # route uses — off-vocab themes are dropped and "nothing valid" becomes a
    # drop, exactly as in production.
    validated = parse_and_validate(raw, max_themes=_TREND_TAG_MAX_THEMES)
    if validated is None:
        pred_themes: list[str] = []
        pred_type: str | None = None
    else:
        pred_themes, pred_type = validated
    exp_themes = expected.get("themes") or []
    exp_type = expected.get("campaign_type")  # None == expected drop
    overlap = set_overlap_f1(pred_themes, exp_themes)
    return {
        "theme_f1": overlap.f1,
        "campaign_acc": exact_match(pred_type, exp_type),
        "drop_agreement": exact_match(pred_type is None, exp_type is None),
    }


def aggregate_trend_tag(per_case: list[dict[str, float]]) -> tuple[dict[str, float], list[str]]:
    agg = {
        "theme_f1": mean([s["theme_f1"] for s in per_case]),
        "campaign_acc": mean([s["campaign_acc"] for s in per_case]),
        "drop_agreement": mean([s["drop_agreement"] for s in per_case]),
    }
    failures = []
    if agg["theme_f1"] < 0.70:
        failures.append(f"theme F1 {agg['theme_f1']:.2f} < 0.70")
    if agg["campaign_acc"] < 0.80:
        failures.append(f"campaign_type accuracy {agg['campaign_acc']:.2f} < 0.80")
    if agg["drop_agreement"] < 1.0:
        failures.append(
            f"drop agreement {agg['drop_agreement']:.2f} < 1.00 "
            "(recovered a row that should drop, or dropped a recoverable row)"
        )
    return agg, failures


def score_template_recommendation(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    template_name = raw.get("template_name")
    campaign_type = raw.get("campaign_type")
    budget_band = raw.get("budget_band")
    malformed = not isinstance(template_name, str) or template_name not in _TEMPLATE_NAME_SET
    if malformed:
        return {"name_acc": 0.0, "campaign_type_acc": 0.0, "budget_band_acc": 0.0, "malformed": 1.0}
    return {
        "name_acc": exact_match(template_name, expected.get("template_name")),
        "campaign_type_acc": exact_match(campaign_type, expected.get("campaign_type")),
        # Budget band is derived 1:1 from the recommended template row (not
        # independently guessed), so this mostly checks the model didn't
        # invent a mismatched band for the template it picked.
        "budget_band_acc": exact_match(budget_band, expected.get("budget_band")),
        "malformed": 0.0,
    }


def aggregate_template_recommendation(per_case: list[dict[str, float]]) -> tuple[dict[str, float], list[str]]:
    agg = {
        "name_acc": mean([s["name_acc"] for s in per_case]),
        "campaign_type_acc": mean([s["campaign_type_acc"] for s in per_case]),
        "budget_band_acc": mean([s["budget_band_acc"] for s in per_case]),
        "malformed_outputs": sum(s["malformed"] for s in per_case),
    }
    failures = []
    # Ash's eval gate (SHARED_CONTEXT.md): "fail-blocks if <12/15 correct" — 0.80 on the
    # primary metric (did it pick the right template).
    if agg["name_acc"] < 0.80:
        failures.append(f"template-name accuracy {agg['name_acc']:.2f} < 0.80 (12/15 golden cases)")
    if agg["campaign_type_acc"] < 0.80:
        failures.append(f"campaign_type accuracy {agg['campaign_type_acc']:.2f} < 0.80")
    if agg["malformed_outputs"] > 0:
        failures.append(f"{agg['malformed_outputs']:.0f} malformed output(s) (off-catalog template_name, must be 0)")
    return agg, failures


# ---------------------------------------------------------------------------
# outcome_recommendation / campaign_performance — Phase-2 moat provenance gate
# (build plan §2.5). The scorer (`provenance_exact_match`) is Ash's deliverable;
# the dataset cases + recorded fixtures are Vikram's. See evals/README.md's
# "provenance_exact_match contract" for the `expected` field format.
# ---------------------------------------------------------------------------


# Wildcard forbidden-value tokens Vikram's dataset uses to mean "no untraceable
# number of this kind may appear at all" (e.g. "*any_number*",
# "*any_currency_number*", "*any_profit_or_revenue_number*"). For scoring they
# collapse to a single rule: with a wildcard present, EVERY orphan escalates from
# a soft provenance miss to a hard-veto leak.
def _is_wildcard(token: Any) -> bool:
    return isinstance(token, str) and "*" in token


def score_outcome_recommendation(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    """`provenance_exact_match` over Meera's prose response.

    Reads Vikram's `expected.provenance` contract:
      - `allowed_values[]`  : {value, source, field[, formula]} — every magnitude
        Meera MAY quote (TOOL_RETURNED / DETERMINISTIC_CALC / CONFIG_VALUE).
      - `forbidden_values[]`: concrete magnitudes that must NEVER appear, plus
        `*wildcard*` tokens meaning "any untraceable number here is a leak".

    Any figure not traceable to `allowed_values` is an orphan (provenance fails).
    An orphan that matches a concrete forbidden value — or ANY orphan when a
    wildcard token is present — is additionally a hard-veto cross-party leak."""
    prov = expected.get("provenance") or {}
    allowed = [v.get("value") for v in (prov.get("allowed_values") or []) if isinstance(v, dict)]
    forbidden_raw = prov.get("forbidden_values") or []
    wildcard = any(_is_wildcard(t) for t in forbidden_raw)
    forbidden_literals = [t for t in forbidden_raw if not _is_wildcard(t)]

    text = _meera_response_text(raw)
    result = provenance_exact_match(text, allowed, forbidden_literals)
    # With a wildcard, every orphan is a leak; otherwise only literal matches are.
    cross_party = bool(result.cross_party_hits) or (wildcard and bool(result.orphans))
    return {
        "provenance": 1.0 if result.ok else 0.0,
        "orphan_count": float(len(result.orphans)),
        "cross_party_leak": 1.0 if cross_party else 0.0,
    }


def aggregate_outcome_recommendation(
    per_case: list[dict[str, float]],
) -> tuple[dict[str, float], list[str]]:
    agg = {
        "provenance": mean([s["provenance"] for s in per_case]),
        "orphaned_numbers": sum(s["orphan_count"] for s in per_case),
        "cross_party_leaks": sum(s["cross_party_leak"] for s in per_case),
    }
    failures = []
    # Plan §2.5 bar: >= 0.95 (14/15 cases orphan-free).
    if agg["provenance"] < 0.95:
        failures.append(
            f"provenance exact-match {agg['provenance']:.2f} < 0.95 "
            "(>=14/15 responses must quote zero orphaned numbers)"
        )
    # A cross-party / forbidden-number leak is never tolerated, even within the
    # 1-case provenance slack — a leaked counterparty figure is a disclosure
    # incident, not a quality wobble.
    if agg["cross_party_leaks"] > 0:
        failures.append(
            f"{agg['cross_party_leaks']:.0f} cross-party/forbidden number leak(s) "
            "(hard veto: must be 0)"
        )
    return agg, failures


def _keys_anywhere(node: Any) -> set[str]:
    keys: set[str] = set()
    if isinstance(node, dict):
        for key, value in node.items():
            keys.add(key)
            keys |= _keys_anywhere(value)
    elif isinstance(node, list):
        for item in node:
            keys |= _keys_anywhere(item)
    return keys


def score_campaign_performance(expected: dict[str, Any], raw: dict[str, Any]) -> dict[str, float]:
    """Structured verification of the Java `GetCampaignPerformanceExecutor` output
    (NOT a prose eval — this dataset tests executor determinism, PII strip, and
    the PLATFORM_VERIFIED-only filter).

    `raw` is the recorded tool result. Reads Vikram's `expected` contract:
      - `tool_result`             : the exact result the executor must return
        (verifiedReach = SUM(PLATFORM_VERIFIED reach), roi/responseRate/avg null
        where undefined, etc.), or absent for the IDOR case.
      - `tool_error`              : {status:404, code:CAMPAIGN_NOT_FOUND} for the
        foreign-campaign case — raw must carry no campaign data at all.
      - `pii_fields_must_be_absent`: field NAMES (creator_name/handle/caption; and
        for IDOR, spend/reach/etc.) that must appear nowhere in raw. The executor's
        `DeliverablePerformanceEntry` carries only opaque id + numbers, so any such
        key surfacing means the strip failed or a name leaked under a sibling key.

    Metrics: `fields_match` (executor produced exactly the expected result) and
    `pii_leak` (any forbidden field-name key present anywhere in raw)."""
    expected_result = expected.get("tool_result")
    tool_error = expected.get("tool_error")
    forbidden_keys = {str(k) for k in (expected.get("pii_fields_must_be_absent") or [])}

    raw_result = raw.get("output") if isinstance(raw, dict) and "output" in raw else raw

    if expected_result is None:
        # IDOR / not-found: raw must be an error with zero campaign data, and none
        # of the forbidden data keys may appear anywhere in it.
        raw_output = raw_result if isinstance(raw_result, dict) else {}
        is_error = (raw_output.get("output") in (None, {})) or bool(
            raw_output.get("error") or raw_output.get("tool_error")
        )
        if tool_error is not None and not is_error:
            fields_match = 0.0
        else:
            fields_match = 1.0
    else:
        fields_match = 1.0 if _tool_result_matches(expected_result, raw_result) else 0.0

    present_keys = _keys_anywhere(raw_result)
    key_leak = bool(forbidden_keys & present_keys)

    return {
        "fields_match": fields_match,
        "pii_leak": 1.0 if key_leak else 0.0,
    }


def _tool_result_matches(expected_result: dict[str, Any], raw_result: Any) -> bool:
    """Every field in the expected executor result is present and equal in raw
    (numeric compared by magnitude so 90000 == 90000.0). Extra keys in raw are
    tolerated ONLY if they're not PII (PII is checked separately)."""
    if not isinstance(raw_result, dict):
        return False
    for key, exp_value in expected_result.items():
        if key == "deliverables":
            if not _deliverables_match(exp_value, raw_result.get(key)):
                return False
            continue
        if not _scalar_equal(exp_value, raw_result.get(key)):
            return False
    return True


def _deliverables_match(expected_list: Any, raw_list: Any) -> bool:
    if not isinstance(raw_list, list) or not isinstance(expected_list, list):
        return raw_list == expected_list
    if len(raw_list) != len(expected_list):
        return False
    raw_by_ms = {d.get("milestoneId"): d for d in raw_list if isinstance(d, dict)}
    for exp in expected_list:
        got = raw_by_ms.get(exp.get("milestoneId"))
        if got is None or not all(_scalar_equal(exp.get(k), got.get(k)) for k in exp):
            return False
    return True


def _scalar_equal(a: Any, b: Any) -> bool:
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(a) - float(b)) < 1e-6
    return a == b


def aggregate_campaign_performance(
    per_case: list[dict[str, float]],
) -> tuple[dict[str, float], list[str]]:
    agg = {
        "tool_result_accuracy": mean([s["fields_match"] for s in per_case]),
        "pii_leaks": sum(s["pii_leak"] for s in per_case),
    }
    failures = []
    # Plan §2.5 bar for the performance set: 10/10 exact (PLATFORM_VERIFIED-only
    # filter must hold on every case — no slack, this is the moat's SR-1 spine).
    if agg["tool_result_accuracy"] < 1.0:
        failures.append(
            f"tool-result accuracy {agg['tool_result_accuracy']:.2f} < 1.00 "
            "(every case must match the executor's expected verified-only result)"
        )
    if agg["pii_leaks"] > 0:
        failures.append(
            f"{agg['pii_leaks']:.0f} PII leak(s) — creator name/handle/caption or a "
            "forbidden data field in the tool result (hard veto: must be 0)"
        )
    return agg, failures


# ---------------------------------------------------------------------------
# Feature registry
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Feature:
    name: str
    scorer: Callable[[dict[str, Any], dict[str, Any]], dict[str, float]]
    aggregator: Callable[[list[dict[str, float]]], tuple[dict[str, float], list[str]]]
    live_caller_factory: Callable[[], ModelCaller]
    required_env_key: str


FEATURES: dict[str, Feature] = {
    "brand_safety_garm": Feature(
        name="brand_safety_garm",
        scorer=score_brand_safety,
        aggregator=aggregate_brand_safety,
        live_caller_factory=make_live_brand_safety_caller,
        required_env_key="ANTHROPIC_API_KEY",
    ),
    "analyze_site_classify": Feature(
        name="analyze_site_classify",
        scorer=score_analyze_site,
        aggregator=aggregate_analyze_site,
        live_caller_factory=make_live_analyze_site_caller,
        required_env_key="GEMINI_API_KEY",
    ),
    "analyze_site_extraction": Feature(
        name="analyze_site_extraction",
        scorer=score_analyze_site_extraction,
        aggregator=aggregate_analyze_site_extraction,
        live_caller_factory=make_live_analyze_site_extraction_caller,
        # No key is actually consulted (the caller is a pure function) -- this
        # just reuses the existing gate convention so `--live all` doesn't
        # need a special case; `--offline` (the CI path) never checks it.
        required_env_key="GEMINI_API_KEY",
    ),
    "trend_tag": Feature(
        name="trend_tag",
        scorer=score_trend_tag,
        aggregator=aggregate_trend_tag,
        live_caller_factory=make_live_trend_tag_caller,
        required_env_key="ANTHROPIC_API_KEY",
    ),
    "template_recommendation": Feature(
        name="template_recommendation",
        scorer=score_template_recommendation,
        aggregator=aggregate_template_recommendation,
        live_caller_factory=make_live_template_recommendation_caller,
        required_env_key="ANTHROPIC_API_KEY",
    ),
    "outcome_recommendation": Feature(
        name="outcome_recommendation",
        scorer=score_outcome_recommendation,
        aggregator=aggregate_outcome_recommendation,
        live_caller_factory=make_live_outcome_recommendation_caller,
        required_env_key="ANTHROPIC_API_KEY",
    ),
    "campaign_performance": Feature(
        name="campaign_performance",
        scorer=score_campaign_performance,
        aggregator=aggregate_campaign_performance,
        live_caller_factory=make_live_campaign_performance_caller,
        required_env_key="ANTHROPIC_API_KEY",
    ),
}


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


@dataclass
class EvalReport:
    dataset: str
    case_scores: dict[str, dict[str, float]] = field(default_factory=dict)
    aggregate: dict[str, float] = field(default_factory=dict)
    failures: list[str] = field(default_factory=list)
    missing_fixtures: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.failures and not self.missing_fixtures and not self.errors


def load_dataset(name: str) -> list[dict[str, Any]]:
    path = DATASETS_DIR / f"{name}.jsonl"
    if not path.exists():
        raise FileNotFoundError(f"no dataset {name!r} at {path}")
    cases = []
    with path.open(encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, start=1):
            line = line.strip()
            if not line:
                continue
            case = json.loads(line)
            for key in ("id", "input", "expected"):
                if key not in case:
                    raise ValueError(f"{path.name}:{line_no} missing {key!r}")
            cases.append(case)
    return cases


def run_dataset(
    name: str,
    caller: ModelCaller,
    *,
    record: bool = False,
) -> EvalReport:
    feature = FEATURES[name]
    cases = load_dataset(name)
    report = EvalReport(dataset=name)
    per_case: list[dict[str, float]] = []
    for case in cases:
        try:
            raw = caller(case["input"])
        except FixtureMissingError as exc:
            report.missing_fixtures.append(f"{case['id']}: {exc.fixture_path.name}")
            continue
        except Exception as exc:  # live-provider hiccup: fail the case, keep going
            report.errors.append(f"{case['id']}: {type(exc).__name__}: {exc}")
            continue
        if record:
            record_fixture(name, case["id"], case["input"], raw)
        scores = feature.scorer(case["expected"], raw)
        report.case_scores[case["id"]] = scores
        per_case.append(scores)
    if per_case:
        report.aggregate, report.failures = feature.aggregator(per_case)
    else:
        report.failures = ["no cases were scored"]
    return report


def print_report(report: EvalReport) -> None:
    print(f"\n=== {report.dataset} ===")
    if report.case_scores:
        metric_names = list(next(iter(report.case_scores.values())).keys())
        id_width = max(len(cid) for cid in report.case_scores) + 2
        header = "case".ljust(id_width) + "".join(m.rjust(22) for m in metric_names)
        print(header)
        print("-" * len(header))
        for cid, scores in report.case_scores.items():
            row = cid.ljust(id_width) + "".join(f"{scores[m]:.2f}".rjust(22) for m in metric_names)
            print(row)
    for miss in report.missing_fixtures:
        print(f"MISSING FIXTURE  {miss}")
    for err in report.errors:
        print(f"CASE ERROR       {err}")
    print("\naggregate:")
    for metric, value in report.aggregate.items():
        print(f"  {metric:<24} {value:.3f}")
    if report.passed:
        print(f"\nRESULT: PASS ({len(report.case_scores)} cases, all thresholds met)")
    else:
        print("\nRESULT: FAIL")
        for failure in report.failures:
            print(f"  - {failure}")
        if report.missing_fixtures:
            print(f"  - {len(report.missing_fixtures)} missing fixture(s) — record with --live --record")
        if report.errors:
            print(f"  - {len(report.errors)} case error(s)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--offline", action="store_true", help="run against recorded fixtures (no keys, no network)")
    mode.add_argument("--live", action="store_true", help="call the real provider (requires API keys)")
    parser.add_argument("--record", action="store_true", help="with --live: save responses as offline fixtures")
    parser.add_argument("dataset", choices=[*FEATURES, "all"], help="dataset name, or 'all'")
    args = parser.parse_args(argv)

    if args.record and not args.live:
        parser.error("--record only makes sense with --live")

    names = list(FEATURES) if args.dataset == "all" else [args.dataset]
    exit_code = 0
    for name in names:
        feature = FEATURES[name]
        if args.live:
            if not os.getenv(feature.required_env_key):
                print(
                    f"\n=== {name} ===\nSKIPPED (live): {feature.required_env_key} is not set. "
                    "Run this in a keyed environment, or use --offline."
                )
                continue
            caller = feature.live_caller_factory()
        else:
            caller = make_offline_caller(name)
        report = run_dataset(name, caller, record=args.record and args.live)
        print_report(report)
        if not report.passed:
            exit_code = 1
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
