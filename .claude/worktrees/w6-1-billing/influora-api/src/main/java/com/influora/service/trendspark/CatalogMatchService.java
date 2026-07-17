package com.influora.service.trendspark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.SnapsbyCatalogVideo;
import com.influora.domain.entity.Trend;
import com.influora.repository.SnapsbyCatalogVideoRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Catalog-match (schema lock §1c) — queries {@code snapsby_catalog_video} by the brand's
 * niche(s) and ranks by theme overlap with the trend, returning the top matches. Only called in
 * {@link com.influora.domain.enums.NudgeMode#SNAPSBY} mode. */
@Service
public class CatalogMatchService {

    private static final int MAX_RESULTS = 3;

    private final SnapsbyCatalogVideoRepository catalogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CatalogMatchService(SnapsbyCatalogVideoRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public List<SnapsbyCatalogVideo> topMatches(BrandProfile brandProfile, Trend trend) {
        Set<String> brandNiches = parseStringList(brandProfile == null ? null : brandProfile.getNicheTagsJson());
        Set<String> trendThemes = parseStringList(trend == null ? null : trend.getThemesJson());

        List<SnapsbyCatalogVideo> candidates = new ArrayList<>();
        for (String niche : brandNiches) {
            candidates.addAll(catalogRepository.findByNicheAndActiveTrue(niche.trim().toLowerCase()));
        }

        // De-dup (a brand can list overlapping niches) and rank by theme overlap desc.
        List<SnapsbyCatalogVideo> deduped =
                candidates.stream().distinct().collect(java.util.stream.Collectors.toList());

        deduped.sort(
                Comparator.comparingInt((SnapsbyCatalogVideo v) -> themeOverlap(v, trendThemes)).reversed());

        return deduped.stream().limit(MAX_RESULTS).collect(java.util.stream.Collectors.toList());
    }

    private int themeOverlap(SnapsbyCatalogVideo video, Set<String> trendThemes) {
        if (trendThemes.isEmpty()) {
            return 0;
        }
        Set<String> videoThemes = parseStringList(video.getThemesJson());
        int overlap = 0;
        for (String theme : videoThemes) {
            if (trendThemes.contains(theme)) {
                overlap++;
            }
        }
        return overlap;
    }

    private Set<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, LIST_OF_STRING);
            return list == null ? Set.of() : new HashSet<>(list);
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};
}
