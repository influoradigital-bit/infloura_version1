"""Meera intelligence v1, slice 2 (spec 8.3): what Meera recommended, read off her final reply.

The write-back `POST /internal/meera/messages` of a CREATOR turn carries `metadata.recommendations`
(at most 7 items) plus `prompt_version` and `knowledge_version`; Spring's
`CreatorRecommendationService.recordFromWriteback` validates each item on its own and records it
with `source_ref = messageId:line_index`. The checked-in example Java deserialises is
`tests/fixtures/creator_tools/writeback_recommendations.sample.json`; every item built here uses
exactly its keys (`ITEM_KEYS`).

Everything here is deterministic: no model call, no model tool, and no fact the model invented.

- PLAN_MY_WEEK: one item per day line of the week plan (the persona's "Week plan format",
  e.g. "Mon 28 Sep. Evening. Reel. <idea>. <structure name>. Goal: <goal>."). The date is resolved
  ONLY against the `days` list of a `plan_my_week` tool result from the SAME turn -- day of month
  and month must name one of those days, and a weekday, when written, must agree with it. With no
  such result nothing is recorded, however plan-like the text is.
- SCRIPT_CARD: one item when the whole reply is a script card, decided by
  `script_card.parse_meera_script`, the port of the browser's own `parseMeeraScript`. A reply the
  app shows as a plain bubble records nothing.

`recommendations_for_turn` is the only entry point `app/routes/chat.py` uses. It never raises and
never records from a BRAND turn, a failed or refused turn, or a truncated one.
"""

from __future__ import annotations

import logging
import re
from datetime import date
from typing import Any, Iterable, Sequence

from app.auth.audience import AUDIENCE_CREATOR
from app.planner.week_plan import enrich_week_plan
from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_ROWS
from app.recommendations.script_card import parse_meera_script, strip_meera_markdown

logger = logging.getLogger(__name__)

SOURCE_PLAN_MY_WEEK = "PLAN_MY_WEEK"
SOURCE_SCRIPT_CARD = "SCRIPT_CARD"

# The write-back item's keys, exactly the fixture's (and Java's WritebackRecommendation's).
ITEM_KEYS: tuple[str, ...] = (
    "source",
    "line_index",
    "recommended_for",
    "post_type",
    "window_label",
    "window_from",
    "window_to",
    "structure_name",
    "hook_template",
    "topic",
    "festival",
)

# CreatorRecommendationService.MAX_WRITEBACK_ITEMS: the week plan has 7 lines.
MAX_ITEMS = 7
# A reply longer than this is not a week plan or a script card; it is not scanned at all, which
# also bounds the parse time of the write-back path.
MAX_TEXT_CHARS = 20_000
# The script card's only item. The item's position in the reply: a card is the whole reply and
# starts with its Idea line.
SCRIPT_CARD_LINE_INDEX = 0

# Only a clean end of turn is recorded. "refusal" (the model declined), "max_tokens" (the reply was
# cut off) and anything else unknown record nothing. None: the provider reported no reason.
_RECORDABLE_STOP_REASONS = frozenset({None, "end_turn"})


# --------------------------------------------------------------------------- knowledge names

def _collapse(text: str) -> str:
    return " ".join(text.split())


STRUCTURE_NAMES: tuple[str, ...] = tuple(
    _collapse(r["framework"]) for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "storytelling_structure"
)
HOOK_TEMPLATES: tuple[str, ...] = tuple(
    _collapse(r["template"]) for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "hook_template"
)


def _the_one_named(text: str, names: Sequence[str]) -> str | None:
    """The knowledge name `text` contains, spelled exactly as the knowledge spells it (only runs
    of whitespace are collapsed). None when it names none -- or more than one, which says nothing
    about which one the recommendation rests on."""
    haystack = _collapse(text)
    found = {name for name in names if name and name in haystack}
    return found.pop() if len(found) == 1 else None


# --------------------------------------------------------------------------- post type

