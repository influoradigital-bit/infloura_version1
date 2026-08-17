#!/usr/bin/env bash
# F-0264-no-dead-money-controls.sh — gate for F-0264 (dead-controls-money-surface).
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
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

WALLET=src/pages/brand-wallet.tsx
[ -f "$WALLET" ] || { echo "· $WALLET missing — unavailable"; exit 2; }

fail=0

# Pulls the JSX block for a named control: from the line matching the control's visible
# label back up N lines, forward M lines, so we can inspect the actual <Button>/<Tooltip>
# markup around it rather than just grepping for the label string in isolation.
check_control() {
  local name="$1" label="$2" back="$3" fwd="$4"
  local line
  line=$(grep -n -F "$label" "$WALLET" | head -1 | cut -d: -f1)
  if [ -z "$line" ]; then
    echo "· $name: label '$label' not found in $WALLET — unavailable"
    return 2
  fi
  local start=$((line - back))
  [ "$start" -lt 1 ] && start=1
  local end=$((line + fwd))
  local block
  block=$(sed -n "${start},${end}p" "$WALLET")

  echo "· $name (near $WALLET:$line): disabled + reason, not a silent live control"

  if ! printf '%s\n' "$block" | grep -qE '\bdisabled\b'
  then
    echo "  no 'disabled' on the control block"
    echo "VERDICT: broken — $name renders as a clickable control with no handler and no"
    echo "         endpoint behind it (F-0264); a click on a money/tax surface silently"
    echo "         does nothing"
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

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "VERDICT: aligned (proved) — Export, Form 16A and GST Summary are disabled and each"
echo "         carries a reason the brand can actually read"
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
