"""Shared untrusted-content neutralization for prompt injection defense.

Hoisted from brand_safety.py after red-team review (wiki/errors/brand-safety-
endpoint-C2-security-review.md HIGH-1) found pattern-based `.replace()`
vulnerable to case variation (`</UNTRUSTED>`) and split-rejoin (`</un</x>trusted>`).

V1.2 hardening (2026-07-11): assembler.py `_wrap_untrusted` + `build_block_b`
now use this structural fix instead of the bypassable single `.replace()` call.
"""

from __future__ import annotations


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
