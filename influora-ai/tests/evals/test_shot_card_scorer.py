"""The shot card proof eval (spec v2 Phase 6): its scorer on canned replies, its dataset, and its
wiring into run_eval.py. No model is called here -- the live run needs the owner's approval.

Canned replies: a good reply with the Shot cards block, the same reply guessing creator facts, an
old reply without the block (the "before" extractor), and replies that break each live rule.
"""

from __future__ import annotations

import dataclasses
import json
import sys
from collections import Counter
from pathlib import Path

import pytest

SERVICE_ROOT = Path(__file__).resolve().parents[2]  # influora-ai/
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.prompt.content_knowledge import COACH_QUESTIONS  # noqa: E402
from evals import run_eval  # noqa: E402
from evals import shot_card_scorer as sc  # noqa: E402

# --------------------------------------------------------------------------- canned replies

HEAD = (
    "Idea: My 30-second vitamin C morning routine\n"
    "Plan: For skincare beginners; calm confidence; grow followers; 30 seconds, vertical 9:16; "
    "Problem-Solution; Curiosity gap.\n"
    "Action: You apply the serum at your vanity while you talk; if you'd rather not be on camera: "
    "hands only, overhead, with voice-over.\n"
    "Set-up: Sit at your vanity with the window in front of you; phone at eye level about 0.8-1 m away "
    "on the 1x lens; 4K 30fps; one spot.\n"
    "Success looks like: More follows from people who save the routine.\n"
    "Script:\n"
    '0-3s. Shot: Eye-level medium close-up - you hold up the serum. Say: "Your serum is working '
    'harder than you think". Stress: harder. Pause: after "serum". On screen: Vitamin C, done well.\n'
    '3-15s. Shot: Close-up - drops on your fingertips. Say: "Three drops, pressed in, never '
    'rubbed". Stress: pressed. Pause: none. On screen: 3 drops only.\n'
    '15-30s. Shot: Medium close-up - you smile at the lens. Say: "Follow for tomorrow\'s step". '
    'Stress: tomorrow\'s. Pause: after "Follow". On screen: Follow for step 2.\n'
)
BLOCK = (
    "Shot cards:\n"
    "S1: size=MCU; height=eye; distance=0.8-1 m; place=Vanity by the window; light=window-front; "
    "stand=centre; headroom=small; eyes=lens; background=plain wall; space=right; text=top; "
    "prop=right-hand; move=still\n"
    "S2: size=CU; height=eye; distance=0.5 m; place=Vanity by the window; light=window-front; "
    "stand=centre; headroom=cropped; eyes=product; background=plain wall; space=none; text=top; "
    "prop=right-hand; move=still\n"
    "S3: size=MCU; height=eye; distance=0.8-1 m; place=Vanity by the window; light=window-front; "
    "stand=centre; headroom=small; eyes=lens; background=plain wall; space=right; text=top; "
    "prop=none; move=still.\n"
)
TAIL = (
    "Caption: What's your morning skincare step? #skincare\n"
    "Before you shoot: 1) Wipe the lens 2) Keep text above the 65% caption line, check your Reel "
    "preview 3) Lock focus on your face\n"
    "Why this works: Curiosity gap: it opens a question the routine answers.\n"
    "Want the voice-over in Hindi?"
)
GOOD_REPLY = HEAD + BLOCK + TAIL
OLD_REPLY = HEAD + TAIL  # the "before" shape: no Shot cards block

X8 = "OPPO Find X8 Ultra"
ALL_FACTS = ["can_move", "on_camera", "prop_ready", "sit_or_walk", "window_side"]
# The tapped answers behind GOOD_REPLY's facts, and a request that names the place and where the
# serum sits. Owner decision D (2026-09-26) gave can_move and prop_ready answers that CAN name a
# place or a surface; these defaults are the ones that name neither a place nor a side
# ("Somewhere else", "In my hand" names only the surface), so the chat still has to.
DEFAULT_ANSWERS = {
    "can_move": "Somewhere else",
    "on_camera": "Face on camera",
    "prop_ready": "In my hand",
    "sit_or_walk": "Sitting",
    "window_side": "In front of me",
}
REQUEST = "Write me a full script for my vitamin C routine at my vanity by the window, serum in my right hand."
QUIET_REQUEST = "Write me a full script for my vitamin C routine."


def case(answered=ALL_FACTS, phone=X8, category="beauty", answers=None, request=REQUEST) -> sc.ShotCardCase:
    if answers is None:
        answers = {qid: DEFAULT_ANSWERS[qid] for qid in answered}
    return sc.ShotCardCase.from_expected(
        {
            "category": category,
            "answered": sorted(answers),
            "phone_model": phone,
            "answers": answers,
            "request": request,
        }
    )


def with_before_item(reply: str, item: str) -> str:
    """The reply with its third Before-you-shoot item replaced (one sentence of new advice)."""
    return reply.replace("3) Lock focus on your face", f"3) {item}")


