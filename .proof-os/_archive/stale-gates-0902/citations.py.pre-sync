#!/usr/bin/env python3
# origin: design decision "citations must be verifiable" · converts q-* trio opinion → proof
# Usage: python3 gates/citations.py <answers.md> <repo_root>
import re, sys, os
md, root = sys.argv[1], sys.argv[2]
pat = re.compile(r'`?([\w\-/\.]+\.(?:py|ts|tsx|js|md|json)):(\d+)`?')
fails, checks = [], 0
for i, ln in enumerate(open(md, encoding="utf-8", errors="replace"), 1):
    for f, n in pat.findall(ln):
        checks += 1
        p = os.path.join(root, f)
        if not os.path.isfile(p):
            fails.append(f"{md}:{i} cites {f}:{n} — FILE MISSING")
        elif int(n) > sum(1 for _ in open(p, errors="replace")):
            fails.append(f"{md}:{i} cites {f}:{n} — file has fewer lines")
print(f"citations checked: {checks}, failed: {len(fails)}")
for x in fails: print(" ", x)
sys.exit(1 if fails else 0)
