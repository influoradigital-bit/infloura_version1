package com.influora.repository;

import com.influora.domain.entity.SnapsbyCatalogVideo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapsbyCatalogVideoRepository extends JpaRepository<SnapsbyCatalogVideo, String> {

    List<SnapsbyCatalogVideo> findByNicheAndActiveTrue(String niche);
}
