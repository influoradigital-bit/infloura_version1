"""F-0380 scanner — there must be exactly ONE controller write path for deliverable metrics.

Counts @PostMapping / @PutMapping / @PatchMapping annotations whose path contains "metrics",
across influora-api's web/ package. GET mappings are excluded: reading metrics from several places
is fine, writing them from several places is the finding.

Comments are stripped first. Three checks of mine in this session fired on prose that DESCRIBED a
defect rather than the defect itself (the F-0447 probe, the F-0443 switcher javadoc, the F-0658
correction note), so a javadoc mentioning `PUT /deliverables/{id}/metrics` must not be counted as
a mapping.
"""
import re, sys, pathlib

WEB = pathlib.Path("influora-api/src/main/java/com/influora/web")
if not WEB.is_dir():
    print(f"- {WEB} missing - unavailable")
    sys.exit(2)

BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE = re.compile(r"//[^\n]*")
WRITE_MAPPING = re.compile(r'@(Post|Put|Patch)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')


def strip_comments(text: str) -> str:
    return LINE.sub("", BLOCK.sub("", text))


found = []
scanned = 0
for path in sorted(WEB.rglob("*.java")):
    try:
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        continue
    scanned += 1
    base_m = CLASS_MAPPING.search(code)
    base = base_m.group(1) if base_m else ""
    for verb, sub in WRITE_MAPPING.findall(code):
        full = (base.rstrip("/") + "/" + sub.lstrip("/")).rstrip("/")
        if "metrics" in full.lower():
            found.append((verb.upper(), full, path.as_posix()))

if scanned == 0:
    print("- scanned ZERO controller files, which cannot be right - broken instrument, not a pass")
    sys.exit(2)

print(f"- scanned {scanned} controller file(s)")
for verb, route, src in found:
    print(f"    {verb:6} {route}   ({src})")

if len(found) == 0:
    print("VERDICT: broken - there is now NO controller write path for deliverable metrics at all;")
    print("         a creator cannot report performance (F-0380)")
    sys.exit(1)

if len(found) > 1:
    print(f"VERDICT: broken - {len(found)} controller write paths for deliverable metrics. That is")
    print("         the F-0380 shape exactly: two independent writers for one domain entity, where")
    print("         whichever one lacks a UI caller writes through a path the app never reads back")
    print("         the same way.")
    sys.exit(1)

print("- exactly one controller write path for deliverable metrics, as required")
sys.exit(0)
