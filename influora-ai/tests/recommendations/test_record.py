"""Week-plan and script-card recommendations read off Meera's final reply (spec 8.3).

Pinned here:
- a plan line's date resolves ONLY against the `days` of a plan_my_week result from the same
  turn: no result, a result for another week, or a weekday that contradicts the result's date
  records nothing for that line;
- the persona's plan line shape ("Mon 28 Sep. Evening. Reel. <idea>. <structure>. Goal: <goal>.")
  maps to post_type / window / structure_name / topic; lines that do not parse are skipped but
  keep their index; a rest day records nothing;
- structure and hook names count only when spelled exactly as the knowledge spells them;
- a festival is recorded only when the calendar placed it on that day AND the line mentions it;
- every item carries exactly the keys of the write-back fixture Java deserialises;
- `recommendations_for_turn` records nothing from a brand, failed, refused or cut-off turn, and a
  parser crash costs only the recommendations.

The plan payloads are built from the @JsonProperty names of Java's PlanMyWeekResult, read off the
source (the tests/planner/test_week_plan_real_payload.py rule: a hand-made dict passes while the
wire drifts).
"""

from __future__ import annotations

import json
import logging
import re
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import pytest

from app.recommendations import record
from app.recommendations.record import (
    ITEM_KEYS,
    MAX_ITEMS,
    MAX_TEXT_CHARS,
    STRUCTURE_NAMES,
    build_recommendations,
    parse_week_plan,
    recommendations_for_turn,
)

REPO = Path(__file__).resolve().parents[3]
DTO = REPO / "influora-api/src/main/java/com/influora/web/dto/meera/CreatorToolDtos.java"
SAMPLE = Path(__file__).resolve().parents[1] / "fixtures" / "creator_tools" / "writeback_recommendations.sample.json"


def _plan_fields() -> list[str]:
    if not DTO.is_file():
        pytest.fail(f"{DTO} not found -- run inside a full-repo checkout (a skip is a vacuous pass)")
    source = DTO.read_text(encoding="utf-8")
    start = source.index("public record PlanMyWeekResult(")
    end = source.index(") {}", start)
    return re.findall(r'@JsonProperty\("([^"]+)"\)', source[start:end])


def plan_result(start: str, days: int = 7) -> dict[str, Any]:
    """A plan_my_week result shaped exactly like the Java record, 7 days from `start`."""
    first = date.fromisoformat(start)
    values = {
        "today": start,
        "days": [
            {"date": (first + timedelta(d)).isoformat(), "weekday": (first + timedelta(d)).strftime("%A")}
            for d in range(days)
        ],
        "categories": [],
        "topics": [],
        "pattern": {"enough_data": False, "posts_counted": 0, "best_post_type": None, "windows": [], "note": None},
    }
    fields = _plan_fields()
    missing = [f for f in fields if f not in values]
    assert not missing, f"PlanMyWeekResult gained {missing}; model it here"
    return {f: values[f] for f in fields}


# Mon 28 Sep 2026 .. Sun 4 Oct 2026. The calendar puts Gandhi Jayanti (2 Oct) on Wed 30 Sep.
WEEK = plan_result("2026-09-28")

PERSONA_PLAN = "\n".join(
    [
        "Based on 8 of your posts, your evening Reels have done better so far.",
        "Mon 28 Sep. Evening. Reel. 3 saree draping mistakes vs. the right way. Problem-Agitate-Solve (PAS). Goal: saves.",
        "Tue 29 Sep. Morning. Carousel. My Navratri colour guide. Three-act structure. Goal: reach.",
        "Wed 30 Sep. Evening. Reel. A Gandhi Jayanti khadi look. Before-After-Bridge (BAB). Goal: followers.",
        "Thu 1 Oct. Rest or reply day.",
        "Fri 2 Oct. weekday evening (6-9 pm). Photo post. Behind the scenes of my shoot. Goal: followers.",
        "Sat 3 Oct. Night. Reel. Late night skincare routine. Grab-Story-CTA (micro 3-part structure). Goal: reach.",
        "Sun 4 Oct. Afternoon. Reel. Sunday outfit repeat. Goal: saves.",
        "Want the full script for any day?",
    ]
)