# --------------------------------------------------------------------------- the wire contract


def test_good_reply_parses_every_card_and_scores_full_marks():
    parsed = sc.parse_reply(GOOD_REPLY)
    assert parsed.has_block and len(parsed.beats) == 3 and set(parsed.cards) == {1, 2, 3}
    assert parsed.cards[1]["light"] == "window-front" and parsed.cards[3]["move"] == "still"  # trailing "." dropped
    score = sc.score_reply(GOOD_REPLY, case())
    assert (score.F, score.S, score.P, score.C) == (100.0, 100.0, 100.0, 100.0)
    assert score.guessed_facts == 0 and score.contradictions == ()
    assert score.total == 100.0
    # R6: the app's own parser (Python twin of parseMeeraScript) must still render it as a card.
    assert score.renders_as_card and score.has_block


@pytest.mark.parametrize(
    "reply",
    [
        GOOD_REPLY,
        GOOD_REPLY.replace("size=MCU; height=eye", "size=mcu; height=EYE"),  # ASCII case folding
        GOOD_REPLY.replace("prop=right-hand; move=still\nS3", "prop=right-hand\nS3"),  # 12 pairs
        GOOD_REPLY.replace("S3:", "S2:"),  # a beat named twice gets no card
        GOOD_REPLY.replace("S3:", "S9:"),  # a beat that does not exist
        GOOD_REPLY.replace("move=still.\n", "move=still;\n"),  # one trailing stop
        GOOD_REPLY.replace("size=MCU; height=eye; distance=0.8-1 m", "size=MCU; height=eye; distance=" + "9" * 21),
    ],
)
def test_scorer_reads_the_same_cards_as_the_app_parser(reply):
    """The scorer scores the cards the app shows: where the app parser renders the reply, the
    scorer's own fallback scan must agree with it card for card (a third parser that drifted
    would score cards no creator ever sees)."""
    assert sc.parse_meera_script(reply) is not None
    assert sc._scan_reply(reply).cards == sc.parse_reply(reply).cards


def test_unknown_enum_and_over_long_free_text_read_as_unknown():
    card = sc.parse_shot_card_line(
        " size=XL; height=eye; distance=about one and a half metres away; place=" + "x" * 41
        + "; light=window-west; stand=middle; headroom=small; eyes=lens; background=wall; space=right;"
        " text=top; prop=right-pocket; move=still"
    )
    assert card is not None
    for key in ("size", "distance", "place", "light", "stand", "prop"):
        assert card[key] == sc.UNKNOWN, key
    assert card["headroom"] == "small" and card["move"] == "still"


def test_malformed_s_line_keeps_the_beat_with_no_card():
    reply = GOOD_REPLY.replace("prop=right-hand; move=still\nS3", "prop=right-hand\nS3")  # S2: 12 pairs
    parsed = sc.parse_reply(reply)
    assert len(parsed.beats) == 3 and set(parsed.cards) == {1, 3}
    assert parsed.malformed_card_lines == 1
    score = sc.score_reply(reply, case())
    assert score.F == pytest.approx(100 * 2 / 3, abs=0.01)  # beat 2 has no card: 0 of 13


@pytest.mark.parametrize(
    "line",
    [
        "size=MCU; height=eye",  # too few pairs
        "height=eye; size=MCU; distance=?; place=?; light=?; stand=?; headroom=?; eyes=?; "
        "background=?; space=?; text=?; prop=?; move=?",  # wrong order
        "size MCU; height=eye; distance=?; place=?; light=?; stand=?; headroom=?; eyes=?; "
        "background=?; space=?; text=?; prop=?; move=?",  # a pair without "="
    ],
)
def test_malformed_lines_are_refused(line):
    assert sc.parse_shot_card_line(line) is None


def test_card_for_a_beat_that_does_not_exist_is_malformed():
    reply = GOOD_REPLY.replace("S3:", "S4:")
    parsed = sc.parse_reply(reply)
    assert set(parsed.cards) == {1, 2} and parsed.malformed_card_lines == 1


# --------------------------------------------------------------------------- F: guessed facts


def test_guessed_creator_fact_is_a_violation_not_a_filled_field():
    """sit_or_walk unanswered: height and move must be "?", so filling them is a guess."""
    score = sc.score_reply(GOOD_REPLY, case(answered=["can_move", "on_camera", "prop_ready", "window_side"]))
    assert score.guessed_facts == 6  # height + move, on each of 3 beats
    assert set(score.guessed) == {f"S{n}.{k}" for n in (1, 2, 3) for k in ("height", "move")}
    assert score.F == pytest.approx(100 * 11 / 13, abs=0.01)
    agg, failures = sc.aggregate([score.as_metrics()])
    assert agg["guessed_facts"] == 6
    assert any("guessed creator fact" in f for f in failures)


