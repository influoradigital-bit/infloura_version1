"""The photo check's text is written by code (2026-09-25, "the AI picks, the code writes").

`app/prompt/frame_check_render.py` writes every sentence of the photo check from Influora's
knowledge rows and fixed templates; the model only picks ids and enum values. What this pins:

(a) EXHAUSTIVE SAFETY: every row a step can cite, under every kind that may cite it, for no
    phone and for every phone row, in both languages and with every side: the rendered step
    passes frame_check's own gates -- `step_fits_phone`, `step_numbers_grounded` against that
    ONE row, no growth wording -- and is at most 320 characters; it names no lens (at that
    factor), manual control, OIS or HDR the phone row marks No/None, and one the phone MAY
    have only in a conditional sentence. The raw row advice FAILS those gates for some rows
    (so the renderer, not luck, is what makes this pass);
(b) every citable row type is either rendered (`STEP_TEXT_TYPES`) or listed here with the
    reason it is not;
(c) every OK / CANT_TELL line and every what_i_see (every value of every scene field, with the
    other fields varied, in both languages) is description-only, non-empty, and says nothing
    about the person ("face" only as "light on your face");
(d) unknown enum values, unknown ids and unknown kinds are dropped, never rendered -- a
    string the model writes never reaches the output;
(e) the creator-voice lines (`knowledge/creator_step_lines.jsonl`): every line passes the
    writers' checks (numbers grounded in its row's advice, the same numbers and phone features
    in en and hi, no growth / person / third-person words, at most 300 chars); a step IS its
    row's line in the reply's language (a hi step holds none of the row's English); the file
    fails loud at import on a bad line; every sentence-style step row has a line or a listed
    reason (`ROWS_WITHOUT_A_LINE`).
"""

from __future__ import annotations

import json
import random
import re
from typing import Any

import pytest

from app.prompt import frame_check
from app.prompt import frame_check_render as render
from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_ROWS, NAME_FIELD
from app.prompt.frame_check_render import (
    CANT_TELL_LINES,
    CREATOR_STEP_LINES,
    LANGS,
    MAX_STEP_CHARS,
    OK_LINES,
    PRO_MODE_PREFIX,
    SCENE_VALUES,
    STEP_TEXT_TYPES,
    CreatorStepLinesError,
    load_creator_step_lines,
    normalize_lang,
    normalize_scene,
    render_lines,
    render_step,
    render_what_i_see,
)

PHONE_ROWS: list[dict[str, Any]] = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "phone_hardware"]
PHONES: list[dict[str, Any] | None] = [None, *PHONE_ROWS]
SIDES = ("your_left", "your_right", "none")

PRO = frame_check.resolve_phone("OPPO Find X8 Ultra")  # 3x/6x telephoto, ultrawide, manual video, OIS
RENO = frame_check.resolve_phone("OPPO Reno 14 Pro")  # 3.5x periscope; manual video "Check ..."
FLIP = frame_check.resolve_phone("OPPO Find N3 Flip")  # 2x telephoto; manual video "Check ..."; no HDR
F25 = frame_check.resolve_phone("OPPO F25 Pro")  # ultrawide and OIS only
A78 = frame_check.resolve_phone("OPPO A78 5G")  # no telephoto, ultrawide, manual video or OIS

STEP_ROWS: list[dict[str, Any]] = [
    r for r in CREATOR_KNOWLEDGE_ROWS
    if r["data_type"] in STEP_TEXT_TYPES and r["data_type"] in frame_check.CITABLE_TYPES
]

# Citable types render_step writes nothing for, and why (contract (b)).
NOT_RENDERED: dict[str, str] = {
    "phone_hardware": "the creator's phone only gates other rows' advice; it is not a step",
    "physics_principle": "a definition of how light behaves, with no instruction field",
    "coordinate_system_note": "a rule for how Meera words left and right, not a creator step",
    "lighting_workflow": "a rule for the order Meera gives fixes in, not a creator step",
}

# Rows of a rendered type that still render nothing, and why.
EMPTY_ROWS: dict[str, str] = {
    "What makes a good background": "background_rule with a definition and no fix",
    "Only suggest what the creator's phone can actually do.": "coach-facing standing rule",
    "Give every camera setting with its one-line reason, so the creator learns why": (
        "coach-facing standing rule"
    ),
    # camera_height_rule: only eye level is advice; the rest describe an effect, three of them
    # by naming parts of the face.
    "Slightly above eyes": "camera height that only describes an effect",
    "Slightly below eyes": "camera height that only describes an effect",
    "High angle": "camera height that only describes an effect",
    "Low angle": "camera height that only describes an effect",
    # portrait patterns with no creator-voice line (see ROWS_WITHOUT_A_LINE).
    "Loop": "portrait pattern with no creator-voice line",
    "Butterfly": "portrait pattern with no creator-voice line",
}

PERSON_WORDS_RE = re.compile(
    r"\b(?:face\s+shape|skin|clothes|dress|shirt|hair|age|old|young|man|woman|girl|boy"
    r"|beautiful|pretty|fat|thin)\b",
    re.IGNORECASE,
)
# "face" is allowed only in the fixed "light on your face" sense.
ALLOWED_FACE_RE = re.compile(r"light on your face|face pe light", re.IGNORECASE)
FACE_RE = re.compile(r"\bface\b", re.IGNORECASE)


# The free-text gates as frame_check held them at a2b8f027 (growth wording; description-only
# lines). The parser no longer reads model text, so it dropped them; these pinned copies keep
# the bar where it was for every line code writes.
_GROWTH_WORDS_PINNED = re.compile(
    r"\b(?:views|engagement|followers|reach|viral|likes|subscribers|algorithm|guaranteed"
    r"|trending|boost(?:s|ed|ing)?|blow(?:s|ing)?\s+up|grow\s+your|impressions|shares|saves"
    r"|watch\s*time|log\s+dekhenge|post now|don['\u2019]?t miss)\b",
    re.IGNORECASE,
)
_NUMBER_WORDS_PINNED = (
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
    "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
    "eighty", "ninety", "hundred", "thousand", "half", "quarter", "double", "twice",
    "ek", "teen", "char", "paanch", "das", "bees", "tees", "chalis", "pachaas", "sau",
    "pachees", "chaubees", "dedh", "dhai", "aadha", "hazaar", "lakh", "crore",
)
_NUMBER_WORD_PINNED = re.compile(r"\b(?:" + "|".join(_NUMBER_WORDS_PINNED) + r")\b", re.IGNORECASE)
_ADVICE_START_PINNED = re.compile(r"^\W*(?:switch|turn\s+on|set|move|use|put|place)\b", re.IGNORECASE)


