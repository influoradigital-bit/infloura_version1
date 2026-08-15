#!/usr/bin/env bash
# F-0164 — creator-meta-callback.tsx's onboarding-resume redirect now only auto-advances back
# into the wizard on an actual connection (result.connected), so a personal-account creator who
# started the connect from onboarding sees the "Business account needed" explanation (F-0116)
# instead of being silently redirected past it. Re-runs the pinning suite live; exits 0 only if
# green.
#
# Usage: F-0164-onboarding-resume-explanation-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/pages/creator-meta-callback.test.tsx