_POST_TYPES: dict[str, str] = {}
for _word in ("reel", "reels", "instagram reel", "instagram reels", "youtube short", "youtube shorts",
              "short", "shorts", "short video", "short videos", "video", "videos", "reel or short",
              "reel or youtube short", "reel/short", "reel / short"):
    _POST_TYPES[_word] = "REEL"
for _word in ("carousel", "carousels", "carousel post", "carousel posts"):
    _POST_TYPES[_word] = "CAROUSEL"
for _word in ("photo", "photos", "photo post", "photo posts", "image", "image post", "static post",
              "single image", "post"):
    _POST_TYPES[_word] = "POST"

_REST_WORDS = frozenset(
    {"rest", "rest day", "reply day", "rest or reply day", "rest/reply day", "rest / reply day",
     "rest and reply day", "day off", "off", "break"}
)
_PARENTHETICAL = re.compile(r"\([^)]*\)")


def _plain(segment: str) -> str:
    """Lower case, parentheticals and a leading article dropped, whitespace collapsed."""
    text = _collapse(_PARENTHETICAL.sub(" ", segment)).lower().strip(" .,:;!")
    return re.sub(r"^(?:an?|one)\s+", "", text)


# --------------------------------------------------------------------------- time window

_DAYPARTS = ("morning", "afternoon", "evening", "night")
_LABEL_RE = re.compile(
    r"\b(?:(weekday|weekend)s?\s+)?(?:(?:early|late)\s+)?(morning|afternoon|evening|night)s?\b"
)
_TIME = r"([0-9]{1,2})(?:[:.]([0-9]{2}))?\s*(am|pm|a\.m\.|p\.m\.)?"
_RANGE_RE = re.compile(rf"(?<![0-9]){_TIME}\s*(?:-|\u2013|\u2014|to)\s*{_TIME}(?![0-9])")
_SINGLE_TIME_RE = re.compile(rf"(?<![0-9]){_TIME}(?![0-9])")
_TIME_FILLER = re.compile(r"\b(?:around|at|approx|approximately|about|ist)\b|[\s,()~]")


def _meridiem(token: str | None) -> str | None:
    if not token:
        return None
    return "am" if token.startswith("a") else "pm"


def _to_24h(hour: int, minute: int, meridiem: str | None) -> int | None:
    """Minutes after midnight, or None when the clock reading is not a real one."""
    if minute > 59:
        return None
    if meridiem is None:
        return hour * 60 + minute if 0 <= hour <= 23 else None
    if not 1 <= hour <= 12:
        return None
    hour = hour % 12 + (12 if meridiem == "pm" else 0)
    return hour * 60 + minute


def _hhmm(minutes: int) -> str:
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def _daypart_of(minutes: int) -> str:
    """CreatorPostRules.daypartOf: morning 05:00-11:59, afternoon 12:00-16:59, evening
    17:00-21:59, night 22:00-04:59."""
    if 5 * 60 <= minutes < 12 * 60:
        return "morning"
    if 12 * 60 <= minutes < 17 * 60:
        return "afternoon"
    if 17 * 60 <= minutes < 22 * 60:
        return "evening"
    return "night"


def _resolve_range(match: re.Match[str], daypart: str | None) -> tuple[int, int] | None:
    h1, m1, mer1, h2, m2, mer2 = match.groups()
    start_h, end_h = int(h1), int(h2)
    start_m, end_m = int(m1 or 0), int(m2 or 0)
    mer1, mer2 = _meridiem(mer1), _meridiem(mer2)
    if mer1 is None and mer2 is None:
        if start_h > 12 or end_h > 12 or start_h == 0 or end_h == 0:
            pass  # a 24-hour clock ("18:00-21:00")
        elif daypart in ("afternoon", "evening", "night"):
            mer1 = mer2 = "pm"
        elif daypart == "morning":
            mer1 = mer2 = "am"
        else:
            return None  # "6-9" with nothing to say which half of the day
        start, end = _to_24h(start_h, start_m, mer1), _to_24h(end_h, end_m, mer2)
    elif mer1 is None or mer2 is None:
        # One meridiem written ("6-9 pm", "11-1 pm", "10 am-1"): it applies to both ends unless
        # that puts the start after the end, in which case the unwritten end is the other half.
        given = mer1 or mer2
        other = "am" if given == "pm" else "pm"
        start = _to_24h(start_h, start_m, mer1 or given)
        end = _to_24h(end_h, end_m, mer2 or given)
        if start is not None and end is not None and start >= end:
            if mer1 is None:
                start = _to_24h(start_h, start_m, other)
            else:
                end = _to_24h(end_h, end_m, other)
    else:
        start, end = _to_24h(start_h, start_m, mer1), _to_24h(end_h, end_m, mer2)
    if start is None or end is None or start == end:
        return None
    return start, end