def _growth(text: str) -> bool:
    return bool(_GROWTH_WORDS_PINNED.search(text))


def _description_only(text: str) -> bool:
    """frame_check's `is_description_only` as of a2b8f027: no digit, no number word, no growth
    wording, no lens or control, no opening advice verb. "do"/"saath" count only with a
    unit, so they go through frame_check's own `_NUMBER_WORD_RE`."""
    return not (
        re.search(r"\d", text)
        or _NUMBER_WORD_PINNED.search(text)
        or frame_check._NUMBER_WORD_RE.search(text)
        or _GROWTH_WORDS_PINNED.search(text)
        or frame_check.phone_features_named(text)
        or _ADVICE_START_PINNED.search(text)
    )


def _name(row: dict[str, Any]) -> str:
    return row[NAME_FIELD[row["data_type"]]]


def _row(name: str) -> dict[str, Any]:
    """The one step row named `name` (exactly, else by its start)."""
    found = [r for r in STEP_ROWS if _name(r) == name] or [
        r for r in STEP_ROWS if _name(r).startswith(name)
    ]
    assert len(found) == 1, (name, len(found))
    return found[0]


def _kinds_for(row: dict[str, Any]) -> list[str]:
    return [k for k, types in frame_check.KIND_ROW_TYPES.items() if row["data_type"] in types]


def _phone_label(phone: dict[str, Any] | None) -> str:
    return "no phone" if phone is None else phone["model"]


def _about_the_person(text: str) -> list[str]:
    hits = PERSON_WORDS_RE.findall(text)
    if FACE_RE.search(ALLOWED_FACE_RE.sub("", text)):
        hits.append("face")
    return hits


# --- (a) exhaustive safety ---------------------------------------------------------------------


def test_every_rendered_step_passes_the_gates_for_every_phone_lang_and_side() -> None:
    failures: list[str] = []
    rendered = 0
    for row in STEP_ROWS:
        for kind in _kinds_for(row):
            for phone in PHONES:
                for lang in LANGS:
                    for side in SIDES:
                        text = render_step(kind, row, phone, lang, side)
                        if text is None:
                            continue
                        rendered += 1
                        where = f"{row['data_type']} | {_name(row)[:50]} | {kind} | {_phone_label(phone)} | {lang} | {side}"
                        if not frame_check.step_fits_phone(text, phone):
                            failures.append(f"phone fit: {where}: {text}")
                        if not frame_check.step_numbers_grounded(text, [row]):
                            failures.append(f"numbers: {where}: {text}")
                        if _growth(text):
                            failures.append(f"growth: {where}: {text}")
                        if len(text) > MAX_STEP_CHARS:
                            failures.append(f"length {len(text)}: {where}: {text}")
    assert not failures, "\n".join(failures[:20])
    assert rendered > 1000  # the loop really ran over the knowledge


FEATURES = ("telephoto", "ultrawide", "manual_video", "ois", "log_hdr")


@pytest.mark.parametrize("phone", PHONES, ids=_phone_label)
def test_steps_name_no_feature_the_phone_lacks_and_an_unsure_one_only_conditionally(phone) -> None:
    """Every step row x every kind x every side x both langs, against this phone: sentence by
    sentence, no lens (at that factor), manual control, OIS or HDR the phone row marks No/None
    is named at all; one the phone MAY have (no phone known, or a row saying "Check ...") only
    in a conditional sentence."""
    checked = 0
    for row in STEP_ROWS:
        for kind in _kinds_for(row):
            for lang in LANGS:
                for side in SIDES:
                    text = render_step(kind, row, phone, lang, side)
                    if text is None:
                        continue
                    for sentence in render._split_sentences(text):
                        named = render.phone_features_named(sentence)
                        assert named <= set(FEATURES)
                        for feature in named:
                            state = render._feature_state(phone, feature, sentence)
                            assert state != "no", (_name(row), _phone_label(phone), feature, sentence)
                            if state == "unknown":
                                assert render._CONDITIONAL_RE.search(sentence), (
                                    _name(row), _phone_label(phone), feature, sentence)
                            checked += 1
    # Not vacuous: the rows really name features (the A78 has none of them, so it gets none).
    assert checked > 0 or phone is A78


def test_raw_row_advice_fails_the_gates_that_the_rendered_step_passes() -> None:
    """Falsification: without the renderer, real rows fail the same gates."""
    flicker = _row("India")
    assert not frame_check.step_fits_phone(flicker["fix"], A78)
    assert not frame_check.step_fits_phone(flicker["fix"], None)
    for phone in (A78, None):
        text = render_step("settings", flicker, phone, "en")
        assert text and frame_check.step_fits_phone(text, phone), text

    cluttered = _row("Talking Head (Cluttered background)")
    raw = " ".join(str(cluttered[f]) for f in ("phone_camera", "distance", "fps", "shutter", "iso"))
    assert not frame_check.step_fits_phone(raw, A78)
    text = render_step("settings", cluttered, A78, "en")
    assert text and frame_check.step_fits_phone(text, A78), text
    assert "1x Main, closer, background lights off" in text and "elephoto" not in text

    serious = _row("Serious / educational")
    lead = "Put the light on your left. "
    assert len(lead + serious["setup"]) > MAX_STEP_CHARS  # the length cap is not vacuous
    # The first sentence stays whole; the side hint yields.
    assert render._assemble(lead, [serious["setup"]], {}, "en") == serious["setup"]
    text = render_step("move_light", serious, None, "en", "your_left")
    assert text == CREATOR_STEP_LINES[("lighting_look", "Serious / educational")]["en"]
    assert len(text) <= MAX_STEP_CHARS


def test_no_phone_puts_manual_controls_in_one_pro_mode_sentence() -> None:
    row = _row("Talking Head (Window light)")
    for lang in LANGS:
        text = render_step("settings", row, None, lang)
        assert text is not None
        prefix = PRO_MODE_PREFIX[lang]
        assert text.count(prefix) == 1
        head, tail = text.split(prefix)
        assert not render.phone_features_named(head), head
        assert "FPS 25" in tail and "shutter 1/50" in tail and "white balance 5600K" in tail
    assert PRO_MODE_PREFIX["en"].startswith("If your camera app has a Pro video mode: ")
    assert PRO_MODE_PREFIX["hi"].startswith("Agar aapke camera app mein Pro video mode hai: ")


