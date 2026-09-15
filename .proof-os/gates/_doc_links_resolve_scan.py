# -*- coding: utf-8 -*-
"""
Scanner for gates/F-0807-doc-links-resolve.sh (CLASS gate for F-0807: dead relative
markdown links in docs/docs/features/**/*.md).

WHAT COUNTS AS A LINK: an inline link `[text](destination)` where `text` contains no
unescaped `[` or `]` (this is what keeps a `[CORRECTED ..., F-0807: removed a "See
[../database.md]" link ...]` marker from being read as a link: the marker's outer bracket
nests a `[`, which is disallowed in `text`, and the inner `[../database.md]` is never
followed by `(`, only by `"` closing the quote). A marker that broke that convention by
reproducing full `[x](x)` link syntax inside its own quote IS flagged — correctly: that
syntax renders as a real clickable dead link regardless of the prose wrapped around it.

Also handled: reference-style links (`[text][label]`, collapsed `[text][]`, and shortcut
`[label]` — the last only when `label` matches an actual `[label]: target` definition
elsewhere in the file, which is what keeps a bare `[CORRECTED ...]` marker from being
mistaken for a shortcut reference) and raw HTML `<a href="...">` anchors.

WHAT IS STRIPPED BEFORE ANY MATCHING: fenced code blocks (``` and ~~~), indented code
blocks, inline code spans, and HTML comments. Code and comments are prose's #1 source of
bracket-and-paren text that looks link-shaped but is not one — `handlers[key](payload)` in
a fenced block, `[db](../database.md)` inside a `<!-- TODO -->`, a titled or angle-bracket
link example quoted inside backticks. All four are blanked out (replaced with whitespace,
line count preserved so reported line numbers still line up) before the link scanner ever
sees the text.

DESTINATION PARSING: a destination may be wrapped in `<...>`, may carry a trailing
`"title"` / `'title'` / `(title)`, and may be percent-encoded. All three are unwrapped/
stripped/decoded before the target is resolved against disk, so `[Meera](meera-ai.md
"Meera AI")` and `[Meera](<meera-ai.md>)` both resolve to `meera-ai.md`, not to the title
or the angle brackets glued onto the path.

RESOLUTION: a target resolves only if it names an existing FILE (a target that resolves to
a directory is rejected — `os.path.exists` alone would pass it) whose path components match
the on-disk directory listing byte-for-byte (case-sensitive), even on a case-insensitive
filesystem. That second check exists because this repo is edited on Windows and served from
a case-sensitive Linux docs host: `Meera-AI.md` can resolve fine here and 404 there.

WHAT IS SKIPPED: http(s)/mailto/bare-`//` targets (external — not this gate's job), and a
bare `#anchor` target (intra-page, no file to resolve). A target with a trailing `#anchor`
on a real relative path has the anchor stripped before resolving; the anchor itself is not
verified to exist in the destination file.

WHAT IS NOT HANDLED (deliberately — see the shell wrapper's NOT CHECKED block for the
authoritative list): a link whose `[text]` portion is split across two source lines by a
soft line break. This scanner works line-by-line (after code/comment stripping) so it never
sees a link like that as one string. Handling it means gluing paragraph lines into one
buffer before matching and mapping character offsets back to line numbers for reporting —
a bigger structural change than the mechanical stripping/parsing fixes above, with its own
failure modes (a naive "join non-blank lines" pass can glue across list items, table rows,
or blockquote boundaries that should stay separate). Judged not worth that risk for a class
of link this repo's docs do not currently use; anchors, external links, and other doc trees
remain the other stated limits.

Exit codes: 0 = every relative link resolves, 1 = at least one dead link, 2 = unavailable
(can't even see the tree). --verbose prints one line per dead link found.
"""
import glob
import os
import re
import sys
import urllib.parse

FEATURES_GLOB = "docs/docs/features/**/*.md"

EXTERNAL_PREFIXES = ("http://", "https://", "mailto:", "//")

# ---------------------------------------------------------------------------------------
# Step 1: strip fenced code, indented code, inline code spans and HTML comments before any
# link matching happens. Everything here replaces matched characters with spaces (never
# touching '\n'), so line numbers reported later still point at the original source line.
# ---------------------------------------------------------------------------------------

_HTML_COMMENT_RE = re.compile(r'<!--.*?-->', re.DOTALL)
_FENCE_OPEN_RE = re.compile(r'^( {0,3})(`{3,}|~{3,})')
_INLINE_CODE_RE = re.compile(r'(`+)(.*?)\1')


def _blank(s):
    """Replace every non-newline character in `s` with a space."""
    return ''.join(ch if ch == '\n' else ' ' for ch in s)


def _blank_match(m):
    return _blank(m.group(0))


