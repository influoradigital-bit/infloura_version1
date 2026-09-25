"""The photo check's text, written by code (2026-09-25, "the AI picks, the code writes").

`POST /ai/shoot-check/frame` (`app/routes/shoot_check.py`, parsed by `app/prompt/frame_check.py`)
used to send the model's own sentences to the creator and try to catch bad wording with
filters. A blacklist never converges: "wide lens" on a phone without one, "25 frames a
second", unicode digits, growth wording, advice hidden in a description, a remark about
the person. So the model now returns only ids and enum values, and EVERY sentence in the
response is written here:

  - what the photo shows (`render_what_i_see`): fixed coach-voice clauses per scene value
    (place, light, the side the light comes from, background, phone height, framing), one
    fixed clause when someone else is in the frame, and one fixed line per unusable-photo
    reason. Nothing about the person's looks, body, clothes, age or gender exists in any
    template, so nothing of the sort can be said;
  - each step (`render_step`): the cited row's creator-voice line in the reply's language
    (`CREATOR_STEP_LINES`, read from `knowledge/creator_step_lines.jsonl`: one English and
    one Hinglish line per row, said to "you", stating only numbers the row's own advice
    states); a settings row's labelled parts (lens / distance / framing / fps / shutter /
    ISO / white balance / EV / stabilization ..., the labels in the reply's language, the
    values as the row writes them); a row with no line, its OWN advice field. With a
    templated side lead for move_you and move_light ("Turn so the light is on your left.");
  - what is already working and what one photo cannot show (`render_lines`): fixed lines
    per id (`OK_LINES`, `CANT_TELL_LINES`), English and Hinglish (Latin script);
  - each step's topic label (`step_label`, 2026-09-25): a short creator-voice label per row
    in the reply's language (`CREATOR_STEP_LABELS`, read from
    `knowledge/creator_step_labels.jsonl`: "Window behind you" / "Window aapke peeche"), so
    the app can say what a step is about without showing a knowledge row's own name;
  - a settings row's parts as data (`render_step_parts`): the SAME fitted parts the step's
    text is built from, [{label, value, needs_pro, needs_ois}] in the text's order, so the
    app can show them as a list and the two cannot drift.

Phone fit is deterministic: the advice is split into sentences (a settings row into its
parts) and a sentence or part naming something the creator's phone lacks is removed
(`phone_features_named` / `_feature_state`): telephoto, periscope, zoom lens, or an Nx with
N > 1 needs the phone row's telephoto AT THAT FACTOR ("3x" on a 2x-telephoto phone is a
digital crop; a run like "2x-3x" is narrowed to the factor the phone has); ultrawide, wide
angle, 0.5x its ultrawide; ISO, shutter, white balance, Kelvin, fps, Pro mode its
manual_video; OIS its ois; HDR its log_hdr. A bracketed aside naming a missing lens is cut
and the rest kept ("1x Main (use 0.5x only when ...)" -> "1x Main") -- never an OIS aside
("EIS off at night (OIS only)" without OIS is wrong advice); a lens part's own fallback is
used when the phone lacks the lens at that factor ("3x Telephoto (no telephoto: 1x Main,
closer)" -> "1x Main, closer"), and the row's later parts about that lens go with it
("Distance: 2.5m on the telephoto"); failing that, a sentence keeps only its "; " clauses and
", and" / ", or" parts that fit. A manual control or OIS the phone MAY have -- no phone row
(none saved, or one we have no notes on), or a row that says "Check the camera app's Pro
video mode" -- is never stated as fact: those parts (only those: "Pick one light ..., and set
white balance ..." keeps its first part plain) ride in ONE sentence per feature starting "If
your camera app has a Pro video mode: ..." or "If your phone has optical stabilisation
(OIS): ...". A sentence that already says "if your phone ..." stays as it is, and a lens or
HDR sentence with no condition of its own is removed for an unknown phone (it is not assumed
to have that lens). A standing rule written to the coach about "the creator" (how Meera
advises) is never a step. The lines are written to the creator; the one word swap left
(`_CREATOR_VOICE`) keeps a settings row's aside about the nose out of its step.

A side lead ("Turn so the light is on your left.") opens only a row where the light sits to
one side (`_SIDE_ROWS`: a window beside the creator for move_you, a movable main light for
move_light); every other row's own advice would contradict it.

Known limits: a sentence naming a control is removed on a phone without it even when it
only explains ("Auto white balance can shift colours mid-video ..." on an auto-only phone);
an fps value is not checked against the phone's max_fps; a sentence is cut only at the
English separators above, so a Hinglish sentence joined by "aur" / "ya" that names a
missing control is removed whole; a settings row's values stay English under Hinglish
labels.

A step keeps whole sentences only, at most `MAX_STEP_CHARS` long; the first kept sentence
is always whole. The side lead and the conditional prefix are templated per language and
carry no number, and a line states only numbers its row's advice states (the tests pin
every line), so every number in a step is the cited row's own.

The phone-feature helpers live here (not in frame_check.py) so frame_check can import this
module without a cycle; frame_check re-exports them under the same names.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, NamedTuple

from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_ROWS, NAME_FIELD

LANGS: tuple[str, ...] = ("en", "hi")
DEFAULT_LANG = "en"

# A rendered step is at most this long (whole sentences; the first is always whole).
MAX_STEP_CHARS = 320

STEP_KINDS: tuple[str, ...] = ("move_you", "move_phone", "move_light", "settings")
SIDES: tuple[str, ...] = ("your_left", "your_right", "none")

# --- scene enums -------------------------------------------------------------------------------

SCENE_VALUES: dict[str, tuple[str, ...]] = {
    "usable": ("yes", "too_dark", "lens_covered", "blank", "too_blurry"),
    "place": (
        "bedroom", "living_room", "kitchen", "desk", "studio", "street", "park", "rooftop",
        "market", "other_indoor", "other_outdoor", "unknown",
    ),
    "light": (
        "window", "sun", "shade", "ring_light", "lamp", "tube_light", "ceiling_light", "mixed",
        "low_light", "unknown",
    ),
    "light_side": ("your_left", "your_right", "in_front", "behind_you", "above", "unknown"),
    "background": (
        "clean", "cluttered", "bright_window_behind", "plain_wall_close", "busy", "unknown",
    ),
    "phone_height": ("eye_level", "below_eyes", "above_eyes", "unknown"),
    "framing": ("close_up", "chest_up", "waist_up", "full_body", "unknown"),
    "others_in_frame": ("yes", "no"),
}
# A `usable` the model did write but not as one of its values ("no", "unusable", false):
# never read as "yes" -- the photo is treated as not judgeable (see `normalize_scene`).
USABLE_UNCLEAR = "unclear"
# What an unknown or missing value becomes. `usable` is special (see `normalize_scene`).
_SCENE_DEFAULTS: dict[str, str] = {
    field: ("no" if field == "others_in_frame" else "unknown")
    for field in SCENE_VALUES
    if field != "usable"
}


def _norm_enum(value: Any) -> str | None:
    """A model-picked enum value, compared case- and spacing-insensitively ("Your Left",
    "your-left" -> "your_left"). Anything that is not a string -> None."""
    if not isinstance(value, str):
        return None
    return re.sub(r"[\s-]+", "_", value.strip().lower())


def normalize_lang(raw: Any) -> str:
    """The reply's `lang` -> "en" or "hi"; anything else -> "en"."""
    value = _norm_enum(raw)
    return value if value in LANGS else DEFAULT_LANG


def _usable(raw: Any) -> str:
    """The model's `usable` -> "yes" ONLY when it is "yes" (any case, a closing "." or "!"
    allowed: "Yes.") or missing;
    one of the unusable reasons, also when written with something after it ("Too_Dark.",
    "too_dark (black frame)"); anything else -- "no", "unusable", false, 0, a list -- is
    `USABLE_UNCLEAR`: the photo is not judged."""
    if raw is None:
        return "yes"
    value = _norm_enum(raw)
    if value and value.rstrip(".!") == "yes":
        return "yes"
    if value:
        for reason in SCENE_VALUES["usable"][1:]:
            if value.startswith(reason):
                return reason
    return USABLE_UNCLEAR


