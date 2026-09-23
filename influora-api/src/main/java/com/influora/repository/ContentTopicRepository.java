package com.influora.repository;

import com.influora.domain.entity.ContentTopic;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage-abstraction repository for the hand-typed {@code content_topics} catalogue (V75). See
 * {@code ContentTopicService} for the category-matching and safety-screening logic layered on top
 * of {@link #findServable} -- this repository does status/date filtering only.
 */
public interface ContentTopicRepository extends JpaRepository<ContentTopic, Long> {

    /**
     * Servable rows for {@code today}: {@code status = 'APPROVED'} and {@code today} within
     * {@code [live_from, live_until]} inclusive of both ends, newest first ({@code id DESC} --
     * {@code id} is a real {@code AUTO_INCREMENT} column, monotonic by construction, see the V75
     * migration comment).
     *
     * <p>{@code today} is a single caller-supplied value compared against both bounds -- it is
     * never the server's or the database's own idea of "now" (see {@code
     * ContentTopicService#topicsFor}'s javadoc on why the caller decides the date).
     */
    @Query(
            "SELECT t FROM ContentTopic t WHERE t.status = 'APPROVED' AND t.liveFrom <= :today AND"
                    + " t.liveUntil >= :today ORDER BY t.id DESC")
    List<ContentTopic> findServable(@Param("today") LocalDate today);
}
