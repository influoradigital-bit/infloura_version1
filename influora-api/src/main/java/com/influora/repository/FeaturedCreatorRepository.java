package com.influora.repository;

import com.influora.domain.entity.FeaturedCreator;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeaturedCreatorRepository extends JpaRepository<FeaturedCreator, String> {

    @Query(
            """
            SELECT f FROM FeaturedCreator f
            WHERE f.active = true
              AND (:category IS NULL OR f.featuredCategory = :category)
              AND (f.featuredFrom IS NULL OR f.featuredFrom <= :now)
              AND (f.featuredUntil IS NULL OR f.featuredUntil >= :now)
            ORDER BY f.featuredCategory ASC, f.displayOrder ASC
            """)
    List<FeaturedCreator> findActiveFeatured(
            @Param("category") String category, @Param("now") Instant now, Pageable pageable);
}
