"""Knowledge v6 (dataset 6, 2026-09-24): outdoor light and delivery -- how the creator SAYS
the lines.

What this pins:
- the delivery rows reached Meera as creator advice, not as an annotation schema;
- the guardrails that stop a delivery tip becoming a target or a score are present;
- no example names TikTok or LinkedIn, and every example is labelled synthetic;
- the full-script beat carries one stressed phrase and a pause by word, never by seconds;
- the four outdoor-light gaps are covered, and they reach the frame check too.
"""

from __future__ import annotations

import re

from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_ROWS,
    CREATOR_KNOWLEDGE_TEXT,
    DELIVERY_EXAMPLES_HEADING,
    DELIVERY_HEADING,
    LOOKUP_TEXT,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.prompt.frame_check import build_system_prompt


def _flat(text: str) -> str:
    return " ".join(text.split())


def _rows(data_type: str) -> list[dict]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == data_type]


def _delivery_text() -> str:
    return CREATOR_KNOWLEDGE_TEXT[CREATOR_KNOWLEDGE_TEXT.index(DELIVERY_HEADING):]


def _examples_text() -> str:
    # 2026-09-24 (lookup tool): the 12 examples moved out of the always-sent block into the
    # "delivery_examples" topic that get_creator_knowledge returns.
    return LOOKUP_TEXT["delivery_examples"]


def test_delivery_section_renders_guardrails_before_rules_and_examples():
    text = _delivery_text()
    assert text.index("Delivery guardrails (always):") < text.index("Delivery rules:")
    # The examples are the lookup topic now, labelled synthetic, and still bound by the guardrails.
    assert "Delivery examples (synthetic" not in CREATOR_KNOWLEDGE_TEXT
    assert _examples_text().startswith(DELIVERY_EXAMPLES_HEADING)
    assert "Delivery examples (synthetic illustrations, not real creator data" in _examples_text()
    assert "delivery guardrails in your knowledge block still apply" in _examples_text()
    for r in _rows("delivery_rule"):
        assert f"- {r['rule']}: {r['advice']}" in text
    # The guardrails themselves must reach Meera, not just their heading.
    for g in _rows("delivery_guardrails")[0]["guardrails"]:
        assert f"- {g.strip()}" in text, g


def test_advice_is_written_for_a_creator_not_for_an_annotation_schema():
    text = _delivery_text()
    for machine_word in ("pause_ms", "emit a boundary", "boundary type", "label each language span", "null"):
        assert machine_word not in text, machine_word


def test_guardrails_forbid_targets_scores_and_causal_claims():
    g = _flat(" ".join(_rows("delivery_guardrails")[0]["guardrails"]))
    assert "Never give a fixed words-per-minute, pitch, loudness, pause length" in g
    assert "against the creator's own usual voice" in g
    assert "Never claim a delivery tip causes" in g
    assert "accent, a dialect, mixing languages" in g


def test_no_numeric_delivery_target_anywhere_in_the_rules():
    for r in _rows("delivery_rule"):
        advice = r["advice"]
        assert not re.search(r"\d+\s*(wpm|words per minute|ms|db|hz|seconds?)\b", advice, re.IGNORECASE), advice


def test_examples_are_short_video_only_and_labelled_synthetic():
    for r in _rows("delivery_example"):
        assert r["platform"] in ("Instagram Reels", "YouTube Shorts"), r
        assert r["example_status"] == "synthetic illustration, not real creator data"
    assert "linkedin" not in CREATOR_KNOWLEDGE_TEXT.lower()
    assert "linkedin" not in _examples_text().lower()


def test_safety_and_identity_rules_are_present_and_high_confidence():
    by_id = {r["source_id"]: r for r in _rows("delivery_rule")}
    assert "never diagnose" in by_id["VOICE_HEALTH_CAUTION"]["advice"]
    assert "Never call an accent or dialect an error" in by_id["PRESERVE_ACCENT_IDENTITY"]["advice"]
    assert "never 'correct' it into one language" in by_id["CODE_SWITCH_SPANS"]["advice"]
    assert by_id["VOICE_HEALTH_CAUTION"]["confidence"] == "high"
    assert by_id["PRESERVE_ACCENT_IDENTITY"]["confidence"] == "high"
    # Research claims with no citation we could check stay medium.
    assert by_id["FOCAL_PROMINENCE"]["confidence"] == "medium"


def test_script_beat_carries_one_stress_and_a_pause_by_word():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert 'Stress: <the one phrase to stress>. Pause: after "<word>", or none.' in text
    assert "never a length in seconds" in text


def test_persona_routes_delivery_questions_to_the_guardrails():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert 'answer from "How to deliver the lines" and obey its guardrails' in text
    assert "judge pace and energy against the creator's own usual voice" in text
    assert "never promise that a way of speaking brings views" in text


def test_outdoor_light_gaps_are_covered_and_reach_the_frame_check():
    scenarios = {r["scenario"] for r in _rows("lighting_rule")}
    for s in (
        "Harsh midday sun (sun high, your shadow short and right under you)",
        "Golden hour (about the first hour after sunrise and the last hour before sunset)",
        "Cloudy or overcast day",
        "Shade under trees",
    ):
        assert s in scenarios, s
    system = build_system_prompt()
    assert "Harsh midday sun" in system and "Shade under trees" in system
    # Delivery is about speaking, not the photo: it stays out of the frame check.
    assert DELIVERY_HEADING not in system


def test_examples_show_the_break_the_stress_reason_and_the_pause():
    text = _examples_text()
    assert (
        '- ex01 (English (India), Instagram Reels): Say: "Most creators post every day, / but they skip'
        ' this one step." Stress: "this one step" (contrastive focal phrase). Pause: after "day"'
    ) in text
    for r in _rows("delivery_example"):
        assert r["pause"] and r["pace"], r["example_id"]
        assert "pause_ms" not in str(r) and "boundary_after" not in str(r)


def test_no_markdown_bold_reaches_meera():
    assert "**" not in CREATOR_KNOWLEDGE_TEXT
    assert "**" not in _examples_text()


def test_ex09_stresses_the_safety_word_and_ex07_is_not_a_fact_to_copy():
    ex = {r["example_id"]: r for r in _rows("delivery_example")}
    assert ex["ex09"]["stress"].startswith('"stop"')
    assert "only when it is the creator's own experience" in ex["ex07"]["note"]
    assert "Verify the numerical claim" in ex["ex10"]["note"]


def test_every_delivery_rule_keeps_its_source_numbers():
    for r in _rows("delivery_rule"):
        refs = r["source_refs"]
        assert refs and all(isinstance(n, int) for n in refs), r["rule"]


def test_outdoor_setting_needs_no_nd_filter_and_matches_indoor_clips():
    row = next(r for r in _rows("camera_technical_setting") if r["situation"] == "Talking Head (Outdoors, daylight)")
    assert "no ND filter" in row["shutter"]
    assert row["fps"].startswith("25")
