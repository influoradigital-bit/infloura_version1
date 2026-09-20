"""Emit src/main as `path:line`, with comments and string literals neutered.

F-0972's gate greps for a server-side reader of each PortfolioVisibility flag.
Two gates in this repo (2026-09-07) went red on the COMMENT that explained the
pattern they banned, so a gate must never see comment or string-literal text.
"""
import os
import re
import sys

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')

root = sys.argv[1]
out = []
for dirpath, _, names in os.walk(root):
    for name in names:
        if not name.endswith(".java"):
            continue
        path = os.path.join(dirpath, name).replace("\\", "/")
        src = open(path, encoding="utf-8", errors="replace").read()
        src = BLOCK_COMMENT.sub(" ", src)
        src = LINE_COMMENT.sub(" ", src)
        src = STRING_LITERAL.sub('""', src)
        for line in src.splitlines():
            if line.strip():
                out.append(path + ":" + line)
# Write bytes, not text: Windows defaults stdout to cp1252 and this tree carries
# non-Latin-1 source (Cyrillic in homoglyph-detection tests, en-dashes in
# javadoc). A UnicodeEncodeError here would surface as "gate unavailable".
sys.stdout.buffer.write("\n".join(out).encode("utf-8", errors="replace"))
