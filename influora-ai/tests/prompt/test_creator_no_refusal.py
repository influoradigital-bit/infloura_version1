"""Creator Meera must never refuse a content-idea request.

Seen in real use (Swapnil, 2026-09-22): "I want new content idea for today" got
"Main tumhare deals aur earnings manage karti hoon, content ideas nahi deti".
The opening framed Meera as a deals-only manager, so the model inferred content
was out of scope. These tests pin the prompt TEXT that closes that; they do not
prove the live model stops refusing.
"""

from __future__ import annotations

from app.config import PROMPT_VERSION
from app.prompt.assembler import assemble_prompt
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA


def _flat(text: str) -> str:
    return " ".join(text.split())


def _opening(text: str) -> str:
    # The role statement: everything before the first rule section.
    return _flat(text.split("Who you are talking to:")[0])


def _creator_system_text() -> str:
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-norefuse-001",
            "audience": "CREATOR",
            "creator": {
                "workspace_id": "creator-norefuse-001",
                "display_name": "Tejas Patil",
                "first_name": "Tejas",
                "city": "Pune",
                "tier": "NANO",
                "categories": ["Travel", "Food", "Fashion"],
                "creator_language": "hi-IN",
                "brand_tone": "FRIENDLY",
            },
            "conversation": [{"role": "user", "content": "I want new content idea for today"}],
        },
        session_id="s-norefuse",
    )
    return _flat("\n".join(b["text"] for b in prompt.system_blocks))


def test_role_statement_includes_content_help():
    opening = _opening(MEERA_CREATOR_PERSONA)
    assert "Content help is part of your job" in opening
    for word in ("content ideas", "hooks", "scripts", "storytelling", "camera guidance"):
        assert word in opening, word
    assert "alongside deals, rates and earnings" in opening


def test_no_refusal_rule_is_in_the_opening():
    opening = _opening(MEERA_CREATOR_PERSONA)
    assert "Never tell the creator that content ideas are not your job" in opening
    assert "never hand the question back without an idea" in opening


def test_multi_category_is_asked_in_the_intake_not_answered_per_category():
    # .22.2 replaced "one idea per category, do not ask first" with the intake.
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "With several categories, one of the questions is which category today" in text
    assert "give one concrete idea per category (at most 3)" not in text


def test_audience_not_available_still_answers():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Audience not available is never a reason to hold back." in text
    assert "Instagram gives no demographics below 100 followers" in text
    assert "then still give the idea from their category and the content knowledge" in text


def test_follower_count_is_never_a_put_down():
    assert "Never use the follower count as a put-down or as filler." in _flat(MEERA_CREATOR_PERSONA)


def test_rules_reach_the_assembled_creator_system_prompt():
    text = _creator_system_text()
    assert "Never tell the creator that content ideas are not your job" in text
    assert "Content idea intake." in text
    assert "then still give the idea from their category and the content knowledge" in text


def test_prompt_version_bumped_for_no_refusal():
    # .22.1 introduced no-refusal; .22.2 (content-idea intake), then .22.3 (knowledge v3), .22.4 (statistic rule), .22.5 (knowledge v4 + script format), .22.6 (script review).
    assert PROMPT_VERSION == "meera-2026.09.22.6"