def test_question_mark_for_an_unanswered_fact_is_not_a_violation():
    reply = GOOD_REPLY.replace("height=eye", "height=?").replace("move=still", "move=?")
    score = sc.score_reply(reply, case(answered=["can_move", "on_camera", "prop_ready", "window_side"]))
    assert score.guessed_facts == 0
    assert score.F == pytest.approx(100 * 11 / 13, abs=0.01)


def test_saved_phone_answers_distance_and_its_absence_makes_distance_a_guess():
    no_phone = sc.score_reply(GOOD_REPLY, case(phone=None))
    assert {g.split(".")[1] for g in no_phone.guessed} == {"distance"}
    assert sc.score_reply(GOOD_REPLY, case(phone=X8)).guessed_facts == 0


def test_a_light_side_the_answer_does_not_name_is_a_guessed_fact():
    """Priya 2026-09-26: an answered question is not enough, the answer must support the value.
    window_side "To my side" names no left or right, so window-left is a guess; the honest
    "window" loses nothing in P."""
    answers = {**DEFAULT_ANSWERS, "window_side": "To my side"}
    guessed = sc.score_reply(GOOD_REPLY.replace("window-front", "window-left"), case(answers=answers))
    assert {g for g in guessed.guessed if g.endswith(".light")} == {"S1.light", "S2.light", "S3.light"}
    honest = sc.score_reply(GOOD_REPLY.replace("window-front", "window"), case(answers=answers))
    assert honest.guessed_facts == 0 and honest.P == 100.0
    # "In front of me" names the front only: window-left is still a guess there.
    wrong_side = sc.score_reply(GOOD_REPLY.replace("window-front", "window-left"), case())
    assert {g.split(".")[1] for g in wrong_side.guessed} == {"light"}
    # Hindi answers count the same as their English options.
    hindi = {**DEFAULT_ANSWERS, "window_side": "Mere peeche"}
    assert sc.score_reply(GOOD_REPLY.replace("window-front", "window-behind"), case(answers=hindi)).guessed_facts == 0


def test_a_place_or_prop_side_the_chat_never_named_is_a_guessed_fact():
    """can_move "Somewhere else" names no place and prop_ready "In my hand" names no side: only
    what the creator said in the chat does. prop=none on a beat without the product needs
    prop_ready."""
    score = sc.score_reply(GOOD_REPLY, case(request=QUIET_REQUEST))
    assert Counter(g.split(".")[1] for g in score.guessed) == {"place": 3, "prop": 2}
    assert "S3.prop" not in score.guessed  # prop=none, and prop_ready was answered
    no_prop_answer = case(answered=["can_move", "on_camera", "sit_or_walk", "window_side"])
    honest_props = GOOD_REPLY.replace("prop=right-hand", "prop=?")
    assert sc.score_reply(honest_props, no_prop_answer).guessed == ("S3.prop",)
    assert sc.score_reply(honest_props.replace("prop=none", "prop=?"), no_prop_answer).guessed_facts == 0


def test_guessing_on_the_dataset_case_is_vetoed_not_rewarded():
    """Priya's repro on sc-01 (now can_move "By the window", window_side "To my side", prop_ready
    "In my hand" after owner decision D): the guessed card was +16 points and never a guessed
    fact. "By the window" supports a window place, never "Bedroom desk"; "In my hand" supports
    the surface, never the side."""
    expected = next(c["expected"] for c in run_eval.load_dataset("shot_card_plan") if c["id"].startswith("sc-01"))
    sc01 = sc.ShotCardCase.from_expected(expected)
    honest = GOOD_REPLY.replace("light=window-front", "light=window").replace(
        "place=Vanity by the window", "place=?"
    ).replace("prop=right-hand", "prop=?")
    invented = GOOD_REPLY.replace("light=window-front", "light=window-left").replace(
        "place=Vanity by the window", "place=Bedroom desk"
    )
    honest_score = sc.score_reply(honest, sc01)
    invented_score = sc.score_reply(invented, sc01)
    assert honest_score.guessed_facts == 0 and honest_score.P == 100.0
    assert {g.split(".")[1] for g in invented_score.guessed} == {"light", "place", "prop"}
    assert invented_score.F < 100.0 and invented_score.P == honest_score.P
    _, failures = sc.aggregate([invented_score.as_metrics()])
    assert any("guessed creator fact" in f for f in failures)


