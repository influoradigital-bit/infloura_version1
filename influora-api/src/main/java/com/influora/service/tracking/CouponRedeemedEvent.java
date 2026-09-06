package com.influora.service.tracking;

/**
 * Internal-only signal that a {@code CouponRedemption} row has been written — published by {@link
 * RedemptionWriter#doRedeem} from inside that method's transaction, consumed by {@link
 * AffiliateEarningRecordingListener} at {@code AFTER_COMMIT} to record the creator's commission.
 *
 * <p>NOT a user-facing notification event. It exists solely to move the commission write OUT of the
 * sale's transaction — see the listener's javadoc for why that ordering matters and what it fixes.
 *
 * <p>Carries only the redemption id, deliberately, rather than the entity: by the time the listener
 * runs the transaction has committed and the entity is detached, so the listener re-reads it. That
 * also means the listener never operates on a redemption that was rolled back — if the transaction
 * failed, {@code AFTER_COMMIT} never fires at all.
 */
record CouponRedeemedEvent(String redemptionId) {}
