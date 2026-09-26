"""Shot card proof eval: scoring by code only (spec v2 2026-09-26, Phase 6 "Proof before keeping").

The dataset (`evals/datasets/shot_card_plan.jsonl`) holds 20 full-script requests: 2 per framing
category (beauty, fashion, food, fitness, tech products, screen demos, finance/education, travel,
comedy/lifestyle) plus 1 group/interview and 1 motivational talk. Each has a saved phone, a
language and pre-filled answers to the coach questions, with some answers deliberately missing.

Four scores per case, 0 to 100, all computed here by code (no model grades a model):

- F, fields filled: per beat, the share of the 13 shot-card fields holding a valid value. "?"
  is not filled. A CREATOR-FACT field (place, light, height, move, distance, eyes, prop) whose
  value the creator's answers do not support is a GUESSED FACT (owner decision 1, 2026-09-26):
  it earns nothing in F and is counted on its own, as a hard veto. Supported means the VALUE
  follows from what the creator said (`ShotCardCase.value_known`), not only that the question
  was answered: a light side only from window_side "In front of me" / "Behind me"; a place, or
  a prop side and surface, only when the creator's words name it -- the chat, or a tapped option
  that names one (can_move "By the window" / "At my desk" / "Outside", prop_ready "In my hand" /
  "On a table", product_side "Left" / "Centre" / "Right", owner decision D 2026-09-26);
  prop=none only with a prop_ready answer.
- S, category-correct shot size: each beat's size is in the allowed set for its category and
  the beat's action (`allowed_sizes`), built from the `category_composition_rule` and
  `category_composition_example` rows.
- P, specific positions: stand and text are enum values, not "somewhere good", and the light
  carries its side where the window_side answer names one. Nothing else is asked of P, because
  "?" is the right answer wherever the creator did not say (a P that wanted a prop side would
  reward the guess F vetoes).
- C, no contradiction with live rules (`find_contradictions`): no "exactly two fingers"; left
  and right only ever the creator's own; no 8K or LOG on a phone in the Phone notes (the OPPO
  Find X8 Ultra first of all); safe-zone percentages only from `app/shoot/safe_zones.json`, with
  a check-your-preview caveat; no other creator's @handle or the named grid accounts; the Set-up
  line, Before you shoot and the card distances pass `step_fits_phone` for the saved phone;
  and (owner decision B, 2026-09-26) the "Made for:" line states no audience fact -- a share,
  an age band, a gender or a city -- that the case's own audience context (`audience_summary`,
  the "Your audience" line) does not hold (`made_for_audience_guesses`). No audience context,
  or one that is "not available", means every such fact is a guess.

A reply WITH the "Shot cards:" block is scored from the block. A reply WITHOUT it (the "before"
prompt, or an older reply) is scored by a fixed extractor over the Set-up line and each beat's
Shot and On screen text (`extract_fields_from_prose`), so before and after are measured on the
same 13 fields.

Ship rule (spec): run before and after, 3 runs each, the median counts; ship only if the total
improves by at least 10 points, C is 100 in every after run, and no category drops by more than
5. `ship_verdict` computes it from saved per-case scores (`run_eval.py --save-scores`). It is
also a veto here that any after run has a guessed fact or a reply that does not render as a
script card.

Wire format (the contract every builder codes against): an optional block after the last Script
beat and before "Caption:", a line exactly "Shot cards:" then per beat
"S<n>: size=..; height=..; distance=..; place=..; light=..; stand=..; headroom=..; eyes=..;
background=..; space=..; text=..; prop=..; move=.." (13 pairs, this order). An unknown enum value
or an over-long free text reads as "?"; a malformed S-line keeps the beat, with no card.

stdlib + pure app modules only (no app.config, no provider SDK), like the rest of the harness.
"""

from __future__ import annotations

import json
import re
import statistics
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_ROWS, COACH_QUESTIONS, find_phone
from app.prompt.frame_check_render import step_fits_phone
from app.recommendations.script_card import _BEAT_RE, parse_meera_script, strip_meera_markdown

SERVICE_ROOT = Path(__file__).resolve().parent.parent  # influora-ai/
SAFE_ZONES_JSON = SERVICE_ROOT / "app" / "shoot" / "safe_zones.json"

# --------------------------------------------------------------------------- the wire contract

FIELD_ORDER: tuple[str, ...] = (
    "size", "height", "distance", "place", "light", "stand", "headroom",
    "eyes", "background", "space", "text", "prop", "move",
)
UNKNOWN = "?"

_ENUMS: dict[str, frozenset[str]] = {
    "size": frozenset({"ECU", "CU", "MCU", "MS", "MLS", "FS", "LS", "OVERHEAD"}),
    "height": frozenset({"eye", "chest", "above", "below", "overhead"}),
    "stand": frozenset({"left", "centre", "right"}),
    "headroom": frozenset({"cropped", "small", "medium"}),
    "eyes": frozenset({"lens", "product", "off_lens"}),
    "space": frozenset({"left", "right", "top", "none"}),
    "text": frozenset({"top", "opposite_face", "lower_middle", "none"}),
    "move": frozenset({"still", "sit", "stand", "walk", "pan", "push"}),
}
LIGHT_KINDS = frozenset({"window", "sun", "shade", "lamp", "ring_light", "tube_light", "mixed"})
LIGHT_SIDES = frozenset({"left", "right", "front", "behind"})
PROP_SIDES = frozenset({"left", "centre", "right"})
PROP_SURFACES = frozenset({"hand", "table", "floor"})
FREE_TEXT_MAX: dict[str, int] = {"distance": 20, "place": 40, "background": 40}

# Creator facts and the coach questions (or the saved phone) they may come from. Everything
# else (size, stand, headroom, background, space, text) is a craft choice from the category's
# framing rules. SAVED_PHONE stands for "the creator saved a phone on the settings page".
SAVED_PHONE = "saved_phone"
FACT_SOURCES: dict[str, tuple[str, ...]] = {
    "place": ("can_move",),
    "light": ("window_side", "other_light", "outdoor_light"),
    "height": ("sit_or_walk",),
    "move": ("sit_or_walk",),
    "distance": ("room_size", "phone_lens", SAVED_PHONE),
    "eyes": ("on_camera",),
    # prop is known only with a prop_ready answer (the persona: "with no prop_ready answer it is
    # ?"); the SIDE is then supported by the tapped product_side answer, which is one of the
    # chat words `value_known` reads (owner decision D, 2026-09-26).
    "prop": ("prop_ready",),
}
LIGHT_QUESTIONS: tuple[str, ...] = FACT_SOURCES["light"]

# The only answers that name a light side: window_side's options, by their English text.
WINDOW_SIDE_NAMES: dict[str, str] = {"In front of me": "front", "Behind me": "behind"}

# Words a creator may use in the chat for a prop side or surface (English and Hinglish, as the
# dataset's languages). A prop side counts only when the chat names BOTH a side and a surface.
_PROP_SIDE_WORDS: dict[str, frozenset[str]] = {
    "left": frozenset({"left", "baayen", "baen", "baayein", "बाएं", "बाएँ", "बाईं"}),
    "right": frozenset({"right", "daayen", "daen", "daayein", "दाएं", "दाएँ", "दाईं"}),
    "centre": frozenset({"centre", "center", "middle", "beech", "बीच"}),
}
_PROP_SURFACE_WORDS: dict[str, frozenset[str]] = {
    "hand": frozenset({"hand", "hands", "haath", "hath", "हाथ"}),
    "table": frozenset({"table", "desk", "counter", "mez", "mej", "मेज़", "मेज"}),
    "floor": frozenset({"floor", "zameen", "zamin", "फर्श", "ज़मीन"}),
}
# Words that never name a place on their own ("by the", "my", "wala").
_PLACE_STOPWORDS = frozenset(
    {
        "a", "an", "the", "my", "your", "our", "by", "at", "in", "on", "near", "next", "to", "of",
        "with", "and", "or", "for", "from", "me", "mera", "meri", "mere", "apna", "apni", "apne",
        "ka", "ki", "ke", "par", "pe", "mein", "aur", "hai", "wala", "wali", "wale",
    }
)
_WORD_SPLIT_RE = re.compile(r"[\s,.;:!?()\[\]\"'“”‘’/|&+–—-]+")


