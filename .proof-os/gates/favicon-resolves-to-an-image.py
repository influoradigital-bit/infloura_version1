#!/usr/bin/env python
"""Gate for F-0709 (spa-catchall-swallows-static-path).

The failure this exists to prevent: https://influora.in/favicon.ico answered HTTP 200 with
Content-Type text/html and the 97,483-byte SPA index.html. No favicon.ico existed anywhere in the
repository, so the SPA history fallback caught the request. Google asks for /favicon.ico before it
reads any <link rel="icon">, received something that was not an image, had no reason to fall back
because it was a 200 rather than a 404, and rendered the generic globe beside influora.in in
search results.

Why nothing caught it: every icon in the <link> list resolved correctly, so any check that only
walked those tags passed. /favicon.ico is the one path a browser and Google request WITHOUT being
told to, which is exactly why it is the one path no markup-derived check covers. This gate asserts
the unreferenced path explicitly, and then walks the referenced ones anyway.

Deliberately dependency-free: the ICO header is parsed with struct rather than Pillow, so CI does
not need an image library to run a check about images. Pillow generated these files; it is not
needed to verify them.

exit 0 = proved | 1 = broken | 2 = unavailable (never green)
"""

import os
import re
import struct
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PUBLIC = os.path.join(ROOT, "public")
INDEX = os.path.join(ROOT, "index.html")

# Google Search Central recommends a multiple of 48px square for the search favicon. The bug this
# gate closes shipped with 32x32 as the largest declared icon.
MIN_RECOMMENDED = 48


def unavailable(msg):
    print("UNAVAILABLE: " + msg)
    sys.exit(2)


def broken(msg, *detail):
    print("BROKEN: " + msg)
    for d in detail:
        print("        " + d)
    sys.exit(1)


def png_size(path):
    """(w, h) from the IHDR of a PNG, or None if it is not a PNG."""
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def ico_sizes(path):
    """Every (w, h) declared in an ICO directory. 0 in the header means 256."""
    with open(path, "rb") as f:
        blob = f.read()
    if len(blob) < 6:
        return []
    reserved, kind, count = struct.unpack("<HHH", blob[:6])
    if reserved != 0 or kind != 1 or count == 0:
        return []
    out = []
    for i in range(count):
        off = 6 + i * 16
        if off + 16 > len(blob):
            break
        w = blob[off] or 256
        h = blob[off + 1] or 256
        out.append((w, h))
    return out


if not os.path.isfile(INDEX):
    unavailable("no index.html at %s" % INDEX)
if not os.path.isdir(PUBLIC):
    unavailable("no public/ directory at %s" % PUBLIC)

# --- 1. the unreferenced path Google asks for first -------------------------------------------
ico = os.path.join(PUBLIC, "favicon.ico")
if not os.path.isfile(ico):
    broken(
        "public/favicon.ico does not exist.",
        "Google requests /favicon.ico before reading any <link rel=icon>. With the file absent",
        "the SPA history fallback answers it 200 text/html, Google gets a non-image with no",
        "reason to fall back, and the search result shows a generic icon.",
    )

sizes = ico_sizes(ico)
if not sizes:
    broken(
        "public/favicon.ico is not a parseable ICO.",
        "A file that exists but is not an image reproduces the original defect exactly: a 200",
        "that is not a favicon.",
    )
if not any(w >= MIN_RECOMMENDED for w, _ in sizes):
    broken(
        "public/favicon.ico has no entry at %dpx or larger (found %s)."
        % (MIN_RECOMMENDED, sorted({w for w, _ in sizes})),
        "Google recommends a multiple of 48px square for the search favicon.",
    )

# --- 2. every icon the markup DOES reference actually resolves ---------------------------------
with open(INDEX, encoding="utf-8") as f:
    html = f.read()

hrefs = re.findall(r'<link[^>]*rel="[^"]*icon[^"]*"[^>]*>', html)
if not hrefs:
    broken("index.html declares no <link rel=icon> at all.")

missing = []
declared_png_max = 0
for tag in hrefs:
    m = re.search(r'href="([^"]+)"', tag)
    if not m:
        continue
    href = m.group(1)
    if href.startswith(("http://", "https://", "data:")):
        continue
    path = os.path.join(PUBLIC, href.lstrip("/"))
    if not os.path.isfile(path):
        missing.append(href)
        continue
    # Every icon href must RESOLVE, apple-touch-icon included — a dangling one is served as
    # HTML by the SPA fallback. But only rel="icon" counts toward the size floor: the whole
    # point of this check is that apple-icon.png was already 180px and Google still had
    # nothing above 32px, because apple-touch-icon is not in the list Google reads. A
    # substring match on "icon" catches "apple-touch-icon" too and would have made this gate
    # green against the exact defect it exists to catch.
    if re.search(r'rel="icon"', tag):
        dims = png_size(path)
        if dims:
            declared_png_max = max(declared_png_max, dims[0])

if missing:
    broken(
        "index.html points at %d icon file(s) that do not exist in public/." % len(missing),
        *["  %s" % h for h in missing]
        + [
            "A dangling icon href is served by the SPA fallback as HTML, which is the same",
            "failure as the missing favicon.ico — it just fails one <link> deeper.",
        ]
    )

if declared_png_max < MIN_RECOMMENDED:
    broken(
        "the largest PNG in the rel=icon list is %dpx; Google recommends %dpx or a multiple of it."
        % (declared_png_max, MIN_RECOMMENDED),
        "apple-touch-icon does not count — it is outside the rel=icon list Google reads.",
    )

print(
    "PROVED: public/favicon.ico exists and parses as an ICO carrying %s, every rel=icon href in"
    % sorted({w for w, _ in sizes})
)
print(
    "        index.html resolves to a real file, and the largest declared PNG is %dpx (>= %d)."
    % (declared_png_max, MIN_RECOMMENDED)
)
print(
    "        NOT proved: that the deployed host actually serves these — this reads the repo, not"
)
print("        the live origin. See the ops note in the F-0709 record.")
sys.exit(0)
