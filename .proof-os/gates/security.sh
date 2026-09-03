#!/usr/bin/env bash
# gates/security.sh — kabir's oracle, and the gate with the largest blind spot here.
# origin: F-0003 (kabir's teeth) + F-0015 (no-lockfile false red)
# LAW: tool-cannot-run => exit 2 (unavailable/believed). exit 1 is ONLY for real
#      findings. Bad arguments are 64 — never a verdict about the caller's cwd.
# LAW (F-0025): every external step is wall-clock bounded (PROOF_GATE_TIMEOUT, default
#      300s). `npm audit` is a network call and was completely unbounded.
# LAW (F-0026 / law 7): $SELF is resolved ABSOLUTELY before the cd — the old order (cd,
#      then source "$(dirname "$0")/_rc.sh") meant a relative invocation wrote NO rc
#      file at all, while the release claimed liveness in 20/20 gates.
# LAW (rule 5): what this gate does NOT check is most of security, and it now says so on
#      every exit path instead of returning a bare exit 2.
# Usage: gates/security.sh [project_dir] [live_url]
set -u

SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || {
  echo "· cannot resolve gate directory — unavailable"; exit 2; }

NOTCHECKED="anything — the gate exited before it could name a blind spot"
_nc() { [ -n "${_NC_DONE:-}" ] || { _NC_DONE=1; printf 'NOT CHECKED: %s\n' "$NOTCHECKED"; }; }
trap _nc EXIT

if [ "$#" -ge 1 ] && [ -z "$1" ]; then
  NOTCHECKED="anything at all — the arguments were rejected before any oracle ran"
  echo "· empty project_dir argument — a verdict about the caller's cwd would be a lie"
  echo "usage: gates/security.sh [project_dir] [live_url]"
  exit 64
fi
# §1 usage: extra positional arguments must not be silently discarded.
if [ "$#" -gt 2 ]; then
  NOTCHECKED="anything at all — the arguments were rejected before any oracle ran"
  echo "· $# arguments given; this gate takes at most 2 — unrecognised: $(shift 2; echo "$*")"
  echo "usage: gates/security.sh [project_dir] [live_url]"
  exit 64
fi
PROJECT="${1:-.}"
NOTCHECKED="anything in '$PROJECT' — the directory could not be entered"
[ -d "$PROJECT" ] || { echo "· not a directory: $PROJECT — unavailable"; exit 2; }
cd "$PROJECT" || { echo "· project dir unreadable: $PROJECT — unavailable"; exit 2; }

. "$SELF/_rc.sh" 2>/dev/null || true
if type rc_init >/dev/null 2>&1; then rc_init security; fi

fail=0; unavail=0
notes=""
note() { notes="${notes:+$notes; }$1"; }
TMO="${PROOF_GATE_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $TMO"
else TO=""; note "the time budget (no coreutils timeout on PATH — npm audit ran unbounded)"; fi
timed_out() { [ "$1" -eq 124 ] || [ "$1" -eq 137 ]; }

# GITLEAKS EXITS 1 FOR TWO COMPLETELY DIFFERENT THINGS: "leaks found" and "I could not
# scan at all". `gitleaks detect` on a directory that is not a git repo exits 1 with
# `FTL could not scan the repo`, having read nothing — and this gate, the one whose whole
# job is credentials, reported that as VERDICT: broken while its NOT CHECKED line never
# admitted that zero bytes had been scanned. Kept identical to gates/frontend.sh.
gitleaks_could_not_scan() {
  printf '%s\n' "$1" | grep -qiE '(^|[[:space:]])(FTL|FATAL)([[:space:]]|:)|could not scan|not a git repository|failed to (open|load|get) |error opening|unable to (open|read)|no such file or directory|invalid.*(config|toml)|panic:'
}

# This gate has never done SAST, licence review, authz review, or dependency-confusion
# checks, and without gitleaks it does no secret detection at all. Stated up front so it
# is true on every exit path, including the early ones.
STRUCTURAL="SAST / taint analysis (no semgrep, codeql or equivalent runs here); licence compliance; authentication and authorisation logic; secrets in git HISTORY; container, IaC and CI-workflow configuration; runtime behaviour"

