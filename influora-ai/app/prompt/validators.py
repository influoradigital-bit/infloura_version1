"""Shared prompt-output validators — extracted from `app/routes/trendspark.py`
(Priya R1 ruling, Conflict 7 — `wiki/build/creator-copilot-priya-review-r1.md`).

These five regexes plus `_has_forbidden_petname`/`_statement_count` are
SECURITY CONTROLS (prompt-injection / tone-policy enforcement on model
output), not incidental route logic. Duplicating them across route files
(e.g. copy-pasting into `app/routes/creator_suggestion.py`) was REJECTED as a
permanent state by Priya's ruling: a future petname/price-bypass fix applied
to one copy would silently miss the other. Security controls get ONE source
of truth — this module.

Extract-first discipline (the binding sequencing Priya's ruling requires):
this module is BYTE-FOR-BYTE BEHAVIOR-PRESERVING relative to the regexes/
helpers that used to live inline in `app/routes/trendspark.py` — same
patterns, same semantics, same docstrings. `trendspark.py` now imports these
names from here instead of defining them locally; its full test suite
(`tests/eval/test_trendspark_nudge.py`, `tests/routes/test_trendspark_registration.py`)
must stay green, UNCHANGED, on this refactor alone.

`app/routes/creator_suggestion.py` imports from this module from day one
(see `wiki/build/creator-copilot-ai-route-plan.md` §2.3) instead of
re-embedding a second copy.

Route/tone-specific validators do NOT belong here — e.g. trendspark's
`_OWN_CONTENT_FORBIDDEN_RE` (only meaningful for trendspark's OWN_CONTENT
mode) and the creator route's `_MARKETPLACE_RE` (creator-tone-specific, per
Priya's ruling: "lives in the creator route/prompt, not the shared module")
stay local to their own route/prompt module.
"""

from __future__ import annotations

import re

from app.prompt.trendspark import FORBIDDEN_PETNAMES

# ```json ... ``` / ``` ... ``` fence stripping (defensive parse, schema-lock §4).
_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)
# Unambiguous romantic pet-names -> whole-word, case-insensitive.
_PETNAME_RE = re.compile(
    r"\b(" + "|".join(p for p in FORBIDDEN_PETNAMES if p != "love") + r")\b",
    re.IGNORECASE,
)
# "love" only as a vocative (direct address) so the verb "love this" is NOT flagged.
#
# F-20: `\blove\s*[!.?]` flagged the VERB whenever it happened to sit before
# punctuation — "There is a lot to love!" was rejected as a pet-name, which this
# regex's own docstring says must not happen. A vocative "love" is preceded by a
# comma or opens/closes the address; a verb is preceded by "to/we/i/they/you/
# would/really/just" and friends. Require the vocative shape explicitly.
_LOVE_VOCATIVE_RE = re.compile(
    r",\s*love\b"                                   # "...thanks, love"
    r"|^\s*love\s*[,!.?]"                            # "Love, ..." opening address
    r"|\b(?<!to )(?<!we )(?<!i )(?<!they )(?<!you )"  # not the verb
    r"(?<!really )(?<!just )(?<!would )(?<!will )(?<!ll )"
    r"(?:hey|hi|hello|thanks|thank you|okay|ok|yes|no)\s+love\b",
    re.IGNORECASE,
)
# Echoed price kill-switch: the model is never SENT a price, so any price-shaped
# figure in the output is invented -> reject.
#
# F-20, two rounds. The ORIGINAL pattern was `₹|\brs\.?\b|\binr\b|\brupees?\b` —
# currency WORDS only — so "Grab it for 999 today" and "Only 2k for a reel" both
# sailed through the control whose whole job is to catch an invented price.
#
# The first fix over-corrected: matching any bare 3+ digit magnitude also
# rejected "your Diwali 2026 Reel", "this audio hit 150k uses this week" and
# "1,200 creators used this sound". The model is SHOWN video titles and
# untrusted trend_text and told to mention them naturally, so numeric content is
# expected — and every rejection is a paid Haiku call billed and thrown away,
# which is F-20's own first bullet ("rejects the best outputs") reintroduced
# through a different regex.
#
# What separates a price from a count is not size, it is CONTEXT: a currency
# token beside the number, or a commercial connector ("2k per reel", "999 for a
# post"), or a price verb in front of it ("costs 2500", "grab it for 999"). A
# bare magnitude on its own is a view count, a year, or a follower number, and
# those are the outputs the prompts explicitly ask for.
_CURRENCY = r"(?:₹|rs\.?|inr|rupees?|bucks)"
_UNIT = r"(?:k|l|lakhs?|lacs?|cr|crores?)"
_MAGNITUDE = r"\d[\d,  ]*(?:\.\d+)?\s*" + _UNIT + r"?"
# Nouns that make a number commercial rather than descriptive.
_PRICED_NOUN = r"(?:reels?|posts?|videos?|creators?|collabs?|campaigns?|stor(?:y|ies)|shoots?|months?|days?)"
# Verbs/adverbs that introduce a price.
_PRICE_VERB = r"(?:for|at|costs?|charges?|charging|paying|pay|priced\s+at|only|just|budget\s+of|around)"

