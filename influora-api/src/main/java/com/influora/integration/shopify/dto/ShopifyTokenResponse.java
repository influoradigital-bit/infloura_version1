package com.influora.integration.shopify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST https://{shop}/admin/oauth/access_token} response. Unlike Meta's short-lived ->
 * long-lived two-step exchange, Shopify's free OAuth "custom app" flow issues a single
 * non-expiring access token directly from the authorization code -- there is no refresh step
 * (token is valid until the merchant uninstalls the app or the scope changes), so this is the
 * ONLY exchange call in the Shopify flow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShopifyTokenResponse(
        @JsonProperty("access_token") String accessToken, @JsonProperty("scope") String scope) {}
