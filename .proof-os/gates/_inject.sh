# shellcheck shell=sh
# _inject.sh — safe temporary writes into a product file, for falsifying a gate.
#
# WHY THIS EXISTS (F-0326). Gates are grouped by file ownership so two producers never write
# the same file. That grouping has a hole: a gate-repair producer owns only .proof-os/gates/**,
# but to prove its gate can FAIL it must inject a defect into the product file the gate guards
# and then put it back. That transient second ownership is declared nowhere.
#
# What happened: a gate-repair agent snapshotted contracts-and-deliverables.tsx, injected, and
# restored — from a snapshot taken BEFORE a peer in the same wave wrote its fix to that file.
# The restore was byte-perfect and silently reverted the peer. Tests and gate survived, the
# source edit did not, and tsc went red against code that no longer existed. Both agents
# reported success and both were telling the truth when they wrote it.
#
# The lock is not the fix. A lock stops two writers at once; it does not stop a restore from a
# snapshot that went stale while you held nothing. THE FIX IS THE REFUSAL: inject_end compares
# the file against what the injector itself last left there, and if it differs — someone else
# wrote during the window — it REFUSES to restore and says so loudly, leaving both versions on
# disk. Losing a peer's work silently is the failure; a noisy stop is not.
#
# Usage:
#     . "$(dirname "$0")/_inject.sh"
#     inject_begin src/pages/x.tsx   || exit 2      # lock + snapshot
#     ...mutate the file...
#     inject_mark  src/pages/x.tsx                  # "this is mine" — after EVERY write
#     run_the_gate; rc=$?
#     inject_end   src/pages/x.tsx   || exit 2      # verify-or-refuse, restore, unlock
#
# inject_end returns 0 restored · 1 REFUSED (a peer wrote; nothing was touched) · 2 unusable.
# Always byte-exact `cp`, never `sed -i`: sed rewrites the whole file and silently normalised
# CRLF→LF here, breaking a restore that was otherwise correct.

_INJECT_DIR="${_INJECT_DIR:-}"

_inject_key() { printf 'inject-%s' "$(printf '%s' "$1" | tr '/\\:' '___')"; }

_inject_store() {
  if [ -z "$_INJECT_DIR" ]; then
    _r="${PROOF_OS_DIR:-.proof-os}"
    [ -d "$_r" ] || return 1
    _INJECT_DIR="$_r/tmp/inject.$$"
    mkdir -p "$_INJECT_DIR" 2>/dev/null || return 1
  fi
  printf '%s' "$_INJECT_DIR"
}

_inject_hash() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

# inject_begin <path> — take the lock, snapshot the file, remember its hash.
inject_begin() {
  _ip="$1"
  [ -f "$_ip" ] || { echo "inject: $_ip is not a file — refusing to claim it"; return 2; }
  _is=$(_inject_store) || { echo "inject: no writable store under .proof-os"; return 2; }
  if command -v gate_lock >/dev/null 2>&1; then
    gate_lock "$(_inject_key "$_ip")" 120 1800 || {
      echo "inject: another holder has $_ip and did not release it in 120s — NOT proceeding."
      echo "        Two agents injecting into one file is exactly F-0326; wait or re-group."
      return 2; }
  fi
  _ib="$_is/$(_inject_key "$_ip")"
  cp "$_ip" "$_ib.orig" || { echo "inject: could not snapshot $_ip"; return 2; }
  _inject_hash "$_ip" > "$_ib.h0"
  # Until the caller marks a write, "mine" is the pristine file.
  cp "$_ib.h0" "$_ib.hmine"
  echo "inject: claimed $_ip (snapshot $(cut -c1-12 < "$_ib.h0"))"
  return 0
}

# inject_mark <path> — record the file as the injector last left it. Call after EVERY write.
inject_mark() {
  _ip="$1"; _is=$(_inject_store) || return 2
  _ib="$_is/$(_inject_key "$_ip")"
  [ -f "$_ib.orig" ] || { echo "inject: $_ip was never claimed — call inject_begin first"; return 2; }
  _inject_hash "$_ip" > "$_ib.hmine"
  return 0
}

# inject_end <path> — verify the file is still the injector's, then restore. Refuse otherwise.
inject_end() {
  _ip="$1"; _is=$(_inject_store) || return 2
  _ib="$_is/$(_inject_key "$_ip")"
  [ -f "$_ib.orig" ] || { echo "inject: $_ip was never claimed"; return 2; }
  _now=$(_inject_hash "$_ip"); _mine=$(cat "$_ib.hmine" 2>/dev/null); _h0=$(cat "$_ib.h0" 2>/dev/null)

  if [ "$_now" != "$_mine" ]; then
    cp "$_ib.orig" "$_ib.refused-snapshot" 2>/dev/null
    echo "inject: REFUSING to restore $_ip (F-0326)."
    echo "        The file is not what this injector last wrote, so someone else changed it"
    echo "        while the injection was live. Restoring the snapshot would silently revert"
    echo "        their work — which is the defect this helper exists to prevent."
    echo "          snapshot : $(printf '%s' "$_h0"   | cut -c1-12)   (before the injection)"
    echo "          mine     : $(printf '%s' "$_mine" | cut -c1-12)   (what this injector left)"
    echo "          on disk  : $(printf '%s' "$_now"  | cut -c1-12)   (what is there now)"
    echo "        The pre-injection snapshot is kept at $_ib.refused-snapshot."
    echo "        Reconcile by hand; do not cp it back without reading the peer's diff first."
    command -v gate_unlock >/dev/null 2>&1 && gate_unlock "$(_inject_key "$_ip")"
    return 1
  fi

  cp "$_ib.orig" "$_ip" || { echo "inject: restore of $_ip FAILED"; return 2; }
  if [ "$(_inject_hash "$_ip")" != "$_h0" ]; then
    echo "inject: restored $_ip but the hash does not match the snapshot — do not trust this tree"
    return 2
  fi
  rm -f "$_ib.orig" "$_ib.h0" "$_ib.hmine" 2>/dev/null
  command -v gate_unlock >/dev/null 2>&1 && gate_unlock "$(_inject_key "$_ip")"
  echo "inject: restored $_ip (hash verified)"
  return 0
}
