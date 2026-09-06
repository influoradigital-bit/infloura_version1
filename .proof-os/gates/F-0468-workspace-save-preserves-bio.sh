#!/usr/bin/env bash
# gates/F-0468-workspace-save-preserves-bio.sh — instance gate, closes F-0468.
#
# origin: F-0468 (partial-fix-narrows-defect) — "F-0462 was closed for three of four fields.
# description is still omitted from the payload and Workspace.setCompanyDetails assigns it
# unconditionally, so every settings save still clears the brand bio captured at onboarding."
#
# WHY THE FOURTH FIELD WAS HARDER THAN THE OTHER THREE, and so got left behind: `description` was
# WRITE-ONLY. WorkspaceReadResponse did not carry it, so there was nothing to echo back even if the
# payload had included it. Fixing the payload alone would have sent `undefined` and cleared the bio
# just the same. Both sides had to move, which is exactly the shape a "three of four fields" fix
# misses.
#
# THE CHAIN THIS PROVES, end to end:
#   READ   WorkspaceController maps workspace.getDescription() into WorkspaceReadResponse
#   CLIENT brand-settings carries loadedWorkspace.description into the PATCH payload
#   GUARD  Save is refused until workspaceInfoLoaded — otherwise the carry-forward would send
#          undefined from an unloaded snapshot and clear the bio through the "fixed" path
#   WRITE  WorkspaceService#updateMyWorkspace passes description to applyCompanyDetails
#
# THE TEST'S FAKE SERVER REPLACES RATHER THAN MERGES, deliberately. Full-replace is the real
# contract; a merging double would pass whether or not the client sent the field, which is the
# assertion that would have let F-0468 through in the first place. Falsified by deleting the
# payload line: "expected undefined to be 'India-first D2C skincare...'" — the bio being wiped.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

PAGE=src/pages/brand-settings.tsx
CTRL=influora-api/src/main/java/com/influora/web/WorkspaceController.java
SVC=influora-api/src/main/java/com/influora/service/WorkspaceService.java
TEST=src/pages/__tests__/brand-settings-workspace-description-roundtrip.test.tsx
[ -f "$PAGE" ] || { echo "VERDICT: broken — $PAGE is missing (F-0468)"; exit 1; }

# --- CHECK A: the READ side still carries the field ---------------------------------------------
if [ -f "$CTRL" ]; then
  grep -q "workspace.getDescription()" "$CTRL" || {
    echo "VERDICT: broken — WorkspaceReadResponse no longer receives workspace.getDescription(), so"
    echo "         description is write-only again and the client has nothing to echo back. The"
    echo "         payload below would then carry undefined and clear the bio (F-0468)"
    exit 1; }
  echo "· read side: the controller maps getDescription() into the response"
else
  echo "· backend controller not present here — READ SIDE NOT CHECKED"
fi

# --- CHECK B: the WRITE side still assigns it ---------------------------------------------------
if [ -f "$SVC" ]; then
  grep -q "applyCompanyDetails" "$SVC" || {
    echo "VERDICT: broken — updateMyWorkspace no longer calls applyCompanyDetails (F-0468)"; exit 1; }
  echo "· write side: updateMyWorkspace still applies the company details"
fi

# --- CHECK C: the client carries all four unsurfaced fields -------------------------------------
for field in description industry companySize logoUrl; do
  grep -q "$field: loadedWorkspace?.$field ?? undefined," "$PAGE" || {
    echo "VERDICT: broken — the PATCH payload no longer carries \`$field\` forward. PATCH"
    echo "         /workspaces/me is FULL-REPLACE, so an omitted field is CLEARED, not ignored"
    echo "         (F-0462/F-0468)"
    exit 1; }
done
echo "· client carries all four unsurfaced fields forward"

# --- CHECK D: Save stays gated on the load ------------------------------------------------------
# Without this the carry-forward is worse than useless: an unloaded snapshot sends undefined for
# every one of those four fields and clears them through the very code that was meant to fix this.
grep -q "workspaceInfoLoaded" "$PAGE" || {
  echo "VERDICT: broken — the Save guard on workspaceInfoLoaded is gone; a save before the load"
  echo "         completes would carry undefined forward and clear all four fields (F-0468)"
  exit 1; }
echo "· Save is still gated on an authoritative loaded snapshot"

# --- CHECK E: behaviour -------------------------------------------------------------------------
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --error-unmatch "$TEST" >/dev/null 2>&1 || {
    echo "VERDICT: broken — $TEST is not git-tracked; it proves nothing on a fresh clone (F-0324)"
    exit 1; }
  echo "· the round-trip test is git-tracked"
fi
command -v npx >/dev/null 2>&1 || { echo "· npx unavailable — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f package.json ] || { echo "· no package.json here — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f "$TEST" ] || { echo "VERDICT: broken — the F-0468 round-trip test is gone"; exit 1; }
log=$(mktemp 2>/dev/null || echo "/tmp/f0468.$$")
npx vitest run --reporter=basic "$TEST" >"$log" 2>&1; trc=$?
if grep -qE "Cannot find module|Failed to load|ERR_MODULE_NOT_FOUND" "$log"; then
  echo "· the suite could not load — unavailable, NOT a pass"; tail -10 "$log"; exit 2; fi
if [ "$trc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0468 round-trip test does not pass"
  sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "×|expected|Tests " | head -10; exit 1; fi
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "Tests |Test Files " | head -2

echo "VERDICT: aligned (proved) — a workspace save that edits only the name carries the brand bio"
echo "         and the other three unsurfaced fields through unchanged, across a fake server that"
echo "         replaces rather than merges; the field is readable, writable, and Save stays gated"
echo "         on a loaded snapshot."
echo "NOT CHECKED: this exercises the CLIENT against a double, not a live Spring instance — the"
echo "             full-replace semantics are reproduced from WorkspaceService, not observed. No"
echo "             test here covers a save racing a slow load (the guard is asserted to EXIST, not"
echo "             exercised under a race), nor the OTHER writers of these columns: onboarding and"
echo "             the admin brand editor both set them through different paths this gate never"
echo "             touches."
exit 0
