#!/usr/bin/env bash
# gates/e2e.sh — neha's oracle. A real browser, or honestly unavailable.
# origin: registry grants neha may_claim=proved; PROOFOS.md roadmap item 2.
# LAW (false-red): tool-cannot-run => exit 2. exit 1 ONLY for real findings.
# LAW (F-0025): every step wall-clock bounded; over budget => unavailable, never a hang.
#      Override with PROOF_GATE_TIMEOUT (seconds per step, default 300).
# A URL is REQUIRED — an E2E gate with nothing deployed proves nothing.
# Usage: gates/e2e.sh [project_dir] <live_url>
set -u
cd "${1:-.}" || { echo "· project dir unreadable — unavailable"; exit 2; }
URL="${2:-}"
fail=0; unavail=0; ran=0
TMO="${PROOF_GATE_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $TMO"; else TO=""; fi
timed_out() { [ "$1" -eq 124 ] || [ "$1" -eq 137 ]; }

if [ -z "$URL" ]; then
  echo "· no URL given — E2E gate cannot run (unavailable, NOT a pass)"
  echo "VERDICT: partial — nothing deployed to test (believed, not proved)"; exit 2
fi

# ---- 1. reachability + status ----------------------------------------------
if command -v curl >/dev/null 2>&1; then
  echo "· GET $URL"; ran=1
  code=$(curl -sL -o /tmp/_e2e_body -w '%{http_code}' --max-time 20 "$URL" 2>/dev/null) || code="000"
  if [ "$code" = "000" ]; then
    echo "  unreachable — unavailable"; unavail=1
  elif [ "$code" -ge 400 ]; then
    echo "  HTTP $code — page does not serve"; fail=1
  else
    echo "  HTTP $code"
    bytes=$(wc -c < /tmp/_e2e_body 2>/dev/null || echo 0)
    if [ "$bytes" -lt 500 ]; then echo "  body only ${bytes}B — shell with no content"; fail=1; fi
  fi
else
  echo "· curl UNAVAILABLE"; unavail=1
fi

# ---- 2. real browser: console errors + dead page ----------------------------
BROWSER_SCRIPT=$(mktemp /tmp/_e2e_XXXX.mjs)
cat > "$BROWSER_SCRIPT" <<'JS'
import { chromium } from 'playwright';
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

if command -v node >/dev/null 2>&1 && node -e "import('playwright')" >/dev/null 2>&1; then
  echo "· playwright: load, console errors, broken images"; ran=1
  out=$($TO node "$BROWSER_SCRIPT" "$URL" 2>&1); rc=$?
  if timed_out $rc; then echo "  browser exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; fi
  if [ $rc -ne 0 ]; then
    echo "  browser could not run — unavailable"; unavail=1
  else
    echo "$out" | node -e '
      let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{
        let r; try { r=JSON.parse(s.trim().split("\n").pop()); } catch { console.log("  unparseable"); process.exit(2); }
        let bad=0;
        if (r.errs.length){ console.log("  console errors: "+r.errs.length); r.errs.slice(0,5).forEach(e=>console.log("    "+e)); bad=1; }
        if (r.brokenImgs.length){ console.log("  broken images: "+r.brokenImgs.length); bad=1; }
        if (r.reqfail.length){ console.log("  failed requests: "+r.reqfail.length); r.reqfail.slice(0,5).forEach(e=>console.log("    "+e)); bad=1; }
        if (r.interactive === 0){ console.log("  zero buttons/links — page is not interactive"); bad=1; }
        process.exit(bad);
      });'
    [ $? -ne 0 ] && fail=1
  fi
else
  echo "· playwright UNAVAILABLE (node or playwright missing)"; unavail=1
fi
rm -f "$BROWSER_SCRIPT" /tmp/_e2e_body 2>/dev/null

# ---- 3. project e2e suite ---------------------------------------------------
if [ -f playwright.config.ts ] && [ -d node_modules ]; then
  echo "· npx playwright test"; ran=1
  out=$($TO npx --no-install playwright test --reporter=line 2>&1); rc=$?
  if timed_out $rc; then echo "  suite exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; out=""; fi
  if [ $rc -ne 0 ]; then
    if echo "$out" | grep -qiE 'Cannot find module|not found|no tests found|Executable doesn.t exist'; then
      echo "  suite could not run — unavailable"; unavail=1
    else echo "$out" | tail -20; fail=1; fi
  fi
else
  echo "· no playwright.config.ts or node_modules — suite unavailable"; unavail=1
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $ran -eq 0 ] && { echo "VERDICT: partial — nothing actually ran (believed, not proved)"; exit 2; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
