#!/usr/bin/env bash
# gates/F-0324-wallet-load-states-distinct.sh — gate for F-0324 (wallet-has-no-loading-affordance).
#
# THE DEFECT. src/pages/brand-wallet.tsx fetched its summary, transactions, escrow holdings and
# fundable campaigns on mount with NO loading affordance at all. A slow or failing fetch was
# indistinguishable from a genuinely empty wallet — on a money surface, where "₹0" and "we
# haven't loaded your balance yet" mean completely different things. The file used to hold a
# `loading` state written on both edges of `loadWallet` and READ BY NOTHING — removed while
# fixing an eslint violation, and its absence was the actual finding. So the shape to catch is
# not "no loading state exists" (that's too easy to satisfy with a token search) but "a loading
# state exists and is SET, and nothing on screen changes because of it."
#
# WHAT THIS GATE ASSERTS, and why each leg is behavioural rather than a token search.
#   1. SELF-FALSIFICATION (F-0329 house rule; F-0273/F-0281 are the reference shape). A gate
#      that only checks "does a symbol named walletStatus appear somewhere" is satisfied by a
#      status variable that is declared, set, and never read — exactly the F-0324 defect. So
#      this gate does not trust its own vitest suite until it has watched that suite REJECT
#      that precise shape: it swaps a frozen, verbatim copy of the ORIGINAL (pre-fix) Balance
#      Cards block — the unconditional grid this ticket's fix replaced — back onto disk between
#      the GATE-F0324-CARDS-START/END markers, and runs the real regression spec
#      (brand-wallet.load-states.test.tsx) against it. That spec MUST fail against the known-bad
#      block. If it does not, this gate refuses to report anything about the real code. The real
#      file is restored via a trap no matter how this script exits.
#   2. STRUCTURAL: the per-region status machinery and the shared error component still exist
#      in the real file (mirrors F-0245's gate leg for dashboard-page.tsx).
#   3. THE REAL CODE, same spec, against whatever is actually on disk once the self-check has
#      restored it. This is the leg that fails on the pre-fix tree and passes after the fix.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

FE_FILE=src/pages/brand-wallet.tsx
FE_TEST=src/pages/__tests__/brand-wallet.load-states.test.tsx
SHARED=src/components/shared/DashboardCardError.tsx
DASH=src/components/brand/dashboard/dashboard-page.tsx
for f in "$FE_FILE" "$FE_TEST" "$SHARED" "$DASH"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

PY=""
for c in "${PROOF_PYTHON:-}" python3 python py; do
  [ -n "$c" ] || continue
  command -v "$c" >/dev/null 2>&1 || continue
  "$c" -c "import sys" >/dev/null 2>&1 && { PY="$c"; break; }
done
[ -n "$PY" ] || { echo "· no working python interpreter on PATH — unavailable"; exit 2; }

if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"; exit 2
fi

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
cp "$FE_FILE" "$WORK/brand-wallet.tsx.orig" || { echo "· cannot back up $FE_FILE — unavailable"; rm -rf "$WORK"; exit 2; }
# Restored no matter how this script exits — the self-falsification step below deliberately
# breaks the real file on disk to prove the spec notices.
trap 'cp "$WORK/brand-wallet.tsx.orig" "$FE_FILE" 2>/dev/null; rm -rf "$WORK" 2>/dev/null' EXIT

echo "· markers: GATE-F0324-CARDS-START/END present in $FE_FILE"
if ! grep -q "GATE-F0324-CARDS-START" "$FE_FILE" || ! grep -q "GATE-F0324-CARDS-END" "$FE_FILE"; then
  echo "VERDICT: broken — the anchors this gate uses to swap in a known-bad Balance Cards block"
  echo "         are gone; self-falsification cannot run, so nothing below can be trusted (F-0324)"
  exit 1
fi
echo "  present"

