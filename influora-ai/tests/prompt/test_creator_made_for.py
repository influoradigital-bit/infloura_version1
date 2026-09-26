"""The "Made for" line and the audience it may name (owner decision B, Swapnil 2026-09-26).

Before an idea, and at the top of every full script right after the Idea line, Meera states the
basis in one line: who it is for, the topic and where it came from, and the goal. Who it is for
comes ONLY from the "Your audience" line or the creator's own audience result (get_my_audience),
preferring the people engaging with their Reels this month; with neither, the fixed
not-available wording. Never an age, gender or city guessed from a name or a photo.

Pins the prompt TEXT (and that the layout's own example carries no audience facts a model could
copy); the parser side of the line is pinned in tests/recommendations/test_script_card_parity.py
and the live-output check in evals/shot_card_scorer.py (`made_for_audience_guesses`).
"""

from __future__ import annotations

import re

from app.prompt.assembler import assemble_prompt
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA


def _flat(text: str) -> str:
    return " ".join(text.split())


TEXT = _flat(MEERA_CREATOR_PERSONA)
SCRIPT = TEXT[TEXT.index("Full script format (only when asked):") : TEXT.index("Profile review format")]
NOT_AVAILABLE = "your audience data isn't available yet, so this is written for a general audience"


def _layout_lines() -> list[str]:
    source = MEERA_CREATOR_PERSONA
    section = source[source.index("Full script format (only when asked):") : source.index("Profile review format")]
    return [line.strip() for line in section.splitlines()]


def test_made_for_sits_right_after_idea_in_the_layout():
    lines = _layout_lines()
    idea = lines.index("Idea: a short title.")
    assert lines[idea + 1].startswith("Made for: the basis in one line of at most 200 characters")
    plan = next(i for i, line in enumerate(lines) if line.startswith("Plan: for whom;"))
    assert idea < idea + 1 < plan
    assert '"Made for: <who> · Topic: <topic> (<where it came from>) · Goal: <goal>"' in SCRIPT
    assert (
        "Start with the Idea line, nothing before it. The Made for line comes right after it, its"
        " label in English too." in SCRIPT
    )
    assert "The audience note goes in the Made for line;" in SCRIPT
    assert "Any other note, such as which defaults you used, goes inside the Plan line." in SCRIPT


def test_the_layouts_own_example_carries_no_audience_fact_to_copy():
    """A model copies examples: the template line and the rule's example use placeholders, never
    a real share, age band, gender or city."""
    template = next(line for line in _layout_lines() if line.startswith('"Made for:'))
    assert not re.search(r"\d", template)
    rule = TEXT[TEXT.index('- For whom comes from the "Your audience" line.') :]
    rule = rule[: rule.index(" - ")]
    example = rule[rule.index("for example") : rule.index("If the audience is not available")]
    assert not re.search(r"\d|%|\bwomen\b|\bmen\b|Mumbai|Delhi", example), example


def test_who_it_is_for_comes_only_from_the_audience_line_or_result():
    rule = TEXT[TEXT.index('- For whom comes from the "Your audience" line.') :]
    rule = rule[: rule.index(" - ")]
    assert 'For whom comes from the "Your audience" line. So does the Made for line, the basis in one line:' in rule
    assert "for an idea without a full script, say it in one line before the idea." in rule
    assert (
        "who it is for, the topic with where it came from (they typed it, today's topics, their"
        " best-performing posts, or their categories) and the goal." in rule
    )
    assert (
        "Who is only from that line or their own audience result: the people engaging with their"
        " Reels this month when it shows them, otherwise their followers" in rule
    )
    assert f'If the audience is not available, write "{NOT_AVAILABLE}"' in rule
    assert "Never guess a gender, an age or a city from their name, their photo or their category." in rule


def test_the_audience_rule_prefers_the_engaged_audience_and_never_guesses():
    assert (
        "Their own audience result, when you have one, counts the same; when it shows who engaged"
        ' this month, prefer that and call them "the people engaging with your Reels this month".'
        in TEXT
    )
    assert (
        "Never state an audience fact that is not in that line or that result: no guessed ages,"
        " genders, cities or percentages, and never one read from their name or a photo." in TEXT
    )
    assert "(or, for who engaged this month, below 100 engagements)" in TEXT


def test_the_made_for_rules_reach_creators_only():
    def system(request: dict) -> str:
        return _flat("\n".join(b["text"] for b in assemble_prompt(request, session_id="s").system_blocks))

    creator = system(
        {
            "workspace_id": "c-1", "audience": "CREATOR",
            "creator": {"workspace_id": "c-1", "first_name": "Asha", "categories": ["Food"]},
            "conversation": [{"role": "user", "content": "full script"}],
        }
    )
    brand = system({"workspace_id": "b-1", "conversation": [{"role": "user", "content": "hi"}]})
    for marker in ("Made for: the basis in one line", NOT_AVAILABLE, "Q1, the topic:", "Set-up seen"):
        assert marker in creator, marker
        assert marker not in brand, marker


# --- fix round 2026-09-26: the basis line never sits before Idea; long lines keep the note ----


def test_in_a_full_script_the_basis_is_only_the_made_for_line_never_before_idea():
    """Checker finding: "before you write an idea, say that same basis in one line, and put it on
    the Made for line" let a model put the basis ABOVE the Idea line of a full script, and then
    neither parser renders the card (both need Idea first). The rule now says which is which."""
    rule = TEXT[TEXT.index('- For whom comes from the "Your audience" line.') :]
    rule = rule[: rule.index(" - ")]
    assert (
        "In a full script the basis is only the Made for line, right after Idea, never a line"
        " before it; for an idea without a full script, say it in one line before the idea." in rule
    )
    # The old two-place wording is gone from the whole prompt.
    assert "say that same basis in one line, and put it on the Made for line" not in TEXT


def test_a_basis_line_above_idea_does_not_parse_so_the_rule_matters():
    """Pins WHY the rule above exists: the parser drops a full script whose first line is not Idea."""
    import json
    from pathlib import Path

    from app.recommendations.script_card import parse_meera_script

    fixtures = json.loads(
        (Path(__file__).resolve().parents[1] / "fixtures" / "meera_scripts.json").read_text(encoding="utf-8")
    )
    case = fixtures["made_for_cases"][0]
    text = case["text"] if isinstance(case, dict) else case
    lines = text.split("\n")
    made = next(i for i, line in enumerate(lines) if line.startswith("Made for:"))
    idea = next(i for i, line in enumerate(lines) if line.startswith("Idea:"))
    assert parse_meera_script(text) is not None
    moved = [lines[made]] + [line for i, line in enumerate(lines) if i != made]
    assert moved.index(lines[made]) < moved.index(lines[idea])
    assert parse_meera_script("\n".join(moved)) is None


def test_a_long_made_for_line_never_loses_the_general_audience_note():
    """Priya finding: a Made for value over 200 characters is dropped by both parsers, and with it
    the only statement that the script was written for a general audience. The persona now caps
    the line by shortening the topic, and the Plan line carries the short note too."""
    rule = TEXT[TEXT.index('- For whom comes from the "Your audience" line.') :]
    rule = rule[: rule.index(" - ")]
    assert "Keep the Made for line within 200 characters: shorten the topic first, never the audience part." in rule
    assert (
        'The audience note goes in the Made for line; when the audience is not available, the Plan'
        ' line\'s for whom also says "a general audience", so the card keeps it even if the Made for'
        " line runs long. Any other note, such as which defaults you used, goes inside the Plan line."
        in SCRIPT
    )
