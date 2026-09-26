"""Python port of `parseMeeraScript` (src/lib/meera-result-cards.ts) -- read-only twin.

Meera intelligence v1, slice 2 (spec 8.3.1): a SCRIPT_CARD recommendation is recorded only for a
reply the app itself turns into a script card. The browser decides that with `parseMeeraScript`;
this module decides it here with the same rules, so "counts as a script" means one thing on both
sides. A reply the TS parser refuses (a plain bubble in the chat) must produce no recommendation.

Ported line for line, with the JavaScript semantics kept where Python's defaults differ:

- `stripMeeraMarkdown` (src/lib/meera-text.ts) first: CRLF -> LF, `---`/`***`/`___` divider lines
  dropped, one leading `#`..`######` heading marker removed per line, `**x**` / `__x__` unwrapped.
- JS `String.prototype.trim` / `\\s` whitespace (WhiteSpace + LineTerminator), not Python's
  `str.isspace` set, which also counts U+001C..U+001F and U+0085 and misses U+FEFF.
- JS `.` never matches `\\n`, `\\r`, U+2028 or U+2029; Python's `.` matches all but `\\n`.
- JS `\\d` is ASCII only; the regex `i` flag without `u` never folds a non-ASCII letter onto an
  ASCII one (Python's IGNORECASE folds U+017F onto `s` and U+212A onto `k`), hence re.ASCII.

Parity is pinned by tests/recommendations/test_script_card_parity.py, which runs every script
text of src/lib/meera-result-cards.test.ts (copied into tests/fixtures/meera_scripts.json) plus
the persona's own example through this port and asserts the TS test's own verdicts.

Shot cards (spec v2 Phase 6, risk R6): the optional block after the last beat and before
`Caption:` -- a line `Shot cards:`, then `S<n>: ` + 13 `key=value` pairs joined by `; ` in the
fixed order of `SHOT_CARD_KEYS`. Replies with or without the block are both script cards, so
SCRIPT_CARD recording never stops because the prompt started (or stopped) writing it. Inside the
block nothing refuses the card: a malformed line, an `S<n>` for a beat that does not exist or a
beat named twice only leaves that beat without a card; an unknown enum value or an over-long free
text is `?` for that one field. The fixture's `shot_card_cases` pin every card field, and
src/lib/meera-result-cards.shot-cards.test.ts runs the same fixture through the real TS parser.

Made for (owner decision B, 2026-09-26): an optional `Made for:` line right after `Idea:` -- the
one-line basis of the script (audience, topic and its source, goal). Only in that position. A
value that is empty or longer than `MADE_FOR_MAX_CHARS` code points is consumed and ignored
(`made_for` None), never a refusal; a reply without the line parses exactly as before. Pinned
against the TS parser by the fixture's `made_for_cases`.
"""

from __future__ import annotations

import dataclasses
import re
from dataclasses import dataclass

# ECMAScript WhiteSpace + LineTerminator: what `trim()` removes and `\s` matches.
_JS_WS_CHARS = (
    "\t\n\x0b\x0c\r \xa0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009"
    "\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
)
_WS = "[" + re.escape(_JS_WS_CHARS) + "]"
# JS `.` (no `s` flag): anything but a line terminator.
_DOT = r"[^\n\r\u2028\u2029]"


def _js_trim(value: str) -> str:
    return value.strip(_JS_WS_CHARS)


# --------------------------------------------------------------------------- stripMeeraMarkdown

_DIVIDER = re.compile(rf"^{_WS}*([-*_])\1{{2,}}{_WS}*$")
_HEADING = re.compile(rf"^({_WS}*)#{{1,6}}{_WS}+")
_BOLD = re.compile(rf"\*\*({_DOT}+?)\*\*|__({_DOT}+?)__")