def test_a_phone_with_the_controls_gets_them_plainly() -> None:
    row = _row("Talking Head (Window light)")
    text = render_step("settings", row, PRO, "en")
    assert text is not None and PRO_MODE_PREFIX["en"] not in text
    assert "FPS: 25." in text and "Shutter: 1/50." in text
    assert render_step("settings", row, A78, "en") == (
        "Lens: 1x Main. Distance: 0.8-1m. Framing: Chest up. EV: +0.5. "
        "Stabilization: Tripod (stabilization off)."
    )


def test_a_lens_factor_must_be_the_phones_own() -> None:
    """Review 2026-09-25: "3x Telephoto" reached a 2x-telephoto phone and a 3.5x one."""
    cluttered = _row("Talking Head (Cluttered background)")
    for phone in (FLIP, RENO):
        text = render_step("move_phone", cluttered, phone, "en")
        assert text.startswith("Lens: 1x Main, closer, background lights off."), text
        assert "3x" not in text and "telephoto" not in text.lower(), text  # nor "2.5m on the telephoto"
    assert render_step("move_phone", cluttered, PRO, "en").startswith("Lens: 3x Telephoto. Distance: 2.5m on the telephoto.")
    broll = _row("Cinematic B-Roll")
    for phone in (FLIP, RENO, F25, A78, None):
        assert "3x" not in render_step("settings", broll, phone, "en")
    desk = _row("Product Review (Desk)")  # "2x-3x Telephoto (no telephoto: 1x Main, closer)"
    assert render_step("settings", desk, FLIP, "en").startswith("Lens: 2x Telephoto.")
    assert render_step("settings", desk, PRO, "en").startswith("Lens: 3x Telephoto.")
    assert render_step("settings", desk, RENO, "en").startswith("Lens: 1x Main, closer.")
    bad_bg = _row("What makes a bad background, and the fix")  # "switch to the telephoto (3x/3.5x)"
    assert "(3.5x)" in render_step("move_you", bad_bg, RENO, "en")
    assert "(3x)" in render_step("move_you", bad_bg, PRO, "en")
    flip = render_step("move_you", bad_bg, FLIP, "en")
    assert "If your phone has a telephoto, switch to it to narrow the view." in flip and "3x" not in flip
    talking = _row("Talking-head (educational/vlog)")  # "If your phone has a 3x telephoto ... 2-2.5m"
    for phone in (FLIP, RENO):
        text = render_step("move_phone", talking, phone, "en")
        assert "keep the phone about 0.8-1m away." in text and "3x" not in text, text
    assert "3x telephoto" in render_step("move_phone", talking, PRO, "en")


def test_ois_and_hdr_are_checked_against_the_phone() -> None:
    """Review 2026-09-25: "Stabilization: OIS only" and "rely on OIS" reached the A78 (no OIS)."""
    for name in ("Streetlight (weak/orange)", "Restaurant (dim ambient)", "Night Street (Neon)",
                 "Food close-up (restaurant or home)", "Cinematic B-Roll", "Night market (bright neon)",
                 "Walking Street Vlog", "Talking Head (Outdoors, daylight)"):
        text = render_step("settings", _row(name), A78, "en")
        assert text and "OIS" not in text and "EIS" not in text.replace("Super Steady / EIS On", ""), (name, text)
    outdoors = render_step("settings", _row("Talking Head (Outdoors, daylight)"), A78, "en")
    assert "Stabilization: Tripod (stabilization off)." in outdoors  # the OIS alternative is cut
    jitter = _row("Digital crop jitter")
    for lang in LANGS:  # EIS off with no OIS is wrong advice
        assert render_step("move_phone", jitter, A78, lang) is None
    assert "turn off 'Ultra Steady' (EIS)" in render_step("move_phone", jitter, F25, "en")
    # With no phone known, EIS off only ever rides in the "if your phone has OIS" sentence.
    assert render_step("move_phone", jitter, None, "en").startswith("If your phone has OIS and ")
    assert render_step("move_phone", jitter, None, "hi").startswith("Agar aapke phone mein OIS hai ")
    backlit = _row("Backlit subject")
    for phone in (A78, F25, FLIP):
        text = render_step("move_light", backlit, phone, "en")
        assert "HDR" not in text and "push brightness up (+EV)" in text, text
    # No phone known: HDR only in the line's own "if your phone has" sentence.
    assert render_step("move_light", backlit, None, "en").endswith(
        "If your phone has an HDR video mode, use it to balance the strong contrast.")
    assert "HDR video mode" in render_step("move_light", backlit, RENO, "en")


def test_an_unsure_manual_control_is_conditional_not_fact() -> None:
    """Review 2026-09-25: the Reno 14 Pro and Find N3 Flip rows say "Check the camera app's Pro
    video mode", and got "FPS: 25. Shutter: 1/50." as fact."""
    row = _row("Talking Head (Window light)")
    for phone in (RENO, FLIP):
        assert render._feature_state(phone, "manual_video") == "unknown"
        assert render_step("settings", row, phone, "en") == render_step("settings", row, None, "en")
        text = render_step("settings", row, phone, "en")
        assert "FPS: 25." not in text and PRO_MODE_PREFIX["en"] + "FPS 25" in text
    assert render._feature_state(PRO, "manual_video") == "yes"
    assert render._feature_state(A78, "manual_video") == "no"
    assert render._feature_state(None, "manual_video") == "unknown"


def test_with_no_phone_only_the_control_clause_is_conditional() -> None:
    """Review 2026-09-25: everyday advice that also mentions a control went whole into the
    Pro-mode sentence, so an auto-only creator read it as not for them."""
    colours = render_step("move_light", _row("Lights of different colours"), None, "en")
    assert colours == (
        "Pick the light you want on your face, and switch the others off or keep them only in the "
        "background. If your camera app has a Pro video mode, set white balance for that face light."
    )
    assert render_step("move_light", _row("Lights of different colours"), A78, "en") == (
        "Pick the light you want on your face, and switch the others off or keep them only in the "
        "background."
    )
    choppy = render_step("move_phone", _row("Choppy, laggy video in a dim room"), None, "hi")
    assert choppy.startswith("Room mein asli light add karo. ")
    assert "Agar aapke camera app mein Pro video mode hai, toh fps khud lock karo" in choppy
    shade = render_step("move_light", _row("Shade under trees"), None, "en")
    assert "face open sky or a light-coloured wall." in shade
    assert shade.endswith("If your camera app has a Pro video mode, lock white balance.")
    flicker = render_step("settings", _row("India"), None, "en")
    assert "If your camera app has a Pro video mode, indoors under home lights use 25fps at 1/50s" in flicker
    assert flicker.count("Pro video mode") == 1 and "Pro/manual mode" not in flicker


