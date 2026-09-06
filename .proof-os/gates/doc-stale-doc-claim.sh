#!/usr/bin/env bash
# gates/doc-stale-doc-claim.sh — CLASS gate for doc-stale-doc-claim.
#
# Closes F-0516, F-0526, F-0527, F-0529, F-0531, F-0532, F-0533 - the seven whose false claim I
# verified against code AND corrected in docs/docs/features. The other eleven rows in this class
# stay OPEN: their claim was never traced to a doc, so closing them here would be inference.
#
# The class recurred 18 times with no gate, which had it BLOCKED in promote.py --recurrence.
# Every row reads the same way: a doc asserts a surface is missing / mock / unimplemented, and the
# code says otherwise. "The doc, not the code, is wrong."
#
# WHAT THIS GATE DOES: reads the backend's real routes and type names, then looks for NEGATIVE
# STATUS CLAIMS in current-truth docs that sit next to a route or type which actually ships.
#
# THREE DESIGN DECISIONS, each made because a looser version was measurably wrong:
#
#  1. ADJACENCY, NOT CO-OCCURRENCE. The first pass flagged any line holding a negative phrase and a
#     route anywhere on it. That matched "5 events have no listener ... /notifications/read-all" —
#     two unrelated clauses in one bullet. Precision went 50% -> 100% on routes by requiring the
#     claim within 45 characters of the thing it supposedly describes.
#
#  2. CURRENT-TRUTH DOCS ONLY. Audit reports, dated reviews, error write-ups and the QA answer
#     files describe past states ON PURPOSE. A gate that fails on an accurate historical record
#     teaches the team to ignore it, so those trees are excluded by path.
#
#  3. AN AUDITABLE EXEMPTION. Regex cannot always tell what a negative attaches to: "wiring in
#     `RedemptionService` that does not exist" names a type that ships while denying something
#     inside it — a TRUE statement. Instead of loosening the pattern until it catches nothing, such
#     a line carries an in-doc `doc-claim-ok:` marker with a reason. Exemptions are greppable and
#     reviewable; a widened regex would have hidden the same thing invisibly.
#
# HOW IT WAS PROVED. Four probes: a planted route claim fails, a planted type claim fails, a claim
# about a type that genuinely does NOT exist does not fire (real gaps stay reportable), and the
# exemption marker suppresses. The type path is why probe two matters — it was silently DEAD on
# first write: a heredoc ate one level of escaping and turned `\b` into a literal 0x08 byte, so the
# regex could never match and the scanner reported a clean tree. It found five real stale claims
# the moment the byte was fixed.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_doc_stale_claim_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

# The scanner is the gate. It exits 2 itself if it cannot see routes, types or docs, so a broken
# instrument can never read as a pass.
python "$SCAN" --verbose
rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

# A regex that cannot match is worse than no gate: it reports a clean tree forever. This is a
# permanent self-test against exactly the corruption that silently disabled the type path once.
if python - <<'PY'
import pathlib, re, sys
src = pathlib.Path(".proof-os/gates/_doc_stale_claim_scan.py").read_text(encoding="utf-8")
if "\x08" in src or "\x0c" in src:
    print("· the scanner source contains a control byte where an escape was intended")
    sys.exit(0)   # 0 == corruption found, for the shell test below
ns = {}
exec(compile(src.replace("sys.exit(main())", "pass"), "scan", "exec"), ns)
probe = "- The `MetricsPollingJob` is not implemented."
ok = bool(ns["JAVA_TYPE"].search(probe)) and bool(ns["NEGATIVE"].search(probe))
sys.exit(1 if ok else 0)
PY
then
  echo "VERDICT: broken — the scanner's own patterns no longer match a known-bad sample, so a green"
  echo "         result above proves nothing. This is the false-green mode this gate exists to"
  echo "         avoid, and it has happened once already."
  exit 1
fi
echo "· scanner self-test passes: its patterns still match a known-bad sample"

echo "VERDICT: aligned (proved) — no current-truth doc asserts that a route the backend serves, or"
echo "         a type it defines, is missing, mock or unimplemented. Claims that read as negative"
echo "         but are true are exempted in-doc with a stated, greppable reason."
echo "NOT CHECKED: claims about BEHAVIOUR rather than existence — 'X is slow', 'Y does not validate"
echo "             Z' — are invisible here; this gate only knows whether a named thing SHIPS."
echo "             Frontend-only claims (a page is a mock shell) are not covered: the scanner reads"
echo "             Java routes and types, not React components. Historical trees (reports,"
echo "             ai-review, errors, build, .proof-os/tasks) are excluded BY DESIGN and may hold"
echo "             any number of stale claims — that is what makes them historical, and it means"
echo "             this gate cannot speak to a finding whose source doc lived there."
exit 0
