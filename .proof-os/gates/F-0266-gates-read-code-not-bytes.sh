#!/usr/bin/env bash
# F-0266-gates-read-code-not-bytes.sh — gate for F-0266 (gate-cannot-tell-code-from-comment).
#
# The defect: a grep-based gate that forbids a string fails on the very fix that removed
# it, because the fix's own explanatory comment quotes the forbidden string. Reproduced
# at exit 1 against the pre-fix gates/F-0238-no-fabricated-contract-pdf.sh over a tree
# whose only occurrence of "Opened a local copy" was in a trailing `//`, a JSX `{/* */}`
# and a `/* */` comment — see .proof-os/tasks/T-BRANDOPEN-0817/F-0266.prefix.log. The
# mirror shape is worse and also in scope: a gate that REQUIRES a string greens when the
# string exists only in a comment, certifying a fix that was merely described.
#
# This gate asserts the PROPERTY, not the patch. It does not care how a gate strips
# comments; it cares that (1) the shared tokenizer exists and survives non-ASCII, (2) it
# actually distinguishes comment from code in both directions, (3) no shell gate under
# .proof-os/gates/ reads a source file raw — including through a loop variable, which is
# how a PARTIALLY converted gate hides while looking hardened.
#   exit 0 = proved · 1 = broken · 2 = unavailable
# Usage: gates/F-0266-gates-read-code-not-bytes.sh [project_dir]
set -u

NC="NOT CHECKED: whether a gate's PATTERNS are the right ones (this proves only that they
             are matched against code rather than bytes); template literals and regex
             literals containing '/', which gates/_strip_comments.py tokenizes loosely
             and documents as blind spots; dead code, which is still code to the
             stripper — 'the string is present' never meant 'the string is reachable';
             the .py gates, which do their own file reading and are not covered here;
             and the 37 gates that run vitest instead of grepping, which are a different
             trust question entirely."
say_nc() { printf '%s\n' "$NC"; }
broken() { echo "VERDICT: broken — $1 (F-0266)"; say_nc; exit 1; }
unavail() { echo "· $1 — unavailable"; say_nc; exit 2; }

ROOT="${1:-}"
if [ -z "$ROOT" ]; then
  ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || unavail "cannot resolve project root"
fi
[ -d "$ROOT" ] || unavail "not a directory: $ROOT"
GATES="$ROOT/.proof-os/gates"
[ -d "$GATES" ] || unavail "$GATES missing"

TMP=$(mktemp -d 2>/dev/null) || unavail "cannot create a temp dir"
cleanup() { rm -rf "$TMP" 2>/dev/null; }
trap cleanup EXIT

# ── leg 1 · the shared helper exists and loads ───────────────────────────────
HELPER="$GATES/_code.sh"
STRIP="$GATES/_strip_comments.py"
[ -f "$HELPER" ] || broken "gates/_code.sh is missing; there is no shared place where code is told from comment"
[ -f "$STRIP" ]  || broken "gates/_strip_comments.py is missing; _code.sh has no tokenizer to call"

SELF="$GATES"
# shellcheck source=/dev/null
. "$HELPER" 2>/dev/null || broken "gates/_code.sh could not be sourced"
type code_view >/dev/null 2>&1 || broken "gates/_code.sh defines no code_view"
type code_why  >/dev/null 2>&1 || broken "gates/_code.sh defines no code_why"
# A missing python is the gate being unable to run, NOT a defect in the subject.
code_ready || unavail "$(code_why)"
echo "· gates/_code.sh loads; code_view/code_why present"

