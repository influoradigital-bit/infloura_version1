"""Shot cards in the full script, built from the creator's answers (spec v2 Phase 6, owner
decisions 1 and 2, Swapnil 2026-09-26; PROMPT_VERSION stays meera-2026.09.25.9).

A full script gains an optional "Shot cards:" block after the last beat and before "Caption:":
one "S<n>: " line per beat with 13 key=value pairs in a fixed order. The creator-fact fields
come only from the answers to the 10 coach questions, their saved phone or what they said; the
craft fields come from the category's framing topic; anything unknown is "?", shown as "Not set
yet". The 7-day challenge buttons ask one "Which category today?" question first when the
creator has several categories.

These tests pin the prompt TEXT against the wire contract (key order, enum values, free-text
limits), the coach bank in the knowledge file and the challenge buttons' prefill text in the
app. They do not prove the live model fills the card or stops at three questions.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import COACH_QUESTIONS
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

# The wire contract, as the builders were given it (all parsers code against exactly this).
CARD_KEYS = (
    "size", "height", "distance", "place", "light", "stand", "headroom",
    "eyes", "background", "space", "text", "prop", "move",
)
ENUMS = {
    "size": ["ECU", "CU", "MCU", "MS", "MLS", "FS", "LS", "OVERHEAD"],
    "height": ["eye", "chest", "above", "below", "overhead"],
    "stand": ["left", "centre", "right"],
    "headroom": ["cropped", "small", "medium"],
    "eyes": ["lens", "product", "off_lens"],
    "space": ["left", "right", "top", "none"],
    "text": ["top", "opposite_face", "lower_middle", "none"],
    "move": ["still", "sit", "stand", "walk", "pan", "push"],
}
LIGHT_KINDS = ["window", "sun", "shade", "lamp", "ring_light", "tube_light", "mixed"]
LIGHT_SIDES = ["left", "right", "front", "behind"]
PROP_SIDES = ["left", "centre", "right"]
PROP_SURFACES = ["hand", "table", "floor"]
FREE_TEXT_MAX = {"distance": 20, "place": 40, "background": 40}

# Owner decision 1: which coach question fills which creator-fact field.
FACT_SOURCES = {
    "place": ["can_move"],
    "light": ["window_side", "other_light", "outdoor_light"],
    "height": ["sit_or_walk"],
    "move": ["sit_or_walk"],
    "distance": ["room_size", "phone_lens"],
    "eyes": ["on_camera"],
    "prop": ["prop_ready"],
}
CRAFT_KEYS = ("size", "stand", "headroom", "background", "space", "text")
COACH_IDS = {
    "other_light", "can_move", "room_size", "window_side", "phone_lens",
    "on_camera", "sit_or_walk", "outdoor_light", "prop_ready", "time_available",
}

SCRIPT_SECTION_START = "Full script format (only when asked):"
SCRIPT_SECTION_END = "Profile review format (only when asked to review their profile):"
CHALLENGE_CARD = (
    Path(__file__).resolve().parents[3] / "src" / "components" / "creator" / "challenge" / "ChallengeCard.tsx"
)


def _flat(text: str) -> str:
    return " ".join(text.split())


TEXT = _flat(MEERA_CREATOR_PERSONA)


def _bullet(start_marker: str) -> str:
    start = TEXT.index(start_marker)
    return TEXT[start : TEXT.index(" - ", start + 1)]


def _script_section() -> str:
    return TEXT[TEXT.index(SCRIPT_SECTION_START) : TEXT.index(SCRIPT_SECTION_END)]


def _card_rule() -> str:
    return _bullet("- Shot cards are built from the creator's answers, never guessed.")


def _facts_part() -> str:
    rule = _card_rule()
    return rule[rule.index("Their facts come only from") : rule.index("The craft choices come from")]


def _craft_part() -> str:
    rule = _card_rule()
    return rule[rule.index("The craft choices come from") : rule.index("When they next ask for a")]


def _template_line() -> str:
    lines = [ln.strip() for ln in MEERA_CREATOR_PERSONA.splitlines() if ln.strip().startswith("S1: ")]
    assert len(lines) == 1, lines
    return lines[0]


def _or_list(values: list[str]) -> str:
    return ", ".join(values[:-1]) + " or " + values[-1]


# --- the block's wire format ---------------------------------------------------------


def test_template_line_has_the_13_contract_keys_in_order():
    line = _template_line()
    assert line.startswith("S1: ")
    pairs = line[len("S1: ") :].split("; ")
    assert [p.split("=", 1)[0] for p in pairs] == list(CARD_KEYS)
    assert all(p == f"{k}=<{k}>" for p, k in zip(pairs, CARD_KEYS))
    # No closing full stop and no outer quotes: "move=still." would be an unknown enum value.
    assert not line.endswith(".") and '"' not in line


def test_block_sits_after_the_beats_and_before_caption_in_the_layout():
    section = _script_section()
    beats = section.index("Script: then one line per beat")
    block = section.index("Shot cards: then one line per beat, in beat order, S1 for the first beat,")
    template = section.index(_template_line())
    caption = section.index("Caption: one caption that carries the conversation question")
    assert beats < block < template < caption
    assert "exactly like this line, with no quotes and no full stop at the end:" in section
    assert section.count("Shot cards: then") == 1


def test_every_enum_is_listed_exactly_as_the_contract_says():
    rule = _card_rule()
    for key, values in ENUMS.items():
        found = re.findall(rf"\b{key} \(([^)]*)\)", rule)
        assert len(found) == 1, (key, found)
        listed = [v for v in found[0].split(", ") if v != "their own"]
        assert listed == values, (key, listed)
    assert f"as {_or_list(LIGHT_KINDS)}, adding {_or_list(['-' + s for s in LIGHT_SIDES])} (their own side)" in rule
    assert "only when the answer names it, like window-front" in rule
    assert (
        f"as their own {_or_list(PROP_SIDES)} plus {_or_list(['-' + s for s in PROP_SURFACES])}, like right-hand,"
        " or none when they answered prop_ready and the beat shows no product;" in rule
    )
    # Owner decision 1 (Priya 2026-09-26, both sides agree with the eval's guessed-fact veto):
    # with no prop_ready answer even "none" is a guess, and no option names a side or surface.
    assert "with no prop_ready answer it is ?, and so is a side or surface they did not name." in rule


def test_free_text_limits_match_the_contract():
    rule = _card_rule()
    for key, limit in FREE_TEXT_MAX.items():
        assert re.search(rf"\b{key} \([^)]*at most {limit} characters", rule), key
    assert "place and background are in the creator's language" in rule
    assert "The label Shot cards, the keys and the listed values stay in English even in Hindi" in rule


def test_unknown_is_a_question_mark_shown_as_not_set_yet():
    rule = _card_rule()
    assert 'Write ? for any value you do not know; the app shows it as "Not set yet".' in rule
    assert "never guessed" in rule


# --- owner decision 1: facts from answers, craft from the framing topic --------------


def test_each_creator_fact_names_its_coach_questions():
    facts = _facts_part()
    assert facts.startswith(
        "Their facts come only from their answers to the coach questions, their saved phone or"
        " what they told you in this chat:"
    )
    # No can_move option names a place, so the card's place is only one they named themselves.
    assert (
        "place (where they will shoot, at most 40 characters) from can_move, and only a spot they"
        " named themselves, never one you picked;" in facts
    )
    assert "light from window_side, other_light or outdoor_light," in facts
    assert "from sit_or_walk;" in facts and "height (" in facts and "move (" in facts
    assert "from room_size, phone_lens or their saved phone;" in facts
    assert "eyes (lens, product, off_lens) from on_camera;" in facts
    assert "prop from prop_ready and where they said the product sits," in facts
    named = set(re.findall(r"\b[a-z]+_[a-z_]+\b", facts)) & COACH_IDS
    assert named == {qid for ids in FACT_SOURCES.values() for qid in ids}
    for key in FACT_SOURCES:
        assert re.search(rf"\b{key} ", facts), key
    for key in CRAFT_KEYS:
        assert not re.search(rf"\b{key} \(", facts), key


def test_the_named_coach_questions_exist_in_the_knowledge_bank():
    # The other side of the seam: the ids the persona names are the rows the model is shown.
    assert set(COACH_QUESTIONS) == COACH_IDS
    for ids in FACT_SOURCES.values():
        for qid in ids:
            assert qid in COACH_QUESTIONS, qid


def test_craft_fields_come_from_the_framing_topic_only():
    craft = _craft_part()
    assert craft.startswith(
        "The craft choices come from their category's framing topic, looked up as Framing first says:"
    )
    for key in CRAFT_KEYS:
        assert re.search(rf"\b{key} \(", craft), key
    for key in FACT_SOURCES:
        assert not re.search(rf"\b{key} \(", craft), key
    assert not set(re.findall(r"\b[a-z]+_[a-z_]+\b", craft)) & COACH_IDS
    assert "matching the beat's Shot" in craft
    # The lookup it points at is the existing Framing first rule.
    assert "- Framing first. For a shoot plan (Plan my shoot), look up the creator's framing topic" in TEXT


# --- the question budget still wins ------------------------------------------------


def test_plan_my_shoot_fills_unknown_card_fields_first_inside_the_budget():
    bullet = _bullet("- Plan my shoot.")
    assert (
        "Pick first the ones that fill a shot card field that is still ? (see Shot cards below),"
        " then the ones whose answer would change your steps the most" in bullet
    )
    assert (
        "at most 3 questions in TOTAL for that request, counting any already asked for it in this"
        " conversation." in bullet
    )
    assert (
        "This budget is the rule that wins: where any other sentence here seems to allow more"
        " questions, ask fewer." in bullet
    )
    assert "never a question of your own: one per message," in bullet


def test_the_card_rule_asks_only_next_time_and_only_inside_the_budget():
    rule = _card_rule()
    next_time = (
        "When they next ask for a plan or a script, ask the coach question for a field that is"
        " still ?, inside the question budget, instead of filling it in."
    )
    assert next_time in rule
    # Its only question-asking is that sentence; it never opens a new round of questions.
    assert not re.search(r"\bask", rule.replace(next_time, ""))


def test_skip_leaves_unanswered_fields_unknown():
    bullet = _bullet("- Plan my shoot.")
    assert '"Skip", "jaldi batao" and anything like them mean stop asking and plan NOW' in bullet
    assert (
        "say those defaults in one line, then give the plan. A default is never their answer:"
        " the shot card fields it covers stay ?." in bullet
    )


# --- owner decision 2: the challenge buttons' one category question ----------------


def test_challenge_post_asks_which_category_once_then_writes():
    rule = _bullet("- Today's challenge post.")
    assert "have more than one category, and this conversation has not settled which one" in rule
    assert 'ask ONE question first, in their language: "Which category today?"' in rule
    assert "with their categories as the ready options" in rule
    assert "the goal in the same message only when it is unknown" in rule
    assert "That one message is all you ask for this request: once they answer, write it" in rule
    assert "any shot card field you still do not know stays ?" in rule
    assert '"Skip" and anything like it still mean answer now on the Skip override defaults, said in one line.' in rule
    # The Skip override it points at still exists, with the category default.
    assert "their first or strongest category, a Reel, and the grow-followers goal" in TEXT
    # Never a second message of questions, and never the plan-my-shoot per-message wording.
    assert "per message" not in rule


def test_challenge_rule_quotes_the_apps_own_prefill_text():
    if not CHALLENGE_CARD.is_file():
        pytest.fail(f"{CHALLENGE_CARD} not found -- run inside a full-repo checkout (a skip is a vacuous pass)")
    source = CHALLENGE_CARD.read_text(encoding="utf-8")
    assert "script: `Write me a script for today's ${typeLabel.toLowerCase()}" in source
    assert "idea: `Give me an idea for today's ${typeLabel.toLowerCase()}.`" in source
    rule = _bullet("- Today's challenge post.")
    assert "\"Write me a script for today's post\" or \"Give me an idea for today's post\", or the same in Hindi" in rule


def test_challenge_rule_sits_outside_the_idea_intake_and_plan_my_shoot():
    idea = TEXT[TEXT.index("Content idea intake.") : TEXT.index("- Short video only.")]
    assert "Which category today?" not in idea
    assert "Which category today?" not in _bullet("- Plan my shoot.")
    assert TEXT.count("Which category today?") == 1


# --- reaches creators only ---------------------------------------------------------


def _system_text(audience: str | None) -> str:
    request: dict = {"workspace_id": "shot-001", "conversation": [{"role": "user", "content": "full script do"}]}
    if audience == "CREATOR":
        request["audience"] = "CREATOR"
        request["creator"] = {
            "workspace_id": "shot-001",
            "display_name": "Asha Rao",
            "first_name": "Asha",
            "categories": ["Food", "Travel"],
            "creator_language": "hi-IN",
        }
    prompt = assemble_prompt(request, session_id="s-shot")
    return _flat("\n".join(b["text"] for b in prompt.system_blocks))


def test_rules_reach_the_creator_prompt_and_never_the_brand_prompt():
    creator = _system_text("CREATOR")
    brand = _system_text(None)
    for marker in (_template_line(), "- Shot cards are built from the creator's answers", "Which category today?"):
        assert marker in creator, marker
        assert marker not in brand, marker


def test_persona_stays_plain_text():
    assert "**" not in MEERA_CREATOR_PERSONA
    assert "escr" + "ow" not in MEERA_CREATOR_PERSONA.lower()
    assert "co-pilot" not in MEERA_CREATOR_PERSONA.lower()