# ---------------------------------------------------------------------------
# The frozen known-bad body — VERBATIM the pre-fix Balance Cards block (git history, this
# gate's own preamble): an unconditional grid reading wallet.balance/escrowLocked/
# pendingSettlement/runwayDays with NO reference to walletStatus at all. walletStatus is still
# declared and set elsewhere in the file (loadWallet is untouched by this swap) — this is
# exactly the F-0324 shape: a status variable that exists and is written, and changes nothing
# on screen.
# ---------------------------------------------------------------------------
cat > "$WORK/bad_block.txt" <<'TSXEOF'
        {/* GATE-F0324-CARDS-START */}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {/* Available Balance */}
          <Card className="relative overflow-hidden">
            <div className="absolute inset-0 bg-gradient-to-br from-primary/10 via-transparent to-transparent" />
            <CardHeader className="relative pb-2">
              <CardDescription className="flex items-center justify-between">
                <span>Available Balance</span>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6"
                  onClick={() => setIsBalanceVisible(!isBalanceVisible)}
                >
                  {isBalanceVisible ? (
                    <Eye className="h-4 w-4" />
                  ) : (
                    <EyeOff className="h-4 w-4" />
                  )}
                </Button>
              </CardDescription>
              <CardTitle className="text-3xl">
                {isBalanceVisible ? formatCurrency(wallet.balance) : '********'}
              </CardTitle>
            </CardHeader>
            <CardContent className="relative">
              <p className="text-sm text-muted-foreground">
                {wallet.lastRechargeAmount != null && wallet.lastRecharge
                  ? <>Last recharge: {formatCurrency(wallet.lastRechargeAmount)} on{' '}
                    {formatDate(wallet.lastRecharge)}</>
                  : 'No recharge yet'}
              </p>
            </CardContent>
          </Card>

          {/* Escrow Locked */}
          <Card>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <Lock className="h-3.5 w-3.5" />
                Escrow Locked
              </CardDescription>
              <CardTitle className="text-2xl text-amber-500">
                {formatCurrency(wallet.escrowLocked)}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">
                {escrowActiveCount} active campaigns
              </p>
            </CardContent>
          </Card>

          {/* Pending Settlement */}
          <Card>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <Clock className="h-3.5 w-3.5" />
                Pending Settlement
              </CardDescription>
              <CardTitle className="text-2xl text-blue-500">
                {formatCurrency(wallet.pendingSettlement)}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">Processing payouts</p>
            </CardContent>
          </Card>

          {/* 30-Day Runway Projection */}
          <Card className={cn(
            'border-2',
            runwayTone === 'critical' ? 'border-red-300 bg-red-50/30' :
            runwayTone === 'warning' ? 'border-amber-300 bg-amber-50/30' :
            'border-green-300 bg-green-50/30'
          )}>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <TrendingUp className="h-3.5 w-3.5" />
                Runway Projection
              </CardDescription>
              <CardTitle className={cn(
                'text-2xl',
                runwayTone === 'critical' ? 'text-stage-disputed-fg' :
                runwayTone === 'warning' ? 'text-stage-negotiating-fg' :
                'text-stage-approved-fg'
              )}>
                {runwayDays != null ? `${runwayDays} days` : 'Healthy'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <Progress
                value={runwayDays != null ? Math.min((runwayDays / 60) * 100, 100) : 100}
                className={cn(
                  'h-2 mb-2',
                  runwayTone === 'critical' && '[&>div]:bg-red-500',
                  runwayTone === 'warning' && '[&>div]:bg-amber-500',
                  runwayTone === 'healthy' && '[&>div]:bg-green-500'
                )}
              />
              <p className="text-xs text-muted-foreground">
                Burn rate: {wallet.projectedBurn30Days != null
                  ? `${formatCurrency(wallet.projectedBurn30Days)}/mo`
                  : 'Not enough data yet'}
              </p>
            </CardContent>
          </Card>
        </div>
        {/* GATE-F0324-CARDS-END */}
TSXEOF

swap_block() {
  # $1 = replacement body file (must itself contain the START/END marker lines)
  "$PY" - "$FE_FILE" "$1" <<'PYEOF'
import sys
path, body_path = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    lines = f.readlines()
with open(body_path, encoding="utf-8") as f:
    body = f.read()
start = end = None
for i, line in enumerate(lines):
    if "GATE-F0324-CARDS-START" in line:
        start = i
    if "GATE-F0324-CARDS-END" in line:
        end = i
        break
if start is None or end is None or end < start:
    sys.exit("markers not found in order")
new_lines = lines[:start] + [body] + lines[end + 1:]
with open(path, "w", encoding="utf-8") as f:
    f.writelines(new_lines)
PYEOF
}

BUDGET="${PROOF_F0324_VITEST_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

# ---------------------------------------------------------------------------
# 1 · SELF-FALSIFICATION — swap the known-bad (pre-fix, unconditional) Balance Cards block onto
#     disk and run the REAL regression spec against it. It MUST fail.
# ---------------------------------------------------------------------------
echo "· self-check: known-bad Balance Cards block (verbatim pre-fix F-0324) is rejected by the spec"
swap_block "$WORK/bad_block.txt" || { echo "· could not write the known-bad block — unavailable"; exit 2; }
# Scoped to JUST the swapped region — walletStatus legitimately appears elsewhere in the file
# (Transactions tab, Payouts tab, the escrow-count subtext), so a whole-file grep would false-
# positive "swap did not take" against a partial/wrong fix that left those other regions intact.
swapped_region=$(awk '/GATE-F0324-CARDS-START/{f=1} f{print} /GATE-F0324-CARDS-END/{exit}' "$FE_FILE")
if printf '%s' "$swapped_region" | grep -q "walletStatus === 'loading'"; then
  echo "· known-bad swap did not take — unavailable"; exit 2
fi
bad_out=$($TO node_modules/.bin/vitest run "$FE_TEST" --reporter=basic 2>&1)
bad_rc=$?
if [ $bad_rc -eq 124 ] || [ $bad_rc -eq 137 ]; then
  printf '%s\n' "$bad_out" | tail -20
  echo "· self-check exceeded ${BUDGET}s — unavailable, NOT a finding"
  exit 2
fi
if [ $bad_rc -eq 0 ]; then
  printf '%s\n' "$bad_out" | tail -30
  echo "· THIS GATE CANNOT FAIL: brand-wallet.load-states.test.tsx PASSED against a Balance Cards"
  echo "  block that ignores walletStatus entirely — the verbatim pre-fix F-0324 defect (a status"
  echo "  variable that is set and never read). Refusing to report a verdict about the real code"
  echo "  from a spec that has just proved itself blind."
  echo "VERDICT: broken — this gate's own spec no longer detects F-0324"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — brand-wallet.load-states.test.tsx fails against the known-bad block (rc=$bad_rc), as required"

# Restore the real file before checking it — the trap would also do this on exit, but the next
# leg needs the real file back NOW, not at process exit.
cp "$WORK/brand-wallet.tsx.orig" "$FE_FILE" || { echo "· could not restore $FE_FILE — unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# 2 · STRUCTURAL — the per-region status machinery and the shared error component still exist.
#     Mirrors F-0245's gate leg for dashboard-page.tsx; behavioural leg 3 below is what actually
#     proves the shape is wired up, this just catches a missing symbol quickly.
# ---------------------------------------------------------------------------
echo "· per-region status machinery present in $FE_FILE"
FE_CODE=$(code_view "$FE_FILE") || { echo "$(code_why) - unavailable"; exit 2; }
missing=""
for sym in "type LoadStatus" walletStatus escrowStatus fundableCampaignsStatus DashboardCardError loadWallet loadEscrow loadFundableCampaigns; do
  grep -q "$sym" "$FE_CODE" || missing="$missing [$sym]"
done
if [ -n "$missing" ]; then
  echo "  missing:$missing"
  echo "VERDICT: broken — the wallet's three-state machinery is incomplete (F-0324); an"
  echo "         unresolved or failed fetch may render as a factual zero again"
  exit 1
fi
echo "  clean"

SHARED_CODE=$(code_view "$SHARED") || { echo "$(code_why) - unavailable"; exit 2; }
grep -q "export function DashboardCardError" "$SHARED_CODE" || {
  echo "VERDICT: broken — src/components/shared/DashboardCardError.tsx no longer exports"
  echo "         DashboardCardError (F-0324)"
  exit 1
}
DASH_CODE=$(code_view "$DASH") || { echo "$(code_why) - unavailable"; exit 2; }
grep -q "@/components/shared/DashboardCardError" "$DASH_CODE" || {
  echo "VERDICT: broken — dashboard-page.tsx no longer imports the shared DashboardCardError;"
  echo "         either it reverted to a local copy (drift risk) or lost the error state (F-0324)"
  exit 1
}
echo "  dashboard-page.tsx and brand-wallet.tsx share one DashboardCardError implementation"

# ---------------------------------------------------------------------------
# 3 · THE REAL CODE — same spec, against whatever is actually on disk now that it's restored.
# ---------------------------------------------------------------------------
echo "· vitest: $FE_TEST (real code on disk)"
real_out=$($TO node_modules/.bin/vitest run "$FE_TEST" --reporter=basic 2>&1)
real_rc=$?
if [ $real_rc -eq 124 ] || [ $real_rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  exit 2
fi
if [ $real_rc -ne 0 ]; then
  printf '%s\n' "$real_out" | tail -60
  echo "VERDICT: broken — the brand wallet does not honestly distinguish loading, error and"
  echo "         genuinely-empty on at least one of its three data regions (F-0324)"
  exit 1
fi
printf '%s\n' "$real_out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — brand-wallet.tsx tracks walletStatus/escrowStatus/"
echo "         fundableCampaignsStatus independently per region, all three render visually"
echo "         distinct loading/error(+retry)/ready states, and the spec's own assertion table"
echo "         was proved falsifiable — it was watched REJECT the verbatim pre-fix Balance Cards"
echo "         block (a status variable set and never read) — before any green was trusted."
echo "         dashboard-page.tsx and brand-wallet.tsx now share one DashboardCardError"
echo "         implementation instead of two hand-rolled copies."
echo "NOT CHECKED: live rendering against a real backend; the self-falsification swap covers only"
echo "             the Balance Cards region (loadWallet) — the escrow and fundable-campaigns"
echo "             regions are proved by the same spec's real-code leg, not by their own swapped-"
echo "             known-bad run; whether a future caller re-derives wallet.* from walletSummary"
echo "             in a NEW render path that forgets to gate on walletStatus (this gate only"
echo "             watches the Balance Cards block between the GATE-F0324-CARDS markers); the"
echo "             Tax Summary cards, which render null/em-dash unconditionally by design (no"
echo "             backend source yet, per B42) and are correctly out of this gate's scope."
exit 0