def normalize_scene(raw: Any) -> dict[str, str] | None:
    """The model's `scene` object -> every field of `SCENE_VALUES` with a known value, or None
    when `raw` is not an object.

    `usable` is "yes" only when it says exactly that or is missing; an unusable reason stays
    that reason, and anything else becomes `USABLE_UNCLEAR` (a garbled verdict, or a plain
    "no", is never read as "yes": the photo is not judged). Any other field with an unknown or
    missing value becomes "unknown" (`others_in_frame`: "no"); keys outside `SCENE_VALUES` (a
    "text", a "description") are ignored."""
    if not isinstance(raw, dict):
        return None
    out: dict[str, str] = {"usable": _usable(raw.get("usable"))}
    for field, default in _SCENE_DEFAULTS.items():
        value = _norm_enum(raw.get(field))
        out[field] = value if value in SCENE_VALUES[field] else default
    return out


def _lang(lang: Any) -> str:
    return lang if lang in LANGS else DEFAULT_LANG


# --- what_i_see ----------------------------------------------------------------------------

_PLACE: dict[str, dict[str, str]] = {
    "bedroom": {"en": "you're in a bedroom", "hi": "aap bedroom mein ho"},
    "living_room": {"en": "you're in a living room", "hi": "aap living room mein ho"},
    "kitchen": {"en": "you're in a kitchen", "hi": "aap kitchen mein ho"},
    "desk": {"en": "you're at a desk", "hi": "aap desk pe ho"},
    "studio": {"en": "you're in a studio", "hi": "aap studio mein ho"},
    "street": {"en": "you're on a street", "hi": "aap street pe ho"},
    "park": {"en": "you're in a park", "hi": "aap park mein ho"},
    "rooftop": {"en": "you're on a rooftop", "hi": "aap rooftop pe ho"},
    "market": {"en": "you're in a market", "hi": "aap market mein ho"},
    "other_indoor": {"en": "you're indoors", "hi": "aap indoor ho"},
    "other_outdoor": {"en": "you're outdoors", "hi": "aap bahar ho"},
}
_LIGHT: dict[str, dict[str, str]] = {
    "window": {"en": "window light", "hi": "window ki light"},
    "sun": {"en": "direct sunlight", "hi": "seedhi dhoop"},
    "shade": {"en": "open shade", "hi": "chhaon ki light"},
    "ring_light": {"en": "a ring light", "hi": "ring light"},
    "lamp": {"en": "lamp light", "hi": "lamp ki light"},
    "tube_light": {"en": "tube light", "hi": "tube light"},
    "ceiling_light": {"en": "ceiling light", "hi": "ceiling light"},
    "mixed": {"en": "lights of different colours", "hi": "alag alag colour ki light"},
    "low_light": {"en": "low light", "hi": "kam light"},
}
_LIGHT_SIDE: dict[str, dict[str, str]] = {
    "your_left": {"en": "from your left", "hi": "aapke left se"},
    "your_right": {"en": "from your right", "hi": "aapke right se"},
    "in_front": {"en": "from in front of you", "hi": "aapke saamne se"},
    "behind_you": {"en": "from behind you", "hi": "aapke peeche se"},
    "above": {"en": "from above", "hi": "upar se"},
}
_BACKGROUND: dict[str, dict[str, str]] = {
    "clean": {"en": "a clean background", "hi": "background clean hai"},
    "cluttered": {"en": "a cluttered background", "hi": "background cluttered hai"},
    "bright_window_behind": {
        "en": "a bright window behind you", "hi": "aapke peeche bright window hai",
    },
    "plain_wall_close": {
        "en": "a plain wall close behind you", "hi": "aapke peeche paas mein plain wall hai",
    },
    "busy": {"en": "a busy background", "hi": "background busy hai"},
}
_PHONE_HEIGHT: dict[str, dict[str, str]] = {
    "eye_level": {"en": "phone at eye level", "hi": "phone eye level pe hai"},
    "below_eyes": {"en": "phone below your eyes", "hi": "phone aapki aankhon se neeche hai"},
    "above_eyes": {"en": "phone above your eyes", "hi": "phone aapki aankhon se upar hai"},
}
_FRAMING: dict[str, dict[str, str]] = {
    "close_up": {"en": "framed as a close-up", "hi": "framing close-up hai"},
    "chest_up": {"en": "framed chest up", "hi": "framing chest up hai"},
    "waist_up": {"en": "framed waist up", "hi": "framing waist up hai"},
    "full_body": {"en": "framed full length", "hi": "framing full length hai"},
}

WHAT_I_SEE_OPENING: dict[str, str] = {"en": "I can see ", "hi": "Mujhe dikh raha hai: "}
# Every scene field unknown: say so honestly rather than guess.
WHAT_I_SEE_ALL_UNKNOWN: dict[str, str] = {
    "en": "I can see the shot, but I can't tell the room or the light clearly.",
    "hi": "Shot dikh raha hai, par room ya light saaf samajh nahi aa rahi.",
}
# The ONE clause about anyone else in the frame -- nothing more about them, ever.
WHAT_I_SEE_OTHERS: dict[str, str] = {
    "en": "Someone else is in the frame too; I'm only looking at the shot.",
    "hi": "Frame mein koi aur bhi hai; main sirf shot dekh rahi hoon.",
}
# An unusable photo: one fixed line per reason, with what to do instead.
WHAT_I_SEE_UNUSABLE: dict[str, dict[str, str]] = {
    "too_dark": {
        "en": "It's too dark to judge anything here; retake the photo with more light on you.",
        "hi": "Photo itni dark hai ki kuch judge nahi ho pa raha; zyada light mein dobara photo lo.",
    },
    "lens_covered": {
        "en": "The lens looks covered, so I can't see the shot; wipe or uncover the lens and "
              "retake the photo.",
        "hi": "Lens dhaka hua lag raha hai, shot nahi dikh raha; lens saaf karke dobara photo lo.",
    },
    "blank": {
        "en": "The photo looks blank, so there's nothing to judge; please retake it.",
        "hi": "Photo blank lag rahi hai, judge karne ko kuch nahi hai; please dobara photo lo.",
    },
    "too_blurry": {
        "en": "The photo is too blurry to judge; hold the phone steady and retake it.",
        "hi": "Photo itni blurry hai ki judge nahi ho pa raha; phone steady pakad ke dobara photo lo.",
    },
    USABLE_UNCLEAR: {
        "en": "I couldn't judge this photo clearly; please retake it and send it again.",
        "hi": "Yeh photo theek se judge nahi ho payi; please dobara photo lo aur bhejo.",
    },
}


def _light_clause(light: str, side: str, lang: str) -> str | None:
    light_text = _LIGHT.get(light, {}).get(lang)
    side_text = _LIGHT_SIDE.get(side, {}).get(lang)
    if lang == "hi":
        if light_text and side_text:
            return f"{light_text} {side_text} aa rahi hai"
        if light_text:
            return f"{light_text} hai"
        if side_text:
            return f"light {side_text} aa rahi hai"
        return None
    if light_text and side_text:
        return f"{light_text} {side_text}"
    if light_text:
        return light_text
    if side_text:
        return f"light {side_text}"
    return None


