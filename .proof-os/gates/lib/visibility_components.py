"""Print the record component names of PortfolioVisibility, one per line.

Writes LF-only BYTES, never print(). On Windows, print() emits CRLF, every name
the gate reads then carries a trailing \\r, and the greps built from those names
match nothing -- which the gate reports as eight flags with no server-side
reader. A false red on a correct fix is as corrosive as a false green.
"""
import re
import sys

src = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r"record\s+PortfolioVisibility\s*\((.*?)\)\s*\{", src, re.S)
if not match:
    sys.stdout.buffer.write(b"NO_RECORD")
    raise SystemExit(0)

body = re.sub(r"/\*.*?\*/", " ", match.group(1), flags=re.S)
body = re.sub(r"//[^\n]*", " ", body)

names = []
for part in body.split(","):
    tokens = part.replace("\n", " ").split()
    if len(tokens) >= 2:
        names.append(tokens[-1])

sys.stdout.buffer.write("\n".join(names).encode("utf-8"))