def mask_non_prose(text):
    """Return `text` with fenced code blocks, indented code blocks, inline code spans and
    HTML comments replaced by whitespace, preserving every line break so line numbers in
    the masked text still match the original file."""
    # HTML comments first (can span multiple lines; regex over the raw text is simplest).
    text = _HTML_COMMENT_RE.sub(_blank_match, text)

    lines = text.split('\n')
    out = []
    fence_char = None
    fence_len = 0
    prev_blank = True
    in_indented = False

    for line in lines:
        if fence_char is not None:
            out.append(_blank(line))
            close = re.match(r'^ {0,3}(`{3,}|~{3,})\s*$', line)
            if close and close.group(1)[0] == fence_char and len(close.group(1)) >= fence_len:
                fence_char = None
                fence_len = 0
            prev_blank = False
            continue

        m = _FENCE_OPEN_RE.match(line)
        if m:
            fence_char = m.group(2)[0]
            fence_len = len(m.group(2))
            out.append(_blank(line))
            prev_blank = False
            continue

        if line.strip() == '':
            out.append(line)
            prev_blank = True
            in_indented = False
            continue

        lead = len(line) - len(line.lstrip(' '))
        tab_indent = line.startswith('\t')
        if (lead >= 4 or tab_indent) and (prev_blank or in_indented):
            out.append(_blank(line))
            in_indented = True
            prev_blank = False
            continue

        in_indented = False
        out.append(_INLINE_CODE_RE.sub(_blank_match, line))
        prev_blank = False

    return '\n'.join(out)


# ---------------------------------------------------------------------------------------
# Step 2: find link-shaped constructs on a (masked) line.
# ---------------------------------------------------------------------------------------

_REF_DEF_RE = re.compile(
    r'''^[ ]{0,3}\[([^\[\]]+)\]:\s*(<[^<>]*>|\S+)(?:\s+(?:"[^"]*"|'[^']*'|\([^()]*\)))?\s*$'''
)
_HTML_A_RE = re.compile(r'<a\s+[^>]*?href\s*=\s*(["\'])(.*?)\1', re.IGNORECASE)


def _find_bracket_text(line, start):
    """Given `line[start] == '['`, return the index just past the matching ']' for a
    bracket whose contents hold no nested '[' or ']', or None if there isn't one."""
    j = start + 1
    n = len(line)
    while j < n and line[j] not in '[]':
        j += 1
    if j < n and line[j] == ']':
        return j
    return None


def iter_link_candidates(line):
    """Yield (whole_match, raw_destination_or_None, ref_label_or_None) for every
    link-shaped construct on `line`: inline `[text](dest)` (raw_destination set), full/
    collapsed reference `[text][label]` / `[text][]` (ref_label set), and shortcut
    `[label]` (ref_label set to the bracket text itself — only a real candidate if that
    label turns out to be defined; the caller checks that)."""
    i = 0
    n = len(line)
    while i < n:
        if line[i] != '[':
            i += 1
            continue
        j = _find_bracket_text(line, i)
        if j is None:
            i += 1
            continue
        text = line[i + 1:j]
        k = j + 1

        if k < n and line[k] == '(':
            depth = 1
            m = k + 1
            while m < n and depth > 0:
                if line[m] == '(':
                    depth += 1
                elif line[m] == ')':
                    depth -= 1
                m += 1
            if depth == 0:
                dest_raw = line[k + 1:m - 1]
                yield (line[i:m], dest_raw, None)
                i = m
                continue

        if k < n and line[k] == '[':
            j2 = _find_bracket_text(line, k)
            if j2 is not None:
                label = line[k + 1:j2]
                yield (line[i:j2 + 1], None, label if label else text)
                i = j2 + 1
                continue

        # Shortcut-reference candidate: bare [text]. Only a real link if `text` matches a
        # defined reference label — the caller enforces that, which is what keeps plain
        # prose brackets (including a `[CORRECTED ...]` marker) from being flagged here.
        yield (line[i:j + 1], None, text)
        i = j + 1


def collect_reference_defs(masked_text):
    """Scan `masked_text` (code/comments already stripped) for `[label]: target "title"`
    reference definitions. Returns {normalized_label: raw_destination}."""
    defs = {}
    for line in masked_text.splitlines():
        m = _REF_DEF_RE.match(line)
        if m:
            label = m.group(1).strip().lower()
            defs[label] = m.group(2)
    return defs


# ---------------------------------------------------------------------------------------
# Step 3: parse a raw destination string into a bare target (strip <...>, strip a trailing
# title, percent-decode).
# ---------------------------------------------------------------------------------------

_ANGLE_DEST_RE = re.compile(
    r'''^<([^<>]*)>\s*(?:"[^"]*"|'[^']*'|\([^()]*\))?\s*$'''
)
_BARE_DEST_RE = re.compile(
    r'''^(\S*)(?:\s+(?:"[^"]*"|'[^']*'|\([^()]*\)))?\s*$'''
)


def parse_destination(raw):
    """Strip `<...>` wrapping and a trailing title from a raw link destination, then
    percent-decode it. Returns the bare target string (may be empty)."""
    s = raw.strip()
    if not s:
        return ""
    m = _ANGLE_DEST_RE.match(s)
    if m:
        target = m.group(1)
    else:
        m2 = _BARE_DEST_RE.match(s)
        target = m2.group(1) if m2 else s
    try:
        target = urllib.parse.unquote(target)
    except Exception:
        pass
    return target


