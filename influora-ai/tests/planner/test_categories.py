"""Creator categories reach the festival calendar (audit 2026-09-24, lane B1).

Onboarding saves "Food & Cooking", "Tech & Gaming" and the rest; the calendar's `fits` say "Food",
"Technology". The match used to be exact, so every onboarded creator got only the ALL rows.

Three things this file defends, none of them against a retyped fixture:

- the onboarding verticals are READ from `src/pages/creator-onboarding.tsx`, and every one of them
  must reach at least one calendar category that has events, end to end through `events_for_week`;
- the Python table and the Java table (`CreatorCategoryMap.java`, used by `ContentTopicService`
  for the admin's topics) are compared entry by entry from the Java SOURCE, so they cannot drift;
- every festival `fits` word is a calendar category, and every calendar category no festival uses
  is one of the topic-only words;
- the Java copy of the onboarding verticals (`CreatorCategoryMap.ONBOARDING_VERTICALS`) is the
  onboarding page's list.
"""

from __future__ import annotations

import re
from datetime import date, timedelta
from pathlib import Path

import pytest

from app.planner.categories import (
    CALENDAR_CATEGORIES,
    CATEGORY_MAP,
    calendar_categories_for,
    match_keys,
    normalise_category,
)
from app.planner.events import EVENT_ROWS, FITS_ALL, events_for_week

REPO = Path(__file__).resolve().parents[3]
ONBOARDING_TSX = REPO / "src/pages/creator-onboarding.tsx"
JAVA_MAP = (
    REPO
    / "influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorCategoryMap.java"
)


def _onboarding_verticals() -> list[str]:
    """CONTENT_VERTICALS exactly as the onboarding page declares it."""
    source = ONBOARDING_TSX.read_text(encoding="utf-8")
    block = re.search(r"const CONTENT_VERTICALS\s*=\s*\[(.*?)\];", source, re.DOTALL)
    assert block, "CONTENT_VERTICALS not found in creator-onboarding.tsx"
    return re.findall(r"'([^']+)'", block.group(1))


def _java_table() -> dict[str, tuple[str, ...]]:
    source = JAVA_MAP.read_text(encoding="utf-8")
    start = source.index("TABLE =")
    end = source.index(";", start)
    block = source[start:end]
    entries = re.findall(r'Map\.entry\(\s*"([^"]+)",\s*List\.of\(([^)]*)\)\s*\)', block)
    assert len(entries) == block.count("Map.entry("), "a Java TABLE entry is not on the parsed shape"
    return {key: tuple(re.findall(r'"([^"]+)"', values)) for key, values in entries}


def _java_calendar_categories() -> tuple[str, ...]:
    source = JAVA_MAP.read_text(encoding="utf-8")
    start = source.index("CALENDAR_CATEGORIES =")
    end = source.index(";", start)
    return tuple(re.findall(r'"([^"]+)"', source[start:end]))


def _java_onboarding_verticals() -> list[str]:
    source = JAVA_MAP.read_text(encoding="utf-8")
    start = source.index("ONBOARDING_VERTICALS =")
    end = source.index(";", start)
    return re.findall(r'"([^"]+)"', source[start:end])


def _category_names_in_a_year(categories: list[str]) -> set[str]:
    """Every non-ALL event a creator in `categories` is shown across a whole year."""
    fits_all = {row["name"] for row in EVENT_ROWS if FITS_ALL in row["fits"]}
    names: set[str] = set()
    start = date(2027, 1, 1)
    for week in range(53):
        plan = events_for_week(EVENT_ROWS, start + timedelta(days=7 * week), categories, days=7)
        names |= {event["name"] for day in plan["days"] for event in day["events"]}
    return names - fits_all


# --- the onboarding verticals, read from the page ---------------------------------


def test_the_onboarding_list_is_read_not_retyped():
    verticals = _onboarding_verticals()
    assert len(verticals) >= 12, verticals
    assert "Food & Cooking" in verticals


@pytest.mark.parametrize("vertical", _onboarding_verticals())
def test_every_onboarding_vertical_maps_to_a_calendar_category_with_events(vertical):
    mapped = calendar_categories_for(vertical)
    assert mapped, f"{vertical!r} maps to no calendar category"
    used = {entry for row in EVENT_ROWS for entry in row["fits"]}
    assert any(category in used for category in mapped), (vertical, mapped)
    # End to end: over a year, the calendar shows this creator at least one category day.
    assert _category_names_in_a_year([vertical]), f"{vertical!r} only ever gets the ALL rows"


def test_an_onboarded_food_creator_gets_world_food_day():
    """The audit's own case: the week of 2026-10-12 showed a "Food & Cooking" creator nothing."""
    plan = events_for_week(EVENT_ROWS, date(2026, 10, 12), ["Food & Cooking"], days=7)
    names = {event["name"] for day in plan["days"] for event in day["events"]}
    assert "World Food Day" in names, names


def test_free_text_tech_gets_the_technology_day():
    """seams.md M-1: ["tech", "gadgets"] used to be identical to a creator with no categories."""
    assert "National Technology Day (India)" in _category_names_in_a_year(["tech", "gadgets"])


# --- the mapping rule -------------------------------------------------------------


def test_normalisation_trims_casefolds_and_collapses_inner_whitespace():
    assert normalise_category("  Food   &\tCooking ") == "food & cooking"
    assert calendar_categories_for("  LOCAL   business ") == ("Local business",)
    assert calendar_categories_for("fOOD  &  cooking") == ("Food",)


