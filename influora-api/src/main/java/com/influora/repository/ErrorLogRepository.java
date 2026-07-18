package com.influora.repository;

import com.influora.domain.entity.ErrorLog;
import com.influora.domain.enums.ErrorLogSeverity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for the admin error-log console ({@code errorApi}, api-contracts.ts 654-671). */
public interface ErrorLogRepository extends JpaRepository<ErrorLog, String> {

    /** Most recent errors first, capped by the caller's page size. */
    List<ErrorLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** {@code total24h} — every error captured in the window. */
    long countByCreatedAtAfter(Instant since);

    /** {@code critical24h} — see {@code AdminErrorLogService.getStats} for why this is honest-0 today. */
    long countBySeverityAndCreatedAtAfter(ErrorLogSeverity severity, Instant since);

    /** {@code unresolved} — all-time open errors, not windowed. */
    long countByResolvedFalse();

    /** {@code topEndpoints} — busiest endpoints within the window, most errors first. */
    @Query(
            "SELECT e.endpoint AS endpoint, COUNT(e) AS cnt FROM ErrorLog e "
                    + "WHERE e.createdAt > :since AND e.endpoint IS NOT NULL "
                    + "GROUP BY e.endpoint ORDER BY COUNT(e) DESC")
    List<EndpointCount> topEndpointsSince(@Param("since") Instant since, Pageable pageable);

    /** Projection for {@link #topEndpointsSince}. */
    interface EndpointCount {
        String getEndpoint();

        long getCnt();
    }
}
