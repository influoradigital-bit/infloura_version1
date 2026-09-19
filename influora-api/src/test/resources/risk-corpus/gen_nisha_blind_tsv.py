#!/usr/bin/env python3
"""
Generate nisha-blind-0917.tsv from NISHA-RISK-CORPUS-0917.md, byte-for-byte.

K-2b (Kavya, KAVYA-K2B-0917.md; Priya's follow-up): RiskFlagCorpusTest previously hand-embedded
Nisha's 56 rows as Java string literals. Retyping introduced 5 silent character-level defects --
a candra-O (U+0911) became plain O (U+0913) in HD-F-07, and a decomposed nukta (U+091C + U+093C)
became precomposed U+095B in OPP-F-07, OPP-F-08, OPP-F-13 and HD-N-07 -- exactly the class of
input her corpus exists to test, and exactly the class of bug RiskText.norm's NFC step is meant
to survive, not paper over by accident in the fixture itself.

This script is the fix: it parses her markdown table directly and copies each `text` cell's bytes
into the TSV unchanged. No retyping, no normalisation, no manual escaping. Run it whenever her
corpus file changes:

    python influora-api/src/test/resources/risk-corpus/gen_nisha_blind_tsv.py

It is idempotent: re-running it regenerates the same TSV from the same markdown.

Parsing approach: `## Flag N: RULE_CODE` headings name the rule for every row below them, until
the next `## Flag` heading. Within a rule's section, `### SHOULD FLAG` / `### SHOULD NOT FLAG`
headings set the expected label for the markdown table that follows. A table row is
`| id | language | "text" | FLAG-or-NO_FLAG | why | blind |` -- split on `|`, strip whitespace,
and strip exactly one pair of surrounding double quotes from the text cell (the corpus author's
own convention; the text itself never contains a literal `|`, checked by inspection). Header and
separator rows (`|---|---|...`) are skipped by requiring the id column to start with a known
prefix.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MD_PATH = HERE.parent.parent.parent.parent.parent / ".proof-os" / "tasks" / "T-MEERA-CREATOR-PHASE-B" / "NISHA-RISK-CORPUS-0917.md"
OUT_PATH = HERE / "nisha-blind-0917.tsv"

FLAG_HEADING = re.compile(r"^##\s*Flag\s*\d+:\s*(\S+)")
LABEL_HEADING = re.compile(r"^###\s*SHOULD\s*(NOT\s*)?FLAG")
ROW_ID = re.compile(r"^[A-Z]+-[A-Z]-\d+$")


def parse(md_text: str) -> list[tuple[str, str, str, str, bool]]:
    """Returns (id, rule, language, text, expect_flag) tuples, in file order."""
    rule = None
    expect_flag: bool | None = None
    rows: list[tuple[str, str, str, str, bool]] = []
    for raw_line in md_text.splitlines():
        line = raw_line.strip()
        m = FLAG_HEADING.match(line)
        if m:
            rule = m.group(1)
            continue
        m = LABEL_HEADING.match(line)
        if m:
            expect_flag = m.group(1) is None  # "SHOULD FLAG" -> True, "SHOULD NOT FLAG" -> False
            continue
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 6:
            continue
        row_id = cells[0]
        if not ROW_ID.match(row_id):
            continue  # header row, separator row, or something else entirely
        if rule is None or expect_flag is None:
            raise ValueError(f"row {row_id} appears before a Flag/SHOULD-FLAG heading was seen")
        language, text_cell = cells[1], cells[2]
        text = text_cell
        if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
            text = text[1:-1]
        rows.append((row_id, rule, language, text, expect_flag))
    return rows


def main() -> int:
    if not MD_PATH.is_file():
        print(f"ERROR: source markdown not found at {MD_PATH}", file=sys.stderr)
        return 1
    md_text = MD_PATH.read_text(encoding="utf-8")
    rows = parse(md_text)
    if len(rows) != 56:
        print(f"ERROR: expected 56 rows, parsed {len(rows)} -- markdown format may have changed", file=sys.stderr)
        return 1
    with OUT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        for row_id, rule, language, text, expect_flag in rows:
            f.write(f"{row_id}\t{rule}\t{language}\t{text}\t{'FLAG' if expect_flag else 'NO_FLAG'}\tnisha_blind\n")
    print(f"wrote {len(rows)} rows to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
