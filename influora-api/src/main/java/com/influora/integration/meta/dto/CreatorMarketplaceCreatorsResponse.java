package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /{ig-user-id}/creator_marketplace_creators} — Meta Creator Marketplace search
 * (T-CREATORCONNECT-0902 §Meta Creator Marketplace client). Ships DARK: requires the
 * {@code instagram_creator_marketplace_discovery} Advanced Access scope this app cannot yet
 * submit for App Review (wiki/decisions/2026-09-02-what-we-need.md §1.2/§4.2), gated behind
 * {@code influora.meta.creator-marketplace.enabled} (default false). Wrapped by {@code
 * MetaGraphApiClient.get} so rate-limit tracking applies like every other Graph call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatorMarketplaceCreatorsResponse(List<Creator> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Creator(
            String id,
            String username,
            @JsonProperty("is_account_verified") Boolean isAccountVerified,
            String biography,
            String country,
            @JsonProperty("profile_picture_url") String profilePictureUrl,
            @JsonProperty("has_brand_partnership_experience") Boolean hasBrandPartnershipExperience,
            @JsonProperty("past_brand_partnership_partners") List<String> pastBrandPartnershipPartners,
            // Untyped: Meta's own schema for this nested object is not finalized/documented at the
            // time this client was written dark (no live App Review access to verify against) —
            // captured as opaque JSON rather than guessing a shape and silently dropping fields.
            Object insights) {}
}
