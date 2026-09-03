#!/usr/bin/env bash
# gates/e2e.sh — neha's oracle. A real browser, or honestly unavailable.
# origin: registry grants neha may_claim=proved; PROOFOS.md roadmap item 2.
# LAW (false-red): tool-cannot-run => exit 2. exit 1 ONLY for real findings. An
#      UNREADABLE tool result is an unavailable oracle, not a finding. Bad args = 64.
# LAW (F-0025): every step wall-clock bounded; over budget => unavailable, never a hang.
#      Override with PROOF_GATE_TIMEOUT (seconds per step, default 300).
# LAW (F-0026 / law 7): $SELF absolute before the cd, rc_init AFTER it, so liveness
#      lands in the PROJECT's .proof-os rather than the caller's.
# LAW (rule 5): prints what it could NOT check, on EVERY exit path.
# A URL is REQUIRED — an E2E gate with nothing deployed proves nothing.
# Usage: gates/e2e.sh [project_dir] <live_url>
set -u

SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || {
  echo "· cannot resolve gate directory — unavailable"; exit 2; }

NOTCHECKED="anything — the gate exited before it could name a blind spot"
_nc() { [ -n "${_NC_DONE:-}" ] || { _NC_DONE=1; printf 'NOT CHECKED: %s\n' "$NOTCHECKED"; }; }
trap _nc EXIT

if [ "$#" -ge 1 ] && [ -z "$1" ]; then
  NOTCHECKED="anything at all — the arguments were rejected before any oracle ran"
  echo "· empty project_dir argument — a verdict about the caller's cwd would be a lie"
  echo "usage: gates/e2e.sh [project_dir] <live_url>"
  exit 64
fi
# §1 usage: extra positional arguments must not be silently discarded.
if [ "$#" -gt 2 ]; then
  NOTCHECKED="anything at all — the arguments were rejected before any oracle ran"
  echo "· $# arguments given; this gate takes at most 2 — unrecognised: $(shift 2; echo "$*")"
  echo "usage: gates/e2e.sh [project_dir] <live_url>"
  exit 64
fi
PROJECT="${1:-.}"
NOTCHECKED="anything in '$PROJECT' — the directory could not be entered"
[ -d "$PROJECT" ] || { echo "· not a directory: $PROJECT — unavailable"; exit 2; }
cd "$PROJECT" || { echo "· project dir unreadable: $PROJECT — unavailable"; exit 2; }

. "$SELF/_rc.sh" 2>/dev/null || true
if type rc_init >/dev/null 2>&1; then rc_init e2e; fi

URL="${2:-}"
fail=0; unavail=0; ran=0
notes=""
note() { notes="${notes:+$notes; }$1"; }
TMO="${PROOF_GATE_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $TMO"
else TO=""; note "the time budget (no coreutils timeout on PATH — the browser ran unbounded)"; fi
timed_out() { [ "$1" -eq 124 ] || [ "$1" -eq 137 ]; }

# True of this gate on every path it can take: it opens ONE anonymous page and reads what
# the browser reports. It signs in to nothing and submits nothing.
JOURNEYS="authenticated flows (this gate never logs in), form submission, multi-step user journeys, anything behind a login or a paywall, payment paths, and any page other than the single URL given"

NOTCHECKED="$JOURNEYS"

if [ -z "$URL" ]; then
  NOTCHECKED="everything — no URL was given, so nothing deployed was contacted at all; $JOURNEYS"
  echo "· no URL given — E2E gate cannot run (unavailable, NOT a pass)"
  echo "VERDICT: partial — nothing deployed to test (believed, not proved)"; exit 2
fi

# ---- 1. reachability + status ----------------------------------------------
if command -v curl >/dev/null 2>&1; then
  echo "· GET $URL"; ran=1
  # mktemp, not a fixed /tmp/_e2e_body: the fixed path collided between concurrent runs
  # and was pre-creatable as a symlink by anyone on the box. The sibling browser script
  # three lines below already did this correctly.
  BODY=$(mktemp "${TMPDIR:-/tmp}/_e2e_body.XXXXXX") || {
    NOTCHECKED="everything — no temp file could be created; $JOURNEYS"
    echo "· cannot create temp file — unavailable"; exit 2; }
  code=$(curl -sL -o "$BODY" -w '%{http_code}' --max-time 20 "$URL" 2>/dev/null) || code="000"
  if [ "$code" = "000" ]; then
    echo "  unreachable — unavailable"; unavail=1; note "the page itself (it never answered)"
  elif [ "$code" -ge 400 ]; then
    echo "  HTTP $code — page does not serve"; fail=1
  else
    echo "  HTTP $code"
    # A BYTE COUNT IS NOT A DEFECT. "under 500 bytes" called a 28-byte valid HTML page, a
    # JSON health endpoint and a 301 target "broken" — a heuristic about size dressed as
    # evidence about correctness, and it alone could produce exit 1. It is now a declared
    # heuristic: the browser step below decides whether the page is actually dead
    # (zero buttons/links, page errors), and only that can be a finding.
    bytes=$(wc -c < "$BODY" 2>/dev/null || echo 0)
    if [ "$bytes" -lt 500 ]; then
      echo "  note: body is only ${bytes}B — small, but small is not broken; see the browser step"
      note "whether a ${bytes}B response body is a real page or an empty shell (size is a heuristic here, never a finding — only the browser step judges the page)"
    fi
  fi
  rm -f "$BODY"