def _words(text: str) -> list[str]:
    return [w for w in _WORD_SPLIT_RE.split(_ascii_fold(text or "", upper=False)) if w]


def canonical_answer(qid: str, answer: str) -> str | None:
    """A tapped answer as its English option text (Hindi options map by position), or None when
    it is not one of the question's options."""
    row = COACH_QUESTIONS.get(qid)
    if row is None:
        return None
    text = (answer or "").strip()
    for options in (row["options"], row["options_hi"]):
        if text in options:
            return row["options"][list(options).index(text)]
    return None


def _ascii_fold(value: str, upper: bool) -> str:
    """A-Z / a-z only, like the app's parsers (no Unicode case mapping)."""
    if upper:
        return "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in value)
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in value)


def normalize_field(key: str, raw: str) -> str:
    """One card value, validated against the contract; anything invalid reads as "?". Enum
    values are ASCII case-folded as the app's parsers fold them ("mcu" is MCU)."""
    value = (raw or "").strip()
    if not value or value == UNKNOWN:
        return UNKNOWN
    if key in FREE_TEXT_MAX:
        return value if len(value) <= FREE_TEXT_MAX[key] else UNKNOWN
    value = _ascii_fold(value, upper=key == "size")
    if key in _ENUMS:
        return value if value in _ENUMS[key] else UNKNOWN
    if key == "light":
        kind, sep, side = value.partition("-")
        if kind not in LIGHT_KINDS:
            return UNKNOWN
        if sep and side not in LIGHT_SIDES:
            return UNKNOWN
        return value
    if key == "prop":
        if value == "none":
            return value
        side, sep, surface = value.partition("-")
        return value if sep and side in PROP_SIDES and surface in PROP_SURFACES else UNKNOWN
    raise KeyError(key)


def parse_shot_card_line(body: str) -> dict[str, str] | None:
    """The 13 values of one "S<n>:" line (after the label), or None when the line is malformed:
    not exactly 13 "key=value" pairs in the contract's order."""
    text = body.strip()
    if text.endswith((".", ";")):  # one trailing stop, as the app's parsers allow
        text = text[:-1]
    parts = [p.strip() for p in text.split(";")]
    if len(parts) != len(FIELD_ORDER):
        return None
    card: dict[str, str] = {}
    for key, part in zip(FIELD_ORDER, parts):
        name, sep, value = part.partition("=")
        if not sep or _ascii_fold(name.strip(), upper=False) != key:
            return None
        card[key] = normalize_field(key, value)
    return card


@dataclass(frozen=True)
class Beat:
    shot: str
    say: str
    on_screen: str

    @property
    def action(self) -> str:
        """The action half of "Shot: <camera angle> - <action>" (the whole text without a dash),
        so the size the model chose does not decide which sizes are allowed."""
        for sep in (" - ", " – ", " — "):
            if sep in self.shot:
                return self.shot.split(sep, 1)[1]
        return self.shot


@dataclass(frozen=True)
class ParsedReply:
    beats: tuple[Beat, ...]
    setup: str | None
    before_you_shoot: str | None
    has_block: bool
    cards: dict[int, dict[str, str]]  # 1-based beat number -> the 13 normalized values
    malformed_card_lines: int


_S_LINE_RE = re.compile(r"^S\s*([0-9]+)\s*:(.*)$", re.IGNORECASE)
_KEY_RE = re.compile(r"^([A-Za-z][A-Za-z \-]*?)\s*:\s*(.*)$")


def _strip_wrapping_quotes(line: str) -> str:
    if len(line) >= 2 and line[0] in "\"“”" and line[-1] in "\"“”":
        return line[1:-1]
    return line


def parse_reply(text: str) -> ParsedReply:
    """Beats, the Set-up line and the Shot cards, as the APP reads them: when the production
    parser (`parse_meera_script`, the Python twin of the app's `parseMeeraScript`) accepts the
    reply, its beats and cards are used, so the eval scores exactly the cards a creator sees.
    Only a reply the app would NOT render falls back to this module's own scan of the same
    contract (for diagnostics: such a reply already fails `renders_as_card`). Never raises."""
    scanned = _scan_reply(text)
    app = parse_meera_script(text)
    if app is None:
        return scanned
    cards: dict[int, dict[str, str]] = {}
    for number, beat in enumerate(app.beats, start=1):
        card = getattr(beat, "card", None)
        if card is not None:
            cards[number] = {key: str(getattr(card, key)) for key in FIELD_ORDER}
    return ParsedReply(
        beats=tuple(Beat(b.shot, b.say, b.on_screen) for b in app.beats),
        setup=app.setup,
        before_you_shoot=" ".join(app.before_you_shoot),
        has_block=scanned.has_block,
        cards=cards,
        malformed_card_lines=max(0, scanned.block_lines - len(cards)) if scanned.has_block else 0,
    )


def _scan_reply(text: str) -> _ScannedReply:
    """This module's own read of the contract (used when the app parser refuses the reply,
    and for whether the block is present at all)."""
    lines = [ln.strip() for ln in strip_meera_markdown(text or "").split("\n")]
    setup: str | None = None
    before: str | None = None
    script_at: int | None = None
    for i, line in enumerate(lines):
        match = _KEY_RE.match(line)
        if not match:
            continue
        key = re.sub(r"\s+", " ", match.group(1).lower())
        if key in ("set-up", "setup", "set up") and setup is None and script_at is None:
            setup = match.group(2).strip() or None
        elif key == "script" and not match.group(2).strip() and script_at is None:
            script_at = i
        elif key == "before you shoot" and before is None:
            before = match.group(2).strip() or None

    beats: list[Beat] = []
    cards: dict[int, dict[str, str]] = {}
    block_lines = 0
    has_block = False
    if script_at is not None:
        i = script_at + 1
        while i < len(lines):
            match = _BEAT_RE.search(_strip_wrapping_quotes(lines[i]))
            if not match:
                break
            beats.append(Beat(match.group(3).strip(), match.group(4).strip(), match.group(7).strip()))
            i += 1
        if i < len(lines) and re.fullmatch(r"shot\s+cards\s*:", lines[i], re.IGNORECASE):
            has_block = True
            i += 1
            seen: set[int] = set()
            doubled: set[int] = set()
            # The block runs up to the Caption line; a bad line only costs its beat the card,
            # and a beat named twice gets no card.
            while i < len(lines) and not re.match(r"caption\s*:", lines[i], re.IGNORECASE):
                if lines[i]:
                    block_lines += 1
                s_match = _S_LINE_RE.match(lines[i])
                i += 1
                if not s_match:
                    continue
                number = int(s_match.group(1))
                if not 1 <= number <= len(beats):
                    continue
                if number in seen:
                    doubled.add(number)
                seen.add(number)
                card = parse_shot_card_line(s_match.group(2))
                if card is not None:
                    cards[number] = card
                else:
                    cards.pop(number, None)
            for number in doubled:
                cards.pop(number, None)
    malformed = max(0, block_lines - len(cards))
    return _ScannedReply(tuple(beats), setup, before, has_block, cards, malformed, block_lines)


@dataclass(frozen=True)
class _ScannedReply(ParsedReply):
    block_lines: int = 0


# --------------------------------------------------------------------------- the "before" extractor

