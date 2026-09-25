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
"""

from __future__ import annotations

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


@dataclass(frozen=True)
class ScriptBeat:
    start: int
    end: int
    shot: str
    say: str
    stress: str | None
    pause: str | None
    on_screen: str


@dataclass(frozen=True)
class ParsedMeeraScript:
    idea: str
    plan: str
    action: str
    success_looks_like: str | None
    beats: tuple[ScriptBeat, ...]
    caption: str
    before_you_shoot: tuple[str, str, str]
    why_this_works: str
    follow_up: str | None


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

    plan = _split_key_value(lines[i])
    i += 1
    if not plan or not _key_is(plan, "plan") or not plan[1]:
        return None

    action = _split_key_value(lines[i])
    i += 1
    if not action or not _key_is(action, "action") or not action[1]:
        return None

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
        success_looks_like=success,
        beats=tuple(beats),
        caption=caption[1],
        before_you_shoot=before_you_shoot,
        why_this_works=why[1],
        follow_up=follow_up,
    )
