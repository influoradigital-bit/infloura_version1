"""`plan_my_week`: the festival calendar folded into the server's dated plan.

Two rules this file defends:

- **no date is ever invented.** Every date in the enriched plan is one Spring sent. A result with
  no usable date is passed through untouched rather than enriched against a guess, and a festival
  whose date nobody has verified is left out entirely.
- **the hand-typed part stays untrusted.** Dates, the posting pattern and the calendar are the
  server's and this repo's; `topics` is the `content_topics` table, so it rides inside
  `<untrusted_editorial>` exactly as it does for `get_todays_topics`.
"""

from __future__ import annotations

import json
from datetime import date, timedelta

from app.planner.events import load_events
from app.planner.week_plan import enrich_week_plan
from app.prompt.creator_persona import CREATOR_CAPABILITY_LINES, MEERA_CREATOR_PERSONA
from app.tools.creator_schemas import (
    CREATOR_TOOL_NAMES,
    CREATOR_TOOL_TO_SPRING_PATH,
    PLAN_MY_WEEK,
    all_creator_tool_schemas,
    is_creator_tool,
)
from app.tools.loop import _model_copy_of_tool_result


def _flat(text: str) -> str:
    return " ".join(text.split())


TEXT = _flat(MEERA_CREATOR_PERSONA)

# 23-29 September 2026: Wednesday to Tuesday. World Tourism Day (27th) and World Heart Day (29th)
# are fixed rows in the committed calendar; Coffee Day (1 Oct) is outside the window.
_DAYS = [
    {"date": "2026-09-23", "weekday": "Wednesday"},
    {"date": "2026-09-24", "weekday": "Thursday"},
    {"date": "2026-09-25", "weekday": "Friday"},
    {"date": "2026-09-26", "weekday": "Saturday"},
    {"date": "2026-09-27", "weekday": "Sunday"},
    {"date": "2026-09-28", "weekday": "Monday"},
    {"date": "2026-09-29", "weekday": "Tuesday"},
]


def _plan(**over) -> dict:
    data = {
        "today": "2026-09-23",
        "days": [dict(day) for day in _DAYS],
        "categories": ["Food"],
        "topics": [
            {
                "id": 7,
                "category": "Food",
                "title": "Filter coffee is having a moment",
                "angles": ["Two ways at home"],
                "live_until": "2026-10-07",
                "sensitivity": None,
            }
        ],
        "pattern": {
            "enough_data": True,
            "posts_counted": 14,
            "best_post_type": "REEL",
            "windows": [{"label": "weekday evening", "posts": 6, "engagement_rate": "4.8%"}],
            "note": None,
        },
    }
    data.update(over)
    return data


# --- the calendar is attached to the server's days -------------------------------


def _days_carrying(enriched: dict, name: str) -> dict[str, dict]:
    return {
        day["date"]: event
        for day in enriched["days"]
        for event in day["events"]
        if event["name"] == name
    }


def test_events_land_on_the_dates_spring_sent():
    enriched = enrich_week_plan(_plan())
    assert [day["date"] for day in enriched["days"]] == [day["date"] for day in _DAYS]
    assert [day["weekday"] for day in enriched["days"]] == [day["weekday"] for day in _DAYS]
    # World Tourism Day is 27 September, post_before_days 3, and fits Food: it goes on its post
    # day, 24 September, and on no other day (lane B2: it used to fill 24-27).
    assert set(_days_carrying(enriched, "World Tourism Day")) == {"2026-09-24"}
    # World Heart Day is 29 September, post_before_days 2, and fits Food: post day 27 September.
    assert set(_days_carrying(enriched, "World Heart Day")) == {"2026-09-27"}


def test_days_until_counts_down_to_the_day_itself():
    enriched = enrich_week_plan(_plan())
    tourism = _days_carrying(enriched, "World Tourism Day")
    # Counted from the post day to the day itself, and post_by names the day itself.
    assert {day: event["days_until"] for day, event in tourism.items()} == {"2026-09-24": 3}
    assert tourism["2026-09-24"]["post_by"] == "2026-09-27"
    assert tourism["2026-09-24"]["date"] == "2026-09-27"


def _week_from(start: str) -> list[dict]:
    first = date.fromisoformat(start)
    return [
        {"date": (first + timedelta(days=n)).isoformat(), "weekday": (first + timedelta(days=n)).strftime("%A")}
        for n in range(7)
    ]


