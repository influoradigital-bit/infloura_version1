package com.influora.domain.entity;

import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_conversations")
public class AiConversation {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "started_by", nullable = false, length = 26)
    private String startedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationStatus status;

    @Column(length = 200)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /**
     * F-0751 — what {@link #workspaceId} points at. {@code WORKSPACE} means a {@code workspaces.id};
     * {@code CREATOR} means a {@code users.id}. There is no database default and none here: see
     * {@link Builder#build()}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_type", nullable = false)
    private ConversationTenantType tenantType;

    protected AiConversation() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public ConversationTenantType getTenantType() {
        return tenantType;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public void setStatus(ConversationStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void markMessageAt(Instant when) {
        this.lastMessageAt = when;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AiConversation c = new AiConversation();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder startedBy(String startedBy) {
            c.startedBy = startedBy;
            return this;
        }

        public Builder status(ConversationStatus status) {
            c.status = status;
            return this;
        }

        public Builder title(String title) {
            c.title = title;
            return this;
        }

        /**
         * F-0751 — REQUIRED. Declares whether {@link #workspaceId(String)} was given a
         * {@code workspaces.id} or a creator's {@code users.id}.
         */
        public Builder tenantType(ConversationTenantType tenantType) {
            c.tenantType = tenantType;
            return this;
        }

        public AiConversation build() {
            c.createdAt = Instant.now();
            if (c.status == null) {
                c.status = ConversationStatus.ACTIVE;
            }
            // F-0751 — deliberately NOT defaulted, here or in the schema. Defaulting to WORKSPACE
            // would make a creator row that forgot the discriminator indistinguishable from a
            // brand row, in exactly the DPDP export and deletion path V74 exists to serve. That
            // silent-wrong-value shape is what caused F-0751 in the first place. Fail at
            // construction instead, where the stack trace names the caller, rather than at INSERT.
            if (c.tenantType == null) {
                throw new IllegalStateException(
                        "AiConversation.tenantType is required (F-0751): say whether workspaceId is a"
                                + " workspaces.id (WORKSPACE) or a creator's users.id (CREATOR)");
            }
            return c;
        }
    }
}