_SIZE_WORDS: tuple[tuple[re.Pattern[str], str], ...] = (
    # Same keywords as the app's shotSizeForShot (spec Phase 5), in the same order, but with NO
    # default: an unmatched shot is "?", because an extractor that guesses MS inflates F and S.
    # Matched on the lower-cased shot text with hyphens as spaces ("close-up" -> "close up").
    (re.compile(r"\bextreme close\b"), "ECU"),
    (re.compile(r"\bmedium close\b"), "MCU"),
    (re.compile(r"\bclose\b"), "CU"),
    (re.compile(r"\bmedium long\b|\b3/4\b|\bknees\b"), "MLS"),
    (re.compile(r"\bmedium\b|\bwaist\b"), "MS"),
    (re.compile(r"\bfull body\b|\bfull shot\b|\bfull length\b|\bhead to toe\b"), "FS"),
    (re.compile(r"\bwide\b|\bestablishing\b|\blong\b"), "LS"),
    (re.compile(r"\boverhead\b|\btop down\b|\bhands\b|\bflat ?lay\b"), "OVERHEAD"),
)
_HEIGHT_WORDS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\boverhead\b|\btop[\s-]down\b", re.I), "overhead"),
    (re.compile(r"\beye[\s-]?level\b|\bat (?:your )?eye height\b", re.I), "eye"),
    (re.compile(r"\bchest[\s-](?:height|level)\b", re.I), "chest"),
    (re.compile(r"\bhigh angle\b|\babove (?:your )?(?:eye|head)", re.I), "above"),
    (re.compile(r"\blow angle\b|\bbelow (?:your )?eye", re.I), "below"),
)
_DISTANCE_RE = re.compile(
    r"(?<![\d.])\d+(?:\.\d+)?(?:\s*[-–]\s*\d+(?:\.\d+)?)?\s*(?:m|cm|ft|feet|metres?|meters?)\b", re.I
)
_PLACE_RE = re.compile(
    r"\b(desk|bed|bedroom|kitchen|counter|balcony|terrace|rooftop|living room|sofa|vanity|mirror|"
    r"park|street|gym|office|studio|garden|market|bazaar|cafe|fort|beach|stall)\b",
    re.I,
)
_LIGHT_KIND_RE = r"(window|sunlight|sun|shade|lamp|ring[\s-]?light|tube[\s-]?light)"
_LIGHT_SIDE_RE = re.compile(
    _LIGHT_KIND_RE + r"[^.;]{0,30}?\b(?:on|to|at)\s+your\s+(left|right)\b", re.I
)
_LIGHT_FRONT_RE = re.compile(_LIGHT_KIND_RE + r"[^.;]{0,20}?\b(in front of you|facing you)\b", re.I)
_LIGHT_BEHIND_RE = re.compile(_LIGHT_KIND_RE + r"[^.;]{0,20}?\bbehind you\b", re.I)
_LIGHT_ONLY_RE = re.compile(
    r"\b(?:window light|by the window|near the window|facing the window|sunlight|in the shade|"
    r"lamp|ring[\s-]?light|tube[\s-]?light)\b",
    re.I,
)
_STAND_RE = re.compile(
    r"\b(?:stand|sit|place yourself|position yourself|keep yourself)\b[^.;]{0,25}?"
    r"\b(centre|center|middle|your\s+left|your\s+right)\b",
    re.I,
)
_HEADROOM_RE: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bcrop(?:ped)?\b[^.;]{0,20}\b(?:head|hair|forehead)\b", re.I), "cropped"),
    (re.compile(r"\b(?:little|small|a bit of|two fingers of|tight)\s+headroom\b|\bheadroom\b[^.;]{0,15}\bsmall\b", re.I), "small"),
    (re.compile(r"\bmedium headroom\b", re.I), "medium"),
)
_EYES_RE: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\b(?:look|looking|eyes)\s+(?:at|into|to)\s+(?:the\s+)?(?:lens|camera)\b", re.I), "lens"),
    (re.compile(r"\b(?:look|looking|glanc\w*)\s+(?:at|to)\s+(?:the\s+)?(?:product|serum|dish|bowl|watch|earbuds|device)\b", re.I), "product"),
    (re.compile(r"\blook(?:ing)?\s+away\b|\boff[\s-]camera\b", re.I), "off_lens"),
)
_BACKGROUND_RE = re.compile(
    r"\b((?:plain|clean|simple|clutter[\s-]free|blank|white|neat)\s+(?:wall|background|backdrop|shelf))\b", re.I
)
_SPACE_RE = re.compile(r"\b(?:empty|negative|free|clear)\s+space\s+(?:on\s+|to\s+)?(?:your\s+)?(left|right|top|above)\b", re.I)
_TEXT_RE: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bopposite (?:your )?(?:face|you)\b", re.I), "opposite_face"),
    (re.compile(r"\blower (?:middle|third|half)\b", re.I), "lower_middle"),
    (re.compile(r"\b(?:at the top|top of the (?:frame|screen)|on top|above your head)\b", re.I), "top"),
)
_PROP_HAND_RE = re.compile(r"\b(?:in|with)\s+your\s+(left|right)\s+hand\b", re.I)
_PROP_TABLE_RE = re.compile(
    r"\b(?:on|at)\s+the\s+(?:table|desk|counter)\b[^.;]{0,20}?\b(?:(?:on|to)\s+your\s+(left|right)|(in front of you|centre|center))\b",
    re.I,
)
_MOVE_RE: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bwalk(?:s|ing)?\b", re.I), "walk"),
    (re.compile(r"\bpan(?:s|ning)?\b", re.I), "pan"),
    (re.compile(r"\bpush(?:es|ing)?\s+in\b", re.I), "push"),
    (re.compile(r"\bsit(?:s|ting)?\b|\bseated\b", re.I), "sit"),
    (re.compile(r"\bstanding\b|\bstand\s+(?:in|at|near|by|with|up)\b", re.I), "stand"),
    (re.compile(r"\bstill\b|\bstatic\b|\blocked[\s-]off\b", re.I), "still"),
)


def _first(patterns: Iterable[tuple[re.Pattern[str], str]], *texts: str) -> str:
    for text in texts:
        for pattern, value in patterns:
            if pattern.search(text):
                return value
    return UNKNOWN


def _size_from_shot(shot: str) -> str:
    """The shot size a beat's Shot text names: its abbreviation (ECU, MCU, ...) as a word, or
    the first keyword in `_SIZE_WORDS` order; "?" when it names none."""
    for abbr in ("ECU", "MCU", "MLS", "OVERHEAD", "CU", "MS", "FS", "LS"):
        if re.search(rf"\b{abbr}\b", shot):
            return abbr
    text = re.sub(r"[-–—]", " ", shot.lower())
    for pattern, value in _SIZE_WORDS:
        if pattern.search(text):
            return value
    return UNKNOWN


