package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.MediaMetric;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * Real Hibernate + H2 proof of {@link MediaMetricsRepository#findNewestSnapshotPerPostSince}, the
 * query {@code CreatorPostingPatternService} reads the creator's posts through.
 *
 * <p>WHY THIS EXISTS. {@code CreatorPostingPatternServiceTest} mocks the repository, so it can
 * prove the Java-side dedupe and nothing about the SQL: a {@code @Query} that returned every
 * snapshot, or failed to parse at all, would leave that suite green (the service's own
 * {@code @DataJpaTest} neighbour, {@code ContentTopicRepositoryTest}, enables only
 * {@code ContentTopicRepository}, so it does not load this one either). The reduction to one row
 * per post has to happen in the database — the whole point of the query is that a post polled
 * hourly for three months must not arrive as hundreds of rows on a chat request — and only a real
 * query can show that it does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = MediaMetric.class)
@EnableJpaRepositories(
        basePackageClasses = MediaMetricsRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!MediaMetricsRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:media_newest_snapshot_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class MediaMetricsNewestSnapshotQueryTest {

    private static final String PROFILE_ID = "creator-profile-1";
    private static final String OTHER_PROFILE_ID = "creator-profile-2";
    private static final Instant NOW = Instant.parse("2026-09-23T06:00:00Z");

    @Autowired private MediaMetricsRepository repository;

    private MediaMetric snapshot(
            String id, String mediaId, String profileId, Instant fetchedAt, Instant postedAt, long reach) {
        return MediaMetric.builder()
                .id(id)
                .time(fetchedAt)
                .mediaId(mediaId)
                .creatorProfileId(profileId)
                .platform("INSTAGRAM")
                .mediaType("REEL")
                .reach(reach)
                .engagement(10L)
                .postedAt(postedAt)
                .build();
    }

    @Test
    @DisplayName("three snapshots of one post come back as one row, the newest snapshot")
    void newestSnapshotPerPost() {
        Instant postedAt = NOW.minus(5, ChronoUnit.DAYS);
        repository.save(snapshot("m1", "post-A", PROFILE_ID, NOW.minus(3, ChronoUnit.DAYS), postedAt, 100L));
        repository.save(snapshot("m2", "post-A", PROFILE_ID, NOW.minus(2, ChronoUnit.DAYS), postedAt, 200L));
        repository.save(snapshot("m3", "post-A", PROFILE_ID, NOW.minus(1, ChronoUnit.DAYS), postedAt, 300L));

        List<MediaMetric> rows =
                repository.findNewestSnapshotPerPostSince(PROFILE_ID, NOW.minus(90, ChronoUnit.DAYS));

        assertEquals(1, rows.size(), "one post, one row");
        assertEquals(300L, rows.get(0).getReach(), "the newest snapshot, not the first or an average");
    }

    @Test
    @DisplayName("a post older than the cutoff is excluded, and so is a null posted_at")
    void windowAndNullPostedAt() {
        repository.save(
                snapshot("m4", "post-recent", PROFILE_ID, NOW, NOW.minus(10, ChronoUnit.DAYS), 100L));
        repository.save(
                snapshot("m5", "post-old", PROFILE_ID, NOW, NOW.minus(100, ChronoUnit.DAYS), 100L));
        repository.save(snapshot("m6", "post-no-date", PROFILE_ID, NOW, null, 100L));

        List<MediaMetric> rows =
                repository.findNewestSnapshotPerPostSince(PROFILE_ID, NOW.minus(90, ChronoUnit.DAYS));

        assertEquals(
                List.of("post-recent"), rows.stream().map(MediaMetric::getMediaId).toList());
    }

    @Test
    @DisplayName("another creator's posts are never returned")
    void scopedToOneCreator() {
        Instant postedAt = NOW.minus(2, ChronoUnit.DAYS);
        repository.save(snapshot("m7", "mine", PROFILE_ID, NOW, postedAt, 100L));
        repository.save(snapshot("m8", "theirs", OTHER_PROFILE_ID, NOW, postedAt, 100L));

        List<MediaMetric> rows =
                repository.findNewestSnapshotPerPostSince(PROFILE_ID, NOW.minus(90, ChronoUnit.DAYS));

        assertEquals(List.of("mine"), rows.stream().map(MediaMetric::getMediaId).toList());
    }

    @Test
    @DisplayName("several posts each reduce to one row, newest post first")
    void manyPostsOneRowEach() {
        for (int i = 1; i <= 4; i++) {
            Instant postedAt = NOW.minus(i, ChronoUnit.DAYS);
            repository.save(snapshot("a" + i, "post-" + i, PROFILE_ID, NOW.minus(2, ChronoUnit.HOURS), postedAt, 50L));
            repository.save(snapshot("b" + i, "post-" + i, PROFILE_ID, NOW.minus(1, ChronoUnit.HOURS), postedAt, 60L));
        }

        List<MediaMetric> rows =
                repository.findNewestSnapshotPerPostSince(PROFILE_ID, NOW.minus(90, ChronoUnit.DAYS));

        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.getReach() == 60L), "every row is its newest snapshot");
        assertEquals(
                List.of("post-1", "post-2", "post-3", "post-4"),
                rows.stream().map(MediaMetric::getMediaId).toList(),
                "ordered by posted_at descending");
    }
}
