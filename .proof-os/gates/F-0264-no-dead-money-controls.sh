#!/usr/bin/env bash
# F-0264-no-dead-money-controls.sh — gate for F-0264 (dead-controls-money-surface)
# and for F-0296 (gate-window-grep-admits-wrong-fix), the record opened against THIS
# FILE when its first version greened the plausible wrong fix. F-0296 is closed by the
# hardening below — the unconditional-disabled check and the wired-in vitest leg — and
# the two records share this gate because the artifact F-0296 repairs is the gate itself.
#
# Wallet Export, Download Form 16A and Download GST Summary render as live, clickable
# controls on a money/tax-compliance surface (src/pages/brand-wallet.tsx) with no onClick
# handler and no backend endpoint behind them. A brand who clicks "Download Form 16A" and
# nothing happens reads that as "my tax document is missing", not "this feature doesn't
# exist yet" — silence on a compliance surface is the harm, not merely a dead button.
#
# LOOKUP (T-BRANDMED-0817) found no export/Form16A/GST-summary endpoint in src/lib/api.ts,
# src/lib/meera-api.ts, or influora-api/src/main/java/com/influora/web/WalletController.java
# (the only wallet controller; it exposes /balance, GET /wallet, POST /wallet/topup,
# POST /wallet/withdraw, GET /wallet/transactions, GET /wallet/payouts, /payout-methods —
# nothing else). No InvoiceService/GstSplitUtil path generates a brand-facing Form 16A or
# GST summary either. So the fix for all three is case (b): disable the control and say why
# in text a screen reader / hover can reach — not wire a fake client-side export.
#
# Each control must be BOTH:
#   1. unconditionally disabled (the bare `disabled` attribute, or `disabled={true}` / a
#      literal-true expression — no click can EVER fire it, in any component state), AND
#   2. carry a human-readable reason at the same source location (Tooltip/title text that is
#      not just the control's own name — "Download Form 16A" restated back is not a reason).
# A control satisfying only one of these is still the defect: disabled-with-no-explanation
# reads as broken; explained-but-still-clickable still silently does nothing on click.
#
# F-0296 (T-BRANDMED-0817 residual, ledger.jsonl) — the original version of this gate grepped
# the window for the bare token `disabled`, which a *conditional* `disabled={loading}` also
# contains. The pre-fix Export button was already `disabled={loading}` and satisfied that leg
# without ever fixing anything: once `loading` goes false the control becomes a live, clickable
# no-op again — the exact defect this gate exists to catch. The check below requires the
# `disabled` attribute to carry no state-dependent expression at all.
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

WALLET=src/pages/brand-wallet.tsx
[ -f "$WALLET" ] || { echo "· $WALLET missing — unavailable"; exit 2; }
WALLET_CODE=$(code_view "$WALLET") || { echo "$(code_why) - unavailable"; exit 2; }

fail=0

# Pulls the JSX block for a named control. F-0296-hardening #2 (T-BRANDMED-0817): the original
# version anchored on a fixed line-count window (back N / forward M lines from the label). The
# Export control's reason sat at exactly label+4 lines — any reflow of the <Button> onto more
# lines (a wrapped className, an added prop) pushes the reason out of the window and this gate
# reports "no human-readable reason" against a tree that is still correct. Anchor to the
# ENCLOSING <Tooltip>...</Tooltip> element instead (the pattern all three controls use to carry
# their disabled-reason text) by balancing open/close tags outward from the label line, so the
# block always contains the whole control regardless of how many lines it wraps onto. Fall back
# to a generous fixed window only when no enclosing Tooltip is found nearby (a different pattern
# entirely), so the check still degrades gracefully instead of going unavailable.
find_enclosing_tooltip() {
  local file="$1" line="$2"
  awk -v target="$line" '
    { lines[NR] = $0 }
    END {
      start = 0
      for (i = target; i >= 1 && i >= target - 40; i--) {
        if (lines[i] ~ /<Tooltip(>| )/) { start = i; break }
      }
      if (start == 0) { exit }
      depth = 0
      end = 0
      for (i = start; i <= NR && i <= target + 60; i++) {
        if (lines[i] ~ /<Tooltip(>| )/) depth++
        if (lines[i] ~ /<\/Tooltip>/) { depth--; if (depth <= 0) { end = i; break } }
      }
      if (end == 0) { exit }
      for (i = start; i <= end; i++) print lines[i]
    }
  ' "$file"
}

