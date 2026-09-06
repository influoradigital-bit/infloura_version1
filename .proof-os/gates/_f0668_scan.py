"""F-0668 scanner - ApiError.fields must have at least one REAL consumer outside src/lib/api.ts.

Comments are stripped before matching. The F-0669 gate false-positived on a trailing `//` comment
because its exclusion only skipped lines STARTING with a comment marker; a mention of `.fields` in
prose is not a consumer.
"""
import re, sys, pathlib

ROOT = pathlib.Path(".")
API = (ROOT / "src/lib/api.ts").resolve()

BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE = re.compile(r"//[^\n]*")

def strip_comments(text):
    return LINE.sub("", BLOCK.sub("", text))

USE = re.compile(r"\.fields\s*(\?\.|\.|\[)")          # err.fields?.length / .map / [0]
CATCHES = re.compile(r"instanceof\s+ApiError")

consumers, apierror_sites = [], []
for p in ROOT.glob("src/**/*.ts*"):
    if not p.is_file():
        continue
    rp = p.as_posix()
    if p.resolve() == API:
        continue
    if "/__tests__/" in rp or ".test." in rp or ".spec." in rp:
        continue
    try:
        code = strip_comments(p.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        continue
    if CATCHES.search(code):
        apierror_sites.append(rp)
        if USE.search(code):
            consumers.append(rp)

if not apierror_sites:
    print("- found ZERO files handling ApiError at all, which contradicts the known client code")
    print("  - treating this as a broken parser, not a pass")
    sys.exit(2)

print(f"- {len(apierror_sites)} non-test file(s) handle ApiError; {len(consumers)} read `.fields`")
for c in sorted(consumers):
    print(f"    consumer: {c}")

if not consumers:
    print("VERDICT: broken - ApiError.fields has NO consumer outside src/lib/api.ts; every")
    print("         server-named field error surfaces as one generic message again (F-0668)")
    sys.exit(1)

# Advisory only. The remaining surface is the dead-plumbing CLASS, not this instance, and failing
# on it here would make the gate un-passable for reasons this finding never claimed.
remaining = sorted(set(apierror_sites) - set(consumers))
print(f"- ADVISORY, not a failure: {len(remaining)} other ApiError-handling file(s) do not read")
print("  `.fields`. Most call endpoints with no per-field validation, so this is an upper bound")
print("  on the remaining surface, not a defect count.")
sys.exit(0)
