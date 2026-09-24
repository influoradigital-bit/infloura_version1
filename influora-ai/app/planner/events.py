"""The festival and special-days calendar: load it, and find what falls in a week.

Why this file exists (Swapnil, 2026-09-22/23): a creator's hardest question is not "how do I
grow" but "what do I post today". A dated calendar answers it for every creator, connected or
not, while today's topics (`content_topics`, typed in by hand) cover what is current.

**No date is ever guessed.** The data carries four row shapes and this module refuses to invent
a date for any of them:

- `fixed` — the same month and day every year (Republic Day, Yoga Day, Christmas).
- `rule` — computed from a rule ("second Sunday of May" for Mother's Day).
- `season` — a window, not a day (monsoon, exam season). Never rendered as "on this date".
- `variable` — Diwali, Holi, Eid, Navratri and the rest, whose date follows the moon. Each row
  carries a `year_dates` map that is EMPTY until a human fills in a verified date. A variable row
  with no verified date for the year in question is NOT served, ever: a wrong Diwali date in front
  of a creator is worse than no calendar at all. `missing_verified_dates` reports them so the gap
  is visible instead of silent.

The model is never asked to work any of this out. It is handed dated rows by
`plan_my_week`, whose dates come from Spring in Asia/Kolkata — nothing in the prompt tells the
model what day it is (Ash, `wiki/ai-review/daily-topics-week-plan-ai-review.md`, P0-1).

Fail-loud contract, same as `app/prompt/content_knowledge.py`: every row is validated at import
and a malformed file raises `EventsFileError` at startup rather than degrading into a shorter
calendar nobody notices.
"""

from __future__ import annotations

import calendar
import json
import re
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from app.planner.categories import match_keys, normalise_category

EVENTS_PATH = Path(__file__).parent / "events.jsonl"

_COMMON_REQUIRED: tuple[str, ...] = (
    "event_type",
    "name",
    "type",
    "fits",
    "angles",
    "post_before_days",
    "sensitivity",
    "region",
    "date_status",
)

# Per event_type, the extra field that carries its timing.
_TIMING_FIELD: dict[str, str] = {
    "fixed": "month_day",
    "rule": "rule",
    "season": "window",
    "variable": "year_dates",
}

# A row whose `fits` contains this suits every creator, whatever their categories.
FITS_ALL = "ALL"

_MONTH_DAY = re.compile(r"^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
_WINDOW = re.compile(r"^(\d{2}-\d{2}) to (\d{2}-\d{2})$")
_ISO_DATE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})$")

