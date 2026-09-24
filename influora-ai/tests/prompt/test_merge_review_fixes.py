"""Ash's review of the content-knowledge merge (2026-09-24): the fixes, pinned.

H1  a script reply has a fixed start and end, so the app's card can find it.
M3  no hook template claims what other people did or got.
M5  an imagined viewer is never passed off as their audience.
L1  the goal-metrics row still names the hooks' goal values.
L5  a creator prompt with no language falls back to English, like the rest of the app.
"""

from __future__ import annotations

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_TEXT, load_knowledge
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA


def _flat(text: str) -> str:
    return " ".join(text.split())


PERSONA = _flat(MEERA_CREATOR_PERSONA)


def test_script_reply_has_a_fixed_start_and_end():
    assert "Start with the Idea line, nothing before it." in PERSONA
    assert (
        "The one closing question goes on its own line after Why this works, and nothing follows it."
        in PERSONA
    )
    assert "goes inside the Plan line" in PERSONA


def test_no_hook_claims_what_other_people_did_or_got():
    templates = [r["template"] for r in load_knowledge() if r["data_type"] == "hook_template"]
    assert "People tried this - see what happened" not in templates
    assert "Most people get this wrong - here's the right way" not in templates
    assert "[Who tried it, as the creator told you] tried this - here's what happened" in templates


def test_an_imagined_viewer_is_never_their_audience():
    assert (
        'An imagined viewer from "Write for ONE hyper-specific person" is called imagined and is never'
        " given an age or a city as if it were their audience."
    ) in PERSONA


def test_goal_metrics_row_names_the_hook_goal_values():
    row = next(r for r in load_knowledge() if r.get("concept") == "Judge each video by its goal")
    for goal in ("new_viewer_discovery", "retention_series", "conversion"):
        assert goal in row["video_application"], goal
    assert "names that job" in CREATOR_KNOWLEDGE_TEXT


def test_knowledge_carries_no_borrowed_statistic_and_no_tiktok_terms():
    raw = _flat(CREATOR_KNOWLEDGE_TEXT)
    assert "80%" not in raw
    assert "duet" not in raw.lower()


def test_creator_prompt_without_a_language_falls_back_to_english():
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-lang-001",
            "audience": "CREATOR",
            "creator": {"workspace_id": "creator-lang-001", "audience": "CREATOR", "display_name": "Asha"},
            "conversation": [{"role": "user", "content": "hi"}],
        },
        session_id="s-lang",
    )
    joined = "\n".join(b["text"] for b in prompt.system_blocks)
    assert "Reply language: en-IN" in joined
    assert "Reply language: hi-IN" not in joined