def test_a_tapped_option_that_names_a_place_or_a_prop_spot_supports_it():
    """Owner decision D: can_move "By the window" / "At my desk" / "Outside" name the place, and
    prop_ready "In my hand" / "On a table" plus product_side "Left" / "Centre" / "Right" name the
    prop spot -- in the tapped words, so the value is the creator's, not a guess."""
    answers = {**DEFAULT_ANSWERS, "can_move": "By the window", "product_side": "Right"}
    tapped = case(answers=answers, request=QUIET_REQUEST)
    reply = GOOD_REPLY.replace("place=Vanity by the window", "place=By the window")
    assert sc.score_reply(reply, tapped).guessed_facts == 0
    # The side needs product_side (or the chat): without it the same card guesses the side.
    no_side = case(answers={k: v for k, v in answers.items() if k != "product_side"}, request=QUIET_REQUEST)
    assert {g.split(".")[1] for g in sc.score_reply(reply, no_side).guessed} == {"prop"}
    # A table is not a hand: the tapped surface decides.
    on_table = case(answers={**answers, "prop_ready": "On a table"}, request=QUIET_REQUEST)
    assert {g.split(".")[1] for g in sc.score_reply(reply, on_table).guessed} == {"prop"}
    assert sc.score_reply(reply.replace("prop=right-hand", "prop=right-table"), on_table).guessed_facts == 0
    # "At my desk" does not support a window.
    desk = case(answers={**answers, "can_move": "At my desk"}, request=QUIET_REQUEST)
    assert {g.split(".")[1] for g in sc.score_reply(reply, desk).guessed} == {"place"}


# --------------------------------------------------------------------------- the Made for line


MADE_FOR_AVAILABLE = "18-24 61%, 25-34 27%; women 72%, men 26%; top cities Mumbai, Pune (as of 2026-09-20)"


def with_made_for(reply: str, value: str) -> str:
    return reply.replace("\n", "\nMade for: " + value + "\n", 1)


def test_made_for_audience_facts_from_the_audience_line_keep_c():
    good = with_made_for(
        GOOD_REPLY,
        "your followers - mostly women, 18-24, Mumbai (Instagram) \u00b7 Topic: Mumbai street "
        "breakfast (your best-performing topic) \u00b7 Goal: grow followers",
    )
    assert sc.made_for_lines(good) and sc.parse_meera_script(good).made_for
    score = sc.score_reply(good, dataclasses.replace(case(), audience_summary=MADE_FOR_AVAILABLE))
    assert score.C == 100.0 and score.contradictions == ()


@pytest.mark.parametrize(
    "value, reason",
    [
        ("your followers - mostly women, 35-44 (Instagram) \u00b7 Topic: serum \u00b7 Goal: sell", "made_for_age:35-44"),
        ("your followers - 80% women \u00b7 Topic: serum \u00b7 Goal: sell", "made_for_share:80%"),
        ("your followers - mostly men, Mumbai \u00b7 Topic: serum \u00b7 Goal: sell", None),
        ("your followers in Delhi \u00b7 Topic: serum \u00b7 Goal: sell", "made_for_city:delhi"),
    ],
)
def test_a_made_for_audience_fact_the_line_does_not_hold_breaks_c(value, reason):
    """Every fact is checked against the case's own audience line: an age band, a share or a city
    it does not hold is a contradiction. ("men" IS in that line: 26%.)"""
    reply = with_made_for(GOOD_REPLY, value)
    score = sc.score_reply(reply, dataclasses.replace(case(), audience_summary=MADE_FOR_AVAILABLE))
    if reason is None:
        assert score.C == 100.0, score.contradictions
    else:
        assert score.C == 0.0 and reason in score.contradictions


def test_without_an_audience_every_made_for_audience_fact_is_a_guess():
    """No audience line (or a "not available" one): gender, age and city are all guesses -- the
    persona's rule "never guess gender/age from a name or a photo" -- while the honest
    not-available wording, with a city only in the TOPIC, keeps C."""
    guessed = with_made_for(GOOD_REPLY, "your followers - mostly women, 18-24, Mumbai \u00b7 Topic: serum")
    for summary in (None, "not available: Instagram is connected, but audience details have not arrived yet"):
        score = sc.score_reply(guessed, dataclasses.replace(case(), audience_summary=summary))
        assert set(score.contradictions) >= {"made_for_gender:women", "made_for_age:18-24", "made_for_city:mumbai"}
    honest = with_made_for(
        GOOD_REPLY,
        "your audience data isn't available yet, so this is written for a general audience \u00b7 "
        "Topic: Mumbai street breakfast (your pick) \u00b7 Goal: grow followers",
    )
    assert sc.score_reply(honest, case()).C == 100.0


def test_a_hindi_made_for_line_is_judged_the_same_way():
    hindi = with_made_for(
        GOOD_REPLY,
        "\u0906\u092a\u0915\u0947 followers - \u092e\u0939\u093f\u0932\u093e\u090f\u0901, "
        "\u0926\u093f\u0932\u094d\u0932\u0940 \u00b7 Topic: \u0915\u0947\u0938\u0930",
    )
    score = sc.score_reply(hindi, dataclasses.replace(case(), audience_summary=MADE_FOR_AVAILABLE))
    assert "made_for_city:\u0926\u093f\u0932\u094d\u0932\u0940" in score.contradictions
    assert not any(r.startswith("made_for_gender") for r in score.contradictions)


