#!/usr/bin/env bash
# proof-os gate: payout-money-safety
# Closes F-0080 (re-credit-once) + F-0082 (ledger-key VARCHAR(64) fit).
# Runs the money-path unit tests that assert: a retry-then-reversal re-credits the
# creator exactly once (never zero/twice), and the retry ledger keys fit the
# wallet_transactions.idempotency_key column after the :D/:C suffix.
# NOTE (law 5): this is a UNIT gate — it does NOT exercise a live MySQL. The
# real-DB persistence + crash-resume integration test remains an open live residual.
set -euo pipefail
cd "$(dirname "$0")/../../influora-api"
mvn -o -q test -Dtest="PayoutReconciliationServiceTest,AdminFinanceServiceTest" >/dev/null 2>&1
echo "payout-money-safety: PayoutReconciliationServiceTest + AdminFinanceServiceTest green"