_ORDINALS: dict[str, int] = {"first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5}
_WEEKDAYS: dict[str, int] = {
    "monday": 0,
    "tuesday": 1,
    "wednesday": 2,
    "thursday": 3,
    "friday": 4,
    "saturday": 5,
    "sunday": 6,
}
_MONTHS: dict[str, int] = {name.lower(): i for i, name in enumerate(calendar.month_name) if name}

# "second Sunday of May"
_RULE = re.compile(
    r"^(?P<ordinal>first|second|third|fourth|fifth)\s+(?P<weekday>[a-z]+)\s+of\s+(?P<month>[a-z]+)$",
    re.IGNORECASE,
)


class EventsFileError(ValueError):
    """The events file is malformed. Raised at import, never mid-conversation."""


def _require_str_list(row: dict[str, Any], key: str, lineno: int) -> None:
    value = row.get(key)
    if not isinstance(value, list) or not value or not all(
        isinstance(item, str) and item.strip() for item in value
    ):
        raise EventsFileError(
            f"line {lineno}: {key!r} must be a non-empty list of non-empty strings"
        )


def _month_day(value: str, lineno: int, field: str) -> tuple[int, int]:
    if not isinstance(value, str) or not _MONTH_DAY.match(value.strip()):
        raise EventsFileError(f"line {lineno}: {field} must look like '06-21', got {value!r}")
    month, day = (int(part) for part in value.strip().split("-"))
    # 29 February would silently skip three years in four; a fixed event cannot live there.
    if (month, day) == (2, 29):
        raise EventsFileError(f"line {lineno}: {field} 02-29 is not a usable fixed date")
    if day > calendar.monthrange(2027, month)[1]:  # a non-leap year, so February caps at 28
        raise EventsFileError(f"line {lineno}: {field} {value!r} is not a real date")
    return month, day


def _validate_row(row: Any, lineno: int) -> dict[str, Any]:
    if not isinstance(row, dict):
        raise EventsFileError(f"line {lineno}: row is not a JSON object")
    event_type = row.get("event_type")
    if event_type not in _TIMING_FIELD:
        raise EventsFileError(f"line {lineno}: unknown event_type {event_type!r}")

    for key in _COMMON_REQUIRED:
        if key == "post_before_days":
            value = row.get(key)
            if not isinstance(value, int) or isinstance(value, bool) or value < 0 or value > 30:
                raise EventsFileError(
                    f"line {lineno}: post_before_days must be a whole number 0-30"
                )
            continue
        if key in ("fits", "angles"):
            _require_str_list(row, key, lineno)
            continue
        value = row.get(key)
        if not isinstance(value, str) or not value.strip():
            raise EventsFileError(f"line {lineno}: missing required field {key!r}")

    timing = _TIMING_FIELD[event_type]
    if timing not in row:
        raise EventsFileError(f"line {lineno}: {event_type} row needs {timing!r}")

    if event_type == "fixed":
        _month_day(row["month_day"], lineno, "month_day")
    elif event_type == "rule":
        if not _RULE.match(str(row["rule"]).strip()):
            raise EventsFileError(
                f"line {lineno}: rule must look like 'second Sunday of May', got {row['rule']!r}"
            )
        parsed = _RULE.match(str(row["rule"]).strip())
        assert parsed is not None  # guarded above
        if parsed.group("weekday").lower() not in _WEEKDAYS:
            raise EventsFileError(f"line {lineno}: unknown weekday in rule {row['rule']!r}")
        if parsed.group("month").lower() not in _MONTHS:
            raise EventsFileError(f"line {lineno}: unknown month in rule {row['rule']!r}")
    elif event_type == "season":
        window = _WINDOW.match(str(row["window"]).strip())
        if not window:
            raise EventsFileError(
                f"line {lineno}: window must look like '06-01 to 09-15', got {row['window']!r}"
            )
        _month_day(window.group(1), lineno, "window start")
        _month_day(window.group(2), lineno, "window end")
    else:  # variable
        year_dates = row["year_dates"]
        if not isinstance(year_dates, dict):
            raise EventsFileError(f"line {lineno}: year_dates must be an object")
        for year, value in year_dates.items():
            iso = _ISO_DATE.match(str(value).strip()) if value is not None else None
            if not iso:
                raise EventsFileError(
                    f"line {lineno}: year_dates[{year!r}] must be an ISO date, got {value!r}"
                )
            try:
                parsed_date = date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3)))
            except ValueError as exc:
                raise EventsFileError(
                    f"line {lineno}: year_dates[{year!r}] is not a real date ({exc})"
                ) from exc
            # A date filed under the wrong year is how a verified date turns into a wrong one.
            if str(year).strip() != str(parsed_date.year):
                raise EventsFileError(
                    f"line {lineno}: year_dates key {year!r} does not match its date {value!r}"
                )
    return row