def test_the_else_branch_is_the_advice_when_the_if_branch_is_cut() -> None:
    awb = _row("Phone auto white balance shifts during the take")
    assert render_step("move_light", awb, A78, "en") == (
        "Cut down the clashing light colours, and don't walk through areas lit by different "
        "colours during the shot."
    )
    assert render_step("move_light", awb, A78, "hi").startswith("Alag-alag colour ki lights kam karo, ")
    assert render_step("move_light", awb, PRO, "en").startswith("Lock or set white balance")
    # Review 2026-09-25: on a phone that can't lock white balance, the en step once kept "the
    # lamp behind you can stay warm" (cut loose from its white-balance condition at ", and ")
    # while hi dropped the whole sentence. Both now keep only the row's own two options.
    lamp = _row("Window daylight + warm room lamp")
    assert render_step("move_light", lamp, A78, "en") == (
        "Use the window as your face light. Switch the lamp off, or move it behind you as a warm "
        "lamp in the background."
    )
    assert render_step("move_light", lamp, A78, "hi") == (
        "Window ko apni face light banao. Lamp off karo, ya use apne peeche background mein warm "
        "lamp ki tarah rakho."
    )
    for phone in (A78, F25):
        for lang in LANGS:
            text = render_step("move_light", lamp, phone, lang)
            assert re.search(r"switch the lamp off|lamp off karo", text, re.IGNORECASE), text
            assert not re.search(r"stay warm|warm rakho|warm reh|can't|yeh nahi hai", text, re.IGNORECASE), text
    assert render_step("move_light", lamp, PRO, "en").endswith("If your phone can't, switch the lamp off.")
    assert render_step("move_light", lamp, PRO, "hi").endswith("Agar phone mein yeh nahi hai, toh lamp off karo.")


def test_coach_notes_are_said_to_the_creator() -> None:
    for name in ("Strong natural daylight", "Soft natural window light", "Messy room/clutter",
                 "Blank wall", "Creator faces window", "Creator is 90 deg to window"):
        text = render_step(_kinds_for(_row(name))[0], _row(name), PRO, "en")
        assert not re.search(r"\b(?:creator|the subject)\b", text, re.IGNORECASE), (name, text)
    assert render_step("move_you", _row("Messy room/clutter"), None, "en").startswith(
        "Move yourself or the phone until only a clean part of the room shows behind you")
    vlog = render_step("settings", _row("Walking Street Vlog"), A78, "en")
    assert "nose" not in vlog and "the nearest features look bigger" in vlog


def test_an_aside_or_clause_naming_a_missing_lens_is_cut_and_the_rest_kept() -> None:
    vlog = _row("Walking Street Vlog")
    assert render_step("settings", vlog, A78, "en").startswith("Lens: 1x Main. Distance:")
    assert "0.5x" in render_step("settings", vlog, F25, "en")  # F25 has the ultrawide
    wide_face = _row("Distorted facial features")
    assert render_step("move_phone", wide_face, A78, "en") == (
        "The phone is too close to your face. Move it farther than arm's length and reframe."
    )
    assert "telephoto" in render_step("move_phone", wide_face, PRO, "en")
    choppy = _row("Choppy, laggy video in a dim room")
    assert render_step("move_phone", choppy, A78, "en") == (
        "Add a real light to the room. In low light your phone records fewer frames each second to "
        "catch more light, so the video looks choppy."
    )


def test_side_lead_is_templated_per_kind_and_lang() -> None:
    window = _row("Soft natural window light")  # a window beside you: you turn (move_you)
    body = render_step("move_you", window, None, "en")
    assert render_step("move_you", window, None, "en", "your_left") == "Turn so the light is on your left. " + body
    body_hi = render_step("move_you", window, None, "hi")
    assert render_step("move_you", window, None, "hi", "your_left") == "Aise baitho ki light aapke left side ho. " + body_hi
    lamp = _row("Low-key / dramatic")  # a movable main light from the side (move_light)
    body = render_step("move_light", lamp, None, "en")
    assert render_step("move_light", lamp, None, "en", "your_left") == "Put the light on your left. " + body
    body_hi = render_step("move_light", lamp, None, "hi")
    assert render_step("move_light", lamp, None, "hi", "your_right") == "Light ko apne right side rakho. " + body_hi
    # Ignored for the other kinds, and for a side that is not one of the three.
    phone_row = _row("Eye-level")
    assert render_step("move_phone", phone_row, None, "en", "your_left") == render_step("move_phone", phone_row, None, "en")
    assert render_step("move_light", lamp, None, "en", "camera_left") == body
    assert render_step("move_light", lamp, None, "en", "left of the lamp") == body


@pytest.mark.parametrize(
    ("kind", "name"),
    [
        ("move_light", "Silhouette"),  # "no light on your face"
        ("move_light", "Soft natural window light"),  # a window cannot be moved
        ("move_you", "Creator faces window"),  # "Keep window behind phone"
        ("move_light", "1. Low sun"),  # the sun cannot be moved
        ("move_light", "Ceiling LED/tube/fan light"),
        ("move_you", "Harsh midday sun"),  # "Move into open shade"
        ("move_phone", "Choppy, laggy video in a dim room"),
        ("move_you", "Strong natural daylight"),  # "the sun acts as a backlight"
    ],
)
def test_no_side_lead_where_the_rows_own_advice_says_otherwise(kind, name) -> None:
    row = _row(name)
    for lang in LANGS:
        plain = render_step(kind, row, None, lang)
        assert plain
        for side in ("your_left", "your_right"):
            assert render_step(kind, row, None, lang, side) == plain, (name, side)


def test_every_side_row_is_a_real_row() -> None:
    for (dt, field, name), kinds in render._SIDE_ROWS.items():
        found = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == dt and r.get(field) == name]
        assert len(found) == 1, name
        for kind in kinds:
            assert dt in frame_check.KIND_ROW_TYPES[kind], (name, kind)
            assert render_step(kind, found[0], None, "en", "your_left") != render_step(kind, found[0], None, "en")


