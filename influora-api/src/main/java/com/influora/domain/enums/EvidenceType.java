package com.influora.domain.enums;

/**
 * Meera intelligence v1 (spec &sect;4.2) -- where a claim Meera makes about a creator comes from.
 * Every claim in {@code get_my_content_patterns} carries one, with the number of posts it rests on
 * and their ids. There is deliberately no confidence number anywhere next to it.
 *
 * <p>v1 emits only {@link #CREATOR_POST_DATA}. {@link #ACCOUNT_DATA} and {@link #AUDIENCE_DATA}
 * are reserved for Slice 2 and v2 claims; account and audience facts reach Meera today as context
 * lines, not as claims.
 */
public enum EvidenceType {
    /** The creator's own stored per-post readings ({@code media_metrics}). */
    CREATOR_POST_DATA,
    /** The creator's own account-level numbers ({@code creator_account_insights}). Reserved. */
    ACCOUNT_DATA,
    /** The creator's own audience breakdown. Reserved. */
    AUDIENCE_DATA
}
