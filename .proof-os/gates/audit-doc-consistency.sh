#!/usr/bin/env bash
# proof-os gate: audit-doc-consistency
# Closes F-0083 (stale-md-after-remediation, the F-0078/F-0079 family).
# Deterministic guard: fails if PROJECT-DEEP-AUDIT-2026-08-04.md makes a CURRENT-TENSE
# claim that a now-built admin feature is unbuilt/no-UI. Dated historical banners
# (lines beginning "> " — the append-only changelog) are excluded, as are lines that
# carry an explicit supersession marker ("at the time of" / "since ... update #").
#
# LAW 5 (what this gate does NOT check): it is a targeted denylist of the phrases that
# actually recurred, not a full natural-language doc linter. A newly-worded stale claim
# it doesn't list would pass. Extend the denylist when a new instance is found.
set -euo pipefail
cd "$(dirname "$0")/../.."
DOC="PROJECT-DEEP-AUDIT-2026-08-04.md"
[ -f "$DOC" ] || { echo "audit-doc-consistency: $DOC not found"; exit 2; }

# Now-false current-tense claims (the console + reconciliation + payout + growth are built).
PATTERNS='unbuilt admin finance|finance/escrow console \(backend is ready, no UI\)|No escrow/payout/revenue/at-risk console consumes them|marketing analytics not built.*getGrowth'

# Search body lines that are NOT dated banners (^> ) and NOT explicitly marked superseded.
hits=$(grep -nEi "$PATTERNS" "$DOC" | grep -v '^[0-9]*:> ' | grep -viE 'at the time of|since .*update #' || true)
if [ -n "$hits" ]; then
  echo "audit-doc-consistency: FAIL — stale 'unbuilt' claim(s) for a now-built feature:"
  echo "$hits"
  exit 1
fi
echo "audit-doc-consistency: OK — no current-tense stale 'unbuilt' claims"