def render_what_i_see(scene: dict[str, str] | None, lang: str) -> str:
    """One coach-voice line on what the photo shows, from the scene values only. Unknown
    parts are left out; all unknown -> `WHAT_I_SEE_ALL_UNKNOWN`; an unusable photo -> its one
    fixed line (`WHAT_I_SEE_UNUSABLE`); someone else in the frame -> `WHAT_I_SEE_OTHERS`
    appended. A scene that is not a normalized dict -> "" (nothing to describe)."""
    if not isinstance(scene, dict):
        return ""
    lang = _lang(lang)
    usable = scene.get("usable", "yes")
    if usable != "yes":
        line = WHAT_I_SEE_UNUSABLE.get(usable)
        return line[lang] if line else ""
    clauses = [
        _PLACE.get(scene.get("place", ""), {}).get(lang),
        _light_clause(scene.get("light", ""), scene.get("light_side", ""), lang),
        _BACKGROUND.get(scene.get("background", ""), {}).get(lang),
        _PHONE_HEIGHT.get(scene.get("phone_height", ""), {}).get(lang),
        _FRAMING.get(scene.get("framing", ""), {}).get(lang),
    ]
    kept = [c for c in clauses if c]
    if kept:
        line = WHAT_I_SEE_OPENING[lang] + ", ".join(kept) + "."
    else:
        line = WHAT_I_SEE_ALL_UNKNOWN[lang]
    if scene.get("others_in_frame") == "yes":
        line += " " + WHAT_I_SEE_OTHERS[lang]
    return line


# --- ok / cant_tell ----------------------------------------------------------------------------

# What is already working. Friendly, no numbers, no growth words, nothing about the person
# ("light on your face" is about the light).
OK_LINES: dict[str, dict[str, str]] = {
    "light_soft_on_face": {
        "en": "The light on your face is soft, with gentle shadows.",
        "hi": "Face pe light soft hai, shadows gentle hain.",
    },
    "face_evenly_lit": {
        "en": "The light on your face is even, with no harsh patches.",
        "hi": "Face pe light even hai, koi harsh patch nahi hai.",
    },
    "light_from_side": {
        "en": "The light comes from the side, which gives the shot some depth.",
        "hi": "Light side se aa rahi hai, isse shot mein depth aati hai.",
    },
    "background_clean": {
        "en": "The background is clean and doesn't pull the eye away.",
        "hi": "Background clean hai, dhyan nahi kheenchta.",
    },
    "background_has_depth": {
        "en": "There's space between you and the background, so the shot has depth.",
        "hi": "Aapke aur background ke beech jagah hai, isliye shot mein depth hai.",
    },
    "phone_at_eye_level": {
        "en": "The phone is at eye level, so it feels like natural eye contact.",
        "hi": "Phone eye level pe hai, isliye eye contact natural lagta hai.",
    },
    "framing_fits": {
        "en": "The framing suits this kind of shot.",
        "hi": "Framing is shot ke liye sahi hai.",
    },
    "no_window_behind": {
        "en": "There's no bright window behind you fighting for attention.",
        "hi": "Aapke peeche koi bright window nahi hai, achha hai.",
    },
    "single_light_colour": {
        "en": "The light is all the same colour, so the tones stay steady.",
        "hi": "Saari light same colour ki hai, isliye tones steady rehte hain.",
    },
}

# What one photo cannot show.
CANT_TELL_LINES: dict[str, dict[str, str]] = {
    "audio": {
        "en": "A photo can't tell me how your audio sounds.",
        "hi": "Photo se audio ka pata nahi chalta.",
    },
    "light_outside_frame": {
        "en": "I can't see lights outside the frame, like a lamp off to the side.",
        "hi": "Frame ke bahar ki light, jaise side mein rakha lamp, photo mein nahi dikhti.",
    },
    "room_behind_phone": {
        "en": "I can't see the room behind the phone.",
        "hi": "Phone ke peeche ka room photo mein nahi dikhta.",
    },
    "can_you_move": {
        "en": "I can't tell whether you have room to move or turn.",
        "hi": "Pata nahi chalta ki aapke paas move ya turn karne ki jagah hai ya nahi.",
    },
    "shake_or_motion": {
        "en": "A still photo can't show shake or movement while you film.",
        "hi": "Still photo mein shake ya movement nahi dikhti.",
    },
    "light_changes_over_time": {
        "en": "I can't tell whether the light will change while you shoot.",
        "hi": "Pata nahi chalta ki shoot ke dauraan light badlegi ya nahi.",
    },
    "exact_distance": {
        "en": "I can't judge the exact distance between you and the phone.",
        "hi": "Phone aur aapke beech ki exact distance photo se pata nahi chalti.",
    },
    "focus_while_moving": {
        "en": "I can't tell whether focus holds while you move.",
        "hi": "Aap move karoge to focus tikega ya nahi, yeh photo se pata nahi chalta.",
    },
}


def render_lines(ids: Any, table: dict[str, dict[str, str]], lang: str, limit: int = 3) -> list[str]:
    """Model-picked ids -> the table's fixed lines in `lang`, in the model's order, each id
    once, at most `limit`. An unknown id, a non-string, or a non-list `ids` renders nothing."""
    if not isinstance(ids, (list, tuple)) or limit <= 0:
        return []
    lang = _lang(lang)
    out: list[str] = []
    seen: set[str] = set()
    for item in ids:
        key = _norm_enum(item)
        if key is None or key in seen or key not in table:
            continue
        seen.add(key)
        out.append(table[key][lang])
        if len(out) >= limit:
            break
    return out


# --- lenses, manual controls, OIS and HDR vs the creator's phone -------------------------------

# A zoom lens by name, or an "Nx" lens: N above 1 is a telephoto, below 1 the ultrawide.
# Plain "zoom" is not a lens (digital zoom is on every phone).
_TELEPHOTO_RE = re.compile(
    r"(?<![a-z])(?:tele(?:photo)?|periscope|zoom\s+lens|optical\s+zoom)(?![a-z])", re.IGNORECASE
)
_ULTRAWIDE_RE = re.compile(r"(?<![a-z])(?:ultra[\s-]?wide|wide[\s-]angle)(?![a-z])", re.IGNORECASE)
_LENS_FACTOR_RE = re.compile(r"(?<![\d.])(\d+(?:\.\d+)?)\s*x(?![a-z])", re.IGNORECASE)
# A run of lens factors in one row: "2x-3x", "3x/3.5x", "2x/3x/3.5x".
_FACTOR_RUN_RE = re.compile(
    r"(?<![\d.])\d+(?:\.\d+)?\s*x(?:\s*[-/]\s*\d+(?:\.\d+)?\s*x)+(?![a-z])", re.IGNORECASE
)
# Manual controls: exposure lock and the brightness slider are on every phone and are not here.
_MANUAL_RE = re.compile(
    r"(?<![a-z])(?:iso|shutter|white[\s-]?balance|wb|kelvin|fps|frames?\s*(?:per\s*sec(?:ond)?|/\s*s)"
    r"|pro\s+(?:video\s+)?mode|manual)(?![a-z])"
    r"|(?<![\d.])\d{4}\s*k(?![a-z])"
    r"|(?<![\d.])1\s*(?:[/⁄∕]|upon|over)\s*\d{2,4}(?!\d)",
    re.IGNORECASE,
)
# Optical image stabilisation is hardware: "rely on OIS" is wrong advice on a phone without it.
_OIS_RE = re.compile(r"(?<![a-z])(?:ois|optical\s+image\s+stabili[sz]ation)(?![a-z])", re.IGNORECASE)
# HDR video is a mode some phones lack (the phone row's log_hdr).
_HDR_RE = re.compile(r"(?<![a-z])hdr(?![a-z])", re.IGNORECASE)
# "If your phone has a zoom lens", "if your camera app has a Pro video mode", "agar aapke
# phone mein ..." -- the only way to name a lens or control for a phone we do not know.
_CONDITIONAL_RE = re.compile(
    r"\b(?:if|agar)\s+(?:your|the|aapke|aapka|tumhare|tumhara)\s+(?:phone|camera)\b", re.IGNORECASE
)
_HAS_NOT_RE = re.compile(r"^\s*(?:no|none)\b", re.IGNORECASE)
_HAS_YES_RE = re.compile(r"^\s*yes\b", re.IGNORECASE)
# Phone-row fields written "Yes ..." or "No ...". Any other value ("Check the camera app's Pro
# video mode") is unsure: the phone MAY have it, so it is never stated as fact.
_YES_NO_FIELDS: frozenset[str] = frozenset({"manual_video", "ois"})


