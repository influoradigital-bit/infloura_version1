# shellcheck shell=sh
#
# 0.4.1 #4: the directive above is BARE, and must stay bare — no trailing prose, and no
# other comment line in this file may begin with the word `shellcheck` either, because
# any such line is parsed as a directive too. 0.4.0 wrote the reason for it on the
# directive line itself ("shell=sh   (sourced, never executed - SC2148 does not
# apply)"). A directive line is parsed as key=value pairs, not prose, so the trailing
# text raised SC1125 and the em-dash in it `commitBuffer: invalid character`: the
# linter could not read this file AT ALL, and `shellcheck -S warning gates/*.sh`
# returned a TOOL ERROR that looks like a clean run to anyone reading the output and
# not the exit code. The reason, restated here where it is only a comment: this file is
# SOURCED, never executed, so SC2148 (missing shebang) does not apply to it.
#
# gates/_rc.sh — sourced by shell gates. origin: F-0026 (unproved-progress-claim).
#
# A gate that is still running and a gate whose process died look identical from the
# outside: the log stops changing. F-0026 was exactly that mistake — a stale log line
# read as liveness for eleven minutes while the process was already dead.
#
# So liveness stops being inferred and becomes a file anyone can read:
#   running pid=1234 started=2026-08-02T06:00:00Z          ← claims to be alive
#   exit=1 pid=1234 ended=2026-08-02T06:00:41Z observed=exit    ← finished, its code
#   exit=143 pid=1234 ended=... observed=signal            ← KILLED, not finished
#
# gates/liveness.py reads these. A `running` whose pid is gone means the gate DIED
# rather than passed. Absence of an rc file past the timeout is exit 2, never exit 0.
#
# 0.3.4 fixes (all reproduced against 0.3.3):
#   #24 A GATE KILLED BY A SIGNAL RECORDED exit=0 — a PASS for a process that died.
#       Only EXIT was trapped, and the shell's cooked status inside an EXIT trap after
#       SIGTERM is not 143. TERM/INT/HUP/QUIT are trapped now and record 128+signo,
#       which is what _rc.py always did and this file only claimed to do (#26).
#   #25 CONCURRENCY: two runs of one gate shared `<gate>.rc`, so the first to finish
#       wrote its code over a run still going. Each process owns `<gate>.<pid>.rc`;
#       `<gate>.rc` is a pointer never written over an rc whose pid is still alive.
#   #27 `trap -p` is a bashism in a file declaring `shellcheck shell=sh`. Under dash
#       it is "Illegal option -p", the capture came back empty, and the fallback
#       CLOBBERED any pre-existing EXIT trap — precisely what its comment said it
#       avoided. The POSIX form is plain `trap` with its output REDIRECTED (a command
#       substitution is a subshell, where traps are already reset, which is why the
#       obvious `$(trap)` returns nothing under dash).
#   #28 the sed that unquoted a captured handler mangled handlers containing a single
#       quote. The captured line is now re-executed with `trap` shadowed by a
#       capturing function, so the SHELL does the unquoting, exactly as it quoted it.
#       And `mkdir -p "$RC_DIR"` used the invocation cwd, so a gate run from outside
#       the project MANUFACTURED a .proof-os where nobody looks: we now walk upward
#       for an existing store and write nothing if there is none.
#
# Usage inside a gate:   . "$(dirname "$0")/_rc.sh"; rc_init build

RC_FILE=""; RC_PTR=""; RC_DIR=""
_RC_WRITTEN=""
_rc_prev_EXIT=""; _rc_prev_TERM=""; _rc_prev_INT=""; _rc_prev_HUP=""; _rc_prev_QUIT=""

# An EXISTING .proof-os, printed. Nothing is created: a read never makes state.
_rc_store() {
  if [ -n "${PROOF_OS_DIR:-}" ]; then
    [ -d "$PROOF_OS_DIR" ] || return 1
    ( cd "$PROOF_OS_DIR" 2>/dev/null && pwd ) || return 1
    return 0
  fi
  _rc_d=$(pwd) || return 1
  while :; do
    if [ -d "$_rc_d/.proof-os" ]; then
      ( cd "$_rc_d/.proof-os" 2>/dev/null && pwd ) || return 1
      return 0
    fi
    [ "$_rc_d" = "/" ] && return 1
    _rc_d=$(dirname "$_rc_d")
  done
}

_rc_pid_of() {
  [ -f "$1" ] || return 1
  sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' "$1" 2>/dev/null | head -n 1
}

