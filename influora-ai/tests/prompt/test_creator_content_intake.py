"""Creator Meera asks before she ideates (Swapnil, 2026-09-22).

When a creator asks for a content idea, Meera runs one short intake round (at
most 3 questions, each with ready answers, only for what the context does not
already hold), with a skip override, then gives the idea. This REPLACES the
.22.1 rule "Do not ask them to pick a category first."; the two must never
coexist. These tests pin the prompt TEXT only; they do not prove the live
model asks the questions or stops after one round.
"""

from __future__ import annotations

import re

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


def _intake() -> str:
    return TEXT[TEXT.index("Content idea intake.") : TEXT.index("- Short video only.")]


def test_intake_asks_three_questions_one_per_message():
    # Owner decision A (Swapnil, 2026-09-26) replaced the one-message intake: three questions,
    # ONE per message, each answered with one tap, before an idea or a full script.
    assert "Content idea intake." in TEXT
    assert (
        "Before an idea or a full script, find out what to make with at most 3 questions, ONE per"
        " message, each answered with one tap: give its short ready answers in one plain sentence"
        " and wait for the answer before the next." in TEXT
    )
    assert "only for what is genuinely unknown" in TEXT
    assert "in ONE message" not in _intake()


def test_never_asks_what_the_context_holds():
    assert "Never ask what the context already holds." in TEXT
    assert (
        "Category, audience, language, city, tier and follower count come from your context;"
        " use them, never ask for them."
    ) in TEXT
    assert "Name the category first." in TEXT


def test_each_question_carries_ready_options():
    """Owner decision A: Q1 topic, Q2 category (or the format with one category), Q3 goal."""
    intake = _intake()
    q1, q2, q3 = intake.index("Q1, the topic:"), intake.index("Q2:"), intake.index("Q3, the goal:")
    assert q1 < q2 < q3
    assert 'Q1, the topic: "Do you have a topic in mind?" Answers: "Yes, I\'ll type it";' in intake
    assert (
        '"Pick from today\'s topics" only when today\'s topics came back with a live topic for'
        " their categories;" in intake
    )
    assert (
        '"Use what works on my page" only when their own post results name best posts, each named'
        " by its caption line." in intake
    )
    assert (
        'With no live topic and too few posts of their own, the other answer is "You pick": pick'
        " from their profile categories and say that is where the topic came from." in intake
    )
    assert (
        "Q2: with several categories, which of their categories today, their categories as the"
        " answers; with one category, the format instead: a 15 s or a 30 s Reel." in intake
    )
    assert 'Q3, the goal: "What\'s this Reel for?" Grow followers, Brand deal or Sell something.' in intake
    # Lane B3 (ai.md M10): every downstream rule is video-only, so the intake never offers a
    # carousel; a creator who asks for one is told plainly and offered a Reel.
    assert "carousel" not in intake
    assert "If they ask for a carousel or a photo post, say plainly that your content notes cover short video only, then offer the idea as a Reel." in TEXT
    # The old questions are gone: no YouTube Short / past-work question in the intake.
    assert "YouTube Short" not in intake and "past work" not in intake


def test_topics_are_todays_topics_never_viral():
    assert "Call them today's topics, hand-picked by our team: never call a topic viral or trending." in TEXT
    # "viral" appears only inside prohibitions.
    for match in re.finditer(r"viral", TEXT):
        window = TEXT[max(0, match.start() - 80) : match.end()]
        assert "never" in window.lower(), window


def test_the_challenge_question_is_q2_and_the_category_is_never_asked_twice():
    assert (
        "This question is the intake's Q2 and the challenge day is its topic, so never ask the"
        " category or the topic again for this request." in TEXT
    )
    assert "The intake's three questions are about what to make and never count toward that shoot budget." in TEXT


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