def strip_meera_markdown(text: str) -> str:
    """`stripMeeraMarkdown` in src/lib/meera-text.ts."""
    out: list[str] = []
    for line in text.replace("\r\n", "\n").split("\n"):
        if _DIVIDER.search(line):
            continue
        line = _HEADING.sub(r"\1", line, count=1)
        line = _BOLD.sub(lambda m: m.group(1) if m.group(1) is not None else m.group(2), line)
        out.append(line)
    return "\n".join(out)


def _split_lines(raw: str) -> list[str]:
    lines = strip_meera_markdown(raw).split("\n")
    start, end = 0, len(lines)
    while start < end and _js_trim(lines[start]) == "":
        start += 1
    while end > start and _js_trim(lines[end - 1]) == "":
        end -= 1
    return lines[start:end]


def _split_key_value(line: str) -> tuple[str, str] | None:
    """Splits on the FIRST `:` only (a value may itself contain a colon)."""
    idx = line.find(":")
    if idx == -1:
        return None
    key = _js_trim(line[:idx])
    value = _js_trim(line[idx + 1 :])
    if not key:
        return None
    return key, value


_WS_RUN = re.compile(rf"{_WS}+")


def _key_is(kv: tuple[str, str], expected: str) -> bool:
    return _WS_RUN.sub(" ", kv[0].lower()) == expected


# --------------------------------------------------------------------------- parseMeeraScript

_OPEN_QUOTE = "[\u201c\"]"
_CLOSE_QUOTE = "[\u201d\"]"
_QUOTE_CHARS = frozenset({'"', "\u201c", "\u201d"})

_BEAT_RE = re.compile(
    rf"^([0-9]+)-([0-9]+)s\.{_WS}*Shot:{_WS}*({_DOT}+?)\.{_WS}*Say:{_WS}*{_OPEN_QUOTE}({_DOT}+?){_CLOSE_QUOTE}\.{_WS}*"
    rf"(?:Stress:{_WS}*({_DOT}+?)\.{_WS}*Pause:{_WS}*({_DOT}+?)\.{_WS}*)?"
    rf"On screen:{_WS}*({_DOT}+?)\.?{_WS}*$",
    re.IGNORECASE | re.ASCII,
)
_BEFORE_YOU_SHOOT_RE = re.compile(
    rf"^1\){_WS}*({_DOT}+?){_WS}*2\){_WS}*({_DOT}+?){_WS}*3\){_WS}*({_DOT}+)$"
)


# --------------------------------------------------------------------------- shot cards

SHOT_CARD_UNKNOWN = "?"
SHOT_CARD_KEYS: tuple[str, ...] = (
    "size", "height", "distance", "place", "light", "stand", "headroom",
    "eyes", "background", "space", "text", "prop", "move",
)
SHOT_SIZES = ("ECU", "CU", "MCU", "MS", "MLS", "FS", "LS", "OVERHEAD")
CAMERA_HEIGHTS = ("eye", "chest", "above", "below", "overhead")
LIGHT_KINDS = ("window", "sun", "shade", "lamp", "ring_light", "tube_light", "mixed")
LIGHT_SIDES = ("left", "right", "front", "behind")  # the creator's OWN side (spec 2.4)
STAND_POSITIONS = ("left", "centre", "right")
HEADROOMS = ("cropped", "small", "medium")
EYE_LINES = ("lens", "product", "off_lens")
NEGATIVE_SPACES = ("left", "right", "top", "none")
TEXT_POSITIONS = ("top", "opposite_face", "lower_middle", "none")
PROP_SIDES = ("left", "centre", "right")
PROP_SURFACES = ("hand", "table", "floor")
MOVEMENTS = ("still", "sit", "stand", "walk", "pan", "push")
# Free text is capped in code points (`len`), the same count as the TS `Array.from(v).length`;
# a longer value is `?`, never cut.
SHOT_CARD_TEXT_MAX = {"distance": 20, "place": 40, "background": 40}
# The longest `Made for:` value kept, in code points (`len`, the TS `Array.from(v).length`,
# `MADE_FOR_MAX_CHARS` in src/lib/meera-result-cards.ts). A longer value is ignored, not refused.
MADE_FOR_MAX_CHARS = 200

