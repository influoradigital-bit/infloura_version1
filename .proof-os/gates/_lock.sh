# shellcheck shell=sh
#
# gates/_lock.sh — sourced by shell gates. origin: F-0229
# (gate-budget-false-unavailable).
#
# THE DISEASE. Two agents run the same expensive oracle at once. Neither is broken and
# neither is hung — they are simply sharing one machine's cores, so each takes several
# times its solo wall-clock. The gate's fixed budget kills whichever finishes second and
# reports `unavailable`. Nothing is learned, and because the gate does this every time
# the project is busy, it has never once returned a verdict. An oracle that always says
# "I could not tell" is indistinguishable from no oracle at all (F-0023).
#
# Raising the budget alone does not fix it: with N agents the leg takes N times longer,
# so any fixed number is only a bet on how many agents are running. Serialising the
# expensive leg does fix it — one vitest at a time takes its honest solo time, and the
# others WAIT rather than being starved. Waiting is not spending the leg's budget: the
# lock wait has its own, separate ceiling.
#
# WHAT THIS MUST NEVER DO. Waiting forever is a hang, and a hang reported as anything
# other than unavailable is a lie. Both ceilings here are finite, and a lock that cannot
# be acquired inside its ceiling returns non-zero so the caller reports UNAVAILABLE. No
# path in this file can turn a timeout into a pass.
#
# Usage:
#     SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"   # BEFORE any cd
#     . "$SELF/_lock.sh" 2>/dev/null || true
#     if gate_lock npm-test 900; then
#        ... the expensive leg ...
#        gate_unlock npm-test
#     else
#        echo "waited ${LOCK_WAITED}s for the npm-test lock — unavailable"
#     fi
#
# `mkdir` is the lock primitive: it is atomic and it fails if the directory exists, on
# every filesystem this project runs on, including Windows. A lock file written with
# `>` is NOT atomic and two processes can both "create" it.

LOCK_WAITED=0
LOCK_WHY=""
_LOCK_DIR=""

# An EXISTING .proof-os, printed; nothing is created (the _rc.sh rule — a read never
# makes state). Falls back to the system temp dir so serialisation still works for a
# gate run outside any store; a lock is a courtesy between processes, not a record.
_lock_root() {
  [ -n "$_LOCK_DIR" ] && { printf '%s\n' "$_LOCK_DIR"; return 0; }
  _lr=""
  if [ -n "${PROOF_OS_DIR:-}" ] && [ -d "${PROOF_OS_DIR:-}" ]; then
    _lr=$(cd "$PROOF_OS_DIR" && pwd)
  else
    _ld=$(pwd) || return 1
    while :; do
      [ -d "$_ld/.proof-os" ] && { _lr=$(cd "$_ld/.proof-os" && pwd); break; }
      case "$_ld" in /|"") break ;; esac
      _lnext=$(dirname "$_ld"); [ "$_lnext" = "$_ld" ] && break; _ld="$_lnext"
    done
  fi
  [ -n "$_lr" ] || _lr="${TMPDIR:-/tmp}/proof-os-locks"
  _LOCK_DIR="$_lr/locks"
  mkdir -p "$_LOCK_DIR" 2>/dev/null || return 1
  printf '%s\n' "$_LOCK_DIR"
}

_lock_alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

# A holder that died without unlocking would block every later run forever — the lock
# would become a permanent `unavailable`, which is the same disease one layer down. A
# lock whose recorded pid is gone is reaped. STALE_AFTER is a second backstop for pid
# reuse and for holders on another host/container that this process cannot see.
_lock_reap() {
  _rd="$1"; _stale="$2"
  [ -d "$_rd" ] || return 1
  _rp=$(cat "$_rd/pid" 2>/dev/null)
  if [ -n "$_rp" ] && _lock_alive "$_rp"; then
    _rt=$(cat "$_rd/started" 2>/dev/null)
    case "$_rt" in ''|*[!0-9]*) return 1 ;; esac
    _now=$(date +%s 2>/dev/null) || return 1
    [ $((_now - _rt)) -gt "$_stale" ] || return 1
    LOCK_WHY="reaped a lock held ${_stale}s+ by live pid $_rp"
  fi
  rm -rf "$_rd" 2>/dev/null
  return 0
}

# gate_lock <name> <max_wait_seconds> [stale_after_seconds]
#   0  acquired
#   1  still held after max_wait — caller MUST report unavailable, never a pass
#   2  locking itself is unavailable (no writable location); caller may proceed
#      UNSERIALISED and should say so, because that is exactly today's behaviour
gate_lock() {
  _ln="$1"; _lw="${2:-900}"; _ls="${3:-3600}"
  LOCK_WAITED=0; LOCK_WHY=""
  _lroot=$(_lock_root) || { LOCK_WHY="no writable location for locks"; return 2; }
  _lpath="$_lroot/$_ln.lock"
  _lt0=$(date +%s 2>/dev/null) || { LOCK_WHY="no clock"; return 2; }
  while :; do
    if mkdir "$_lpath" 2>/dev/null; then
      printf '%s\n' "$$" > "$_lpath/pid" 2>/dev/null
      date +%s > "$_lpath/started" 2>/dev/null
      _lnow=$(date +%s); LOCK_WAITED=$((_lnow - _lt0))
      return 0
    fi
    _lock_reap "$_lpath" "$_ls" && continue
    _lnow=$(date +%s); LOCK_WAITED=$((_lnow - _lt0))
    if [ "$LOCK_WAITED" -ge "$_lw" ]; then
      _lh=$(cat "$_lpath/pid" 2>/dev/null)
      LOCK_WHY="lock '$_ln' still held by pid ${_lh:-?} after ${LOCK_WAITED}s"
      return 1
    fi
    sleep 3
  done
}

# gate_unlock <name> — only the holder may release it. Releasing someone else's lock
# would let two expensive legs run at once again, silently.
gate_unlock() {
  _ln="$1"
  _lroot=$(_lock_root) || return 0
  _lpath="$_lroot/$_ln.lock"
  [ -d "$_lpath" ] || return 0
  _lp=$(cat "$_lpath/pid" 2>/dev/null)
  [ "$_lp" = "$$" ] || return 0
  rm -rf "$_lpath" 2>/dev/null
  return 0
}
