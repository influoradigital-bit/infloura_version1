"""One mapping from a creator's category to the festival calendar's categories.

Why this file exists (audit 2026-09-24, lane B1): onboarding saves a creator's categories from
`CONTENT_VERTICALS` in `src/pages/creator-onboarding.tsx` ("Food & Cooking", "Tech & Gaming", ...)
and some profiles hold free text ("tech", "gadgets"). The calendar's `fits` use their own fixed
words ("Food", "Technology", "Local business", ...), and the match was exact, so an onboarded
creator only ever got the `ALL` rows and none of the category days the calendar exists for.

The rule:

- a category is normalised first: trimmed, casefolded, inner whitespace collapsed to one space;
- a category that already IS a calendar category maps to itself;
- otherwise `CATEGORY_MAP` below decides, by the normalised key;
- anything else maps to nothing, and the creator still gets the `ALL` rows.

The SAME table lives in Java (`com.influora.service.creatorcopilot.CreatorCategoryMap`), which
`ContentTopicService` uses to match the admin's topics. `tests/planner/test_categories.py` reads
that Java source and fails if the two tables differ, so they cannot drift apart.
"""

from __future__ import annotations

from collections.abc import Iterable

# Every category the calendar's `fits` use, apart from the ALL marker.
CALENDAR_CATEGORIES: tuple[str, ...] = (
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
)

# Creator category (as onboarding or the creator writes it) -> calendar categories.
CATEGORY_MAP: dict[str, tuple[str, ...]] = {
    # The onboarding verticals (CONTENT_VERTICALS in src/pages/creator-onboarding.tsx).
    "Fashion & Lifestyle": ("Beauty", "Culture"),
    "Beauty & Skincare": ("Beauty",),
    "Fitness & Health": ("Fitness",),
    "Food & Cooking": ("Food",),
    "Tech & Gaming": ("Technology",),
    "Travel & Adventure": ("Travel",),
    "Education & Learning": ("Education",),
    "Finance & Business": ("Finance", "Local business"),
    "Entertainment & Comedy": ("Culture",),
    "Parenting & Family": ("Education", "Food"),
    "Art & Photography": ("DIY or crafts", "Culture"),
    "Music & Dance": ("Culture",),
    # Free-text aliases seen on real profiles.
    "tech": ("Technology",),
    "technology": ("Technology",),
    "gadgets": ("Technology",),
    "food": ("Food",),
    "cooking": ("Food",),
    "fitness": ("Fitness",),
    "health": ("Fitness",),
    "gym": ("Fitness",),
    "beauty": ("Beauty",),
    "skincare": ("Beauty",),
    "makeup": ("Beauty",),
    "travel": ("Travel",),
    "finance": ("Finance",),
    "money": ("Finance",),
    "education": ("Education",),
    "study": ("Education",),
    "art": ("DIY or crafts",),
    "craft": ("DIY or crafts",),
    "crafts": ("DIY or crafts",),
    "diy": ("DIY or crafts",),
}


def normalise_category(value: str) -> str:
    """Trimmed, casefolded, inner whitespace collapsed: "  Food   &  Cooking " -> "food & cooking"."""
    return " ".join(value.split()).casefold()


_CALENDAR_BY_KEY: dict[str, str] = {normalise_category(c): c for c in CALENDAR_CATEGORIES}
_MAP_BY_KEY: dict[str, tuple[str, ...]] = {
    normalise_category(key): value for key, value in CATEGORY_MAP.items()
}


def calendar_categories_for(category: str) -> tuple[str, ...]:
    """The calendar categories one creator category stands for; empty when it maps to none."""
    if not isinstance(category, str):
        return ()
    key = normalise_category(category)
    if not key:
        return ()
    if key in _CALENDAR_BY_KEY:
        return (_CALENDAR_BY_KEY[key],)
    return _MAP_BY_KEY.get(key, ())


def match_keys(categories: Iterable[str]) -> set[str]:
    """Every normalised name a calendar row or topic may carry to match these categories: each raw
    category itself plus every calendar category it maps to."""
    keys: set[str] = set()
    for category in categories:
        if not isinstance(category, str):
            continue
        raw = normalise_category(category)
        if not raw:
            continue
        keys.add(raw)
        keys.update(normalise_category(mapped) for mapped in calendar_categories_for(category))
    return keys
