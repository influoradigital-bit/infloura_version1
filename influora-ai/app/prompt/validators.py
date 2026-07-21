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
_LOVE_VOCATIVE_RE = re.compile(r",\s*love\b|\blove\s*[!.?]", re.IGNORECASE)
# Echoed price kill-switch: rupee symbol, or rs/inr/rupee keywords. The model is
# never SENT a price, so any price token in the output is invented -> reject.
_PRICE_RE = re.compile(r"₹|\brs\.?\b|\binr\b|\brupees?\b", re.IGNORECASE)
# "<=2 sentences" cap (tone-guide §2). Count statements terminated by . or ! (runs
# collapsed); a trailing/soft question CTA ("Want a peek?") is NOT a statement, so
# the canonical GOOD nudges (2 statements + 1 question tag) pass while urgency spam
# ("ACT NOW! ... BUY ...! LIMITED TIME!" -> 3-4 statements) is rejected.
_STATEMENT_RE = re.compile(r"[.!]+")


def _has_forbidden_petname(message: str) -> bool:
    return bool(_PETNAME_RE.search(message) or _LOVE_VOCATIVE_RE.search(message))


def _statement_count(message: str) -> int:
    """Number of '.'/'!'-terminated statements (question CTAs excluded)."""
    return len(_STATEMENT_RE.findall(message))
