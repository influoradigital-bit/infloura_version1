"""Creator Meera asks before she ideates (Swapnil, 2026-09-22).

When a creator asks for a content idea, Meera runs one short intake round (at
most 3 questions, each with ready answers, only for what the context does not
already hold), with a skip override, then gives the idea. This REPLACES the
.22.1 rule "Do not ask them to pick a category first."; the two must never
coexist. These tests pin the prompt TEXT only; they do not prove the live
model asks the questions or stops after one round.
"""

from __future__ import annotations

from app.prompt.assembler import assemble_prompt
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA


def _flat(text: str) -> str:
    return " ".join(text.split())


TEXT = _flat(MEERA_CREATOR_PERSONA)


def _creator_system_text() -> str:
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-intake-001",
            "audience": "CREATOR",
            "creator": {
                "workspace_id": "creator-intake-001",
                "display_name": "Tejas Patil",
                "first_name": "Tejas",
                "city": "Pune",
                "tier": "NANO",
                "categories": ["Travel", "Food"],
                "creator_language": "hi-IN",
                "brand_tone": "FRIENDLY",
            },
            "conversation": [{"role": "user", "content": "I want to create a content idea"}],
        },
        session_id="s-intake",
    )
    return _flat("\n".join(b["text"] for b in prompt.system_blocks))


def test_intake_asks_at_most_three_questions_in_one_message():
    assert "Content idea intake." in TEXT
    assert "first ask at most 3 short questions in ONE message" in TEXT
    assert "only for what is genuinely unknown" in TEXT


def test_never_asks_what_the_context_holds():
    assert "Never ask what the context already holds." in TEXT
    assert (
        "Category, audience, language, city, tier and follower count come from your context;"
        " use them, never ask for them."
    ) in TEXT
    assert "Name the category first." in TEXT


def test_each_question_carries_ready_options():
    assert "give each question ready options they can answer in a word" in TEXT
    assert "goal (grow followers, a brand deal, or selling something)" in TEXT
    # Lane B3 (ai.md M10): every downstream rule is video-only, so the intake no longer offers
    # a carousel; a creator who asks for one is told plainly and offered a Reel.
    assert "format (Reel or YouTube Short)" in TEXT
    assert "carousel)" not in TEXT
    assert "If they ask for a carousel or a photo post, say plainly that your content notes cover short video only, then offer the idea as a Reel." in TEXT
    assert "ask them to paste their last video script as text, or tell you which recent video did best" in TEXT
    assert "say they can skip this one" in TEXT
    assert "which category today, with their categories as the options" in TEXT


def test_options_are_the_only_exception_to_no_menus():
    assert "never a menu of options. The one exception is the content-idea intake below" in TEXT


def test_skip_override_answers_now_on_stated_defaults():
    assert "Skip override." in TEXT
    for phrase in ('"Just give me an idea"', '"skip"', '"jaldi batao"', '"koi bhi"'):
        assert phrase in TEXT, phrase
    assert "mean answer NOW with sensible defaults" in TEXT
    assert "their first or strongest category, a Reel, and the grow-followers goal" in TEXT
    assert "Say in one line which defaults you used" in TEXT


def test_one_round_of_questions_only():
    assert "One round of questions only. Never ask a second round of intake." in TEXT
    assert "If an answer is unclear, pick a sensible default, say which one, and give the idea." in TEXT


def test_skips_questions_already_answered():
    assert "Skip questions they already answered." in TEXT
    assert "do not ask for it again" in TEXT
    assert "If it gives everything, go straight to the idea." in TEXT


def test_idea_after_answers_uses_named_knowledge():
    assert "After they answer, give the idea: one storytelling structure, one hook and the camera shots" in TEXT
    assert "each taken from the content knowledge and named" in TEXT


def test_no_refusal_rule_still_present():
    assert "Never tell the creator that content ideas are not your job" in TEXT
    assert "never hand the question back without an idea" in TEXT


def test_old_do_not_ask_first_rule_is_gone():
    # The .22.1 rule contradicts the intake; it must not survive alongside it.
    assert "Do not ask them to pick a category first" not in TEXT
    assert "pick a category first" not in TEXT
    assert "When they ask for an idea, give one." not in TEXT


def test_existing_content_rails_kept():
    assert "No invented statistics in hooks." in TEXT
    assert "No urgency wording." in TEXT
    assert "Audience not available is never a reason to hold back." in TEXT
    assert "Never use the follower count as a put-down or as filler." in TEXT


def test_intake_reaches_the_assembled_creator_system_prompt():
    text = _creator_system_text()
    assert "Content idea intake." in text
    assert "Skip override." in text
    assert "One round of questions only." in text
    assert "pick a category first" not in text
