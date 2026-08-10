#!/usr/bin/env bash
# F-0132 — no-frontend-role-gate (money-path). The billing Upgrade/Cancel controls must be gated to
# the same roles the server allows: BillingController requires OWNER/ADMIN on POST /billing/checkout
# and /cancel, so MANAGER/MEMBER/VIEWER must see a disabled control with a reason, not an enabled
# button that only 403s on click. Re-runs the canManageBilling rule suite (OWNER/ADMIN allowed;
# MANAGER/MEMBER/VIEWER blocked; null + mock-mode fail open); exits 0 only if green.
#
# Usage: F-0132-billing-role-gate.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/hooks/brand/useBrandBillingAccess.test.ts
