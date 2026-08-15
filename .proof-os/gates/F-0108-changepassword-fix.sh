#!/usr/bin/env bash
# F-0108 (CR-87) — the creator Change Password dialog must be wired to a real,
# correctly role-scoped api.auth.changePassword call. Re-runs the pinning
# regression suite live; exits 0 only if it's green.
#
# Usage: F-0108-changepassword-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/pages/creator-settings-change-password.test.tsx src/pages/creator-settings-logout.test.tsx
