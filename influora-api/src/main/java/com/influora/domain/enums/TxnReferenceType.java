package com.influora.domain.enums;

public enum TxnReferenceType {
    COLLABORATION,
    ESCROW_HOLD,
    MILESTONE,
    CAMPAIGN,
    DEPOSIT_ORDER,
    MANUAL,
    // [F-0402] referenceId is the settled AffiliateEarning's own id.
    AFFILIATE_EARNING
}
