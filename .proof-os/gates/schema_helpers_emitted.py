#!/usr/bin/env python3
"""gates/schema_helpers_emitted.py — origin: F-0371 aeo-schema-built-but-unemitted.

src/lib/seo/schema.ts exports nine get*Schema helpers. Blog posts carry FAQ sections and
getFaqPageSchema is written, tested by nothing, and referenced by no page — so every post
publishes without the structured data that would make its FAQ eligible for answer-engine
extraction. A helper that is present and never emitted is indistinguishable, from the
outside, from one that was never written.

This gate asserts every exported get*Schema helper is referenced by at least one file under
src/ that is not schema.ts itself and not a test.

LAW (false-red): missing schema.ts or missing src/ => exit 2 (unavailable), never 0.
                 exit 1 = at least one helper is exported and never emitted.
Usage: schema_helpers_emitted.py [repo_root]
"""
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
schema_path = os.path.join(root, "src", "lib", "seo", "schema.ts")
src_dir = os.path.join(root, "src")

if not os.path.isfile(schema_path) or not os.path.isdir(src_dir):
    print(f"· {schema_path} or {src_dir} not found — cannot check (unavailable)")
    print("NOT CHECKED: everything — the subject does not exist at this path")
    sys.exit(2)

with open(schema_path, encoding="utf-8") as fh:
    schema_src = fh.read()

helpers = sorted(set(re.findall(r"export function (get[A-Za-z0-9]*Schema)\b", schema_src)))
if not helpers:
    print("· no exported get*Schema helpers found — nothing to check (unavailable)")
    sys.exit(2)

# Collect every referencing file except schema.ts itself and test files.
referenced = {h: [] for h in helpers}
for dirpath, dirnames, filenames in os.walk(src_dir):
    dirnames[:] = [d for d in dirnames if d != "node_modules"]
    for name in filenames:
        if not name.endswith((".ts", ".tsx")):
            continue
        if ".test." in name or ".spec." in name:
            continue
        path = os.path.join(dirpath, name)
        if os.path.abspath(path) == os.path.abspath(schema_path):
            continue
        try:
            with open(path, encoding="utf-8") as fh:
                body = fh.read()
        except OSError:
            continue
        for h in helpers:
            if re.search(r"\b%s\b" % re.escape(h), body):
                referenced[h].append(os.path.relpath(path, root))

unemitted = [h for h in helpers if not referenced[h]]

print(f"· {len(helpers)} exported schema helper(s) in src/lib/seo/schema.ts")
for h in helpers:
    where = referenced[h][0] if referenced[h] else "—"
    mark = "  " if referenced[h] else "!!"
    print(f"  {mark} {h:<28} {len(referenced[h])} site(s)  {where}")

if unemitted:
    print(f"VERDICT: broken — {len(unemitted)} helper(s) exported and never emitted: "
          + ", ".join(unemitted))
    print("NOT CHECKED: whether a helper that IS referenced is passed correct data, "
          "whether the emitted JSON-LD validates against schema.org, and whether the page "
          "that references it is reachable by a crawler — only that the symbol appears "
          "somewhere outside schema.ts and outside a test")
    sys.exit(1)

print("VERDICT: aligned (proved) — every exported schema helper is emitted by at least one page")
print("NOT CHECKED: whether the emitted JSON-LD is correct or validates against schema.org, "
      "and whether crawlers actually receive it after prerendering — only that each helper "
      "is referenced outside schema.ts and outside a test")
sys.exit(0)