def _phone_factors(phone_row: dict[str, Any]) -> frozenset[float]:
    """The telephoto factors the phone row names ("3x and 6x periscope" -> {3, 6}; "32MP 2x"
    -> {2}; "None" -> none)."""
    value = phone_row.get("telephoto")
    if not isinstance(value, str) or _HAS_NOT_RE.match(value):
        return frozenset()
    return frozenset(f for f in (float(n) for n in _LENS_FACTOR_RE.findall(value)) if f > 1)


def _feature_state(phone_row: dict[str, Any] | None, feature: str, text: str = "") -> str:
    """Whether the creator's phone has `feature`, as its row says: "yes", "no" or "unknown".

    No phone row (none saved, or one we have no notes on) -> "unknown". A missing, empty,
    "None" or "No ..." field -> "no". manual_video and ois: "Yes ..." -> "yes", anything else
    ("Check the camera app's Pro video mode") -> "unknown". log_hdr: "yes" only when it names
    HDR. telephoto: every "Nx" factor above 1 that `text` names must be one of the phone's own
    ("3x" on a 2x-telephoto phone is a digital crop, not its lens)."""
    if phone_row is None:
        return "unknown"
    value = phone_row.get(feature)
    if not isinstance(value, str) or not value.strip() or _HAS_NOT_RE.match(value):
        return "no"
    if feature in _YES_NO_FIELDS:
        return "yes" if _HAS_YES_RE.match(value) else "unknown"
    if feature == "log_hdr":
        return "yes" if _HDR_RE.search(value) else "no"
    if feature == "telephoto":
        wanted = {f for f in (float(n) for n in _LENS_FACTOR_RE.findall(text)) if f > 1}
        if not wanted <= _phone_factors(phone_row):
            return "no"
    return "yes"


def _phone_has(phone_row: dict[str, Any], field: str) -> bool:
    """The phone row says the phone surely has `field` ("None", "No (auto only)" and an unsure
    "Check the camera app's Pro video mode" do not)."""
    return _feature_state(phone_row, field) == "yes"


def phone_features_named(text: str) -> frozenset[str]:
    """The phone-row fields a text leans on: telephoto, ultrawide, manual_video, ois, log_hdr."""
    factors = [float(n) for n in _LENS_FACTOR_RE.findall(text)]
    needs: set[str] = set()
    if _TELEPHOTO_RE.search(text) or any(f > 1 for f in factors):
        needs.add("telephoto")
    if _ULTRAWIDE_RE.search(text) or any(f < 1 for f in factors):
        needs.add("ultrawide")
    if _MANUAL_RE.search(text):
        needs.add("manual_video")
    if _OIS_RE.search(text):
        needs.add("ois")
    if _HDR_RE.search(text):
        needs.add("log_hdr")
    return frozenset(needs)


def _states(
    text: str, phone_row: dict[str, Any] | None, lacking: frozenset[str] = frozenset()
) -> tuple[frozenset[str], frozenset[str]]:
    """(what the phone lacks, what it may or may not have) among the features `text` names.
    `lacking` counts as missing whatever the phone row says (a settings row whose lens fell
    back to the main camera: its "2.5m on the telephoto" no longer applies)."""
    no: set[str] = set()
    unsure: set[str] = set()
    for feature in phone_features_named(text):
        state = "no" if feature in lacking else _feature_state(phone_row, feature, text)
        if state == "no":
            no.add(feature)
        elif state == "unknown":
            unsure.add(feature)
    return frozenset(no), frozenset(unsure)


def step_fits_phone(text: str, phone_row: dict[str, Any] | None) -> bool:
    """True when, sentence by sentence, every lens, manual control, OIS or HDR the step names
    is on the creator's phone (`phone_row`, whichever row the step cites). A feature the phone
    MAY have (no phone row known, or a row that says "Check ...") only counts inside a sentence
    that is itself conditional ("if your phone ...", "If your camera app has a Pro video
    mode: ...")."""
    for sentence in _split_sentences(text) or [text]:
        no, unsure = _states(sentence, phone_row)
        if no or (unsure and not _CONDITIONAL_RE.search(sentence)):
            return False
    return True


# --- steps: per-type advice ----------------------------------------------------------------------

# The one advice field render_step reads, per sentence-style row type.
_ADVICE_FIELD: dict[str, str] = {
    "failure_case": "fix",
    "flicker_rule": "fix",
    "lighting_rule": "instruction",
    "window_lighting_rule": "instruction",
    "mixed_light_rule": "recommendation",
    "indian_home_lighting_rule": "guidance",
    "background_repair_rule": "first_fix",
    "background_rule": "fix",  # only the "bad background" row has one; the other renders None
    "lighting_fix_order": "first_move",
    "lighting_look": "setup",
    "subject_positioning_rule": "instruction",
    "sunset_to_night_step": "instruction",
    "indian_creator_scene_checklist": "first_choice",
    "permanent_rule": "rule",
}
# Settings rows: (field, label) in the order the creator gets them -- what every phone can
# do first (lens, distance, framing, extra light), then the manual controls.
_SETTINGS_PARTS: dict[str, tuple[tuple[str, str], ...]] = {
    "camera_technical_setting": (
        ("phone_camera", "Lens"),
        ("distance", "Distance"),
        ("framing", "Framing"),
        ("fps", "FPS"),
        ("shutter", "Shutter"),
        ("iso", "ISO"),
        ("white_balance", "White balance"),
        ("ev", "EV"),
        ("stabilization", "Stabilization"),
    ),
    "night_video_setting": (
        ("lens", "Lens"),
        ("extra_light", "Extra light"),
        ("fps", "FPS"),
        ("shutter", "Shutter"),
        ("iso", "ISO"),
        ("white_balance", "White balance"),
        ("stabilization", "Stabilization"),
    ),
}
# A settings label in a Hinglish reply; a label not here (Lens, FPS, Shutter, ISO, White
# balance, EV) is the same word in both.
_SETTINGS_LABEL_HI: dict[str, str] = {
    "Distance": "Doori",
    "Framing": "Frame",
    "Extra light": "Aur light",
    "Stabilization": "Phone steady",
}
# camera_height_rule: only a height that IS the advice ("Phone at eye-level: ..."). The other
# rows describe what a deliberate choice looks like ("Low angle: ... increases perceived
# scale"), three of them by naming parts of the face ("sees more chin/nostril underside") --
# under the creator's own photo that reads as a remark on them, not as a step.
_CAMERA_HEIGHT_STEPS: frozenset[str] = frozenset({"eye-level"})

# Row types whose own fields are notes for a coach ("Key roughly 30-45 deg horizontally",
# "Phone placement: near face-forward axis ..."): a step is written ONLY from the row's
# creator-voice line, and a row of these types without one (Loop and Butterfly, which can't
# be said without naming parts of the face) is never a step.
_LINE_ONLY_TYPES: frozenset[str] = frozenset({"lighting_angle_rule", "portrait_lighting_pattern"})

# The row types render_step writes an instruction for. Left out, with the reason:
#   phone_hardware -- the creator's phone only gates other rows' advice;
#   physics_principle -- a definition of how light behaves, not a thing to do;
#   coordinate_system_note, lighting_workflow -- rules for how Meera words and orders her
#     advice, not a step for the creator.
STEP_TEXT_TYPES: frozenset[str] = frozenset(
    set(_ADVICE_FIELD) | set(_SETTINGS_PARTS) | {"camera_height_rule"} | _LINE_ONLY_TYPES
)
# The row types a creator-voice line may be written for: every sentence-style type (a
# settings row stays its labelled parts).
_LINE_TYPES: frozenset[str] = STEP_TEXT_TYPES - frozenset(_SETTINGS_PARTS)

# --- creator-voice lines ---------------------------------------------------------------------

CREATOR_STEP_LINES_PATH = Path(__file__).parent / "knowledge" / "creator_step_lines.jsonl"
# The most a line may be in either language; the step cap (`MAX_STEP_CHARS`) leaves room
# for a side lead.
LINE_MAX_CHARS = 300


class CreatorStepLinesError(ValueError):
    """The creator-voice lines file is malformed. Raised at import, never at request time."""