else
  echo "· curl UNAVAILABLE"; unavail=1; note "reachability and status code (no curl)"
fi

# ---- 2. real browser: console errors + dead page ----------------------------
# THE BROWSER ORACLE COULD NEVER EXECUTE, ON ANY INSTALL LAYOUT.
# The script was written to $TMPDIR and did `import { chromium } from 'playwright'`, so
# ESM resolution walked up from /tmp — never the project — and died with
#     ERR_MODULE_NOT_FOUND: Cannot find package 'playwright' imported from /tmp/_e2e_x.mjs
# even with playwright installed and importable from the project. The `npm root -g`
# fallback set NODE_PATH, which the ESM loader IGNORES ENTIRELY, so it fixed nothing.
# The gate therefore reported "browser could not run — unavailable" on every machine and
# could never return 0. neha's registry ceiling of may_claim=proved rested on it.
#
# Two changes, belt and braces:
#   1. the ENTRY POINT is resolved to an absolute path by node's CJS resolver, run with
#      cwd = the project (which honours the project's node_modules AND, on the second
#      attempt, NODE_PATH for a global install), and the script requires THAT path;
#   2. the script is written INSIDE the project (.proof-os/tmp/), so even a bare
#      specifier would resolve against the project rather than /tmp. Cleaned up after.
PW_ENTRY=""; PW_HOW=""
if command -v node >/dev/null 2>&1; then
  PW_ENTRY=$(node -e "console.log(require.resolve('playwright'))" 2>/dev/null) && PW_HOW="project"
  if [ -z "$PW_ENTRY" ]; then
    _g=$(npm root -g 2>/dev/null) || _g=""
    if [ -n "$_g" ]; then
      PW_ENTRY=$(NODE_PATH="$_g" node -e "console.log(require.resolve('playwright'))" 2>/dev/null) \
        && PW_HOW="global install at $_g"
    fi
  fi
fi

BROWSER_SCRIPT=""; PW_TMPDIR=""
if [ -n "$PW_ENTRY" ]; then
  # .proof-os is used only if it ALREADY EXISTS. Creating it here would make a gate RUN
  # manufacture project state (contract §4, and _rc.sh's own "a read never makes state"),
  # which is how "store missing" became "store empty, exit 0" across the whole system.
  if [ -d ".proof-os" ] && mkdir -p ".proof-os/tmp" 2>/dev/null; then
    PW_TMPDIR="$(pwd)/.proof-os/tmp"
    BROWSER_SCRIPT=$(mktemp "$PW_TMPDIR/_e2e_XXXXXX.mjs" 2>/dev/null) || BROWSER_SCRIPT=""
  fi
  # else write it at the project root, which is still INSIDE the project for resolution.
  [ -n "$BROWSER_SCRIPT" ] || BROWSER_SCRIPT=$(mktemp "$(pwd)/._e2e_XXXXXX.mjs" 2>/dev/null) || BROWSER_SCRIPT=""
  # A read-only project is not a reason to fail: the absolute require below still works.
  [ -n "$BROWSER_SCRIPT" ] || BROWSER_SCRIPT=$(mktemp "${TMPDIR:-/tmp}/_e2e_XXXXXX.mjs" 2>/dev/null) || BROWSER_SCRIPT=""
fi

if [ -n "$BROWSER_SCRIPT" ]; then
cat > "$BROWSER_SCRIPT" <<'JS'
// createRequire + an ABSOLUTE entry path: no bare-specifier ESM resolution, so it does
// not matter which directory this file was written into, and NODE_PATH is honoured
// (the CJS resolver reads it; the ESM loader does not).
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)(process.env.PROOF_PW_ENTRY);
const url = process.argv[2];
const errs = [], reqfail = [];
const b = await chromium.launch();
const p = await (await b.newContext()).newPage();
p.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
p.on('pageerror', e => errs.push('pageerror: ' + String(e).slice(0, 200)));
p.on('requestfailed', r => reqfail.push(`${r.method()} ${r.url().slice(0,120)} — ${r.failure()?.errorText}`));
const resp = await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
await p.waitForTimeout(2500);
const imgs = await p.$$eval('img', els => els.filter(i => i.complete && i.naturalWidth === 0).map(i => i.src).slice(0, 10));
const btns = await p.$$eval('button, a[href]', els => els.length);
console.log(JSON.stringify({ status: resp?.status() ?? 0, errs, reqfail: reqfail.slice(0, 10), brokenImgs: imgs, interactive: btns }));
await b.close();
JS
fi