def _by_index(items: list[dict]) -> dict[int, dict]:
    return {i["line_index"]: i for i in items}


# ------------------------------------------------------------------ the persona's plan format


def test_the_persona_plan_is_recorded_line_by_line():
    items = parse_week_plan(PERSONA_PLAN, [WEEK])
    by = _by_index(items)
    assert sorted(by) == [0, 1, 2, 4, 5, 6], "the rest day (index 3) records nothing but keeps its index"
    assert by[0] == {
        "source": "PLAN_MY_WEEK",
        "line_index": 0,
        "recommended_for": "2026-09-28",
        "post_type": "REEL",
        "window_label": "evening",
        "window_from": None,
        "window_to": None,
        "structure_name": "Problem-Agitate-Solve (PAS)",
        "hook_template": None,
        "topic": "3 saree draping mistakes vs. the right way",
        "festival": None,
    }
    assert by[1]["post_type"] == "CAROUSEL" and by[1]["window_label"] == "morning"
    assert by[1]["structure_name"] == "Three-act structure"
    assert by[1]["topic"] == "My Navratri colour guide"
    assert by[2]["festival"] == "Gandhi Jayanti (India)"
    assert by[4]["recommended_for"] == "2026-10-02"
    assert by[4]["post_type"] == "POST"
    assert (by[4]["window_label"], by[4]["window_from"], by[4]["window_to"]) == ("weekday evening", "18:00", "21:00")
    assert by[5]["structure_name"] == "Grab-Story-CTA (micro 3-part structure)"
    assert by[6]["structure_name"] is None and by[6]["topic"] == "Sunday outfit repeat"


def test_every_item_has_exactly_the_fixture_keys():
    sample = json.loads(SAMPLE.read_text(encoding="utf-8"))
    fixture_keys = {k for item in sample["metadata"]["recommendations"] for k in item}
    assert set(ITEM_KEYS) == fixture_keys
    for item in parse_week_plan(PERSONA_PLAN, [WEEK]):
        assert list(item) == list(ITEM_KEYS)


def test_markdown_numbering_and_dash_separators_are_read():
    text = "\n".join(
        [
            "1. **Mon 28 Sep** - Reel - Evening - Saree mistakes",
            "2) Tuesday, 29 September: Carousel. 7 pm. Colour guide",
            "- Wed 30th Sep. Reel. 18:00-21:00. Khadi look",
        ]
    )
    by = _by_index(parse_week_plan(text, [WEEK]))
    assert by[0]["recommended_for"] == "2026-09-28" and by[0]["topic"] == "Saree mistakes"
    assert by[1]["recommended_for"] == "2026-09-29" and by[1]["window_label"] == "evening"
    assert by[2]["window_from"] == "18:00" and by[2]["window_to"] == "21:00"


# ------------------------------------------------------------------ dates: the same turn's plan only


def test_no_plan_result_on_the_turn_records_nothing():
    assert parse_week_plan(PERSONA_PLAN, []) == []
    assert parse_week_plan(PERSONA_PLAN, [None, {"error": "x"}]) == []
    assert build_recommendations(PERSONA_PLAN, []) == []


def test_a_plan_result_for_another_week_resolves_no_line():
    assert parse_week_plan(PERSONA_PLAN, [plan_result("2026-10-12")]) == []


def test_a_weekday_that_contradicts_the_plans_date_is_skipped():
    """28 Sep 2026 is a Monday in the plan; "Tue 28 Sep" is not the plan's day."""
    items = parse_week_plan("Tue 28 Sep. Evening. Reel. An idea.\nTue 29 Sep. Evening. Reel. Another.", [WEEK])
    assert [(i["line_index"], i["recommended_for"]) for i in items] == [(1, "2026-09-29")]


def test_the_year_comes_from_the_plan_days_never_from_a_guess():
    items = parse_week_plan("Fri 1 Jan. Evening. Reel. New year reset.", [plan_result("2026-12-29")])
    assert [i["recommended_for"] for i in items] == ["2027-01-01"]


def test_the_last_plan_result_of_the_turn_is_the_one_used():
    items = parse_week_plan("Mon 12 Oct. Evening. Reel. An idea.", [WEEK, plan_result("2026-10-12")])
    assert [i["recommended_for"] for i in items] == ["2026-10-12"]


