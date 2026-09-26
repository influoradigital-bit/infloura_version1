"""Knowledge v7 (dataset 7 + the creator lighting and positioning guide, 2026-09-24): where
to put the creator, the phone and the light.

What this pins:
- the "Placing the creator, the phone and the light" section reaches both the creator
  knowledge block and the frame check's system prompt;
- the workflow row reads the light first, then instructs creator, phone, light, settings --
  the same order as the persona and the frame check;
- the first moves render in step order (the step number itself is not rendered), before
  the light-angle rules;
- left and right are the creator's own, and dataset 7's machine variable list stays out;
- the looks heading says once that a look is a convention; each look's category fit is
  labelled Influora's suggestion, not the guide's; the text stays plain (no "**") and
  TikTok-free;
- the midday row uses the shadow cue, and no row gives a clock range for midday;
- the persona and the frame check both give instructions creator, phone, light, settings;
- every row of the 14 v7 types reaches the rendered text, and the ALWAYS-SENT block stays in
  budget (under 125,000 characters and at most 320 always-sent rows; since 2026-09-24 the
  file itself may grow behind the get_creator_knowledge lookup tool).
"""

from __future__ import annotations

import re

import pytest

from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_ROWS,
    CREATOR_KNOWLEDGE_TEXT,
    FIRST_MOVE_HEADING,
    LOOKUP_TEXT,
    NAME_FIELD,
    LIGHTING_LOOKS_HEADING,
    PLACEMENT_HEADING,
    REQUIRED_FIELDS,
    render_knowledge_block,
    render_placement_lines,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.prompt.content_knowledge import _HEADING_CARRIED_LIMITS, PLACEMENT_HEADING, SUNSET_HEADING
from app.prompt.frame_check import build_system_prompt

V7_TYPES: tuple[str, ...] = (
    "lighting_workflow",
    "lighting_angle_rule",
    "window_lighting_rule",
    "indian_home_lighting_rule",
    "camera_height_rule",
    "background_repair_rule",
    "portrait_lighting_pattern",
    "indian_creator_scene_checklist",
    "coordinate_system_note",
    "physics_principle",
    "lighting_fix_order",
    "lighting_look",
    "mixed_light_rule",
    "sunset_to_night_step",
)

ANGLE_HEADING = "Where the main light sits, measured from the creator's face:"
LEFT_RIGHT_HEADING = (
    "Left and right (always the creator's own, as they face the phone: \"your left\","
    " \"your right\"):"
)
MIDDAY_SCENARIO = "Harsh midday sun (sun high, your shadow short and right under you)"


def _flat(text: str) -> str:
    return " ".join(text.split())


def _rows(data_type: str) -> list[dict]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == data_type]


def _placement_block() -> str:
    return "\n".join(render_placement_lines(CREATOR_KNOWLEDGE_ROWS))


# --- the section reaches both prompts ------------------------------------------------


def test_placement_section_renders_in_the_knowledge_block_and_the_frame_check():
    block = _placement_block()
    assert block.startswith(PLACEMENT_HEADING)
    assert block in CREATOR_KNOWLEDGE_TEXT
    assert block in build_system_prompt()


def test_every_v7_type_is_registered_and_has_rows():
    for t in V7_TYPES:
        assert t in REQUIRED_FIELDS, t
        assert _rows(t), t
    assert sum(len(_rows(t)) for t in V7_TYPES) == 75


# --- order: the workflow, then the first moves, then the angle rules ------------------

# One instruction order everywhere: the creator, then the phone, then the light, then the
# settings. Each tuple is the words that mark those four moves in one text, in that order.
_ORDER_MARKERS: tuple[str, str, str, str] = ("creator", "phone", "light", "settings")


def _in_order(sentence: str, markers: tuple[str, ...]) -> bool:
    """True when every marker appears in `sentence`, each after the one before it."""
    at = -1
    for m in markers:
        at = sentence.find(m, at + 1)
        if at < 0:
            return False
    return True