def extract_fields_from_prose(beat: Beat, setup: str | None) -> dict[str, str]:
    """The fixed "before" extractor: the 13 fields a reply WITHOUT the Shot cards block states in
    its Set-up line and the beat's own Shot / On screen text. Only explicit words count; nothing
    is defaulted. Values go through `normalize_field`, so both modes share one validator."""
    shot, on_screen, set_up = beat.shot, beat.on_screen, setup or ""
    beat_text = f"{shot}. {on_screen}"
    out: dict[str, str] = {k: UNKNOWN for k in FIELD_ORDER}
    out["size"] = _size_from_shot(shot)
    out["height"] = _first(_HEIGHT_WORDS, beat_text, set_up)
    distance = _DISTANCE_RE.search(set_up) or _DISTANCE_RE.search(beat_text)
    if distance:
        out["distance"] = distance.group(0).strip()
    place = _PLACE_RE.search(set_up) or _PLACE_RE.search(beat_text)
    if place:
        out["place"] = place.group(1).lower()
    light_kind = ""
    for text in (beat_text, set_up):
        side = _LIGHT_SIDE_RE.search(text)
        front = _LIGHT_FRONT_RE.search(text)
        behind = _LIGHT_BEHIND_RE.search(text)
        found = side or front or behind
        if found:
            light_kind = _light_kind(found.group(1))
            where = side.group(2).lower() if side else ("front" if front else "behind")
            out["light"] = f"{light_kind}-{where}"
            break
        only = _LIGHT_ONLY_RE.search(text)
        if only and out["light"] == UNKNOWN:
            out["light"] = _light_kind(only.group(0))
    stand = _STAND_RE.search(beat_text) or _STAND_RE.search(set_up)
    if stand:
        word = stand.group(1).lower()
        out["stand"] = "right" if word.endswith("right") else "left" if word.endswith("left") else "centre"
    out["headroom"] = _first(_HEADROOM_RE, beat_text, set_up)
    out["eyes"] = _first(_EYES_RE, beat_text, set_up)
    background = _BACKGROUND_RE.search(set_up) or _BACKGROUND_RE.search(beat_text)
    if background:
        out["background"] = background.group(1).lower()
    space = _SPACE_RE.search(beat_text) or _SPACE_RE.search(set_up)
    if space:
        word = space.group(1).lower()
        out["space"] = "top" if word == "above" else word
    out["text"] = _first(_TEXT_RE, beat_text, set_up)
    hand = _PROP_HAND_RE.search(beat_text) or _PROP_HAND_RE.search(set_up)
    table = _PROP_TABLE_RE.search(beat_text) or _PROP_TABLE_RE.search(set_up)
    if hand:
        out["prop"] = f"{hand.group(1).lower()}-hand"
    elif table:
        out["prop"] = f"{table.group(1).lower()}-table" if table.group(1) else "centre-table"
    out["move"] = _first(_MOVE_RE, shot, set_up)
    return {k: normalize_field(k, v) for k, v in out.items()}


def _light_kind(word: str) -> str:
    w = re.sub(r"[\s-]+", "_", word.lower())
    if "window" in w:
        return "window"
    if w.startswith("sun"):
        return "sun"
    if "shade" in w:
        return "shade"
    if "ring" in w:
        return "ring_light"
    if "tube" in w:
        return "tube_light"
    if "lamp" in w:
        return "lamp"
    return "mixed"


# --------------------------------------------------------------------------- S: allowed shot sizes

CATEGORY_KEYS: tuple[str, ...] = (
    "beauty", "fashion", "food", "fitness", "tech_product", "screen_demo",
    "finance_education", "travel", "comedy_lifestyle", "groups", "motivational",
)

# category -> (ordered (action words, allowed sizes) rules, default sizes). Read from the
# category_composition_rule / _example rows (framing topics), e.g. fitness form -> MLS/FS,
# recipe step -> OVERHEAD/CU, outfit -> FS, screen demo -> screen insert plus an MCU intro.
# The FIRST matching rule wins, matched against the beat's ACTION only (never its size words).
_SizeRules = tuple[tuple[tuple[re.Pattern[str], frozenset[str]], ...], frozenset[str]]


def _rule(words: str, *sizes: str) -> tuple[re.Pattern[str], frozenset[str]]:
    return re.compile(rf"\b(?:{words})", re.I), frozenset(sizes)


SIZE_RULES: dict[str, _SizeRules] = {
    "beauty": (
        (_rule(r"apply|eyeliner|liner|serum|swatch|blend|texture|label|lipstick|mascara|drops?\b|bottle|product", "ECU", "CU", "MCU"),),
        frozenset({"CU", "MCU", "MS"}),
    ),
    "fashion": (
        (
            _rule(r"fabric|detail|texture|accessor|earring|watch|button|stitch|shoes?\b|bag\b|jewel", "ECU", "CU", "MCU"),
            _rule(r"outfit|look\b|reveal|head[\s-]to[\s-]toe|full body|twirl|walk|mirror|pose", "FS", "MLS", "LS"),
        ),
        frozenset({"MCU", "MS", "MLS", "FS"}),
    ),
    "food": (
        (
            _rule(r"tast|bite|eat|reaction|react", "CU", "MCU", "MS"),
            _rule(r"stir|chop|mix|pour|add|whisk|knead|fry|batter|ingredient|garnish|plat|cook|tadka|sprinkl|cut|bowl|pan\b|tawa|dough|temper", "OVERHEAD", "CU", "ECU"),
        ),
        frozenset({"OVERHEAD", "CU", "MCU", "MS"}),
    ),
    "fitness": (
        (
            _rule(r"form|squat|lunge|push[\s-]?ups?|plank|deadlift|reps?\b|exercise|workout|stretch|burpee|demo|jump|rows?\b|curl", "MLS", "FS"),
            _rule(r"dumbbell|band\b|mat\b|equipment|grip|kettlebell|bottle", "CU", "MCU", "MS"),
        ),
        frozenset({"MCU", "MS", "MLS", "FS"}),
    ),
    "tech_product": (
        (_rule(r"unbox|device|button|port\b|camera bump|lens|screen|display|detail|insert|box\b|case\b|earbuds?|strap|charg", "ECU", "CU", "OVERHEAD", "MCU"),),
        frozenset({"MCU", "MS", "CU"}),
    ),
    "screen_demo": (
        (_rule(r"screen|app\b|prompt|cursor|interface|tab\b|scroll|result|output|recording|demo|laptop|typing|type\b|dashboard", "ECU", "CU", "OVERHEAD", "MCU"),),
        frozenset({"MCU", "MS", "CU"}),
    ),
    "finance_education": (
        (_rule(r"chart|graph|notebook|whiteboard|board\b|cards?\b|calculator|paper|writ|notes?\b|screen|app\b|diagram", "CU", "ECU", "OVERHEAD", "MCU"),),
        frozenset({"MCU", "MS", "CU"}),
    ),
    "travel": (
        (
            _rule(r"vendor|dish|plate|food|stall|tast|bite|chai|snack|jalebi|kachori", "CU", "ECU", "MCU", "MS"),
            _rule(r"landmark|establish|view|skyline|fort|temple|beach|mountain|street|arriv|market|lake|river|sunset|gate|crowd", "LS", "FS", "MLS", "MS"),
        ),
        frozenset({"MCU", "MS", "MLS", "CU"}),
    ),
    "comedy_lifestyle": (
        (
            _rule(r"reaction|react|deadpan|stare|face\b", "CU", "MCU"),
            _rule(r"reveal|room\b|enter|walk", "MS", "MLS", "FS", "LS"),
            _rule(r"insert|object|mug|cup|laptop|desk|detail|plant|diary|journal|remote|keyboard", "CU", "ECU", "OVERHEAD", "MCU"),
        ),
        frozenset({"MCU", "MS", "MLS", "CU"}),
    ),
    "groups": (
        (_rule(r"reaction|react|laugh|speaker|answer|guest|host", "CU", "MCU", "MS"),),
        frozenset({"MS", "MLS", "MCU", "FS"}),
    ),
    "motivational": (
        (
            _rule(r"walk|stroll|moving", "MS", "MLS", "LS", "FS"),
            _rule(r"landscape|horizon|sky\b|view|establish|sunrise", "LS", "FS", "MLS"),
        ),
        frozenset({"MCU", "MS", "CU"}),
    ),
}


def allowed_sizes(category: str, beat: Beat) -> frozenset[str]:
    rules, default = SIZE_RULES[category]
    action = beat.action.replace("-", " ")
    for pattern, sizes in rules:
        if pattern.search(action):
            return sizes
    return default


# --------------------------------------------------------------------------- C: contradictions