def test_the_made_for_check_reaches_the_dataset_cases():
    """The dataset carries an available and a not-available audience line, and the live run's
    creator context sends the line the scorer judges against (one source for both)."""
    cases = {c["id"]: c for c in run_eval.load_dataset("shot_card_plan")}
    with_line = [c for c in cases.values() if c["input"].get("audience_summary")]
    assert any(sc.audience_available(c["input"]["audience_summary"]) for c in with_line)
    assert any(not sc.audience_available(c["input"]["audience_summary"]) for c in with_line)
    for c in with_line:
        assert c["expected"]["audience_summary"] == c["input"]["audience_summary"], c["id"]
        assert sc.build_creator_context(c["input"])["audience_summary"] == c["input"]["audience_summary"]
        assert sc.ShotCardCase.from_expected(c["expected"]).audience_summary == c["input"]["audience_summary"]
    plain = next(c for c in cases.values() if not c["input"].get("audience_summary"))
    assert "audience_summary" not in sc.build_creator_context(plain["input"])


def test_every_creator_fact_maps_to_real_coach_questions():
    for fact, sources in sc.FACT_SOURCES.items():
        for source in sources:
            assert source == sc.SAVED_PHONE or source in COACH_QUESTIONS, (fact, source)
    craft = set(sc.FIELD_ORDER) - set(sc.FACT_SOURCES)
    assert craft == {"size", "stand", "headroom", "background", "space", "text"}


# --------------------------------------------------------------------------- the old reply (before)


def test_old_reply_without_block_is_scored_by_the_fixed_extractor():
    parsed = sc.parse_reply(OLD_REPLY)
    assert not parsed.has_block and len(parsed.beats) == 3
    fields = sc.beat_fields(parsed, 1)
    assert fields["size"] == "MCU" and fields["height"] == "eye" and fields["distance"] == "0.8-1 m"
    assert fields["place"] == "vanity" and fields["light"] == "window-front" and fields["move"] == "sit"
    assert sc.beat_fields(parsed, 2)["size"] == "CU"
    score = sc.score_reply(OLD_REPLY, case())
    assert score.renders_as_card and not score.has_block
    assert 0 < score.F < 100 and score.S == 100.0 and score.C == 100.0
    assert score.guessed_facts == 0


def test_old_reply_guessing_the_window_side_is_caught_by_the_extractor():
    no_light_answers = ["can_move", "on_camera", "prop_ready", "sit_or_walk"]
    score = sc.score_reply(OLD_REPLY, case(answered=no_light_answers))
    assert {g.split(".")[1] for g in score.guessed} == {"light"}
    assert score.guessed_facts == 3


def test_a_question_instead_of_a_script_scores_zero():
    score = sc.score_reply("Will you sit, stand, or walk between spots? (Sitting / Standing)", case())
    assert score.beats == 0 and score.total == 0.0 and not score.renders_as_card
    _, failures = sc.aggregate([score.as_metrics()])
    assert any("renders_as_card" in f for f in failures)


# --------------------------------------------------------------------------- S: category sizes


def test_fitness_form_beat_wants_a_body_shot():
    reply = OLD_REPLY.replace("Close-up - drops on your fingertips", "Close-up - you squat with full form")
    parsed = sc.parse_reply(reply)
    beat = parsed.beats[1]
    assert sc.allowed_sizes("fitness", beat) == frozenset({"MLS", "FS"})
    assert sc.score_reply(reply, case(category="fitness")).S == pytest.approx(100 * 2 / 3, abs=0.01)


def test_size_rules_read_the_action_not_the_size_words():
    beat = sc.Beat(shot="Wide establishing shot - you explain the chart", say="x", on_screen="y")
    assert sc.allowed_sizes("travel", beat) == sc.SIZE_RULES["travel"][1]  # "wide" is not an action
    assert sc.allowed_sizes("finance_education", beat) >= {"CU"}


def test_every_dataset_category_has_size_rules():
    assert set(sc.SIZE_RULES) == set(sc.CATEGORY_KEYS)
    for category, (_, default) in sc.SIZE_RULES.items():
        assert default <= sc._ENUMS["size"], category


# --------------------------------------------------------------------------- C: contradictions


@pytest.mark.parametrize(
    ("item", "phone", "reason"),
    [
        ("Leave exactly two fingers of space above your head", X8, "exact_two_fingers"),
        ("Stand camera left so the serum shows", X8, "viewer_side"),
        ("Keep the product on the left of the frame", X8, "viewer_side"),
        ("Shoot it in 8K for the crispest detail", X8, "8k_on_phone_without_it"),
        ("Record in LOG and grade it later", X8, "log_on_phone_without_it"),
        ("Keep text out of the top 15% of the frame, check your Reel preview", X8, "safe_zone_number:15%"),
        ("Keep the bottom 35% clear of text, check your preview", X8, "safe_zone_number:35%"),
        ("Borrow the grid from @alessiolr", X8, "creator_handle:@alessiolr"),
        ("Use the grid mansourmelouli shares", X8, "named_account:mansourmelouli"),
        ("Use the 3x telephoto for a tighter frame", "OPPO A78 5G", "setting_does_not_fit_phone"),
    ],
)
def test_each_live_rule_breaks_c(item, phone, reason):
    reply = with_before_item(GOOD_REPLY, item)
    score = sc.score_reply(reply, case(phone=phone))
    assert score.C == 0.0
    assert any(r.startswith(reason) for r in score.contradictions), score.contradictions
    _, failures = sc.aggregate([score.as_metrics()])
    assert any(f.startswith("C ") for f in failures)


