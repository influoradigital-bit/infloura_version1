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

    public MeeraCreatorFeatureProperties(
            @Value("${influora.meera.creator-enabled:true}") boolean creatorEnabled) {
        this.creatorEnabled = creatorEnabled;
    }

    public boolean isCreatorEnabled() {
        return creatorEnabled;
    }
}
