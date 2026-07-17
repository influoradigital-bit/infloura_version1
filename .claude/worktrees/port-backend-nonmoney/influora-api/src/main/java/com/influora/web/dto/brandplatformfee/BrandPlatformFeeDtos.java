package com.influora.web.dto.brandplatformfee;

/**
 * Brand-facing platform-fee transparency DTOs — Task B2 (wiki/tech/BRAND_EXECUTION_PLAN.md).
 * Mirrors {@code CreatorPlatformFeeDtos.PlatformFeeResponse}'s shape/style ({@code feeBps},
 * {@code feePercent}, {@code source}) for consistency across the two transparency endpoints, plus
 * a {@code copy} field: the CEO-mandated brand-facing string (leadership ruling item 5) that the
 * backlog explicitly scopes B2 to return ("bps + copy"), unlike the creator endpoint where that
 * string is owned by the frontend.
 */
public final class BrandPlatformFeeDtos {

    private BrandPlatformFeeDtos() {}

    /**
     * Global brand-side take rate exposed to authenticated brand workspace members. {@code source}
     * is {@code GLOBAL_DEFAULT} until per-plan / per-brand overrides ship in a later wave. {@code
     * copy} is a fixed literal (leadership ruling item 5) — not derived from {@code feeBps} at
     * runtime.
     */
    public record PlatformFeeResponse(int feeBps, double feePercent, String source, String copy) {}
}