def test_safe_zone_number_without_the_preview_caveat_breaks_c():
    reply = GOOD_REPLY.replace(", check your Reel preview", "")
    assert "safe_zone_without_preview_caveat" in sc.score_reply(reply, case()).contradictions


@pytest.mark.parametrize(
    "item",
    [
        "Leave about two fingers of headroom",
        "Keep the ring light on your left",
        "Keep text above the 65% caption line and out of the top 14%, check your Reel preview",
        "Get the timing right at the start",
        "If your phone has a 3x lens, use it from 2 m",
    ],
)
def test_the_allowed_versions_keep_c(item):
    phone = "Vivo V29" if item.startswith("If your phone") else X8
    reply = GOOD_REPLY.replace("; 4K 30fps", "") if phone != X8 else GOOD_REPLY
    score = sc.score_reply(with_before_item(reply, item), case(phone=phone))
    assert score.C == 100.0, score.contradictions


def test_unconditional_manual_setting_on_a_phone_not_in_the_notes_breaks_c():
    """step_fits_phone, as live: a phone we have no notes on gets "30fps" only as "if your phone..."."""
    score = sc.score_reply(GOOD_REPLY, case(phone="Vivo V29"))
    assert score.contradictions == ("setting_does_not_fit_phone",)


def test_safe_zone_numbers_come_from_the_config_file():
    config = json.loads(sc.SAFE_ZONES_JSON.read_text(encoding="utf-8"))
    top = round(config["platforms"]["instagram_reels"]["default"]["top"] * 100, 1)
    assert top in sc.SAFE_ZONE_PERCENTAGES
    assert 15.0 not in sc.SAFE_ZONE_PERCENTAGES and 25.0 not in sc.SAFE_ZONE_PERCENTAGES
    assert 35.0 not in sc.SAFE_ZONE_PERCENTAGES


# --------------------------------------------------------------------------- the ship rule


def _run(total: float, *, c: float = 100.0, guessed: float = 0.0, cat_totals=None) -> dict:
    cat_totals = cat_totals or {}
    return {
        f"c{i}": {
            "total": cat_totals.get(i, total), "C": c, "guessed_facts": guessed, "renders_as_card": 1.0,
            "category_id": float(i),
        }
        for i in range(len(sc.CATEGORY_KEYS))
    }


def test_ship_verdict_needs_ten_points_and_three_runs_each():
    ok, reasons, summary = sc.ship_verdict([_run(50)] * 3, [_run(61)] * 3)
    assert ok, reasons
    assert summary["gain"] == pytest.approx(11)
    assert not sc.ship_verdict([_run(50)] * 3, [_run(59)] * 3)[0]
    assert not sc.ship_verdict([_run(50)] * 2, [_run(80)] * 3)[0]


def test_ship_verdict_uses_the_median_run():
    ok, _, summary = sc.ship_verdict([_run(50), _run(51), _run(90)], [_run(40), _run(62), _run(63)])
    assert summary["before_total"] == pytest.approx(51) and summary["after_total"] == pytest.approx(62)
    assert ok


def test_ship_verdict_vetoes_a_contradiction_a_guess_or_a_category_drop():
    before = [_run(50)] * 3
    assert not sc.ship_verdict(before, [_run(80), _run(80), _run(80, c=0.0)])[0]
    assert not sc.ship_verdict(before, [_run(80, guessed=1.0)] * 3)[0]
    dropped = _run(80, cat_totals={3: 44.0})  # fitness: 50 -> 44
    ok, reasons, _ = sc.ship_verdict(before, [dropped] * 3)
    assert not ok and any("fitness" in r for r in reasons)


# --------------------------------------------------------------------------- the dataset


def test_dataset_shape_matches_the_spec():
    cases = run_eval.load_dataset("shot_card_plan")
    assert len(cases) == 20
    counts = Counter(c["expected"]["category"] for c in cases)
    assert counts == {k: (1 if k in ("groups", "motivational") else 2) for k in sc.CATEGORY_KEYS}
    assert {c["input"]["creator_language"] for c in cases} == {"en-IN", "hi-IN"}
    for c in cases:
        inp, exp = c["input"], c["expected"]
        assert inp["phone_model"] and exp["phone_model"] == inp["phone_model"], c["id"]
        assert exp["answered"] == sorted(inp["coach_answers"]), c["id"]
        # The scorer judges each value against the tapped answer and the request, not only
        # whether the question was answered, so it carries both, exactly as the chat replays them.
        assert exp["answers"] == inp["coach_answers"] and exp["request"] == inp["request"], c["id"]
        assert len(inp["categories"]) == 1, c["id"]  # one category: no "Which category today?" turn
        sc.ShotCardCase.from_expected(exp)
        for qid, answer in inp["coach_answers"].items():
            row = COACH_QUESTIONS[qid]
            assert answer in (row["options_hi"] if inp["creator_language"].startswith("hi") else row["options"]), (
                c["id"], qid, answer
            )


