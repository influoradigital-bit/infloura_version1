#!/usr/bin/env bash
# gates/frontend.sh — kavya's oracle: her promoted ruleset, run for real.
# origin: kavya's 9 promoted items + F-0013/F-0017 (eslint-9 / no-config false reds)
# LAW: tool-cannot-run => exit 2 (unavailable). exit 1 ONLY for real findings. Bad
#      arguments are 64 — never a verdict about the caller's cwd.
# LAW (F-0025): every external step is wall-clock bounded (PROOF_GATE_TIMEOUT, default
#      300s) and npx never reaches the network: --no-install, so a missing tool is
#      UNAVAILABLE immediately instead of a silent download that can hang forever.
# LAW (F-0026 / law 7): $SELF is resolved ABSOLUTELY before the cd. The old order —
#      cd first, then "$(dirname "$0")/_rc.sh" and "$(dirname "$0")/eslint.sage.mjs" —
#      meant ANY relative invocation lost both: no rc file was written at all, and
#      kavya's entire ruleset silently degraded to "eslint tool failure — unavailable",
#      turning a real finding into a missing tool.
# LAW (rule 5/6): prints what it could NOT check on EVERY exit path, and declares the
#      scope it actually searched instead of implying it searched everything.
# Usage: gates/frontend.sh [project_dir]
set -u

# ---- resolve self BEFORE anything changes directory --------------------------
SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || {
  echo "· cannot resolve gate directory — unavailable"; exit 2; }

NOTCHECKED="anything — the gate exited before it could name a blind spot"
_nc() { [ -n "${_NC_DONE:-}" ] || { _NC_DONE=1; printf 'NOT CHECKED: %s\n' "$NOTCHECKED"; }; }
trap _nc EXIT

if [ "$#" -ge 1 ] && [ -z "$1" ]; then
  NOTCHECKED="anything at all — the arguments were rejected before any oracle ran"
  echo "· empty project_dir argument — a verdict about the caller's cwd would be a lie"
  echo "usage: gates/frontend.sh [project_dir]"
  exit 64
fi
# §1 usage: extra positional arguments must not be silently discarded (same rule the
# build and e2e gates now apply — a gate that ignores half its argv was not invoked).
if [ "$#" -gt 1 ]; then
  NOTCHECKED="anything at all — the arguments were rejected before any oracle ran"
  echo "· $# arguments given; this gate takes at most 1 — unrecognised: $(shift 1; echo "$*")"
  echo "usage: gates/frontend.sh [project_dir]"
  exit 64
fi
PROJECT="${1:-.}"
NOTCHECKED="anything in '$PROJECT' — the directory could not be entered"
[ -d "$PROJECT" ] || { echo "· not a directory: $PROJECT — unavailable"; exit 2; }
cd "$PROJECT" || { echo "· project dir unreadable: $PROJECT — unavailable"; exit 2; }

. "$SELF/_rc.sh" 2>/dev/null || true
if type rc_init >/dev/null 2>&1; then rc_init frontend; fi

fail=0; unavail=0
notes=""
note() { notes="${notes:+$notes; }$1"; }
TMO="${PROOF_GATE_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $TMO"
else TO=""; note "the time budget (no coreutils timeout on PATH — steps ran unbounded)"; fi
timed_out() { [ "$1" -eq 124 ] || [ "$1" -eq 137 ]; }
run(){ echo "· $1"; }

# TSCONFIG CONFIGURATION errors mean tsc type-checked NOTHING — an unavailable oracle
# (§1), not a finding. Kept identical to gates/build.sh so the two gates cannot reach
# opposite verdicts from the same tsc output, which they did on TS5023.
tsconfig_issue() {
  printf '%s\n' "$1" | grep -qE 'TS5023|TS5024|TS5058|TS6046|TS18002|TS18003|TS5012|TS5014|Unknown compiler option|Failed to parse file|Cannot read file .*tsconfig'
}
# GITLEAKS EXITS 1 FOR TWO COMPLETELY DIFFERENT THINGS: "leaks found" and "I could not
# scan at all". `gitleaks detect` on a directory that is not a git repo exits 1 with
# `FTL could not scan the repo` / `fatal: not a git repository`, having read nothing —
# and the gate reported that as VERDICT: broken. Every project directory that is not
# itself a git repo was a false red on the security gate, with no note admitting that
# nothing had been scanned. The OUTPUT is what tells the two apart.
gitleaks_could_not_scan() {
  printf '%s\n' "$1" | grep -qiE '(^|[[:space:]])(FTL|FATAL)([[:space:]]|:)|could not scan|not a git repository|failed to (open|load|get) |error opening|unable to (open|read)|no such file or directory|invalid.*(config|toml)|panic:'
}

NOTCHECKED="the frontend — the gate stopped before any oracle produced a result"