_EXACT_FINGERS_RE = re.compile(
    r"\b(?:exactly|precisely|bilkul|theek)\s+(?:two|2|do)\s+(?:fingers?|ungli(?:yan|yon|yaan)?)\b", re.I
)
_NOT_A_SIDE = r"(?!\s+(?:at|in|on|next|above|below|by|there|here|now|under|over|beside|behind|up|away|where|time|moment|track|place|spot|lens|light|angle|height|distance|one|way|note|level|amount|pace|speed|size|frame|shot)\b)"
_VIEWER_SIDE_RES: tuple[re.Pattern[str], ...] = (
    re.compile(r"\b(?:camera|screen|frame|viewer'?s?|stage)[\s-]+(?:left|right" + _NOT_A_SIDE + r")\b", re.I),
    re.compile(r"\b(?:left|right)\s+(?:side\s+)?of\s+(?:the\s+)?(?:frame|screen|shot|picture|photo|image|video)\b", re.I),
    re.compile(r"\b(?:on|to|at)\s+the\s+(?:left|right" + _NOT_A_SIDE + r")\b", re.I),
    re.compile(r"\b(?:screen|frame|camera|video)\s+(?:ke|ki|mein|me)\s+(?:left|right|baayen|baen|daayen|daen)\b", re.I),
    re.compile("(?:स्क्रीन|फ्रेम|कैमरा)\\s+(?:के|की|में)\\s+(?:बाएं|बाईं|दाएं|दाईं|लेफ्ट|राइट)"),
)
_8K_RE = re.compile(r"(?<![\w.])8\s?k\b", re.I)
_LOG_UPPER_RE = re.compile(r"\bLOG\b")
_LOG_PHRASE_RE = re.compile(
    r"\b(?:[ds]-?log|log\s+(?:profile|mode|video|format|gamma|recording|footage|setting))\b"
    r"|\b(?:shoot|record|film)\w*\s+(?:in\s+)?log\b",
    re.I,
)
_CONDITIONAL_RE = re.compile(r"\b(?:if|agar)\s+(?:your|the|aapke|aapka|tumhare|tumhara)\s+(?:phone|camera)\b", re.I)
_PERCENT_RE = re.compile(
    r"(?<![\d.])(\d{1,3}(?:\.\d+)?)\s*(?:%|percent\b|per\s*cent\b|प्रतिशत)", re.I
)
_SAFE_CONTEXT_RE = re.compile(
    r"safe[\s-]?(?:zone|area)|covered|caption|username|buttons?\b|interface|\bUI\b|top bar|app bar|"
    r"bottom|\btop\b|margin|edge|rail|overlay|सेफ",
    re.I,
)
_PREVIEW_RE = re.compile(r"preview|प्रीव्यू", re.I)
_SENTENCE_SPLIT_RE = re.compile(r"(?<!\d)[.!?।](?!\d)|\n")
_HANDLE_RE = re.compile(r"(?<![\w.@])@[A-Za-z0-9_](?:[A-Za-z0-9_.]{0,28}[A-Za-z0-9_])?")
# Spec 2.7: never name, credit or copy these (or any other) accounts' grids.
NAMED_GRID_ACCOUNTS: tuple[str, ...] = ("alessiolr", "mansourmelouli", "editorsinventory")


def safe_zone_percentages(path: Path = SAFE_ZONES_JSON) -> frozenset[float]:
    """Every percentage a reply may state about the safe zone: the canonical config's values
    (and the covered shares they imply), read from the file itself. Meta's 35% bottom ad margin
    is NOT here on purpose (spec 2.6/Phase 6: never presented as a rule for normal posts)."""
    with path.open(encoding="utf-8") as fh:
        config = json.load(fh)
    values: set[float] = set()
    for platform in config["platforms"].values():
        z = platform["default"]
        shares = (
            z["top"], z["caption_line"], z["covered_from"], 1 - z["covered_from"], z["side"],
            z["rail"]["x"], 1 - z["rail"]["x"], z["rail"]["y_from"], z["rail"]["y_to"],
            z["cta_band"]["x_to"],
        )
        values.update(round(float(s) * 100, 1) for s in shares)
    if not values:
        raise ValueError(f"{path}: no safe-zone values read")
    return frozenset(values)


SAFE_ZONE_PERCENTAGES: frozenset[float] = safe_zone_percentages()


def _phone_lacks(row: Mapping[str, Any], feature: str) -> bool:
    if feature == "8k":
        return not re.search(r"(?<!no )\b8k\b", str(row.get("max_resolution") or ""), re.I)
    log = str(row.get("log_hdr") or "")
    return not re.search(r"\blog\b", log, re.I) or bool(
        re.search(r"no log|not available|announced|check the camera app", log, re.I)
    )


# --- the Made for line (owner decision B, 2026-09-26) -------------------------------------------

_MADE_FOR_KEY_RE = re.compile(r"^\s*made\s+for\s*:\s*(.*)$", re.IGNORECASE)
# The audience part ends where the topic or the goal starts.
_MADE_FOR_AUDIENCE_END_RE = re.compile(r"\u00b7|\btopic\s*:|\bgoal\s*:", re.IGNORECASE)
_MF_PERCENT_RE = re.compile(r"(\d+(?:\.\d+)?)\s*%")
_MF_AGE_BAND_RE = re.compile(r"(?<![\d.])(\d{2})\s*(?:-|\u2013|to)\s*(\d{2})(?![\d%])|(?<![\d.])(\d{2})\s*\+")
# Gender words (English, Hinglish, Hindi) -> the class the audience line must name.
_MF_GENDER_WORDS: dict[str, str] = {
    "women": "f", "woman": "f", "female": "f", "females": "f", "girls": "f", "ladies": "f",
    "mahila": "f", "mahilayen": "f", "mahilaen": "f", "ladkiyan": "f", "ladkiyaan": "f",
    "aurat": "f", "auratein": "f", "aurten": "f",
    "\u092e\u0939\u093f\u0932\u093e": "f", "\u092e\u0939\u093f\u0932\u093e\u090f\u0901": "f",
    "\u092e\u0939\u093f\u0932\u093e\u090f\u0902": "f",
    "\u0932\u0921\u093c\u0915\u093f\u092f\u093e\u0901": "f", "\u0932\u0921\u093c\u0915\u093f\u092f\u093e\u0902": "f",
    "men": "m", "man": "m", "male": "m", "males": "m", "boys": "m", "purush": "m", "ladke": "m",
    "\u092a\u0941\u0930\u0941\u0937": "m", "\u0932\u0921\u093c\u0915\u0947": "m",
}
_MF_GENDER_IN_SUMMARY: dict[str, re.Pattern[str]] = {
    "f": re.compile(r"\b(?:women|woman|female|females)\b", re.IGNORECASE),
    "m": re.compile(r"\b(?:men|man|male|males)\b", re.IGNORECASE),
}
# City names a Made for line may carry -> the spellings the audience line may use for them.
_MF_CITIES: dict[str, tuple[str, ...]] = {
    "mumbai": ("mumbai", "bombay"), "delhi": ("delhi",), "new delhi": ("delhi",),
    "bengaluru": ("bengaluru", "bangalore"), "bangalore": ("bengaluru", "bangalore"),
    "pune": ("pune",), "hyderabad": ("hyderabad",), "chennai": ("chennai",),
    "kolkata": ("kolkata", "calcutta"), "ahmedabad": ("ahmedabad",), "jaipur": ("jaipur",),
    "lucknow": ("lucknow",), "surat": ("surat",), "indore": ("indore",), "bhopal": ("bhopal",),
    "nagpur": ("nagpur",), "chandigarh": ("chandigarh",), "kochi": ("kochi",), "patna": ("patna",),
    "noida": ("noida",), "gurugram": ("gurugram", "gurgaon"), "gurgaon": ("gurugram", "gurgaon"),
    "thane": ("thane",), "vadodara": ("vadodara",), "coimbatore": ("coimbatore",),
    "nashik": ("nashik",), "ludhiana": ("ludhiana",), "guwahati": ("guwahati",),
    "bhubaneswar": ("bhubaneswar",), "dehradun": ("dehradun",), "ranchi": ("ranchi",),
    "raipur": ("raipur",), "amritsar": ("amritsar",), "goa": ("goa",), "kanpur": ("kanpur",),
    "\u092e\u0941\u0902\u092c\u0908": ("mumbai", "bombay"), "\u0926\u093f\u0932\u094d\u0932\u0940": ("delhi",),
    "\u092a\u0941\u0923\u0947": ("pune",), "\u092c\u0947\u0902\u0917\u0932\u0941\u0930\u0941": ("bengaluru", "bangalore"),
    "\u0915\u094b\u0932\u0915\u093e\u0924\u093e": ("kolkata", "calcutta"), "\u091a\u0947\u0928\u094d\u0928\u0908": ("chennai",),
    "\u0939\u0948\u0926\u0930\u093e\u092c\u093e\u0926": ("hyderabad",), "\u091c\u092f\u092a\u0941\u0930": ("jaipur",),
    "\u0932\u0916\u0928\u090a": ("lucknow",),
}
_MF_WORD_RE = re.compile(r"[\w\u0900-\u097f]+", re.UNICODE)


