package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code snapsby_catalog_video} (V51) — MVP catalog for catalog-match (Vikram-seeded rows).
 * {@code priceInr} is server-derived, never accepted from a client request (Guardrail 4). */
@Entity
@Table(name = "snapsby_catalog_video")
public class SnapsbyCatalogVideo {

    @Id
    @Column(length = 26)
    private String id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 64)
    private String niche;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "themes", nullable = false, columnDefinition = "json")
    private String themesJson;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(name = "price_inr", nullable = false)
    private Integer priceInr;

    @Column(name = "preview_url", length = 500)
    private String previewUrl;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SnapsbyCatalogVideo() {}

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getNiche() {
        return niche;
    }

    public String getThemesJson() {
        return themesJson;
    }

    public String getLanguage() {
        return language;
    }

    public Integer getPriceInr() {
        return priceInr;
    }

    public String getPreviewUrl() {
        return previewUrl;
    }

    public Boolean getActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