_rc_alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

_rc_put() {  # file line — atomic: a reader never sees half a record
  printf '%s\n' "$2" > "$1.$$.tmp" 2>/dev/null || return 1
  mv -f "$1.$$.tmp" "$1" 2>/dev/null || { rm -f "$1.$$.tmp" 2>/dev/null; return 1; }
}

# #27/#28 — capture whatever traps already exist, using the shell's own quoting.
_rc_capture() {
  _rc_c=""
  while [ $# -gt 0 ]; do
    case "$1" in --) shift; continue ;; esac
    _rc_c="$1"; shift; break
  done
  for _rc_s in "$@"; do
    case "$_rc_s" in
      EXIT|TERM|INT|HUP|QUIT|SIGTERM|SIGINT|SIGHUP|SIGQUIT|0|1|2|3|15)
        case "$_rc_s" in
          0) _rc_s=EXIT ;; 1) _rc_s=HUP ;; 2) _rc_s=INT ;; 3) _rc_s=QUIT ;;
          15) _rc_s=TERM ;; SIG*) _rc_s=${_rc_s#SIG} ;;
        esac
        eval "_rc_prev_$_rc_s=\$_rc_c" ;;
    esac
  done
}

_rc_chain() {  # run whatever handler was installed before us, if any
  eval "_rc_c=\${_rc_prev_$1:-}"
  [ -n "$_rc_c" ] && eval "$_rc_c"
  return 0
}

rc_init() {
  RC_FILE=""; RC_PTR=""; _RC_WRITTEN=""
  RC_DIR=$(_rc_store) || return 0            # no store => nothing to record into
  [ -n "$RC_DIR" ] || return 0
  RC_DIR="$RC_DIR/rc"
  mkdir -p "$RC_DIR" 2>/dev/null || return 0 # a subdir of an EXISTING store only
  _rc_line="running pid=$$ started=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  RC_FILE="$RC_DIR/$1.$$.rc"                 # #25 this process, and only this one
  _rc_put "$RC_FILE" "$_rc_line" || { RC_FILE=""; return 0; }
  RC_PTR="$RC_DIR/$1.rc"
  _rc_other=$(_rc_pid_of "$RC_PTR")
  if [ -n "$_rc_other" ] && [ "$_rc_other" != "$$" ] && _rc_alive "$_rc_other"; then
    RC_PTR=""                                # another run of this gate is still alive
  else
    _rc_put "$RC_PTR" "$_rc_line" || RC_PTR=""
  fi

  # CHAIN, never clobber: a gate may already have its own cleanup trap. Replacing it
  # silently cancels the other one — either liveness is never written, or temp dirs leak.
  _rc_tmp="${TMPDIR:-/tmp}/rc-traps.$$"
  if trap > "$_rc_tmp" 2>/dev/null; then
    eval "$(sed 's/^trap /_rc_capture /' "$_rc_tmp" 2>/dev/null)" 2>/dev/null || true
  fi
  rm -f "$_rc_tmp" 2>/dev/null

  trap 'rc_done $? exit; _rc_chain EXIT' EXIT
  trap 'rc_sig 15 TERM' TERM
  trap 'rc_sig 2 INT'   INT
  trap 'rc_sig 1 HUP'   HUP
  trap 'rc_sig 3 QUIT'  QUIT
}

rc_sig() {  # #24 killed is not finished: 128+signo, observed, never the cooked status
  rc_done $((128 + $1)) signal
  _rc_chain "$2"
  exit $((128 + $1))
}

rc_done() {
  [ -n "${RC_FILE:-}" ] || return 0
  [ -n "${_RC_WRITTEN:-}" ] && return 0      # a signal death is not overwritten by EXIT
  _RC_WRITTEN=1
  _rc_code=$1
  _rc_obs=${2:-exit}
  case "$_rc_code" in
    ''|*[!0-9]*) _rc_code=unknown; _rc_obs=unobserved ;;
  esac
  _rc_line="exit=$_rc_code pid=$$ ended=$(date -u +%Y-%m-%dT%H:%M:%SZ) observed=$_rc_obs"
  _rc_put "$RC_FILE" "$_rc_line" || true
  if [ -n "${RC_PTR:-}" ]; then
    _rc_owner=$(_rc_pid_of "$RC_PTR")
    if [ -z "$_rc_owner" ] || [ "$_rc_owner" = "$$" ]; then
      _rc_put "$RC_PTR" "$_rc_line" || true
    fi
  fi
  return 0
}