_UPPER_TO_LOWER = {c: c + 32 for c in range(ord("A"), ord("Z") + 1)}
_LOWER_TO_UPPER = {c: c - 32 for c in range(ord("a"), ord("z") + 1)}
# `S<n>:` at the start of a block line; ASCII digits, no Unicode case folding (as the TS regex).
_SHOT_LINE_RE = re.compile(rf"^S{_WS}*([0-9]+){_WS}*:", re.IGNORECASE | re.ASCII)
# One trailing `.` or `;` (the spec's own example line ends with a full stop).
_TRAILING_STOP_RE = re.compile(rf"[.;]{_WS}*\Z")


@dataclass(frozen=True)
class ShotCard:
    """One beat's shot card: every field is a contract value or `?` (unknown)."""

    size: str
    height: str
    distance: str
    place: str
    light: str
    stand: str
    headroom: str
    eyes: str
    background: str
    space: str
    text: str
    prop: str
    move: str


def _ascii_lower(value: str) -> str:
    """A-Z only, like the TS `asciiLower` (full Unicode case mapping differs across languages)."""
    return value.translate(_UPPER_TO_LOWER)


def _ascii_upper(value: str) -> str:
    return value.translate(_LOWER_TO_UPPER)


def _enum_value(value: str, allowed: tuple[str, ...], fold) -> str:
    folded = fold(value)
    return folded if folded in allowed else SHOT_CARD_UNKNOWN


def _free_text(value: str, limit: int) -> str:
    if not value or value == SHOT_CARD_UNKNOWN:
        return SHOT_CARD_UNKNOWN
    return value if len(value) <= limit else SHOT_CARD_UNKNOWN


def _light_value(value: str) -> str:
    folded = _ascii_lower(value)
    kind, dash, side = folded.partition("-")
    if kind not in LIGHT_KINDS:
        return SHOT_CARD_UNKNOWN
    if not dash:
        return folded
    return folded if side in LIGHT_SIDES else SHOT_CARD_UNKNOWN


def _prop_value(value: str) -> str:
    folded = _ascii_lower(value)
    if folded == "none":
        return "none"
    side, dash, surface = folded.partition("-")
    if dash and side in PROP_SIDES and surface in PROP_SURFACES:
        return folded
    return SHOT_CARD_UNKNOWN


def _parse_shot_card_body(body: str) -> ShotCard | None:
    """`parseShotCardBody`: the text after `S<n>:` -> a card, or None when not exactly 13
    `key=value` pairs in `SHOT_CARD_KEYS` order. A bad value is `?`, never a malformed line."""
    text = _TRAILING_STOP_RE.sub("", _js_trim(body), count=1)
    pairs = text.split(";")
    if len(pairs) != len(SHOT_CARD_KEYS):
        return None
    raw: dict[str, str] = {}
    for key, pair in zip(SHOT_CARD_KEYS, pairs):
        name, eq, value = pair.partition("=")
        if not eq or _ascii_lower(_js_trim(name)) != key:
            return None
        raw[key] = _js_trim(value)
    return ShotCard(
        size=_enum_value(raw["size"], SHOT_SIZES, _ascii_upper),
        height=_enum_value(raw["height"], CAMERA_HEIGHTS, _ascii_lower),
        distance=_free_text(raw["distance"], SHOT_CARD_TEXT_MAX["distance"]),
        place=_free_text(raw["place"], SHOT_CARD_TEXT_MAX["place"]),
        light=_light_value(raw["light"]),
        stand=_enum_value(raw["stand"], STAND_POSITIONS, _ascii_lower),
        headroom=_enum_value(raw["headroom"], HEADROOMS, _ascii_lower),
        eyes=_enum_value(raw["eyes"], EYE_LINES, _ascii_lower),
        background=_free_text(raw["background"], SHOT_CARD_TEXT_MAX["background"]),
        space=_enum_value(raw["space"], NEGATIVE_SPACES, _ascii_lower),
        text=_enum_value(raw["text"], TEXT_POSITIONS, _ascii_lower),
        prop=_prop_value(raw["prop"]),
        move=_enum_value(raw["move"], MOVEMENTS, _ascii_lower),
    )


