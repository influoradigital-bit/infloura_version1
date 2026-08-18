# shellcheck shell=sh
#
# gates/_code.sh — sourced by shell gates. origin: F-0266
# (gate-cannot-tell-code-from-comment).
#
# THE DISEASE. A gate forbids a string and greps the raw file for it. The fix that
# removes the string writes a comment explaining WHY it was removed — and that comment
# quotes the string. The gate reads its own documentation as the defect and fails the
# very fix that closed it. Reproduced at exit 1 against the pre-fix
# gates/F-0238-no-fabricated-contract-pdf.sh; see
# .proof-os/tasks/T-BRANDOPEN-0817/F-0266.prefix.log.
#
# The pressure that creates is to stop documenting fixes, which is the opposite of what
# this trust layer is for. So the fix is not "write shorter comments" — it is to give
# every gate one shared, tested way to look at CODE rather than at file bytes.
#
# THE MIRROR DISEASE, which this same helper closes: a gate that REQUIRES a string
# ("the handler must call openDispute") greens when the string appears only in a
# comment. That one is worse — a false GREEN, a gate certifying a fix that was only
# ever described. Both are the same missing distinction.
#
# Usage inside a gate (SELF must be resolved BEFORE any cd, per F-0025/F-0026):
#
#     SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
#     cd "${1:-.}" || { echo "· not a directory — unavailable"; exit 2; }
#     . "$SELF/_code.sh" || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
#     code_ready || { echo "· $(code_why) — unavailable"; exit 2; }
#
#     V=$(code_view "$F") || { echo "· $(code_why) — unavailable"; exit 2; }
#     grep -q "Opened a local copy" "$V" && fail=1     # was: grep ... "$F"
#
# `code_view` returns a PATH, so it is a drop-in for "$F" in grep/sed/awk. Comments are
# blanked in place and newlines are preserved, so `grep -n "$V"` reports the SAME line
# numbers as the original file — a gate can still print a real location.
# `code_of` is the same thing as text, for gates that already pipe.
#
# WHY code_why() IS A FUNCTION AND NOT JUST $CODE_WHY. `V=$(code_view "$F")` runs the
# function in a SUBSHELL. Any variable it assigns — including the reason it failed —
# dies with that subshell, so the obvious `echo "$CODE_WHY"` in the caller printed an
# EMPTY reason on every failure path: an unavailable verdict with no cause, which is
# the "believed with a filename" shape this layer exists to refuse. The reason is
# therefore written to a FILE under the shared temp dir, which the parent can read.
# For that to work `code_ready` MUST be called from the gate's own shell (not inside
# `$( )`), because it is what creates that directory.
#
# LAW (false-red, F-0013/F-0015/F-0017). If the stripper cannot run, this helper
# reports UNAVAILABLE and NEVER silently falls back to grepping raw bytes. A silent
# fallback would restore the exact defect this file exists to remove, and would do it
# invisibly — the gate would look hardened and behave like the old one.
#
# NOT PROVIDED, deliberately: this is a tokenizer, not a parser. It tells a comment
# from a string literal from code. It does not know whether the code it leaves behind
# is reachable, exported, or dead. See gates/_strip_comments.py for the per-language
# blind spots (template literals, regex literals containing `/`).

_CODE_PY=""
_CODE_TMPDIR=""
_CODE_READY=""
CODE_WHY=""

# `command -v` only proves a name is ON PATH, not that it RUNS. On Windows a `python3`
# App Execution Alias resolves via `command -v` and then fails at execution time
# ("Python was not found; run without arguments to install from the Microsoft Store").
# Every candidate is probed with a real invocation before being trusted.
_code_find_py() {
  [ -n "$_CODE_PY" ] && return 0
  for _c in "${PROOF_PYTHON:-}" python3 python py; do
    [ -n "$_c" ] || continue
    command -v "$_c" >/dev/null 2>&1 || continue
    "$_c" -c "import sys" >/dev/null 2>&1 && { _CODE_PY="$_c"; return 0; }
  done
  return 1
}