def made_for_lines(text: str) -> list[str]:
    """Every "Made for:" value in the reply (after markdown is stripped), in order. Scanned from
    the lines themselves, not the parsed card, so an over-long line the parser ignores is still
    judged."""
    out: list[str] = []
    for line in strip_meera_markdown(text or "").split("\n"):
        match = _MADE_FOR_KEY_RE.match(line)
        if match:
            out.append(match.group(1).strip())
    return out


def _audience_part(value: str) -> str:
    end = _MADE_FOR_AUDIENCE_END_RE.search(value)
    return value[: end.start()] if end else value


def audience_available(audience_summary: str | None) -> bool:
    """Whether the case's "Your audience" line holds audience facts at all."""
    summary = (audience_summary or "").strip().lower()
    return bool(summary) and not summary.startswith("not available")


def made_for_audience_guesses(text: str, audience_summary: str | None) -> list[str]:
    """Audience facts in the Made for line(s) that the audience context does not hold, as short
    reasons: a share ("61%"), an age band ("18-24", "65+"), a gender word, or a city. Only the
    audience part is read (up to the first "\u00b7", "Topic:" or "Goal:"), so a city in the topic
    ("Mumbai street breakfast") is not an audience claim. Without an available audience line,
    every such fact is a guess."""
    summary = (audience_summary or "") if audience_available(audience_summary) else ""
    folded = summary.lower().replace("\u2013", "-")
    words = set(_MF_WORD_RE.findall(folded))
    reasons: list[str] = []
    for value in made_for_lines(text):
        part = _audience_part(value)
        lowered = part.lower()
        for match in _MF_PERCENT_RE.finditer(part):
            number = match.group(1)
            if not re.search(rf"(?<![\d.]){re.escape(number)}\s*%", folded):
                reasons.append(f"made_for_share:{number}%")
        for match in _MF_AGE_BAND_RE.finditer(part):
            band = f"{match.group(1)}-{match.group(2)}" if match.group(1) else f"{match.group(3)}+"
            if not re.search(rf"(?<!\d){re.escape(band)}(?!\d)", re.sub(r"\s*-\s*", "-", folded)):
                reasons.append(f"made_for_age:{band}")
        tokens = _MF_WORD_RE.findall(lowered)
        for token in tokens:
            gender = _MF_GENDER_WORDS.get(token)
            if gender and not _MF_GENDER_IN_SUMMARY[gender].search(folded):
                reasons.append(f"made_for_gender:{token}")
        joined = " ".join(tokens)
        for city, spellings in _MF_CITIES.items():
            if re.search(rf"(?<![\w\u0900-\u097f]){re.escape(city)}(?![\w\u0900-\u097f])", joined) and not (
                words & set(spellings)
            ):
                reasons.append(f"made_for_city:{city}")
    return list(dict.fromkeys(reasons))


def find_contradictions(
    text: str,
    parsed: ParsedReply,
    phone_model: str | None,
) -> list[str]:
    """Every live rule this reply breaks, as short reasons (empty when C holds)."""
    reasons: list[str] = []
    body = strip_meera_markdown(text or "")
    if _EXACT_FINGERS_RE.search(body):
        reasons.append("exact_two_fingers")
    for pattern in _VIEWER_SIDE_RES:
        hit = pattern.search(body)
        if hit:
            reasons.append(f"viewer_side:{hit.group(0)}")
            break

    phone_row = find_phone(CREATOR_KNOWLEDGE_ROWS, phone_model) if phone_model else None
    sentences = [s for s in _SENTENCE_SPLIT_RE.split(body) if s.strip()]
    for sentence in sentences:
        says_8k = bool(_8K_RE.search(sentence))
        says_log = bool(_LOG_UPPER_RE.search(sentence) or _LOG_PHRASE_RE.search(sentence))
        if phone_row is not None:
            if says_8k and _phone_lacks(phone_row, "8k"):
                reasons.append("8k_on_phone_without_it")
            if says_log and _phone_lacks(phone_row, "log"):
                reasons.append("log_on_phone_without_it")
        elif (says_8k or says_log) and not _CONDITIONAL_RE.search(sentence):
            reasons.append("8k_or_log_on_unknown_phone")

    safe_zone_numbers = False
    for sentence in sentences:
        numbers = [float(n) for n in _PERCENT_RE.findall(sentence)]
        if not numbers or not _SAFE_CONTEXT_RE.search(sentence):
            continue
        safe_zone_numbers = True
        for number in numbers:
            if round(number, 1) not in SAFE_ZONE_PERCENTAGES:
                reasons.append(f"safe_zone_number:{number:g}%")
    if safe_zone_numbers and not _PREVIEW_RE.search(body):
        reasons.append("safe_zone_without_preview_caveat")

    handle = _HANDLE_RE.search(body)
    if handle:
        reasons.append(f"creator_handle:{handle.group(0)}")
    lowered = body.lower()
    for name in NAMED_GRID_ACCOUNTS:
        if name in lowered:
            reasons.append(f"named_account:{name}")

    settings_texts = [t for t in (parsed.setup, parsed.before_you_shoot) if t]
    settings_texts += [c["distance"] for c in parsed.cards.values() if c["distance"] != UNKNOWN]
    for settings in settings_texts:
        if not step_fits_phone(settings, phone_row):
            reasons.append("setting_does_not_fit_phone")
            break
    return list(dict.fromkeys(reasons))


# --------------------------------------------------------------------------- the case scorer


