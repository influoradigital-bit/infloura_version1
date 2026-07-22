package com.influora.domain.entity;

import com.influora.domain.enums.MeeraInteractionEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code meera_interaction_log} (V20260721160000) — Phase 2 item 2.3's flywheel event log (Meera:
 * Label-to-Moat build plan §2.3). Immutable, append-only — mirrors {@link PortfolioEvent}/{@link
 * CreatorNudgeLog}'s builder-only, no-mutator shape; an interaction event is a fact, never edited.
 *
 * <p><b>Never write raw free text here.</b> {@link #revisionReason} MUST already be redacted via
 * {@code com.influora.common.SensitiveTextRedactor} before this entity is built — see {@code
 * MeeraInteractionLogService#record}, the single write entry point that enforces this.
 */
@Entity
@Table(name = "meera_interaction_log")
public class MeeraInteractionLog {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private MeeraInteractionEventType eventType;

    @Column(name = "tool_name", length = 32)
    private String toolName;

    @Column(name = "recommended_flag")
    private Boolean recommendedFlag;

    @Column(name = "campaign_id", length = 26)
    private String campaignId;

    @Column(name = "revision_reason", columnDefinition = "TEXT")
    private String revisionReason;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MeeraInteractionLog() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public MeeraInteractionEventType getEventType() {
        return eventType;
    }

    public String getToolName() {
        return toolName;
    }

    public Boolean getRecommendedFlag() {
        return recommendedFlag;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getRevisionReason() {
        return revisionReason;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final MeeraInteractionLog l = new MeeraInteractionLog();

        public Builder id(String id) {
            l.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            l.workspaceId = workspaceId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            l.sessionId = sessionId;
            return this;
        }

        public Builder eventType(MeeraInteractionEventType eventType) {
            l.eventType = eventType;
            return this;
        }

        public Builder toolName(String toolName) {
            l.toolName = toolName;
            return this;
        }

        public Builder recommendedFlag(Boolean recommendedFlag) {
            l.recommendedFlag = recommendedFlag;
            return this;
        }

        public Builder campaignId(String campaignId) {
            l.campaignId = campaignId;
            return this;
        }

        /** Caller MUST have already passed this through {@code SensitiveTextRedactor.redact}. */
        public Builder revisionReason(String revisionReason) {
            l.revisionReason = revisionReason;
            return this;
        }

        public Builder promptVersion(String promptVersion) {
            l.promptVersion = promptVersion;
            return this;
        }

        public MeeraInteractionLog build() {
            l.createdAt = Instant.now();
            return l;
        }
    }
}