if [ -n "$BROWSER_SCRIPT" ]; then
  echo "· playwright: load, console errors, broken images ($PW_HOW: $PW_ENTRY)"; ran=1
  out=$(PROOF_PW_ENTRY="$PW_ENTRY" $TO node "$BROWSER_SCRIPT" "$URL" 2>&1); rc=$?
  if timed_out $rc; then
    # `out` MUST be cleared with rc. Leaving the truncated transcript in place sent
    # control into the parse branch, the parser said "unparseable" and exited 2, and
    # that 2 was converted to fail=1 — a healthy page reported as broken by a slow
    # browser. The suite step twelve lines down always did clear it.
    echo "  browser exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; out=""
    note "console errors, broken images and failed requests (the browser timed out)"
  fi
  if [ $rc -ne 0 ]; then
    # Print WHY. The old silent "browser could not run" hid a permanent
    # ERR_MODULE_NOT_FOUND behind a message that read like a missing browser binary, so
    # a bug that made the oracle impossible looked like an ordinary absent tool.
    printf '%s\n' "$out" | grep -vE '^\s*$' | head -4 | sed 's/^/    /'
    if printf '%s\n' "$out" | grep -qE 'ERR_MODULE_NOT_FOUND|Cannot find module|Cannot find package'; then
      echo "  playwright resolved to $PW_ENTRY but node could not load it — unavailable"
      note "console errors, broken images and failed requests (the playwright module could not be loaded)"
    elif printf '%s\n' "$out" | grep -qiE "Executable doesn.t exist|browserType.launch.*Executable|playwright install"; then
      echo "  the playwright MODULE loaded, but no browser binary is installed "
      echo "  (npx playwright install chromium) — unavailable, NOT a finding about the site"
      note "console errors, broken images and failed requests (the playwright module loaded but no browser binary is installed on this machine)"
    else
      echo "  browser could not run (exit $rc) — unavailable"
      note "console errors, broken images and failed requests (the browser could not run)"
    fi
    unavail=1
  elif [ -z "$out" ]; then
    :   # nothing to parse: already counted as unavailable above
  else
    printf '%s\n' "$out" | node -e '
      let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{
        let r; try { r=JSON.parse(s.trim().split("\n").pop()); } catch { console.log("  unparseable"); process.exit(2); }
        let bad=0;
        if (r.errs.length){ console.log("  console errors: "+r.errs.length); r.errs.slice(0,5).forEach(e=>console.log("    "+e)); bad=1; }
        if (r.brokenImgs.length){ console.log("  broken images: "+r.brokenImgs.length); bad=1; }
        if (r.reqfail.length){ console.log("  failed requests: "+r.reqfail.length); r.reqfail.slice(0,5).forEach(e=>console.log("    "+e)); bad=1; }
        if (r.interactive === 0){ console.log("  zero buttons/links — page is not interactive"); bad=1; }
        process.exit(bad);
      });'
    prc=$?
    # 2 = the parser could not read the browser output. An unreadable tool result is an
    # UNAVAILABLE oracle, not a defect in the site (§1 false-red law).
    if [ $prc -eq 2 ]; then
      echo "  browser output unreadable — unavailable, NOT a finding"; unavail=1
      note "console errors, broken images and failed requests (the browser output could not be parsed)"
    elif [ $prc -ne 0 ]; then fail=1; fi
  fi
elif [ -z "$PW_ENTRY" ]; then
  echo "· playwright UNAVAILABLE (node missing, or playwright resolves from neither this project nor the global root)"; unavail=1
  note "everything a browser sees: console errors, page errors, broken images, failed requests, interactivity"
else
  echo "· playwright is installed at $PW_ENTRY but no temp file could be written — unavailable"; unavail=1
  note "everything a browser sees (the runner script could not be written anywhere)"
fi
[ -n "$BROWSER_SCRIPT" ] && rm -f "$BROWSER_SCRIPT" 2>/dev/null
[ -n "$PW_TMPDIR" ] && rmdir "$PW_TMPDIR" 2>/dev/null
true

# ---- 3. project e2e suite ---------------------------------------------------
if [ -f playwright.config.ts ] && [ -d node_modules ]; then
  echo "· npx playwright test"; ran=1
  out=$($TO npx --no-install playwright test --reporter=line 2>&1); rc=$?
  if timed_out $rc; then echo "  suite exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; out=""
    note "the project's own e2e suite (it timed out)"; fi
  if [ $rc -ne 0 ]; then
    if echo "$out" | grep -qiE 'Cannot find module|not found|no tests found|Executable doesn.t exist'; then
      echo "  suite could not run — unavailable"; unavail=1
      note "the project's own e2e suite (it could not run)"
    else echo "$out" | tail -20; fail=1; fi
  fi
else
  echo "· no playwright.config.ts or node_modules — suite unavailable"; unavail=1
  note "the project's own e2e suite (no playwright.config.ts or no node_modules)"
fi

NOTCHECKED="${notes:+$notes; }$JOURNEYS"

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $ran -eq 0 ] && { echo "VERDICT: partial — nothing actually ran (believed, not proved)"; exit 2; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
