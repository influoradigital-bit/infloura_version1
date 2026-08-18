#!/usr/bin/env python3
"""gates/_code.py — the python sibling of gates/_code.sh. origin: F-0266, F-0325, F-0329.

WHY THIS EXISTS. F-0266 gave the 53 SHELL gates one shared, tested way to look at CODE
rather than at file bytes — `gates/_code.sh`, which shells out to `gates/_strip_comments.py`.
The .py gates were never migrated, because they do their own file reading: eight of them
still call `read_text()` / `open()` and regex the raw bytes. F-0329's sweep found the first
casualty (notifications_wired.py) and it was reproduced at exit 1 against a tree that was
completely correct — the only match in the repository was the fix's own comment naming the
array it had deleted. See .proof-os/tasks/T-F0329-GATES/notifications_wired.inject.log.

THE TWO DISEASES, both closed here, both the same missing distinction:
  · FALSE RED  — a gate FORBIDS a token and the fix's comment quotes it. The gate fails the
                 very fix that closed the record, and the pressure that creates is to stop
                 documenting fixes.
  · FALSE GREEN— a gate REQUIRES a token ("the layout must call useNotifications") and the
                 token appears only in a comment, or in the sentence someone wrote while
                 DELETING the call. Worse: a closed record standing on nothing.

LAW (false-red, F-0013/F-0015/F-0017, inherited verbatim from _code.sh). If the stripper
cannot run, this module raises CodeUnavailable and the calling gate must exit 2. It NEVER
silently falls back to raw bytes — a silent fallback restores the exact defect this file
removes, and does it invisibly: the gate would look hardened and behave like the old one.

ONE TOKENIZER, NOT TWO. This module IMPORTS gates/_strip_comments.py rather than
re-implementing it, so the shell population and the python population can never drift into
disagreeing about what a comment is. Its blind spots are that file's blind spots (template
literals, regex literals containing `/`) and are reproduced at the bottom of this docstring
so a caller does not have to go and find them.

F-0325 (a gate that crashed emitting `<=`). `print()` encodes through the CONSOLE codepage,
which on this project's Windows hosts is cp1252. A verdict containing an em dash, `->`,
`<=` or `Rs.` raised UnicodeEncodeError and the gate died mid-sentence — a false RED that
said nothing at all about the artefact it guarded. `harden_stdout()` sets errors='replace'
on stdout/stderr so a verdict always reaches the operator, degraded rather than absent.
This is the same failure `_strip_comments.py` fixed for its OWN output by writing to
sys.stdout.buffer; do not regress that one either — this module never re-encodes the
stripper's bytes, it decodes them as UTF-8 exactly as they were written.

USAGE from a .py gate:

    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        from _code import CodeUnavailable, code_of, code_lines, harden_stdout
    except Exception as e:
        print(f"gates/_code.py unreadable ({e}) - unavailable"); sys.exit(2)
    harden_stdout()
    try:
        text = code_of(some_tsx_path)          # comments blanked, line numbers preserved
    except CodeUnavailable as e:
        print(f"{e} - unavailable"); sys.exit(2)

`code_lines()` returns 1-indexed (lineno, text) pairs off the SAME stripped view, so a gate
can still print a real, correct location for what it found.

KNOWN BLIND SPOTS (from _strip_comments.py; stated, not silently exceeded):
  · JS/TS template literals are one opaque string, so a `//` inside a `${...}` that itself
    contains a string is not modelled correctly.
  · A regex literal containing `/` can be misread as the start of a line comment.
  · This is a tokenizer, not a parser. It tells a comment from a string literal from code.
    It does NOT know whether the code it leaves behind is reachable, exported, or dead.
"""
import sys
from pathlib import Path

_SELF = Path(__file__).resolve().parent


class CodeUnavailable(Exception):
    """The comment-free view could not be produced. The caller MUST exit 2, never 0 or 1."""


