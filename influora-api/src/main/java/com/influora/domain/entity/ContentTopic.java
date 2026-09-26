package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code content_topics} (V75) -- a hand-typed catalogue of creator content ideas, read (never
 * written) by {@code ContentTopicService}. See the V75 migration comment and {@code
 * ContentTopicService}'s class javadoc for why screening happens at read time rather than here:
 * this entity is a plain mapping of the table and enforces nothing about the words in {@link
 * #title}/{@link #angles}.
 *
 * <p>No setters and no builder -- there is no application write path for this table in this
 * ticket (rows are inserted by hand over a SQL client), so the entity is read-only by
 * construction rather than by convention.
 */
@Entity
@Table(name = "content_topics")
public class ContentTopic {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** The wildcard category every creator matches, regardless of her own categories. */
    public static final String CATEGORY_ALL = "ALL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 80)
    private String category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** One angle per line, plain text -- see {@code ContentTopicService#splitAngles}. */
    @Column(name = "angles", nullable = false, columnDefinition = "TEXT")
    private String angles;

    @Column(name = "live_from", nullable = false)
    private LocalDate liveFrom;

    @Column(name = "live_until", nullable = false)
    private LocalDate liveUntil;

    @Column(name = "region", nullable = false, length = 40)
    private String region;

    @Column(name = "source_note", length = 255)
    private String sourceNote;

    @Column(name = "sensitivity", length = 255)
    private String sensitivity;

    /** One of {@link #STATUS_DRAFT}/{@link #STATUS_APPROVED}/{@link #STATUS_REJECTED}. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentTopic() {}

    public Long getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getAngles() {
        return angles;
    }

    public LocalDate getLiveFrom() {
        return liveFrom;
    }

    public LocalDate getLiveUntil() {
        return liveUntil;
    }

    public String getRegion() {
        return region;
    }

    public String getSourceNote() {
        return sourceNote;
    }

    public String getSensitivity() {
        return sensitivity;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