def load_creator_step_lines(
    path: Path = CREATOR_STEP_LINES_PATH, rows: list[dict[str, Any]] | None = None
) -> dict[tuple[str, str], dict[str, str]]:
    """(data_type, row name) -> {"en", "hi"}: one step line per row, said to the creator.

    Each line of the file is {"data_type", "name", "en", "hi"} with `name` the row's own
    name (`NAME_FIELD`), exactly. Raises `CreatorStepLinesError` on the first line that is
    not such an object, whose type is not a sentence-style step type (`_LINE_TYPES`), whose
    (data_type, name) does not name exactly ONE row of `rows` (default: the knowledge rows),
    that repeats a key, or whose en or hi is empty or over `LINE_MAX_CHARS`; and on an empty
    file."""
    rows = CREATOR_KNOWLEDGE_ROWS if rows is None else rows
    row_count: dict[tuple[str, str], int] = {}
    for r in rows:
        dt = r.get("data_type")
        name = r.get(NAME_FIELD.get(dt, ""))
        if isinstance(dt, str) and isinstance(name, str):
            key = (dt, name.strip())
            row_count[key] = row_count.get(key, 0) + 1
    lines: dict[tuple[str, str], dict[str, str]] = {}
    with path.open(encoding="utf-8") as fh:
        for lineno, raw in enumerate(fh, start=1):
            if not raw.strip():
                continue
            where = f"{path.name} line {lineno}"
            try:
                obj = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise CreatorStepLinesError(f"{where}: invalid JSON ({exc.msg})") from exc
            if not isinstance(obj, dict) or set(obj) != {"data_type", "name", *LANGS}:
                raise CreatorStepLinesError(f"{where}: not a {{data_type, name, en, hi}} object")
            dt, name = obj["data_type"], obj["name"]
            if not isinstance(dt, str) or not isinstance(name, str):
                raise CreatorStepLinesError(f"{where}: data_type and name must be text")
            key = (dt, name.strip())
            if dt not in _LINE_TYPES:
                raise CreatorStepLinesError(f"{where}: {dt!r} is not a type a step line is written for")
            found = row_count.get(key, 0)
            if found != 1:
                raise CreatorStepLinesError(f"{where}: {key} names {found} rows, not exactly one")
            if key in lines:
                raise CreatorStepLinesError(f"{where}: {key} has a line already")
            for lang in LANGS:
                text = obj[lang]
                if not isinstance(text, str) or not text.strip():
                    raise CreatorStepLinesError(f"{where}: {key} {lang} is empty")
                if len(text) > LINE_MAX_CHARS:
                    raise CreatorStepLinesError(
                        f"{where}: {key} {lang} is {len(text)} chars, over {LINE_MAX_CHARS}"
                    )
            lines[key] = {lang: _one_line(obj[lang]) for lang in LANGS}
    if not lines:
        raise CreatorStepLinesError(f"{path.name}: no lines")
    return lines


def _one_line(text: str) -> str:
    return " ".join(text.split())


CREATOR_STEP_LINES: dict[tuple[str, str], dict[str, str]] = load_creator_step_lines()


def _row_key(row: dict[str, Any]) -> tuple[str, str] | None:
    """(data_type, the row's own name, stripped), or None."""
    dt = row.get("data_type")
    name = row.get(NAME_FIELD.get(dt, "")) if isinstance(dt, str) else None
    if not isinstance(name, str):
        return None
    return dt, name.strip()  # type: ignore[return-value]


def _line_for(row: dict[str, Any]) -> dict[str, str] | None:
    """The row's creator-voice line ({"en", "hi"}), or None."""
    key = _row_key(row)
    return CREATOR_STEP_LINES.get(key) if key else None


# --- step topic labels ---------------------------------------------------------------------------

CREATOR_STEP_LABELS_PATH = Path(__file__).parent / "knowledge" / "creator_step_labels.jsonl"
# A label is a short phrase the app shows under a step ("From the guide: Window behind you").
LABEL_MAX_CHARS = 40
_DIGIT_RE = re.compile(r"\d")


class CreatorStepLabelsError(ValueError):
    """The step labels file is malformed. Raised at import, never at request time."""


def load_creator_step_labels(
    path: Path = CREATOR_STEP_LABELS_PATH, rows: list[dict[str, Any]] | None = None
) -> dict[tuple[str, str], dict[str, str]]:
    """(data_type, row name) -> {"en", "hi"}: one short topic label per row that can be a step.

    Each line of the file is {"data_type", "name", "en", "hi"} with `name` the row's own
    name (`NAME_FIELD`), exactly. Raises `CreatorStepLabelsError` when the file cannot be
    read or is empty, and on the first line that is not such an object, whose type is not a
    step type (`STEP_TEXT_TYPES`), whose (data_type, name) does not name exactly ONE row of
    `rows` (default: the knowledge rows), that repeats a key, or whose en or hi is empty,
    over `LABEL_MAX_CHARS`, holds a digit, or ends in a full stop (a label is a phrase)."""
    rows = CREATOR_KNOWLEDGE_ROWS if rows is None else rows
    row_count: dict[tuple[str, str], int] = {}
    for r in rows:
        key = _row_key(r) if isinstance(r, dict) else None
        if key is not None:
            row_count[key] = row_count.get(key, 0) + 1
    labels: dict[tuple[str, str], dict[str, str]] = {}
    try:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CreatorStepLabelsError(f"{path.name}: cannot be read ({exc})") from exc
    for lineno, raw in enumerate(raw_lines, start=1):
        if not raw.strip():
            continue
        where = f"{path.name} line {lineno}"
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise CreatorStepLabelsError(f"{where}: invalid JSON ({exc.msg})") from exc
        if not isinstance(obj, dict) or set(obj) != {"data_type", "name", *LANGS}:
            raise CreatorStepLabelsError(f"{where}: not a {{data_type, name, en, hi}} object")
        dt, name = obj["data_type"], obj["name"]
        if not isinstance(dt, str) or not isinstance(name, str):
            raise CreatorStepLabelsError(f"{where}: data_type and name must be text")
        key = (dt, name.strip())
        if dt not in STEP_TEXT_TYPES:
            raise CreatorStepLabelsError(f"{where}: {dt!r} is not a type a step is written for")
        found = row_count.get(key, 0)
        if found != 1:
            raise CreatorStepLabelsError(f"{where}: {key} names {found} rows, not exactly one")
        if key in labels:
            raise CreatorStepLabelsError(f"{where}: {key} has a label already")
        for lang in LANGS:
            text = obj[lang]
            if not isinstance(text, str) or not text.strip():
                raise CreatorStepLabelsError(f"{where}: {key} {lang} is empty")
            text = _one_line(text)
            if len(text) > LABEL_MAX_CHARS:
                raise CreatorStepLabelsError(
                    f"{where}: {key} {lang} is {len(text)} chars, over {LABEL_MAX_CHARS}"
                )
            if _DIGIT_RE.search(text):
                raise CreatorStepLabelsError(f"{where}: {key} {lang} has a digit: {text!r}")
            if text.endswith("."):
                raise CreatorStepLabelsError(f"{where}: {key} {lang} ends in a full stop")
        labels[key] = {lang: _one_line(obj[lang]) for lang in LANGS}
    if not labels:
        raise CreatorStepLabelsError(f"{path.name}: no labels")
    return labels


CREATOR_STEP_LABELS: dict[tuple[str, str], dict[str, str]] = load_creator_step_labels()


def step_label(row: dict[str, Any], lang: str) -> str | None:
    """The row's topic label in `lang` ("en" or "hi"; anything else is "en"), or None when
    the labels file has none for it (the caller shows its own note instead)."""
    key = _row_key(row) if isinstance(row, dict) else None
    labels = CREATOR_STEP_LABELS.get(key) if key else None
    return labels[_lang(lang)] if labels else None

