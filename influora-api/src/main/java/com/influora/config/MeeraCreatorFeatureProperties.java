package com.influora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Priya gate review defect 4 — the Phase A rollback flag ({@code
 * influora.meera.creator-enabled}, env {@code MEERA_CREATOR_ENABLED}, default {@code true}).
 * When {@code false}, every CREATOR-audience Meera surface — {@code GET}/{@code PUT
 * /creator/agent-preferences}, {@code POST /creator/agent-preferences/consent}, every {@code
 * /creator/meera/**} route, and the public {@code GET /public/creators/{username}/verified}
 * endpoint — returns {@code 404 FEATURE_DISABLED} via the standard {@link
 * com.influora.common.ApiException} envelope, so a bad Phase A deploy can be pulled back without
 * a code rollback.
 *
 * <p>Deliberately a plain {@code @Value}-injected {@code @Component}, NOT a {@code
 * @ConfigurationProperties} class — this codebase has a documented incident (see the long comment
 * block in {@code InfluoraApiApplication}'s {@code @EnableConfigurationProperties} list, and
 * {@code MeeraVoiceAiClient}'s own javadoc for the same reasoning) where a {@code
 * @ConfigurationProperties} class compiled fine but was never registered in that list, and so
 * could not be constructed at all — crashing boot for every live bean that depended on it. A
 * single boolean flag has no need to risk that failure mode: {@code @Value} needs no separate
 * registration step.
 */
@Component
public class MeeraCreatorFeatureProperties {

    private final boolean creatorEnabled;
    private final boolean creatorSendEnabled;

    public MeeraCreatorFeatureProperties(
            @Value("${influora.meera.creator-enabled:true}") boolean creatorEnabled,
            @Value("${influora.meera.creator-send-enabled:false}") boolean creatorSendEnabled) {
        this.creatorEnabled = creatorEnabled;
        this.creatorSendEnabled = creatorSendEnabled;
    }

    public boolean isCreatorEnabled() {
        return creatorEnabled;
    }

    /**
     * T-MEERA-CREATOR-PHASE-B defect D-02 (QA-TECH-0912.md question 15, ruling 1) — the separate,
     * default-<b>false</b> enable step for the one creator capability that puts a message in front of
     * a brand under the creator's name ({@code send_routine_reply}).
     *
     * <p><b>Why this exists before the route it guards.</b> {@code send_routine_reply} is already in
     * the {@code scope} claim of every level-1 and level-2 creator's on-behalf token
     * ({@link com.influora.service.meera.CreatorToolScopes#SCOPE_LEVEL_1}), because scope is minted
     * as a ceiling and {@code OnBehalfAuthResolver#requireScope} is string membership against that
     * claim with no registry behind it. So there is no intermediate state in which a
     * {@code @PostMapping("/send_routine_reply")} exists and the grant does not: the capability would
     * go live on the code deploy that adds the route, for every level-1 creator's next turn. This
     * flag moves that moment to a config change that can be reviewed, staged and reverted without a
     * code rollback — the same property shape as {@link #isCreatorEnabled()}, deliberately NOT the
     * same property, so pulling sends back does not also take down every creator read.
     *
     * <p>Separate from {@code influora.meera.creator-enabled} on purpose, and default false rather
     * than true on purpose: {@code creator-enabled} defaults true because it guards reads that are
     * safe to serve, and this one defaults false because the safe state for a send is "off".
     *
     * <p>The route does not exist yet. {@code com.influora.architecture.CreatorSendGateTest} is the
     * control that makes the commit adding it come back here: it pins as empty the set of send-capable
     * routes served by <b>any</b> {@code @Controller} class under {@code src/main/java} — not just
     * {@code CreatorMeeraToolController}, which is the scope hole that gate shipped with twice — and
     * requires the class serving any entry in it to be gated on this flag.
     */
    public boolean isCreatorSendEnabled() {
        return creatorSendEnabled;
    }
}