# ── leg 2 · non-ASCII must survive the stripper ──────────────────────────────
# The regression this guards: _strip_comments.py used to emit via sys.stdout.write,
# which encodes cp1252 on this project's Windows hosts, so every source file containing
# ₹ (the money screens) or → (the admin audit panels) crashed it with UnicodeEncodeError
# and the calling gate reported UNAVAILABLE. Honest, but it silently exempted the money
# surfaces from comment-aware checking — the exact files where a false verdict costs
# most. If this leg ever goes red again, those screens have gone unchecked.
NONASCII="$TMP/nonascii.tsx"
{
  printf '%s\n' 'export const fee = "₹1,200";'
  printf '%s\n' 'export const flow = "escrow → payout";  // ₹ and → in a comment too'
  printf '%s\n' 'const emdash = "an — em dash";'
} > "$NONASCII"
if ! NAV=$(code_view "$NONASCII"); then
  broken "the comment stripper cannot handle non-ASCII source: $(code_why); the ₹/→ money surfaces would be silently exempted from every comment-aware gate"
fi
grep -q '₹1,200' "$NAV" || broken "the stripper lost the ₹ literal; non-ASCII code is not surviving tokenization"
grep -q 'escrow → payout' "$NAV" || broken "the stripper lost the → literal; non-ASCII code is not surviving tokenization"
echo "· non-ASCII (₹, →, —) survives the stripper"

# ── leg 3 · the property itself, both directions ─────────────────────────────
# Every form of comment that was reproduced as a false negative, plus the string as
# real code. A stripper that only removes whole-line // comments — the naive filter
# this record replaced — fails the trailing and JSX rows below.
FORBIDDEN="Opened a local copy"
CMT="$TMP/comment-only.tsx"
{
  printf '%s\n' '// F-0238: this branch used to toast "Opened a local copy" and hide the failure.'
  printf '%s\n' 'const legacyToastRemoved = true; // used to read: "Opened a local copy"'
  printf '%s\n' 'export function Note() {'
  printf '%s\n' '  return (<div>{/* Historically this said "Opened a local copy" */}<span>ok</span></div>);'
  printf '%s\n' '}'
  printf '%s\n' '/* block form: the retired copy was "Opened a local copy" */'
  printf '%s\n' 'export const live = "https://example.com/not-a-comment";'
} > "$CMT"
CODEF="$TMP/in-code.tsx"
{
  printf '%s\n' 'export function Regressed() {'
  printf '%s\n' '  toast.success("Opened a local copy");'
  printf '%s\n' '}'
} > "$CODEF"

CV=$(code_view "$CMT") || broken "code_view failed on the comment fixture: $(code_why)"
DV=$(code_view "$CODEF") || broken "code_view failed on the code fixture: $(code_why)"
if grep -q "$FORBIDDEN" "$CV"; then
  echo "· the forbidden string is still visible after stripping these comment forms:"
  grep -n "$FORBIDDEN" "$CV" | head -4
  broken "a comment quoting a forbidden string still reads as code; documenting a fix would still fail its own gate"
fi
grep -q "$FORBIDDEN" "$DV" || \
  broken "the same string written as real CODE was ALSO stripped — the helper has stopped detecting genuine regressions, which is the obvious wrong fix for this record"
grep -q 'https://example.com' "$CV" || \
  broken "a URL inside a string literal was truncated at its '//' — the tokenizer is treating string contents as comments"
echo "· comment forms (leading //, trailing //, JSX {/* */}, /* */) do not match; the same string in CODE does; a URL in a string survives"

# ── leg 4 · end to end through a real call site ──────────────────────────────
# Leg 3 proves the helper. This proves a gate that USES it, so the wiring is exercised
# and not just the library. F-0238 is the record's origin gate and is a pure grep gate.
ORIGIN="$GATES/F-0238-no-fabricated-contract-pdf.sh"
if [ -f "$ORIGIN" ]; then
  SUB=src/components/brand/deal-room/deal-contract-tab.tsx
  mkdir -p "$TMP/ok/$(dirname "$SUB")" "$TMP/bad/$(dirname "$SUB")"
  {
    printf '%s\n' 'const demoContractData = { brand: "demo" };'
    printf '%s\n' 'const legacyToastRemoved = true; // used to read: "Opened a local copy"'
    printf '%s\n' 'export function T(){ return (<div>{/* was "Opened a local copy" */}</div>); }'
  } > "$TMP/ok/$SUB"
  {
    printf '%s\n' 'const demoContractData = { brand: "demo" };'
    printf '%s\n' 'export function T(){ toast.success("Opened a local copy"); }'
  } > "$TMP/bad/$SUB"
  bash "$ORIGIN" "$TMP/ok" >/dev/null 2>&1; ok_rc=$?
  bash "$ORIGIN" "$TMP/bad" >/dev/null 2>&1; bad_rc=$?
  [ "$ok_rc" -eq 0 ] || broken "F-0238 still fails a correct tree whose only occurrence is in comments (exit $ok_rc) — the origin defect is back at a real call site"
  [ "$bad_rc" -eq 1 ] || broken "F-0238 no longer catches the string reintroduced in CODE (exit $bad_rc, expected 1) — a call site has been blinded"
  echo "· F-0238 end to end: comment-only tree exit 0, real reintroduction exit 1"
