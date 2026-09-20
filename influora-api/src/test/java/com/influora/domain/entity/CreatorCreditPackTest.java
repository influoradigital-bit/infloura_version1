package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-CREATOR-CREDITS-SEARCH K1 [vikram] -- unit tests for {@link CreatorCreditPack}'s builder. */
class CreatorCreditPackTest {

    @Test
    @DisplayName("Builder carries credits through as tenths, unscaled")
    void builderCarriesCreditsAsTenths() {
        CreatorCreditPack pack =
                CreatorCreditPack.builder()
                        .id("01M2Z6C91ZM0FKX6NT642Z3YZ8")
                        .code("STARTER")
                        .name("Starter")
                        .credits(500) // 50.0 credits
                        .pricePaise(14900)
                        .sortOrder(1)
                        .active(true)
                        .build();

        assertEquals(500, pack.getCredits());
        assertEquals(14900, pack.getPricePaise());
        assertTrue(pack.isActive());
    }

    @Test
    @DisplayName("Builder stamps createdAt and updatedAt on build()")
    void builderStampsTimestamps() {
        CreatorCreditPack pack =
                CreatorCreditPack.builder()
                        .id("01M2Z6C91ZM0FKX6NT642Z3YZ8")
                        .code("STARTER")
                        .name("Starter")
                        .credits(500)
                        .pricePaise(14900)
                        .build();

        org.junit.jupiter.api.Assertions.assertNotNull(pack.getCreatedAt());
        org.junit.jupiter.api.Assertions.assertNotNull(pack.getUpdatedAt());
    }
}
