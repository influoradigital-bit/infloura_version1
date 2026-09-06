#!/usr/bin/env python3
"""Scanner leg for gates/F-0669-fabricated-contract-terms.sh.

Finds invented contract terms in LIVE component code. Comments are stripped BEFORE matching
rather than whole comment lines being skipped: a first cut of this check skipped only lines
STARTING with a comment marker, and then failed on

    revisionRate: number; // avg revisions per deliverable

a trailing comment on an unrelated admin type. A gate that cannot tell code from a comment
about code is the F-0266 shape, and it would also re-trip on the very comments the F-0669 fix
added to explain which literals were removed and why.

Exit 0 = clean. Exit 1 = at least one live-code hit, printed as path:line: source.
"""
import re
import sys
import pathlib

PAT = re.compile(
    r"'Influora Brand'"
    r"|6 months on social media"
    r"|revisions per deliverable"
    r"|No exclusivity agreement"
    r"|INR 2,500"
)
LINE_COMMENT = re.compile(r"//.*$")


def strip_comments(lines):
    """Yield (lineno, raw, code) with // and /* */ comment text removed from `code`."""
    in_block = False
    for n, raw in enumerate(lines, 1):
        line = raw
        if in_block:
            if "*/" in line:
                line, in_block = line.split("*/", 1)[1], False
            else:
                yield n, raw, ""
                continue
        while "/*" in line:
            before, rest = line.split("/*", 1)
            if "*/" in rest:
                line = before + rest.split("*/", 1)[1]
            else:
                line, in_block = before, True
                break
        yield n, raw, LINE_COMMENT.sub("", line)


def main():
    hits = []
    for f in sorted(pathlib.Path("src").rglob("*")):
        if f.suffix not in (".ts", ".tsx"):
            continue
        # Specs assert these strings are ABSENT — matching them there would be self-defeating.
        if "__tests__" in f.parts or ".test." in f.name:
            continue
        try:
            lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for n, raw, code in strip_comments(lines):
            if PAT.search(code):
                hits.append("%s:%d: %s" % (f.as_posix(), n, raw.strip()))
    for h in hits:
        print(h)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
