#!/usr/bin/env python3
"""
Generate nisha-compliance-r7.tsv from NISHA-COMPLIANCE-ROWS-0918.md, byte-for-byte.

Round 7 / K-2c.2 (R7-A, F-0778, HIGH, priya): HIDE_DISCLOSURE fired on ASCI-compliance
instructions -- a brand telling the creator to KEEP, add or place the disclosure label, e.g.
"Please do not post without the paid partnership label." Nisha's 14 rows (NC-01..NC-14) are all
NO_FLAG and were written blind: without opening HideDisclosureRule.java, RiskText.java,
RULINGS-U-0917.md, any KABIR-*/PRIYA-* file, or any *Probe* file.

Same fix as gen_nisha_blind_tsv.py, for the same reason: no retyping, no manual escaping, no
normalisation, so a byte-level corpus-integrity defect (like the candra-O / nukta retype bugs
gen_nisha_blind_tsv.py exists to prevent) cannot creep in here either. This script parses her
markdown table directly and copies each `text` cell's bytes into the TSV unchanged. Run it
whenever NISHA-COMPLIANCE-ROWS-0918.md's first table changes:

    python influora-api/src/test/resources/risk-corpus/gen_nisha_compliance_tsv.py

It is idempotent: re-running it regenerates the same TSV from the same markdown.

Parsing approach: the document has ONE flat table (no "## Flag N" / "### SHOULD FLAG" headings --
every row is HIDE_DISCLOSURE, every row is NO_FLAG). A table row is
`| id | lang | "text" | expected | why |` -- split on `|`, strip whitespace, and strip exactly one
pair of surrounding double quotes from the text cell (the corpus author's own convention). Header
and separator rows (`|---|---|...`) are skipped by requiring the id column to match the pattern
"NC-" followed by digits. The
second table ("Short-form check") is a different shape entirely (word / terse form / send it? /
why) and is deliberately NOT parsed here -- it prunes an existing alternative and row rather than
adding corpus rows, and is applied by hand in RiskFlagCorpusTest.java.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MD_PATH = HERE.parent.parent.parent.parent.parent / ".proof-os" / "tasks" / "T-MEERA-CREATOR-PHASE-B" / "NISHA-COMPLIANCE-ROWS-0918.md"
OUT_PATH = HERE / "nisha-compliance-r7.tsv"

ROW_ID = re.compile(r"^NC-\d+$")
RULE = "HIDE_DISCLOSURE"
SOURCE = "nisha_blind_r7"


def parse(md_text: str) -> list[tuple[str, str, str, str, bool]]:
    """Returns (id, rule, language, text, expect_flag) tuples, in file order."""
    rows: list[tuple[str, str, str, str, bool]] = []
    for raw_line in md_text.splitlines():
        line = raw_line.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 4:
            continue
        row_id = cells[0]
        if not ROW_ID.match(row_id):
            continue  # header row, separator row, the short-form table, or something else
        language = cells[1]
        text = cells[2]
        if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
            text = text[1:-1]
        expected = cells[3]
        if expected not in ("FLAG", "NO_FLAG"):
            raise ValueError(f"row {row_id}: unrecognised expected column {expected!r}")
        rows.append((row_id, RULE, language, text, expected == "FLAG"))
    return rows


def main() -> int:
    if not MD_PATH.is_file():
        print(f"ERROR: source markdown not found at {MD_PATH}", file=sys.stderr)
        return 1
    md_text = MD_PATH.read_text(encoding="utf-8")
    rows = parse(md_text)
    if len(rows) != 14:
        print(f"ERROR: expected 14 rows, parsed {len(rows)} -- markdown format may have changed", file=sys.stderr)
        return 1
    if any(expect_flag for (_id, _rule, _lang, _text, expect_flag) in rows):
        print("ERROR: NISHA-COMPLIANCE-ROWS-0918.md's first table is documented as all-NO_FLAG; a FLAG row was parsed", file=sys.stderr)
        return 1
    with OUT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        for row_id, rule, language, text, expect_flag in rows:
            f.write(f"{row_id}\t{rule}\t{language}\t{text}\t{'FLAG' if expect_flag else 'NO_FLAG'}\t{SOURCE}\n")
    print(f"wrote {len(rows)} rows to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
