package com.influora.repository;

import com.influora.domain.entity.MediaMetric;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage-abstraction repository for per-post metrics (V21 {@code media_metrics}).
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same
 * storage-seam intent as {@link CreatorMetricsRepository} — see its javadoc for the full
 * TimescaleDB-swap rationale. This is an ordinary MySQL JPA repository today.
 *
 * <p><b>Workspace isolation:</b> same discipline as {@link CreatorMetricsRepository} — {@code
 * creator_profiles} has no direct {@code workspace_id}, so every finder here is scoped only by
 * {@code creatorProfileId} / {@code mediaId}. Before calling ANY finder below with a
 * caller-supplied {@code creatorProfileId}, callers MUST first call {@code
 * com.influora.service.MetricsAuthorizationService#resolveAuthorizedCreatorProfileId(String
 * workspaceId, String creatorProfileId)} and use only the id it returns — see {@link
 * CreatorMetricsRepository}'s javadoc for the full rationale and precedent this mirrors.
 */
public interface MediaMetricsRepository extends JpaRepository<MediaMetric, String> {

    /** Most recent snapshot for a specific media item (a post can be polled multiple times). */
    Optional<MediaMetric> findFirstByMediaIdOrderByTimeDesc(String mediaId);

    /** Recent per-post metrics for a creator, newest first — used by scoring jobs (spec §4). */
    List<MediaMetric> findByCreatorProfileIdOrderByTimeDesc(String creatorProfileId, Pageable pageable);


    /**
     * The same reads, narrowed to ONE connected Instagram account. A creator profile can connect a
     * different account later (seen live: three accounts under one profile), and the unnarrowed
     * methods above mix them, so Meera's posts, her quality score, the posting pattern and the
     * brand-facing performance panel were all built from several accounts at once.
     *
     * <p>A row whose {@code igAccountId} is NULL predates V20260924120000 and cannot be attributed
     * to an account, so it is INCLUDED rather than hidden — otherwise every creator would lose
     * their history the moment this shipped, to fix a problem only account-switchers have. Those
     * rows are cleaned up per creator, separately.
     */
    @Query(
            "select m from MediaMetric m where m.creatorProfileId = :creatorProfileId"
                    + " and (m.igAccountId is null or m.igAccountId = :igAccountId)"
                    + " order by m.time desc")
    List<MediaMetric> findForAccountOrderByTimeDesc(
            @Param("creatorProfileId") String creatorProfileId,
            @Param("igAccountId") String igAccountId,
            Pageable pageable);

    /** Recent per-post metrics for a creator on one platform, newest first. */
    List<MediaMetric> findByCreatorProfileIdAndPlatformOrderByTimeDesc(
            String creatorProfileId, String platform, Pageable pageable);

    /**
     * The NEWEST snapshot of each post this creator published on or after {@code postedAtFrom} —
     * one row per post, not one per poll.
     *
     * <p>{@code media_metrics} stores a snapshot per poll ({@code time} is the fetch time, {@code
     * posted_at} the post's own), so a post polled hourly for three months holds hundreds of rows
     * that are all the same post. {@code CreatorPostingPatternService} needs one row per post, and
     * loading every snapshot to throw all but one away grows without bound on a chat request — the
     * correlated {@code max(time)} below does that reduction in the database instead, so the result
     * size is the number of posts, not the number of polls.
     *
     * <p>Rows with a null {@code posted_at} are excluded by the comparison itself; the caller still
     * applies the upper bound of its window and its own dedupe as a second line of defence.
     *
     * <p><b>Collab posts (F-audit-A5):</b> the correlated subquery is keyed by {@code (mediaId,
     * creatorProfileId)}, not {@code mediaId} alone. An Instagram collab post has ONE {@code
     * media_id} but is polled independently on each collaborator's own profile, so a {@code
     * mediaId}-only {@code max(time)} would pick the single globally-latest poll across every
     * creator and silently drop every other creator's row for that same post the moment a
     * different creator happened to poll it more recently — see {@code
     * MediaMetricsNewestSnapshotQueryTest#collabPostOnTwoProfiles} for the case this closes.
     */
    @Query(
            "select m from MediaMetric m where m.creatorProfileId = :creatorProfileId"
                    + " and m.postedAt >= :postedAtFrom"
                    + " and m.time = (select max(m2.time) from MediaMetric m2"
                    + " where m2.mediaId = m.mediaId and m2.creatorProfileId = m.creatorProfileId)"
                    + " order by m.postedAt desc")
    List<MediaMetric> findNewestSnapshotPerPostSince(
            @Param("creatorProfileId") String creatorProfileId,
            @Param("postedAtFrom") java.time.Instant postedAtFrom);

    /** The same read, narrowed to one connected Instagram account (see the note above). */
    @Query(
            "select m from MediaMetric m where m.creatorProfileId = :creatorProfileId"
                    + " and m.postedAt >= :postedAtFrom"
                    + " and (m.igAccountId is null or m.igAccountId = :igAccountId)"
                    + " and m.time = (select max(m2.time) from MediaMetric m2"
                    + " where m2.mediaId = m.mediaId and m2.creatorProfileId = m.creatorProfileId)"
                    + " order by m.postedAt desc")
    List<MediaMetric> findNewestSnapshotPerPostSinceForAccount(
            @Param("creatorProfileId") String creatorProfileId,
            @Param("postedAtFrom") java.time.Instant postedAtFrom,
            @Param("igAccountId") String igAccountId);

    /** Time-range query for a creator's media metrics (trend charts / analytics API). */
    List<MediaMetric> findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(
            String creatorProfileId, Instant from, Instant to);

    /** All poll snapshots recorded for one media item, oldest first (per-post history). */
    List<MediaMetric> findByMediaIdOrderByTimeAsc(String mediaId);
}
