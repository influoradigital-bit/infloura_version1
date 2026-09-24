"""Fold the festival calendar into the `plan_my_week` tool result.

Spring builds the plan's skeleton: today's date and the next seven dated days (Asia/Kolkata), the
creator's categories, today's editorial topics and the posting pattern from their own posts. The
festival and special-days calendar lives in this repo, not the database
(`app/planner/events.jsonl`), so it is attached here, keyed on the dates Spring already sent.

**This module never decides a date.** It reads `today` and each day's `date` from the tool result
and does arithmetic on those. If the result carries no usable date, the plan is passed through
untouched rather than enriched against a guess — the whole reason `plan_my_week` returns dates at
all is that the model has no way to know them (Ash, `wiki/ai-review/daily-topics-week-plan-ai-
review.md`, P0-1).

A variable-date festival with no verified date for the year is never attached; it is logged so the
gap is visible, and the creator simply gets an evergreen idea for that day instead.

**Each event sits on ONE day** (audit 2026-09-24, lane B2). `events_for_week` says which days an
event is in view (its whole lead window), and the persona builds a day with an event around it,
so attaching it to every one of those days made the plan repeat World Tourism Day four days
running and left no rest day. Here each event goes on its recommended post day only: its date
minus `post_before_days`, clamped to the first day of the window when that falls before it, and
never after the event itself. `days_until` counts from that day and `post_by` is the event's own
date, so the model knows both when to post and when the day is.
"""

from __future__ import annotations

import logging
from datetime import date, timedelta
from typing import Any

from app.planner.events import EVENT_ROWS, events_for_week

logger = logging.getLogger(__name__)


def _parse_date(value: Any) -> date | None:
    if not isinstance(value, str):
        return None
    try:
        return date.fromisoformat(value.strip())
    except ValueError:
        return None


def _place_each_event_once(
    calendar_days: list[dict[str, Any]], rows: list[dict[str, Any]]
) -> dict[str, list[dict[str, Any]]]:
    """date -> the events to post that day, each event on exactly one day of the window.

    The day is the event's date minus its `post_before_days`, clamped into the window: a post day
    before the window's first day becomes that first day, and it is never after the event date.
    """
    placed: dict[str, list[dict[str, Any]]] = {entry["date"]: [] for entry in calendar_days}
    if not calendar_days:
        return placed
    window_start = date.fromisoformat(calendar_days[0]["date"])
    lead_by_name = {row["name"]: int(row["post_before_days"]) for row in rows}
    seen: set[tuple[str, str]] = set()
    for entry in calendar_days:
        for event in entry["events"]:
            key = (event["name"], event["date"])
            if key in seen:
                continue
            seen.add(key)
            occurrence = date.fromisoformat(event["date"])
            lead = timedelta(days=lead_by_name.get(event["name"], 0))
            # Always inside the window: events_for_week only lists an event whose lead window
            # (occurrence - lead .. occurrence) overlaps it, so this day is at or after
            # window_start and at or before a day on which the event was listed.
            post_day = min(max(occurrence - lead, window_start), occurrence)
            placed[post_day.isoformat()].append(
                {
                    **event,
                    "days_until": (occurrence - post_day).days,
                    "post_by": event["date"],
                }
            )
    for events in placed.values():
        events.sort(key=lambda event: event["days_until"])
    return placed


def enrich_week_plan(data: Any, rows: list[dict[str, Any]] | None = None) -> Any:
    """Return a NEW plan dict with `events` on each day plus a `seasons` list.

    Never mutates `data` (the browser card renders the original object). Anything unexpected —
    a non-dict payload, a missing or unparseable `today`, `days` that is not a list — is returned
    unchanged: an error shape must survive this function, and a plan with no date must not be
    enriched against a guessed one.
    """
    if not isinstance(data, dict):
        return data

    today = _parse_date(data.get("today"))
    days = data.get("days")
    if today is None or not isinstance(days, list) or not days:
        logger.warning(
            "week_plan: plan_my_week result carried no usable date (today=%r, days=%s) --"
            " passing it through without the calendar",
            data.get("today"),
            type(days).__name__,
        )
        return data

    categories = [c for c in (data.get("categories") or []) if isinstance(c, str)]
    calendar_rows = rows if rows is not None else EVENT_ROWS
    calendar = events_for_week(
        calendar_rows,
        start=today,
        categories=categories,
        days=len(days),
    )
    by_date = _place_each_event_once(calendar["days"], calendar_rows)

    if calendar["missing_verified_dates"]:
        # Not shown to the model and not guessed: somebody has to fill in this year's date.
        logger.warning(
            "week_plan: %d festival(s) have no verified date for this window: %s",
            len(calendar["missing_verified_dates"]),
            ", ".join(calendar["missing_verified_dates"]),
        )

    enriched_days: list[Any] = []
    for day in days:
        if not isinstance(day, dict):
            # Not a shape Spring sends; leave it exactly as it arrived.
            enriched_days.append(day)
            continue
        # Spring's own date and weekday stay authoritative; only `events` is added.
        match = by_date.get(str(day.get("date", "")).strip())
        enriched_days.append({**day, "events": list(match) if match else []})

    return {**data, "days": enriched_days, "seasons": list(calendar["seasons"])}
