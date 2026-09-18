package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * F-0891 — {@code GET graph.instagram.com/me?fields=user_id,username,account_type} with a
 * SHORT-LIVED Instagram-Login token, read before the long-lived exchange.
 *
 * <p><b>Why a separate record from {@link InstagramUserResponse}.</b> That one is bound by the
 * Facebook-Login clients and carries fields ({@code biography}, {@code website}) the Instagram-Login
 * user node does not list; adding a component to it would also change its arity for every existing
 * caller. This record asks for the three fields that page documents and nothing else — on this node
 * one unsupported field fails the whole request.
 *
 * <p><b>What it is for.</b> On 2026-09-17, 11 of 12 Instagram connects reached a valid short-lived
 * token and then died on {@code 400 code 100 "Unsupported request - method type: get"} from the
 * long-lived exchange, while one succeeded — same app id, same secret, same response shape, same
 * hour. The only remaining variable is the account, and {@code account_type} is the one property of
 * it Meta will tell us before the exchange.
 *
 * <p>Meta's Instagram-Login reference lists {@code account_type} as a requestable field but does not
 * publish its value set, so {@code accountType} is treated as an opaque string: callers act only on
 * the values they positively recognise and let anything else through.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstagramAccountTypeResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("username") String username,
        @JsonProperty("account_type") String accountType) {}