if command -v npx >/dev/null && [ -f package.json ]; then
  # ---- types ----------------------------------------------------------------
  if [ -f tsconfig.json ]; then
    run "tsc --noEmit"
    # --no-install: npx without it fetches typescript from the registry, which is both an
    # unbounded network call (F-0025 unenforced) and a silent change of what was measured.
    out=$($TO npx --no-install tsc --noEmit 2>&1); rc=$?
    if timed_out $rc; then
      echo "  tsc exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; note "types (tsc timed out)"
    elif [ $rc -ne 0 ]; then
      if tsconfig_issue "$out"; then
        echo "$out" | head -6
        echo "  tsconfig is misconfigured — tsc type-checked NOTHING, so this is unavailable, NOT a finding"
        unavail=1; note "types (tsconfig.json is not a usable configuration — tsc never type-checked a single file)"
      elif echo "$out" | grep -qiE 'not found|could not determine|Cannot find module .typescript|npm ERR|could not be found'; then
        echo "  tsc unavailable"; unavail=1; note "types (tsc could not run)"
      else echo "$out" | head -10; fail=1; fi
    fi
  else run "tsc skipped (no tsconfig.json) — unavailable"; unavail=1
    note "types (no tsconfig.json — nothing type-checked this project)"; fi

  # ---- kavya's ruleset ------------------------------------------------------
  run "eslint (sage rules, flat config)"
  out=$($TO npx --no-install eslint . --config "$SELF/eslint.sage.mjs" --no-config-lookup 2>&1); rc=$?
  if timed_out $rc; then
    echo "  eslint exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; note "kavya's ruleset (eslint timed out)"
  else
    if [ $rc -ge 2 ]; then
      out=$(ESLINT_USE_FLAT_CONFIG=false $TO npx --no-install eslint . -c "$SELF/eslint.sage.json" --no-eslintrc 2>&1); rc=$?
    fi
    if timed_out $rc; then
      echo "  eslint exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; note "kavya's ruleset (eslint timed out)"
    elif [ $rc -ge 2 ]; then echo "  eslint tool failure (exit $rc) — unavailable"; unavail=1
      note "kavya's ruleset (eslint could not run)"
    elif [ $rc -ne 0 ]; then
      if echo "$out" | grep -qiE 'invalid option|unrecognized|could not find (a )?config|no eslint configuration'; then
        out2=$($TO npx --no-install eslint . 2>&1); rc2=$?
        if timed_out $rc2; then echo "  eslint exceeded ${TMO}s — unavailable"; unavail=1
          note "kavya's ruleset (eslint timed out)"
        elif [ $rc2 -eq 0 ]; then echo "  (project config used — believed, not proved)"; unavail=1
          note "kavya's ruleset (only the project's own eslint config ran)"
        elif echo "$out2" | grep -qiE 'could not find|no.*config'; then echo "  eslint has no usable config here — unavailable"; unavail=1
          note "kavya's ruleset (no usable eslint config)"
        else echo "$out2" | head -10; fail=1; fi
      else echo "$out" | head -10; fail=1; fi
    fi
  fi
else run "tsc/eslint UNAVAILABLE (no node project here)"; unavail=1
  note "types and kavya's ruleset (no npx, or no package.json here)"; fi

# ---- secrets ----------------------------------------------------------------
if command -v gitleaks >/dev/null; then
  # --no-git when this is not a git repo: `detect` is the git-history scanner and simply
  # cannot run outside one. Choosing the mode is what stops the false red at its source;
  # gitleaks_could_not_scan is the second line of defence for every other way it can fail.
  if [ -d .git ]; then GLARGS="detect --no-banner -s ."
  else GLARGS="detect --no-git --no-banner -s ."
    note "the git history (this tree is not a git repository — gitleaks ran in --no-git mode over working files only)"
  fi
  run "gitleaks (${GLARGS})"
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
else run "gitleaks UNAVAILABLE"; unavail=1
  note "secrets (gitleaks is not installed — NOTHING scanned this tree for credentials)"; fi

# ---- raw hex colours --------------------------------------------------------
# It was hard-scoped to `src app components` and silently no-opped when none existed,
# while still printing its header as though it had run: a violation in lib/ was invisible
# AND undeclared (law 6). Now it searches those dirs when they exist, otherwise the whole
# tree minus the usual noise, and says which happened either way.
HEXDIRS=""
for d in src app components; do [ -d "$d" ] && HEXDIRS="${HEXDIRS:+$HEXDIRS }$d"; done
if [ -z "$HEXDIRS" ]; then
  HEXDIRS="."
  run "grep: raw hex colors outside tokens — scope: whole tree (no src/ app/ components/ here)"
else
  run "grep: raw hex colors outside tokens — scope: $HEXDIRS"
fi
# shellcheck disable=SC2086  # HEXDIRS is a deliberate word list of directories
hexout=$(grep -rEn --include='*.tsx' --include='*.jsx' \
           --exclude-dir=node_modules --exclude-dir=.next --exclude-dir=dist \
           --exclude-dir=build --exclude-dir=out --exclude-dir=.git --exclude-dir=.proof-os \
           '#[0-9a-fA-F]{6}' $HEXDIRS 2>/dev/null | grep -v tokens)
if [ -n "$hexout" ]; then printf '%s\n' "$hexout" | head -10; fail=1; fi
note "hex colours outside .tsx/.jsx (css, scss, styled-components and inline strings elsewhere are not scanned)"

NOTCHECKED="${notes:+$notes; }runtime behaviour, visual regression, accessibility beyond whichever jsx-a11y rules loaded, bundle size, and whether the UI does what the user actually asked for"

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
