#!/usr/bin/env bash
# gates/F-0807-doc-links-resolve.sh — CLASS gate for F-0807 (dead doc links)
# and F-0810 (this gate reding on correct content).
#
# F-0810: the first cut of this scanner was line-regex with no markdown structure, and it
# turned RED on 8 of 8 valid shapes — a fenced `handlers[key](payload)` was reported as
# "DEAD -> 'payload'", and so were a titled link, an angle-bracket link and a commented-out
# one. Every feature doc already carries fenced code (wallet.md:16 has `[see escrow]`), so
# that gate would have gone red on correct documentation the first time anyone documented a
# dispatch map. A gate that reds on correct content is worse than no gate. The scanner now
# masks fenced/indented code, inline code spans and HTML comments BEFORE matching, and the
# self-test below re-proves BOTH directions on every run — a real dead link is flagged AND a
# correction marker that merely quotes one is not. That second half is what F-0810 is.
#
# F-0807: docs/docs/ contains ONLY features/. Every `[../known-limitations.md](...)` and
# `[../database.md](...)` link inside docs/docs/features/*.md pointed at a file that never
# existed. The sweep that closed the 34 measured instances of that is done; THIS gate is
# what stops a 35th one (or a dead link to any other made-up filename) from landing quietly.
#
# GENERAL BY DESIGN, NOT A TWO-FILENAME SPECIAL CASE: it does not know the names
# "known-limitations.md" or "database.md". It resolves every relative markdown link in
# docs/docs/features/*.md against disk and fails on any target that does not exist. The
# sweep also turned up (and fixed) dead links to ../api.md, ../security.md and ../ai.md —
# names nobody had listed — which is exactly the class of thing a name-specific gate would
# have missed and this one catches.
#
# THE TRAP THIS GATE MUST NOT FALL INTO: the fix for each dead link is a
# `[CORRECTED ..., F-0807: removed a "See [../database.md]" link ...]` marker that QUOTES
# the very link it removed. A gate that treats any `[...]` near a dead filename as a link
# would go red on its own corrections — this exact defect ("a grep gate matches its own
# comment explaining the banned pattern") has already shipped twice in this repo. The fix
# here is structural, not a denylist: a real markdown link is `[text](target)` with the
# `(target)` immediately after the `]`; the correction markers quote the target as
# `"See [../database.md]"` with a `"` after the `]`, never a `(`. No parens, no link, no
# matter what word came before the bracket. See _doc_links_resolve_scan.py's module
# docstring for the full reasoning, including the one case (an old F-0557 correction that
# had accidentally reproduced live `[x](x)` link syntax inside its own quote) where a
# link-shaped correction WAS a real dead link and correctly gets flagged.
#
# HOW IT WAS PROVED (see the self-test below, and the task's own P1/P2/P3 falsification):
#   P1: a genuinely dead relative link in a feature doc                    -> must go RED
#   P2: a [CORRECTED ...] marker that quotes a dead link with no parens,
#       exactly like the shipped fix                                      -> must stay GREEN
#   P3: the real tree, post-sweep, no mutations                           -> must be GREEN
# The self-test below runs the P1/P2 shapes against the scanner's own regex on every
# invocation, so a future edit that widens the pattern back into false-positive territory
# fails this gate immediately instead of waiting to be caught by hand again.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_doc_links_resolve_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

# Self-test FIRST: prove the scanner's link regex tells a real dead link apart from a
# correction marker that only quotes one, using the exact shapes P1/P2 falsify with. If
# this ever stops being true, a green result below proves nothing, so it must block first.
if ! python - <<'PY'
import sys
sys.path.insert(0, ".proof-os/gates")
import importlib.util
spec = importlib.util.spec_from_file_location("scan", ".proof-os/gates/_doc_links_resolve_scan.py")
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)

base = "docs/docs/features"

# P1 shape: a genuinely dead relative link (plain "See [x](x)." sentence).
p1 = 'Some claim here. See [../made-up-file.md](../made-up-file.md).'
p1_dead = list(scan.find_dead_links(p1, base))
p1_ok = len(p1_dead) == 1 and p1_dead[0][2] == "../made-up-file.md"

# P2 shape: the shipped correction convention — quotes the same dead target with NO
# parens immediately after the bracket, exactly like the six/22 fixed files.
p2 = ('Some claim here. [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a '
      '"See [../made-up-file.md]" link — docs/docs/ contains only features/, so that '
      'file never existed].')
p2_dead = list(scan.find_dead_links(p2, base))
p2_ok = len(p2_dead) == 0

if p1_ok and p2_ok:
    sys.exit(0)

print("· self-test failed: P1(dead-link-detected)={} P2(quote-not-flagged)={}".format(
    p1_ok, p2_ok))
sys.exit(1)
PY
then
  echo "VERDICT: broken — the scanner's own regex no longer distinguishes a real dead link"
  echo "         from a correction marker that only quotes one. This is the exact defect"
  echo "         ('a grep gate matches its own comment') that has shipped twice already; a"
  echo "         green result from the real scan below would prove nothing until this"
  echo "         self-test passes again."
  exit 1
fi
echo "· self-test passes: a real dead link is flagged, a quoted correction is not"

python "$SCAN" --verbose
rc=$?
[ "$rc" -eq 2 ] && exit 2

if [ "$rc" -ne 0 ]; then
  echo "VERDICT: broken — at least one relative markdown link in docs/docs/features/**/*.md"
  echo "         does not resolve to an existing file. See the DEAD lines above."
  echo "NOT CHECKED: anchors (#section) on a link whose file DOES resolve are not verified"
  echo "             — only the file path is. External http(s)/mailto links are not"
  echo "             fetched or checked. Links outside docs/docs/features/ (other doc"
  echo "             trees, wiki/, README files) are not scanned by this gate. A link whose"
  echo "             [text] portion is split across two source lines by a soft line break"
  echo "             is not detected — the scanner matches line by line; see the scan"
  echo "             module's docstring for why that join was judged not worth the risk."
  exit 1
fi

echo "VERDICT: aligned (proved) — every relative markdown link in docs/docs/features/**/*.md"
echo "         (inline, reference-style, and raw HTML <a href>) resolves to an existing"
echo "         file on disk, case-sensitively, under a recursive scan of the tree."
echo "NOT CHECKED: anchors (#section) on a link whose file DOES resolve are not verified —"
echo "             only the file path is, so a link to a real file's dead heading fragment"
echo "             would pass here. External http(s)/mailto links are not fetched or"
echo "             checked — a real link to a 404'd website is invisible to this gate."
echo "             Links outside docs/docs/features/ (docs/docs itself if it grows other"
echo "             files, wiki/, top-level README, .proof-os/tasks) are not scanned; this"
echo "             gate's class is scoped to the feature-doc tree F-0807 was found in. A"
echo "             link whose [text] portion is split across two source lines by a soft"
echo "             line break is not detected — the scanner matches per line (after code/"
echo "             comment stripping), never joining paragraph lines into one buffer, since"
echo "             a naive join risks gluing across list items, table rows, or blockquote"
echo "             boundaries that must stay separate. No such split link exists in this"
echo "             tree today; see _doc_links_resolve_scan.py's docstring for the reasoning."
exit 0