def test_dataset_leaves_creator_facts_unanswered_on_purpose():
    cases = [sc.ShotCardCase.from_expected(c["expected"]) for c in run_eval.load_dataset("shot_card_plan")]
    missing = Counter(f for cs in cases for f in sc.FACT_SOURCES if not cs.fact_known(f))
    assert sum(1 for cs in cases if any(not cs.fact_known(f) for f in sc.FACT_SOURCES)) >= 15
    for fact in ("place", "light", "height", "move", "eyes", "prop"):
        assert missing[fact] >= 3, (fact, missing)
    assert any(all(cs.fact_known(f) for f in sc.FACT_SOURCES) for cs in cases)  # and some complete


def test_phones_cover_the_notes_and_unknown_phones():
    phones = [c["input"]["phone_model"] for c in run_eval.load_dataset("shot_card_plan")]
    assert phones.count(X8) >= 3
    assert any(p.startswith("OPPO A78") for p in phones)
    assert sum(1 for p in phones if not p.startswith("OPPO")) >= 3


def test_live_conversation_replays_answers_in_the_creators_language():
    cases = {c["id"]: c["input"] for c in run_eval.load_dataset("shot_card_plan")}
    turns = sc.build_conversation(cases["sc-02-beauty-eyeliner-hi"])
    assert turns[0] == {"role": "user", "content": cases["sc-02-beauty-eyeliner-hi"]["request"]}
    assert turns[1]["role"] == "assistant" and COACH_QUESTIONS["window_side"]["question_hi"] in turns[1]["content"]
    assert turns[2] == {"role": "user", "content": "Mere saamne"}
    assert turns[-1] == {"role": "user", "content": sc.CLOSING_LINE["hi"]}
    assert len(turns) == 2 + 2 * 2
    context = sc.build_creator_context(cases["sc-02-beauty-eyeliner-hi"])
    assert context["tools_enabled"] == [] and context["phone_model"] == "OPPO Reno 14 Pro"


# --------------------------------------------------------------------------- run_eval wiring


def test_run_dataset_scores_every_case_through_the_feature():
    good_every_time = {"response": GOOD_REPLY, "stop_reason": "end_turn"}
    report = run_eval.run_dataset("shot_card_plan", lambda case_input: good_every_time)
    assert len(report.case_scores) == 20 and not report.errors
    assert {"F", "S", "P", "C", "total", "guessed_facts", "category_id"} <= set(
        next(iter(report.case_scores.values()))
    )
    # The same reply for every creator guesses facts for the ones who did not answer: red.
    assert report.aggregate["guessed_facts"] > 0 and not report.passed