def test_a_post_day_before_the_window_is_clamped_to_its_first_day():
    # Window starts 26 September: Tourism Day's post day (24th) has passed, so it goes on the 26th,
    # the first day of the window, with one day to go -- never after the 27th.
    enriched = enrich_week_plan(_plan(today="2026-09-26", days=_week_from("2026-09-26")))
    tourism = _days_carrying(enriched, "World Tourism Day")
    assert {day: event["days_until"] for day, event in tourism.items()} == {"2026-09-26": 1}
    # An event whose day is the window's first day stays on it, 0 days to go.
    on_the_day = enrich_week_plan(_plan(today="2026-09-27", days=_week_from("2026-09-27")))
    tourism = _days_carrying(on_the_day, "World Tourism Day")
    assert {day: event["days_until"] for day, event in tourism.items()} == {"2026-09-27": 0}


def test_each_event_sits_on_exactly_one_day_and_the_week_keeps_free_days():
    """ai.md H1, the audit's own week: a Food and Fitness creator on 2026-09-24 saw World Tourism
    Day on four days running and an event on all seven days, so the persona's "a day with an event
    is built around it" left no evergreen idea and no rest day."""
    enriched = enrich_week_plan(
        _plan(today="2026-09-24", days=_week_from("2026-09-24"), categories=["Food", "Fitness"])
    )
    placements = [
        (event["name"], event["post_by"]) for day in enriched["days"] for event in day["events"]
    ]
    assert placements, "the week should still carry its festivals"
    assert len(placements) == len(set(placements)), placements
    free_days = [day["date"] for day in enriched["days"] if not day["events"]]
    assert len(free_days) >= 1, "no day is left for an evergreen idea or a rest day"
    by_date = {day["date"]: {e["name"] for e in day["events"]} for day in enriched["days"]}
    assert by_date["2026-09-24"] == {"World Tourism Day", "Daughters' Day (India)"}
    assert by_date["2026-09-27"] == {"World Heart Day"}


def test_events_carry_their_angles_and_sensitivity_note():
    enriched = enrich_week_plan(_plan())
    event = next(
        event
        for day in enriched["days"]
        for event in day["events"]
        if event["name"] == "World Tourism Day"
    )
    assert event["angles"]
    assert "permission" in event["sensitivity"]


def test_seasons_are_attached_as_context():
    enriched = enrich_week_plan(_plan())
    assert "Festive shopping season (India)" in {s["name"] for s in enriched["seasons"]}


def test_a_creator_in_another_category_sees_different_days():
    enriched = enrich_week_plan(_plan(categories=["Finance"]))
    names = {event["name"] for day in enriched["days"] for event in day["events"]}
    assert "World Heart Day" not in names  # fits Fitness and Food only


def test_nothing_is_mutated_and_the_original_survives():
    original = _plan()
    before = json.dumps(original, sort_keys=True)
    enrich_week_plan(original)
    assert json.dumps(original, sort_keys=True) == before


# --- no date is ever invented ----------------------------------------------------


def test_a_plan_with_no_usable_date_is_passed_through_untouched():
    for broken in ({"days": _DAYS}, _plan(today="not a date"), _plan(today=None), _plan(days=[])):
        assert enrich_week_plan(broken) == broken


def test_a_non_dict_payload_is_returned_as_is():
    assert enrich_week_plan(None) is None
    assert enrich_week_plan("boom") == "boom"


def test_an_unverified_festival_is_left_out_rather_than_dated(tmp_path):
    """The rule, pinned against a FIXTURE row rather than the shipped data: as soon as somebody
    fills in a real Diwali date (app/planner/EVENT-DATES.md), a test written against the shipped
    Diwali would start proving the opposite of what it claims."""
    unverified = {
        "event_type": "variable",
        "name": "Moon Festival (no date on file)",
        "type": "festival",
        "fits": ["ALL"],
        "angles": ["An evergreen idea"],
        "post_before_days": 3,
        "sensitivity": "none",
        "region": "India",
        "date_status": "needs_verified_date",
        "year_dates": {},
    }
    november = _plan(
        today="2026-11-03",
        days=[{"date": f"2026-11-{day:02d}", "weekday": "Tuesday"} for day in range(3, 10)],
        categories=["Food"],
    )
    enriched = enrich_week_plan(november, rows=[unverified])
    names = {event["name"] for day in enriched["days"] for event in day["events"]}
    assert names == set(), names


