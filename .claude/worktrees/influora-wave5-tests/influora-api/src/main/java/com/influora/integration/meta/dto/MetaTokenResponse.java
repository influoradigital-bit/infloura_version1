package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth token exchange response — shared shape for the short-lived code exchange, the
 * long-lived exchange, and the refresh call (Meta reuses this response body for all three).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetaTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresInSeconds) {}
