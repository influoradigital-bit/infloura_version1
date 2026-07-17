package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A brand workspace's connected Shopify store (V27 {@code shopify_integrations}), Wave D task D1
 * (free OAuth "custom app" path, no $99/mo fee -- see {@code ShopifyOAuthService} javadoc).
 *
 * <p>[SEC: Kabir sign-off gate] {@code encryptedAccessToken} is AES-256-GCM ciphertext produced by
 * {@code ShopifyTokenStorage} -- this entity never sees or exposes plaintext; there is deliberately
 * no plain getter/setter pair that could be mistaken for one. Mirrors {@link MetaOAuthToken}'s
 * discipline exactly.
 *
 * <p>{@code shopDomain} is the only identifier {@code ShopifyWebhookController} trusts from an
 * inbound webhook to resolve a workspace -- see that controller's class javadoc for the full trust
 * model (the HMAC signature proves the request came from Shopify for *some* shop; this table's
 * {@code UNIQUE(shop_domain)} is what maps that shop to the correct workspace).
 */
@Entity
@Table(name = "shopify_integrations")
public class ShopifyIntegration {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "shop_domain", nullable = false, length = 255)
    private String shopDomain;

    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_scopes", columnDefinition = "json")
    private String grantedScopesJson;

    @Column(name = "webhook_secret", length = 255)
    private String webhookSecret;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopifyIntegration() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getShopDomain() {
        return shopDomain;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public String getGrantedScopesJson() {
        return grantedScopesJson;
    }

    public String getWebhookSecret() {
        return webhookSecret;
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

    /** Replaces the encrypted token + scopes on reconnect/re-auth (does not change id/workspace/shop). */
    public void rotateToken(String encryptedAccessToken, String grantedScopesJson) {
        this.encryptedAccessToken = encryptedAccessToken;
        this.grantedScopesJson = grantedScopesJson;
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
        private final ShopifyIntegration s = new ShopifyIntegration();

        public Builder id(String id) {
            s.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            s.workspaceId = workspaceId;
            return this;
        }

        public Builder shopDomain(String shopDomain) {
            s.shopDomain = shopDomain;
            return this;
        }

        public Builder encryptedAccessToken(String encryptedAccessToken) {
            s.encryptedAccessToken = encryptedAccessToken;
            return this;
        }

        public Builder grantedScopesJson(String grantedScopesJson) {
            s.grantedScopesJson = grantedScopesJson;
            return this;
        }

        public Builder webhookSecret(String webhookSecret) {
            s.webhookSecret = webhookSecret;
            return this;
        }

        public Builder connectedAt(Instant connectedAt) {
            s.connectedAt = connectedAt;
            return this;
        }

        public ShopifyIntegration build() {
            Instant now = Instant.now();
            if (s.connectedAt == null) {
                s.connectedAt = now;
            }
            s.createdAt = now;
            s.updatedAt = now;
            return s;
        }
    }
}
