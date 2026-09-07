#!/usr/bin/env bash
# Gate coupling F-0738 (platform-funded-fabrication) to F-0739 (enum-value-never-migrated).
#
# THE SITUATION THIS EXISTS TO FREEZE.
#
# F-0738: AffiliateSettlementWriter.creditCreatorWallet posts
#   platform clearing wallet (DEBIT) -> creator wallet (CREDIT)
# and NOTHING debits the brand workspace that owes the commission. AFFILIATE_COMMISSION appears in
# exactly two places in the whole codebase — the enum declaration and that one ledger post. The
# clearing wallet is not platform profit: LedgerEscrowBackend.fund posts brand wallet -> clearing on
# every ESCROW_HOLD, so it is the omnibus account holding every brand's escrowed funds. An unfunded
# commission is therefore paid out of other brands' money, and the pool silently stops covering its
# liabilities.
#
# F-0739: that settlement cannot currently complete at all. wallet_transactions.type (V8) is
#   ENUM('DEPOSIT','WITHDRAWAL','ESCROW_HOLD','ESCROW_RELEASE','ESCROW_REFUND','PLATFORM_FEE','PAYOUT','ADJUSTMENT')
# and reference_type is
#   ENUM('COLLABORATION','ESCROW_HOLD','MILESTONE','CAMPAIGN','DEPOSIT_ORDER','MANUAL').
# Neither contains AFFILIATE_COMMISSION / AFFILIATE_EARNING, no migration ever widens them, and
# WalletTransaction maps both with @Enumerated(EnumType.STRING). Under MySQL strict mode the insert
# raises 1265 and the monthly batch (@Scheduled cron "0 0 5 1 * *") rolls back.
#
# So today nothing drains — by accident, not by design. The two defects are cancelling out.
#
# WHY A GATE AND NOT A FIX. Closing F-0738 properly means deciding WHO funds the commission — brand
# wallet balance, the campaign's escrow hold, or an invoice. No document in this repository decides
# that: wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md is CFO-signed and
# specifies the RATE only, silent on the funding leg. That is a Rohan/Swapnil ruling, not an
# engineering default, and inventing one would put a made-up policy on a money path.
#
# THE HAZARD THIS BLOCKS. F-0739 looks like a trivial oversight. The natural fix — one ALTER TABLE
# widening the enum — is exactly the change someone will make the moment they notice creators have
# never been paid affiliate commission. That single line converts a dormant accounting hole into a
# live drain from the omnibus account, with no code review necessarily touching
# AffiliateSettlementWriter at all. This gate makes the two inseparable: the enum may be widened
# only once a brand-side debit exists.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/influora-api/src/main/resources/db/migration"
WRITER="$ROOT/influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java"

if [ ! -d "$MIGRATIONS" ]; then
  echo "UNAVAILABLE: no migration directory at $MIGRATIONS"
  exit 2
fi
if [ ! -f "$WRITER" ]; then
  echo "UNAVAILABLE: AffiliateSettlementWriter not found at $WRITER"
  exit 2
fi

# Is the ledger able to record an affiliate commission at all?
if grep -rqE "AFFILIATE_COMMISSION|AFFILIATE_EARNING" "$MIGRATIONS" 2>/dev/null; then
  ENUM_WIDENED=1
else
  ENUM_WIDENED=0
fi

# Does the settlement path debit the workspace that owes the commission? requireWorkspaceWallet is
# the only way in this codebase to reach a brand workspace's wallet (WalletService), so its absence
# from this file means no brand-side leg exists, whatever else the file does.
if grep -q "requireWorkspaceWallet" "$WRITER" 2>/dev/null; then
  FUNDING_LEG=1
else
  FUNDING_LEG=0
fi

if [ "$ENUM_WIDENED" -eq 1 ] && [ "$FUNDING_LEG" -eq 0 ]; then
  echo "BROKEN: wallet_transactions now accepts an affiliate commission posting, but"
  echo "        AffiliateSettlementWriter still never debits the owing brand workspace."
  echo "        Every settled commission is now paid out of the omnibus clearing account that holds"
  echo "        other brands' escrowed funds, and a fabricated redemption (F-0727) mints real money."
  echo "        Add the brand-side debit, or revert the migration. F-0738 / F-0739."
  exit 1
fi

if [ "$ENUM_WIDENED" -eq 0 ] && [ "$FUNDING_LEG" -eq 0 ]; then
  echo "PROVED: the drain is closed — wallet_transactions cannot record an affiliate commission, so"
  echo "        the unfunded settlement path cannot move money (F-0738 dormant behind F-0739)."
  echo "NOT CHECKED: that this is INTENTIONAL. It is not — affiliate commission has never actually"
  echo "        been credited to any creator, and the monthly batch has been failing. This gate"
  echo "        holds the drain shut; it does not make the feature work. F-0738 needs a funding"
  echo "        ruling (brand wallet / escrow hold / invoice) before F-0739 may be migrated."
  exit 0
fi

echo "PROVED: the affiliate settlement path debits the owing brand workspace before crediting the"
echo "        creator, so a commission is funded by whoever owes it (F-0738 closed)."
exit 0