def test_camera_height_rule_is_its_line() -> None:
    assert render_step("move_phone", _row("Eye-level"), None, "en") == (
        "Keep the phone at your eye level. It gives a neutral view and easy eye contact, the best "
        "default for interviews, teaching and talking straight to camera."
    )
    assert render_step("move_phone", _row("Eye-level"), None, "hi").startswith("Phone apni eye level pe rakho.")


def test_every_step_row_renders_on_a_phone_with_every_feature() -> None:
    for row in STEP_ROWS:
        kind = _kinds_for(row)[0]
        text = render_step(kind, row, PRO, "en")
        if _name(row) in EMPTY_ROWS or _name(row).startswith(tuple(EMPTY_ROWS)):
            assert text is None, _name(row)
        else:
            assert text, (row["data_type"], _name(row))


# --- (b) type coverage -----------------------------------------------------------------------------


def test_every_citable_type_is_rendered_or_excluded_with_a_reason() -> None:
    citable = set(frame_check.CITABLE_TYPES)
    rendered = citable & set(STEP_TEXT_TYPES)
    assert set(STEP_TEXT_TYPES) <= citable, set(STEP_TEXT_TYPES) - citable
    assert not rendered & set(NOT_RENDERED)
    assert rendered | set(NOT_RENDERED) == citable, citable - rendered - set(NOT_RENDERED)
    for dt in NOT_RENDERED:
        row = next(r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == dt)
        for kind in render.STEP_KINDS:
            assert render_step(kind, row, PRO, "en") is None


# --- (c) fixed lines and what_i_see -------------------------------------------------------------


def _assert_safe_line(line: str) -> None:
    assert line and line.strip(), line
    assert _description_only(line), line
    assert not _about_the_person(line), (line, _about_the_person(line))


def test_ok_and_cant_tell_lines_are_safe_in_both_langs() -> None:
    assert set(OK_LINES) == {
        "light_soft_on_face", "face_evenly_lit", "light_from_side", "background_clean",
        "background_has_depth", "phone_at_eye_level", "framing_fits", "no_window_behind",
        "single_light_colour",
    }
    assert set(CANT_TELL_LINES) == {
        "audio", "light_outside_frame", "room_behind_phone", "can_you_move", "shake_or_motion",
        "light_changes_over_time", "exact_distance", "focus_while_moving",
    }
    for table in (OK_LINES, CANT_TELL_LINES):
        for lines in table.values():
            assert set(lines) == set(LANGS)
            for line in lines.values():
                _assert_safe_line(line)


def test_the_person_word_check_is_not_vacuous() -> None:
    for bad in ("Your hair looks great.", "Nice shirt.", "You look young.", "Your face is round.",
                "The man behind you.", "Face shape is oval."):
        assert _about_the_person(bad), bad
    assert not _about_the_person("The light on your face is soft.")


def _scenes_covering_every_value(per_value: int = 150) -> list[dict[str, str]]:
    """Every value of every scene field, each with `per_value` random settings of the others
    (the full product is 864,000 scenes x 2 langs -- too slow for a unit test), plus the
    all-unknown scene."""
    rng = random.Random(20260925)
    fields = list(SCENE_VALUES)
    scenes: list[dict[str, str]] = []
    for field in fields:
        for value in SCENE_VALUES[field]:
            for _ in range(per_value):
                scene = {f: rng.choice(SCENE_VALUES[f]) for f in fields}
                scene[field] = value
                if field != "usable":
                    scene["usable"] = "yes"  # otherwise the value would not be rendered
                scenes.append(scene)
    unknown = {f: "unknown" for f in fields}
    unknown.update(usable="yes", others_in_frame="no")
    scenes.append(unknown)
    return scenes


def test_what_i_see_is_safe_for_every_scene_value_in_both_langs() -> None:
    scenes = _scenes_covering_every_value()
    assert len(scenes) > 7000
    for scene in scenes:
        for lang in LANGS:
            _assert_safe_line(render_what_i_see(scene, lang))


def test_every_what_i_see_fragment_is_safe() -> None:
    """Each fixed clause on its own, so a fragment the sampling missed cannot hide."""
    tables = (
        render._PLACE, render._LIGHT, render._LIGHT_SIDE, render._BACKGROUND,
        render._PHONE_HEIGHT, render._FRAMING, render.WHAT_I_SEE_UNUSABLE,
    )
    for table in tables:
        for lines in table.values():
            for line in lines.values():
                assert not _about_the_person(line), line
                assert _description_only(line), line
    for table in (render.WHAT_I_SEE_ALL_UNKNOWN, render.WHAT_I_SEE_OTHERS, render.WHAT_I_SEE_OPENING):
        for line in table.values():
            assert not _about_the_person(line) and _description_only(line), line


def test_what_i_see_reads_like_a_coach_and_omits_unknown_parts() -> None:
    scene = normalize_scene({
        "place": "bedroom", "light": "window", "light_side": "your_left",
        "background": "cluttered", "phone_height": "below_eyes", "framing": "chest_up",
        "others_in_frame": "no",
    })
    assert render_what_i_see(scene, "en") == (
        "I can see you're in a bedroom, window light from your left, a cluttered background, "
        "phone below your eyes, framed chest up."
    )
    assert render_what_i_see(normalize_scene({"light_side": "above"}), "en") == "I can see light from above."
    assert render_what_i_see(normalize_scene({}), "en") == render.WHAT_I_SEE_ALL_UNKNOWN["en"]
    assert render_what_i_see(normalize_scene({}), "hi") == render.WHAT_I_SEE_ALL_UNKNOWN["hi"]


def test_someone_else_in_frame_adds_one_fixed_clause_and_nothing_more() -> None:
    for lang in LANGS:
        alone = render_what_i_see(normalize_scene({"place": "park"}), lang)
        crowd = render_what_i_see(normalize_scene({"place": "park", "others_in_frame": "yes"}), lang)
        assert crowd == alone + " " + render.WHAT_I_SEE_OTHERS[lang]


def test_an_unusable_photo_gets_its_one_fixed_line() -> None:
    for reason in ("too_dark", "lens_covered", "blank", "too_blurry"):
        for lang in LANGS:
            scene = normalize_scene({"usable": reason, "place": "bedroom", "others_in_frame": "yes"})
            assert render_what_i_see(scene, lang) == render.WHAT_I_SEE_UNUSABLE[reason][lang]
    assert "retake" in render.WHAT_I_SEE_UNUSABLE["too_dark"]["en"]
    assert "wipe" in render.WHAT_I_SEE_UNUSABLE["lens_covered"]["en"]


