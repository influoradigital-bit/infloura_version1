#!/usr/bin/env bash
# gates/F-0241-no-false-draft-reassurance.sh
# origin failure: F-0241 (false-save-reassurance) — the box claimed "this campaign is saved as
# a draft, so nothing is lost" at a moment when the create call had thrown and saved nothing.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
F=src/components/brand/VerificationRequiredBox.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0
if grep -qiE "saved as a draft, so nothing is lost" "$F"; then
  echo "· the false 'already saved' reassurance is back"; fail=1
fi
# NOTE: match on ASCII-only text. The file uses a curly apostrophe (3 bytes in UTF-8),
# and a "." in an ERE matches one BYTE — "hasn.t" silently never matches "hasn’t".
if ! grep -qiE "been saved yet" "$F"; then
  echo "· the box does not state that the campaign is unsaved"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0241 regressed)"; \
  echo "NOT CHECKED: whether the save-draft button actually persists, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the save-draft button actually persists, or runtime behaviour"
exit 0
