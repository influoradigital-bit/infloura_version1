#!/usr/bin/env python3
"""gates/frontmatter.py — origin: F-0030 silent-frontmatter-drop.

ash/SKILL.md declared `description:` as an unquoted YAML scalar containing ": " —
"...a concrete improvement plan: better prompts...". YAML reads that as a nested
mapping and throws. Claude Code then loads the skill with EMPTY metadata: no name,
no description, no model. The skill appears installed, appears on the map, and can
never be model-invoked. Nothing failed loudly.

A service that is present and inert is the worst cell on the board — aligned-looking,
provably nothing. This gate parses every SKILL.md the way the loader does.

0.3.4 closes three gaps:
  * the F-0030 story is "no name, no description, no model", and only `description` was
    ever checked — a SKILL.md with a description and NO name passed;
  * several directory arguments where some were empty rendered green with no signal,
    because targets were pooled and only the pooled total was tested;
  * `text.split("---", 2)` mis-sliced any frontmatter containing a `---` line, and the
    scan descended exactly one level, so nested skill trees were invisible.

LAW (false-red): pyyaml missing / no skills found => exit 2 (unavailable).
                 exit 1 = a skill would load degraded.
Usage: gates/frontmatter.py <skills_dir> [more_dirs...] [--depth N]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("frontmatter")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import io, os, re, sys

USAGE = "usage: gates/frontmatter.py <skills_dir> [more_dirs...] [--depth N]"

BLIND = [
    "whether the description actually TRIGGERS the skill — only that the loader can "
    "read it",
    "the skill's body: everything after the frontmatter block is unread",
    "fields beyond name/description/model; an unknown key is not an error here",
    "skills installed outside the directories named on the command line",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ---------------------------------------------------------------- arguments
MAX_DEPTH = 4
dirs = []
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a == "--depth":
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, f"· --depth needs a number\n{USAGE}", ["everything: the gate never ran"])
        try:
            MAX_DEPTH = int(argv[i + 1])
        except ValueError:
            die(64, f"· --depth {argv[i+1]!r} is not an integer\n{USAGE}",
                ["everything: the gate never ran"])
        i += 2
        continue
    if a.startswith("--"):
        die(64, f"· unknown option {a}\n{USAGE}", ["everything: the gate never ran"])
    dirs.append(a)
    i += 1

if not dirs:
    dirs = ["skills"]

try:
    import yaml
except ImportError:
    die(2, "· pyyaml not installed — cannot parse frontmatter the way the loader does "
           "(unavailable)",
        ["every SKILL.md: the loader's own parser is not installed here"])

# ---------------------------------------------------------------- discovery
per_dir, targets, bad_dirs = {}, [], []
for d in dirs:
    if not os.path.isdir(d):
        bad_dirs.append(d)
        per_dir[d] = 0
        continue
    found = []
    base_depth = os.path.abspath(d).rstrip(os.sep).count(os.sep)
    for r, ds, fs in os.walk(d):
        if os.path.abspath(r).rstrip(os.sep).count(os.sep) - base_depth >= MAX_DEPTH:
            ds[:] = []
        ds[:] = [x for x in ds if x not in (".git", "node_modules", "__pycache__")]
        if "SKILL.md" in fs:
            p = os.path.join(r, "SKILL.md")
            label = os.path.basename(os.path.abspath(r)) or os.path.basename(os.path.abspath(d))
            found.append((label, p))
    per_dir[d] = len(found)
    targets.extend(sorted(found))

empty_args = [d for d, n in per_dir.items() if n == 0]

if not targets:
    print("SKILL.md files found per argument: " +
          ", ".join(f"{d}={n}" for d, n in per_dir.items()))
    die(2, f"· no SKILL.md found under {dirs} — nothing to check (unavailable)",
        [f"every skill under {', '.join(dirs)}: not one SKILL.md was located there"])

# ---------------------------------------------------------------- parsing
FM_OPEN = re.compile(r"^---\s*$")
FM_CLOSE = re.compile(r"^(---|\.\.\.)\s*$")


def frontmatter_block(text):
    """The loader's rule: a `---` line first, closed by the next `---` or `...` LINE.
    0.3.3 used text.split('---', 2), which cut at the first three hyphens anywhere —
    inside a description, inside a horizontal rule — and silently truncated the block."""
    lines = text.splitlines()
    if not lines or not FM_OPEN.match(lines[0]):
        return None, "no YAML frontmatter — all metadata is dropped"
    for j in range(1, len(lines)):
        if FM_CLOSE.match(lines[j]):
            return "\n".join(lines[1:j]), None
    return None, "frontmatter block never closes"


fails, warns = [], []
for name, path in targets:
    try:
        text = io.open(path, encoding="utf-8", errors="replace").read()
    except OSError as e:
        fails.append(f"{name}: SKILL.md unreadable ({e.strerror or e}) — the loader "
                     f"would see nothing either")
        continue
    block, why = frontmatter_block(text)
    if why:
        fails.append(f"{name}: {why}")
        continue
    try:
        meta = yaml.safe_load(block)
    except Exception as e:
        first = str(e).splitlines()[0]
        fails.append(f"{name}: frontmatter FAILS TO PARSE ({first}) "
                     f"— loads with empty metadata, silently uninvokable")
        continue
    if meta is None:
        fails.append(f"{name}: frontmatter block is empty — no name, no description, "
                     f"no model (this is the F-0030 end state)")
        continue
    if not isinstance(meta, dict):
        fails.append(f"{name}: frontmatter is {type(meta).__name__}, not a mapping")
        continue

    # F-0030 is "no name, no description, no model". Only description was ever checked.
    nm = meta.get("name")
    if nm is None or not str(nm).strip():
        fails.append(f"{name}: no name — the skill has no invocable identity; it loads "
                     f"and can never be called (F-0030)")
    elif str(nm).strip() != name:
        warns.append(f"{name}: frontmatter name is {str(nm).strip()!r} but the directory "
                     f"is {name!r} — one of the two is what users will type")

    desc = meta.get("description")
    if not desc or not str(desc).strip():
        fails.append(f"{name}: no description — the model has no trigger for this skill")
    elif len(str(desc)) > 1024:
        fails.append(f"{name}: description {len(str(desc))} chars, over the 1024 limit")

# ---------------------------------------------------------------- verdict
extra = []
if bad_dirs:
    extra.append("argument(s) that are not directories: " + ", ".join(bad_dirs))
if empty_args:
    extra.append("every skill under argument(s) that contributed ZERO SKILL.md files: " +
                 ", ".join(empty_args) + " — they were scanned and found nothing, which "
                 "is not the same as being clean")

print("SKILL.md files parsed per argument: " +
      ", ".join(f"{d}={n}" for d, n in per_dir.items()) +
      f" · total {len(targets)}, failed: {len(fails)}, warnings: {len(warns)}")
for x in fails:
    print("  ", x)
for w in warns:
    print("   warning:", w)
emit(extra)

if fails:
    sys.exit(1)
if empty_args:
    # A green verdict must not cover an argument the gate proved nothing about.
    print(f"· {len(empty_args)} argument(s) contributed no SKILL.md — this run cannot "
          f"speak for them (unavailable)")
    sys.exit(2)
sys.exit(0)
