package com.influora.web.dto.meera;

/**
 * Wire DTO for the brand-facing {@code OPTION_TAPPED} flywheel write (Phase 2 item 2.3 — Meera:
 * Label-to-Moat build plan §2.3, Priya's Q3 ruling). Browser-facing camelCase convention, same as
 * {@link MeeraToolDtos} — NOT the Python-consumed snake_case seam {@link MeeraContextDtos} uses.
 */
public final class MeeraInteractionDtos {

    private MeeraInteractionDtos() {}

    /**
     * {@code POST /workspaces/{workspaceId}/meera/interactions/option-tapped} body. {@code
     * toolName} is typically {@code "present_options"} (the only tool that renders tappable
     * option cards today); {@code recommended} echoes whether the tapped option was Meera's
     * recommended one (for the eventual funnel read). No {@code workspaceId}/{@code campaignId}
     * field — workspace is resolved from the authenticated principal (never the path param, IDOR
     * fix), and this event has no natural campaign association yet.
     */
    public record OptionTappedRequest(String sessionId, String toolName, Boolean recommended) {}
}