# --- (d) unknowns are dropped, never rendered -----------------------------------------------------


def test_normalize_scene_maps_unknown_values_to_unknown_and_ignores_free_text() -> None:
    scene = normalize_scene({
        "place": "bedroom, and you look old",
        "light": "WINDOW",
        "light_side": "Your Left",
        "background": 7,
        "framing": None,
        "others_in_frame": "two people",
        "text": "You look tired today.",
        "what_i_see": "Nice shirt.",
    })
    assert scene == {
        "usable": "yes", "place": "unknown", "light": "window", "light_side": "your_left",
        "background": "unknown", "phone_height": "unknown", "framing": "unknown",
        "others_in_frame": "no",
    }
    line = render_what_i_see(scene, "en")
    assert "old" not in line and "tired" not in line and "shirt" not in line
    assert line == "I can see window light from your left."


def test_normalize_scene_none_for_a_non_object_and_unclear_for_a_garbled_usable() -> None:
    for raw in (None, "bedroom", ["bedroom"], 3):
        assert normalize_scene(raw) is None
    for garbled in ("sort of", 1, "no", False, 0, ["too_dark"], "unusable", ""):
        assert normalize_scene({"usable": garbled})["usable"] == render.USABLE_UNCLEAR, garbled
    assert normalize_scene({"usable": "Too Dark"})["usable"] == "too_dark"
    assert normalize_scene({"usable": "too_dark (black frame)"})["usable"] == "too_dark"
    assert normalize_scene({"usable": "Yes"})["usable"] == "yes"
    for polite in ("Yes.", "yes!", " YES "):
        assert normalize_scene({"usable": polite})["usable"] == "yes", polite
    assert normalize_scene({"usable": "yes, but dark"})["usable"] == render.USABLE_UNCLEAR
    assert normalize_scene({"place": "desk"})["usable"] == "yes"  # missing -> yes
    assert normalize_scene({"usable": None})["usable"] == "yes"
    for lang in LANGS:
        line = render_what_i_see(normalize_scene({"usable": "no", "place": "desk"}), lang)
        assert line == render.WHAT_I_SEE_UNUSABLE[render.USABLE_UNCLEAR][lang]
    assert render_what_i_see(None, "en") == ""
    assert render_what_i_see("You look great", "en") == ""  # type: ignore[arg-type]


def test_every_scene_field_has_its_values() -> None:
    assert set(SCENE_VALUES) == {
        "usable", "place", "light", "light_side", "background", "phone_height", "framing",
        "others_in_frame",
    }
    for field, values in SCENE_VALUES.items():
        if field not in ("usable", "others_in_frame"):
            assert "unknown" in values, field


def test_render_lines_drops_unknown_ids_dedups_keeps_order_and_caps() -> None:
    ids = ["audio", "You look great", "audio", 5, None, "room_behind_phone", "exact_distance", "can_you_move"]
    assert render_lines(ids, CANT_TELL_LINES, "en") == [
        CANT_TELL_LINES["audio"]["en"],
        CANT_TELL_LINES["room_behind_phone"]["en"],
        CANT_TELL_LINES["exact_distance"]["en"],
    ]
    assert render_lines(["background_clean"], OK_LINES, "hi", limit=1) == [OK_LINES["background_clean"]["hi"]]
    assert render_lines(["Background_Clean "], OK_LINES, "en") == [OK_LINES["background_clean"]["en"]]
    assert render_lines("audio", CANT_TELL_LINES, "en") == []
    assert render_lines(None, CANT_TELL_LINES, "en") == []
    assert render_lines(["audio"], OK_LINES, "en") == []  # an id from the other table
    assert render_lines(["audio"], CANT_TELL_LINES, "fr") == [CANT_TELL_LINES["audio"]["en"]]


def test_render_step_drops_unknown_kinds_and_types() -> None:
    row = _row("Soft natural window light")
    assert render_step("move_camera", row, None, "en") is None
    assert render_step("", row, None, "en") is None
    assert render_step("move_light", {"data_type": "hook_template", "template": "Say this"}, None, "en") is None
    assert render_step("move_light", {"instruction": "Say you look great"}, None, "en") is None
    assert render_step("settings", PRO, PRO, "en") is None  # a phone row is never a step
    assert render_step("move_light", "Soft natural window light", None, "en") is None  # type: ignore[arg-type]
    assert render_step("move_light", row, None, "fr") == render_step("move_light", row, None, "en")


def test_normalize_lang_defaults_to_english() -> None:
    assert normalize_lang("hi") == "hi"
    assert normalize_lang(" HI ") == "hi"
    for raw in (None, "hinglish", "fr", 1):
        assert normalize_lang(raw) == "en"


# --- (e) creator-voice lines ----------------------------------------------------------------------

# Sentence-style step rows with NO creator-voice line, and why. Every one renders nothing. A
# settings row (camera_technical_setting, night_video_setting) never has a line: it stays its
# labelled parts. Keyed by (data_type, the start of the row's name).
ROWS_WITHOUT_A_LINE: dict[tuple[str, str], str] = {
    ("background_rule", "What makes a good background"): (
        "a definition with no fix field; the renderer returns None for it"
    ),
    ("permanent_rule", "Only suggest what the creator's phone can actually do"): (
        "a rule for how the coach advises (_coach_facing), not an instruction for the creator"
    ),
    ("permanent_rule", "Give every camera setting with its one-line reason"): (
        "a rule for how the coach talks (_coach_facing), not an instruction for the creator"
    ),
    ("camera_height_rule", "Slightly above eyes"): (
        "describes how the angle makes the face look; only eye level is written as a step"
    ),
    ("camera_height_rule", "Slightly below eyes"): (
        "describes how the angle makes the face look (chin / nostrils); only eye level is a step"
    ),
    ("camera_height_rule", "High angle"): "describes an effect; only eye level is a step",
    ("camera_height_rule", "Low angle"): "describes an effect; only eye level is a step",
    ("portrait_lighting_pattern", "Loop"): (
        "the note is where a small nose shadow falls; it can't be said without naming face parts"
    ),
    ("portrait_lighting_pattern", "Butterfly"): (
        "the note is a small downward nose shadow and an eye-socket check; same face-part problem"
    ),
}
SETTINGS_TYPES = frozenset(render._SETTINGS_PARTS)

