"""Content knowledge v4 and the full script format (Swapnil, 2026-09-22).

v4 adds three row types the full script reads: actions to film per category,
a starting length per goal, and which storytelling structure fits which
situation. Two structures his file named ("Claim-Problem-Replacement-Test",
"Character-Obstacle-Change") were never defined, so they are mapped onto
Problem-Agitate-Solve (PAS) and Three-act structure, and the loader now refuses
any selection rule that names a structure the block does not define.

The script is laid out in plain lines, not a markdown table: the creator chat
bubble renders raw text (`whitespace-pre-wrap`, no markdown renderer), so pipes
and asterisks would show as symbols. These tests pin the prompt TEXT only; they
do not prove the live model follows the layout.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_TEXT,
    KNOWLEDGE_PATH,
    KnowledgeFileError,
    load_knowledge,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA


def _flat(text: str) -> str:
    return " ".join(text.split())


TEXT = _flat(MEERA_CREATOR_PERSONA)
KNOWLEDGE = _flat(CREATOR_KNOWLEDGE_TEXT)
ROWS = load_knowledge()


def _of(data_type: str) -> list[dict]:
    return [r for r in ROWS if r["data_type"] == data_type]


def _write(tmp_path: Path, rows: list[dict]) -> Path:
    p = tmp_path / "k.jsonl"
    p.write_bytes(b"".join(json.dumps(r, ensure_ascii=False).encode("utf-8") + b"\n" for r in rows))
    return p


# --- the v4 data ----------------------------------------------------------------


def test_every_selection_rule_names_a_defined_structure_exactly():
    defined = {r["framework"] for r in _of("storytelling_structure")}
    for r in _of("structure_selection_rule"):
        assert r["structure"] in defined, r


def test_the_two_undefined_structures_are_mapped_not_carried():
    raw = KNOWLEDGE_PATH.read_bytes().decode("utf-8")
    for name in ("Claim-Problem-Replacement-Test", "Character-Obstacle-Change"):
        assert name not in raw
    by_situation = {r["situation"]: r for r in _of("structure_selection_rule")}
    contrarian = by_situation["Contrarian opinion"]
    assert contrarian["structure"] == "Problem-Agitate-Solve (PAS)"
    assert "without overclaiming" in contrarian["use_when"]
    assert "no invented numbers" in contrarian["use_when"]
    personal = by_situation["Personal experience"]
    assert personal["structure"] == "Three-act structure"
    assert "Act two: the obstacle" in personal["use_when"]
    assert by_situation["Quick tip or explanation"]["structure"] == "Grab-Story-CTA (micro 3-part structure)"


def test_v3_fixes_survive_the_v4_merge():
    raw = KNOWLEDGE_PATH.read_bytes().decode("utf-8")
    assert "tiktok" not in raw.lower()
    assert "video_goal" not in raw
    # The adapted-pattern source on rows 70-75 and the row 74 statistic fix are
    # pinned by test_creator_content_knowledge.py; the merge only appended rows.
    assert len(raw.splitlines()) == 210  # release/0922: go-live 128 + v4's 44 new rows + v5's 38 camera rows


def test_length_ranges_are_whole_seconds_low_to_high():
    goals = {r["goal"]: r["starting_range_seconds"] for r in _of("length_guideline")}
    assert goals == {
        "new_viewer_discovery": "15-35",
        "followers": "25-45",
        "saves": "30-60",
        "shares": "15-40",
        "tutorial": "35-90",
        "story_or_case_study": "45-90",
    }


def test_all_three_new_sections_render_into_the_block():
    assert "Which structure to use (situation -> the storytelling structure above):" in KNOWLEDGE
    assert "- Contrarian opinion -> Problem-Agitate-Solve (PAS)." in KNOWLEDGE
    assert "- followers: 25-45 seconds. Success looks like: Profile visits" in KNOWLEDGE
    assert "- Food: at home: Cut fruit, Pour tea" in KNOWLEDGE
    assert "a person, shop or place is filmed only with permission" in KNOWLEDGE
    for r in _of("contextual_action"):
        for action in r["home_actions"] + r["outdoor_actions"]:
            assert action in KNOWLEDGE, action


# --- the loader guards the new types ------------------------------------------------


def _rule(**over) -> dict:
    return _of("structure_selection_rule")[0] | over


def _story() -> dict:
    return next(r for r in ROWS if r.get("framework") == "Grab-Story-CTA (micro 3-part structure)")


def test_loader_refuses_a_rule_naming_an_undefined_structure(tmp_path):
    with pytest.raises(KnowledgeFileError, match="undefined structure 'Grab-Story-CTA'"):
        load_knowledge(_write(tmp_path, [_story(), _rule(structure="Grab-Story-CTA")]))
    assert len(load_knowledge(_write(tmp_path, [_story(), _rule()]))) == 2


@pytest.mark.parametrize("bad", ["15 to 35", "35-15", "20-20", "", "15-35s"])
def test_loader_refuses_a_malformed_length_range(tmp_path, bad):
    row = _of("length_guideline")[0] | {"starting_range_seconds": bad}
    with pytest.raises(KnowledgeFileError):
        load_knowledge(_write(tmp_path, [row]))


@pytest.mark.parametrize("field", ["home_actions", "outdoor_actions"])
@pytest.mark.parametrize("bad", [[], "Cut fruit", ["  "]])
def test_loader_refuses_bad_action_lists(tmp_path, field, bad):
    row = _of("contextual_action")[0] | {field: bad}
    with pytest.raises(KnowledgeFileError, match=f"'{field}' must be a non-empty list"):
        load_knowledge(_write(tmp_path, [row]))


# --- the persona: the full script format ------------------------------------------


def test_full_script_only_on_request():
    assert "Full script format (only when asked):" in TEXT
    assert (
        "Write a full script only when the creator asks for a script, or says yes to an idea you gave them."
        in TEXT
    )
    assert "A plain idea question still gets the short idea; end it by offering the full script." in TEXT


def test_short_reply_rule_names_the_script_and_the_week_plan_as_its_exceptions():
    # Lane B3 (ai.md M1): the week plan is seven lines plus a line above it, so the short rule
    # has to name it too, and a format's own layout (numbered tips, label lines) wins.
    assert "The one exception to length is a full script" not in TEXT
    assert (
        "The two exceptions are a full script and a week plan: each is laid out exactly as"
        " its own format below says, and that layout wins over this rule."
    ) in TEXT


def test_script_picks_structure_and_length_from_the_knowledge():
    assert 'Pick the structure from "Which structure to use" for their situation' in TEXT
    assert 'the length from "Script length by goal" for their goal' in TEXT
    assert "The beat timings start at 0s, leave no gaps, and end at that length." in TEXT
    assert "Timings and the length are script choices, not metrics" in TEXT


def test_every_beat_names_a_camera_angle_and_a_permitted_action():
    assert "Every beat names one camera angle from the knowledge exactly as it is named" in TEXT
    assert 'a real action for their category from "Actions to film"' in TEXT
    assert "Never script filming a person, shop or place without the creator asking permission first." in TEXT


def test_layout_is_plain_text_with_every_part():
    assert "no asterisks, no table pipes, no headers, no emojis" in TEXT
    for part in (
        "Idea: a short title.",
        "Plan: for whom; the one feeling; the goal; the length in seconds, vertical 9:16;",
        '"0-3s. Shot: <camera angle> - <action>. Say: "<exact line>". On screen: <text>."',
        "Caption: one caption that carries the conversation question;",
        'Action: what they do on camera while they speak, from "Actions to film";',
        'Success looks like: the line for their goal from "Script length by goal".',
        "Before you shoot: three practical items",
        "Why this works: the knowledge entries you used, each by its exact name",
    ):
        assert part in TEXT, part


def test_hashtags_are_optional_and_capped_at_two():
    assert "hashtags are optional, at most 2, and only relevant ones" in TEXT
    assert "3 to 5" not in TEXT


def test_plan_offers_a_hands_only_fallback():
    assert "if they would rather not be on camera: hands only, overhead, with voice-over" in TEXT


def test_one_call_to_action_matched_to_the_goal():
    assert "One call to action, in the last beat only, and it matches the goal:" in TEXT
    assert "followers means follow, saves means save, shares means send it to someone" in TEXT
    assert "Never stack follow, save, share and comment in one ending" in TEXT
    assert "the conversation question goes in the caption instead" in TEXT


def test_no_absolute_promises_in_lines_caption_or_tips():
    assert "No absolute promises, in the lines, the caption or the filming tips." in TEXT
    for banned in ('"the secret"', '"exactly the same taste"', '"guaranteed"', '"always works"',
                   '"the first 3 seconds decide"'):
        assert banned in TEXT, banned
    # The review's softer wording is the model the rule points at.
    assert '"isse flavour achchhe se aata hai"' in TEXT
    assert '"kaafi close hai"' in TEXT


def test_for_whom_never_invents_an_audience():
    assert 'For whom comes from the "Your audience" line.' in TEXT
    assert "describe the viewer from their category only, with no ages, cities or percentages" in TEXT


def test_existing_rails_hold_inside_a_script():
    assert (
        "Every rule above still holds inside a script: their language, no invented statistics,"
        " no urgency wording, only Instagram Reels or YouTube Shorts for short-form,"
        " never a real individual or brand as a target."
    ) in TEXT


def test_script_rules_and_v4_knowledge_reach_the_assembled_creator_prompt():
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-script-001",
            "audience": "CREATOR",
            "creator": {
                "workspace_id": "creator-script-001",
                "display_name": "Asha Rao",
                "first_name": "Asha",
                "city": "Pune",
                "tier": "NANO",
                "categories": ["Food"],
                "creator_language": "hi-IN",
                "brand_tone": "FRIENDLY",
            },
            "conversation": [{"role": "user", "content": "Full script do chai reel ka"}],
        },
        session_id="s-script",
    )
    joined = _flat("\n".join(b["text"] for b in prompt.system_blocks))
    assert "Full script format (only when asked):" in joined
    assert "Script length by goal" in joined
    assert "Actions to film, by category" in joined


def test_brand_prompt_carries_neither_script_rules_nor_v4_sections():
    prompt = assemble_prompt(
        {"workspace_id": "brand-script-001", "conversation": [{"role": "user", "content": "hi"}]},
        session_id="s-brand",
    )
    joined = "\n".join(b["text"] for b in prompt.system_blocks)
    for marker in ("Full script format", "Script length by goal", "Actions to film", "Which structure to use"):
        assert marker not in joined, marker