_PRICE_RE = re.compile(
    # 1. currency adjacent, either side: "₹15,000", "Rs. 2k", "15,000 rupees"
    rf"{_CURRENCY}\s*{_MAGNITUDE}"
    rf"|\b{_MAGNITUDE}\s*{_CURRENCY}\b"
    # 2. commercial connector: "2k per reel", "1.5L a campaign", "2500/post"
    rf"|\b{_MAGNITUDE}\s*(?:/|per\b|a\b|an\b|each\b|for\s+(?:a|an|one|each)\b)\s*{_PRICED_NOUN}"
    # 3. price verb in front: "costs 2500", "grab it for 999", "only 2k"
    rf"|\b{_PRICE_VERB}\s+{_MAGNITUDE}\b",
    re.IGNORECASE,
)

# A bare 4-digit number in 1900-2099 with no currency and no unit is a YEAR, not
# a price ("your Diwali 2026 Reel"). Checked after the fact so the regex above
# stays readable.
_YEAR_ONLY_RE = re.compile(r"^(?:19|20)\d{2}$")


def has_invented_price(message: str) -> bool:
    """True when `message` quotes a price the model was never given.

    Use this rather than `_PRICE_RE.search` directly — it applies the year
    guard, which the regex deliberately does not encode.
    """
    for match in _PRICE_RE.finditer(message or ""):
        digits = re.search(r"\d[\d,  ]*(?:\.\d+)?", match.group(0))
        if digits is None:
            continue
        bare = re.sub(r"[,  ]", "", digits.group(0))
        has_currency = re.search(_CURRENCY, match.group(0), re.IGNORECASE) is not None
        has_unit = re.search(_UNIT + r"\b", match.group(0), re.IGNORECASE) is not None
        if _YEAR_ONLY_RE.match(bare) and not has_currency and not has_unit:
            continue
        return True
    return False


# "<=2 sentences" cap (tone-guide §2).
#
# F-20: this counted EVERY `.`/`!`, so decimals and abbreviations inflated the
# count — "This sound is up 2.5x this week. Three creators used it. Want a
# peek?" counted as 3 and was rejected. The prompts explicitly ask for real
# numbers, so the old rule rejected the HIGHEST-QUALITY outputs. A sentence
# terminator is a `.`/`!` that is not between two digits and not part of a
# common abbreviation.
_ABBREVIATIONS = ("e.g.", "i.e.", "etc.", "vs.", "mr.", "ms.", "mrs.", "dr.", "no.")
_STATEMENT_RE = re.compile(r"(?<!\d)[.!]+(?!\d)")


def _has_forbidden_petname(message: str) -> bool:
    return bool(_PETNAME_RE.search(message) or _LOVE_VOCATIVE_RE.search(message))


def _statement_count(message: str) -> int:
    """Number of '.'/'!'-terminated statements (question CTAs excluded).

    F-20: abbreviations are removed before counting, and `_STATEMENT_RE` ignores
    a dot between two digits, so "up 2.5x this week." is one statement rather
    than two.
    """
    text = message
    for abbreviation in _ABBREVIATIONS:
        text = re.sub(re.escape(abbreviation), abbreviation.replace(".", ""), text, flags=re.IGNORECASE)
    return len(_STATEMENT_RE.findall(text))