NOTCHECKED="$STRUCTURAL"

# ---- 1. dependency advisories ------------------------------------------------
if [ -f package-lock.json ] || [ -f yarn.lock ] || [ -f pnpm-lock.yaml ]; then
  if command -v npm >/dev/null; then
    echo "· npm audit --audit-level=high (budget ${TMO}s)"
    out=$($TO npm audit --audit-level=high 2>&1); rc=$?
    if timed_out $rc; then
      echo "  npm audit exceeded ${TMO}s — unavailable (F-0025)"; unavail=1
      note "dependency advisories (npm audit timed out)"
    elif [ $rc -ne 0 ]; then
      # The env grep used to run over the WHOLE audit body, advisory titles included, so
      # an advisory titled "... network ..." downgraded a real finding to unavailable.
      # npm's own error lines are the only place an environment failure can appear.
      envlines=$(printf '%s\n' "$out" | grep -E '^npm (ERR!|error|warn)|^ *code +E[A-Z]+')
      if printf '%s\n' "$envlines" | grep -qiE 'ENOLOCK|requires.*lockfile|ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ETIMEDOUT|ENETUNREACH|network|registry|EPROXY|self.signed certificate'; then
        echo "  audit could not run (env issue) — unavailable"; unavail=1
        note "dependency advisories (npm audit could not reach the registry)"
      else printf '%s\n' "$out" | tail -5; fail=1; fi
    fi
  else echo "· npm UNAVAILABLE"; unavail=1
    note "dependency advisories (npm is not on PATH)"; fi
else echo "· no lockfile — npm audit cannot run here (unavailable, NOT a finding)"; unavail=1
  note "dependency advisories (no lockfile — nothing pinned to audit)"; fi

# ---- 2. secrets --------------------------------------------------------------
if command -v gitleaks >/dev/null; then
  if [ -d .git ]; then GLARGS="detect --no-banner -s ."
  else GLARGS="detect --no-git --no-banner -s ."
    note "the git history (this tree is not a git repository — gitleaks ran in --no-git mode over working files only)"
  fi
  echo "· gitleaks $GLARGS (budget ${TMO}s)"
  # shellcheck disable=SC2086  # GLARGS is a deliberate argument list
  glout=$($TO gitleaks $GLARGS 2>&1); rc=$?
  printf '%s\n' "$glout" | head -20
  if timed_out $rc; then echo "  gitleaks exceeded ${TMO}s — unavailable"; unavail=1
    note "secrets (gitleaks timed out)"
  elif [ $rc -ne 0 ] && { [ $rc -ge 2 ] || gitleaks_could_not_scan "$glout"; }; then
    echo "  gitleaks could not scan this tree (exit $rc) — unavailable, NOT a finding: it read nothing, so it found nothing"
    unavail=1
    note "secrets ENTIRELY (gitleaks exited $rc without scanning: live AWS keys, tokens or private keys in this tree would NOT have been seen)"
  elif [ $rc -ne 0 ]; then fail=1; fi
else
  echo "· gitleaks UNAVAILABLE — NO secret detection ran (not a pass; this gate ships no fallback scanner)"
  unavail=1
  note "secrets ENTIRELY (gitleaks is absent and there is no fallback: live AWS keys, tokens or private keys in this tree would NOT have been seen)"
fi

# ---- 3. response headers of a live URL ---------------------------------------
if [ -n "${2:-}" ]; then
  echo "· headers on $2"
  h=$($TO curl -sI --max-time 10 "$2" 2>/dev/null) || { echo "  unreachable — unavailable"; unavail=1; h=""; }
  if [ -n "$h" ]; then for want in "strict-transport-security" "x-content-type-options"; do
    echo "$h" | grep -qi "$want" || { echo "  missing header: $want"; fail=1; }; done
  else note "response headers (the URL did not answer)"; fi
  note "every response header except strict-transport-security and x-content-type-options (CSP, frame-options, referrer-policy and permissions-policy are not inspected)"
else
  note "response headers (no live URL was given)"
fi

NOTCHECKED="${notes:+$notes; }$STRUCTURAL"

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