check_control() {
  local name="$1" label="$2" fallback_back="$3" fallback_fwd="$4"
  local line
  line=$(grep -n -F "$label" "$WALLET_CODE" | head -1 | cut -d: -f1)
  if [ -z "$line" ]; then
    echo "· $name: label '$label' not found in $WALLET — unavailable"
    return 2
  fi
  local block
  block=$(find_enclosing_tooltip "$WALLET" "$line")
  if [ -z "$block" ]; then
    # Fallback: no enclosing <Tooltip> found near the label — use a widened fixed window rather
    # than the original tight one, so a modest reflow still doesn't false-fail.
    local start=$((line - fallback_back - 10))
    [ "$start" -lt 1 ] && start=1
    local end=$((line + fallback_fwd + 15))
    block=$(sed -n "${start},${end}p" "$WALLET_CODE")
    echo "  (no enclosing <Tooltip> found — fell back to a widened line window $start-$end)"
  fi

  echo "· $name (near $WALLET:$line): disabled + reason, not a silent live control"

  # Strip aria-disabled="..." first — that's a separate accessibility-hint attribute, not the
  # `disabled` prop this check cares about, and its own value also contains the literal
  # substring "disabled" (inside "aria-disabled") which would otherwise self-satisfy the match.
  local block_clean
  block_clean=$(printf '%s\n' "$block" | sed -E 's/aria-disabled="[^"]*"//g')

  local disabled_matches
  disabled_matches=$(printf '%s\n' "$block_clean" | grep -oE '\bdisabled\b(=\{[^}]*\})?')

  if [ -z "$disabled_matches" ]; then
    echo "  no 'disabled' on the control block"
    echo "VERDICT: broken — $name renders as a clickable control with no handler and no"
    echo "         endpoint behind it (F-0264); a click on a money/tax surface silently"
    echo "         does nothing"
    fail=1
    return 1
  fi

  # F-0296 hardening: `disabled={<something other than the literal true>}` is a CONDITIONAL
  # disable — e.g. `disabled={loading}` reads as satisfied by a naive `disabled` grep but
  # leaves the control clickable the instant `loading` flips false. Only a bare `disabled` or
  # `disabled={true}` counts as unconditional.
  local bad_conditional
  bad_conditional=$(printf '%s\n' "$disabled_matches" | grep -E '=\{' | grep -vE '^disabled=\{true\}$')
  if [ -n "$bad_conditional" ]; then
    echo "  'disabled' is conditional, not unconditional: $(printf '%s' "$bad_conditional" | tr '\n' ' ')"
    echo "VERDICT: broken — $name's disabled state depends on a variable (F-0296 hardening of"
    echo "         F-0264); the control becomes a live, silent no-op again as soon as that"
    echo "         condition changes — disabled must not depend on component state"
    fail=1
    return 1
  fi

  # Reason text must exist in the block and must not simply be the control's own label
  # restated (e.g. TooltipContent that just says "Download Form 16A" again explains nothing).
  local reason_lines
  reason_lines=$(printf '%s\n' "$block" | grep -iE "coming soon|not (yet )?available|isn.t available|not yet supported|unavailable")
  if [ -z "$reason_lines" ]; then
    echo "  no human-readable reason found near the control"
    echo "VERDICT: broken — $name is disabled but gives the brand no reason why (F-0264);"
    echo "         a silently-disabled control on a tax/money surface still reads as broken"
    fail=1
    return 1
  fi

  echo "  clean — disabled, with a readable reason"
  return 0
}