# The side lead for kinds where the side matters; no number in any of them.
_SIDE_LEADS: dict[str, dict[str, dict[str, str]]] = {
    "move_you": {
        "your_left": {
            "en": "Turn so the light is on your left. ",
            "hi": "Aise baitho ki light aapke left side ho. ",
        },
        "your_right": {
            "en": "Turn so the light is on your right. ",
            "hi": "Aise baitho ki light aapke right side ho. ",
        },
    },
    "move_light": {
        "your_left": {"en": "Put the light on your left. ", "hi": "Light ko apne left side rakho. "},
        "your_right": {"en": "Put the light on your right. ", "hi": "Light ko apne right side rakho. "},
    },
}
# The rows a side lead may open, and for which kinds: (type, name field, name) -> kinds. Only
# a light that sits to ONE side of the creator: a window beside them (they turn: move_you --
# a window cannot be moved) or a movable main light to one side (move_light). Every other row
# gets no lead: its own advice puts the light in front, behind, above or nowhere ("Silhouette",
# "Butterfly", "Creator faces window", "Harsh midday sun", a ceiling light), or is not about
# where the light sits at all.
_SIDE_ROWS: dict[tuple[str, str, str], frozenset[str]] = {
    ("lighting_rule", "scenario", "Soft natural window light"): frozenset({"move_you"}),
    ("window_lighting_rule", "window_position", "Creator is 30-45 deg to window"): frozenset({"move_you"}),
    ("window_lighting_rule", "window_position", "Creator is 90 deg to window"): frozenset({"move_you"}),
    ("window_lighting_rule", "window_position", "Window beside creator"): frozenset({"move_you"}),
    ("indian_creator_scene_checklist", "setting", "Bedroom"): frozenset({"move_you"}),
    ("lighting_look", "look", "Low-key / dramatic"): frozenset({"move_light"}),
}

# The conditional sentences a feature the phone MAY have rides in (no phone known, or a row
# that says "Check ..."). A lens or HDR the phone may lack has none: that part is removed.
PRO_MODE_PREFIX: dict[str, str] = {
    "en": "If your camera app has a Pro video mode: ",
    "hi": "Agar aapke camera app mein Pro video mode hai: ",
}
OIS_PREFIX: dict[str, str] = {
    "en": "If your phone has optical stabilisation (OIS): ",
    "hi": "Agar aapke phone mein OIS (optical stabilisation) hai: ",
}
_CONDITIONAL_PREFIX: dict[str, dict[str, str]] = {"manual_video": PRO_MODE_PREFIX, "ois": OIS_PREFIX}
_CONDITIONAL_ORDER: tuple[str, ...] = ("manual_video", "ois")

# A sentence ends at . ! or ? followed by space and a capital, a digit or an opening quote.
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(])")
_PARENS_RE = re.compile(r"\s*\(([^()]*)\)")
_COACH_FACING_RE = re.compile(r"\bthe creator\b", re.IGNORECASE)
# A lens part's own fallback: "3x Telephoto (no telephoto: 1x Main, closer)".
_LENS_FALLBACK_RE = re.compile(
    r"^(?P<main>.*?)\s*\(no\s+(?P<feat>telephoto|ultrawide)\s*:\s*(?P<alt>[^()]*)\)\s*$",
    re.IGNORECASE,
)
# "in Pro/manual mode", "on a Pro-mode phone,", "On a phone with Pro/manual video," -- the
# conditional prefix already says it, so it is cut from an item riding in that sentence.
_PRO_PHRASE_RE = re.compile(
    r"\s*\b(?:in|on)\s+(?:a\s+)?(?:phone\s+with\s+)?pro(?:/manual)?(?:[\s-]mode)?"
    r"(?:\s+(?:phone|video))?\b,?",
    re.IGNORECASE,
)
# Where a sentence that does not fit the phone may be cut: its "; " clauses and its ", and" /
# ", or" parts. A settings part only at ", or " (its label lives in the first part).
_SENTENCE_SEPS: tuple[str, ...] = ("; ", ", and ", ", or ")
# The "else" branch of an if-your-phone-allows-it sentence. When the branch before it was
# removed (the phone lacks the control), the else branch IS the advice: its lead-in goes
# ("If your phone can't, cut down the clashing light colours" -> "Cut down ...";
# "Agar phone mein yeh nahi hai, toh alag-alag colour ..." -> "Alag-alag colour ...").
_ELSE_LEAD_RE = re.compile(
    r"^(?:(?:if it does not|if it doesn't|if not|otherwise|if your phone can(?:not|'t))\b,?\s*"
    r"|(?:agar (?:aapke )?phone mein yeh nahi hai|warna|nahi toh?)\b,?\s*(?:toh\s+)?)",
    re.IGNORECASE,
)
_SETTINGS_SEPS: tuple[str, ...] = (", or ",)
# Word swaps on the text of a row WITHOUT a creator-voice line (a line is used as written).
# Every sentence-style row that is a step has a line, so only a settings row's own text is
# left: its aside naming the nose goes. No number is added or changed.
_CREATOR_VOICE: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r",\s*like your nose,"), ""),  # "the nearest features, like your nose, look bigger"
)


def _text(row: dict[str, Any], field: str) -> str:
    value = row.get(field)
    return _one_line(value) if isinstance(value, str) else ""


def _creator_voice(text: str) -> str:
    for pattern, replacement in _CREATOR_VOICE:
        text = pattern.sub(replacement, text)
    return text


def _split_sentences(text: str) -> list[str]:
    return [s.strip() for s in _SENTENCE_SPLIT_RE.split(text) if s.strip()]


def _end(sentence: str) -> str:
    s = sentence.rstrip()
    return s if s[-1:] in ".!?" else s + "."


def _lower_first(text: str) -> str:
    """"Lock it" -> "lock it"; "ISO" and "EIS" keep their capitals."""
    if len(text) >= 2 and text[0].isupper() and text[1].islower():
        return text[0].lower() + text[1:]
    return text


def _strip_parens(text: str, drop: Any) -> str:
    """`text` without each bracketed aside for which `drop(content)` is true."""
    out = _PARENS_RE.sub(lambda m: "" if drop(m.group(1)) else m.group(0), text)
    return _one_line(out).strip(" ,;")


def _upper_first(text: str) -> str:
    return text[:1].upper() + text[1:]


def _split_pieces(text: str, seps: tuple[str, ...]) -> list[tuple[str, str]]:
    """`text` split at any of `seps` outside brackets -> [(separator before, piece)], the
    first piece's separator being "". A piece never ends inside "( ... )"."""
    out: list[tuple[str, str]] = []
    depth = 0
    start = 0
    before = ""
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif depth == 0:
            hit = next((s for s in seps if text.startswith(s, i)), None)
            if hit is not None:
                out.append((before, text[start:i].strip()))
                before = hit
                i += len(hit)
                start = i
                continue
        i += 1
    out.append((before, text[start:].strip()))
    return [(sep, piece) for sep, piece in out if piece]


def _narrow_factors(text: str, phone_row: dict[str, Any] | None) -> str:
    """A run of lens factors -> only those the phone has ("2x-3x Telephoto" on a 3x phone ->
    "3x Telephoto"; "(3x/3.5x)" on a 3.5x phone -> "(3.5x)"). Left as written when the phone
    has none of them (the part is then fitted as missing) or all of them."""
    if phone_row is None:
        return text
    have = _phone_factors(phone_row)
    if not have:
        return text

    def narrow(m: re.Match[str]) -> str:
        factors = _LENS_FACTOR_RE.findall(m.group(0))
        kept = [f for f in factors if float(f) in have or float(f) <= 1]
        if not any(float(f) > 1 for f in kept) or len(kept) == len(factors):
            return m.group(0)
        return "/".join(f + "x" for f in kept)

    return _FACTOR_RUN_RE.sub(narrow, text)


