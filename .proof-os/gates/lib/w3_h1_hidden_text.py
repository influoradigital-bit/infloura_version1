#!/usr/bin/env python3
r"""W3 blocker check — the homepage <h1> must not ship as hidden text.

WHY THIS EXISTS
---------------
src/components/motion/WordReveal.tsx:15 defaults `as = 'h1'`, and
src/pages/landing.tsx:276 calls it with no `as` prop, so the homepage <h1>
IS WordReveal. Its animated path (:26-56) renders initial="hidden" +
whileInView, splitting the headline into one motion.span per word, each with
`hidden: { opacity: 0, y: 12 }`.

WordReveal is used on exactly two pages. Every other prerendered page ships a
plain <h1> -- /about ships "Real deals, without the chaos" as clean text. The
homepage has never prerendered successfully, so how a WordReveal <h1>
snapshots is UNKNOWN until the first good build. If it lands mid-animation the
<h1> ships opacity:0 on every word: hidden-text-shaped to Google, and invisible
to answer engines, which read raw HTML and never run an IntersectionObserver.

The W2 gate CANNOT catch this -- it asserts the <h1> is non-empty and is not
the ErrorBoundary string, and does not inspect inline styles. Verified against
.proof-os/gates/fixtures/w2/_priya-realistic-postW1/, which W2 passes.

WHY NOT sed
-----------
The reviewed procedure used `sed -n '/<h1/,/<\/h1>/p'`. Prerendered HTML puts
the body on one long line, so on /about that range captures 28,635 of 32,304
bytes -- 89% of the document. Grepping that for opacity:0 matches framer-motion
styles anywhere on the page and reports a block on a perfectly clean <h1>.
This parses the <h1> element specifically instead.

EXIT: 0 clean - 1 hidden-text violation - 2 could not run
"""
import io
import os
import re
import sys

HIDDEN_RE = re.compile(r"opacity\s*:\s*0(?![.\d%])", re.I)
COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
H1_RE = re.compile(r"<h1\b[^>]*>(.*?)</h1\s*>", re.DOTALL | re.I)
TAG_RE = re.compile(r"<[^>]+>")


def main(argv):
    path = argv[1] if len(argv) > 1 else os.path.join("dist", "index.html")
    if not os.path.isfile(path):
        print("CHECK UNAVAILABLE: %s does not exist - nothing was built. "
              "This must never read as a pass." % path)
        return 2
    try:
        html = io.open(path, encoding="utf-8", errors="replace").read()
    except OSError as exc:
        print("CHECK UNAVAILABLE: cannot read %s (%s)" % (path, exc))
        return 2

    html = COMMENT_RE.sub("", html)
    matches = H1_RE.findall(html)
    if not matches:
        print("FAIL %s: no <h1> element found at all" % path)
        return 1
    if len(matches) > 1:
        print("FAIL %s: %d <h1> elements found, expected exactly 1"
              % (path, len(matches)))
        return 1

    inner = matches[0]
    text = TAG_RE.sub("", inner).replace("\u00a0", " ")
    text = " ".join(text.split())

    problems = []
    hits = HIDDEN_RE.findall(inner)
    if hits:
        problems.append("<h1> carries %d opacity:0 declaration(s) - the "
                        "headline ships as hidden text" % len(hits))
    if not text:
        problems.append("<h1> has no text content once tags are stripped")

    if problems:
        print("FAIL %s:" % path)
        for p in problems:
            print("\t- %s" % p)
        print("\t  h1 inner (first 300 chars): %s" % inner[:300])
        print("\t  FIX: pass a non-whileInView variant, or drop WordReveal "
              "from the hero headline. Do NOT weaken this check.")
        return 1

    print("PASS %s - <h1> ships as visible text: %r" % (path, text))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
