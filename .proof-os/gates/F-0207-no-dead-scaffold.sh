#!/usr/bin/env bash
# gates/F-0207-no-dead-scaffold.sh — origin: F-0207 (dead-next-scaffold).
# Fails while the dead Next.js scaffold or its orphaned dependency remains.
set -u
cd "$(dirname "$0")/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
FAIL=0
# F-0212: the scaffold has a tracked TWIN at repo root (app/layout.tsx + app/globals.css),
# invisible to tsc (tsconfig excludes app/) — both twins must be gone, not just src/app's.
[ -f src/app/layout.tsx ] && { echo "DEAD SCAFFOLD PRESENT: src/app/layout.tsx"; FAIL=1; }
[ -f app/layout.tsx ] && { echo "DEAD SCAFFOLD PRESENT: app/layout.tsx (root twin, F-0212)"; FAIL=1; }
[ -f app/globals.css ] && { echo "DEAD SCAFFOLD PRESENT: app/globals.css (root twin, F-0212)"; FAIL=1; }
grep -q '"@vercel/analytics"' package.json && { echo "ORPHAN DEP PRESENT: @vercel/analytics in package.json"; FAIL=1; }
[ -f src/app/globals.css ] || { echo "REGRESSION: src/app/globals.css missing — it is LIVE (imported by src/main.tsx)"; FAIL=1; }
echo "NOT CHECKED: other v0/Next scaffold remnants; other orphaned dependencies"
exit $FAIL
