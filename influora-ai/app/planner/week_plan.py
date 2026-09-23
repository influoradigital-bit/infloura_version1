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
"""

from __future__ import annotations

import logging
from datetime import date
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
    calendar = events_for_week(
        rows if rows is not None else EVENT_ROWS,
        start=today,
        categories=categories,
        days=len(days),
    )
    by_date = {entry["date"]: entry for entry in calendar["days"]}

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
        enriched_days.append({**day, "events": list(match["events"]) if match else []})

    return {**data, "days": enriched_days, "seasons": list(calendar["seasons"])}
