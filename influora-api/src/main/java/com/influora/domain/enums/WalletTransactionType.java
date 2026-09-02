package com.influora.domain.enums;

public enum WalletTransactionType {
    DEPOSIT,
    WITHDRAWAL,
    ESCROW_HOLD,
    ESCROW_RELEASE,
    ESCROW_REFUND,
    PLATFORM_FEE,
    PAYOUT,
    ADJUSTMENT,
    // [F-0402] Creator-side credit for a settled AffiliateEarning -- posted by
    // AffiliateSettlementWriter#doSettleCreator, mirroring ESCROW_RELEASE's clearing-wallet ->
    // creator-wallet shape but kept distinct so affiliate commission is separately reportable
    // from milestone-release income rather than conflated under ESCROW_RELEASE.
    AFFILIATE_COMMISSION
}
