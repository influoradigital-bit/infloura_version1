package com.influora.web.dto.shopify;

import java.util.List;

/** Shopify OAuth request/response records, grouped per the {@code MetaDtos} convention. */
public final class ShopifyDtos {

    private ShopifyDtos() {}

    /** Response for {@code GET /shopify/oauth/authorize} — frontend navigates the browser to {@code authorizationUrl}. */
    public record ShopifyAuthorizeResponse(String authorizationUrl, String state) {}

    /** Response for {@code GET /shopify/oauth/callback} once the token has been exchanged and stored. */
    public record ShopifyCallbackResponse(boolean connected, String shopDomain, List<String> grantedScopes) {}
}