def _shot_cards_for_beats(block_lines: list[str], beat_count: int) -> list[ShotCard | None]:
    """`shotCardsForBeats`: one card or None per beat. A line that is not a well-formed `S<n>:`
    line for an existing beat is dropped; a beat named twice gets no card."""
    cards: list[ShotCard | None] = [None] * beat_count
    seen: set[int] = set()
    doubled: set[int] = set()
    for line in block_lines:
        trimmed = _js_trim(line)
        match = _SHOT_LINE_RE.search(trimmed)
        if not match:
            continue
        n = int(match.group(1))
        if n < 1 or n > beat_count:
            continue
        if n in seen:
            doubled.add(n)
        seen.add(n)
        cards[n - 1] = _parse_shot_card_body(trimmed[match.end() :])
    for n in doubled:
        cards[n - 1] = None
    return cards


@dataclass(frozen=True)
class ScriptBeat:
    start: int
    end: int
    shot: str
    say: str
    stress: str | None
    pause: str | None
    on_screen: str
    # From the optional `Shot cards:` block; None when the reply has no block or no well-formed
    # `S<n>:` line for this beat (the TS `card?`).
    card: ShotCard | None = None


@dataclass(frozen=True)
class ParsedMeeraScript:
    idea: str
    plan: str
    action: str
    # Optional, like the TS `setup?`: replies written before the persona added the line lack it.
    setup: str | None
    success_looks_like: str | None
    beats: tuple[ScriptBeat, ...]
    caption: str
    before_you_shoot: tuple[str, str, str]
    why_this_works: str
    follow_up: str | None
    # From the optional `Made for:` line right after `Idea:` (the TS `madeFor?`). None when the
    # line is absent, empty or over `MADE_FOR_MAX_CHARS`.
    made_for: str | None = None


def _strip_wrapping_quotes(line: str) -> str:
    if len(line) >= 2 and line[0] in _QUOTE_CHARS and line[-1] in _QUOTE_CHARS:
        return line[1:-1]
    return line


def _parse_before_you_shoot(value: str) -> tuple[str, str, str] | None:
    match = _BEFORE_YOU_SHOOT_RE.search(_js_trim(value))
    if not match:
        return None
    a, b, c = (_js_trim(g) for g in match.groups())
    if not a or not b or not c:
        return None
    return a, b, c


