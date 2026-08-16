#!/usr/bin/env python3
"""
dto-drift gate — proof-os class `dto-drift` (F-0214, F-0215, F-0216).

A TypeScript interface in src/lib/ claims to describe a Spring DTO. When the Java record's
field names change (or were never what the TS said), nothing fails: `tsc` only checks the TS
side against itself, so every read of the renamed field silently becomes `undefined`. The
symptom is never an error — it is a blank link, a permanently-false flag, "[object Object]",
or a full-page React #31 crash when the real value is an object.

Three production defects in one week came from exactly this:
  - metadata.deliverables was a DeliverableSlot[] typed as `number`  -> React #31, dead route
  - pdfDownloadUrl returned {downloadUrl} typed as {url}             -> download opened about:blank
  - PlatformStatResponse sends profileUrl/isVerified, typed url/verified
                                                                     -> "Self-reported" on every
                                                                        platform, dead links

This gate diffs the declared TS field names against the real Java record components for each
pair in PAIRS below. It is deliberately name-based: names are what Jackson serialises and what
the FE destructures, and a name mismatch is always a bug.

Add a pair whenever a TS interface starts mirroring a Java record. FE-only derived fields go in
`fe_only` with a reason, so the exemption is declared rather than silent.

Usage:  python ci/dto-drift-check.py [--json]
Exit:   0 no drift · 1 drift found · 2 a source file or record could not be read
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (TS interface, TS file, Java record, Java file, FE-only fields that legitimately have no
#  Java counterpart -> reason)
PAIRS = [
    {
        "ts": "PortfolioPlatformStats",
        "ts_file": "src/lib/api.ts",
        "java": "PlatformStatResponse",
        "java_file": "influora-api/src/main/java/com/influora/web/dto/creator/CreatorDtos.java",
        "fe_only": {
            "avgReach": "not on PlatformStatResponse; populated from portfolio metrics",
            # Declared, not silent: the backend genuinely never sends this. It is optional and
            # the "synced N ago" label degrades to hidden (creator-portfolio-public.tsx:746,
            # relativeTime handles null), so live portfolios simply never show a sync time.
            # Only the mock fixtures populate it. Remove this exemption if the backend adds it.
            "lastSyncedAt": "never sent by PlatformStatResponse; optional, label hides when absent",
        },
    },
    {
        "ts": "ContractApiRecord",
        "ts_file": "src/lib/api.ts",
        "java": "ContractResponse",
        "java_file": "influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java",
        "fe_only": {},
    },
    {
        "ts": "ContractGeneratePayload",
        "ts_file": "src/lib/api.ts",
        "java": "ContractGenerateRequest",
        "java_file": "influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java",
        "fe_only": {},
    },
]

TS_FIELD = re.compile(r"^\s*(?:readonly\s+)?([A-Za-z_$][\w$]*)\s*\??\s*:", re.M)


def ts_fields(path: Path, name: str) -> set[str] | None:
    """Field names of `interface <name> { ... }`, brace-matched so nested objects don't leak."""
    src = path.read_text(encoding="utf-8", errors="replace")
    m = re.search(rf"\binterface\s+{re.escape(name)}\b[^{{]*{{", src)
    if not m:
        return None
    i, depth, start = m.end(), 1, m.end()
    while i < len(src) and depth:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
        i += 1
    body = src[start : i - 1]
    # Strip comments and any nested object literals so only top-level keys survive.
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//[^\n]*", "", body)
    flat, depth = [], 0
    for ch in body:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        elif depth == 0:
            flat.append(ch)
    return set(TS_FIELD.findall("".join(flat)))


def java_fields(path: Path, name: str) -> set[str] | None:
    """Component names of `record <name>(...)`, paren-matched."""
    src = path.read_text(encoding="utf-8", errors="replace")
    m = re.search(rf"\brecord\s+{re.escape(name)}\s*\(", src)
    if not m:
        return None
    i, depth, start = m.end(), 1, m.end()
    while i < len(src) and depth:
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
        i += 1
    body = re.sub(r"@\w+(\([^)]*\))?", " ", src[start : i - 1])
    body = re.sub(r"<[^<>]*>", " ", body)  # drop generics so List<String> x -> x
    out = set()
    for part in body.split(","):
        toks = part.replace("\n", " ").split()
        if len(toks) >= 2:
            out.add(toks[-1].strip())
    return out


def main() -> int:
    as_json = "--json" in sys.argv
    findings, unreadable = [], []

    for pair in PAIRS:
        tsf, jf = ROOT / pair["ts_file"], ROOT / pair["java_file"]
        if not tsf.exists() or not jf.exists():
            unreadable.append(f"{pair['ts']}: missing {tsf if not tsf.exists() else jf}")
            continue
        ts, java = ts_fields(tsf, pair["ts"]), java_fields(jf, pair["java"])
        if ts is None or java is None:
            missing = pair["ts"] if ts is None else pair["java"]
            unreadable.append(f"could not locate {missing} (renamed or moved?)")
            continue
        for field in sorted(ts - java - set(pair["fe_only"])):
            findings.append(
                {
                    "ts": f"{pair['ts_file']}::{pair['ts']}.{field}",
                    "java": f"{pair['java_file']}::{pair['java']}",
                    "detail": f"`{field}` is declared in TS but is not a component of "
                    f"{pair['java']} — it will be undefined at runtime. "
                    f"Java has: {', '.join(sorted(java))}",
                }
            )

    if as_json:
        print(json.dumps({"findings": findings, "unreadable": unreadable}, indent=2))
    else:
        for u in unreadable:
            print(f"UNREADABLE  {u}")
        for f in findings:
            print(f"DRIFT  {f['ts']}\n       {f['detail']}")
        if not findings and not unreadable:
            print(f"dto-drift: OK — {len(PAIRS)} TS/Java pair(s) agree on field names")

    if unreadable:
        return 2
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
