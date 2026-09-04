"""Gate fix round 1 (Priya Q8) — Python<->Java drift check for the CREATOR
context payload.

Priya proved the drift by running the code: Spring's `CreatorContextResponse`
was widened with six settings fields (`excluded_categories`, `blocked_brands`,
`working_hours_start/end`, `working_days`, `weekly_sponsored_limit`) but
`CREATOR_CONTEXT_PAYLOAD_FIELDS` -- which doubles as the allow-list
`build_block_b_creator` filters on -- was not, so every one of them was
silently dropped from the prompt. The Java-side fix was a no-op.

This test reads the @JsonProperty names off the ACTUAL Java record (not a
hand-maintained copy) and fails on any difference in either direction:

- a Java field missing from the Python tuple would be silently filtered out
  of Block B (this exact bug);
- a Python field missing from Java is a stale name nothing will ever send.

It deliberately FAILS (not skips) when the Java source cannot be found: CI
runs this suite from `influora-ai/` inside a full-repo checkout
(.github/workflows/ai-tests.yml), and a vacuous pass is how the last drift
went unnoticed. If the DTO moves, update `_JAVA_DTO_CANDIDATES`.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.prompt import assembler
from app.prompt.assembler import CREATOR_CONTEXT_PAYLOAD_FIELDS, build_block_b_creator

_REPO_ROOT = Path(__file__).resolve().parents[3]
_JAVA_DTO_CANDIDATES = (
    _REPO_ROOT / "influora-api/src/main/java/com/influora/web/dto/meera/MeeraContextDtos.java",
)

# Fields on the Java record that are NOT rendered as prose in Block B by
# design: routing/identity of the payload itself, or read by a different
# consumer (the A6 consent gate + its version in app/routes/chat.py +
# voice.py; the A8 per-creator cap override in app/costs/spend_tracker.py).
# Single source of truth lives next to the allow-list in the assembler.
_NOT_RENDERED_BY_DESIGN = assembler.CREATOR_CONTEXT_FIELDS_NOT_RENDERED
assert _NOT_RENDERED_BY_DESIGN >= {"audience", "workspace_id", "consent_accepted"}


def _java_creator_context_fields() -> set[str]:
    for candidate in _JAVA_DTO_CANDIDATES:
        if candidate.is_file():
            source = candidate.read_text(encoding="utf-8")
            break
    else:
        pytest.fail(
            "MeeraContextDtos.java not found at any of "
            + ", ".join(str(c) for c in _JAVA_DTO_CANDIDATES)
            + " -- this drift check must run inside a full-repo checkout; "
            "a skip here is exactly the vacuous pass that hid the last drift."
        )
    match = re.search(r"record\s+CreatorContextResponse\s*\((.*?)\)\s*\{", source, re.DOTALL)
    assert match, "CreatorContextResponse record not found in MeeraContextDtos.java"
    names = re.findall(r'@JsonProperty\("([a-z0-9_]+)"\)', match.group(1))
    assert names, "no @JsonProperty names parsed off CreatorContextResponse"
    return set(names)


def test_creator_context_payload_fields_match_the_java_record_exactly():
    java = _java_creator_context_fields()
    python = set(CREATOR_CONTEXT_PAYLOAD_FIELDS)
    only_in_java = sorted(java - python)
    only_in_python = sorted(python - java)
    assert not only_in_java, (
        "Spring's CreatorContextResponse emits fields the Python allow-list drops "
        f"(they never reach Meera's prompt): {only_in_java}. Add them to "
        "CREATOR_CONTEXT_PAYLOAD_FIELDS AND render them in build_block_b_creator."
    )
    assert not only_in_python, (
        f"CREATOR_CONTEXT_PAYLOAD_FIELDS names fields Java never sends: {only_in_python}"
    )


def test_creator_context_payload_fields_has_no_duplicates_and_is_sorted():
    """Sorted + unique keeps the diff against Java reviewable."""
    assert list(CREATOR_CONTEXT_PAYLOAD_FIELDS) == sorted(set(CREATOR_CONTEXT_PAYLOAD_FIELDS))


def test_every_allow_listed_field_is_actually_read_by_the_creator_block_builder():
    """An allow-list entry alone is not enough (Priya: "the function must emit
    lines for them"). Structural check: every field name except the three
    non-rendered-by-design ones appears as a `ctx.get("<name>")` /
    `_creator_str(ctx, "<name>"` read inside the assembler module, so a new
    field cannot be allow-listed and then forgotten by the renderer."""
    source = Path(assembler.__file__).read_text(encoding="utf-8")
    unread = [
        name
        for name in CREATOR_CONTEXT_PAYLOAD_FIELDS
        if name not in _NOT_RENDERED_BY_DESIGN
        and not re.search(rf"""ctx(?:\.get\(|, )['"]{re.escape(name)}['"]""", source)
    ]
    assert not unread, f"allow-listed but never read by build_block_b_creator: {unread}"


def test_every_java_field_changes_the_rendered_creator_block():
    """Behavioural half of the structural check above: for each Java field
    (except the non-rendered-by-design ones), a context WITH a distinctive
    value renders differently from one WITHOUT it.

    A field that only renders under another field (`agency_name` needs
    `represented=True`) lists its prerequisites in `_PREREQUISITES`; both
    sides of its comparison then carry the prerequisites so the diff
    isolates the field itself."""
    java = _java_creator_context_fields() - _NOT_RENDERED_BY_DESIGN
    base = {
        "workspace_id": "c-drift",
        "display_name": "Drift Tester",
        "first_name": "Drift",
        "consent_accepted": True,
    }
    distinctive = {
        "display_name": "Zebra Quokka",
        "first_name": "Zebra",
        "city": "Kochi-Drift",
        "tier": "MEGA-DRIFT",
        "categories": ["Drift-Category"],
        "creator_language": "ta-IN",
        "brand_tone": "FORMAL",
        "floors": {"reel_floor": "7,777"},
        "metrics_summary": {"followers": "77,777 followers"},
        "deals_summary": {"active_count": "77", "completed_count": "78"},
        "approval_level": 2,
        "represented": True,
        "excluded_categories": ["Drift-Excluded"],
        "blocked_brands": ["Drift-Blocked-Brand"],
        "working_hours_start": 7,
        "working_hours_end": 21,
        "working_days": [6, 7],
        "weekly_sponsored_limit": 9,
        "identity": {"kyc_done": True, "gstin_present": True},
        "floor_currency": "AED",
        "working_hours_timezone": "Asia/Dubai",
        # Gate fix round 3 (Priya): the represented-by-agency NAME.
        "agency_name": "Drift Agency Talent",
    }
    missing_fixture = sorted(java - set(distinctive))
    assert not missing_fixture, (
        f"Java added {missing_fixture}; give each a distinctive value here so the "
        "render check covers it"
    )
    for name in sorted(java):
        prereq = _PREREQUISITES.get(name, {})
        baseline = build_block_b_creator({**base, **prereq})["text"]
        rendered = build_block_b_creator({**base, **prereq, name: distinctive[name]})["text"]
        assert rendered != baseline, f"setting `{name}` did not change the creator Block B"


# Fields whose render line is gated on another field being set.
_PREREQUISITES: dict[str, dict] = {
    "agency_name": {"represented": True},
}


def test_agency_name_is_allow_listed_and_rendered_only_when_represented():
    """Gate fix round 3 (Priya): `agency_name` was read by the renderer but
    missing from the allow-list -> dead code, Meera never saw the name. The
    exact-match test above enforces the Java side once Vikram's DTO change
    (`@JsonProperty("agency_name") String agencyName`, nullable) lands; this
    pins the Python side independently."""
    assert "agency_name" in CREATOR_CONTEXT_PAYLOAD_FIELDS
    assert "agency_name" not in _NOT_RENDERED_BY_DESIGN
    assert "agency_name" in assembler._FORBIDDEN_BRAND_FIELDS
    ctx = {"workspace_id": "c-1", "display_name": "D", "first_name": "D", "agency_name": "Drift Agency Talent"}
    assert "Drift Agency Talent" not in build_block_b_creator(ctx)["text"]
    assert "REPRESENTED by Drift Agency Talent" in build_block_b_creator({**ctx, "represented": True})["text"]
