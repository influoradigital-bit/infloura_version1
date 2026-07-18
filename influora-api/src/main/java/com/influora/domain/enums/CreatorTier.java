package com.influora.domain.enums;

/**
 * Admin-assignable creator tier override ({@code creator_profiles.tier_override},
 * V20260718160000__creator_tier_override.sql) — mirrors the {@code tier} union in {@code
 * src/admin/types/admin.types.ts} exactly ({@code NANO|MICRO|MID|MACRO}).
 *
 * <p>This codebase persists NO tier concept otherwise: the admin surface derives tier from {@code
 * total_followers} at read time (see {@code AdminCreatorService.deriveTier}). When {@code
 * tier_override} is null on a profile the derived value stands unchanged (preserving current
 * behavior for every existing row); when an admin sets it via {@code creatorApi.adjustTier}
 * (PUT /admin/creators/{id}/tier) it wins over the derived tier. Jackson serializes this enum by
 * name, so this enum and the frontend union must stay in lockstep.
 */
public enum CreatorTier {
    NANO,
    MICRO,
    MID,
    MACRO
}