def test_workflow_row_instructs_creator_phone_light_then_settings_like_persona_and_frame_check():
    (workflow,) = _rows("lighting_workflow")
    principle, definition = workflow["principle"], workflow["definition"]
    # Reading the light is an observation that comes first; it is not the first instruction.
    assert principle.startswith("Read the light, then ")
    assert definition.startswith("Look first at where the useful light comes from")
    instructions = principle.split("Read the light, then ", 1)[1]
    assert _in_order(instructions, _ORDER_MARKERS), principle
    assert "Then instruct in this order:" in definition
    assert _in_order(definition.split("Then instruct in this order:", 1)[1], _ORDER_MARKERS), definition
    # The same order as the persona's and the frame check's order sentences.
    persona = _flat(MEERA_CREATOR_PERSONA)
    persona_order = persona[persona.index("give shooting instructions in this order"):]
    persona_order = persona_order[: persona_order.index("settings.") + len("settings")]
    frame = _flat(build_system_prompt())
    frame_order = frame[frame.index("Give the fixes in this order"):]
    frame_order = frame_order[: frame_order.index("settings") + len("settings")]
    for sentence in (persona_order, frame_order):
        assert _in_order(sentence, _ORDER_MARKERS), sentence
    # And it renders first in the placement section.
    block = _placement_block()
    assert block.index(f"- {principle}: {definition}") < block.index(FIRST_MOVE_HEADING)


def test_first_moves_render_in_step_order_without_step_numbers_before_the_angle_rules():
    text = CREATOR_KNOWLEDGE_TEXT
    steps = sorted(_rows("lighting_fix_order"), key=lambda r: int(r["step"]))
    assert [int(r["step"]) for r in steps] == list(range(1, len(steps) + 1))
    positions = [
        text.index(f"- {r['situation']}: first move: {r['first_move']} Why: {r['why']}")
        for r in steps
    ]
    assert positions == sorted(positions)
    assert text.index(PLACEMENT_HEADING) < text.index(FIRST_MOVE_HEADING) < positions[0]
    assert positions[-1] < text.index(ANGLE_HEADING)
    section = text[text.index(FIRST_MOVE_HEADING): text.index(ANGLE_HEADING)]
    assert "(step" not in section


# --- left and right, and no machine variable list ------------------------------------


def test_left_right_rule_is_present_and_the_machine_variable_list_is_absent():
    text = CREATOR_KNOWLEDGE_TEXT
    assert LEFT_RIGHT_HEADING in text
    # "always", matching the persona and the frame check -- no "by default", no carve-out.
    assert "Always use the creator's own left and right as they face the phone" in text
    assert "Never say camera-left or camera-right, because" in text
    assert "by default" not in text[text.index(LEFT_RIGHT_HEADING):][:1500]
    assert "Outdoors, also give the sun's direction and height from the creator when known" in text
    # dataset 7 carried a `production_variables` list for a machine; it is not creator advice.
    for r in CREATOR_KNOWLEDGE_ROWS:
        assert "production_variables" not in r, r.get("principle")
    for machine_word in (
        "production_variables",
        "sun_azimuth",
        "sun_elevation",
        "subject_facing_azimuth",
        "camera_azimuth",
        "key_azimuth_relative_to_face",
        "subject-left",
        "subject-right",
    ):
        assert machine_word not in text, machine_word
        assert machine_word not in build_system_prompt(), machine_word


# --- looks are conventions; plain text; no TikTok ------------------------------------


def test_looks_heading_says_convention_once_and_each_fit_is_labelled_influoras_suggestion():
    text = CREATOR_KNOWLEDGE_TEXT
    assert LIGHTING_LOOKS_HEADING in text
    assert "a look is a visual convention, never a promise of views" in LIGHTING_LOOKS_HEADING
    looks = text[text.index(LIGHTING_LOOKS_HEADING):].split("\n\n", 1)[0].splitlines()[1:]
    rows = _rows("lighting_look")
    assert len(looks) == len(rows)
    for r in rows:
        line = next(ln for ln in looks if ln.startswith(f"- {r['look']}: "))
        # The category fit is Influora's, not the guide's; the guide's own label follows it.
        assert "Fits: Influora's suggestion, not from the guide: " in line, r["look"]
        assert "The guide's label: " in r["fits_categories"], r["look"]
        # The heading carries the convention note once; no per-row repetition.
        assert "convention" not in line, r["look"]