def test_live_run_refuses_without_the_owners_approval(monkeypatch, capsys):
    """~120 paid calls: `--live` must not spend them unless SHOT_CARD_EVAL_APPROVED=1."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "not-a-real-key")
    monkeypatch.delenv("SHOT_CARD_EVAL_APPROVED", raising=False)

    def must_not_build():
        raise AssertionError("live caller built without approval")

    feature = run_eval.FEATURES["shot_card_plan"]
    monkeypatch.setitem(
        run_eval.FEATURES,
        "shot_card_plan",
        run_eval.Feature(
            feature.name, feature.scorer, feature.aggregator, must_not_build,
            feature.required_env_key, feature.live_opt_in_env,
        ),
    )
    code = run_eval.main(["--live", "shot_card_plan"])
    out = capsys.readouterr().out
    assert code == 3 and "SHOT_CARD_EVAL_APPROVED" in out and "PASS" not in out


def test_save_scores_writes_what_the_ship_verdict_reads(monkeypatch, tmp_path):
    monkeypatch.setattr(
        run_eval, "make_offline_caller", lambda name: (lambda case_input: {"response": OLD_REPLY})
    )
    out = tmp_path / "before-1.json"
    run_eval.main(["--offline", "shot_card_plan", "--save-scores", str(out)])
    scores = sc.load_saved_scores(out)
    assert len(scores) == 20 and all("total" in s for s in scores.values())


# --------------------------------------------------------------------------- the before arm


PERSONA_SOURCE = SERVICE_ROOT / "app" / "prompt" / "creator_persona.py"
OLD_PERSONA_SOURCE = 'X = 1\nMEERA_CREATOR_PERSONA = """\\\nOLD RULES ONLY, no card block.\n"""\n'


def test_before_arm_reads_an_older_persona_without_importing_it(monkeypatch):
    """Priya 2026-09-26: the before arm must be runnable on THIS branch (the old commit has no
    harness). It takes only the old persona text, by `ast`, never by importing old code."""
    from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

    assert run_eval.persona_from_source(PERSONA_SOURCE.read_text(encoding="utf-8")) == MEERA_CREATOR_PERSONA
    shown: list[tuple[str, str]] = []
    monkeypatch.setattr(run_eval, "_git_show", lambda ref, path: shown.append((ref, path)) or OLD_PERSONA_SOURCE)
    monkeypatch.setenv(run_eval.SHOT_CARD_PERSONA_REF_ENV, "96f37fb6")
    assert run_eval.shot_card_persona() == ("96f37fb6", "OLD RULES ONLY, no card block.\n")
    assert shown == [("96f37fb6", "influora-ai/app/prompt/creator_persona.py")]
    monkeypatch.delenv(run_eval.SHOT_CARD_PERSONA_REF_ENV)
    assert run_eval.shot_card_persona() == ("working tree", MEERA_CREATOR_PERSONA)


def test_before_arm_swaps_only_the_persona_in_the_real_prompt():
    case_input = next(c["input"] for c in run_eval.load_dataset("shot_card_plan") if c["id"].startswith("sc-01"))
    from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

    def system_text(prompt) -> str:
        return "\n".join(b.get("text", "") for b in prompt.system_blocks)

    before = system_text(run_eval.assemble_shot_card_prompt(case_input, "OLD RULES ONLY, no card block.\n"))
    after = system_text(run_eval.assemble_shot_card_prompt(case_input, MEERA_CREATOR_PERSONA))
    assert "OLD RULES ONLY, no card block." in before and "Shot cards: then one line per beat" not in before
    assert "Shot cards: then one line per beat" in after
    # Everything but Block A's rules half is the same prompt.
    assert before.replace("OLD RULES ONLY, no card block.\n", "") == after.replace(MEERA_CREATOR_PERSONA, "")


def test_the_verdict_refuses_before_and_after_with_the_same_persona(monkeypatch, tmp_path, capsys):
    """Recording "before" on this branch without the persona ref would compare the new prompt
    with itself (about 60 paid calls for nothing): the saved runs carry the persona sha256 and
    the verdict refuses a pair that shares one, or a run that carries none."""
    monkeypatch.setattr(run_eval, "make_offline_caller", lambda name: (lambda case_input: {"response": OLD_REPLY}))
    monkeypatch.delenv(run_eval.SHOT_CARD_PERSONA_REF_ENV, raising=False)
    after = tmp_path / "after.json"
    run_eval.main(["--offline", "shot_card_plan", "--save-scores", str(after)])
    same = tmp_path / "same.json"
    run_eval.main(["--offline", "shot_card_plan", "--save-scores", str(same)])
    monkeypatch.setattr(run_eval, "_git_show", lambda ref, path: OLD_PERSONA_SOURCE)
    monkeypatch.setenv(run_eval.SHOT_CARD_PERSONA_REF_ENV, "96f37fb6")
    before = tmp_path / "before.json"
    run_eval.main(["--offline", "shot_card_plan", "--save-scores", str(before)])

    scores, identity = sc.load_saved_run(before)
    assert len(scores) == 20 and identity["persona_ref"] == "96f37fb6"
    assert sc.load_saved_run(after)[1]["persona_ref"] == "working tree"
    capsys.readouterr()

    assert sc.main(["--before", *[str(same)] * 3, "--after", *[str(after)] * 3]) == 1
    assert "same creator persona" in capsys.readouterr().out
    assert sc.main(["--before", *[str(before)] * 3, "--after", *[str(after)] * 3]) == 1  # no gain: still no
    assert "same creator persona" not in capsys.readouterr().out

    bare = tmp_path / "bare.json"
    bare.write_text(json.dumps({"case_scores": scores}), encoding="utf-8")
    assert sc.main(["--before", *[str(bare)] * 3, "--after", *[str(after)] * 3]) == 1
    assert "no persona sha256" in capsys.readouterr().out


def test_ship_verdict_checks_persona_ids_when_given():
    ok, _, _ = sc.ship_verdict([_run(50)] * 3, [_run(61)] * 3, before_ids=["a"] * 3, after_ids=["b"] * 3)
    assert ok
    ok, reasons, _ = sc.ship_verdict([_run(50)] * 3, [_run(61)] * 3, before_ids=["a"] * 3, after_ids=["a", "b", "b"])
    assert not ok and any("same creator persona" in r for r in reasons)
    ok, reasons, _ = sc.ship_verdict([_run(50)] * 3, [_run(61)] * 3, before_ids=["a", None, "a"], after_ids=["b"] * 3)
    assert not ok and any("no persona sha256" in r for r in reasons)
