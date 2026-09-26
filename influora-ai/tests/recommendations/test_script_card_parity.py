"""The Python port of `parseMeeraScript` agrees with the TypeScript original (spec 8.3.1).

A SCRIPT_CARD recommendation is recorded only for a reply the chat renders as a script card, so
"is this a script" must mean the same thing in `src/lib/meera-result-cards.ts` (the browser) and
`app/recommendations/script_card.py` (the write-back). The shared fixture
`tests/fixtures/meera_scripts.json` holds:

- `cases`: every script text of `src/lib/meera-result-cards.test.ts`, built with that file's own
  string operations, and that test's own verdict for it (card = parsed, none = undefined);
- `edge_cases`: text-semantics traps where JavaScript and Python differ by default (trim set,
  `.` vs line terminators, ASCII-only case folding and digits, markdown stripping), each with
  the verdict the REAL TypeScript parser returned (bundled with esbuild, run under node);
- `shot_card_cases` (spec v2 Phase 6, risk R6): replies with and without the optional
  `Shot cards:` block, a malformed line, unknown enum values, over-long free text, fewer lines
  than beats, a Hindi reply and the refusals, each with the verdict AND every card field the
  REAL TypeScript parser returned. `src/lib/meera-result-cards.shot-cards.test.ts` runs the same
  cases through the TS parser itself, so the two sides are pinned to one set of values.

A text the TS parser rejects must produce no recommendation -- the last test pins that through
`build_recommendations`, the function the write-back calls.
"""

from __future__ import annotations

import dataclasses
import json
import re
from pathlib import Path

import pytest

from app.recommendations.record import build_recommendations
from app.recommendations.script_card import SHOT_CARD_KEYS, ShotCard, parse_meera_script

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "meera_scripts.json"
PERSONA = Path(__file__).resolve().parents[2] / "app" / "prompt" / "creator_persona.py"
TS_TEST = Path(__file__).resolve().parents[3] / "src" / "lib" / "meera-result-cards.test.ts"


def _fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _all_cases() -> list[dict]:
    data = _fixture()
    return data["cases"] + data["edge_cases"] + data["shot_card_cases"]


def _shot_card_cases() -> list[dict]:
    return _fixture()["shot_card_cases"]


def _cards(parsed) -> list[dict | None] | None:
    if parsed is None:
        return None
    return [dataclasses.asdict(b.card) if b.card is not None else None for b in parsed.beats]


@pytest.mark.parametrize("case", _all_cases(), ids=lambda c: c["name"])
def test_the_port_gives_the_typescript_verdict(case):
    parsed = parse_meera_script(case["text"])
    assert (parsed is not None) == (case["expected"] == "card"), case["name"]


@pytest.mark.parametrize("case", _all_cases(), ids=lambda c: c["name"])
def test_a_text_the_typescript_parser_rejects_records_no_script(case):
    """Through `build_recommendations`, the write-back's own entry: a rejected script text never
    yields a SCRIPT_CARD, an accepted one yields exactly one."""
    items = build_recommendations(case["text"], [])
    scripts = [i for i in items if i["source"] == "SCRIPT_CARD"]
    assert len(scripts) == (1 if case["expected"] == "card" else 0), case["name"]


def test_the_fixture_still_carries_the_typescript_test_texts():
    """The copied texts must not drift from the TS test they were copied from: every distinctive
    Idea line of the fixture's `cases` appears in the TS test file."""
    if not TS_TEST.is_file():
        pytest.fail(f"{TS_TEST} not found -- run inside a full-repo checkout (a skip is a vacuous pass)")
    ts_source = TS_TEST.read_text(encoding="utf-8")
    ideas = {
        line.split(":", 1)[1].strip()
        for case in _fixture()["cases"]
        for line in case["text"].split("\n")
        if line.strip().lower().startswith("idea")
        and ":" in line
        and line.split(":", 1)[1].strip()
    }
    assert {"3 saffron mistakes to avoid", "Six quick shots for a longer reel", "Too short a script"} <= ideas
    for idea in ideas:
        assert idea in ts_source, f"fixture Idea {idea!r} is no longer in {TS_TEST.name}"


