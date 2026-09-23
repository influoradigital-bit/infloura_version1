package com.influora.domain.enums;

import java.util.Optional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.2) — the CREATOR-audience tool whitelist.
 *
 * <p><b>This is deliberately a separate enum from {@link MeeraToolName}, not an extension of it.</b>
 * {@code MeeraToolName} carries the six BRAND-audience tools and is load-bearing for two gates that
 * would both break if a creator tool were added to it: {@code ToolCallValidatorTest} asserts
 * {@code MeeraToolName.values().length == 6} exactly, and {@code .github/workflows/schema-check.yml}
 * blocks on {@code MeeraToolName}'s name set being equal to the Python {@code TOOL_SCHEMAS} name
 * set. Neither gate looks at this enum, and neither should: the creator tools are described by
 * {@code creator_schemas.py}, a different catalogue for a different audience.
 *
 * <p><b>Six values from SPEC.md &sect;3.1, plus one outside it.</b> {@code get_todays_topics}
 * (T-CONTENT-TOPICS) is not in SPEC.md's &sect;3.1 catalogue -- it is a later addition with its own
 * route and executor, wired the same way every SPEC.md tool is: a name here, a tier in
 * {@code CreatorToolCallValidator}, a scope entry in {@code CreatorToolScopes}, and a route in
 * {@code CreatorMeeraToolController}, all in one change. {@code send_routine_reply},
 * {@code rank_open_campaigns} and {@code draft_application} are still not declared here: they are
 * Phase-B1/B5/B7 and remain names the model can be told about but the server cannot answer, which
 * is exactly why an enum constant is withheld until a route and an executor both exist.
 *
 * <p>Values are lower_snake_case, matching the wire names the model emits, so {@link #name()} is
 * the tool name verbatim and no mapping table is needed anywhere.
 */
public enum CreatorToolName {
    get_my_deals,
    get_brief,
    estimate_my_rate,
    get_my_metrics,
    check_deal_risks,
    draft_reply,
    get_todays_topics;

    /**
     * Exact {@link #name()} match — never case-insensitive, never trimmed. A model that emits
     * {@code "Get_My_Deals"} or {@code " get_my_deals"} has emitted something this server did not
     * publish, and the right answer is {@code UNKNOWN_TOOL_NAME} at the validator rather than a
     * lenient parse that quietly normalises hallucinated input into a real capability.
     *
     * @return empty for null, blank, or any string that is not exactly one of the constants above
     */
    public static Optional<CreatorToolName> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (CreatorToolName tool : values()) {
            if (tool.name().equals(raw)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }
}