def test_only_the_first_seven_day_lines_are_read():
    lines = [f"{(date(2026, 9, 28) + timedelta(d)).strftime('%a %d %b')}. Evening. Reel. Idea {d}." for d in range(7)]
    text = "\n".join(lines + lines)  # the plan written twice
    items = parse_week_plan(text, [WEEK])
    assert len(items) == MAX_ITEMS
    assert [i["line_index"] for i in items] == list(range(7))


# ------------------------------------------------------------------ lines that do not parse


@pytest.mark.parametrize(
    "line",
    [
        "Mon 28 Sep. Evening. Story. Behind the scenes.",  # not a post type the matcher knows
        "Mon 28 Sep. Evening. Saree mistakes.",  # no post type at all
        "Mon 28 Sep. Rest.",
        "Mon 28 Sep. Reply day.",
        "28 Sep is a great day for a Reel about sarees.",  # prose, not a plan line
        "On Mon 28 Sep. Evening. Reel. Idea.",  # a plan line starts with its date
    ],
)
def test_a_line_that_does_not_parse_records_nothing(line):
    assert parse_week_plan(line, [WEEK]) == []


# ------------------------------------------------------------------ time windows


@pytest.mark.parametrize(
    ("segment", "expected"),
    [
        ("Evening", ("evening", None, None)),
        ("weekend morning", ("weekend morning", None, None)),
        ("Late night", ("night", None, None)),
        ("weekday evening (6-9 pm)", ("weekday evening", "18:00", "21:00")),
        ("6-9 pm", (None, "18:00", "21:00")),
        ("11-1 pm", (None, "11:00", "13:00")),
        ("10 pm to 1 am", (None, "22:00", "01:00")),
        ("18:00-21:00", (None, "18:00", "21:00")),
        ("Evening, 6-9", ("evening", "18:00", "21:00")),
        ("7 pm", ("evening", None, None)),
        ("around 7:30 am", ("morning", None, None)),
        ("6-9", None),  # which half of the day? not guessed
        ("3 mistakes", None),  # a number is not a time
        ("Evening outfit ideas", None),  # an idea that mentions a daypart is not a window
    ],
)
def test_window_segments(segment, expected):
    window = record._parse_window(segment)
    if expected is None:
        assert window is None
    else:
        assert (window["window_label"], window["window_from"], window["window_to"]) == expected


# ------------------------------------------------------------------ knowledge names, festivals


def test_a_structure_counts_only_spelled_exactly_as_the_knowledge_spells_it():
    exact = parse_week_plan("Mon 28 Sep. Evening. Reel. Idea. Before-After-Bridge (BAB).", [WEEK])
    loose = parse_week_plan("Mon 28 Sep. Evening. Reel. Idea. before-after-bridge (bab).", [WEEK])
    assert exact[0]["structure_name"] == "Before-After-Bridge (BAB)"
    assert loose[0]["structure_name"] is None
    assert loose[0]["topic"] == "Idea. before-after-bridge (bab)"


def test_two_structure_names_in_one_line_name_neither():
    item = parse_week_plan(
        "Mon 28 Sep. Evening. Reel. Three-act structure meets Before-After-Bridge (BAB) in one reel.", [WEEK]
    )[0]
    assert item["structure_name"] is None


def test_structure_names_are_the_knowledge_frameworks():
    assert "Before-After-Bridge (BAB)" in STRUCTURE_NAMES
    assert len(STRUCTURE_NAMES) == len(set(STRUCTURE_NAMES)) >= 5


def test_a_festival_is_recorded_only_on_the_day_the_calendar_put_it():
    week = plan_result("2026-10-30")  # the calendar puts Diwali (8 Nov) on Sun 1 Nov
    items = _by_index(
        parse_week_plan(
            "Sun 1 Nov. Evening. Reel. Diwali rangoli in 30 seconds.\n"
            "Mon 2 Nov. Evening. Reel. Diwali gift ideas.\n"
            "Sun 1 Nov. Evening. Reel. Winter skincare.",
            [week],
        )
    )
    assert items[0]["festival"] == "Diwali"
    assert items[1]["festival"] is None, "Diwali is not on 2 Nov in this plan"
    assert items[2]["festival"] is None, "the line does not mention it"