@dataclass(frozen=True)
class ShotCardCase:
    """What the scorer needs to know about one dataset case (its `expected` block)."""

    category: str
    answered: frozenset[str]
    phone_model: str | None
    # question id -> the tapped answer as its English option (only answered questions)
    answers: tuple[tuple[str, str], ...] = ()
    # every word the creator said in the chat: the request and the tapped answers, folded
    chat_words: frozenset[str] = frozenset()
    # the case's "Your audience" line (owner decision B); None = no line, i.e. not available
    audience_summary: str | None = None

    @classmethod
    def from_expected(cls, expected: Mapping[str, Any]) -> ShotCardCase:
        """`answers` (question id -> tapped answer) and `request` are what value-level support
        is judged on. Without them every value that needs the creator's words (a light side, a
        place, a prop side) reads as unsupported, never as known."""
        category = str(expected["category"])
        if category not in SIZE_RULES:
            raise ValueError(f"unknown shot-card category {category!r}")
        answered = frozenset(str(q) for q in expected.get("answered") or [])
        unknown = answered - set(COACH_QUESTIONS)
        if unknown:
            raise ValueError(f"not coach question ids: {sorted(unknown)}")
        raw_answers = expected.get("answers") or {}
        if not isinstance(raw_answers, Mapping):
            raise ValueError("answers must map question ids to the tapped answers")
        if raw_answers and set(raw_answers) != answered:
            raise ValueError(f"answers {sorted(raw_answers)} do not match answered {sorted(answered)}")
        answers: list[tuple[str, str]] = []
        for qid, answer in sorted(raw_answers.items()):
            canonical = canonical_answer(str(qid), str(answer))
            if canonical is None:
                raise ValueError(f"{qid}: {answer!r} is not one of the question's options")
            answers.append((str(qid), canonical))
        said = [str(expected.get("request") or ""), *(str(a) for a in raw_answers.values())]
        chat_words = frozenset(w for text in said for w in _words(text))
        phone = expected.get("phone_model")
        audience = expected.get("audience_summary")
        return cls(
            category,
            answered,
            phone.strip() if isinstance(phone, str) and phone.strip() else None,
            tuple(answers),
            chat_words,
            audience.strip() if isinstance(audience, str) and audience.strip() else None,
        )

    def answer(self, qid: str) -> str | None:
        return dict(self.answers).get(qid)

    def fact_known(self, fact_field: str) -> bool:
        """Whether a question (or the saved phone) this fact may come from was answered."""
        for source in FACT_SOURCES[fact_field]:
            if source == SAVED_PHONE:
                if self.phone_model:
                    return True
            elif source in self.answered:
                return True
        return False

    def window_side(self) -> str | None:
        """The light side the window_side answer names ("front" / "behind"), else None."""
        answer = self.answer("window_side")
        return WINDOW_SIDE_NAMES.get(answer) if answer else None

    def value_known(self, fact_field: str, value: str) -> bool:
        """Whether the creator's own words support this creator-fact VALUE (owner decision 1):
        the question answered is necessary, and where no option can name the value (a light
        side, a place, a prop side and surface) the answer or the chat must name it."""
        if fact_field == "place":
            words = [w for w in _words(value) if w not in _PLACE_STOPWORDS]
            return bool(words) and all(w in self.chat_words for w in words)
        if not self.fact_known(fact_field):
            return False
        if fact_field == "light":
            kind, sep, side = value.partition("-")
            return not sep or (kind == "window" and side == self.window_side())
        if fact_field == "prop":
            if value == "none":
                return True
            side, _, surface = value.partition("-")
            return bool(self.chat_words & _PROP_SIDE_WORDS.get(side, frozenset())) and bool(
                self.chat_words & _PROP_SURFACE_WORDS.get(surface, frozenset())
            )
        return True


@dataclass(frozen=True)
class ShotCardScore:
    F: float
    S: float
    P: float
    C: float
    total: float
    guessed_facts: int
    renders_as_card: bool
    has_block: bool
    beats: int
    malformed_card_lines: int
    truncated: bool
    category: str
    contradictions: tuple[str, ...] = ()
    guessed: tuple[str, ...] = field(default=())

    def as_metrics(self) -> dict[str, float]:
        """Flat floats for the harness report (every case has the same keys)."""
        return {
            "F": self.F,
            "S": self.S,
            "P": self.P,
            "C": self.C,
            "total": self.total,
            "guessed_facts": float(self.guessed_facts),
            "renders_as_card": 1.0 if self.renders_as_card else 0.0,
            "has_block": 1.0 if self.has_block else 0.0,
            "beats": float(self.beats),
            "malformed_card_lines": float(self.malformed_card_lines),
            "truncated": 1.0 if self.truncated else 0.0,
            "category_id": float(CATEGORY_KEYS.index(self.category)),
        }


def beat_fields(parsed: ParsedReply, number: int) -> dict[str, str]:
    """The 13 values for beat `number` (1-based): its card when the reply has the block (no
    line for that beat = no card = all "?"), the fixed extractor when it has none."""
    if parsed.has_block:
        return dict(parsed.cards.get(number) or {k: UNKNOWN for k in FIELD_ORDER})
    return extract_fields_from_prose(parsed.beats[number - 1], parsed.setup)


def score_reply(text: str, case: ShotCardCase, *, stop_reason: str | None = None) -> ShotCardScore:
    parsed = parse_reply(text)
    contradictions = tuple(
        dict.fromkeys(
            [
                *find_contradictions(text, parsed, case.phone_model),
                *made_for_audience_guesses(text, case.audience_summary),
            ]
        )
    )
    c_score = 0.0 if contradictions else 100.0
    renders = parse_meera_script(text) is not None
    if not parsed.beats:
        return ShotCardScore(
            0.0, 0.0, 0.0, c_score, 0.0, 0, renders, parsed.has_block, 0,
            parsed.malformed_card_lines, stop_reason == "max_tokens", case.category, contradictions,
        )

    f_scores: list[float] = []
    s_scores: list[float] = []
    p_scores: list[float] = []
    guessed: list[str] = []
    for number, beat in enumerate(parsed.beats, start=1):
        values = beat_fields(parsed, number)
        filled = [k for k in FIELD_ORDER if values[k] != UNKNOWN]
        guessed_here = [k for k in filled if k in FACT_SOURCES and not case.value_known(k, values[k])]
        guessed += [f"S{number}.{k}" for k in guessed_here]
        f_scores.append((len(filled) - len(guessed_here)) / len(FIELD_ORDER))
        s_scores.append(1.0 if values["size"] in allowed_sizes(case.category, beat) else 0.0)

        # P asks only for what the creator's answers make knowable: never a prop side (only a
        # product_side answer or the chat names one) and a light side only where window_side names it.
        checks = [values["stand"] != UNKNOWN, values["text"] != UNKNOWN]
        if case.window_side() is not None:
            checks.append("-" in values["light"])
        p_scores.append(sum(checks) / len(checks))

    f_value = 100.0 * statistics.fmean(f_scores)
    s_value = 100.0 * statistics.fmean(s_scores)
    p_value = 100.0 * statistics.fmean(p_scores)
    total = statistics.fmean([f_value, s_value, p_value, c_score])
    return ShotCardScore(
        round(f_value, 2), round(s_value, 2), round(p_value, 2), c_score, round(total, 2),
        len(guessed), renders, parsed.has_block, len(parsed.beats), parsed.malformed_card_lines,
        stop_reason == "max_tokens", case.category, contradictions, tuple(guessed),
    )


def reply_text(raw: Mapping[str, Any] | str) -> str:
    if isinstance(raw, str):
        return raw
    value = raw.get("response") if isinstance(raw, Mapping) else None
    return value if isinstance(value, str) else ""


# --------------------------------------------------------------------------- aggregate + ship rule


def aggregate(per_case: Sequence[Mapping[str, float]]) -> tuple[dict[str, float], list[str]]:
    """One run's summary and its hard failures (a single run can always go red)."""
    agg: dict[str, float] = {
        metric: statistics.fmean([s[metric] for s in per_case]) for metric in ("F", "S", "P", "C", "total")
    }
    agg["guessed_facts"] = sum(s["guessed_facts"] for s in per_case)
    agg["renders_as_card"] = statistics.fmean([s["renders_as_card"] for s in per_case])
    agg["with_block"] = statistics.fmean([s["has_block"] for s in per_case])
    agg["truncated"] = sum(s["truncated"] for s in per_case)
    for index, category in enumerate(CATEGORY_KEYS):
        totals = [s["total"] for s in per_case if int(s["category_id"]) == index]
        if totals:
            agg[f"total[{category}]"] = statistics.fmean(totals)
    failures: list[str] = []
    if agg["C"] < 100.0:
        bad = sum(1 for s in per_case if s["C"] < 100.0)
        failures.append(f"C {agg['C']:.1f} < 100 ({bad} repl(ies) contradict a live rule; must be 0)")
    if agg["guessed_facts"] > 0:
        failures.append(
            f"{agg['guessed_facts']:.0f} guessed creator fact(s): a field filled with no coach answer "
            "behind it (owner decision 1: must be 0, unanswered is '?')"
        )
    if agg["renders_as_card"] < 1.0:
        failures.append(
            f"renders_as_card {agg['renders_as_card']:.2f} < 1.00: a reply the app shows as a plain "
            "bubble, not a script card (R6: parser lag or a broken reply)"
        )
    return agg, failures


