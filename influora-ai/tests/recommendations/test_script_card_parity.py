"""The Python port of `parseMeeraScript` agrees with the TypeScript original (spec 8.3.1).

A SCRIPT_CARD recommendation is recorded only for a reply the chat renders as a script card, so
"is this a script" must mean the same thing in `src/lib/meera-result-cards.ts` (the browser) and
`app/recommendations/script_card.py` (the write-back). The shared fixture
`tests/fixtures/meera_scripts.json` holds:

- `cases`: every script text of `src/lib/meera-result-cards.test.ts`, built with that file's own
  string operations, and that test's own verdict for it (card = parsed, none = undefined);
- `edge_cases`: text-semantics traps where JavaScript and Python differ by default (trim set,
  `.` vs line terminators, ASCII-only case folding and digits, markdown stripping), each with
  the verdict the REAL TypeScript parser returned (bundled with esbuild, run under node).

A text the TS parser rejects must produce no recommendation -- the last test pins that through
`build_recommendations`, the function the write-back calls.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from app.recommendations.record import build_recommendations
from app.recommendations.script_card import parse_meera_script

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "meera_scripts.json"
PERSONA = Path(__file__).resolve().parents[2] / "app" / "prompt" / "creator_persona.py"
TS_TEST = Path(__file__).resolve().parents[3] / "src" / "lib" / "meera-result-cards.test.ts"


def _fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _all_cases() -> list[dict]:
    data = _fixture()
    return data["cases"] + data["edge_cases"]


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