def _parse_window(segment: str) -> dict[str, str | None] | None:
    """A segment that is ONLY a posting time -- "Evening", "weekday evening (6-9 pm)", "7 pm",
    "18:00-21:00" -- as window_label / window_from / window_to; None when anything else is in it
    (so "3 mistakes" is never read as 3 o'clock)."""
    text = _collapse(segment).lower()
    label_match = _LABEL_RE.search(text)
    daypart = label_match.group(2) if label_match else None
    rest = text
    if label_match:
        rest = rest[: label_match.start()] + " " + rest[label_match.end():]
    range_match = _RANGE_RE.search(rest)
    single_match = None if range_match else _SINGLE_TIME_RE.search(rest)
    time_match = range_match or single_match
    if time_match:
        rest = rest[: time_match.start()] + " " + rest[time_match.end():]
    if _TIME_FILLER.sub("", rest):
        return None
    if not label_match and not time_match:
        return None

    window_from = window_to = None
    label = None
    if label_match:
        kind = label_match.group(1)
        label = f"{kind} {daypart}" if kind else daypart
    if range_match:
        resolved = _resolve_range(range_match, daypart)
        if resolved is not None:
            window_from, window_to = _hhmm(resolved[0]), _hhmm(resolved[1])
    elif single_match and label is None:
        h, m, mer = single_match.groups()
        minutes = _to_24h(int(h), int(m or 0), _meridiem(mer))
        if minutes is not None and (mer or int(h) > 12 or int(h) == 0):
            label = _daypart_of(minutes)
    if label is None and window_from is None:
        return None
    return {"window_label": label, "window_from": window_from, "window_to": window_to}


# --------------------------------------------------------------------------- the plan's days

_WEEKDAY_RE = (
    r"mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:r|rs|rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"
)
_MONTHS: dict[str, int] = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}
_MONTH_RE = (
    r"jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
    r"|sep(?:t|tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
)
# "Mon 28 Sep." / "1. Fri 25 Sep -" / "- Monday, 28 September:" at the very start of a line.
_DAY_LINE_RE = re.compile(
    rf"^\s*(?:[0-9]{{1,2}}[.)]\s+|[-*\u2022]\s+)?(?:({_WEEKDAY_RE})\.?,?\s+)?"
    rf"([0-9]{{1,2}})(?:st|nd|rd|th)?\s+({_MONTH_RE})\b\.?"
    rf"\s*(?:[.,:;|]|[-\u2013\u2014](?=\s))?\s*(.*)$",
    re.IGNORECASE,
)
_SEGMENT_SEP = re.compile(r"\.\s+|\s+[-\u2013\u2014|]\s+|;\s+")
_GOAL_RE = re.compile(r"^goal\s*:", re.IGNORECASE)


class _PlanDay:
    __slots__ = ("iso", "weekday", "events")

    def __init__(self, iso: str, weekday: str, events: list[dict[str, Any]]):
        self.iso = iso
        self.weekday = weekday
        self.events = events