def test_parsed_fields_match_the_typescript_happy_path():
    """The TS test's own toEqual for VALID_SCRIPT, field for field."""
    parsed = parse_meera_script(_fixture()["cases"][0]["text"])
    assert parsed is not None
    assert parsed.idea == "3 saffron mistakes to avoid"
    assert parsed.success_looks_like == "reel gets watched to the end and shared to a friend"
    assert [(b.start, b.end) for b in parsed.beats] == [(0, 10), (10, 20), (20, 30)]
    assert parsed.beats[0].shot == "Close-up on your face - hold up the saffron box"
    assert parsed.beats[0].say == "Is your saffron even real?"
    assert parsed.beats[0].on_screen == "REAL vs FAKE"
    assert parsed.beats[0].stress is None and parsed.beats[0].pause is None
    assert parsed.before_you_shoot == (
        "Charge your phone to 100%",
        "Wipe the counter clean",
        "Keep the certificate within reach",
    )
    assert parsed.why_this_works == "Problem-agitate-solve structure, curiosity-gap hook template"
    assert parsed.follow_up is None


def test_stress_pause_and_nested_quotes_match_the_typescript_values():
    cases = {c["name"]: c["text"] for c in _fixture()["cases"]}
    stress = parse_meera_script(cases["stress_pause_markers"])
    assert stress.beats[0].stress == "even real"
    assert stress.beats[0].pause == 'after "saffron", or none'
    assert stress.beats[1].stress is None
    nested = parse_meera_script(cases["nested_quote_in_say"])
    assert nested.beats[0].say == 'My nani said "check the colour" first'
    six = parse_meera_script(cases["six_beats_with_follow_up"])
    assert len(six.beats) == 6
    assert six.follow_up == "Which language should the voice-over be in \u2014 Hindi or English?"


def test_setup_line_matches_the_typescript_values():
    """The release's optional Set-up line (persona 2026-09-25): the TS test's own expectations."""
    cases = {c["name"]: c["text"] for c in _fixture()["cases"]}
    setup = (
        "sit facing the window, light on your left; phone at eye height, an arm away, 1x lens; "
        "lock focus and exposure; walk to the counter for the last shot"
    )
    with_setup = parse_meera_script(cases["setup_after_action"])
    assert with_setup.setup == setup
    assert with_setup.action == "hold up the saffron box and speak to camera"
    assert with_setup.success_looks_like == "reel gets watched to the end and shared to a friend"
    assert parse_meera_script(cases["valid_3_beats"]).setup is None
    no_success = parse_meera_script(cases["setup_without_success_line"])
    assert no_success.setup == setup and no_success.success_looks_like is None
    for name in ("setup_label_setup", "setup_label_set_up_spaced", "setup_label_upper"):
        assert parse_meera_script(cases[name]).setup == setup, name
    without = parse_meera_script(cases["valid_3_beats"])
    assert dataclasses.replace(with_setup, setup=None) == without


def _persona_beat_example() -> str:
    """The beat line the persona's "Full script format" shows the model, quoted as it is there."""
    source = PERSONA.read_text(encoding="utf-8")
    section = source[source.index("Full script format (only when asked):") :]
    match = re.search(r'^\s*("0-3s\. Shot: .*On screen: <text>\.")\s*$', section, re.MULTILINE)
    assert match, "the persona's example beat line moved or changed shape"
    return match.group(1)


