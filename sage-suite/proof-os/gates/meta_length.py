#!/usr/bin/env python3
# origin: aditya checklist "meta description under 160 chars" — was eyeballed, now len()
# Usage: python3 gates/meta_length.py <file...>   checks lines tagged meta/description
import sys, re
LIMIT_DESC, LIMIT_TITLE, fails = 160, 60, []
for path in sys.argv[1:]:
    for i, ln in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
        m = re.search(r'(?:meta[_ -]?description|description)\s*[:=]\s*["\']?(.+?)["\']?\s*$', ln, re.I)
        if m and len(m.group(1)) > LIMIT_DESC: fails.append(f"{path}:{i} description {len(m.group(1))}>{LIMIT_DESC}")
        t = re.search(r'(?:meta[_ -]?title|<title>)\s*[:=>]\s*["\']?(.+?)["\']?</?', ln, re.I)
        if t and len(t.group(1)) > LIMIT_TITLE: fails.append(f"{path}:{i} title {len(t.group(1))}>{LIMIT_TITLE}")
print(f"meta checks failed: {len(fails)}"); [print(" ", x) for x in fails]
sys.exit(1 if fails else 0)