# The stripper lives next to this file. Captured at SOURCE time: the sourcing gate has
# usually already cd'd into the project by then, so $0 is useless here.
_CODE_SELF="${_CODE_SELF:-}"
if [ -z "$_CODE_SELF" ]; then
  # ${BASH_SOURCE[0]} is a bashism; under dash we fall back to the caller's $SELF.
  # shellcheck disable=SC3054
  if [ -n "${BASH_SOURCE:-}" ]; then
    _CODE_SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)"
  fi
  [ -n "$_CODE_SELF" ] || _CODE_SELF="${SELF:-}"
fi
_CODE_STRIP="$_CODE_SELF/_strip_comments.py"

# _code_fail <reason> — record it where a PARENT shell can still read it (see above).
_code_fail() {
  CODE_WHY="$1"
  [ -n "$_CODE_TMPDIR" ] && printf '%s\n' "$1" > "$_CODE_TMPDIR/why" 2>/dev/null
  return 2
}

# code_why — the last recorded reason, subshell-safe.
code_why() {
  if [ -n "$_CODE_TMPDIR" ] && [ -s "$_CODE_TMPDIR/why" ]; then
    cat "$_CODE_TMPDIR/why"
  else
    printf '%s\n' "${CODE_WHY:-the code view failed and recorded no reason}"
  fi
}

# code_ready — true when a comment-free view can actually be produced. CALL THIS FROM
# THE GATE'S OWN SHELL, never inside `$( )`: it creates the temp dir the views and the
# failure reasons live in.
code_ready() {
  [ -n "$_CODE_READY" ] && return 0
  # A NAMED temp dir, not an anonymous one. 50 gates now call this, several of them
  # many times a day, and none of them can be relied on to run a cleanup trap (most
  # already own an EXIT trap of their own, and clobbering it is how _rc.sh #27 broke).
  # So instead of trapping, each run reaps the leftovers of runs whose PID IS GONE.
  # The leak is bounded by the number of gates running RIGHT NOW, not by uptime.
  _code_root="${TMPDIR:-/tmp}"
  for _cd_old in "$_code_root"/proof-os-code.*; do
    [ -d "$_cd_old" ] || continue
    _cd_pid=${_cd_old##*/proof-os-code.}; _cd_pid=${_cd_pid%%.*}
    case "$_cd_pid" in ''|*[!0-9]*) continue ;; esac
    kill -0 "$_cd_pid" 2>/dev/null || rm -rf "$_cd_old" 2>/dev/null
  done
  _CODE_TMPDIR="$(mktemp -d "$_code_root/proof-os-code.$$.XXXXXX" 2>/dev/null)" || {
    CODE_WHY="cannot create a temp dir for the code view"; return 1; }
  if ! _code_find_py; then
    _code_fail "no working python interpreter (probed: \$PROOF_PYTHON, python3, python, py) — cannot tell code from comment"
    return 1
  fi
  if [ ! -f "$_CODE_STRIP" ]; then
    _code_fail "comment stripper missing at $_CODE_STRIP — cannot tell code from comment"
    return 1
  fi
  _CODE_READY=1
  return 0
}

# _code_lang <path> — the --lang the stripper should use, from the extension.
_code_lang() {
  case "$1" in
    *.tsx|*.ts|*.jsx|*.js|*.mjs|*.cjs) echo ts ;;
    *.java)                            echo java ;;
    *.sh|*.bash)                       echo shell ;;
    *)                                 echo "" ;;
  esac
}

