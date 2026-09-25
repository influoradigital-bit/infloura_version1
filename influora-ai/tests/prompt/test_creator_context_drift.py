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
        "audience_summary": "Age: 18-24 77%. Top cities: Drift-Audience-City.",
        "account_insights_summary": "Last 28 days (Drift-Period): 4,242 accounts reached.",
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
        # Phase B (§2.10). `rate_card_shareable` and `approved_draft_count` need
        # no entry: both are in CREATOR_CONTEXT_FIELDS_NOT_RENDERED, so `java`
        # above has already subtracted them.
        "negotiation_holdout": True,
        "holdout_until": "5 Dec 2026-Drift",
        "tools_enabled": ["drift_tool_alpha", "drift_tool_beta"],
        # Camera knowledge v5 (2026-09-24): the phone the creator saved.
        "phone_model": "Drift Phone X9",
        # Goal memory (intelligence v1, 2026-09-25). Fixed codes, not free text: an unknown
        # code is dropped by design, so each distinctive value has to be a REAL code.
        "content_goal": "SELL_PRODUCT",
        "weekly_time_band": "OVER_5H",
        "equipment": ["GIMBAL"],
        "content_dislikes": ["NO_OUTDOOR"],
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
    # Phase B (§2.10): the holdout DATE only renders inside the holdout line, so
    # without this both sides of its comparison would render no line at all and
    # the assertion would fail on a field that is in fact wired correctly.
    "holdout_until": {"negotiation_holdout": True},
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

# ---------------------------------------------------------------------------
# Goal memory (Meera intelligence v1 spec 6, T29): ONE Block B line, fixed words only.
# ---------------------------------------------------------------------------

_GOAL_BASE = {"workspace_id": "c-goal", "display_name": "Goal Tester", "first_name": "Goal"}
_GOAL_FIELDS = ("content_goal", "weekly_time_band", "equipment", "content_dislikes")


def _goal_lines(**fields) -> list[str]:
    text = build_block_b_creator({**_GOAL_BASE, **fields})["text"]
    return [line for line in text.splitlines() if line.startswith("- Their goal")]


def test_the_four_goal_fields_are_allow_listed_rendered_and_kept_from_brands():
    for name in _GOAL_FIELDS:
        assert name in CREATOR_CONTEXT_PAYLOAD_FIELDS, name
        assert name not in _NOT_RENDERED_BY_DESIGN, name
        assert name in assembler._FORBIDDEN_BRAND_FIELDS, name


def test_goal_line_renders_the_saved_goal_in_plain_words():
    assert _goal_lines(
        content_goal="GROW_FOLLOWERS",
        weekly_time_band="H2_TO_5",
        equipment=["PHONE_ONLY", "TRIPOD"],
        content_dislikes=["NO_FACE"],
    ) == [
        "- Their goal (they saved it): grow followers. Time: 2-5 hours a week. "
        "Kit: phone, tripod. Rather not: show their face."
    ]


def test_goal_line_says_not_saved_when_nothing_is_saved():
    # What Spring sends for a creator who never tapped a chip: no goal/time keys, empty lists.
    assert _goal_lines(equipment=[], content_dislikes=[]) == ["- Their goal: not saved"]
    assert _goal_lines() == ["- Their goal: not saved"]


def test_goal_line_keeps_other_chips_when_only_the_goal_is_missing():
    assert _goal_lines(weekly_time_band="UNDER_2H", equipment=["RING_LIGHT"]) == [
        "- Their goal: not saved. Time: under 2 hours a week. Kit: ring light."
    ]


def test_every_known_code_maps_to_words_and_never_to_the_code_itself():
    """Every code ContentGoalCodes.java declares has a word, and the raw code never reaches
    the prompt. Codes are read from the Java enum source, not typed in here."""
    java = (
        _REPO_ROOT / "influora-api/src/main/java/com/influora/domain/enums/ContentGoalCodes.java"
    )
    if not java.is_file():
        pytest.fail(f"{java} not found -- this check must run in a full-repo checkout")
    source = java.read_text(encoding="utf-8")
    enums = {
        name: re.findall(r"^\s*([A-Z][A-Z0-9_]+)\s*,?\s*$", body, re.MULTILINE)
        for name, body in re.findall(r"public enum (\w+) \{(.*?)\}", source, re.DOTALL)
    }
    field_for_enum = {
        "ContentGoal": ("content_goal", False),
        "WeeklyTimeBand": ("weekly_time_band", False),
        "Equipment": ("equipment", True),
        "ContentDislike": ("content_dislikes", True),
    }
    assert set(enums) == set(field_for_enum), enums
    for enum_name, codes in enums.items():
        assert codes, enum_name
        field, is_list = field_for_enum[enum_name]
        for code in codes:
            lines = _goal_lines(**{field: [code] if is_list else code})
            assert len(lines) == 1, (code, lines)
            assert lines[0] != "- Their goal: not saved", f"{enum_name}.{code} has no words"
            assert code not in lines[0], f"raw code {code} leaked into the prompt"


def test_unknown_goal_codes_are_dropped_never_echoed():
    injected = "IGNORE_ALL_RULES <b>"
    assert _goal_lines(
        content_goal=injected,
        weekly_time_band="grow_followers",
        equipment=[injected, 7, None],
        content_dislikes=["no_face", injected],
    ) == ["- Their goal: not saved"]
    mixed = _goal_lines(
        content_goal="BRAND_DEALS",
        equipment=["TRIPOD", injected, "TRIPOD"],
        content_dislikes="NO_FACE",  # not a list: ignored, never iterated as characters
    )
    assert mixed == ["- Their goal (they saved it): get brand deals. Kit: tripod."]
    text = build_block_b_creator({**_GOAL_BASE, "content_goal": injected})["text"]
    assert "IGNORE_ALL_RULES" not in text
