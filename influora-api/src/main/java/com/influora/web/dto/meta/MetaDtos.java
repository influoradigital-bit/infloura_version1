package com.influora.web.dto.meta;

import java.time.Instant;
import java.util.List;

/** Meta OAuth request/response records, grouped per the {@code MoneyDtos}/{@code CampaignDtos} convention. */
public final class MetaDtos {

    private MetaDtos() {}

    /** Response for {@code GET /meta/oauth/authorize} — frontend navigates the browser to {@code authorizationUrl}. */
    public record MetaAuthorizeResponse(String authorizationUrl, String state) {}

    /**
     * Response for {@code GET /meta/oauth/callback} once the token has been exchanged and stored.
     *
     * <p>{@code accountType} (Creator AI Co-pilot Tier-1, API-CONTRACT.md §4.2) — {@code
     * "personal"} means OAuth succeeded but the linked IG is not a Business/Creator account and
     * {@code connected} is {@code false}; {@code "business"} means a usable account resolved and
     * {@code connected} is {@code true}. {@code null} on the (unchanged) brand OAuth path, which
     * does not resolve or report this field.
     */
    public record MetaCallbackResponse(
            boolean connected, List<String> grantedScopes, String accountType) {}

    /** Response for the Meta connection-status read ({@code MetaConnectionService.getStatus}). */
    public record MetaConnectionStatusResponse(
            boolean connected,
            String handle,
            Long followers,
            Instant connectedAt,
            List<String> grantedScopes) {}

    /** Response for the Meta disconnect action ({@code MetaConnectionService.disconnect}). */
    public record MetaDisconnectResponse(boolean disconnected) {}
}
