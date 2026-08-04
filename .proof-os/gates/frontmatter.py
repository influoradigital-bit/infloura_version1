#!/usr/bin/env python3
"""gates/frontmatter.py — origin: F-0030 silent-frontmatter-drop.

ash/SKILL.md declared `description:` as an unquoted YAML scalar containing ": " —
"...a concrete improvement plan: better prompts...". YAML reads that as a nested
mapping and throws. Claude Code then loads the skill with EMPTY metadata: no name,
no description, no model. The skill appears installed, appears on the map, and can
never be model-invoked. Nothing failed loudly.

A service that is present and inert is the worst cell on the board — aligned-looking,
provably nothing. This gate parses every SKILL.md the way the loader does.

LAW (false-red): pyyaml missing / no skills dir => exit 2 (unavailable).
                 exit 1 = a skill would load degraded.
Usage: gates/frontmatter.py <skills_dir> [more_dirs...]
"""
import io, os, sys

try:
    import yaml
except ImportError:
    print("· pyyaml not installed — cannot parse frontmatter the way the loader does (unavailable)")
    sys.exit(2)

dirs = sys.argv[1:] or ["skills"]
targets = []
for d in dirs:
    if not os.path.isdir(d):
        continue
    for name in sorted(os.listdir(d)):
        p = os.path.join(d, name, "SKILL.md")
        if os.path.isfile(p):
            targets.append((name, p))
        elif name == "SKILL.md":
            targets.append((os.path.basename(d), os.path.join(d, name)))

if not targets:
    print(f"· no SKILL.md found under {dirs} — nothing to check (unavailable)")
    sys.exit(2)

fails = []
for name, path in targets:
    text = io.open(path, encoding="utf-8", errors="replace").read()
    if not text.startswith("---"):
        fails.append(f"{name}: no YAML frontmatter — all metadata is dropped")
        continue
    parts = text.split("---", 2)
    if len(parts) < 3:
        fails.append(f"{name}: frontmatter block never closes")
        continue
    try:
        meta = yaml.safe_load(parts[1])
    except Exception as e:
        first = str(e).splitlines()[0]
        fails.append(f"{name}: frontmatter FAILS TO PARSE ({first}) "
                     f"— loads with empty metadata, silently uninvokable")
        continue
    if not isinstance(meta, dict):
        fails.append(f"{name}: frontmatter is {type(meta).__name__}, not a mapping")
        continue
    desc = meta.get("description")
    if not desc or not str(desc).strip():
        fails.append(f"{name}: no description — the model has no trigger for this skill")
    elif len(str(desc)) > 1024:
        fails.append(f"{name}: description {len(str(desc))} chars, over the 1024 limit")

print(f"SKILL.md files parsed: {len(targets)}, failed: {len(fails)}")
for x in fails:
    print("  ", x)
sys.exit(1 if fails else 0)