def load_events(path: Path = EVENTS_PATH) -> list[dict[str, Any]]:
    """Reads and validates every row. Raises `EventsFileError` on the first bad row, on a
    duplicate event name, or on an empty file."""
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    with path.open(encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as exc:
                raise EventsFileError(f"line {lineno}: invalid JSON ({exc.msg})") from exc
            row = _validate_row(raw, lineno)
            name = row["name"].strip()
            if name in seen:
                raise EventsFileError(f"line {lineno}: duplicate event {name!r}")
            seen.add(name)
            rows.append(row)
    if not rows:
        raise EventsFileError(f"{path.name}: no rows")
    return rows


def nth_weekday(year: int, month: int, weekday: int, ordinal: int) -> date | None:
    """The `ordinal`-th `weekday` of that month, or None when the month has no such day (a fifth
    Sunday most years). Returning None rather than the last one keeps "fifth Sunday" honest."""
    first = date(year, month, 1)
    offset = (weekday - first.weekday()) % 7
    day = 1 + offset + (ordinal - 1) * 7
    if day > calendar.monthrange(year, month)[1]:
        return None
    return date(year, month, day)


def _fits(row: dict[str, Any], categories: list[str]) -> bool:
    """A row suits this creator when it is marked ALL, or when one of its `fits` matches one of
    their categories or a calendar category that category maps to (`app/planner/categories.py`:
    "Food & Cooking" -> Food, "tech" -> Technology), ignoring case and extra spaces. A creator
    with no categories, or none the map knows, gets only the ALL rows."""
    wanted = match_keys(categories)
    for entry in row["fits"]:
        value = entry.strip()
        if value == FITS_ALL:
            return True
        if normalise_category(value) in wanted:
            return True
    return False


def _occurrence(row: dict[str, Any], year: int) -> date | None:
    """The row's date in `year`, or None when it has none (a variable row with no verified date,
    or a rule whose ordinal does not exist that year). Seasons have no single date."""
    event_type = row["event_type"]
    if event_type == "fixed":
        month, day = (int(part) for part in row["month_day"].split("-"))
        return date(year, month, day)
    if event_type == "rule":
        parsed = _RULE.match(row["rule"].strip())
        assert parsed is not None  # validated at load
        return nth_weekday(
            year,
            _MONTHS[parsed.group("month").lower()],
            _WEEKDAYS[parsed.group("weekday").lower()],
            _ORDINALS[parsed.group("ordinal").lower()],
        )
    if event_type == "variable":
        value = row["year_dates"].get(str(year))
        if not value:
            return None
        return date.fromisoformat(value.strip())
    return None


def _in_season(row: dict[str, Any], day: date) -> bool:
    window = _WINDOW.match(row["window"].strip())
    assert window is not None  # validated at load
    start_month, start_day = (int(p) for p in window.group(1).split("-"))
    end_month, end_day = (int(p) for p in window.group(2).split("-"))
    start = (start_month, start_day)
    end = (end_month, end_day)
    today = (day.month, day.day)
    if start <= end:
        return start <= today <= end
    # A window that wraps the new year, e.g. winter 11-15 to 02-15.
    return today >= start or today <= end


def events_for_week(
    rows: list[dict[str, Any]],
    start: date,
    categories: list[str],
    days: int = 7,
) -> dict[str, Any]:
    """What falls in `days` days from `start` (inclusive) for a creator in `categories`.

    `start` comes from the server, in Asia/Kolkata — never from the model.

    A dated event occupies the days from `post_before_days` before it up to the day itself, so a
    Diwali row with `post_before_days: 7` shows up a week ahead, which is when the content has to
    be shot. `days_until` is 0 on the day itself. This is the event's whole lead window; the week
    plan (`app/planner/week_plan.py`) then shows each event on ONE of these days only.

    Returns:
        {"days": [{"date", "weekday", "events": [...]}, ...],
         "seasons": [row, ...],
         "missing_verified_dates": [name, ...]}
    """
    window = [start + timedelta(days=offset) for offset in range(days)]
    years = {day.year for day in window}

    per_day: dict[date, list[dict[str, Any]]] = {day: [] for day in window}
    missing: list[str] = []

    for row in rows:
        if not _fits(row, categories):
            continue
        if row["event_type"] == "season":
            continue
        occurrences = [occ for occ in (_occurrence(row, year) for year in years) if occ is not None]
        if row["event_type"] == "variable" and not occurrences:
            # Reported, never guessed: somebody has to fill in this year's date.
            missing.append(row["name"])
            continue
        for occurrence in occurrences:
            lead = timedelta(days=int(row["post_before_days"]))
            for day in window:
                if occurrence - lead <= day <= occurrence:
                    per_day[day].append(
                        {
                            "name": row["name"],
                            "type": row["type"],
                            "date": occurrence.isoformat(),
                            "days_until": (occurrence - day).days,
                            "angles": list(row["angles"]),
                            "sensitivity": row["sensitivity"],
                        }
                    )

    seasons = [
        {
            "name": row["name"],
            "angles": list(row["angles"]),
            "sensitivity": row["sensitivity"],
        }
        for row in rows
        if row["event_type"] == "season" and _fits(row, categories) and _in_season(row, start)
    ]

    return {
        "days": [
            {
                "date": day.isoformat(),
                "weekday": day.strftime("%A"),
                # Nearest first: the day itself before something a week away.
                "events": sorted(per_day[day], key=lambda event: event["days_until"]),
            }
            for day in window
        ],
        "seasons": seasons,
        "missing_verified_dates": sorted(set(missing)),
    }


# Loaded once, at import: a malformed file fails at startup, not mid-chat.
EVENT_ROWS: list[dict[str, Any]] = load_events()
