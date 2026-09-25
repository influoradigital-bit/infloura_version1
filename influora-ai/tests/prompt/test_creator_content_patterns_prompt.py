"""get_my_content_patterns, prompt side (Meera intelligence v1 spec 7, T26 -- deterministic).

The spec asks for an eval over three fixture turns (enough data / thin / not connected). There is
no creator-chat eval harness under tests/eval, and the offline harness under evals/ replays
RECORDED model replies -- inventing replies here would make the eval circular (the harness's own
F-21 note says the same). So this file pins the other half, which is fully deterministic: for
each of the three cases, what the model is actually handed on that turn -- the assembled creator
system prompt, the tool schema offered with it, and the model copy of the tool result -- carries
the rule and the facts Meera needs to answer that case correctly. No live model is called.

The three payloads start from the REAL fixture (GetMyContentPatternsWireShapeTest's byte-for-byte
Jackson dump). The thin and not-connected shapes follow GetMyContentPatternsExecutor.render /
CreatorIntelligenceService exactly: empty lists (never null), NON_NULL dropping the absent strings,
and the executor's own note wording.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from app.prompt.assembler import assemble_prompt
from app.tools.creator_schemas import GET_MY_CONTENT_PATTERNS
from app.tools.loop import _model_copy_of_tool_result

FIXTURE = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "creator_tools"
    / "get_my_content_patterns.real.json"
)
_LISTS = ("baseline", "best_posts", "weak_posts", "what_works")


def _enough() -> dict[str, Any]:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _thin(settled: int = 4, unsettled: int = 1) -> dict[str, Any]:
    """CreatorIntelligenceService's thin-data profile, rendered: everything withheld, counts and
    the executor's note given, as_of dropped by NON_NULL."""
    real = _enough()
    out = {k: v for k, v in real.items() if k not in ("as_of", *_LISTS)}
    out.update(
        enough_data=False,
        settled_posts=settled,
        unsettled_posts=unsettled,
        note=(
            f"Not enough settled posts yet: {settled} of the {real['min_posts_needed']} "
            f"needed from the last {real['lookback_days']} days."
        ),
    )
    for key in _LISTS:
        out[key] = []
    return out


def _not_connected() -> dict[str, Any]:
    """CreatorIntelligenceService.notConnected(), rendered: no post data, no note."""
    real = _enough()
    out = {k: v for k, v in real.items() if k not in ("as_of", *_LISTS)}
    out.update(available=False, reason="NOT_CONNECTED", enough_data=False)
    out.update(settled_posts=0, unsettled_posts=0)
    for key in _LISTS:
        out[key] = []
    return out


def _turn(question: str) -> tuple[str, list[dict[str, Any]]]:
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-cp-001",
            "audience": "CREATOR",
            "creator": {
                "workspace_id": "creator-cp-001",
                "display_name": "Asha Rao",
                "first_name": "Asha",
                "city": "Pune",
                "tier": "NANO",
                "categories": ["Beauty & Skincare"],
                "creator_language": "en-IN",
                "brand_tone": "FRIENDLY",
                "tools_enabled": [GET_MY_CONTENT_PATTERNS],
                "content_goal": "GROW_FOLLOWERS",
                "equipment": [],
                "content_dislikes": [],
            },
            "conversation": [{"role": "user", "content": question}],
        },
        session_id="s-content-patterns",
    )
    system = " ".join("\n".join(b["text"] for b in prompt.system_blocks).split())
    return system, prompt.tools


def _schema_description(tools: list[dict[str, Any]]) -> str:
    [schema] = [t for t in tools if t["name"] == GET_MY_CONTENT_PATTERNS]
    return " ".join(schema["description"].split())


def _model_view(payload: dict[str, Any]) -> dict[str, Any]:
    text = _model_copy_of_tool_result(GET_MY_CONTENT_PATTERNS, payload)
    assert "<untrusted_" not in text
    return json.loads(text)


# Rules every one of the three turns must carry, whatever the data says.
_ALWAYS = (
    "Their own results (what works for them):",
    "always say how many posts it rests on",
    "Never compare them with other creators, an average creator, their category",
    "Never say they are growing, improving, going viral, declining or doing well overall.",
    "get_my_content_patterns: read what has worked for them",
)


@pytest.mark.parametrize(
    "question",
    ["what's working for me?", "which of my posts did best?", "am I growing?"],
)
def test_every_turn_offers_the_tool_with_the_results_rules(question):
    system, tools = _turn(question)
    for rule in _ALWAYS:
        assert rule in system, rule
    assert GET_MY_CONTENT_PATTERNS in [t["name"] for t in tools]
    # The goal saved on the chips reaches the same turn, as words.
    assert "- Their goal (they saved it): grow followers." in system


def test_enough_data_turn_hands_meera_sample_sizes_to_quote():
    system, tools = _turn("what's working for me?")
    description = _schema_description(tools)
    assert "each with the number of posts it rests on" in description
    assert "Quote the strings exactly; never compute a new percentage, average or ranking." in description
    assert '"based on 8 of your Reels"' in system

    model = _model_view(_enough())
    assert model["enough_data"] is True
    for pattern in model["what_works"]:
        assert pattern["posts"] == pattern["evidence"]["sample_size"] >= 3
        assert pattern["reach_vs_usual"].startswith(("+", "-"))
    reels = next(p for p in model["what_works"] if p["kind"] == "POST_TYPE")
    assert reels["label"] == "Reels and videos"
    assert 'Say "Reels and videos"; never claim Reels beat videos' in system


def test_thin_data_turn_carries_the_counts_and_no_claim():
    system, tools = _turn("what's working for me?")
    assert "When enough_data is false, say how many settled posts they have and how many are needed, and claim no pattern." in _schema_description(tools)
    assert "Say thin data plainly." in system
    assert "Never call a handful of posts a pattern." in system
    assert "give general advice from the content knowledge and say it is general" in system

    model = _model_view(_thin(settled=4))
    assert model["enough_data"] is False
    assert model["settled_posts"] == 4 and model["min_posts_needed"] == 10
    assert model["note"] == "Not enough settled posts yet: 4 of the 10 needed from the last 90 days."
    for key in _LISTS:
        assert model[key] == [], f"a thin result must claim nothing in {key}"


def test_not_connected_turn_says_instagram_is_not_connected():
    system, tools = _turn("which of my posts did best?")
    assert "When available is false, say Instagram is not connected." in _schema_description(tools)
    model = _model_view(_not_connected())
    assert model["available"] is False and model["reason"] == "NOT_CONNECTED"
    assert "note" not in model
    for key in _LISTS:
        assert model[key] == []


def test_the_derived_payloads_use_only_real_record_keys():
    real_keys = set(_enough()) | {"reason", "note"}
    assert set(_thin()) <= real_keys
    assert set(_not_connected()) <= real_keys


def test_saved_kit_and_rather_nots_are_respected():
    """The goal line puts their kit and rather-nots in context; this rule makes Meera honour
    them instead of suggesting a gimbal shot or a face-to-camera hook they said no to."""
    from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

    flat = " ".join(MEERA_CREATOR_PERSONA.split())
    assert "Their saved kit and rather-nots." in flat
    assert "never assume a tripod, mic, light or gimbal they have not listed" in flat
    assert "never suggest those; offer a version that works without it" in flat
    # It sits in the "Their own results" block, after the goal rule, before the dates section.
    assert flat.index("Their own goal.") < flat.index("Their saved kit and rather-nots.")
    assert flat.index("Their saved kit and rather-nots.") < flat.index("Dates and today's topics:")
