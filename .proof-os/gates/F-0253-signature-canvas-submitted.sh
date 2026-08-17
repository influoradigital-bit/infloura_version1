#!/usr/bin/env bash
# F-0253-signature-canvas-submitted.sh — gate for F-0253 (decorative-legal-control).
#
# contracts-and-deliverables.tsx used to draw the brand's signature onto a <canvas> whose
# 2d context was never read: no toDataURL(), no export, nothing wiring the drawn strokes into
# the submit payload. Only `signatureText` (the typed name) reached
# `api.contracts.sign('brand', id, { name, agreedAt })` — see src/lib/api.ts:2410, which accepts
# only `{ name: string; agreedAt: string }` and has no image/bitmap field at all. Meanwhile the
# dialog copy told the brand "you agree to be legally bound ... under the IT Act 2000" right next
# to a signature pad whose mark was silently thrown away.
#
# The chosen fix (an engineering call, not the only valid one): since the backend genuinely has
# no field for a signature image, the honest fix is to remove the decorative canvas rather than
# fabricate a bitmap upload the API can't accept — and to make the legal copy describe only what
# is actually submitted (the typed name).
#
# Three legs:
#   1. The decorative canvas element and its drawing plumbing (canvasRef, startDrawing/draw/
#      stopDrawing, isDrawing, strokeStyle) are gone from the sign flow — no orphaned "signature"
#      UI that draws to a context nothing ever reads.
#   2. The "legally bound" copy no longer implies a *drawn* mark is part of what's binding —
#      it must not tell the brand to "draw" a signature.
#   3. Behavioral: a vitest suite asserts the sign submission payload carries only the typed
#      name/agreedAt (what the API actually accepts) and that the dialog renders no <canvas>.
#
# What it deliberately does NOT do: assert exact copy wording — only that "draw" framing is gone
# and a canvas element isn't rendered.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

TARGET=src/components/brand/contracts/contracts-and-deliverables.tsx
[ -f "$TARGET" ] || { echo "· $TARGET missing — unavailable"; exit 2; }

echo "· source: no decorative signature canvas left in $TARGET"
if grep -qE '<canvas' "$TARGET"; then
  grep -nE '<canvas' "$TARGET"
  echo "VERDICT: broken — a <canvas> signature pad is still rendered whose strokes are never"
  echo "         read into the submit payload (F-0253)"
  exit 1
fi
if grep -qE 'canvasRef|startDrawing|const draw = |stopDrawing|clearSignature' "$TARGET"; then
  grep -nE 'canvasRef|startDrawing|const draw = |stopDrawing|clearSignature' "$TARGET"
  echo "VERDICT: broken — dead signature-canvas plumbing (canvasRef/startDrawing/draw/stopDrawing)"
  echo "         still present even though nothing renders or reads it (F-0253)"
  exit 1
fi
echo "  clean — no <canvas> signature pad, no orphaned drawing handlers"

echo "· copy: legal notice does not tell the brand to draw a binding mark"
if grep -qiE 'draw (your|a) signature' "$TARGET"; then
  grep -niE 'draw (your|a) signature' "$TARGET"
  echo "VERDICT: broken — copy still invites drawing a signature that is not what gets submitted"
  echo "         (F-0253)"
  exit 1
fi
echo "  clean — no 'draw your signature' framing"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/contracts/__tests__/contracts-and-deliverables.signature.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: broken — the sign flow does not honestly submit only what the legal copy"
  echo "         describes (F-0253)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the signature control submits exactly what the copy promises,"
echo "         and no drawn mark is discarded"
echo "NOT CHECKED: whether a future backend signature-image field should exist at all — that's a"
echo "             product/legal call, not this gate's; and whether contracts signed before this"
echo "             fix (with a discarded drawing) need any remediation."
exit 0