def test_a_script_written_exactly_to_the_persona_layout_is_a_card():
    """The persona's own layout, with its own example beat line (outer quotes and all), filled in
    three times with contiguous timings."""
    example = _persona_beat_example()
    beats = [example, example.replace("0-3s.", "3-10s."), example.replace("0-3s.", "10-20s.")]
    text = "\n".join(
        [
            "Idea: a short title.",
            "Plan: for whom; the one feeling; the goal; 20 seconds, vertical 9:16; "
            "Before-After-Bridge (BAB); The mistakes that are quietly ruining your [outcome].",
            "Action: what they do on camera while they speak.",
            "Set-up: where you sit and the light; where the phone goes; the settings; one spot.",
            "Success looks like: the line for their goal.",
            "Script:",
            *beats,
            "Caption: one caption that carries the conversation question.",
            "Before you shoot: 1) one 2) two 3) three",
            "Why this works: Before-After-Bridge (BAB) because it shows the change; "
            "The mistakes that are quietly ruining your [outcome] for curiosity.",
            "Which language do you want the voice-over in?",
        ]
    )
    parsed = parse_meera_script(text)
    assert parsed is not None
    assert parsed.setup == "where you sit and the light; where the phone goes; the settings; one spot."
    assert parsed.beats[0].stress == "<the one phrase to stress>"
    items = build_recommendations(text, [])
    assert items == [
        {
            "source": "SCRIPT_CARD",
            "line_index": 0,
            "recommended_for": None,
            "post_type": "REEL",
            "window_label": None,
            "window_from": None,
            "window_to": None,
            "structure_name": "Before-After-Bridge (BAB)",
            "hook_template": "The mistakes that are quietly ruining your [outcome]",
            "topic": "a short title.",
            "festival": None,
        }
    ]


# --- shot cards (spec v2 Phase 6) ------------------------------------------------------------


@pytest.mark.parametrize("case", _shot_card_cases(), ids=lambda c: c["name"])
def test_every_shot_card_field_matches_the_typescript_parser(case):
    """Card for card, field for field: what the REAL TS parser returned for this text."""
    assert _cards(parse_meera_script(case["text"])) == case["cards"], case["name"]


def test_the_shot_card_fixture_is_not_vacuous():
    """The parity above compares real values: every required kind of case is present, cards
    exist, unknowns exist, and both verdicts occur (a gate comparing None to None proves nothing)."""
    cases = {c["name"]: c for c in _shot_card_cases()}
    for name in (
        "shot_cards_every_beat",
        "shot_cards_absent_still_a_card",
        "shot_cards_malformed_line_keeps_the_beat",
        "shot_cards_unknown_enum_values",
        "shot_cards_free_text_at_and_over_the_cap",
        "shot_cards_fewer_lines_than_beats",
        "shot_cards_hindi_reply",
    ):
        assert name in cases, name
    cards = [card for c in cases.values() for card in (c["cards"] or []) if card]
    assert len(cards) >= 30
    assert any("?" in card.values() for card in cards)
    assert {c["expected"] for c in cases.values()} == {"card", "none"}
    assert all(card.keys() == set(SHOT_CARD_KEYS) for card in cards)


def test_a_full_block_parses_to_the_contract_values():
    """Hand-written expectations, independent of the fixture's recorded values."""
    parsed = parse_meera_script(
        {c["name"]: c for c in _shot_card_cases()}["shot_cards_every_beat"]["text"]
    )
    assert parsed.beats[0].card == ShotCard(
        size="MCU", height="eye", distance="0.8-1 m", place="Bedroom desk", light="window-left",
        stand="centre", headroom="small", eyes="lens", background="plain wall", space="right",
        text="top", prop="right-hand", move="still",
    )
    assert parsed.beats[1].card.size == "OVERHEAD" and parsed.beats[1].card.prop == "centre-table"
    assert parsed.beats[2].card.text == "opposite_face" and parsed.beats[2].card.move == "push"
    # The block changes nothing else: the same reply without it is the same script, card-less.
    without = parse_meera_script(
        {c["name"]: c for c in _shot_card_cases()}["shot_cards_absent_still_a_card"]["text"]
    )
    assert [dataclasses.replace(b, card=None) for b in parsed.beats] == list(without.beats)
    assert dataclasses.replace(parsed, beats=without.beats) == without