def _plan_days(plan_results: Iterable[Any]) -> dict[tuple[int, int], _PlanDay]:
    """(day of month, month) -> the plan's day, from the LAST usable plan_my_week result of the
    turn. Spring's `date` is authoritative; the festival calendar is attached by the same
    `enrich_week_plan` the model's copy was built with."""
    usable = [r for r in plan_results if isinstance(r, dict) and isinstance(r.get("days"), list)]
    if not usable:
        return {}
    enriched = enrich_week_plan(usable[-1])
    days = enriched.get("days") if isinstance(enriched, dict) else None
    out: dict[tuple[int, int], _PlanDay] = {}
    for day in days or []:
        if not isinstance(day, dict):
            continue
        try:
            parsed = date.fromisoformat(str(day.get("date", "")).strip())
        except ValueError:
            continue
        weekday = parsed.strftime("%A").lower()
        events = [e for e in day.get("events") or [] if isinstance(e, dict)]
        out[(parsed.day, parsed.month)] = _PlanDay(parsed.isoformat(), weekday, events)
    return out


def _festival_aliases(name: str) -> list[str]:
    """"Navratri and Durga Puja" -> [Navratri, Durga Puja]; "Eid al-Adha (Bakrid)" ->
    [Eid al-Adha, Bakrid]; "Independence Day (India)" -> [Independence Day]."""
    aliases: list[str] = []
    for inner in re.findall(r"\(([^)]*)\)", name):
        if inner.strip().lower() != "india":
            aliases.append(inner.strip())
    main = _PARENTHETICAL.sub(" ", name)
    for part in re.split(r",\s*|\s+and\s+", main):
        part = _collapse(part)
        if len(part) >= 3:
            aliases.append(part)
    return aliases


def _festival_for(line: str, day: _PlanDay) -> str | None:
    """The calendar event placed on this day that the line itself mentions, by its calendar name.
    Only the plan's own events for that day are candidates, never a name the model brought."""
    lowered = line.lower()
    for event in day.events:
        name = event.get("name")
        if not isinstance(name, str):
            continue
        for alias in _festival_aliases(name):
            if re.search(rf"(?<!\w){re.escape(alias.lower())}(?!\w)", lowered):
                return name
    return None


def _parse_plan_line(line: str, line_index: int, days: dict[tuple[int, int], _PlanDay]) -> dict[str, Any] | None:
    match = _DAY_LINE_RE.match(line)
    if not match:
        return None
    weekday_word, day_text, month_word, rest = match.groups()
    day = days.get((int(day_text), _MONTHS[month_word[:3].lower()]))
    if day is None:
        return None
    if weekday_word and not day.weekday.startswith(weekday_word[:3].lower()):
        return None  # "Tue 28 Sep" when the plan says the 28th is a Monday: not the plan's day

    rest = rest.strip().rstrip(".").strip()
    segments: list[tuple[int, int, str]] = []
    cursor = 0
    for sep in _SEGMENT_SEP.finditer(rest):
        segments.append((cursor, sep.start(), rest[cursor:sep.start()]))
        cursor = sep.end()
    segments.append((cursor, len(rest), rest[cursor:]))
    segments = [(s, e, t.strip()) for s, e, t in segments if t.strip()]

    item: dict[str, Any] = {key: None for key in ITEM_KEYS}
    item["source"] = SOURCE_PLAN_MY_WEEK
    item["line_index"] = line_index
    item["recommended_for"] = day.iso
    have_window = False
    topic_parts: list[int] = []
    for idx, (_s, _e, text) in enumerate(segments):
        plain = _plain(text)
        if plain in _REST_WORDS:
            return None
        if _GOAL_RE.match(text):
            continue
        if item["post_type"] is None and plain in _POST_TYPES:
            item["post_type"] = _POST_TYPES[plain]
            continue
        window = _parse_window(text)
        if window is not None:
            if not have_window:
                item.update(window)
                have_window = True
            else:
                # "Evening. 6-9 pm." -- a second time-only segment completes the first.
                for key, value in window.items():
                    if item[key] is None:
                        item[key] = value
            continue
        if item["structure_name"] is None and _collapse(text) in STRUCTURE_NAMES:
            item["structure_name"] = _collapse(text)
            continue
        unquoted = _collapse(text.strip("\"'\u201c\u201d\u2018\u2019"))
        if item["hook_template"] is None and unquoted in HOOK_TEMPLATES:
            item["hook_template"] = unquoted
            continue
        topic_parts.append(idx)

    if item["post_type"] is None:
        return None
    if item["structure_name"] is None:
        item["structure_name"] = _the_one_named(rest, STRUCTURE_NAMES)
    if topic_parts:
        if topic_parts == list(range(topic_parts[0], topic_parts[-1] + 1)):
            topic = rest[segments[topic_parts[0]][0] : segments[topic_parts[-1]][1]]
        else:
            topic = ". ".join(segments[i][2] for i in topic_parts)
        item["topic"] = _collapse(topic) or None
    item["festival"] = _festival_for(line, day)
    return item


