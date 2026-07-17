package com.influora.domain.entity;

import com.influora.domain.enums.NudgeMessageSource;
import com.influora.domain.enums.NudgeMode;
import com.influora.domain.enums.TrendCampaignType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code nudge_log} (V51) — THE flywheel (schema lock §1d). No PII beyond {@code workspaceId};
 * never log emails, tokens, or raw model prompts here. */
@Entity
@Table(name = "nudge_log")
public class NudgeLog {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "trend_id", nullable = false, length = 26)
    private String trendId;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false, length = 16)
    private TrendCampaignType campaignType;

    @Column(name = "match_score", nullable = false)
    private Integer matchScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private NudgeMode mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "video_ids", columnDefinition = "json")
    private String videoIdsJson;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_source", nullable = false, length = 16)
    private NudgeMessageSource messageSource;

    @Column(name = "shown_at", nullable = false)
    private Instant shownAt;

    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    @Column(name = "purchased_video_id", length = 26)
    private String purchasedVideoId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NudgeLog() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getTrendId() {
        return trendId;
    }

    public TrendCampaignType getCampaignType() {
        return campaignType;
    }

    public Integer getMatchScore() {
        return matchScore;
    }

    public NudgeMode getMode() {
        return mode;
    }

    public String getVideoIdsJson() {
        return videoIdsJson;
    }

    public String getMessage() {
        return message;
    }

    public NudgeMessageSource getMessageSource() {
        return messageSource;
    }

    public Instant getShownAt() {
        return shownAt;
    }

    public Instant getClickedAt() {
        return clickedAt;
    }

    public void markClicked() {
        if (this.clickedAt == null) {
            this.clickedAt = Instant.now();
        }
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }

    public String getPurchasedVideoId() {
        return purchasedVideoId;
    }

    public void markPurchased(String purchasedVideoId) {
        this.purchasedAt = Instant.now();
        this.purchasedVideoId = purchasedVideoId;
        markClicked();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final NudgeLog n = new NudgeLog();

        public Builder id(String id) {
            n.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            n.workspaceId = workspaceId;
            return this;
        }

        public Builder trendId(String trendId) {
            n.trendId = trendId;
            return this;
        }

        public Builder campaignType(TrendCampaignType campaignType) {
            n.campaignType = campaignType;
            return this;
        }

        public Builder matchScore(int matchScore) {
            n.matchScore = matchScore;
            return this;
        }

        public Builder mode(NudgeMode mode) {
            n.mode = mode;
            return this;
        }

        public Builder videoIdsJson(String videoIdsJson) {
            n.videoIdsJson = videoIdsJson;
            return this;
        }

        public Builder message(String message) {
            n.message = message;
            return this;
        }

        public Builder messageSource(NudgeMessageSource messageSource) {
            n.messageSource = messageSource;
            return this;
        }

        public NudgeLog build() {
            Instant now = Instant.now();
            n.shownAt = now;
            n.createdAt = now;
            return n;
        }
    }
}