# --------------------------------------------------------------------------------------
# the one tokenizer
# --------------------------------------------------------------------------------------
def _load_stripper():
    """Import gates/_strip_comments.py as a module. Raises CodeUnavailable if it is gone."""
    path = _SELF / "_strip_comments.py"
    if not path.is_file():
        raise CodeUnavailable(
            f"comment stripper missing at {path} - cannot tell code from comment")
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location("_proof_strip_comments", str(path))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)  # type: ignore[union-attr]
    except Exception as e:                                    # noqa: BLE001
        raise CodeUnavailable(f"comment stripper at {path} would not load: {e}") from e
    if not hasattr(mod, "LANGS"):
        raise CodeUnavailable(
            f"comment stripper at {path} has no LANGS table - wrong file or a rewrite")
    return mod


_STRIP = None


def _stripper():
    global _STRIP
    if _STRIP is None:
        _STRIP = _load_stripper()
    return _STRIP


LANG_BY_SUFFIX = {
    ".ts": "ts", ".tsx": "ts", ".js": "ts", ".jsx": "ts", ".mjs": "ts", ".cjs": "ts",
    ".java": "java",
    ".sh": "shell", ".bash": "shell",
}


def lang_for(path) -> str:
    """The --lang the stripper should use, from the extension. Refuses to guess.

    A WRONG grammar silently deletes code (shell's `#` rule applied to TS would eat every
    `#private` field and every `#` in a string), so an unknown extension is unavailable,
    not 'probably ts'.
    """
    suffix = Path(path).suffix.lower()
    lang = LANG_BY_SUFFIX.get(suffix)
    if lang is None:
        raise CodeUnavailable(
            f"no comment grammar known for {path} - refusing to guess "
            "(a wrong grammar silently deletes code)")
    return lang


def code_of_text(text: str, lang: str) -> str:
    """Comment-free view of TEXT. Newlines are preserved, so line numbers survive."""
    fn = _stripper().LANGS.get(lang)
    if fn is None:
        raise CodeUnavailable(
            f"unknown language {lang!r} - known: {sorted(_stripper().LANGS)}")
    return fn(text)


def code_of(path, lang: str = "") -> str:
    """Comment-free view of a FILE. Drop-in for `Path(p).read_text()` in a grep gate.

    Decoded strictly as UTF-8: `errors='replace'` would let a mis-decoded byte silently
    become U+FFFD in the middle of a token a gate is about to match on, which is a wrong
    answer wearing a right answer's clothes. A file that is not UTF-8 is unavailable.
    """
    p = Path(path)
    if not p.is_file():
        raise CodeUnavailable(f"{p} is not a readable file")
    lang = lang or lang_for(p)
    try:
        raw = p.read_bytes()
    except OSError as e:
        raise CodeUnavailable(f"cannot read {p}: {e}") from e
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        raise CodeUnavailable(f"{p}: not UTF-8 text ({e}) - cannot tokenize") from e
    return code_of_text(text, lang)


def code_lines(path, lang: str = ""):
    """[(lineno, text), ...] over the comment-free view. 1-indexed, same numbers as the
    real file, so a finding can still be printed with a location an operator can open."""
    return list(enumerate(code_of(path, lang).splitlines(), 1))


def code_lines_of_text(text: str, lang: str):
    """code_lines() for text a gate already holds — used by frozen self-check fixtures."""
    return list(enumerate(code_of_text(text, lang).splitlines(), 1))


# --------------------------------------------------------------------------------------
# F-0325 — a verdict that reaches the operator on a cp1252 console
# --------------------------------------------------------------------------------------
def harden_stdout() -> None:
    """Make print() incapable of killing the gate on a non-UTF-8 console.

    Call it FIRST, before any print. Encoding is left alone (a cp1252 console still
    renders cp1252 correctly); only the error handler changes, so a character the console
    cannot represent degrades to '?' instead of raising UnicodeEncodeError halfway through
    the verdict line.
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")       # py3.7+
        except Exception:                              # noqa: BLE001
            pass


if __name__ == "__main__":
    # `python _code.py <file>` prints the comment-free view — the same thing the gates see.
    harden_stdout()
    if len(sys.argv) != 2:
        print("usage: _code.py <file>   (prints the comment-free view)")
        sys.exit(64)
    try:
        out = code_of(sys.argv[1]).encode("utf-8")
    except CodeUnavailable as exc:
        print(f"{exc} - unavailable")
        sys.exit(2)
    buf = getattr(sys.stdout, "buffer", None)
    if buf is None:
        sys.stdout.write(out.decode("utf-8"))
    else:
        buf.write(out)
        buf.flush()
    sys.exit(0)
