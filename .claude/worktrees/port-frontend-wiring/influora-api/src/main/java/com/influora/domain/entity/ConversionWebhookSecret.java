package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A brand workspace's HMAC signing secret for {@code ConversionWebhookController}'s public {@code
 * /webhooks/conversion} and {@code /webhooks/redemption} endpoints (V31 {@code
 * conversion_webhook_secrets}).
 *
 * <p>[SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] These two endpoints shipped
 * (Wave A, task A2) with NO signature verification, and the E2 review's downgrade of the
 * amount-entropy pre-poisoning HIGH to accepted-risk was explicitly conditioned on Wave D1 adding
 * per-brand HMAC verification here before launch. D1 only added HMAC to its OWN new {@code
 * /webhooks/shopify} route; this controller was never touched. This entity is the fix's trust
 * anchor.
 *
 * <p><b>Trust model -- differs from both {@link ShopifyIntegration} and {@link
 * WooCommerceIntegration}.</b> Shopify signs with ONE app-level secret (OAuth app-install flow);
 * WooCommerce brands generate their OWN secret in a third-party admin panel we have no
 * relationship with. Neither shape fits here: {@code /webhooks/conversion}/{@code
 * /webhooks/redemption} are called by an arbitrary brand's own commerce backend / custom checkout
 * -- there is no OAuth app-install flow for a "generic checkout" and no third-party admin UI to
 * copy a secret out of. Instead, WE mint the secret server-side ({@code
 * ConversionWebhookSecretService#generate}) and hand it to the brand ONCE at creation time; the
 * brand configures their own backend/pixel to send it back as an {@code X-Influora-Signature}
 * HMAC-SHA256 header on every call. The plaintext is never persisted or retrievable again after
 * that one response -- only this entity's AES-256-GCM ciphertext is stored, mirroring {@code
 * WooCommerceIntegration}'s "never see plaintext again" discipline exactly.
 *
 * <p>[SEC: Kabir sign-off gate] {@code encryptedSecret} is AES-256-GCM ciphertext produced by
 * {@code ConversionWebhookSecretService} -- this entity never exposes plaintext; there is
 * deliberately no plain getter/setter pair that could be mistaken for one.
 */
@Entity
@Table(name = "conversion_webhook_secrets")
public class ConversionWebhookSecret {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversionWebhookSecret() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Replaces the encrypted secret on rotate (does not change id/workspace); un-revokes if previously revoked. */
    public void rotateSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
        this.revoked = false;
        touch();
    }

    public void revoke() {
        this.revoked = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ConversionWebhookSecret s = new ConversionWebhookSecret();

        public Builder id(String id) {
            s.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            s.workspaceId = workspaceId;
            return this;
        }

        public Builder encryptedSecret(String encryptedSecret) {
            s.encryptedSecret = encryptedSecret;
            return this;
        }

        public ConversionWebhookSecret build() {
            Instant now = Instant.now();
            s.createdAt = now;
            s.updatedAt = now;
            return s;
        }
    }
}
