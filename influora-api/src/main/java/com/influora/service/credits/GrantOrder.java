package com.influora.service.credits;

import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.enums.CreditBucket;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.3, owner ruling R4) — pure spend-order sort. Free credit
 * (FREE_MONTHLY, FREE_SIGNUP, ADMIN) is always spent before PAID; within each of those two groups,
 * the grant closest to expiry goes first (NULL — never expires — sorts last); ties break on
 * {@code granted_at} ascending (oldest grant first).
 */
public final class GrantOrder {

    private GrantOrder() {}

    public static List<CreatorCreditGrant> sort(List<CreatorCreditGrant> grants, Instant now) {
        return grants.stream()
                .sorted(
                        Comparator.comparingInt((CreatorCreditGrant g) -> groupRank(g.getBucket()))
                                .thenComparing(g -> g.getExpiresAt() == null ? Instant.MAX : g.getExpiresAt())
                                .thenComparing(CreatorCreditGrant::getGrantedAt))
                .toList();
    }

    /** 0 = free-before-paid group (FREE_MONTHLY/FREE_SIGNUP/ADMIN), 1 = PAID. */
    private static int groupRank(CreditBucket bucket) {
        return bucket == CreditBucket.PAID ? 1 : 0;
    }
}
