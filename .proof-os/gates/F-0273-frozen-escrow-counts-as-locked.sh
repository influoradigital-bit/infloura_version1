#!/usr/bin/env bash
# gates/F-0273-frozen-escrow-counts-as-locked.sh
# origin failure: F-0273 (frozen-escrow-reads-unlocked) — deriveEscrowFromMilestones counted only
# FUNDED milestones, so a deal under dispute (money FROZEN: held by the platform, NOT released)
# rendered "Escrow Status: Not Locked". The brand was told their money was not held at the exact
# moment a dispute froze it — the same false-money-statement class F-0236 was opened for.
# Found by priya (fresh-context) reviewing the F-0236 fix; the F-0236 gate could not see it,
# because that gate asserts the function EXISTS, not which statuses it counts.
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory: ${1:-.} — unavailable"; exit 2; }
F=src/components/brand/contracts/contracts-and-deliverables.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0

# Isolate the derivation function body, so we assert about IT and not about the whole file.
BODY=$(awk '/function deriveEscrowFromMilestones/{f=1} f{print} f&&/^}/{exit}' "$F")
if [ -z "$BODY" ]; then
  echo "· deriveEscrowFromMilestones not found — the escrow badge has no single derivation point"
  fail=1
else
  # FROZEN is held money and MUST count toward locked.
  printf '%s' "$BODY" | grep -q "FROZEN" || {
    echo "· deriveEscrowFromMilestones does not consider FROZEN — frozen escrow will read Not Locked"
    fail=1; }
  printf '%s' "$BODY" | grep -q "FUNDED" || {
    echo "· deriveEscrowFromMilestones no longer considers FUNDED"; fail=1; }
  # RELEASED / REFUNDED have LEFT escrow and must never count as held. Guard against an
  # over-broad fix that counts every status to make the gate pass.
  for s in RELEASED REFUNDED; do
    printf '%s' "$BODY" | grep -q "$s" && {
      echo "· deriveEscrowFromMilestones counts $s — that money has left escrow and is not held"
      fail=1; }
  done
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0273 regressed)"; \
  echo "NOT CHECKED: whether the backend's MilestoneStatus set is still exactly PENDING/FUNDED/RELEASED/REFUNDED/FROZEN, whether the rendered amount is right, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the backend's MilestoneStatus set is still exactly PENDING/FUNDED/RELEASED/REFUNDED/FROZEN, whether the rendered amount is right, or runtime behaviour"
exit 0
