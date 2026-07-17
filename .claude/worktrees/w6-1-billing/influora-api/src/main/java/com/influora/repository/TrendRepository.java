package com.influora.repository;

import com.influora.domain.entity.Trend;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendRepository extends JpaRepository<Trend, String> {

    /** Active = not yet past {@code expires_at}, most recently detected first. n8n (Dev, T3)
     * auto-deletes expired rows, but we filter defensively here too. */
    @Query("SELECT t FROM Trend t WHERE t.expiresAt > :now ORDER BY t.detectedDate DESC")
    List<Trend> findActive(@Param("now") Instant now);
}
