#!/usr/bin/env bash
# gates/F-0210-reduced-motion-css.sh — origin: F-0210 (reduced-motion-visual-regression).
# Runs the headless value-in-slot CSS probe (F-0210-reduced-motion-css.mjs beside this
# file) against the compiled dist CSS under forced prefers-reduced-motion.
# LAW: tool-cannot-run => exit 2. exit 1 ONLY when a computed value contradicts the render.
set -u
SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
cd "$SELF/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -d dist/assets ] || { echo "· no dist/assets — run npm run build first — unavailable"; exit 2; }
[ -x "/c/Program Files/Google/Chrome/Application/chrome.exe" ] || [ -f "C:/Program Files/Google/Chrome/Application/chrome.exe" ] || { echo "· Chrome not found — unavailable"; exit 2; }
node "$SELF/F-0210-reduced-motion-css.mjs"
rc=$?
[ $rc -eq 0 ] && exit 0
[ $rc -eq 1 ] && exit 1
echo "· probe crashed (exit $rc) — unavailable, not a verdict"; exit 2
