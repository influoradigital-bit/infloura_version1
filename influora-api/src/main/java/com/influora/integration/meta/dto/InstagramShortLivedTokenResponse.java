package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Business Login for Instagram code-exchange response (T-IGLOGIN-0820).
 *
 * <p>Deliberately NOT {@link MetaTokenResponse}: the Instagram short-lived exchange returns a
 * different body. It carries {@code user_id} — the Instagram user id — which on this path REPLACES
 * the {@code GET /me/accounts} lookup the Facebook path uses to find a linked Page's
 * {@code instagram_business_account}. There is no Page here to look one up from, so losing this
 * field means losing the account id entirely.
 *
 * <p>It also has no {@code expires_in}: the short-lived token is ~1 hour and callers must
 * immediately exchange it for a long-lived one, which does report an expiry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstagramShortLivedTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("user_id") String userId,
        @JsonProperty("permissions") List<String> permissions) {}
