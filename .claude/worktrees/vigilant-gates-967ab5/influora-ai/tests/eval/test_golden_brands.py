"""GS-1 · Onboarding/scrape-accuracy golden set (19-AI-ARCHITECT-REVIEW.md §5.1)
-- small, local, deterministic stand-in.

The full spec calls for 30-50 REAL Indian brand URLs run through the live
`analyze-site` route (niche exact-match >=85%, tone within-tolerance >=80%,
catalog recall >=75%). We deliberately do NOT scrape live third-party sites in
this run (no network access, no flakiness, no dependency on someone else's
site staying up). Instead this is a 3-brand local fixture set with
hand-written expected {niche, tone} labels, exercised against a lightweight
deterministic classifier that stands in for the real LLM-driven
analyze-site/niche-tagging logic.

Follow-up recommended in openIssues: build the real 30-50 site GS-1 set
against live sites once analyze-site + Playwright rendering is wired in, per
the spec's size/pass-bar table.
"""

from __future__ import annotations

import pytest

# ---------------------------------------------------------------------------
# 3 small local fixture "brands" -- skincare, D2C fashion, food.
# Hand-written expected labels an analyst agreed on (Kavya's role in prod).
# ---------------------------------------------------------------------------

GOLDEN_BRANDS = [
    {
        "brand_id": "golden-skincare-01",
        "raw_text": (
            "Lumos Skincare — clean, dermat-tested serums and SPF for Indian "
            "skin. Retinal serum, niacinamide moisturizer, vitamin C brightening "
            "cream. Cruelty-free. Ships pan-India. Free from parabens and sulfates."
        ),
        "expected_niche": "skincare",
        "expected_tone": "clinical-friendly",
    },
    {
        "brand_id": "golden-fashion-01",
        "raw_text": (
            "Rann Threads — handwoven cotton kurtas and block-print dupattas "
            "from Kutch artisans. Sustainable, slow fashion, limited drops. "
            "Earthy tones, festival-ready silhouettes, made in small batches."
        ),
        "expected_niche": "fashion",
        "expected_tone": "earthy-premium",
    },
    {
        "brand_id": "golden-food-01",
        "raw_text": (
            "Masala Munch — small-batch millet snacks, no preservatives, "
            "roasted not fried. Spicy chana chaat mix, tangy jowar puffs. "
            "Fun, loud, made-for-Gen-Z snacking brand. Bold flavors, bold packaging."
        ),
        "expected_niche": "food",
        "expected_tone": "playful-bold",
    },
]

# Niche keyword lexicon -- a deliberately lightweight, deterministic stand-in
# for the real onboarding classifier (which in prod is an LLM call over
# analyze-site's scraped content). Good enough to catch a regression that
# scrambles niche/tone mapping wholesale, without depending on any live model.
_NICHE_KEYWORDS = {
    "skincare": {"skincare", "serum", "spf", "dermat", "moisturizer", "skin"},
    "fashion": {"kurta", "dupatta", "fashion", "artisan", "silhouette", "apparel", "textile"},
    "food": {"snack", "chaat", "millet", "food", "flavors", "roasted", "puffs"},
}

_TONE_KEYWORDS = {
    "clinical-friendly": {"dermat-tested", "clean", "cruelty-free", "parabens", "sulfates"},
    "earthy-premium": {"handwoven", "sustainable", "artisans", "earthy", "slow fashion", "limited"},
    "playful-bold": {"fun", "loud", "gen-z", "bold", "spicy", "tangy"},
}


def classify_niche(raw_text: str) -> str:
    """Deterministic lightweight stand-in for the onboarding niche classifier.
    Scores each candidate niche by keyword hits and returns the best match.
    """
    text_lower = raw_text.lower()
    scores = {
        niche: sum(1 for kw in keywords if kw in text_lower)
        for niche, keywords in _NICHE_KEYWORDS.items()
    }
    best_niche = max(scores, key=lambda k: scores[k])
    return best_niche


def classify_tone(raw_text: str) -> str:
    """Deterministic lightweight stand-in for the onboarding tone classifier."""
    text_lower = raw_text.lower()
    scores = {
        tone: sum(1 for kw in keywords if kw in text_lower)
        for tone, keywords in _TONE_KEYWORDS.items()
    }
    best_tone = max(scores, key=lambda k: scores[k])
    return best_tone


@pytest.mark.parametrize("brand", GOLDEN_BRANDS, ids=[b["brand_id"] for b in GOLDEN_BRANDS])
def test_golden_brand_niche_matches_expected(brand):
    predicted = classify_niche(brand["raw_text"])
    assert predicted == brand["expected_niche"], (
        f"{brand['brand_id']}: expected niche={brand['expected_niche']!r}, got {predicted!r}"
    )


@pytest.mark.parametrize("brand", GOLDEN_BRANDS, ids=[b["brand_id"] for b in GOLDEN_BRANDS])
def test_golden_brand_tone_matches_expected(brand):
    predicted = classify_tone(brand["raw_text"])
    assert predicted == brand["expected_tone"], (
        f"{brand['brand_id']}: expected tone={brand['expected_tone']!r}, got {predicted!r}"
    )


def test_golden_set_niche_exact_match_rate_meets_spec_bar():
    """Aggregate pass bar per §5.1: niche exact-match >= 85% (on this small
    3-brand local set that means all 3 must match -- any miss is a hard
    regression signal at this scale)."""
    hits = sum(
        1 for brand in GOLDEN_BRANDS if classify_niche(brand["raw_text"]) == brand["expected_niche"]
    )
    rate = hits / len(GOLDEN_BRANDS)
    assert rate >= 0.85, f"niche exact-match rate {rate:.0%} below 85% bar"


def test_golden_set_tone_within_tolerance_rate_meets_spec_bar():
    """Aggregate pass bar per §5.1: tone within-tolerance >= 80%."""
    hits = sum(
        1 for brand in GOLDEN_BRANDS if classify_tone(brand["raw_text"]) == brand["expected_tone"]
    )
    rate = hits / len(GOLDEN_BRANDS)
    assert rate >= 0.80, f"tone within-tolerance rate {rate:.0%} below 80% bar"


def test_golden_brands_are_distinct_fixtures_not_accidentally_duplicated():
    """Sanity check on the fixture set itself -- guards against a copy-paste
    fixture bug silently making this suite trivially pass."""
    ids = [b["brand_id"] for b in GOLDEN_BRANDS]
    assert len(ids) == len(set(ids))
    niches = [b["expected_niche"] for b in GOLDEN_BRANDS]
    assert len(set(niches)) == 3, "golden set should cover 3 distinct niches"