def parse_week_plan(text: str, plan_results: Iterable[Any]) -> list[dict[str, Any]]:
    """One PLAN_MY_WEEK item per parseable day line; nothing without a same-turn plan result.

    `line_index` is the line's position among the reply's day lines (0-based), so a rest day or a
    line that does not parse still takes its index -- a replay of the same text gives the same
    `source_ref`s. Only the first 7 day lines are read."""
    days = _plan_days(plan_results)
    if not days:
        return []
    items: list[dict[str, Any]] = []
    day_line_index = 0
    for line in strip_meera_markdown(text).split("\n"):
        if not _DAY_LINE_RE.match(line):
            continue
        if day_line_index >= MAX_ITEMS:
            break
        item = _parse_plan_line(line, day_line_index, days)
        day_line_index += 1
        if item is not None:
            items.append(item)
    return items


def parse_script_card(text: str) -> list[dict[str, Any]]:
    """One SCRIPT_CARD item when the reply is a script card the app renders, else nothing."""
    script = parse_meera_script(text)
    if script is None:
        return []
    item: dict[str, Any] = {key: None for key in ITEM_KEYS}
    item.update(
        source=SOURCE_SCRIPT_CARD,
        line_index=SCRIPT_CARD_LINE_INDEX,
        post_type="REEL",  # the persona writes scripts for Reels and YouTube Shorts only
        structure_name=_the_one_named(script.why_this_works, STRUCTURE_NAMES),
        hook_template=_the_one_named(script.why_this_works, HOOK_TEMPLATES),
        topic=_collapse(script.idea) or None,
    )
    return [item]


def build_recommendations(text: str, plan_results: Iterable[Any]) -> list[dict[str, Any]]:
    """The items for one reply. A script card is the whole reply (the parser refuses anything
    around it), so a reply is either a script card or scanned for plan lines, never both."""
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
        return []
    items = parse_script_card(text) or parse_week_plan(text, plan_results)
    return items[:MAX_ITEMS]


def recommendations_for_turn(
    *,
    audience: str | None,
    final_text: str,
    plan_results: Sequence[Any],
    provider_failed: bool,
    finish_reason: str | None,
    stop_reason: str | None,
    workspace_id: str | None = None,
    request_id: str | None = None,
) -> list[dict[str, Any]]:
    """The write-back's `metadata.recommendations` for one finished turn, or [] -- never raises.

    Records nothing from a BRAND turn, a turn whose provider failed, a turn that did not end with
    a clean `stop` (fallbacks, caps, a declined money tool) or whose stop reason is a refusal or a
    cut-off. A parser bug costs the recommendations, never the write-back."""
    if audience != AUDIENCE_CREATOR:
        return []
    if provider_failed or finish_reason != "stop" or stop_reason not in _RECORDABLE_STOP_REASONS:
        return []
    try:
        return build_recommendations(final_text, plan_results)
    except Exception as exc:  # noqa: BLE001 - a parser failure must never reach the write-back
        from app.security.redaction import log_event

        log_event(
            logger,
            logging.WARNING,
            "meera_recommendations_parse_failed",
            workspace_id=workspace_id,
            request_id=request_id,
            fields={"error_type": type(exc).__name__},
        )
        return []
