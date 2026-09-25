"""Plan my shoot, the coach style and the shooting grounding rule (PROMPT_VERSION .25.1,
Swapnil 2026-09-25, contract D).

When a creator asks how or where to shoot, or asks for a full script, Meera's one intake runs
as "Plan my shoot": only questions from the coach question bank (rendered in the knowledge
block under the exact heading below), one per message, at most 3, skipping what is already
known. Every shooting instruction is grounded in the knowledge, and the full script gains a
Set-up line between Action and Success looks like, which the app's script card
(src/lib/meera-result-cards.ts) parses as optional.

These tests pin the prompt TEXT only; they do not prove the live model asks one question per
message or stops at three.
"""

from __future__ import annotations

from app.config import PROMPT_VERSION
from app.prompt.assembler import assemble_prompt
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

COACH_HEADING = (
    "Coach questions (ask only these; one per message; at most 3 per plan; "
    "skip any whose answer you already have):"
)
SETUP_LINE = (
    "Set-up: where you sit or stand and where the light falls (your left/right); "
    "where the phone goes (height, distance, lens); the settings for your phone; "
    "how you move between spots."
)
SCRIPT_SECTION_START = "Full script format (only when asked):"
SCRIPT_SECTION_END = "Profile review format (only when asked to review their profile):"


def _flat(text: str) -> str:
    return " ".join(text.split())


TEXT = _flat(MEERA_CREATOR_PERSONA)


def _plan_my_shoot_bullet() -> str:
    start = TEXT.index("- Plan my shoot.")
    return TEXT[start : TEXT.index(" - ", start + 1)]


def _script_section() -> str:
    return TEXT[TEXT.index(SCRIPT_SECTION_START) : TEXT.index(SCRIPT_SECTION_END)]


def _system_text(audience: str | None) -> str:
    request: dict = {"workspace_id": "pms-001", "conversation": [{"role": "user", "content": "reel kaise shoot karu"}]}
    if audience == "CREATOR":
        request["audience"] = "CREATOR"
        request["creator"] = {
            "workspace_id": "pms-001",
            "display_name": "Asha Rao",
            "first_name": "Asha",
            "city": "Pune",
            "tier": "NANO",
            "categories": ["Food"],
            "creator_language": "hi-IN",
            "brand_tone": "FRIENDLY",
        }
    prompt = assemble_prompt(request, session_id="s-pms")
    return _flat("\n".join(b["text"] for b in prompt.system_blocks))


# --- the intake -------------------------------------------------------------------


def test_plan_my_shoot_runs_for_shooting_questions_and_full_scripts():
    bullet = _plan_my_shoot_bullet()
    assert "When they ask how or where to shoot something, or ask for a full script" in bullet
    assert "For how or where to shoot, and for a full script, that same intake runs as Plan my shoot below." in TEXT


def test_intake_names_the_coach_bank_by_its_exact_heading_and_asks_nothing_else():
    bullet = _plan_my_shoot_bullet()
    assert (
        f'Every shooting question comes ONLY from the "{COACH_HEADING}" section of your knowledge block'
        in bullet
    )
    assert "never a question of your own" in bullet


def test_one_question_per_message_and_skip_what_is_known():
    bullet = _plan_my_shoot_bullet()
    assert "never a question of your own: one per message," in bullet
    # The old per-plan count of bank questions only is gone: the budget below counts everything.
    assert "at most 3 in the whole plan" not in bullet
    assert (
        "skip any whose answer you already have from your context, their message or this conversation"
        in bullet
    )
    assert "with that question's options as short ready answers in one plain sentence" in bullet


# --- ONE intake budget (review round, still .25.1) ----------------------------------


def test_one_question_budget_counts_every_question_for_the_request():
    bullet = _plan_my_shoot_bullet()
    assert (
        "ask before you plan, inside ONE question budget: at most 3 questions in TOTAL for that"
        " request, counting any already asked for it in this conversation." in bullet
    )


def test_after_the_idea_intake_only_one_bank_question():
    bullet = _plan_my_shoot_bullet()
    assert (
        "If the content idea intake already ran in this conversation, ask at most ONE coach"
        " question and nothing else, whatever that intake asked." in bullet
    )
    # The one-round rule points at the same budget instead of contradicting it.
    assert (
        "never repeats a question already answered, and asks only inside the question budget below,"
        " even when the idea intake already ran." in TEXT
    )


def test_the_budget_states_that_it_wins():
    bullet = _plan_my_shoot_bullet()
    assert (
        "This budget is the rule that wins: where any other sentence here seems to allow more"
        " questions, ask fewer." in bullet
    )


def test_goal_and_unknown_category_may_be_asked_shooting_questions_only_from_the_bank():
    bullet = _plan_my_shoot_bullet()
    assert (
        "The goal, and the category only when your context has none, may be asked the way the"
        " content idea intake asks them." in bullet
    )
    assert f'Every shooting question comes ONLY from the "{COACH_HEADING}" section' in bullet
    # The never-ask list no longer forbids the category outright (that contradicted the line above).
    assert "Never ask for their category, city" not in bullet


def test_plain_idea_intake_keeps_its_one_message_form():
    idea = TEXT[TEXT.index("Content idea intake.") : TEXT.index("- Short video only.")]
    assert "When they ask for a content idea, first ask at most 3 short questions in ONE message" in idea
    assert "per message" not in idea
    assert "budget" not in idea