def test_bad_values_become_unknown_and_bad_lines_cost_only_that_beat():
    cases = {c["name"]: c["text"] for c in _shot_card_cases()}
    enums = parse_meera_script(cases["shot_cards_unknown_enum_values"])
    first = enums.beats[0].card
    assert (first.size, first.light, first.stand, first.prop, first.move) == ("?", "?", "?", "?", "?")
    assert (first.distance, first.place) == ("0.8-1 m", "Bedroom desk")
    assert enums.beats[1].card.light == "ring_light-front" and enums.beats[1].card.size == "OVERHEAD"
    capped = parse_meera_script(cases["shot_cards_free_text_at_and_over_the_cap"])
    assert capped.beats[0].card.place == "p" * 40 and capped.beats[0].card.distance == "d" * 20
    assert (capped.beats[1].card.distance, capped.beats[1].card.place, capped.beats[1].card.background) == ("?", "?", "?")
    assert capped.beats[2].card.place.startswith("\U0001F3E0")  # 40 code points, 50 UTF-16 units: kept
    malformed = parse_meera_script(cases["shot_cards_malformed_line_keeps_the_beat"])
    assert len(malformed.beats) == 3 and malformed.beats[1].card is None
    assert malformed.beats[1].say == "Watch what happens in water"
    fewer = parse_meera_script(cases["shot_cards_fewer_lines_than_beats"])
    assert [b.card is not None for b in fewer.beats] == [True, False, True]
    hindi = parse_meera_script(cases["shot_cards_hindi_reply"])
    assert hindi.beats[0].card.place == "\u092c\u0947\u0921\u0930\u0942\u092e \u0915\u0940 \u0921\u0947\u0938\u094d\u0915"
    assert hindi.beats[0].card.size == "MCU" and hindi.beats[2].card.prop == "?"


@pytest.mark.parametrize(
    "name",
    ["shot_cards_every_beat", "shot_cards_absent_still_a_card", "shot_cards_hindi_reply", "shot_cards_malformed_line_keeps_the_beat"],
)
def test_script_card_recording_does_not_depend_on_the_block(name):
    """Risk R6: with or without the block, the write-back still records exactly one SCRIPT_CARD."""
    text = {c["name"]: c["text"] for c in _shot_card_cases()}[name]
    scripts = [i for i in build_recommendations(text, []) if i["source"] == "SCRIPT_CARD"]
    assert len(scripts) == 1


def test_the_personas_shot_card_line_parses_into_a_card():
    """The persona's own `S1:` example line (placeholders and all) inside a block: the Python port
    reads it as a card, so a key renamed or reordered in the prompt fails here, not silently."""
    source = PERSONA.read_text(encoding="utf-8")
    section = source[source.index("Full script format (only when asked):") :]
    match = re.search(r"^\s*(S1: size=.*)$", section, re.MULTILINE)
    assert match, "the persona's S1 example line moved or changed shape"
    example = _persona_beat_example()
    text = "\n".join(
        [
            "Idea: a short title.",
            "Plan: for whom; the feeling; the goal; 20 seconds.",
            "Action: speak to camera.",
            "Script:",
            example,
            example.replace("0-3s.", "3-10s."),
            example.replace("0-3s.", "10-20s."),
            "Shot cards:",
            match.group(1).strip(),
            "Caption: a caption.",
            "Before you shoot: 1) one 2) two 3) three",
            "Why this works: a reason.",
        ]
    )
    parsed = parse_meera_script(text)
    assert parsed is not None and parsed.beats[0].card is not None
    assert [pair.split("=")[0].strip() for pair in match.group(1).strip()[3:].split(";")] == list(SHOT_CARD_KEYS)
    assert len(build_recommendations(text, [])) == 1
