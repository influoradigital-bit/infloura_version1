package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One (edition, sponsor_slug, bucket_day) demand-signal bucket for the Festival Box coupon-copy
 * tracker
 * (T-FESTIVALBOX-0905 phase 6, V20260905170000). Written by {@code
 * FestivalCouponCopyRepository#recordCopy}'s native upsert from an UNAUTHENTICATED request — see
 * that repository method and the migration header for the full design (why a daily bucket, why no
 * visitor data, why the atomic upsert).
 *
 * <p>Every {@code @Column} names its column explicitly, same discipline as {@link FestivalEnquiry}
 * — {@code ddl-auto=validate} runs at boot, so a drifted column name fails startup, not a test.
 *
 * <p><b>This entity intentionally has NO ip/ipHash/userAgent/session field of any kind.</b> That
 * absence is the point (see the migration header) — do not add one. A test asserts this by
 * reflection ({@code FestivalCouponCopyServiceTest}) so a future edit cannot reintroduce visitor
 * data here without a test failing.
 *
 * <p>Normal application writes NEVER go through {@link jakarta.persistence.EntityManager#persist}
 * for this entity — the only write path is the native {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * in {@code FestivalCouponCopyRepository#recordCopy}, chosen specifically so the increment is
 * atomic. This class still exists as a full JPA entity because the admin metrics read side ({@code
 * AdminFestivalMetricsService}) reads rows back via ordinary Spring Data queries.
 */
@Entity
/*
 * The uniqueConstraints below is LOAD-BEARING FOR THE TESTS, not decoration — do not simplify this
 * back to a bare @Table(name = ...).
 *
 * The real MySQL table has UNIQUE KEY uq_festival_coupon_copies_edition_sponsor_day, and the whole
 * counter design depends on it: the repository's INSERT ... ON DUPLICATE KEY UPDATE needs a
 * constraint to collide on, or it simply inserts a new row every time and the count is silently
 * wrong. But a @DataJpaTest builds its schema from THIS ANNOTATION, not from the Flyway migration
 * (flyway is disabled there, ddl-auto=create-drop). With the constraint declared only in the
 * migration, the test schema had no unique key at all — so the concurrency test that exists to
 * prove the increment is atomic could never have failed, whatever the code did.
 *
 * Found while building AbuseThrottleCounter's equivalent test, which hit the same trap. Declaring
 * the constraint here is what makes both tests capable of failing.
 */
@Table(
        name = "festival_coupon_copies",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_festival_coupon_copies_edition_sponsor_day",
                        columnNames = {"edition", "sponsor_slug", "bucket_day"}))
public class FestivalCouponCopy {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "edition", nullable = false, length = 64)
    private String edition;

    @Column(name = "sponsor_slug", nullable = false, length = 80)
    private String sponsorSlug;

    @Column(name = "coupon_code", nullable = false, length = 50)
    private String couponCode;

    /**
     * The day this bucket counts, as a date.
     *
     * <p>The COLUMN is {@code bucket_day}, not {@code day}, while the Java field stays {@code day}
     * (so the derived query {@code findByEditionOrderByDayAscSponsorSlugAsc} reads naturally).
     * {@code day} is a RESERVED WORD in H2 even under {@code MODE=MySQL}: with the column named
     * {@code day}, Hibernate's generated {@code CREATE TABLE} fails outright in any
     * {@code @DataJpaTest}, and backtick-quoting cannot save it because
     * {@code FestivalCouponCopyRepository#recordCopy} is a NATIVE query whose SQL would then need
     * MySQL backticks and H2 double-quotes at once. Renaming the column was the only fix that
     * leaves one portable statement. Do not rename it back.
     */
    @Column(name = "bucket_day", nullable = false)
    private LocalDate day;

    @Column(name = "copy_count", nullable = false)
    private Long copyCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FestivalCouponCopy() {}

    /**
     * Test/seed-only factory — the real write path is {@code
     * FestivalCouponCopyRepository#recordCopy}'s native upsert, never {@code
     * JpaRepository#save(FestivalCouponCopy)}. Exists so repository/service tests can seed a known
     * row without duplicating column-mapping logic.
     */
    public static FestivalCouponCopy seed(
            String id,
            String edition,
            String sponsorSlug,
            String couponCode,
            LocalDate day,
            long copyCount) {
        FestivalCouponCopy c = new FestivalCouponCopy();
        c.id = id;
        c.edition = edition;
        c.sponsorSlug = sponsorSlug;
        c.couponCode = couponCode;
        c.day = day;
        c.copyCount = copyCount;
        Instant now = Instant.now();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public String getId() {
        return id;
    }

    public String getEdition() {
        return edition;
    }

    public String getSponsorSlug() {
        return sponsorSlug;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public LocalDate getDay() {
        return day;
    }

    public Long getCopyCount() {
        return copyCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
