package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention window + master switch for {@code MeeraInteractionLogRetentionPurgeJob} — the age-based
 * bulk delete against {@code meera_interaction_log} (Phase 2 item 2.3's flywheel event log).
 *
 * <p><b>Why this exists.</b> Priya's PARTIAL-2 ruling (wiki/build/partials-resolution-plan.md) locks
 * this purge as a HARD, MANDATORY predecessor to ANY future read/join/analytics consumer of this
 * table. Kabir's L1 finding (wiki/build/phase2-kabir-security.md) independently flags the same
 * unbounded-row-growth gap. Landing the purge now — before any reader exists — is ops hygiene,
 * decoupled from and not blocking that gate.
 *
 * <p><b>{@code enabled} defaults {@code false}</b> — same conservative-default convention as {@link
 * BrandSafetyScoringProperties} (feature-flagged) and {@code influora.cleanup.dry-run} (defaults to
 * the non-destructive choice everywhere except prod, see {@code application-prod.yml}). With it off,
 * {@code MeeraInteractionLogRetentionPurgeJob} never issues the delete — no environment starts
 * purging rows just because this class exists on the classpath. Turn it on explicitly per
 * environment via {@code MEERA_INTERACTION_LOG_RETENTION_ENABLED}.
 *
 * <p>{@code retentionDays} defaults to 180 — the exact window the V20260721160000 migration's own
 * comment names ("Retention: 180 days (Priya's B7 ruling, phase2-priya-review.md)").
 */
@ConfigurationProperties(prefix = "influora.meera-interaction-log-retention")
public class MeeraInteractionLogRetentionProperties {

    /**
     * Master switch. {@code false} (default) means {@code MeeraInteractionLogRetentionPurgeJob}'s
     * {@code @Scheduled} method still fires but returns immediately without touching the database —
     * the pre-existing behaviour (unbounded growth) is preserved until someone deliberately opts in.
     */
    private boolean enabled = false;

    /**
     * How many days of {@code meera_interaction_log} rows to keep. A value {@code <= 0} would purge
     * everything on the next run — the job does not special-case that (an operator setting 0 has
     * unambiguously asked for "keep nothing"), but it is far outside the 180-day design default, so
     * treat any deploy-time override with the same care as any other destructive-by-design knob.
     */
    private int retentionDays = 180;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
