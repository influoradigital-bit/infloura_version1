#!/usr/bin/env bash
# gates/F-0650-provisioning-lock-commit-order.sh
# origin: F-0650 (unlocked-check-then-insert) — first-admin provisioning's count-then-insert had no
# lock at all, then a FIRST fix attempt added a MySQL named lock but released it INSIDE the
# transactional callback, before commit — and since AdminUser has an assigned @Id (no
# @GeneratedValue), save() defers the actual INSERT to flush time, so the lock was released before
# the row was even durably written. A fresh-context CTO review caught this; this gate is the
# SECOND, corrected fix.
#
# THE FIX: ProvisionSuperAdminRunner#provisionAdmin now acquires the named lock, then calls
# transactionTemplate.execute(...) (synchronous — does not return until commit), and releases the
# lock in a finally wrapping THAT CALL, not inside the callback. provisionAdminInTransaction (the
# renamed former provisionAdminLocked) holds no lock of its own.
#
# FALSIFICATION, measured 2026-09-04: the new test releasesLockOnlyAfterRowIsDurablyCommitted models
# persist-defers-to-flush explicitly (save() only stages; a stub on the mocked
# PlatformTransactionManager#commit marks durable-commit; RELEASE_LOCK records whether commit had
# already fired). Reverted the production file to the pre-fix (release-inside-callback) shape,
# reran: this one test failed for the right reason, the other 6 (which only assert call ORDER, not
# commit TIMING) stayed green — confirming they are exactly the blind spot that let the first,
# broken fix pass. Restored, reran clean, 7/7.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable. Clean build always — the standing rule from the
# stale-.class false green discovered earlier this session.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api
[ -d "$API" ] || { echo "· $API not a directory — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_F0650_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· mvn -o clean -Dtest=ProvisionSuperAdminRunnerTest test"
out=$($TO mvn -o -q clean -Dtest=com.influora.ops.ProvisionSuperAdminRunnerTest -f "$API/pom.xml" test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol|does not exist"; then
  printf '%s\n' "$out" | tail -50
  echo "VERDICT: unavailable"
  exit 2
fi
if echo "$out" | grep -qi "No tests were executed"; then
  printf '%s\n' "$out" | tail -20
  echo "VERDICT: unavailable — target class not found"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — the provisioning lock no longer covers the durable-commit window (F-0650)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  /'
echo "  green on a clean build"

echo "VERDICT: aligned (proved) — the provisioning lock is held until the transaction that inserted"
echo "         the admin row has actually committed, not merely until the callback returns."
echo "NOT CHECKED: MySQL's real cross-connection/cross-process GET_LOCK semantics (JVM-local mock"
echo "             only); whether acquire/release outside a Spring transaction shares one DB"
echo "             connection or opens a fresh one per call against a real connection pool."
exit 0