check_control "Export"                "Export"                 6  4
check_control "Download Form 16A"      "Download Form 16A"      10 4
check_control "Download GST Summary"   "Download GST Summary"   10 4

# Fail fast on the static checks before touching vitest — if the source shape already proves
# a control is broken, that verdict doesn't depend on whether npx/vitest happen to be
# available in this environment.
if [ "$fail" -ne 0 ]; then
  exit 1
fi

# F-0296 — this gate previously ran no vitest leg at all, even though
# src/pages/__tests__/brand-wallet.dead-controls.test.tsx already exists and asserts the actual
# DOM contract (aria-disabled, accessible name carrying the reason) that static grep on JSX
# source can't see — e.g. that `disabled` really does reach the rendered `<button>` element
# after React/Radix apply it, not just that the token appears near the label in source.
#
# T-BRANDMED-0817 (F-0296 hardening #3): this leg used to call `npx --yes vitest`, unlike every
# sibling gate (F-0223, F-0226, ...) which calls `node_modules/.bin/vitest` directly. Offline,
# `npx --yes` tries to resolve/fetch a package and surfaces as a vitest FAILURE (wrong verdict —
# looks like the suite is red) rather than `unavailable` (the true state — the tool couldn't run
# at all). Matched to the sibling pattern, plus a bounded timeout so a hung resolve can't hang
# this gate either.
TEST_FILE=src/pages/__tests__/brand-wallet.dead-controls.test.tsx
if [ -f "$TEST_FILE" ]; then
  command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
  if [ -f node_modules/.bin/vitest ]; then
    BUDGET="${PROOF_F0264_VITEST_TIMEOUT:-120}"
    if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
    echo "· running $TEST_FILE (vitest leg, budget ${BUDGET}s)"
    vitest_log=$(mktemp 2>/dev/null || echo "./.f0264-vitest.log")
    $TO node_modules/.bin/vitest run "$TEST_FILE" --reporter=default >"$vitest_log" 2>&1
    rc=$?
    if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
      echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
      rm -f "$vitest_log" 2>/dev/null || true
      exit 2
    elif [ $rc -eq 0 ]; then
      echo "  vitest suite passed"
    else
      echo "  vitest suite FAILED:"
      sed 's/^/  | /' "$vitest_log"
      echo "VERDICT: broken — $TEST_FILE does not pass (F-0264/F-0296); the DOM-level contract"
      echo "         this gate claims to prove is not actually verified"
      fail=1
    fi
    rm -f "$vitest_log" 2>/dev/null || true
  else
    echo "  vitest not installed (--no-install) — cannot run the vitest leg — unavailable"
    echo "VERDICT: unavailable — cannot confirm the DOM-level contract without vitest"
    exit 2
  fi
else
  echo "· $TEST_FILE missing — unavailable"
  echo "VERDICT: unavailable — the vitest leg this gate wires in does not exist"
  exit 2
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "VERDICT: aligned (proved) — Export, Form 16A and GST Summary are unconditionally disabled,"
echo "         each carries a reason the brand can actually read, and"
echo "         brand-wallet.dead-controls.test.tsx passes against the real rendered DOM"
echo "NOT CHECKED: the deal-room 'attach' (Paperclip) control and 'Continue Chat' button"
echo "             (src/components/brand/deals/deal-room-dashboard.tsx) — same defect class,"
echo "             different file, out of this gate's scope (F-0264 handoff); the deliverable"
echo "             'Preview' control actually lives in"
echo "             src/components/brand/contracts/contracts-and-deliverables.tsx:1326, not"
echo "             under src/components/brand/deliverables/ as assigned, and that file has an"
echo "             in-flight concurrent edit — also handoff, not covered here. Screen-reader"
echo "             behavior of the Tooltip-on-disabled-button pattern (pointer-events-none can"
echo "             suppress hover on the inner element) is asserted by source shape, not by an"
echo "             actual assistive-tech run."
exit 0