# ---------------------------------------------------------------------------------------
# Step 4: resolve a target against disk — file only, case-sensitive path components.
# ---------------------------------------------------------------------------------------

def case_sensitive_isfile(path):
    """True only if `path` names an existing FILE (not a directory) whose every path
    component matches the on-disk directory listing byte-for-byte. This is deliberately
    stricter than os.path.isfile/exists on a case-insensitive filesystem (Windows), so a
    case-differing target fails here instead of 404ing on the case-sensitive Linux host
    this documentation is actually served from."""
    abs_path = os.path.abspath(path)
    drive, rest = os.path.splitdrive(abs_path)
    parts = [p for p in rest.split(os.sep) if p]
    current = (drive + os.sep) if drive else os.sep
    for part in parts:
        try:
            entries = os.listdir(current)
        except OSError:
            return False
        if part not in entries:
            return False
        current = os.path.join(current, part)
    return os.path.isfile(current)


# ---------------------------------------------------------------------------------------
# Main scan.
# ---------------------------------------------------------------------------------------

def find_dead_links(text, base_dir):
    """Yield (line_no, whole_match, target, resolved_path) for every link in `text`
    (inline, reference-style, or raw HTML `<a href>`) whose destination does not resolve
    to an existing file, relative to base_dir. `text` is the ORIGINAL (unmasked) file
    content; masking happens internally so callers don't have to remember to do it."""
    masked = mask_non_prose(text)
    ref_defs = collect_reference_defs(masked)
    masked_lines = masked.splitlines()

    def resolve_and_maybe_yield(line_no, whole, raw_target):
        target = parse_destination(raw_target) if raw_target is not None else raw_target
        if not target:
            return None
        if target.startswith(EXTERNAL_PREFIXES):
            return None
        path_part = target.split("#", 1)[0]
        if not path_part:
            return None  # pure in-page anchor, e.g. [x](#section)
        resolved = os.path.normpath(os.path.join(base_dir, path_part))
        if not case_sensitive_isfile(resolved):
            return (line_no, whole, target, resolved)
        return None

    for i, line in enumerate(masked_lines, start=1):
        for whole, dest_raw, ref_label in iter_link_candidates(line):
            if dest_raw is not None:
                hit = resolve_and_maybe_yield(i, whole, dest_raw)
                if hit:
                    yield hit
                continue
            # reference-style (full/collapsed) or shortcut candidate — only real if the
            # label is actually defined somewhere in this file.
            label = ref_label.strip().lower()
            if label in ref_defs:
                hit = resolve_and_maybe_yield(i, whole, ref_defs[label])
                if hit:
                    yield hit
            # an undefined label (including shortcut candidates like a stray
            # `[CORRECTED ...]` marker) renders as plain text, not a link — skip it.

        for m in _HTML_A_RE.finditer(line):
            hit = resolve_and_maybe_yield(i, m.group(0), m.group(2))
            if hit:
                yield hit


def _count_link_candidates(masked_text, ref_defs):
    total = 0
    for line in masked_text.splitlines():
        for whole, dest_raw, ref_label in iter_link_candidates(line):
            if dest_raw is not None:
                t = parse_destination(dest_raw)
                if t and not t.startswith(EXTERNAL_PREFIXES):
                    total += 1
            else:
                label = ref_label.strip().lower()
                if label in ref_defs:
                    t = parse_destination(ref_defs[label])
                    if t and not t.startswith(EXTERNAL_PREFIXES):
                        total += 1
        for m in _HTML_A_RE.finditer(line):
            t = parse_destination(m.group(2))
            if t and not t.startswith(EXTERNAL_PREFIXES):
                total += 1
    return total


def main(argv):
    verbose = "--verbose" in argv

    files = sorted(glob.glob(FEATURES_GLOB, recursive=True))
    if not files:
        print("· no files matched {!r} — unavailable".format(FEATURES_GLOB))
        return 2

    total_dead = 0
    total_links_checked = 0
    per_file_dead = {}

    for f in files:
        try:
            with open(f, encoding="utf-8", newline="") as fh:
                content = fh.read()
        except OSError as e:
            print("· cannot read {} ({}) — unavailable".format(f, e))
            return 2

        base_dir = os.path.dirname(f)
        masked = mask_non_prose(content)
        ref_defs = collect_reference_defs(masked)
        total_links_checked += _count_link_candidates(masked, ref_defs)

        dead_here = list(find_dead_links(content, base_dir))
        if dead_here:
            per_file_dead[f] = dead_here
            total_dead += len(dead_here)
            if verbose:
                for line_no, whole, target, resolved in dead_here:
                    reason = "is a directory, not a file" if os.path.isdir(resolved) else "does not exist"
                    print("DEAD {}:{} -> {!r} (resolves to {}, which {})".format(
                        f, line_no, target, resolved, reason))

    print("- scanned {} feature doc(s) under {}".format(len(files), FEATURES_GLOB))
    print("- {} relative (non-http) link(s) checked".format(total_links_checked))
    print("- {} dead relative link(s) in {} file(s)".format(total_dead, len(per_file_dead)))

    return 1 if total_dead else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
