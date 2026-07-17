package com.influora.repository;

import com.influora.domain.entity.PlatformStat;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformStatRepository extends JpaRepository<PlatformStat, String> {

    List<PlatformStat> findByCreatorProfileIdIn(Collection<String> creatorProfileIds);

    List<PlatformStat> findByCreatorProfileId(String creatorProfileId);

    /** Upsert lookup key for {@code PlatformStatsAggregationJob} (H-10) — one row per platform per creator. */
    Optional<PlatformStat> findByCreatorProfileIdAndPlatform(String creatorProfileId, String platform);
}
