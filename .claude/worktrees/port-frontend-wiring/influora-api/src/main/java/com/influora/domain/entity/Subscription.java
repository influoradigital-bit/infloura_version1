package com.influora.domain.entity;

import com.influora.domain.enums.SubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Workspace subscription — 1:1 with workspaces (a workspace has at most one subscription).
 * V54__subscription_billing.sql, Task 11/14 subscription billing prep batch 1.
 *
 * <p>No TRIALING state — the plan has no time-boxed trial. Free tier is permanently limited; Pro
 * is a paid upgrade with no trial. Status flow: ACTIVE → PAST_DUE (payment failed, grace period)
 * → HALTED (dunning exhausted) or CANCELLED (user-requested).
 *
 * <p><b>seats_purchased:</b> additional seats beyond plan limit (Pro only — add-on SKU). Deferred
 * until seat-invite flow is built (Task 14, per audit Finding 2 — no member-add flow exists yet).
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, unique = true, length = 26)
    private String workspaceId;

    @Column(name = "plan_id", nullable = false, length = 26)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "razorpay_subscription_id", unique = true, length = 100)
    private String razorpaySubscriptionId;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "seats_purchased", nullable = false)
    private int seatsPurchased;

    /**
     * Admin-grant tracking (V63__subscription_comp_fields.sql, Task 25 {@code
     * AdminBillingController}). {@code razorpaySubscriptionId == null} alone is NOT a reliable
     * "this is a comp" signal — it's also true for every ordinary lazily-created Free-tier row
     * ({@link com.influora.service.billing.SubscriptionService#createFreeSubscription}). These
     * four columns make "admin-granted, not backed by a real Razorpay subscription" explicit.
     * Cleared automatically by {@link #linkRazorpaySubscription} the moment a REAL Razorpay
     * subscription is ever linked to this row, so a later genuine conversion can never leave a
     * stale {@code comp=true} flag masking real revenue (see that method's javadoc).
     */
    @Column(name = "is_comp", nullable = false)
    private boolean comp;

    @Column(name = "comp_reason", length = 500)
    private String compReason;

    @Column(name = "comp_granted_by", length = 26)
    private String compGrantedBy;

    @Column(name = "comp_expires_at")
    private Instant compExpiresAt;

    /**
     * The applied webhook envelope's own {@code created_at} (Kabir red-team MEDIUM-1,
     * wiki/errors/subscription-phase2-kabir-redteam.md) — Razorpay guarantees delivery ordering
     * only within retries of the SAME event, not across distinct events for the same
     * subscription. {@link com.influora.service.billing.SubscriptionService#applySubscriptionWebhookUpdate}
     * compares a new delivery's event time against this before overwriting status/period, and
     * no-ops (logs, does not apply) an incoming event that is older than the last one actually
     * applied — otherwise a delayed retry of a stale event could overwrite a fresher one.
     */
    @Column(name = "last_webhook_event_at")
    private Instant lastWebhookEventAt;

    /**
     * Optimistic-locking column (Kabir red-team MEDIUM-2,
     * wiki/errors/subscription-phase2-kabir-redteam.md): the UPDATE branch of {@code
     * applySubscriptionWebhookUpdate} had no {@code @Version} and no row lock, so two genuinely
     * concurrent webhook deliveries with different idempotency keys (e.g. {@code
     * subscription.activated} and {@code subscription.charged} firing near-simultaneously for the
     * same first payment) could both read the same row and lost-update each other with no error.
     * Same pattern as {@link Dispute#getVersion()} / {@code PlatformFeeConfig#getVersion()}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getPlanId() {
        return planId;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public String getRazorpaySubscriptionId() {
        return razorpaySubscriptionId;
    }

    public Instant getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public boolean isCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    public int getSeatsPurchased() {
        return seatsPurchased;
    }

    public boolean isComp() {
        return comp;
    }

    public String getCompReason() {
        return compReason;
    }

    public String getCompGrantedBy() {
        return compGrantedBy;
    }

    public Instant getCompExpiresAt() {
        return compExpiresAt;
    }

    public Instant getLastWebhookEventAt() {
        return lastWebhookEventAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
        touch();
    }

    public void setCancelAtPeriodEnd(boolean cancelAtPeriodEnd) {
        this.cancelAtPeriodEnd = cancelAtPeriodEnd;
        touch();
    }

    public void renewPeriod(Instant newPeriodStart, Instant newPeriodEnd) {
        this.currentPeriodStart = newPeriodStart;
        this.currentPeriodEnd = newPeriodEnd;
        touch();
    }

    /**
     * Links this row to a Razorpay-side subscription. Set on the first {@code
     * subscription.activated}/{@code subscription.charged} webhook after checkout — the row may
     * already exist (the lazily-created Free-tier row, since {@code workspace_id} is UNIQUE and
     * this is a 1:1-with-workspace table) or may not (first-ever billing settings visit was the
     * checkout itself). Also re-used, idempotently, if a workspace re-subscribes after a prior
     * cancellation.
     */
    public void linkRazorpaySubscription(String razorpaySubscriptionId) {
        this.razorpaySubscriptionId = razorpaySubscriptionId;
        // A real Razorpay subscription now backs this row — any prior admin-comp flag is stale
        // and must be cleared, otherwise a workspace that comp'd-then-genuinely-subscribed would
        // keep comp=true forever and be silently excluded from MRR (AdminBillingService#getMetrics
        // filters comp=true out of revenue) despite now being a real paying customer.
        if (razorpaySubscriptionId != null && this.comp) {
            this.comp = false;
            this.compReason = null;
            this.compGrantedBy = null;
            this.compExpiresAt = null;
        }
        touch();
    }

    /**
     * Marks this row as an admin-granted comp/override (Task 25 {@code AdminBillingController} —
     * both {@code POST /admin/billing/comp} and {@code POST /admin/billing/override} route through
     * this same mechanism, see that controller's class javadoc for the framing distinction between
     * the two). {@code reason} is mandatory at the caller level (controller/service validation),
     * never enforced here — this method just records whatever it's given.
     */
    public void markComp(String reason, String grantedByAdminId, Instant expiresAt) {
        this.comp = true;
        this.compReason = reason;
        this.compGrantedBy = grantedByAdminId;
        this.compExpiresAt = expiresAt;
        touch();
    }

    /** Re-points this row at a different plan — used when a webhook-resolved plan differs from what this row currently references (e.g. Free row being upgraded to Pro). */
    public void changePlan(String planId) {
        this.planId = planId;
        touch();
    }

    /**
     * Records the just-applied webhook delivery's own {@code created_at} as the new staleness
     * watermark (see {@link #lastWebhookEventAt} javadoc). {@code null} is a no-op — some callers
     * (e.g. a status-only event whose envelope carried no parseable {@code created_at}) have
     * nothing to record, and must not clobber a previously-recorded watermark with {@code null}.
     */
    public void markWebhookApplied(Instant webhookEventAt) {
        if (webhookEventAt != null) {
            this.lastWebhookEventAt = webhookEventAt;
        }
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Subscription s = new Subscription();

        public Builder id(String id) {
            s.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            s.workspaceId = workspaceId;
            return this;
        }

        public Builder planId(String planId) {
            s.planId = planId;
            return this;
        }

        public Builder status(SubscriptionStatus status) {
            s.status = status;
            return this;
        }

        public Builder razorpaySubscriptionId(String razorpaySubscriptionId) {
            s.razorpaySubscriptionId = razorpaySubscriptionId;
            return this;
        }

        public Builder currentPeriodStart(Instant currentPeriodStart) {
            s.currentPeriodStart = currentPeriodStart;
            return this;
        }

        public Builder currentPeriodEnd(Instant currentPeriodEnd) {
            s.currentPeriodEnd = currentPeriodEnd;
            return this;
        }

        public Builder cancelAtPeriodEnd(boolean cancelAtPeriodEnd) {
            s.cancelAtPeriodEnd = cancelAtPeriodEnd;
            return this;
        }

        public Builder seatsPurchased(int seatsPurchased) {
            s.seatsPurchased = seatsPurchased;
            return this;
        }

        public Builder lastWebhookEventAt(Instant lastWebhookEventAt) {
            s.lastWebhookEventAt = lastWebhookEventAt;
            return this;
        }

        public Builder comp(boolean comp) {
            s.comp = comp;
            return this;
        }

        public Builder compReason(String compReason) {
            s.compReason = compReason;
            return this;
        }

        public Builder compGrantedBy(String compGrantedBy) {
            s.compGrantedBy = compGrantedBy;
            return this;
        }

        public Builder compExpiresAt(Instant compExpiresAt) {
            s.compExpiresAt = compExpiresAt;
            return this;
        }

        public Subscription build() {
            Instant now = Instant.now();
            s.createdAt = now;
            s.updatedAt = now;
            if (s.seatsPurchased == 0) {
                s.seatsPurchased = 1;
            }
            return s;
        }
    }
}