# The checks every line passes (the writers' check_lines.py, ported): no growth wording
# (`_GROWTH_WORDS_PINNED`), nothing about the person's looks or body, no third person.
LINE_PERSON_RE = re.compile(
    r"\b(?:skin(?:\s+tone)?|complexion|clothes|clothing|dress|shirt|hair|age|old|young|man|woman"
    r"|girl|boy|beautiful|pretty|handsome|ugly|fat|thin|weight|nose|chin|ears?|nostrils?|wrinkles?"
    r"|forehead|jaw|double\s+chin|body|under\s+(?:your\s+)?eyes|aankhon\s+ke\s+neeche)\b",
    re.IGNORECASE,
)
# The camera controls a line names by their short name: en and hi must name the same ones.
LINE_CONTROL_RE = re.compile(r"\b(?:EV|ISO|fps)\b", re.IGNORECASE)
# An absolute side: the templated side lead says which side, a line must not fight it. "right
# behind you", "right under you" and "looks right" are not sides.
LINE_SIDE_RE = re.compile(r"\b(?:left|right)\b", re.IGNORECASE)
LINE_NOT_A_SIDE_RE = re.compile(r"\bright\s+(?:behind|under)\b|\blooks?\s+right\b", re.IGNORECASE)
LINE_THIRD_PERSON_RE = re.compile(r"\b(?:the creator|creator's|the subject|subject's|talent)\b", re.IGNORECASE)


def _key(row: dict[str, Any]) -> tuple[str, str]:
    return row["data_type"], _name(row).strip()


def _without_a_line(row: dict[str, Any]) -> str | None:
    found = [why for (dt, start), why in ROWS_WITHOUT_A_LINE.items()
             if row["data_type"] == dt and _name(row).startswith(start)]
    assert len(found) <= 1, _name(row)
    return found[0] if found else None


def _number_claims(text: str) -> list[tuple[str, tuple[str, ...], str | None]]:
    return sorted((n, tuple(parts), unit) for n, parts, unit in frame_check._number_tokens(text))


def _line_problems(row: dict[str, Any], line: dict[str, str]) -> list[str]:
    out: list[str] = []
    for lang in LANGS:
        text = line[lang]
        if not text.strip() or len(text) > render.LINE_MAX_CHARS:
            out.append(f"{lang} empty or {len(text)} chars")
        if not frame_check.step_numbers_grounded(text, [row]):
            out.append(f"{lang} states a number (or number word) the row's advice does not")
        if _growth(text):
            out.append(f"{lang} growth word")
        if LINE_PERSON_RE.search(text):
            out.append(f"{lang} person/body word {LINE_PERSON_RE.search(text).group(0)!r}")
        if LINE_THIRD_PERSON_RE.search(text):
            out.append(f"{lang} third person {LINE_THIRD_PERSON_RE.search(text).group(0)!r}")
        if "\n" in text or "  " in text:
            out.append(f"{lang} newline or double space")
        if LINE_SIDE_RE.search(LINE_NOT_A_SIDE_RE.sub("", text)):
            out.append(f"{lang} absolute left/right")
    if _number_claims(line["en"]) != _number_claims(line["hi"]):
        out.append("en and hi numbers differ")
    if render.phone_features_named(line["en"]) != render.phone_features_named(line["hi"]):
        out.append("en and hi name different phone features")
    controls = [{c.upper() for c in LINE_CONTROL_RE.findall(line[lang])} for lang in LANGS]
    if controls[0] != controls[1]:
        out.append("en and hi name different controls (EV/ISO/fps)")
    return out


def test_every_line_passes_the_voice_checks() -> None:
    rows = {_key(r): r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] in STEP_TEXT_TYPES}
    assert len(CREATOR_STEP_LINES) > 80
    failures = [
        f"{key}: {problem}"
        for key, line in CREATOR_STEP_LINES.items()
        for problem in _line_problems(rows[key], line)
    ]
    assert not failures, "\n".join(failures)


def test_the_voice_checks_are_not_vacuous() -> None:
    row = _row("Eye-level")
    good = CREATOR_STEP_LINES[_key(row)]
    assert _line_problems(row, good) == []
    for bad in (
        {**good, "hi": good["hi"] + " Ek baar check karo."},  # a Hinglish number word the row lacks
        {**good, "en": good["en"] + " Stand 2m back."},  # a number the row lacks
        {**good, "en": good["en"] + " Your hair looks great."},
        {**good, "en": good["en"] + " The creator should smile."},
        {**good, "en": good["en"] + " This gets more views."},
        {**good, "hi": good["hi"] + " ISO check karo."},  # a feature only one language names
        {**good, "hi": "x" * (render.LINE_MAX_CHARS + 1)},
    ):
        assert _line_problems(row, bad), bad
    # Review 2026-09-25: each of these once passed the gate.
    backlit = _row("Backlit subject")
    ev_line = CREATOR_STEP_LINES[_key(backlit)]
    assert _line_problems(backlit, ev_line) == []
    for which, bad_row, bad, problem in (
        ("hi drops EV", backlit, {**ev_line, "hi": ev_line["hi"].replace(" (+EV)", "")}, "controls"),
        ("absolute side", row, {**good, "en": good["en"] + " Keep the lamp on your left."}, "left/right"),
        ("under-eye", row, {**good, "en": good["en"] + " It shows shadows under your eyes."}, "person/body"),
        ("under-eye hi", row, {**good, "hi": good["hi"] + " Aankhon ke neeche shadows dikhti hain."}, "person/body"),
    ):
        assert any(problem in p for p in _line_problems(bad_row, bad)), which


def test_every_step_row_has_a_line_or_a_reason() -> None:
    """Contract (e): a sentence-style step row has a line, or is in ROWS_WITHOUT_A_LINE with the
    reason (and renders nothing); a settings row never has one."""
    for row in STEP_ROWS:
        key, why = _key(row), _without_a_line(row)
        if row["data_type"] in SETTINGS_TYPES:
            assert key not in CREATOR_STEP_LINES and why is None, key
            continue
        assert (key in CREATOR_STEP_LINES) != (why is not None), key
        if why is not None:
            for kind in _kinds_for(row):
                for phone in (None, PRO):
                    for lang in LANGS:
                        assert render_step(kind, row, phone, lang) is None, (key, why)
    for dt, start in ROWS_WITHOUT_A_LINE:  # every reason names a real row
        assert [r for r in STEP_ROWS if r["data_type"] == dt and _name(r).startswith(start)], start
    assert set(CREATOR_STEP_LINES) <= {_key(r) for r in STEP_ROWS}


