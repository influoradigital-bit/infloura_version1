#!/usr/bin/env python3
"""F-0184-llms-txt-urls-resolve.py - gate for F-0184 (content-integrity).

RECORD. public/llms.txt is the file written specifically for AI systems to read and
quote. It advertised /tds, /kyc, /refund-policy, /guidelines/creators and
/guidelines/brands as real explainer/legal pages while none of them had a registered
route in src/App.tsx, so every one of them fell through to the `/:handle`
public-portfolio catch-all and rendered a "creator not found" screen under an HTTP 200.
An assistant following llms.txt sent a real person to a soft-404.

missed_by (verbatim from the ledger, and the specification for this gate):
  "a CI check that HEADs (or route-matches) every URL listed in llms.txt and
   sitemap.xml and fails the build on any that don't resolve to real content"

Two phrases in that sentence do the work, and this gate implements each separately:

  LEG A - "route-matches". Every path advertised in llms.txt must match a CONCRETE
  route in src/App.tsx. Matching only `/:handle` or `*` is NOT a resolution: those are
  the catch-alls that produced the original soft-404, and a check that accepted them
  would have greened F-0184 on the day it was filed.

  LEG B - "resolve to REAL CONTENT". A registered client-side route is not content for
  the consumer llms.txt is written for. Every AI crawler robots.txt welcomes by name
  (GPTBot, ClaudeBot, PerplexityBot, CCBot) fetches HTML without executing JavaScript -
  that is the stated reason scripts/prerender.mjs exists at all, in its own header. A
  path that is not prerendered has no physical dist/<route>/index.html, so
  public/_redirects serves it dist/app-shell.html: an empty #root and a script tag. A
  URL that llms.txt promises is a policy page and that answers a crawler with a
  content-free shell has not resolved to real content; it has resolved to nothing, in a
  way a browser-only spot-check cannot see. So the advertised path must also be in the
  prerender set - PRERENDER_ROUTES from scripts/marketing-routes.mjs, plus the blog
  slugs prerender.mjs discovers from src/content/blog/*.md.

ANTI-VACUITY. src/App.tsx carries a long comment (T-SEOCRO-0819) that names
/refund-policy, /disputes, /grievance, /kyc, /tds and /disclosure in prose, and
scripts/marketing-routes.mjs names /features/escrow in prose. A gate that grepped file
bytes would read those sentences as routes and pass with the routes deleted. This gate
reads CODE via gates/_code.py (F-0266), and then refuses to run at all - exit 2, never
1 - if the parse looks degenerate (too few routes, or an empty prerender set), because
a false red is as bad as a false green.

The dist/ legs (physical prerendered file, sitemap.xml) are REPORT-ONLY. dist/ is a
build output that may be stale or absent in a clean checkout; failing on it would make
this gate a function of whether someone ran `npm run build`, not of the tree.

  exit 0 = proved (every advertised URL route-matches AND resolves to real content)
  exit 1 = broken (F-0184 present)
  exit 2 = unavailable (a file or helper this gate reads could not be read)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SELF = Path(__file__).resolve().parent
sys.path.insert(0, str(SELF))
try:
    from _code import CodeUnavailable, code_of, harden_stdout
except Exception as e:  # noqa: BLE001
    print("gates/_code.py unreadable ({}) - unavailable".format(e))
    sys.exit(2)
harden_stdout()

ORIGIN = "https://influora.in"
LLMS = ROOT / "public" / "llms.txt"
APP = ROOT / "src" / "App.tsx"
ROUTES_MJS = ROOT / "scripts" / "marketing-routes.mjs"
BLOG_DIR = ROOT / "src" / "content" / "blog"
DIST = ROOT / "dist"

NOT_CHECKED_ALWAYS = [
    "no HTTP request is made - this is a static check of the tree, not of live influora.in",
    "prose that names a page without writing its path",
    "whether a prerendered page's rendered text actually matches what llms.txt claims about it",
]


def unavailable(msg):
    print("- {} - unavailable".format(msg))
    print("VERDICT: unavailable - this gate could not read what it must read; no claim made.")
    print("NOT CHECKED: everything.")
    sys.exit(2)


for _p in (LLMS, APP, ROUTES_MJS):
    if not _p.is_file():
        unavailable("{} missing".format(_p.relative_to(ROOT).as_posix()))
if not BLOG_DIR.is_dir():
    unavailable("src/content/blog missing")

# --------------------------------------------------------------------------
# 1. what llms.txt advertises
# --------------------------------------------------------------------------
try:
    llms = LLMS.read_text(encoding="utf-8")
except Exception as e:  # noqa: BLE001
    unavailable("public/llms.txt unreadable ({})".format(e))


def normalise(path):
    if path is None:
        return None
    path = path.rstrip(".,;:!?)]\"'")
    if not path.startswith("/"):
        return None
    if len(path) > 1:
        path = path.rstrip("/")
    return path or "/"


advertised = {}  # path -> set of 1-indexed llms.txt line numbers
for lineno, line in enumerate(llms.splitlines(), 1):
    # a. absolute URLs:  https://influora.in/features/hype
    for m in re.finditer(re.escape(ORIGIN) + r"(/[^\s)\]>,;\"'`]*)?", line):
        p = normalise(m.group(1) or "/")
        if p:
            advertised.setdefault(p, set()).add(lineno)
    # b. bare paths named in prose: ".. reachable from the site footer at /terms, /kyc .."
    #    The lookbehind stops "https://influora.in" from contributing "//influora".
    for m in re.finditer(r"(?<![\w/:.])(/[a-z0-9][a-z0-9/-]*)", line):
        p = normalise(m.group(1))
        if p and "influora" not in p:
            advertised.setdefault(p, set()).add(lineno)

if not advertised:
    unavailable("no URL or path could be extracted from public/llms.txt")

# --------------------------------------------------------------------------
# 2. the route table, read as CODE (F-0266), never as file bytes
# --------------------------------------------------------------------------
try:
    app_code = code_of(APP)
    mjs_code = code_of(ROUTES_MJS)
except CodeUnavailable as e:
    unavailable(str(e))
except Exception as e:  # noqa: BLE001
    unavailable("comment-stripping failed ({})".format(e))

route_paths = []
for m in re.finditer(r'path="([^"]+)"', app_code):
    raw = m.group(1)
    route_paths.append(normalise(raw) or raw)

CATCHALLS = {"*"}
CATCHALLS |= {p for p in route_paths if re.fullmatch(r"/:[A-Za-z0-9_]+", p)}
concrete = sorted({p for p in route_paths if p not in CATCHALLS})

# client-side redirects: path="/features/escrow" element={<Navigate to="/features/secure-payments"
redirects = {}
for m in re.finditer(r'path="([^"]+)"\s*element=\{\s*<Navigate\s+to="([^"]+)"', app_code):
    src = normalise(m.group(1))
    dst = normalise(m.group(2).split("?")[0])
    if src and dst:
        redirects[src] = dst

if len(concrete) < 20 or "/" not in concrete:
    unavailable(
        "src/App.tsx parsed to only {} concrete routes - the route table did not parse; "
        "refusing to report a failure off a bad parse".format(len(concrete))
    )

# --------------------------------------------------------------------------
# 3. the prerender set = the paths a JS-less fetch actually receives content for
# --------------------------------------------------------------------------
prerendered = set()
for m in re.finditer(r"path:\s*'([^']+)'", mjs_code):
    p = normalise(m.group(1))
    if p:
        prerendered.add(p)
blog_slugs = sorted(p.stem for p in BLOG_DIR.glob("*.md"))
prerendered |= set("/blog/{}".format(s) for s in blog_slugs)

if len(prerendered) < 5 or "/" not in prerendered or not blog_slugs:
    unavailable(
        "scripts/marketing-routes.mjs + src/content/blog parsed to {} prerendered routes and {} "
        "posts - the prerender set did not parse".format(len(prerendered), len(blog_slugs))
    )


def route_matches(path):
    """Return (matched_route, matched_only_via_catchall)."""
    segs = [s for s in path.split("/") if s]
    splat = None
    for r in concrete:
        rsegs = [s for s in r.split("/") if s]
        if rsegs and rsegs[-1] == "*":
            if segs[: len(rsegs) - 1] == rsegs[:-1]:
                splat = splat or r
            continue
        if len(rsegs) != len(segs):
            continue
        if all(rs.startswith(":") or rs == s for rs, s in zip(rsegs, segs)):
            return r, False
    if splat:
        return splat, False
    # Report the catch-all React Router would actually pick: a same-arity param route
    # (`/:handle`, the one F-0184's five URLs landed on) beats the `*` NotFound route.
    for c in sorted(CATCHALLS - {"*"}):
        if len([s for s in c.split("/") if s]) == len(segs):
            return c, True
    if "*" in CATCHALLS:
        return "*", True
    return None, True


# --------------------------------------------------------------------------
# LEG A - route-match
# --------------------------------------------------------------------------
print("== LEG A: every URL in llms.txt must match a concrete route in src/App.tsx ==")
leg_a = []
for path in sorted(advertised):
    target = redirects.get(path, path)
    r, only_catchall = route_matches(target)
    if r is None:
        leg_a.append((path, "no route at all -> hard 404"))
    elif only_catchall:
        leg_a.append((path, "only the catch-all {!r} -> soft-404 under HTTP 200".format(r)))
for path, why in leg_a:
    print(
        "  FAIL llms.txt:{} advertises {} - {}".format(
            ",".join(str(n) for n in sorted(advertised[path])), path, why
        )
    )
if not leg_a:
    print("  clean - all {} advertised paths hit a concrete route".format(len(advertised)))

# --------------------------------------------------------------------------
# LEG B - resolves to real content for the JS-less AI fetch llms.txt exists to serve
# --------------------------------------------------------------------------
print("== LEG B: every URL in llms.txt must be prerendered (a JS-less AI fetch gets content) ==")
leg_b = []
for path in sorted(advertised):
    target = redirects.get(path, path)
    if target not in prerendered:
        leg_b.append((path, target))
for path, target in leg_b:
    via = "" if target == path else " (redirects to {})".format(target)
    print(
        "  FAIL llms.txt:{} advertises {}{} - not in PRERENDER_ROUTES, so public/_redirects "
        "serves it dist/app-shell.html: empty #root, no content for a JS-less crawler".format(
            ",".join(str(n) for n in sorted(advertised[path])), path, via
        )
    )
if not leg_b:
    print("  clean - all {} advertised paths are prerendered".format(len(advertised)))

# --------------------------------------------------------------------------
# report-only: dist/ is a build output; it may be stale or absent. Never fails.
# --------------------------------------------------------------------------
notes = []
sitemap = DIST / "sitemap.xml"
if sitemap.is_file():
    xml = sitemap.read_text(encoding="utf-8", errors="replace")
    locs = set()
    for m in re.finditer(re.escape(ORIGIN) + r"([^<]*)</loc>", xml):
        p = normalise(m.group(1) or "/")
        if p:
            locs.add(p)
    bad = [p for p in sorted(locs) if route_matches(redirects.get(p, p))[1]]
    print(
        "== report-only: dist/sitemap.xml - {} <loc> entries, {} unresolvable ==".format(
            len(locs), len(bad)
        )
    )
    for p in bad:
        print("  NOTE sitemap advertises {} with no concrete route".format(p))
else:
    notes.append("dist/sitemap.xml is not built - the sitemap half of missed_by is unverified here")

if (DIST / "index.html").is_file():
    missing = []
    for p in sorted(advertised):
        t = redirects.get(p, p)
        if t == "/":
            continue
        if not (DIST / t.lstrip("/") / "index.html").is_file():
            missing.append(t)
    print(
        "== report-only: dist/ prerendered files - {} advertised paths have no physical "
        "index.html ==".format(len(missing))
    )
    for p in missing[:12]:
        print("  NOTE no dist{}/index.html".format(p))
else:
    notes.append("dist/ is not built - physical prerendered HTML was not inspected")

print("")
if leg_a or leg_b:
    print(
        "VERDICT: broken - {} URL(s) that public/llms.txt advertises to AI systems do not "
        "resolve to real content (F-0184)".format(len(leg_a) + len(leg_b))
    )
    print("NOT CHECKED: " + "; ".join(notes + NOT_CHECKED_ALWAYS))
    sys.exit(1)

print(
    "VERDICT: proved - all {} URLs in public/llms.txt route-match a concrete route and are "
    "prerendered".format(len(advertised))
)
print("NOT CHECKED: " + "; ".join(notes + NOT_CHECKED_ALWAYS))
sys.exit(0)