def _run_summary(case_scores: Mapping[str, Mapping[str, float]]) -> tuple[float, dict[str, float]]:
    per_case = list(case_scores.values())
    total = statistics.fmean([s["total"] for s in per_case])
    by_category: dict[str, float] = {}
    for index, category in enumerate(CATEGORY_KEYS):
        totals = [s["total"] for s in per_case if int(s["category_id"]) == index]
        if totals:
            by_category[category] = statistics.fmean(totals)
    return total, by_category


MIN_RUNS = 3
MIN_TOTAL_GAIN = 10.0
MAX_CATEGORY_DROP = 5.0


def ship_verdict(
    before_runs: Sequence[Mapping[str, Mapping[str, float]]],
    after_runs: Sequence[Mapping[str, Mapping[str, float]]],
    *,
    before_ids: Sequence[str | None] | None = None,
    after_ids: Sequence[str | None] | None = None,
) -> tuple[bool, list[str], dict[str, float]]:
    """The spec's ship rule over saved per-case scores (case id -> metrics, one mapping per run):
    medians of 3+ runs each side; total +10 or more; C 100 in every after run; no category's
    median down by more than 5; plus no guessed fact and every reply a script card after.

    `before_ids` / `after_ids` are each run's persona sha256 (`run_eval.shot_card_run_identity`).
    When given, a run without one, or a before and an after sharing one, is refused: the two
    arms carry the same PROMPT_VERSION, so only the persona text tells them apart."""
    reasons: list[str] = []
    if before_ids is not None or after_ids is not None:
        ids = [*(before_ids or [None]), *(after_ids or [None])]
        if any(not i for i in ids):
            reasons.append("a run has no persona sha256: save it with this harness's --save-scores")
            return False, reasons, {}
        shared = set(before_ids or ()) & set(after_ids or ())
        if shared:
            reasons.append(
                "before and after ran the same creator persona (sha256 "
                + ", ".join(sorted(s[:12] for s in shared if s))
                + "): record before with SHOT_CARD_EVAL_PERSONA_REF set to the commit before the card prompt"
            )
            return False, reasons, {}
    if len(before_runs) < MIN_RUNS or len(after_runs) < MIN_RUNS:
        reasons.append(f"need at least {MIN_RUNS} runs each side (got {len(before_runs)} before, {len(after_runs)} after)")
        return False, reasons, {}
    case_sets = {frozenset(run) for run in [*before_runs, *after_runs]}
    if len(case_sets) != 1:
        reasons.append("runs scored different case sets; compare the same dataset only")
        return False, reasons, {}

    before = [_run_summary(run) for run in before_runs]
    after = [_run_summary(run) for run in after_runs]
    summary = {
        "before_total": statistics.median(t for t, _ in before),
        "after_total": statistics.median(t for t, _ in after),
    }
    summary["gain"] = summary["after_total"] - summary["before_total"]
    if summary["gain"] < MIN_TOTAL_GAIN:
        reasons.append(f"total gain {summary['gain']:.1f} < {MIN_TOTAL_GAIN:.0f} points")
    for n, run in enumerate(after_runs, start=1):
        if any(s["C"] < 100.0 for s in run.values()):
            reasons.append(f"after run {n}: C is not 100 (a reply contradicts a live rule)")
        if any(s["guessed_facts"] > 0 for s in run.values()):
            reasons.append(f"after run {n}: a guessed creator fact")
        if any(s["renders_as_card"] < 1.0 for s in run.values()):
            reasons.append(f"after run {n}: a reply that is not a script card")
    for category in CATEGORY_KEYS:
        b = [cats[category] for _, cats in before if category in cats]
        a = [cats[category] for _, cats in after if category in cats]
        if not b or not a:
            continue
        drop = statistics.median(b) - statistics.median(a)
        summary[f"drop[{category}]"] = drop
        if drop > MAX_CATEGORY_DROP:
            reasons.append(f"category {category} drops {drop:.1f} > {MAX_CATEGORY_DROP:.0f} points")
    return not reasons, reasons, summary


def load_saved_scores(path: Path) -> dict[str, dict[str, float]]:
    return load_saved_run(path)[0]


def load_saved_run(path: Path) -> tuple[dict[str, dict[str, float]], dict[str, str]]:
    """A `--save-scores` file: its per-case scores and its `run_identity` (empty when absent)."""
    with Path(path).open(encoding="utf-8") as fh:
        data = json.load(fh)
    scores = data.get("case_scores") if isinstance(data, dict) and "case_scores" in data else data
    if not isinstance(scores, dict) or not scores:
        raise ValueError(f"{path}: no case scores")
    identity = data.get("run_identity") if isinstance(data, dict) else None
    return scores, dict(identity) if isinstance(identity, dict) else {}


# --------------------------------------------------------------------------- live-run conversation

CLOSING_LINE: dict[str, str] = {
    "en": "That's everything. Write the full script now, no more questions.",
    "hi": "Bas itna hi. Ab full script likh do, aur sawaal nahi.",
}


def _lang(tag: Any) -> str:
    return "hi" if isinstance(tag, str) and tag.lower().startswith("hi") else "en"


def build_conversation(case_input: Mapping[str, Any]) -> list[dict[str, str]]:
    """The chat a live run replays before Meera writes: the creator's request, then each
    pre-filled coach question as Meera asked it (the bank's own wording and options, in the
    creator's language) with the creator's tapped answer, then the creator's "write it now"."""
    lang = _lang(case_input.get("creator_language"))
    turns: list[dict[str, str]] = [{"role": "user", "content": str(case_input["request"])}]
    answers = case_input.get("coach_answers") or {}
    for qid in COACH_QUESTIONS:  # the bank's own order, so every run replays the same chat
        if qid not in answers:
            continue
        row = COACH_QUESTIONS[qid]
        question = row["question_hi"] if lang == "hi" else row["question_en"]
        options = row["options_hi"] if lang == "hi" else row["options"]
        turns.append({"role": "assistant", "content": f"{question} ({' / '.join(options)})"})
        turns.append({"role": "user", "content": str(answers[qid])})
    turns.append({"role": "user", "content": CLOSING_LINE[lang]})
    return turns


def build_creator_context(case_input: Mapping[str, Any]) -> dict[str, Any]:
    """The Block B creator context for one case: only allow-listed CREATOR fields."""
    return {
        "workspace_id": "eval-shot-card",
        "first_name": case_input.get("first_name") or "Creator",
        "display_name": case_input.get("first_name") or "Creator",
        "categories": list(case_input.get("categories") or []),
        "creator_language": case_input.get("creator_language") or "en-IN",
        "phone_model": case_input.get("phone_model"),
        "content_goal": case_input.get("content_goal"),
        # Owner decision B: the case's "Your audience" line, when it has one (absent = the
        # assembler's own "not available" line).
        **({"audience_summary": case_input["audience_summary"]} if case_input.get("audience_summary") else {}),
        "brand_tone": "FRIENDLY",
        "tools_enabled": [],
    }


def main(argv: Sequence[str] | None = None) -> int:
    """`python -m evals.shot_card_scorer --before b1.json b2.json b3.json --after a1.json ...`
    (files written by `run_eval.py --live shot_card_plan --save-scores <file>`)."""
    import argparse

    parser = argparse.ArgumentParser(description=main.__doc__)
    parser.add_argument("--before", nargs="+", required=True, type=Path)
    parser.add_argument("--after", nargs="+", required=True, type=Path)
    args = parser.parse_args(argv)
    before = [load_saved_run(p) for p in args.before]
    after = [load_saved_run(p) for p in args.after]
    ok, reasons, summary = ship_verdict(
        [scores for scores, _ in before],
        [scores for scores, _ in after],
        before_ids=[identity.get("persona_sha256") for _, identity in before],
        after_ids=[identity.get("persona_sha256") for _, identity in after],
    )
    for key, value in summary.items():
        print(f"  {key:<28} {value:.2f}")
    print("SHIP: yes" if ok else "SHIP: no")
    for reason in reasons:
        print(f"  - {reason}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
