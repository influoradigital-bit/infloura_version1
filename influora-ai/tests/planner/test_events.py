"""The festival calendar: loaded strictly, and never a guessed date.

The rule these tests exist to defend: a `variable` row (Diwali, Holi, Eid, Navratri) is served
ONLY when a human has filled in a verified date for that year. Everything else about the loader is
the same fail-loud contract the content-knowledge loader has.
"""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pytest

from app.planner.events import (
    EVENT_ROWS,
    EVENTS_PATH,
    EventsFileError,
    events_for_week,
    load_events,
    nth_weekday,
)


def _write(tmp_path: Path, rows: list) -> Path:
    path = tmp_path / "events.jsonl"
    path.write_bytes(
        b"".join(
            (row if isinstance(row, str) else json.dumps(row, ensure_ascii=False)).encode("utf-8")
            + b"\n"
            for row in rows
        )
    )
    return path


def _fixed(**over) -> dict:
    row = {
        "event_type": "fixed",
        "month_day": "06-21",
        "name": "International Yoga Day",
        "type": "international_day",
        "fits": ["Fitness"],
        "angles": ["Five minutes of desk stretches"],
        "post_before_days": 3,
        "sensitivity": "no medical claims",
        "region": "global",
        "date_status": "fixed",
    }
    row.update(over)
    return row


def _variable(**over) -> dict:
    row = {
        "event_type": "variable",
        "name": "Diwali",
        "type": "festival",
        "fits": ["ALL"],
        "angles": ["Three mithai without a thermometer"],
        "post_before_days": 7,
        "sensitivity": "respectful",
        "region": "India",
        "date_status": "needs_verified_date",
        "year_dates": {},
    }
    row.update(over)
    return row


# --- the committed file ---------------------------------------------------------


def test_committed_file_loads_and_every_variable_row_is_still_unverified():
    rows = EVENT_ROWS
    assert len(rows) == 59
    by_type: dict[str, int] = {}
    for row in rows:
        by_type[row["event_type"]] = by_type.get(row["event_type"], 0) + 1
    assert by_type == {"fixed": 30, "rule": 4, "season": 5, "variable": 20}
    # Nobody has filled in a lunar date yet, and no date was invented for one.
    for row in rows:
        if row["event_type"] == "variable":
            assert row["year_dates"] == {}, row["name"]


def test_committed_file_has_no_illness_or_mourning_days():
    names = " ".join(row["name"].casefold() for row in EVENT_ROWS)
    for banned in ("cancer", "aids", "memorial", "mourning", "martyr"):
        assert banned not in names, banned


def test_every_committed_row_carries_angles_and_a_sensitivity_note():
    for row in EVENT_ROWS:
        assert row["angles"], row["name"]
        assert row["sensitivity"].strip(), row["name"]


def test_religious_and_national_rows_say_respectful():
    for name in ("Diwali", "Eid al-Fitr", "Independence Day (India)", "Christmas"):
        row = next(r for r in EVENT_ROWS if r["name"] == name)
        assert "respect" in row["sensitivity"].casefold(), name


# --- the loader refuses bad data -------------------------------------------------


def test_loader_accepts_a_good_row(tmp_path):
    assert len(load_events(_write(tmp_path, [_fixed()]))) == 1


@pytest.mark.parametrize(
    "row, message",
    [
        (_fixed(event_type="party"), "unknown event_type"),
        (_fixed(month_day="6-21"), "month_day must look like"),
        (_fixed(month_day="13-01"), "month_day must look like"),
        (_fixed(month_day="02-30"), "is not a real date"),
        (_fixed(month_day="02-29"), "not a usable fixed date"),
        (_fixed(fits=[]), "'fits' must be a non-empty list"),
        (_fixed(angles=["  "]), "'angles' must be a non-empty list"),
        (_fixed(post_before_days=-1), "post_before_days"),
        (_fixed(post_before_days="3"), "post_before_days"),
        (_fixed(sensitivity="   "), "missing required field 'sensitivity'"),
        ({k: v for k, v in _fixed().items() if k != "month_day"}, "needs 'month_day'"),
        ({"event_type": "rule", **{k: v for k, v in _fixed().items() if k != "event_type"},
          "rule": "sometime in May"}, "rule must look like"),
        ({"event_type": "season", **{k: v for k, v in _fixed().items() if k != "event_type"},
          "window": "June to September"}, "window must look like"),
        (_variable(year_dates={"2026": "Diwali night"}), "must be an ISO date"),
        (_variable(year_dates={"2026": "2027-11-08"}), "does not match its date"),
        (_variable(year_dates={"2026": "2026-02-30"}), "not a real date"),
        (_variable(year_dates=["2026-11-08"]), "year_dates must be an object"),
    ],
)
def test_loader_rejects_a_bad_row(tmp_path, row, message):
    with pytest.raises(EventsFileError, match=message):
        load_events(_write(tmp_path, [row]))


def test_loader_rejects_bad_json_empty_file_and_duplicate_names(tmp_path):
    with pytest.raises(EventsFileError, match="invalid JSON"):
        load_events(_write(tmp_path, ["{not json"]))
    with pytest.raises(EventsFileError, match="no rows"):
        load_events(_write(tmp_path, []))
    with pytest.raises(EventsFileError, match="duplicate event"):
        load_events(_write(tmp_path, [_fixed(), _fixed()]))


# --- rule dates are computed, not guessed ---------------------------------------


def test_nth_weekday_computes_real_dates():
    # Mother's Day 2027: second Sunday of May.
    assert nth_weekday(2027, 5, 6, 2) == date(2027, 5, 9)
    # Father's Day 2027: third Sunday of June.
    assert nth_weekday(2027, 6, 6, 3) == date(2027, 6, 20)
    # A fifth Sunday that does not exist is None, not the last Sunday.
    assert nth_weekday(2027, 2, 6, 5) is None


