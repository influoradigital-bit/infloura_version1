#!/usr/bin/env bash
# gates/doc-stale-doc.sh — CLASS gate for doc-stale-doc.
# Closes F-0546, F-0547, F-0552, F-0555, F-0556, F-0557, F-0558, F-0562, F-0566.
#
# THIS CLASS IS THE SAME DEFECT AS doc-stale-doc-claim, RECORDED UNDER A SECOND NAME. Every row in
# both reads identically: a feature doc asserts a surface is missing or unwired, and the code says
# otherwise. Nine rows here, eighteen there, one shape — and because promote.py counts recurrence
# per class STRING, the split hid a 27-instance problem as two smaller ones and meant gating either
# label left the other blocked. Recorded separately as its own finding rather than silently merged,
# since renaming ledger rows would rewrite history.
#
# ALL NINE WERE VERIFIED AGAINST CODE BEFORE ANY DOC WAS TOUCHED:
#   F-0546  WalletController.java:191/207/223 — payout-methods GET/POST/PUT all exist
#   F-0547  GstSplitUtil.java:28 — compares supplier/customer GSTIN state codes; not always IGST
#   F-0552  src/lib/api.ts — five refreshAccessToken sites; the 401 refresh really is wired
#   F-0555  WorkspaceService.java:122 — updatePhone on a real nullable column
#   F-0556  RazorpayWebhookController.java:126 — an explicit "subscription.activated" arm
#   F-0557  NotificationListener — MEASURED: 34 of 34 concrete events have a listener. The only
#           unhandled name is NotificationEvent itself, a sealed marker interface, not an event.
#   F-0558  Workspace.java:48 verificationStatus + CampaignValidator.java:64 refusing ACTIVE
#   F-0562  ContractService.java:1211 publishes ContractReadyForEscrowEvent
#   F-0566  UploadController.java:42 — a real POST multipart handler
#
# FOUR OF THE NINE NEEDED NO DOC EDIT: F-0552, F-0556, F-0562 and F-0566's claims are no longer
# present in any doc — they were corrected before this pass. Five were live and are now corrected
# WITH code citations, which also dropped the feature-doc ratchet from 23 to 19.
#
# THE STANDING DEFENCE IS THE SHARED SCANNER, not this file's checks. The specific greps below only
# stop these five corrections being reverted; the scanner is what catches the NEXT one.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_doc_stale_claim_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

# --- CHECK A: the shared scanner (route/type claims + the feature-doc ratchet) -------------------
python "$SCAN"; rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

# --- CHECK B: the five corrections stay corrected -----------------------------------------------
# Each pairs a doc with the code fact that refutes the old claim, so a revert on EITHER side fails.
fail() { echo "VERDICT: broken — $1"; exit 1; }

grep -q "both now have after-commit listeners" docs/docs/features/billing-subscriptions.md 2>/dev/null \
  || fail "billing-subscriptions.md claims the subscription halted/payment-failed events have no listener again (F-0557)"
grep -q "sealed \`NotificationEvent\` marker" docs/docs/features/notifications.md 2>/dev/null \
  || fail "notifications.md asserts events without listeners again; 34 of 34 concrete events are handled (F-0557)"
grep -q "WalletController.java:191" docs/docs/features/payouts.md 2>/dev/null \
  || fail "payouts.md dropped the citation for the payout-method routes it once called orphaned (F-0546)"
grep -q "GstSplitUtil.java:28" docs/docs/features/invoicing-gst.md 2>/dev/null \
  || fail "invoicing-gst.md dropped the citation showing the GST split is state-code driven, not always IGST (F-0547)"
grep -q "WorkspaceService.java:122" docs/docs/features/workspaces-members.md 2>/dev/null \
  || fail "workspaces-members.md dropped the citation showing workspace phone persists (F-0555)"
echo "· all five corrected feature-doc claims still carry their code citation"

# --- CHECK C: the code facts those citations rest on --------------------------------------------
# A citation to a line that no longer says what it claimed is a new stale claim wearing a footnote.
A=influora-api/src/main/java/com/influora
grep -q 'Mapping("/payout-methods")' "$A/web/WalletController.java" 2>/dev/null \
  || fail "the payout-methods routes are gone, so payouts.md's corrected claim is now itself false (F-0546)"
grep -q "stateCode(supplierGstin)" "$A/service/GstSplitUtil.java" 2>/dev/null \
  || fail "GstSplitUtil no longer compares state codes, so invoicing-gst.md's correction is now false (F-0547)"
grep -q "workspace.updatePhone(phone)" "$A/service/WorkspaceService.java" 2>/dev/null \
  || fail "WorkspaceService no longer persists the workspace phone (F-0555)"
echo "· the code each citation points at still says what the doc claims"

echo "VERDICT: aligned (proved) — no current-truth doc asserts that a shipped route or type is"
echo "         missing; the five live stale claims in this class are corrected and cited, and the"
echo "         code behind each citation still holds."
echo "NOT CHECKED: the same limits as the shared scanner — behaviour claims ('X is slow') and"
echo "             frontend-only claims are invisible, and historical trees are excluded by design."
echo "             The 19 remaining uncited feature-doc claims are RATCHETED, not verified: they may"
echo "             not grow, but nothing here says they are true. And docs/docs/known-limitations.md"
echo "             is linked from nine feature docs and DOES NOT EXIST — every 'see known-limitations'"
echo "             pointer in this class's own docs is dead, which no check here fails on."
exit 0
