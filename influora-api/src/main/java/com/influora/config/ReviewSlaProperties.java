package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The brand's review clock — how long a brand has to act on a draft before Influora steps in.
 *
 * <p>Owner's ruling: a brand gets <b>3 working days</b> to approve, reject or ask for a revision on
 * a submitted draft, and <b>2 working days</b> to re-review each resubmission. If the brand does
 * nothing, the deliverable is <b>escalated to the Influora team</b> — it is never auto-approved and
 * it never triggers a payment. Both clocks are counted in working days by {@code WorkingDays}
 * (Monday to Friday), which is the only place that definition lives.
 *
 * <p>The third number in the rule — at most <b>2 revision rounds</b> — is deliberately NOT
 * duplicated here. It is already a persisted, per-deal value ({@code Collaboration.maxRevisions},
 * defaulted by {@code Collaboration.DEFAULT_MAX_REVISIONS = 2}) and already enforced at {@code
 * BrandDeliverableService#requestRevision}. Adding a second copy as config would give the same rule
 * two sources of truth that could disagree, which is exactly how a "configurable" limit ends up
 * unenforced.
 */
@ConfigurationProperties(prefix = "influora.review-sla")
public class ReviewSlaProperties {

    /**
     * Master switch for {@code BrandReviewSlaEscalationJob}. Defaults {@code true} because the
     * ruling is a product promise, not an experiment: with it off, a brand can sit on a draft
     * indefinitely and the creator has no recourse, which is the exact state this work exists to
     * end. Off is still available per-environment as a kill switch if the escalation queue ever
     * needs to be paused.
     *
     * <p>Switching it OFF never auto-approves or auto-pays anything — it only stops the team being
     * told. Nothing in this feature can move money in either state.
     */
    private boolean escalationEnabled = true;

    /** Working days the brand has to act on a first submission (status {@code SUBMITTED}). */
    private int firstReviewWorkingDays = 3;

    /**
     * Working days the brand has to act on a resubmission after asking for a revision (status
     * {@code RESUBMITTED}). Each revision round restarts this clock from the moment the creator
     * resubmits.
     */
    private int revisionReviewWorkingDays = 2;

    /**
     * Most deliverables one run may escalate. A bound, not a filter: anything left over is picked
     * up by the next run. It exists so that the first run after this ships — which sees every
     * draft a brand has ever left sitting, not just today's — lands in the team's queue at a rate
     * a human can work through instead of all at once.
     */
    private int batchLimit = 200;

    public boolean isEscalationEnabled() {
        return escalationEnabled;
    }

    public void setEscalationEnabled(boolean escalationEnabled) {
        this.escalationEnabled = escalationEnabled;
    }

    public int getFirstReviewWorkingDays() {
        return firstReviewWorkingDays;
    }

    public void setFirstReviewWorkingDays(int firstReviewWorkingDays) {
        this.firstReviewWorkingDays = firstReviewWorkingDays;
    }

    public int getRevisionReviewWorkingDays() {
        return revisionReviewWorkingDays;
    }

    public void setRevisionReviewWorkingDays(int revisionReviewWorkingDays) {
        this.revisionReviewWorkingDays = revisionReviewWorkingDays;
    }

    public int getBatchLimit() {
        return batchLimit;
    }

    public void setBatchLimit(int batchLimit) {
        this.batchLimit = batchLimit;
    }
}