def test_a_rule_row_lands_on_its_computed_day():
    rows = load_events(EVENTS_PATH)
    plan = events_for_week(rows, date(2027, 5, 7), ["Food"], days=7)
    hits = [
        (day["date"], event["days_until"])
        for day in plan["days"]
        for event in day["events"]
        if event["name"] == "Mother's Day"
    ]
    # post_before_days is 4, so it appears from 5 May; the window starts 7 May.
    assert ("2027-05-09", 0) in hits
    assert ("2027-05-07", 2) in hits


# --- the week window ------------------------------------------------------------


def test_a_variable_row_with_no_verified_date_is_reported_never_served(tmp_path):
    rows = load_events(_write(tmp_path, [_variable()]))
    plan = events_for_week(rows, date(2026, 11, 1), ["Food"], days=7)
    assert plan["missing_verified_dates"] == ["Diwali"]
    assert all(day["events"] == [] for day in plan["days"])


def test_a_variable_row_is_served_once_its_date_is_filled_in(tmp_path):
    rows = load_events(_write(tmp_path, [_variable(year_dates={"2026": "2026-11-08"})]))
    plan = events_for_week(rows, date(2026, 11, 3), ["Food"], days=7)
    assert plan["missing_verified_dates"] == []
    days_with_diwali = {
        day["date"]: day["events"][0]["days_until"] for day in plan["days"] if day["events"]
    }
    # post_before_days 7, so every day of this window carries it, counting down to the day itself.
    assert days_with_diwali["2026-11-03"] == 5
    assert days_with_diwali["2026-11-08"] == 0


def test_lead_time_keeps_an_event_out_of_view_until_it_is_due(tmp_path):
    rows = load_events(_write(tmp_path, [_fixed(month_day="06-21", post_before_days=3)]))
    early = events_for_week(rows, date(2027, 6, 10), ["Fitness"], days=7)
    assert all(day["events"] == [] for day in early["days"])
    due = events_for_week(rows, date(2027, 6, 18), ["Fitness"], days=7)
    assert [day["date"] for day in due["days"] if day["events"]] == [
        "2027-06-18",
        "2027-06-19",
        "2027-06-20",
        "2027-06-21",
    ]
    # Never after the day itself.
    after = events_for_week(rows, date(2027, 6, 22), ["Fitness"], days=7)
    assert all(day["events"] == [] for day in after["days"])


def test_the_window_is_seven_dated_days_with_real_weekday_names():
    plan = events_for_week(EVENT_ROWS, date(2026, 9, 23), ["Food"], days=7)
    assert [day["date"] for day in plan["days"]] == [
        "2026-09-23",
        "2026-09-24",
        "2026-09-25",
        "2026-09-26",
        "2026-09-27",
        "2026-09-28",
        "2026-09-29",
    ]
    assert plan["days"][0]["weekday"] == "Wednesday"
    assert plan["days"][6]["weekday"] == "Tuesday"


def test_a_window_spanning_new_year_still_finds_both_years(tmp_path):
    rows = load_events(_write(tmp_path, [_fixed(month_day="01-01", name="New Year", post_before_days=3)]))
    plan = events_for_week(rows, date(2026, 12, 29), ["Fitness"], days=7)
    assert [day["date"] for day in plan["days"] if day["events"]] == [
        "2026-12-29",
        "2026-12-30",
        "2026-12-31",
        "2027-01-01",
    ]


# --- categories -----------------------------------------------------------------


def test_only_matching_categories_see_a_row():
    plan = events_for_week(EVENT_ROWS, date(2027, 6, 19), ["Finance"], days=7)
    names = {event["name"] for day in plan["days"] for event in day["events"]}
    assert "International Yoga Day" not in names

    fitness = events_for_week(EVENT_ROWS, date(2027, 6, 19), ["Fitness"], days=7)
    fitness_names = {event["name"] for day in fitness["days"] for event in day["events"]}
    assert "International Yoga Day" in fitness_names


def test_category_match_ignores_case_and_spacing():
    plan = events_for_week(EVENT_ROWS, date(2027, 6, 19), ["  fitness "], days=7)
    names = {event["name"] for day in plan["days"] for event in day["events"]}
    assert "International Yoga Day" in names


def test_a_creator_with_no_categories_still_gets_the_all_rows():
    plan = events_for_week(EVENT_ROWS, date(2027, 8, 13), [], days=7)
    names = {event["name"] for day in plan["days"] for event in day["events"]}
    assert "Independence Day (India)" in names  # fits: ALL
    assert "International Yoga Day" not in names


# --- seasons --------------------------------------------------------------------


def test_seasons_are_context_not_a_day():
    monsoon = events_for_week(EVENT_ROWS, date(2027, 7, 1), ["Food"], days=7)
    assert "Monsoon" in {season["name"] for season in monsoon["seasons"]}
    assert "Monsoon" not in {
        event["name"] for day in monsoon["days"] for event in day["events"]
    }


def test_a_season_window_that_wraps_the_new_year_matches_january():
    winter = events_for_week(EVENT_ROWS, date(2027, 1, 10), ["Food"], days=7)
    assert "Winter" in {season["name"] for season in winter["seasons"]}
    summer = events_for_week(EVENT_ROWS, date(2027, 4, 10), ["Food"], days=7)
    assert "Winter" not in {season["name"] for season in summer["seasons"]}
    assert "Summer" in {season["name"] for season in summer["seasons"]}
