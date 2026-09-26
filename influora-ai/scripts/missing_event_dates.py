"""Which festivals have no date on file for the weeks ahead.

    python scripts/missing_event_dates.py            # next 60 days
    python scripts/missing_event_dates.py --days 120

A festival with no verified date for the year is skipped by `plan_my_week`, so the creator simply
never hears about it. That is the right behaviour (a wrong Diwali date is worse than no calendar)
but it is silent, which is why this exists: run it, see what is missing, look the dates up and add
them as app/planner/EVENT-DATES.md describes.

Exits 1 when something is missing, so it can be wired into a reminder later. It is NOT a test: a
missing date is a job for a person, not a broken build.
"""

from __future__ import annotations

import argparse
import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.planner.events import EVENT_ROWS, events_for_week  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--days", type=int, default=60, help="how far ahead to look (default 60)")
    parser.add_argument("--from", dest="start", default=None, help="start date, YYYY-MM-DD")
    args = parser.parse_args()

    start = date.fromisoformat(args.start) if args.start else date.today()
    # Every category, so nothing is hidden just because one creator is in another niche.
    categories = sorted({fit for row in EVENT_ROWS for fit in row["fits"] if fit != "ALL"})
    calendar = events_for_week(EVENT_ROWS, start=start, categories=categories, days=args.days)

    missing = sorted(set(calendar["missing_verified_dates"]))
    end = start + timedelta(days=args.days - 1)
    years = sorted({start.year, end.year})
    print(f"Window: {start} to {end} ({args.days} days)")
    if not missing:
        print("Every festival has a date on file for this window.")
        return 0

    print(
        f"{len(missing)} festival(s) have no date on file for "
        f"{' or '.join(str(year) for year in years)}:"
    )
    for name in missing:
        print(f"  - {name}")
    print()
    print("This check is per YEAR, not per day: a festival whose date has already passed this")
    print("year keeps appearing until next year's date is added. Fill the ones that fall inside")
    print("the window first.")
    print("Add them as app/planner/EVENT-DATES.md describes: the source first, then the date.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