def _advice_sentences(row: dict[str, Any]) -> list[str]:
    """The row's own English sentences (its advice field and every advice text), 3+ words."""
    text = render._sentence_advice(row) + " " + frame_check.advice_text(row)
    return [s.rstrip(".") for s in render._split_sentences(" ".join(text.split())) if len(s.split()) >= 3]


def test_a_step_is_the_line_in_its_language() -> None:
    """Contract (b): on a phone with every feature, the en step IS the en line and the hi step
    the hi line (lens factors narrowed to the phone's own); a hi step, on any phone, holds none
    of the row's English advice sentences nor of its English line."""
    checked = 0
    for row in STEP_ROWS:
        line = CREATOR_STEP_LINES.get(_key(row))
        if line is None:
            continue
        english = _advice_sentences(row) + [
            s.rstrip(".") for s in render._split_sentences(line["en"]) if len(s.split()) >= 3
        ]
        for kind in _kinds_for(row):
            for lang in LANGS:
                assert render_step(kind, row, PRO, lang) == render._narrow_factors(line[lang], PRO), (_key(row), lang)
            for phone in PHONES:
                hi = render_step(kind, row, phone, "hi")
                if hi is None:
                    continue
                checked += 1
                leaked = [s for s in english if s.casefold() in hi.casefold()]
                assert not leaked, (_key(row), _phone_label(phone), leaked)
    assert checked > 100


def test_en_and_hi_steps_keep_the_same_sentences_on_every_phone() -> None:
    """Review 2026-09-25: the phone fit cuts an English sentence at ", and " but a Hinglish one
    that has no such separator only whole, so en could keep a clause that hi dropped (the
    warm-lamp row on the A78 kept "the lamp behind you can stay warm" in en only). On every
    phone, a line's en and hi steps keep as many sentences, the same numbers and the same
    phone features."""
    checked = 0
    for row in STEP_ROWS:
        if _key(row) not in CREATOR_STEP_LINES:
            continue
        for kind in _kinds_for(row):
            for phone in PHONES:
                en, hi = render_step(kind, row, phone, "en"), render_step(kind, row, phone, "hi")
                where = (_key(row), kind, _phone_label(phone))
                assert (en is None) == (hi is None), where
                if en is None:
                    continue
                checked += 1
                assert len(render._split_sentences(en)) == len(render._split_sentences(hi)), (where, en, hi)
                assert _number_claims(en) == _number_claims(hi), (where, en, hi)
                assert render.phone_features_named(en) == render.phone_features_named(hi), (where, en, hi)
    assert checked > 300


def test_a_side_lead_opens_the_line() -> None:
    window = _row("Creator is 30-45 deg to window")
    for lang in LANGS:
        body = CREATOR_STEP_LINES[_key(window)][lang]
        lead = render._SIDE_LEADS["move_you"]["your_left"][lang]
        assert render_step("move_you", window, PRO, lang, "your_left") == lead + body


def test_a_hinglish_settings_step_has_hinglish_labels() -> None:
    row = _row("Talking Head (Window light)")
    en = render_step("settings", row, A78, "en")
    hi = render_step("settings", row, A78, "hi")
    assert en == (
        "Lens: 1x Main. Distance: 0.8-1m. Framing: Chest up. EV: +0.5. "
        "Stabilization: Tripod (stabilization off)."
    )
    assert hi == (
        "Lens: 1x Main. Doori: 0.8-1m. Frame: Chest up. EV: +0.5. "
        "Phone steady: Tripod (stabilization off)."
    )
    night = next(r for r in STEP_ROWS if r["data_type"] == "night_video_setting" and r.get("extra_light"))
    assert "Aur light: " in render_step("settings", night, PRO, "hi")
    assert "Extra light: " in render_step("settings", night, PRO, "en")


def test_the_lines_file_resolves_and_fails_loud_on_a_bad_line(tmp_path) -> None:
    good = {"data_type": "camera_height_rule", "name": "Eye-level", "en": "Keep the phone at eye level.",
            "hi": "Phone eye level pe rakho."}

    def load(*objs: Any, rows: list[dict[str, Any]] | None = None) -> Any:
        path = tmp_path / "lines.jsonl"
        path.write_text("".join((o if isinstance(o, str) else json.dumps(o)) + "\n" for o in objs), encoding="utf-8")
        return load_creator_step_lines(path, rows)

    assert load(good) == {("camera_height_rule", "Eye-level"): {"en": good["en"], "hi": good["hi"]}}
    assert load_creator_step_lines() == CREATOR_STEP_LINES  # the shipped file resolves
    bad_lines = {
        "unknown name": {**good, "name": "Eye level-ish"},
        "unknown type": {**good, "data_type": "hook_template"},
        "settings type": {**good, "data_type": "camera_technical_setting", "name": "Talking Head (Window light)"},
        "empty hi": {**good, "hi": "  "},
        "missing hi": {k: v for k, v in good.items() if k != "hi"},
        "extra key": {**good, "note": "x"},
        "long en": {**good, "en": "a" * (render.LINE_MAX_CHARS + 1)},
        "not text": {**good, "en": 5},
    }
    for label, obj in bad_lines.items():
        with pytest.raises(CreatorStepLinesError):
            load(obj)
        assert label
    with pytest.raises(CreatorStepLinesError):
        load(good, good)  # a key twice
    with pytest.raises(CreatorStepLinesError):
        load("{not json")
    with pytest.raises(CreatorStepLinesError):
        load("")  # no lines
    eye = _row("Eye-level")
    with pytest.raises(CreatorStepLinesError):
        load(good, rows=[eye, dict(eye)])  # the name fits two rows
    assert load({**good, "hi": "x" * render.LINE_MAX_CHARS}, rows=[eye])  # the cap itself is fine


# --- the phone helpers match frame_check's ------------------------------------------------------------


@pytest.mark.parametrize("phone", PHONES, ids=_phone_label)
def test_phone_helpers_agree_with_frame_check(phone: dict[str, Any] | None) -> None:
    texts = [str(v) for r in STEP_ROWS for v in r.values() if isinstance(v, str)]
    texts += ["Switch to 3x.", "Use the periscope.", "Switch to 0.5x.", "Set ISO 100 and 1/50.",
              "If your phone has a zoom lens, use it.", "Agar aapke phone mein telephoto hai."]
    for text in texts:
        assert render.phone_features_named(text) == frame_check.phone_features_named(text), text
        assert render.step_fits_phone(text, phone) == frame_check.step_fits_phone(text, phone), text