def test_a_day_shape_spring_does_not_send_is_left_alone():
    enriched = enrich_week_plan(_plan(days=[*_DAYS[:6], "Tuesday"]))
    assert enriched["days"][6] == "Tuesday"


# --- the trust split ------------------------------------------------------------


def test_topics_are_wrapped_while_dates_and_pattern_stay_trusted():
    rendered = _model_copy_of_tool_result(PLAN_MY_WEEK, _plan())
    trusted_part, _, wrapped = rendered.partition("\n")
    trusted = json.loads(trusted_part)
    assert set(trusted) == {"today", "days", "pattern", "categories", "seasons"}
    assert trusted["today"] == "2026-09-23"
    assert trusted["pattern"]["posts_counted"] == 14
    assert "World Tourism Day" in trusted_part  # the calendar is ours, so it is trusted
    assert "<untrusted_editorial>" in wrapped
    assert "Filter coffee is having a moment" not in trusted_part
    assert "Filter coffee is having a moment" in wrapped


def test_an_unknown_key_from_spring_is_treated_as_untrusted():
    rendered = _model_copy_of_tool_result(PLAN_MY_WEEK, _plan(note="typed somewhere"))
    trusted_part, _, wrapped = rendered.partition("\n")
    assert "typed somewhere" not in trusted_part
    assert "typed somewhere" in wrapped


def test_an_empty_topic_list_still_rides_in_the_wrapper():
    # Deliberate: the split is by KEY, not by whether the value happens to be empty today, so a
    # `topics` list that fills up later cannot arrive trusted because it was empty when the rule
    # was written.
    rendered = _model_copy_of_tool_result(PLAN_MY_WEEK, _plan(topics=[]))
    trusted_part, _, wrapped = rendered.partition("\n")
    assert json.loads(trusted_part)["today"] == "2026-09-23"
    assert "topics" not in trusted_part
    assert "<untrusted_editorial>" in wrapped


def test_a_plan_carrying_only_server_keys_needs_no_wrapper():
    plan = _plan()
    plan.pop("topics")
    rendered = _model_copy_of_tool_result(PLAN_MY_WEEK, plan)
    assert "<untrusted" not in rendered
    assert json.loads(rendered)["today"] == "2026-09-23"


# --- wiring and the prompt ------------------------------------------------------


def test_the_tool_is_wired_as_a_read():
    assert PLAN_MY_WEEK in CREATOR_TOOL_NAMES
    assert is_creator_tool(PLAN_MY_WEEK)
    assert CREATOR_TOOL_TO_SPRING_PATH[PLAN_MY_WEEK] == "/internal/meera/creator/plan_my_week"
    schema = next(s for s in all_creator_tool_schemas() if s["name"] == PLAN_MY_WEEK)
    assert schema["input_schema"]["properties"] == {}
    description = _flat(schema["description"])
    assert "you have no other way to know them" in description
    assert "`enough_data` decides whether you may say a best time at all" in description


def test_the_capability_bullet_exists_and_names_no_other_tool():
    bullet = CREATOR_CAPABILITY_LINES[PLAN_MY_WEEK]
    assert "Call it before planning a week or saying when to post" in bullet
    for other in CREATOR_TOOL_NAMES:
        if other != PLAN_MY_WEEK:
            assert other not in bullet


def test_the_persona_pins_the_plan_format():
    assert "Week plan format (only when asked for a plan or a calendar):" in TEXT
    assert "Read the plan tool first." in TEXT
    assert "Never work out a date yourself" in TEXT
    assert '"Mon 28 Sep. Evening. Reel. <the idea>. <structure name>. Goal: <goal>."' in TEXT
    assert "with the number of posts it is based on" in TEXT
    assert "these are suggestions until they have posted more" in TEXT
    assert "Never say a festival is on a date the tool did not give you." in TEXT
    assert "keep one rest or reply day" in TEXT
    assert "End by offering the full script for any day." in TEXT
    # Lane B2: the rule matches the data -- one day per event, and post_by is the day itself.
    assert "Each festival or special day sits on ONE day of the plan" in TEXT
    assert "never repeat it on another day" in TEXT
    assert "A day with a festival or special day in the tool is built around it" not in TEXT


def test_the_committed_calendar_is_what_the_plan_reads():
    # enrich_week_plan defaults to the committed rows; passing them explicitly must match.
    assert enrich_week_plan(_plan(), rows=load_events()) == enrich_week_plan(_plan())