def test_a_festival_alias_names_the_calendar_event():
    assert record._festival_aliases("Navratri and Durga Puja") == ["Navratri", "Durga Puja"]
    assert record._festival_aliases("Eid al-Adha (Bakrid)") == ["Bakrid", "Eid al-Adha"]
    assert record._festival_aliases("Independence Day (India)") == ["Independence Day"]


# ------------------------------------------------------------------ script cards


def test_a_script_card_is_one_reel_item_with_no_date():
    text = json.loads((SAMPLE.parent.parent / "meera_scripts.json").read_text(encoding="utf-8"))["cases"][0]["text"]
    items = build_recommendations(text, [WEEK])
    assert items == [
        {
            "source": "SCRIPT_CARD",
            "line_index": 0,
            "recommended_for": None,
            "post_type": "REEL",
            "window_label": None,
            "window_from": None,
            "window_to": None,
            # "Problem-agitate-solve structure" is not the knowledge's "Problem-Agitate-Solve (PAS)"
            "structure_name": None,
            "hook_template": None,
            "topic": "3 saffron mistakes to avoid",
            "festival": None,
        }
    ]


def test_a_script_names_its_structure_and_hook_only_exactly_as_the_knowledge_does():
    base = json.loads((SAMPLE.parent.parent / "meera_scripts.json").read_text(encoding="utf-8"))["cases"][0]["text"]
    old_why = "Why this works: Problem-agitate-solve structure, curiosity-gap hook template"
    exact = base.replace(
        old_why, "Why this works: Problem-Agitate-Solve (PAS) for the doubt; [Common mistake] - here's the right way to open."
    )
    loose = base.replace(
        old_why, "Why this works: problem-agitate-solve (pas) for the doubt; [common mistake] - here's the right way to open."
    )
    assert exact != base and loose != base
    item = build_recommendations(exact, [])[0]
    assert (item["structure_name"], item["hook_template"]) == (
        "Problem-Agitate-Solve (PAS)",
        "[Common mistake] - here's the right way",
    )
    item = build_recommendations(loose, [])[0]
    assert (item["structure_name"], item["hook_template"]) == (None, None)


# ------------------------------------------------------------------ bounds


def test_an_oversized_reply_is_not_scanned():
    assert build_recommendations(PERSONA_PLAN + "\n" + "x" * MAX_TEXT_CHARS, [WEEK]) == []


def test_non_text_records_nothing():
    assert build_recommendations(None, [WEEK]) == []  # type: ignore[arg-type]
    assert build_recommendations("   ", [WEEK]) == []


# ------------------------------------------------------------------ recommendations_for_turn gates


def _turn(**overrides):
    kwargs = dict(
        audience="CREATOR",
        final_text=PERSONA_PLAN,
        plan_results=[WEEK],
        provider_failed=False,
        finish_reason="stop",
        stop_reason="end_turn",
    )
    kwargs.update(overrides)
    return recommendations_for_turn(**kwargs)


def test_a_clean_creator_turn_records():
    assert len(_turn()) == 6
    assert len(_turn(stop_reason=None)) == 6


@pytest.mark.parametrize(
    "overrides",
    [
        {"audience": "BRAND"},
        {"audience": None},
        {"provider_failed": True},
        {"finish_reason": "empty_response"},
        {"finish_reason": "truncated_tool_use"},
        {"finish_reason": "pending_human_confirm"},
        {"finish_reason": "money_tool_scope_declined"},
        {"finish_reason": "iteration_cap"},
        {"stop_reason": "refusal"},
        {"stop_reason": "max_tokens"},
    ],
)
def test_a_brand_failed_refused_or_cut_off_turn_records_nothing(overrides):
    assert _turn(**overrides) == []


def test_a_parser_crash_costs_only_the_recommendations(monkeypatch, caplog):
    def boom(*_a, **_k):
        raise RuntimeError("parser bug")

    monkeypatch.setattr(record, "build_recommendations", boom)
    with caplog.at_level(logging.WARNING, logger="app.recommendations.record"):
        assert _turn() == []
    assert any(r.getMessage() == "meera_recommendations_parse_failed" for r in caplog.records)