def _fit_one(
    text: str,
    phone_row: dict[str, Any] | None,
    lacking: frozenset[str],
    *,
    or_split: bool = True,
) -> tuple[str, str | None] | None:
    """One text (a sentence, a piece of one, or a settings part) against the creator's phone
    -> (text to keep, None) when it fits, (text, feature) when it must ride in that feature's
    conditional sentence, or None. Tried in order: the whole text; a text that already says
    "if your phone ..." (nothing it names is missing); without the bracketed asides that name
    what is missing or unsure -- never an OIS aside ("EIS off at night (OIS only)" without OIS
    is wrong advice); the conditional sentence, when the one unsure feature is manual video or
    OIS; the first of two " or " alternatives when it is a phrase of its own ("... exposure
    compensation (+EV) or HDR video mode" -> "... exposure compensation (+EV)")."""
    no, unsure = _states(text, phone_row, lacking)
    if not no and not unsure:
        return text, None
    if not no and _CONDITIONAL_RE.search(text):
        return text, None

    def drop(inner: str) -> bool:
        inner_no, inner_unsure = _states(inner, phone_row, lacking)
        return bool(inner_no or inner_unsure) and "ois" not in phone_features_named(inner)

    rest = _strip_parens(text, drop)
    if rest and rest != text and not any(_states(rest, phone_row, lacking)):
        return rest, None
    if not no and len(unsure) == 1:
        (feature,) = unsure
        if feature in _CONDITIONAL_PREFIX:
            return text, feature
    if or_split:
        alternatives = _split_top_level(text, " or ")
        first = alternatives[0].strip(" ,;")
        if len(alternatives) > 1 and len(first.split()) >= 4 and not any(_states(first, phone_row, lacking)):
            return first, None
    return None


def _split_top_level(text: str, sep: str) -> list[str]:
    return [piece for _, piece in _split_pieces(text, (sep,))] or [text]


def _fit(
    text: str,
    phone_row: dict[str, Any] | None,
    *,
    settings: bool = False,
    lacking: frozenset[str] = frozenset(),
) -> tuple[list[str], dict[str, list[str]]]:
    """One sentence (or settings part) against the creator's phone -> (the text to give as
    is, {feature: texts for that feature's conditional sentence}).

    A text that fits whole is kept whole. Otherwise it is cut at its "; " clauses and its
    ", and" / ", or" parts, and each part is fitted on its own (`_fit_one`): the parts that
    name nothing the phone lacks stay as they are, a part naming only what the phone MAY have
    goes to its conditional sentence, the rest is removed ("Pick one light ..., and set white
    balance for that face light" with no phone known -> "Pick one light ..." plus "If your
    camera app has a Pro video mode: set white balance ..."). An ", or" alternative whose
    alternative before it was removed is removed too (it would not read on its own). A
    settings part is cut only at ", or", and a later alternative only stays as plain text
    after the labelled first one ("Stabilization: Tripod (stabilization off), or handheld with
    OIS" on a phone without OIS -> "Stabilization: Tripod (stabilization off)")."""
    pieces = _split_pieces(text if settings else text.rstrip(". "), _SETTINGS_SEPS if settings else _SENTENCE_SEPS)
    whole = _fit_one(text, phone_row, lacking, or_split=len(pieces) <= 1)
    if whole is not None and whole[1] is None:
        return [whole[0]], {}
    plain: list[tuple[str, str]] = []
    conditional: dict[str, list[str]] = {}
    if len(pieces) > 1:
        before_dropped = False
        for index, (sep, piece) in enumerate(pieces):
            if before_dropped and _ELSE_LEAD_RE.match(piece):
                piece = _ELSE_LEAD_RE.sub("", piece)
                sep = "; " if sep == ", or " else sep
            fitted = _fit_one(piece, phone_row, lacking)
            if fitted is not None and fitted[1] is not None and settings:
                fitted = None  # an alternative without its label cannot stand in a sentence of its own
            if fitted is not None and fitted[1] is None and sep == ", or " and before_dropped:
                fitted = None
            if fitted is None:
                before_dropped = True
                if settings and index == 0:
                    break
                continue
            before_dropped = False
            kept, feature = fitted
            if feature is None:
                plain.append((sep, kept))
            else:
                conditional.setdefault(feature, []).append(kept)
    if plain:
        joined = plain[0][1] + "".join(sep + piece for sep, piece in plain[1:])
        return [joined if settings else _upper_first(joined)], conditional
    if whole is not None:
        return [], {whole[1]: [whole[0]]}  # type: ignore[dict-item]
    return [], conditional


def _conditional_item(text: str) -> str:
    """A sentence riding in a conditional sentence: without its own "in Pro/manual mode" (the
    prefix says it) and with its first colon softened, as the prefix ends in one
    ("Indoors under mains lights in Pro/manual mode: 25fps ..." -> "indoors under mains
    lights, 25fps ...")."""
    out = _one_line(_PRO_PHRASE_RE.sub("", text)).strip(" ,;")
    return _upper_first(out).replace(": ", ", ", 1)


def _settings_item(text: str, label: str) -> str:
    """A settings part riding in a conditional sentence: "Shutter: Auto, or 1/50 in Pro mode"
    -> "Shutter Auto, or 1/50"."""
    body = text[len(label) + 2:] if text.startswith(label + ": ") else text
    return _one_line(f"{label} {_PRO_PHRASE_RE.sub('', body)}").strip(" ,;")


def _assemble(lead: str, plain: list[str], conditional: dict[str, list[str]], lang: str) -> str | None:
    """lead + whole plain sentences + at most ONE conditional sentence per feature (Pro mode,
    then OIS), within `MAX_STEP_CHARS`. The first sentence is always kept whole; later ones
    only when they fit. The side lead is a hint: it is left off when it would push the first
    sentence over."""
    return _assemble_kept(lead, plain, conditional, lang)[0]


def _assemble_kept(
    lead: str, plain: list[str], conditional: dict[str, list[str]], lang: str
) -> tuple[str | None, int, dict[str, int]]:
    """`_assemble`, also saying how much of each list the text holds: (text, how many of
    `plain` from the front, {feature: how many of its items from the front}). A settings
    step's parts are cut to exactly these counts, so they are the parts the text shows."""
    first = _end(plain[0]) if plain else None
    if first is not None and len(lead + first) > MAX_STEP_CHARS:
        lead = ""
    text = lead
    body = False
    plain_kept = 0
    items_kept: dict[str, int] = {}
    for sentence in plain:
        candidate = text + ("" if not body else " ") + _end(sentence)
        if body and len(candidate) > MAX_STEP_CHARS:
            break
        text, body = candidate, True
        plain_kept += 1
    for feature in _CONDITIONAL_ORDER:
        prefix = _CONDITIONAL_PREFIX[feature][lang]
        items: list[str] = []
        for item in conditional.get(feature, []):
            piece = _lower_first(item.rstrip(". "))
            joined = prefix + "; ".join(items + [piece]) + "."
            candidate = text + (" " if body else "") + joined
            if (body or items) and len(candidate) > MAX_STEP_CHARS:
                break
            items.append(piece)
        if items:
            text = text + (" " if body else "") + prefix + "; ".join(items) + "."
            body = True
            items_kept[feature] = len(items)
    return (text if body else None), plain_kept, items_kept


def _coach_facing(row: dict[str, Any]) -> bool:
    """A standing rule written to the COACH about the creator ("Only suggest what the
    creator's phone can actually do", "Give every camera setting with its one-line reason,
    so the creator learns why") is how Meera advises, not advice for the creator."""
    return row.get("data_type") == "permanent_rule" and bool(
        _COACH_FACING_RE.search(_text(row, "rule"))
    )


def _sentence_advice(row: dict[str, Any]) -> str:
    dt = row.get("data_type")
    if dt == "camera_height_rule":
        position, result = _text(row, "camera_position"), _text(row, "perceived_result")
        if not position or not result or position.lower() not in _CAMERA_HEIGHT_STEPS:
            return ""
        return f"Phone at {_lower_first(position)}: {_lower_first(result.rstrip('.'))}."
    field = _ADVICE_FIELD.get(dt or "")
    return _creator_voice(_text(row, field)) if field else ""


def _add(into: dict[str, list[str]], more: dict[str, list[str]], shape: Any) -> None:
    for feature, items in more.items():
        into.setdefault(feature, []).extend(shape(item) for item in items)