def test_a_calendar_category_maps_to_itself():
    for category in CALENDAR_CATEGORIES:
        assert calendar_categories_for(category) == (category,)
        assert calendar_categories_for(category.upper()) == (category,)


# The six calendar categories added 2026-09-25 for the admin's topics; no festival uses them yet.
TOPIC_ONLY_CATEGORIES = ("Fashion", "Lifestyle", "Entertainment", "Gaming", "Music", "Parenting")


def test_the_calendar_categories_are_exactly_the_ruled_ones_in_order():
    assert CALENDAR_CATEGORIES == (
        "Food",
        "Fitness",
        "Beauty",
        "Finance",
        "Education",
        "Gardening",
        "Travel",
        "Local business",
        "DIY or crafts",
        "Technology",
        "Culture",
        *TOPIC_ONLY_CATEGORIES,
    )


def test_the_table_is_exactly_the_ruled_one():
    assert CATEGORY_MAP["Fashion & Lifestyle"] == ("Beauty", "Culture", "Fashion", "Lifestyle")
    assert CATEGORY_MAP["Tech & Gaming"] == ("Technology", "Gaming")
    assert CATEGORY_MAP["Entertainment & Comedy"] == ("Culture", "Entertainment")
    assert CATEGORY_MAP["Parenting & Family"] == ("Education", "Food", "Parenting")
    assert CATEGORY_MAP["Music & Dance"] == ("Culture", "Music", "Entertainment")
    assert CATEGORY_MAP["Finance & Business"] == ("Finance", "Local business")
    assert CATEGORY_MAP["Art & Photography"] == ("DIY or crafts", "Culture")
    for alias in ("tech", "technology", "gadgets"):
        assert calendar_categories_for(alias) == ("Technology",)
    for alias in ("art", "craft", "crafts", "diy"):
        assert calendar_categories_for(alias) == ("DIY or crafts",)
    assert calendar_categories_for("knitting") == ()
    assert calendar_categories_for("") == ()


@pytest.mark.parametrize(
    ("word", "expected"),
    [
        ("fashion", "Fashion"),
        ("lifestyle", "Lifestyle"),
        ("shopping", "Lifestyle"),
        ("entertainment", "Entertainment"),
        ("comedy", "Entertainment"),
        ("gaming", "Gaming"),
        ("games", "Gaming"),
        ("music", "Music"),
        ("dance", "Music"),
        ("parenting", "Parenting"),
        ("family", "Parenting"),
    ],
)
def test_the_new_free_text_words_resolve(word, expected):
    assert calendar_categories_for(word) == (expected,)
    assert calendar_categories_for(f"  {word.upper()} ") == (expected,)


def test_the_new_calendar_words_have_no_alias_entry():
    """A word that IS a calendar category maps to itself, so the six new ones need no entry (the
    older ones such as "food" keep theirs; the ruling kept every existing alias)."""
    new_keys = {normalise_category(c) for c in TOPIC_ONLY_CATEGORIES}
    assert not new_keys & {normalise_category(k) for k in CATEGORY_MAP}


def test_match_keys_keep_the_raw_category_as_well():
    assert match_keys(["Tech & Gaming"]) == {"tech & gaming", "technology", "gaming"}
    assert match_keys([None, "  ", 7]) == set()  # type: ignore[list-item]


def test_every_mapped_category_is_a_real_calendar_category():
    for key, mapped in CATEGORY_MAP.items():
        assert mapped, key
        assert set(mapped) <= set(CALENDAR_CATEGORIES), key


def test_festival_words_are_calendar_categories_and_the_rest_are_topic_only_words():
    """Every festival `fits` word is a calendar category (a typo there would match nobody), and
    every calendar category a festival does not use is one of the six topic-only words."""
    used = {entry for row in EVENT_ROWS for entry in row["fits"]} - {FITS_ALL}
    assert used <= set(CALENDAR_CATEGORIES), used - set(CALENDAR_CATEGORIES)
    assert set(CALENDAR_CATEGORIES) - used <= set(TOPIC_ONLY_CATEGORIES)


def test_the_topic_only_categories_leave_the_festival_calendar_unchanged():
    """The widened verticals add names no festival uses, so a year of festivals is the same as
    with the old table's categories alone."""
    old = {
        "Fashion & Lifestyle": ["Beauty", "Culture"],
        "Tech & Gaming": ["Technology"],
        "Entertainment & Comedy": ["Culture"],
        "Parenting & Family": ["Education", "Food"],
        "Music & Dance": ["Culture"],
    }
    for vertical, old_categories in old.items():
        assert _category_names_in_a_year([vertical]) == _category_names_in_a_year(old_categories)


# --- Python and Java cannot drift -------------------------------------------------


def test_the_java_table_is_the_python_table():
    java = _java_table()
    assert len(java) >= 12, "the Java table parse found too few entries"
    assert java == CATEGORY_MAP


def test_the_java_calendar_categories_are_the_python_ones():
    assert _java_calendar_categories() == CALENDAR_CATEGORIES


def test_the_java_onboarding_verticals_are_the_onboarding_page_ones():
    """Java's preview uses its own copy of CONTENT_VERTICALS to find calendar words no onboarding
    option reaches, so that copy must be the page's list, in order."""
    assert _java_onboarding_verticals() == _onboarding_verticals()
