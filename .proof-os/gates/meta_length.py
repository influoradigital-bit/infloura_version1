#!/usr/bin/env python3
"""gates/meta_length.py — origin: aditya checklist "meta description under 160 chars".

The 0.3.3 gate was decorative. Its description pattern required `description` to be
followed immediately by `\\s*[:=]`, so in real HTML — where the next character is a
quote — it matched nothing:

    <meta name="description" content="<200 chars>">   -> exit 0
    {"description": "<200 chars>"}                    -> exit 0

and the title pattern required a separator after the literal `<title>` AND a literal
`<` after a non-greedy capture, so `<title>...</title>` never matched at all: the
60-character limit was unreachable code. Zero arguments and binary input also exited 0.

It now parses the two shapes SEO actually ships in — HTML `<meta>` / `<title>` elements
(any attribute order, tags may span lines) and `key: value` in JSON / YAML frontmatter —
measures the unquoted value, and refuses to be green when it measured nothing.

LAW (false-red): no arguments / unreadable / binary / no meta fields found => exit 2.
                 exit 1 = a field that exists is over its limit.
Usage: gates/meta_length.py <file...> [--desc N] [--title N]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("meta_length")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import sys, re, os

USAGE = "usage: gates/meta_length.py <file...> [--desc N] [--title N]"

BLIND = [
    "whether the description is any GOOD — only its length is measured",
    "HTML entities and unicode escapes are counted as written, not as rendered "
    "(&amp; counts 5, a search engine counts 1)",
    "values assembled at runtime (template interpolation, CMS fields) — only literals "
    "present in the file are visible",
    "duplicate descriptions across pages, and missing descriptions (this gate measures "
    "what is there; it cannot know which pages should have had one)",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ---------------------------------------------------------------- arguments
LIMIT_DESC, LIMIT_TITLE = 160, 60
paths = []
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a in ("--desc", "--title"):
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, f"· {a} needs a number\n{USAGE}", ["everything: the gate never ran"])
        try:
            n = int(argv[i + 1])
        except ValueError:
            die(64, f"· {a} {argv[i+1]!r} is not an integer\n{USAGE}",
                ["everything: the gate never ran"])
        if a == "--desc":
            LIMIT_DESC = n
        else:
            LIMIT_TITLE = n
        i += 2
        continue
    if a.startswith("--"):
        die(64, f"· unknown option {a}\n{USAGE}", ["everything: the gate never ran"])
    paths.append(a)
    i += 1

if not paths:
    die(2, f"· no files given — zero inputs is nothing checked, not a pass (unavailable)\n"
           f"{USAGE}", ["every meta field on every page: no file was named"])

# ---------------------------------------------------------------- patterns
META_TAG = re.compile(r'<meta\b[^<>]*/?>', re.I | re.S)
ATTR = re.compile(r'([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|\'([^\']*)\'|([^\s"\'>]+))',
                  re.S)
TITLE_EL = re.compile(r'<title\b[^<>]*>(.*?)</\s*title\s*>', re.I | re.S)
# key: value / key = value in JSON, YAML frontmatter, JS objects, .env, TOML.
KV = re.compile(
    r'(?mi)^[\s\-*#]*["\']?(meta[_ -]?description|og:description|twitter:description|'
    r'description|meta[_ -]?title|og:title|twitter:title|title|seo_?title)["\']?'
    r'\s*[:=]\s*(.*?)[\s,;]*$')

DESC_KEYS = {"description", "og:description", "twitter:description",
             "meta description", "meta_description", "meta-description"}
TITLE_KEYS = {"title", "og:title", "twitter:title", "meta title", "meta_title",
              "meta-title", "seo_title", "seotitle"}


def unquote(v):
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1]
    return v


def normkey(k):
    return re.sub(r"[_\-]", " ", k.strip().lower()).replace("meta ", "meta ")


def lineno(text, off):
    return text.count("\n", 0, off) + 1


# ---------------------------------------------------------------- checking
fails, unreadable, skipped_binary = [], [], []
found_desc = found_title = 0
per_file = []

for path in paths:
    if os.path.isdir(path):
        unreadable.append(f"{path} (is a directory)")
        continue
    try:
        raw = open(path, "rb").read()
    except OSError as e:
        unreadable.append(f"{path} ({e.strerror or e})")
        continue
    if b"\x00" in raw:
        skipped_binary.append(path)
        continue
    text = raw.decode("utf-8", errors="replace")

    hits = []           # (kind, lineno, value)

    for m in META_TAG.finditer(text):
        attrs = {}
        for am in ATTR.finditer(m.group(0)):
            val = am.group(3) if am.group(3) is not None else \
                  am.group(4) if am.group(4) is not None else am.group(5)
            attrs[am.group(1).lower()] = val or ""
        key = normkey(attrs.get("name") or attrs.get("property") or
                      attrs.get("itemprop") or "")
        content = attrs.get("content")
        if content is None or not key:
            continue
        if key in DESC_KEYS:
            hits.append(("description", lineno(text, m.start()), content))
        elif key in TITLE_KEYS:
            hits.append(("title", lineno(text, m.start()), content))

    for m in TITLE_EL.finditer(text):
        hits.append(("title", lineno(text, m.start()),
                     re.sub(r"\s+", " ", m.group(1)).strip()))

    for m in KV.finditer(text):
        key, val = normkey(m.group(1)), unquote(m.group(2))
        if not val or val in (">", "|", ">-", "|-", "{", "["):
            continue
        if key in DESC_KEYS:
            hits.append(("description", lineno(text, m.start()), val))
        elif key in TITLE_KEYS:
            hits.append(("title", lineno(text, m.start()), val))

    for kind, ln, val in hits:
        limit = LIMIT_DESC if kind == "description" else LIMIT_TITLE
        if kind == "description":
            found_desc += 1
        else:
            found_title += 1
        if len(val) > limit:
            fails.append(f"{path}:{ln} {kind} {len(val)}>{limit} chars — "
                         f"truncated in the SERP: “{val[:limit]}…”")
    per_file.append((path, len(hits)))

# ---------------------------------------------------------------- verdict
extra = []
if unreadable:
    extra.append("file(s) that could not be read: " + ", ".join(unreadable))
if skipped_binary:
    extra.append("binary file(s) skipped, no meta field can be located in them: " +
                 ", ".join(skipped_binary))
empty = [p for p, n in per_file if n == 0]
if empty:
    extra.append(f"{len(empty)} file(s) contain no meta/title field at all, so nothing "
                 f"in them was measured: " + ", ".join(empty[:5]) +
                 (" ..." if len(empty) > 5 else ""))

total = found_desc + found_title
if total == 0:
    print(f"meta fields found: 0 across {len(paths)} argument(s)")
    die(2, "· nothing to measure — a length check that measured no length is not a pass "
           "(unavailable)", extra)

print(f"meta fields measured: {total} ({found_desc} description ≤{LIMIT_DESC}, "
      f"{found_title} title ≤{LIMIT_TITLE}) · failed: {len(fails)}")
for x in fails:
    print("  ", x)
emit(extra)
sys.exit(1 if fails else 0)