def _render_sentences(
    advice: str, phone_row: dict[str, Any] | None
) -> tuple[list[str], dict[str, list[str]]]:
    plain: list[str] = []
    conditional: dict[str, list[str]] = {}
    before_dropped = False
    for sentence in _split_sentences(advice):
        if before_dropped and _ELSE_LEAD_RE.match(sentence):
            sentence = _upper_first(_ELSE_LEAD_RE.sub("", sentence))
        kept, more = _fit(_narrow_factors(sentence, phone_row), phone_row)
        before_dropped = not kept and not more
        for sentence_kept in kept:
            # "Otherwise switch the lamp off" after "Switch the lamp off, or move it ..." says
            # nothing new.
            said = _end(sentence_kept).rstrip(".").casefold()
            if not any(_end(p).casefold().startswith(said) for p in plain):
                plain.append(sentence_kept)
        _add(conditional, more, _conditional_item)
    return plain, conditional


def _lens_value(value: str, phone_row: dict[str, Any] | None) -> tuple[str, str | None]:
    """A lens part with its own no-lens fallback -> (the value that fits this phone, the
    feature it fell back from or None). The main value only when the phone has that lens at
    that factor; otherwise (an unknown phone included: the fallback works on every phone) the
    fallback."""
    m = _LENS_FALLBACK_RE.match(value)
    if not m:
        return value, None
    main, feat, alt = m.group("main").strip(), m.group("feat").lower(), m.group("alt").strip()
    if phone_row is not None and _feature_state(phone_row, feat, main) == "yes":
        return main, None
    return alt, feat


def _render_settings(
    row: dict[str, Any], phone_row: dict[str, Any] | None, lang: str
) -> tuple[list[tuple[str, str]], dict[str, list[tuple[str, str]]]]:
    """A settings row's parts fitted to the phone -> ([(label, "Label: value")] given as is,
    {feature: [(label, "Label value")] for that feature's conditional sentence}), labels in
    `lang`, in `_SETTINGS_PARTS` order."""
    plain: list[tuple[str, str]] = []
    conditional: dict[str, list[tuple[str, str]]] = {}
    lacking: set[str] = set()
    for field, label in _SETTINGS_PARTS[row["data_type"]]:
        if lang == "hi":
            label = _SETTINGS_LABEL_HI.get(label, label)
        value = _creator_voice(_text(row, field)).rstrip(".").strip()
        if not value:
            continue
        if field in ("phone_camera", "lens"):
            value, fell_back = _lens_value(_narrow_factors(value, phone_row), phone_row)
            if fell_back:
                lacking.add(fell_back)  # "Distance: 2.5m on the telephoto" no longer applies
        kept, more = _fit(f"{label}: {value}", phone_row, settings=True, lacking=frozenset(lacking))
        plain.extend((label, text) for text in kept)
        _add(conditional, more, lambda item, label=label: (label, _settings_item(item, label)))
    return plain, conditional


def _settings_part(label: str, text: str, prefix: str, feature: str | None) -> dict[str, Any]:
    """One settings part as data: its label and the value the text shows after it ("Lens: 1x
    Main" -> "1x Main"; a conditional "Shutter Auto, or 1/50" -> "Auto, or 1/50")."""
    value = text[len(prefix):] if text.startswith(prefix) else text
    return {
        "label": label,
        "value": value,
        "needs_pro": feature == "manual_video",
        "needs_ois": feature == "ois",
    }


def _side_lead(kind: str, row: dict[str, Any], side: Any, lang: str) -> str:
    """The templated side lead, only for a row and kind in `_SIDE_ROWS`."""
    fits = any(
        row.get("data_type") == dt and row.get(field) == name and kind in kinds
        for (dt, field, name), kinds in _SIDE_ROWS.items()
    )
    if not fits:
        return ""
    return _SIDE_LEADS.get(kind, {}).get(_norm_enum(side) or "", {}).get(lang, "")


class RenderedStep(NamedTuple):
    """A step as `render_step_parts` writes it: its text, and -- for a settings row only --
    the parts that text is built from ([{label, value, needs_pro, needs_ois}], in the text's
    order); None for every other row."""

    text: str
    parts: list[dict[str, Any]] | None


def render_step(
    kind: str,
    row: dict[str, Any],
    phone_row: dict[str, Any] | None,
    lang: str,
    side: str = "none",
) -> str | None:
    """One step's text, fitted to the creator's phone (see the module docstring): a
    settings row's labelled parts; any other row's creator-voice line in `lang`
    (`CREATOR_STEP_LINES`, used as written); a row with no line, its own advice field. None
    when the kind is unknown, the row's type has no instruction (`STEP_TEXT_TYPES`), the row
    is a coach-facing standing rule, the row has no advice (the "good background" row has no
    fix; a camera height other than eye level; a light-angle or portrait-pattern row with no
    line), or nothing fits the phone. `side` opens the step only for a row in `_SIDE_ROWS`."""
    rendered = render_step_parts(kind, row, phone_row, lang, side)
    return rendered.text if rendered is not None else None


def render_step_parts(
    kind: str,
    row: dict[str, Any],
    phone_row: dict[str, Any] | None,
    lang: str,
    side: str = "none",
) -> RenderedStep | None:
    """`render_step`, with a settings row's parts as data. The parts are the same fitted
    parts the text is assembled from, cut to exactly what the text holds (`_assemble_kept`):
    the plain parts first ("Lens: 1x Main" -> {"label": "Lens", "value": "1x Main"}), then
    those in the Pro video mode sentence (needs_pro), then those in the OIS sentence
    (needs_ois). Labels are in `lang`; values as the row writes them."""
    if kind not in STEP_KINDS or not isinstance(row, dict):
        return None
    if row.get("data_type") not in STEP_TEXT_TYPES or _coach_facing(row):
        return None
    lang = _lang(lang)
    lead = _side_lead(kind, row, side, lang)
    if row["data_type"] in _SETTINGS_PARTS:
        labelled_plain, labelled_conditional = _render_settings(row, phone_row, lang)
        plain = [text for _, text in labelled_plain]
        conditional = {
            feature: [text for _, text in items] for feature, items in labelled_conditional.items()
        }
        text, plain_kept, items_kept = _assemble_kept(lead, plain, conditional, lang)
        if text is None:
            return None
        parts = [_settings_part(label, t, label + ": ", None) for label, t in labelled_plain[:plain_kept]]
        for feature in _CONDITIONAL_ORDER:
            for label, item in labelled_conditional.get(feature, [])[: items_kept.get(feature, 0)]:
                parts.append(_settings_part(label, item.rstrip(". "), label + " ", feature))
        return RenderedStep(text, parts)
    line = _line_for(row)
    if line is not None:
        advice = line[lang]
    elif row["data_type"] in _LINE_ONLY_TYPES:
        return None
    else:
        advice = _sentence_advice(row)
    plain, conditional = _render_sentences(advice, phone_row)
    text = _assemble(lead, plain, conditional, lang)
    return RenderedStep(text, None) if text is not None else None


__all__ = [
    "CANT_TELL_LINES",
    "CREATOR_STEP_LABELS",
    "CREATOR_STEP_LABELS_PATH",
    "CREATOR_STEP_LINES",
    "CREATOR_STEP_LINES_PATH",
    "CreatorStepLabelsError",
    "CreatorStepLinesError",
    "LABEL_MAX_CHARS",
    "LANGS",
    "LINE_MAX_CHARS",
    "MAX_STEP_CHARS",
    "OIS_PREFIX",
    "OK_LINES",
    "PRO_MODE_PREFIX",
    "RenderedStep",
    "SCENE_VALUES",
    "STEP_KINDS",
    "STEP_TEXT_TYPES",
    "USABLE_UNCLEAR",
    "load_creator_step_labels",
    "load_creator_step_lines",
    "normalize_lang",
    "normalize_scene",
    "phone_features_named",
    "render_lines",
    "render_step",
    "render_step_parts",
    "render_what_i_see",
    "step_fits_phone",
    "step_label",
]