def test_never_asks_city_language_phone_time_of_day_or_a_known_category():
    bullet = _plan_my_shoot_bullet()
    assert (
        "Never ask for their city, language, saved phone or the time of day, or for their category"
        " when your context has it" in bullet
    )
    # time_available is a coach bank question; only the clock time is off limits.
    assert "saved phone or the time:" not in bullet
    assert "what the light outside is like comes from the outdoor light question, never the clock" in bullet
    # The existing phone rule points at the bank question inside Plan my shoot, and the
    # existing "ask once" phone rule (pinned by test_creator_camera_knowledge.py) survives.
    assert "In Plan my shoot, that ask is the phone lens coach question, counted in your three." in TEXT
    assert "ask once which phone they film on" in TEXT


def test_skip_plans_now_on_defaults_said_in_one_line():
    bullet = _plan_my_shoot_bullet()
    assert '"Skip", "jaldi batao" and anything like them mean stop asking and plan NOW' in bullet
    assert "say those defaults in one line, then give the plan" in bullet


def test_one_intake_not_two():
    # Merge, don't duplicate: the content idea intake stays (one message, at most 3) and Plan
    # my shoot is declared to be that same intake, never a second round.
    assert TEXT.count("Content idea intake.") == 1
    assert TEXT.count("- Plan my shoot.") == 1
    assert "first ask at most 3 short questions in ONE message" in TEXT
    assert "One round of questions only. Never ask a second round of intake." in TEXT
    assert "Plan my shoot is this same intake for a shoot, not a second round" in TEXT
    # "one per message" belongs to Plan my shoot only, never to the idea intake.
    idea = TEXT[TEXT.index("Content idea intake.") : TEXT.index("- Short video only.")]
    assert "per message" not in idea
    outside = TEXT.replace(_plan_my_shoot_bullet(), "")
    assert "one per message" not in outside
    assert "one question per message" not in outside


def test_options_exception_names_plan_my_shoot():
    assert (
        "never a menu of options. The one exception is the content-idea intake below, and its"
        " Plan my shoot form" in TEXT
    )


# --- coach style and grounding ----------------------------------------------------


def test_coach_style_observe_suggest_confirm():
    assert "Coach style: observe, then suggest, then confirm." in TEXT
    assert '"we" and "let\'s", in the creator\'s language' in TEXT
    assert "give the plan in the coach style" in TEXT
    # Placement order still rules the steps.
    assert "(the creator, the phone, the light, then the settings)" in TEXT


def test_keep_it_short_lists_the_plan_my_shoot_plan_capped_at_five_steps():
    assert (
        "The exceptions are a full script, a week plan, a profile review and a Plan my shoot plan:"
        " each is laid out exactly as its own format below says, and that layout wins over this rule."
        " A Plan my shoot plan is at most 5 steps plus one question to confirm it." in TEXT
    )
    assert (
        "then the settings), at most 5 steps, each from the knowledge and named, then one question"
        " to confirm it works for their room." in TEXT
    )


def test_shooting_instructions_are_grounded_with_no_general_fallback():
    assert "Shooting instructions are grounded, with no general fallback." in TEXT
    assert "comes from an entry in your knowledge block" in TEXT
    assert "is said exactly as that entry states it: never work one out, convert it or round it" in TEXT
    assert 'If no entry fits, say "this isn\'t in Influora\'s notes" and do not invent a step.' in TEXT
    # The general fallback survives for non-shooting content, and comes first.
    general = "this isn't in Influora's content notes, so this is general advice"
    assert general in TEXT
    assert TEXT.index(general) < TEXT.index("Shooting instructions are grounded")


def test_general_fallback_itself_excludes_shooting_instructions():
    # The fallback bullet carries the exception itself, so it never reads as licence to fall
    # back to general knowledge for a shooting step before the grounding rule is reached.
    assert (
        "fall back to general knowledge (except shooting instructions -- for those, say this"
        " isn't in Influora's notes), and say so plainly" in TEXT
    )


# --- the full script's Set-up line -------------------------------------------------


def test_setup_line_sits_between_action_and_success_looks_like():
    section = _script_section()
    assert SETUP_LINE in section
    action = section.index('Action: what they do on camera while they speak')
    setup = section.index(SETUP_LINE)
    success = section.index('Success looks like: the line for their goal')
    assert action < setup < success
    assert section.count("Set-up:") == 1


def test_setup_label_stays_english_and_comes_from_plan_my_shoot():
    section = _script_section()
    assert "The labels Idea, Plan, Action, Set-up, Success looks like, Script, Caption," in section
    assert "The Set-up line is the Plan my shoot plan in one line" in section
    assert "from their answers and the knowledge only" in section
    assert "Run Plan my shoot before writing the script unless its answers are already known or they said skip." in section


def test_persona_stays_plain_text():
    assert "**" not in MEERA_CREATOR_PERSONA
    assert "camera-left" not in MEERA_CREATOR_PERSONA


# --- reaches creators only; version -------------------------------------------------


def test_rules_reach_the_creator_prompt_and_never_the_brand_prompt():
    creator = _system_text("CREATOR")
    brand = _system_text(None)
    for marker in ("- Plan my shoot.", "Coach style: observe, then suggest, then confirm.", SETUP_LINE):
        assert marker in creator, marker
        assert marker not in brand, marker


def test_prompt_version_bumped_for_plan_my_shoot():
    date, _, n = PROMPT_VERSION.removeprefix("meera-").rpartition(".")
    assert (date, int(n)) >= ("2026.09.25", 1)


def test_version_note_keeps_the_previously_chain():
    import app.config as config_module
    from pathlib import Path

    source = Path(config_module.__file__).read_text(encoding="utf-8")
    note = source[source.index('PROMPT_VERSION = "') : source.index("# Previously (.12):")]
    assert 'PROMPT_VERSION = "meera-2026.09.25.1"' in note
    assert "Plan my shoot" in note
    assert "# Review round (still .25.1, unreleased): fixed the intake budget" in note
    assert "and the grounding checks" in note
    assert "# Previously (.13):" in note