else
  echo "· F-0238 gate absent — end-to-end call-site check skipped"
  NC="$NC
             ALSO NOT CHECKED: any real call site — gates/F-0238-no-fabricated-contract-pdf.sh
             was not present, so only the helper itself was exercised."
fi

# ── leg 5 · population: no shell gate may read a source file raw ─────────────
# A gate is caught here if it binds a literal source path to a variable and then hands
# that RAW variable to grep/sed/awk. This is what catches a newly added raw-grep gate,
# which is the whole reason this leg is a population scan and not a fixed list.
#
# Note the closing quote in the pattern: "$F" must not match "$F_CODE", or every
# converted gate would look like an offender.
raw_offenders=""
loop_offenders=""
for g in "$GATES"/*.sh; do
  [ -f "$g" ] || continue
  b=$(basename "$g")
  case "$b" in _*) continue ;; esac                     # helpers are sourced, not run
  case "$b" in F-0266-gates-read-code-not-bytes.sh) continue ;; esac   # this file
  vars=$(grep -oE '^[A-Z][A-Z0-9_]*=("?)(src|influora-api|e2e|scripts|lib|styles|ci|docker)/[^ "]*\.(ts|tsx|js|jsx|mjs|cjs|java)' "$g" \
         | cut -d= -f1 | sort -u)
  [ -n "$vars" ] || continue
  for v in $vars; do
    if grep -nE '(grep|egrep|rg|sed|awk|nl|wc)' "$g" | grep -q "\"\$$v\""; then
      raw_offenders="${raw_offenders}    $b reads \$$v raw on a grep/sed/awk line
"
    fi
    # loop-variable form: for x in ... "$VAR" ... ; and a reader that uses "$x"
    lv=$(grep -oE '^[[:space:]]*for [a-z_][a-z0-9_]* in [^;]*"\$'"$v"'"' "$g" \
         | sed -E 's/^[[:space:]]*for ([a-z_][a-z0-9_]*) in.*/\1/' | sort -u)
    for x in $lv; do
      if grep -nE '(grep|egrep|rg|sed|awk|nl|wc)' "$g" | grep -q "\"\$$x\""; then
        loop_offenders="${loop_offenders}    $b loops \$$v into \$$x and greps \$$x raw
"
      fi
    done
  done
done
if [ -n "$raw_offenders" ] || [ -n "$loop_offenders" ]; then
  [ -n "$raw_offenders" ] && { echo "· gates grepping a source file's raw bytes:"; printf '%s' "$raw_offenders"; }
  [ -n "$loop_offenders" ] && { echo "· gates reading raw through a LOOP variable (these look converted and are not):"; printf '%s' "$loop_offenders"; }
  broken "at least one shell gate still greps source bytes instead of code; it will fail the fix that documents itself, or green a fix that exists only in a comment"
fi
converted=$(grep -l '_code\.sh' "$GATES"/*.sh 2>/dev/null | grep -vc '/_' || true)
echo "· population clean: no shell gate reads a subject file raw ($converted gates route through gates/_code.sh)"

echo "VERDICT: aligned (proved) — comments are told from code, in both directions, by one shared tokenizer, and no shell gate bypasses it"
say_nc
exit 0
