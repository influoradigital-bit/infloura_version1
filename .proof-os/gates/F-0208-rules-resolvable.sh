#!/usr/bin/env bash
# gates/F-0208-rules-resolvable.sh — origin: F-0208 (gate-config-rule-mismatch).
# The sage eslint config must DEFINE every rule the codebase's load-bearing
# eslint-disable comments reference, and honour the project's _-prefix unused-vars
# convention. Proves both with fixtures: a disable comment naming
# react-hooks/exhaustive-deps must NOT produce "Definition for rule ... was not
# found", and an _-prefixed unused parameter must NOT error.
# LAW: tool-cannot-run => exit 2. exit 1 ONLY for a real config regression.
set -u
SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
cd "$SELF/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
CFG="C:/Users/Sage world/AppData/Roaming/Claude/local-agent-mode-sessions/3a613ffb-5d5c-4472-8b3e-6aa41ae4204d/b66976d0-d58d-4e93-84e6-9997f7df1500/rpm/plugin_01BnEF97nKc8pyi8gL7qpsSM/gates/eslint.sage.mjs"
[ -f "$CFG" ] || { echo "· sage config not found — unavailable"; exit 2; }
FIX=$(mktemp -d)/f0208-fixture.tsx
mkdir -p "$(dirname "$FIX")"
cat > "$FIX" <<'TSX'
import { useEffect } from 'react';
declare const motion: { div: (p: { style?: object }) => null };
export function Fixture({ dep, _keptForSignature }: { dep: number; _keptForSignature: string }) {
  useEffect(() => {
    void dep;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // F-0209 ruling teeth: BANNED_STYLE_DIV must error; the motion.* style must NOT.
  return <div id="BANNED_STYLE_DIV" style={{ width: 1 }} /> && <motion.div style={{ x: 1 }} />;
}
TSX
# The fixture must live INSIDE the project — outside it, resolution differs and the
# style-ban rule was observed not to fire (temp-dir false green, 2026-08-15).
INFIX=".proof-os/tasks/.f0208-fixture-$$.tsx"
cp "$FIX" "$INFIX"
OUT=$(npx --no-install eslint --config "$CFG" --no-ignore "$INFIX" 2>&1); RC=$?
rm -rf "$(dirname "$FIX")" "$INFIX"
FAIL=0
# F-0209 ruling: exactly ONE style error (the plain div), none on motion.*
STYLE_ERRS=$(echo "$OUT" | grep -c "no inline styles")
[ "$STYLE_ERRS" -eq 1 ] || { echo "REGRESSION: expected exactly 1 style-ban error (plain div banned, motion.* exempt), got $STYLE_ERRS"; FAIL=1; }
echo "$OUT" | grep -q "was not found" && { echo "REGRESSION: a referenced rule is undefined in the config:"; echo "$OUT" | grep "was not found" | head -3; FAIL=1; }
echo "$OUT" | grep -q "_keptForSignature" && { echo "REGRESSION: _-prefix convention not honoured:"; echo "$OUT" | grep "_keptForSignature" | head -2; FAIL=1; }
[ $RC -ge 2 ] && { echo "· eslint itself failed (exit $RC) — unavailable"; echo "$OUT" | head -5; exit 2; }
[ $FAIL -eq 0 ] && echo "config defines referenced rules and honours the _-convention"
echo "NOT CHECKED: rules referenced by disable comments OTHER than react-hooks/* (fixture covers the class instance, not every rule name); whether severities match the project config"
exit $FAIL