# code_view <file> [lang] — PATH to a comment-free copy. Drop-in for "$file".
# The stripper writes the view DIRECTLY: routing it through `$( )` would eat the
# trailing newline and change the file's last line.
code_view() {
  _vf="$1"; _vl="${2:-}"
  code_ready || return 2
  [ -f "$_vf" ] || { _code_fail "$_vf is not a readable file"; return 2; }
  [ -n "$_vl" ] || _vl="$(_code_lang "$_vf")"
  if [ -z "$_vl" ]; then
    _code_fail "no comment grammar known for $_vf — refusing to guess (a wrong grammar silently deletes code)"
    return 2
  fi
  # one view per source path; a basename alone collides across directories
  _vk="$(printf '%s' "$_vf" | tr -c 'A-Za-z0-9._-' '_')"
  _vp="$_CODE_TMPDIR/$_vk"
  [ -f "$_vp" ] && { printf '%s\n' "$_vp"; return 0; }
  if ! _verr="$("$_CODE_PY" "$_CODE_STRIP" --lang "$_vl" "$_vf" 2>&1 > "$_vp.part")"; then
    rm -f "$_vp.part" 2>/dev/null
    _code_fail "comment-stripping failed on $_vf: $_verr"
    return 2
  fi
  mv -f "$_vp.part" "$_vp" 2>/dev/null || {
    _code_fail "cannot write the code view for $_vf"; return 2; }
  printf '%s\n' "$_vp"
  return 0
}

# code_of <file> [lang] — the file's CODE as text, for gates that already pipe.
code_of() {
  _op="$(code_view "$1" "${2:-}")" || return 2
  cat "$_op"
  return 0
}

# code_grep_r <pattern> <dir> [skip_ere] — `grep -rn` over the CODE of every source
# file under <dir>, printing `path:line:text` exactly as grep -rn would. Files whose
# path matches <skip_ere> are not searched.
#
# The recursive form is where the MIRROR disease bites hardest: a gate that proves "the
# release button has a caller somewhere in src/" greens on any comment in the tree that
# happens to name the function — including the comment written by the person who
# DELETED the caller. Line numbers survive the strip, so callers can still print a real
# location.
#
# Returns 0 if anything matched, 1 if nothing matched (grep's own convention), and 2 if
# a file could not be viewed — never a silent partial answer over a subset of the tree.
code_grep_r() {
  _gp="$1"; _gd="$2"; _gs="${3:-}"
  code_ready || return 2
  _ghit=1
  _glist="$_CODE_TMPDIR/list.$$"
  # PRE-FILTER with a raw grep. Stripping every source file under src/ costs one python
  # process per file — 538 of them here, which took the gate past 200s and turned a
  # correctness fix into a timeout, i.e. straight back into F-0229. The raw grep can only
  # OVER-select (a comment-only match is still a match), so every file that could
  # possibly contain a code hit survives it, and only those few are actually tokenized.
  # -rl is used, not -rn: we want candidate FILES, and the line-accurate answer comes
  # from grepping the stripped view below.
  grep -rlE "$_gp" "$_gd" --include='*.ts' --include='*.tsx' --include='*.js' \
       --include='*.jsx' --include='*.mjs' --include='*.cjs' --include='*.java' \
       2>/dev/null > "$_glist"
  while IFS= read -r _gf; do
    [ -n "$_gf" ] || continue
    if [ -n "$_gs" ] && printf '%s' "$_gf" | grep -qE "$_gs"; then continue; fi
    _gv=$(code_view "$_gf") || return 2
    if _gout=$(grep -nE "$_gp" "$_gv" 2>/dev/null); then
      printf '%s\n' "$_gout" | sed "s|^|$_gf:|"
      _ghit=0
    fi
  done < "$_glist"
  rm -f "$_glist" 2>/dev/null
  return $_ghit
}

# code_cleanup — optional; the OS clears the temp dir anyway. Callers that already own
# an EXIT trap can call it from there. Never required for correctness.
code_cleanup() {
  [ -n "$_CODE_TMPDIR" ] && rm -rf "$_CODE_TMPDIR" 2>/dev/null
  _CODE_TMPDIR=""
  return 0
}