def test_knowledge_text_is_plain_and_never_names_tiktok():
    for text in (CREATOR_KNOWLEDGE_TEXT, build_system_prompt()):
        assert "**" not in text
        assert "tiktok" not in text.lower()


# --- midday: a shadow cue, never a clock range ---------------------------------------

# "11am to 3pm", "11 a.m.-3 p.m.", "11:00-15:00", "11 to 3": any clock time or clock range.
_CLOCK = re.compile(
    r"\b\d{1,2}(?::\d{2})?\s*(?:(?:am|pm)\b|a\.m\.|p\.m\.)"
    r"|\b\d{1,2}(?::\d{2})?\s*(?:-|to|–)\s*\d{1,2}(?::\d{2})?\s*(?:(?:am|pm)\b|a\.m\.|p\.m\.|o'clock)"
    r"|\b\d{1,2}:\d{2}\s*(?:-|to|–)\s*\d{1,2}:\d{2}\b",
    re.IGNORECASE,
)


def test_midday_row_uses_the_shadow_cue():
    scenarios = {r["scenario"] for r in _rows("lighting_rule")}
    assert MIDDAY_SCENARIO in scenarios
    assert "Harsh midday sun (roughly 11am to 3pm)" not in scenarios
    assert MIDDAY_SCENARIO in CREATOR_KNOWLEDGE_TEXT
    assert MIDDAY_SCENARIO in build_system_prompt()


def test_no_knowledge_row_gives_a_clock_range_for_midday():
    midday_rows = [
        r for r in CREATOR_KNOWLEDGE_ROWS
        if re.search(r"midday|noon|mid-day", " ".join(str(v) for v in r.values()), re.IGNORECASE)
    ]
    assert midday_rows
    for r in midday_rows:
        for v in r.values():
            if isinstance(v, str):
                assert not _CLOCK.search(v), v


@pytest.mark.parametrize(
    "value",
    ["roughly 11am to 3pm", "11 a.m.-3 p.m.", "between 11:00-15:00", "from 11 to 3 o'clock"],
)
def test_the_clock_pattern_catches_what_it_is_meant_to(value):
    assert _CLOCK.search(value)


# --- instruction order in the persona and the frame check ----------------------------


def test_persona_gives_instructions_creator_phone_light_then_settings():
    persona = _flat(MEERA_CREATOR_PERSONA)
    assert (
        "give shooting instructions in this order -- move the creator first, then the phone,"
        " then the light, and only then the settings." in persona
    )
    assert '"your left" or "your right as you face the phone", never the viewer\'s side' in persona
    assert '"Placing the creator, the phone and the light"' in persona
    # The persona names the looks by the heading the renderer uses.
    assert 'pick from "Lighting looks"' in persona
    assert LIGHTING_LOOKS_HEADING.startswith("Lighting looks")
    assert "a look is a visual convention, not a promise of views or results" in persona


def test_frame_check_gives_fixes_creator_phone_light_then_settings():
    system = _flat(build_system_prompt())
    assert (
        "Give the fixes in this order: first where the creator stands or turns, then where the"
        " phone goes, then the light, and only then settings -- fix the scene before the settings."
        in system
    )
    assert (
        "Left and right are ALWAYS from the creator's view as they face the phone"
        ' ("your left", "your right"), never the viewer\'s side of the photo.' in system
    )


# --- every v7 row reaches the text; the block stays in budget ------------------------


def test_every_v7_row_reaches_the_rendered_text():
    frame = build_system_prompt()
    for t in V7_TYPES:
        for r in _rows(t):
            for field in REQUIRED_FIELDS[t]:
                if field == "step":
                    continue  # sorts the first moves, never rendered -- pinned by the order test
                assert r[field] in CREATOR_KNOWLEDGE_TEXT, (t, field, r[field][:60])
                assert r[field] in frame, (t, field, r[field][:60])
            limit = r.get("limits")
            if isinstance(limit, str) and limit.strip() and limit.strip() not in _HEADING_CARRIED_LIMITS:
                assert f"Limits: {limit.strip()}" in CREATOR_KNOWLEDGE_TEXT, (t, limit[:60])


