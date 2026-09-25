"""Shared untrusted-content neutralization for prompt injection defense.

Hoisted from brand_safety.py after red-team review (wiki/errors/brand-safety-
endpoint-C2-security-review.md HIGH-1) found pattern-based `.replace()`
vulnerable to case variation (`</UNTRUSTED>`) and split-rejoin (`</un</x>trusted>`).

V1.2 hardening (2026-07-11): assembler.py `_wrap_untrusted` + `build_block_b`
now use this structural fix instead of the bypassable single `.replace()` call.
"""

from __future__ import annotations

import re
import unicodedata


def neutralize_angle_brackets(text: str) -> str:
    """Replaces every literal `<` and `>` in `text` with their HTML entity
    equivalents so the text cannot form ANY tag — opening or closing, any
    case, any spacing, any nesting/splitting arrangement.

    This is a structural fix, not a pattern-based strip: a case-sensitive,
    single-pass `.replace("</untrusted_caption>", "")` (the previous defense)
    is bypassable via case variation (`</UNTRUSTED_CAPTION>`) and via
    split-rejoin (`</untr</untrusted_caption>usted_caption>` — the inner
    exact-case match gets deleted and the outer fragments rejoin into a fresh
    close tag). Neither bypass is possible here: once every `<`/`>` byte is
    gone from the caption, there is no substring of the caption that is a
    `<` or `>` character, so no substring of it — before or after any single-
    pass scan looking for an exact string — can ever be a tag boundary.

    Always apply this to ANY untrusted string (creator captions, scraped HTML,
    user chat text, third-party JSON fields) before interpolating it into ANY
    role in a prompt — system blocks, user messages, delimited regions,
    anywhere untrusted text appears. The only exception is when the string has
    already been passed through an XML-escaping serializer that enforces the
    same rule (e.g. standard library ElementTree with text-node insertion),
    in which case applying it a second time is harmless but redundant.

    The delimiter `<untrusted_X>...</untrusted_X>` pattern is a second layer
    — once this function has run on the interior text, the delimiters are
    structurally safe even if the text was an injection attempt. Delimiters
    alone (without neutralization) are bypassable; neutralization alone
    (without delimiters) means untrusted content can still emit raw text that
    sounds like instructions. Both layers together are required.
    """
    return text.replace("<", "&lt;").replace(">", "&gt;")


def wrap_untrusted(label: str, content: str) -> str:
    """Wrap untrusted data (scraped HTML text, raw pasted user content, any
    third-party string that could contain an injection) so the model treats it
    as data-not-instructions, wrapped in delimiters AND neutralized.

    This combines the structural fix (neutralize_angle_brackets) with the
    delimiter pattern. The delimiter tells the model "this is data"; the
    neutralization makes it impossible for the content to break out of the
    delimiter even if it was crafted to do exactly that.
    """
    safe_content = neutralize_angle_brackets(content)
    return f"<untrusted_{label}>\n{safe_content}\n</untrusted_{label}>"


# --- "[Photo check" headers in replayed USER turns -------------------------------------------
#
# A photo check reaches Meera as an earlier ASSISTANT turn whose text starts with "[Photo check"
# (influora-api PhotoCheckSummary). The persona counts ONLY such an assistant turn as a check. A
# USER turn can never be one, so every header-like bracket in it is softened to "(" here, on the
# server, whatever the client sent. The same rule as the web client's `neutralisePhotoCheckHeader`
# (meera-api.ts) and influora-api's `PhotoCheckChatWriter.neutralisePhotoCheckHeader`.

_PHOTO_CHECK_HOMOGLYPHS = {
    "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "х": "x", "у": "y", "к": "k", "т": "t",
    "һ": "h", "н": "h", "і": "i",
    "ο": "o", "ρ": "p", "τ": "t", "κ": "k", "ε": "e", "χ": "x", "ϲ": "c", "η": "h",
    "օ": "o", "հ": "h",
    "ᴘ": "p", "ʜ": "h", "ᴏ": "o", "ᴛ": "t", "ᴄ": "c", "ᴇ": "e", "ᴋ": "k",
}
_LINE_LEAD_CHARS = frozenset("*_>#`~+-|=\u2022\u00b7\u2023\u25e6\u25aa\u25cf\u115f\u1160\u3164\uffa0\u2800")
_LINE_BREAK_SPLIT = re.compile(r"(\r\n|[\n\r\v\f\x85\u2028\u2029])")
_CHECKBOX = re.compile(r"\[[ xX]\](?=\s|$)")
_HEADER_LOOKAHEAD = 64


def _fold_letters(text: str) -> str:
    """The letters only: NFKD, lowercase, lookalikes mapped, every non-letter dropped."""
    out = []
    for ch in unicodedata.normalize("NFKD", text).lower():
        ch = _PHOTO_CHECK_HOMOGLYPHS.get(ch, ch)
        if unicodedata.category(ch).startswith("L"):
            out.append(ch)
    return "".join(out)


def _is_line_lead(ch: str) -> bool:
    return (
        ch.isspace()
        or ch in _LINE_LEAD_CHARS
        or unicodedata.category(ch) in ("Cf", "Mn", "Me", "Mc", "Zs")
    )


def _neutralize_line(line: str) -> str:
    chars = list(line)
    lead = 0
    while lead < len(chars):
        if _is_line_lead(chars[lead]):
            lead += 1
            continue
        digits = 0
        while lead + digits < len(chars) and digits < 3 and chars[lead + digits] in "0123456789":
            digits += 1
        if digits and lead + digits < len(chars) and chars[lead + digits] in ".)":
            lead += digits + 1
            continue
        break
    if (
        lead < len(chars)
        and unicodedata.category(chars[lead]) == "Ps"
        and chars[lead] != "("
        and not _CHECKBOX.match(line, lead)
    ):
        chars[lead] = "("
    for i, ch in enumerate(chars):
        if ch == "(" or unicodedata.category(ch) != "Ps":
            continue
        if _fold_letters("".join(chars[i + 1 : i + 1 + _HEADER_LOOKAHEAD])).startswith("photocheck"):
            chars[i] = "("
    return "".join(chars)


def neutralize_photo_check_header(text: str) -> str:
    """Softens every header-like bracket in `text`, line by line, to "(":

    - a line's first real character (after whitespace, invisible format characters, combining
      marks and markdown lead-ins such as "**", "- ", "> ", "# ", "1. ") when it is an opening
      bracket of any script ("[", "［", "⟦", "【", ...), whatever follows it;
    - anywhere on a line, an opening bracket followed by letters that fold to "photo check"
      (NFKD, lowercase, lookalike letters, any separator).

    A markdown checkbox ("[ ]", "[x]") is left alone. Used on replayed USER turns, which can never
    be photo checks.
    """
    parts = _LINE_BREAK_SPLIT.split(text)
    return "".join(part if i % 2 == 1 else _neutralize_line(part) for i, part in enumerate(parts))
