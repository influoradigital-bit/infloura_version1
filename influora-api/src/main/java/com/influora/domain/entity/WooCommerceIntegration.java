package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A brand workspace's connected WooCommerce store (V29 {@code woocommerce_integrations}), Wave D
 * task D2 (webhook-based, no OAuth app-install flow -- see {@code WooCommerceConnectController}
 * javadoc for why this differs structurally from {@link ShopifyIntegration}).
 *
 * <p>[SEC: Kabir sign-off gate] {@code encryptedWebhookSecret} is AES-256-GCM ciphertext produced
 * by {@code WooCommerceIntegrationService} -- this entity never sees or exposes plaintext; there is
 * deliberately no plain getter/setter pair that could be mistaken for one. Mirrors {@link
 * ShopifyIntegration}'s discipline exactly, with one structural difference: Shopify's {@code
 * encryptedAccessToken} is an API credential used to CALL Shopify, whereas this entity's secret is
 * used only to VERIFY inbound webhook signatures -- this integration never calls out to the brand's
 * WooCommerce site at all.
 *
 * <p>{@code siteUrl} is the only identifier {@code WooCommerceWebhookController} trusts from an
 * inbound webhook (via the {@code X-WC-Webhook-Source} header WooCommerce sends with every
 * delivery) to resolve which integration's secret to verify against -- see that controller's class
 * javadoc for the full trust model (this table's {@code UNIQUE(site_url)} is what maps a claimed
 * site to the correct workspace + secret).
 */
@Entity
@Table(name = "woocommerce_integrations")
public class WooCommerceIntegration {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "site_url", nullable = false, length = 500)
    private String siteUrl;

    @Column(name = "encrypted_webhook_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedWebhookSecret;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WooCommerceIntegration() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public String getEncryptedWebhookSecret() {
        return encryptedWebhookSecret;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Replaces the encrypted webhook secret on reconnect/rotate (does not change id/workspace/site). */
    public void rotateSecret(String encryptedWebhookSecret) {
        this.encryptedWebhookSecret = encryptedWebhookSecret;
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
        private final WooCommerceIntegration w = new WooCommerceIntegration();

        public Builder id(String id) {
            w.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            w.workspaceId = workspaceId;
            return this;
        }

        public Builder siteUrl(String siteUrl) {
            w.siteUrl = siteUrl;
            return this;
        }

        public Builder encryptedWebhookSecret(String encryptedWebhookSecret) {
            w.encryptedWebhookSecret = encryptedWebhookSecret;
            return this;
        }

        public Builder connectedAt(Instant connectedAt) {
            w.connectedAt = connectedAt;
            return this;
        }

        public WooCommerceIntegration build() {
            Instant now = Instant.now();
            if (w.connectedAt == null) {
                w.connectedAt = now;
            }
            w.createdAt = now;
            w.updatedAt = now;
            return w;
        }
    }
}