def test_heading_carried_caveats_are_said_once_not_on_every_row():
    # Review round 3 counted 15 copies of two caveats. Each now lives in its heading only.
    for caveat in _HEADING_CARRIED_LIMITS:
        assert CREATOR_KNOWLEDGE_TEXT.count(f"Limits: {caveat}") == 0, caveat
    assert "angles are starting points, not laws" in PLACEMENT_HEADING
    assert SUNSET_HEADING in CREATOR_KNOWLEDGE_TEXT and "not the clock" in SUNSET_HEADING


def test_no_night_setting_bands_under_indian_mains_light():
    # 24fps/1/48 (and 30fps/1/60) catch the 100Hz pulse of 50Hz lights at different points.
    # Street lights, neon and shop lights are on mains, so every night row must be 50Hz-safe.
    for r in _rows("camera_technical_setting") + _rows("night_video_setting"):
        name = r.get("situation") or r.get("environment")
        night = r["data_type"] == "night_video_setting" or "Night" in name
        if not night:
            continue
        for bad in ("1/48", "1/60", "1/30"):
            assert bad not in r["shutter"], (name, r["shutter"])
        assert not r["fps"].startswith(("24", "30")), (name, r["fps"])


def test_ceiling_light_is_never_the_bare_face_light():
    # One row said "not the ceiling light alone"; another made it the only face light. Any row
    # that falls back to the ceiling light must add a bounce or fill.
    for r in CREATOR_KNOWLEDGE_ROWS:
        text = " ".join(str(v) for v in r.values()).lower()
        if "ceiling light is the only light" in text or "ceiling light must be your face light" in text:
            assert "bounce" in text or "fill" in text, r


def _in_any_lookup_topic(value: str) -> bool:
    return any(value in text for text in LOOKUP_TEXT.values())


def _always_sent_rows() -> list[dict]:
    """Rows that render into the always-sent block, found by what the renderer does, not by a
    hand-kept list that could drift from it: a row is always sent when taking it out changes
    the block. (Until 2026-09-26 this matched names, but four dataset 9 lookup-only names --
    "Handheld", "Whip pan", "Instagram Reels", "YouTube Shorts" -- are also ordinary words of
    the block.) Every other row must render into a lookup topic."""
    out = []
    rows = CREATOR_KNOWLEDGE_ROWS
    for i, r in enumerate(rows):
        if render_knowledge_block(rows[:i] + rows[i + 1 :]) != CREATOR_KNOWLEDGE_TEXT:
            out.append(r)
            continue
        assert _in_any_lookup_topic(str(r[NAME_FIELD[r["data_type"]]])), r
    return out


def test_knowledge_block_stays_under_the_size_budget():
    # The knowledge block is sent on every creator turn (Priya, 2026-09-24,
    # wiki/decisions/2026-09-24-creator-knowledge-budget.md). Since the lookup tool
    # (get_creator_knowledge) the FILE may grow behind it; the budget counts only what is
    # always sent. The placement section stays always-sent.
    assert len(CREATOR_KNOWLEDGE_TEXT) < 125_000
    always_sent = _always_sent_rows()
    assert len(always_sent) <= 320
    # 520 rows in the file (349 + the 10 always-sent coach questions of 2026-09-25 + dataset 9's
    # 161 of 2026-09-26), 215 behind the tool (12 delivery examples + 42 v8 audio/movement + 161
    # dataset 9 framing and shot planning), plus every explainer Reel format row (2026-09-26).
    reel_rows = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] in ("reel_format", "reel_format_rule")]
    assert reel_rows, "no Reel format rows -- the lookup-only count below would pass vacuously"
    # 306 since owner decision D (2026-09-26) added the always-sent product_side coach row.
    assert len(always_sent) == 306
    assert len(CREATOR_KNOWLEDGE_ROWS) - len(always_sent) == 215 + len(reel_rows)
    lookup_only_types = {r["data_type"] for r in CREATOR_KNOWLEDGE_ROWS} - {
        r["data_type"] for r in always_sent
    }
    assert "delivery_example" in lookup_only_types
    assert "microphone_selection_rule" in lookup_only_types


def test_frame_check_prompt_stays_under_its_budget():
    # Sent uncached with every Shoot Check photo; about 35k characters at v7. Growing past this
    # needs the frame-check trim first (wiki/decisions/2026-09-24-creator-knowledge-budget.md).
    assert len(build_system_prompt()) < 40_000
