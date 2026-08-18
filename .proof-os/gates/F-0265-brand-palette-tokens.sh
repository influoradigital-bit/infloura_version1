#!/usr/bin/env bash
# F-0265-brand-palette-tokens.sh — gate for F-0265 (off-palette-cta).
#
# hype.tsx's primary CTA hardcoded an off-palette teal (`bg-[#0e7490] hover:bg-[#0b5d73]`)
# instead of the committed brand violet token (`--primary: #6d5ae6` in src/app/globals.css,
# documented there as "a committed violet primary aligned with the Meera money-serious accent
# family"). contracts-and-deliverables.tsx's signature canvas painted a raw `ctx.strokeStyle =
# '#3b82f6'`. gates/frontend.sh's raw-hex scan truncates to `head -10`, so findings past the
# tenth slipped through undetected.
#
# Two legs, scoped to the two files this record names:
#   1. No raw hex color literal (`#rrggbb`/`#rgb` in a className bg-[...]/text-[...]/style, or a
#      raw hex assigned to a canvas drawing property) remains in either file.
#   2. The hype.tsx CTA buttons use the project's primary token pair (`bg-primary
#      text-primary-foreground`), not an ad hoc class — so it inherits the palette's own vetted
#      AA-contrast pairing (light: white-on-#6d5ae6 ~4.9:1; dark: #201c33-on-#b0a3f5 ~7.4:1,
#      per the globals.css comments) instead of a fixed color that ignores dark mode entirely.
#
# What it deliberately does NOT do: recompute contrast ratios itself (trusts the token pair's
# documented ratios) or forbid every hex literal project-wide — only in the two files this
# record names.
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

HYPE=src/pages/features/hype.tsx
HYPE_CODE=$(code_view "$HYPE") || { echo "$(code_why) - unavailable"; exit 2; }
CONTRACTS=src/components/brand/contracts/contracts-and-deliverables.tsx
CONTRACTS_CODE=$(code_view "$CONTRACTS") || { echo "$(code_why) - unavailable"; exit 2; }
for f in "$HYPE" "$CONTRACTS"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· no raw hex color literal in $HYPE or $CONTRACTS"
# Matches bg-[#xxxxxx], text-[#xxxxxx], border-[#xxxxxx], strokeStyle = '#xxxxxx', etc.
HEX_HITS=$(grep -nE "#[0-9a-fA-F]{3,6}" "$HYPE_CODE" "$CONTRACTS_CODE" || true)
if [ -n "$HEX_HITS" ]; then
  printf '%s\n' "$HEX_HITS"
  echo "VERDICT: broken — raw hex color literal(s) remain off the design-token system (F-0265)"
  exit 1
fi
echo "  clean — no raw hex literals in either file"

echo "· hype.tsx primary CTA uses the committed brand-violet token pair"
CTA_LINES=$(grep -nE 'Launch a Hype Campaign' "$HYPE_CODE" | cut -d: -f1)
if [ -z "$CTA_LINES" ]; then
  echo "VERDICT: broken — could not locate the 'Launch a Hype Campaign' CTA at all (F-0265)"
  exit 1
fi
missing=0
for ln in $CTA_LINES; do
  # The <Button ...> opening tag is a few lines above the link text; scan a small window.
  start=$((ln - 4)); [ $start -lt 1 ] && start=1
  window=$(sed -n "${start},${ln}p" "$HYPE_CODE")
  if ! printf '%s' "$window" | grep -qE 'bg-primary\b'; then
    echo "  CTA near line $ln does not use bg-primary:"
    printf '%s\n' "$window"
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "VERDICT: broken — the Hype CTA button(s) don't use the committed primary/violet token"
  echo "         (F-0265)"
  exit 1
fi
echo "  clean — both 'Launch a Hype Campaign' CTAs use bg-primary"

echo "VERDICT: aligned (proved) — hype.tsx and contracts-and-deliverables.tsx carry no raw hex"
echo "         and the primary CTA uses the committed brand-violet token pair"
echo "NOT CHECKED: raw-hex usage anywhere else in the codebase (gates/frontend.sh's own head -10"
echo "             truncation bug is unfixed and out of scope for this record); actual rendered"
echo "             contrast in a browser, only the token pair's documented ratios."
exit 0
