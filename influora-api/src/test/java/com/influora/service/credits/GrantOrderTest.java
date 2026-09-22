package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.enums.CreditBucket;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-CREATOR-CREDITS-V2 (SPEC.md A1, owner ruling R4). */
class GrantOrderTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private static CreatorCreditGrant grant(
            String id, CreditBucket bucket, Instant grantedAt, Instant expiresAt) {
        return CreatorCreditGrant.of(id, "creator-1", bucket, 10, grantedAt, expiresAt, "ref:" + id);
    }

    @Test
    @DisplayName("sortsFreeBeforePaidThenSoonestExpiry")
    void sortsFreeBeforePaidThenSoonestExpiry() {
        CreatorCreditGrant paidSoon =
                grant("paid-soon", CreditBucket.PAID, NOW.minusSeconds(3000), NOW.plusSeconds(1000));
        CreatorCreditGrant paidNeverExpires =
                // PAID grants always carry an expiry in practice, but the sort must still handle a
                // null gracefully (sorts last within its own group) rather than NPE.
                grant("paid-null", CreditBucket.PAID, NOW.minusSeconds(3000), null);
        CreatorCreditGrant freeMonthlySoon =
                grant("monthly-soon", CreditBucket.FREE_MONTHLY, NOW.minusSeconds(2000), NOW.plusSeconds(500));
        CreatorCreditGrant freeSignupNeverExpires =
                grant("signup", CreditBucket.FREE_SIGNUP, NOW.minusSeconds(9000), null);
        CreatorCreditGrant adminSameExpiryAsMonthlyButOlder =
                grant("admin-tie", CreditBucket.ADMIN, NOW.minusSeconds(9999), NOW.plusSeconds(500));

        List<CreatorCreditGrant> sorted =
                GrantOrder.sort(
                        List.of(paidSoon, paidNeverExpires, freeMonthlySoon, freeSignupNeverExpires,
                                adminSameExpiryAsMonthlyButOlder),
                        NOW);

        List<String> ids = sorted.stream().map(CreatorCreditGrant::getId).toList();
        // Free-before-paid: the first two entries must both be from the free/admin group.
        assertEquals(
                List.of("admin-tie", "monthly-soon", "signup", "paid-soon", "paid-null"),
                ids,
                "expected: free/admin group (soonest-expiry first, oldest-granted breaks the tie), then"
                        + " PAID group (soonest-expiry first, NULL last); got: "
                        + ids);
    }

    @Test
    @DisplayName("a single grant sorts trivially")
    void singleGrantSortsTrivially() {
        CreatorCreditGrant only = grant("only", CreditBucket.FREE_SIGNUP, NOW, null);
        assertEquals(List.of("only"), GrantOrder.sort(List.of(only), NOW).stream().map(CreatorCreditGrant::getId).toList());
    }
}
