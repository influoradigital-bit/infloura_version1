#!/usr/bin/env bash
# gates/F-0206-gitleaks-baseline.sh — origin: F-0206 (gitleaks-unbaselined-noise).
# Runs gitleaks WITH the repo allowlist (.gitleaks.toml) so only NEW leaks go red.
# LAW: tool-cannot-run => exit 2. exit 1 ONLY for a real (new) leak.
set -u
cd "$(dirname "$0")/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
command -v gitleaks >/dev/null 2>&1 || { echo "· gitleaks not on PATH — unavailable"; echo "NOT CHECKED: everything"; exit 2; }
[ -f .gitleaks.toml ] || { echo "· .gitleaks.toml missing — the baseline this gate exists for is gone"; exit 1; }
gitleaks detect --no-banner -s . --config .gitleaks.toml
rc=$?
echo "NOT CHECKED: secret types outside gitleaks' rules; whether allowlisted paths gained NEW secrets (allowlist is by path, not by value); rotation status of anything historical"
[ $rc -eq 0 ] && exit 0 || exit 1
