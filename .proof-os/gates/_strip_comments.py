#!/usr/bin/env python3
"""gates/_strip_comments.py — shared comment stripper for grep-based gates.

origin: F-0266 (gate-cannot-tell-code-from-comment). A grep gate that forbids a
string literal fails on the correct fix that removes it, because the fix's own
explanatory comment QUOTES the forbidden string, and the gate had no notion of
"comment" vs "code" at all — it grepped the raw file. Reproduced against
gates/F-0238-no-fabricated-contract-pdf.sh: a tree where `downloadContractPDF`
is genuinely unreachable from the live branch, and the only occurrence of the
retired toast string "Opened a local copy" is inside a trailing `//` comment
and a JSX `{/* ... */}` comment, was reported "broken (F-0238 regressed)" at
exit 1. That gate already carried a first attempt at this (a line-start-only
`grep -vE '^\\s*(//|\\*|/\\*)'` filter) which strips a comment only when the
ENTIRE line is the comment — it does not see a trailing same-line comment or a
brace-wrapped JSX comment, both reproduced above. This module replaces that
inline filter with one shared, tested implementation every such gate can call.

Usage (from a shell gate, following the gates/_oracles.py sharing convention —
a module other gates read from a fixed relative path, never copy-pasted):

    SELF="$(cd "$(dirname "$0")" && pwd)"
    CODE=$(python3 "$SELF/_strip_comments.py" --lang ts "$F" 2>&1) || {
      echo "· comment-stripping helper failed on $F — unavailable"; exit 2; }
    printf '%s' "$CODE" | grep -q "forbidden string" && fail=1

Supported --lang values:
  ts     TypeScript / TSX / JS / JSX / MJS / CJS: `//` line comments, `/* */`
         block comments. A JSX `{/* ... */}` comment is a `/* */` block
         wrapped in braces — stripping the block comment already empties the
         quoted text out of it; the surrounding `{` `}` are left as inert
         punctuation, which is fine because callers grep the RESULT for a
         forbidden substring, not for valid syntax.
  java   `//` line comments, `/* */` block comments (javadoc `/** */` falls
         under the same block-comment handling — nothing distinguishes it).
  shell  `#` line comments.

This is a tokenizer, not a parser: it exists only to tell "inside a comment"
from "inside a string literal" from "code", so that:
  - `"https://example.com"` is not truncated at the `//` (ts/java) — a `//`
    inside an open string does not start a comment.
  - `"# not a comment"` is not truncated at the `#` (shell) — same rule.
  - escaped quotes (`\\"`, `\\'`) inside a string do not end the string early.

KNOWN BLIND SPOTS (report these, do not silently claim more than this does):
  - JS/TS template literals (`` ` ``) are tracked as one opaque string, so a
    `//` or `/*` inside a `${...}` interpolation that itself contains a
    string is not modelled correctly — this is a known limitation, not a
    silent wrong answer, because template literals are rare in the gated
    surfaces (JSX attribute/child text, not build logic).
  - Regex literals containing `/` (e.g. `/\\/\\//`) are not distinguished from
    division, so a `//` inside a regex literal can be misread as a line
    comment start. Not observed in any gate's grepped subject files to date.
  - Nested block comments are not a thing in any of the three grammars this
    module claims to support, so `/* /* */ */` closes at the first `*/`,
    matching real JS/TS/Java semantics (nesting is illegal there too).
"""
import sys


def strip_ts(text: str) -> str:
    """// and /* */ comments; " ' ` strings with backslash-escapes."""
    out = []
    i, n = 0, len(text)
    in_line_comment = False
    in_block_comment = False
    str_char = None  # one of " ' `
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                out.append(c)
            i += 1
            continue
        if in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if str_char:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(nxt)
                i += 2
                continue
            if c == str_char:
                str_char = None
            i += 1
            continue
        # not inside a string or comment
        if c in ('"', "'", "`"):
            str_char = c
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_java(text: str) -> str:
    # Same comment/string grammar as TS for our purposes: //, /* */, " and '
    # (Java has no backtick strings, but leaving backtick handling in is
    # harmless — Java source never contains an unmatched backtick).
    return strip_ts(text)


def strip_shell(text: str) -> str:
    """# comments; " ' strings. No /* */ block comments in shell."""
    out = []
    i, n = 0, len(text)
    str_char = None
    while i < n:
        c = text[i]
        if str_char:
            out.append(c)
            if c == "\\" and str_char == '"' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == str_char:
                str_char = None
            i += 1
            continue
        if c in ('"', "'"):
            str_char = c
            out.append(c)
            i += 1
            continue
        if c == "#":
            while i < n and text[i] != "\n":
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


LANGS = {
    "ts": strip_ts, "tsx": strip_ts, "js": strip_ts, "jsx": strip_ts,
    "mjs": strip_ts, "cjs": strip_ts,
    "java": strip_java,
    "shell": strip_shell, "sh": strip_shell,
}


def main(argv):
    if len(argv) != 3 or argv[0] != "--lang":
        print("usage: _strip_comments.py --lang <ts|java|shell> <file>",
              file=sys.stderr)
        return 64
    lang, path = argv[1], argv[2]
    fn = LANGS.get(lang)
    if fn is None:
        print(f"unknown --lang {lang!r} — known: {sorted(LANGS)}", file=sys.stderr)
        return 64
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError as e:
        print(f"cannot read {path}: {e}", file=sys.stderr)
        return 2
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        print(f"{path}: not UTF-8 text ({e}) — cannot tokenize", file=sys.stderr)
        return 2
    # The file was DECODED as UTF-8 above, so it must be ENCODED as UTF-8 on the way
    # out. `sys.stdout.write` uses the console's codepage, which on this project's
    # Windows hosts is cp1252: every source file containing `₹` (the money screens) or
    # `→` (the admin audit panels) crashed the stripper with UnicodeEncodeError, and the
    # gate that called it reported UNAVAILABLE. Honest, but useless — and it silently
    # excluded exactly the money surfaces from comment-aware checking. Writing to the
    # binary buffer bypasses the console codepage entirely.
    out = fn(text).encode("utf-8")
    buf = getattr(sys.stdout, "buffer", None)
    if buf is None:                      # a stdout with no binary buffer (rare)
        sys.stdout.write(out.decode("utf-8"))
    else:
        buf.write(out)
        buf.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
