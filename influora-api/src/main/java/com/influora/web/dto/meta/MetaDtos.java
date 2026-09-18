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

    /**
     * Response for the Meta connection-status read ({@code MetaConnectionService.getStatus}).
     *
     * <p>F-0950 — {@code authPath}, {@code profilePictureUrl} and {@code mediaCount} were added so
     * the Settings card can show WHICH account is connected, not just that one is. Meta's App
     * Review for {@code instagram_business_basic} requires the screencast to show the connected
     * account's username or profile picture; before this, the API returned the handle and nothing
     * displayed it.
     *
     * <p>{@code authPath} is {@code "FACEBOOK_LOGIN"} or {@code "INSTAGRAM_LOGIN"} ({@code null}
     * while disconnected). It also stops the card claiming a Facebook Page is connected for a
     * creator who connected with Instagram login and has no Page. {@code profilePictureUrl} and
     * {@code mediaCount} are {@code null} when Meta did not return them — the Facebook-Login status
     * read does not fetch them.
     */
    public record MetaConnectionStatusResponse(
            boolean connected,
            String handle,
            Long followers,
            Instant connectedAt,
            List<String> grantedScopes,
            String authPath,
            String profilePictureUrl,
            Long mediaCount) {}

    /** Response for the Meta disconnect action ({@code MetaConnectionService.disconnect}). */
    public record MetaDisconnectResponse(boolean disconnected) {}
}
