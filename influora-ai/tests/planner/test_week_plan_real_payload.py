"""The calendar filters on the creator's categories — so the payload Spring actually sends has to
carry them.

WHY THIS FILE EXISTS. Every case in `test_week_plan.py` builds its plan by hand and includes
`categories`, so all of them passed while the real `PlanMyWeekResult` carried no such field: 50 of
the 59 calendar rows are category-specific, and in production only the 9 `ALL` rows could ever
attach. A hand-made fixture cannot catch that; this file builds the plan from the FIELD NAMES the
Java record declares, read from the source, so the two can never drift apart again.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from app.planner.week_plan import enrich_week_plan

DTO = (
    Path(__file__).resolve().parents[3]
    / "influora-api/src/main/java/com/influora/web/dto/meera/CreatorToolDtos.java"
)


def _plan_my_week_json_fields() -> list[str]:
    """The @JsonProperty names of PlanMyWeekResult, straight from the Java source."""
    source = DTO.read_text(encoding="utf-8")
    start = source.index("public record PlanMyWeekResult(")
    end = source.index(") {}", start)
    return re.findall(r'@JsonProperty\("([^"]+)"\)', source[start:end])


def _spring_payload(categories: list[str] | None = None) -> dict:
    """A plan shaped exactly like the Java record, filled with the minimum each field needs."""
    fields = _plan_my_week_json_fields()
    days = [{"date": f"2026-09-{day}", "weekday": "Wednesday"} for day in range(23, 30)]
    values = {
        "today": "2026-09-23",
        "days": days,
        "categories": categories if categories is not None else [],
        "topics": [],
        "pattern": {
            "enough_data": False,
            "posts_counted": 0,
            "best_post_type": None,
            "windows": [],
            "note": "Not enough posts yet.",
        },
    }
    missing = [f for f in fields if f not in values]
    assert not missing, f"PlanMyWeekResult gained a field this test does not model: {missing}"
    return {field: values[field] for field in fields}


def test_the_java_record_carries_categories():
    """Without this field the calendar sees an empty list and only the 9 ALL rows can match."""
    assert "categories" in _plan_my_week_json_fields()


def test_a_food_creator_gets_their_category_day_from_the_real_payload():
    enriched = enrich_week_plan(_spring_payload(["Food"]))
    names = {event["name"] for day in enriched["days"] for event in day["events"]}
    assert "World Heart Day" in names, names  # fits Fitness and Food


def test_a_finance_creator_does_not_get_the_food_day():
    enriched = enrich_week_plan(_spring_payload(["Personal finance"]))
    names = {event["name"] for day in enriched["days"] for event in day["events"]}
    assert "World Heart Day" not in names, names


@pytest.mark.parametrize("categories", [[], None])
def test_no_categories_still_yields_a_plan_and_the_everyone_rows(categories):
    enriched = enrich_week_plan(_spring_payload(categories))
    assert len(enriched["days"]) == 7
    assert "seasons" in enriched
