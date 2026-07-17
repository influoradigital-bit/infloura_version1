package com.influora.web.dto.creatorplatformfee;

/**
 * Creator-facing platform-fee transparency DTOs — Task #27 (P0-V2, Creator Week 4). Matches
 * {@code 10_CREATOR_PAYMENTS_SPEC.md} §7 read-only response shape for Ananya's wallet fee UI.
 */
public final class CreatorPlatformFeeDtos {

    private CreatorPlatformFeeDtos() {}

    /**
     * Global creator-side take rate exposed to authenticated creators. {@code source} is
     * {@code GLOBAL_DEFAULT} until per-plan / per-creator overrides ship in a later wave.
     */
    public record PlatformFeeResponse(int feeBps, double feePercent, String source) {}
}
