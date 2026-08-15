#!/usr/bin/env bash
# gates/F-0213-contract-filename.sh — origin: F-0213 (filename-param-ignored).
# downloadContractPDF(data, filename) must honour its filename: the print-dialog
# flow's only naming channel is document.title, so the unit contract asserts the
# filename lands in the opened document's <title> (default + escaping included).
# LAW: tool-cannot-run => exit 2. exit 1 ONLY on a failing assertion.
set -u
SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
cd "$SELF/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
T=src/lib/__tests__/contract-generator-filename.test.ts
[ -f "$T" ] || { echo "· test file missing: $T — the contract this gate runs is gone"; exit 1; }
npx --no-install vitest run "$T" 2>&1 | tail -5
rc=${PIPESTATUS[0]}
echo "NOT CHECKED: that the browser actually names the saved PDF from document.title (vendor behaviour, not testable headlessly here); the visual content of the PDF"
[ $rc -eq 0 ] && exit 0
[ $rc -eq 1 ] && exit 1
echo "· vitest could not run (exit $rc) — unavailable"; exit 2