def parse_meera_script(text: object) -> ParsedMeeraScript | None:
    """`parseMeeraScript`: the card, or None wherever the TS function returns `undefined`."""
    if not isinstance(text, str):
        return None
    lines = _split_lines(text)
    if len(lines) < 10:
        return None

    i = 0
    idea = _split_key_value(lines[i])
    i += 1
    if not idea or not _key_is(idea, "idea") or not idea[1]:
        return None

    # Made for: optional, only here (right after Idea, before Plan). Unlike Set-up, an empty or
    # over-long value does not refuse the card: the line is consumed and simply not kept.
    made_for: str | None = None
    maybe_made_for = _split_key_value(lines[i]) if i < len(lines) else None
    if maybe_made_for and _key_is(maybe_made_for, "made for"):
        if maybe_made_for[1] and len(maybe_made_for[1]) <= MADE_FOR_MAX_CHARS:
            made_for = maybe_made_for[1]
        i += 1

    plan = _split_key_value(lines[i])
    i += 1
    if not plan or not _key_is(plan, "plan") or not plan[1]:
        return None

    action = _split_key_value(lines[i])
    i += 1
    if not action or not _key_is(action, "action") or not action[1]:
        return None

    # Set-up: optional, and only in this one position (after Action, before Success looks like),
    # also read as `Setup:` or `Set up:`. Present but empty is refused, like every other label.
    setup: str | None = None
    maybe_setup = _split_key_value(lines[i]) if i < len(lines) else None
    if maybe_setup and (
        _key_is(maybe_setup, "set-up") or _key_is(maybe_setup, "setup") or _key_is(maybe_setup, "set up")
    ):
        if not maybe_setup[1]:
            return None
        setup = maybe_setup[1]
        i += 1

    success: str | None = None
    maybe_success = _split_key_value(lines[i]) if i < len(lines) else None
    if maybe_success and _key_is(maybe_success, "success looks like"):
        if not maybe_success[1]:
            return None
        success = maybe_success[1]
        i += 1

    if i >= len(lines):
        return None
    script = _split_key_value(lines[i])
    i += 1
    if not script or not _key_is(script, "script"):
        return None
    if script[1]:
        return None

    beats: list[ScriptBeat] = []
    while i < len(lines):
        match = _BEAT_RE.search(_strip_wrapping_quotes(_js_trim(lines[i])))
        if not match:
            break
        start = int(match.group(1))
        end = int(match.group(2))
        shot = _js_trim(match.group(3))
        say = _js_trim(match.group(4))
        stress = _js_trim(match.group(5)) if match.group(5) is not None else None
        pause = _js_trim(match.group(6)) if match.group(6) is not None else None
        on_screen = _js_trim(match.group(7))
        if not shot or not say or not on_screen:
            return None
        beats.append(ScriptBeat(start, end, shot, say, stress or None, pause or None, on_screen))
        i += 1
    if len(beats) < 3:
        return None
    if beats[0].start != 0:
        return None
    for b, beat in enumerate(beats):
        if beat.end <= beat.start:
            return None
        if b > 0 and beat.start != beats[b - 1].end:
            return None

    # Shot cards: optional, only here (after the last beat, before Caption). The block runs up to
    # the `Caption:` line and can only cost a beat its card; `Shot cards:` with a value is refused.
    maybe_cards = _split_key_value(lines[i]) if i < len(lines) else None
    if maybe_cards and _key_is(maybe_cards, "shot cards"):
        if maybe_cards[1]:
            return None
        i += 1
        block_start = i
        while i < len(lines):
            kv = _split_key_value(lines[i])
            if kv and _key_is(kv, "caption"):
                break
            i += 1
        cards = _shot_cards_for_beats(lines[block_start:i], len(beats))
        beats = [dataclasses.replace(beat, card=card) if card else beat for beat, card in zip(beats, cards)]

    if i >= len(lines):
        return None
    caption = _split_key_value(lines[i])
    i += 1
    if not caption or not _key_is(caption, "caption") or not caption[1]:
        return None

    if i >= len(lines):
        return None
    before = _split_key_value(lines[i])
    i += 1
    if not before or not _key_is(before, "before you shoot") or not before[1]:
        return None
    before_you_shoot = _parse_before_you_shoot(before[1])
    if not before_you_shoot:
        return None

    if i >= len(lines):
        return None
    why = _split_key_value(lines[i])
    i += 1
    if not why or not _key_is(why, "why this works") or not why[1]:
        return None

    follow_up: str | None = None
    if i < len(lines):
        follow_up = _js_trim(lines[i])
        i += 1
        if not follow_up:
            return None

    if i != len(lines):
        return None

    return ParsedMeeraScript(
        idea=idea[1],
        plan=plan[1],
        action=action[1],
        setup=setup,
        success_looks_like=success,
        beats=tuple(beats),
        caption=caption[1],
        before_you_shoot=before_you_shoot,
        why_this_works=why[1],
        follow_up=follow_up,
        made_for=made_for,
    )
