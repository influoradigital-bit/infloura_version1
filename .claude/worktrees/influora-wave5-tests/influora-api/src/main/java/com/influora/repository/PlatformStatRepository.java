package com.influora.repository;

import com.influora.domain.entity.PlatformStat;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformStatRepository extends JpaRepository<PlatformStat, String> {

    List<PlatformStat> findByCreatorProfileIdIn(Collection<String> creatorProfileIds);

    List<PlatformStat> findByCreatorProfileId(String creatorProfileId);
}
