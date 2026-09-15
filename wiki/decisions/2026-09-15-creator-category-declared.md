# Ruling: creator category is declared, never derived

> **Decision by:** Swapnil Maruti (CEO) — final authority on business direction
> **Advised by:** Priya (CTO)
> **Date:** 2026-09-15
> **Status:** LOCKED
> **Unblocks:** Task 0.3 (`wiki/processes/task-creator-profile-and-copilot.md`), therefore all of
> Phase 4

---

## The question

The Creator AI Co-pilot's news-matching pitch is "trends matched to a creator's category." Two
ways to get that category:

- **Derived** — infer it from a creator's captions, the way `themeTagsJson` is inferred today.
- **Declared** — a real field a creator sets, at onboarding or in settings.

## Decision: declared. LOCKED.

A creator selects their category explicitly. Stored as a real column on `CreatorProfile`
(alongside `categoriesJson`, which already exists but is a free-text list, not a controlled
single category — Task 4.1 defines the exact schema).

**Why:**

Deriving category from captions rebuilds the exact trap that made the theme-matching layer fail
non-English creators (**F-0783**): `ThemeMatchService.themesForText` is ASCII substring
containment against a Latin-script vocabulary
(`influora-api/src/main/java/com/influora/service/trendspark/ThemeMatchService.java:98-112`). A
creator who writes Devanagari or Tamil captions produces zero theme matches today and would
produce zero *derived category* matches for the same reason — we would have rebuilt the same
failure with extra steps and called it a fix.

A declared field sidesteps language entirely. A fitness creator ticks "Fitness & Wellness"
whether they caption in Hindi, Hinglish, Tamil, or English.

This was Priya's original recommendation (`wiki/processes/task-creator-profile-and-copilot.md`
Task 0.3) and is consistent with the CEO's own framing of the problem: *"if we derive category
from captions we rebuild the English-only trap with extra steps."*

## Consequence

Task 4.1 (creator category field) and everything downstream of it in Phase 4 — per-category trend
sourcing (Task 4.2), vocabulary extension (Task 4.3) — proceeds on a declared field. Category is
never inferred from `themeTagsJson`, `MediaMetric.caption`, or any AI classification of a
creator's content.

**Not decided here:** the exact list of selectable categories, whether a creator can select more
than one, and whether category is mandatory at onboarding or optional-then-nudged. Those are
Task 4.1 implementation details for Vikram/Ananya.
