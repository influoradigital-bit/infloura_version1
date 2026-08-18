#!/usr/bin/env bash
# gates/F-0241-no-false-draft-reassurance.sh
# origin failure: F-0241 (false-save-reassurance) — the box claimed "this campaign is saved as
# a draft, so nothing is lost" at a moment when the create call had thrown and saved nothing.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }
F=src/components/brand/VerificationRequiredBox.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }
fail=0
if grep -qiE "saved as a draft, so nothing is lost" "$F_CODE"; then
  echo "· the false 'already saved' reassurance is back"; fail=1
fi
# NOTE: match on ASCII-only text. The file uses a curly apostrophe (3 bytes in UTF-8),
# and a "." in an ERE matches one BYTE — "hasn.t" silently never matches "hasn’t".
if ! grep -qiE "been saved yet" "$F_CODE"; then
  echo "· the box does not state that the campaign is unsaved"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0241 regressed)"; \
  echo "NOT CHECKED: whether the save-draft button actually persists, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the save-draft button actually persists, or runtime behaviour"
exit 0
