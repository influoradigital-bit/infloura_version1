package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.config.RedisCacheConfig;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CreatorProfileRepository;
import com.influora.web.dto.creator.DiscoveryDtos.AvailableFiltersMeta;
import com.influora.web.dto.creator.DiscoveryDtos.CategoryFacet;
import com.influora.web.dto.creator.DiscoveryDtos.FollowerRangeFacet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-17 fix — backs {@code CreatorDiscoveryService.search}'s filter-sidebar facet metadata with a
 * short-TTL Redis cache ({@link RedisCacheConfig#DISCOVERY_FACETS_CACHE}, 5 min TTL) instead of
 * re-scanning up to 5,000 discoverable {@link CreatorProfile} rows into memory on every single
 * search request.
 *
 * <p><b>Deliberately a separate bean from {@code CreatorDiscoveryService}, not a private method on
 * it</b> — same reasoning as {@code AdminDashboardStatsCache}: {@code @Cacheable} is only honored
 * through the Spring AOP proxy, so a self-invoked private method on the same bean would silently
 * skip caching entirely. The cache key is a fixed literal ({@code 'discoverable'}) because this is
 * a platform-wide facet count, identical for every brand's search — not principal-scoped.
 *
 * <p><b>Not attempted here (documented, not silently dropped):</b> pushing the category/follower
 * aggregation itself into a single SQL {@code GROUP BY} (rather than counting in Java after
 * loading rows) would cut the per-refresh cost further, but categories are stored as a JSON array
 * column ({@code creator_profiles.categories}) — unnesting that in MySQL needs {@code
 * JSON_TABLE}, which is untested against this schema in this pass. The 5-minute cache already
 * removes the dominant cost (this scan no longer runs per-request, only once per TTL window
 * platform-wide), which is the change M-17 calls out; a DB-side aggregate is a further, smaller
 * optimization left for a follow-up.
 */
@Service
public class CreatorDiscoveryFacetsCache {

    /** Same cap the pre-existing in-memory scan used — bounds worst-case row count per refresh. */
    private static final int MAX_PROFILES_SCANNED = 5_000;

    private static final List<FollowerRangeBucket> FOLLOWER_RANGE_BUCKETS =
            List.of(
                    new FollowerRangeBucket("1K-10K", 1_000L, 10_000L),
                    new FollowerRangeBucket("10K-100K", 10_000L, 100_000L),
                    new FollowerRangeBucket("100K-1M", 100_000L, 1_000_000L));

    private final CreatorProfileRepository creatorProfileRepository;

    public CreatorDiscoveryFacetsCache(CreatorProfileRepository creatorProfileRepository) {
        this.creatorProfileRepository = creatorProfileRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = RedisCacheConfig.DISCOVERY_FACETS_CACHE, key = "'discoverable'")
    public AvailableFiltersMeta computeFacets() {
        List<CreatorProfile> discoverable =
                creatorProfileRepository
                        .findAll(
                                CreatorProfileSpecifications.discoverable(),
                                PageRequest.of(0, MAX_PROFILES_SCANNED, Sort.unsorted()))
                        .getContent();

        Map<String, Long> categoryCounts = new HashMap<>();
        Map<String, Long> followerCounts = new LinkedHashMap<>();
        for (FollowerRangeBucket bucket : FOLLOWER_RANGE_BUCKETS) {
            followerCounts.put(bucket.range(), 0L);
        }

        for (CreatorProfile profile : discoverable) {
            for (String category : JsonLists.stringListFromJson(profile.getCategoriesJson())) {
                String key = category.toLowerCase(Locale.ROOT);
                categoryCounts.merge(key, 1L, Long::sum);
            }
            for (FollowerRangeBucket bucket : FOLLOWER_RANGE_BUCKETS) {
                if (profile.getTotalFollowers() >= bucket.min()
                        && profile.getTotalFollowers() <= bucket.max()) {
                    followerCounts.merge(bucket.range(), 1L, Long::sum);
                    break;
                }
            }
        }

        List<CategoryFacet> categories =
                categoryCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(20)
                        .map(e -> new CategoryFacet(e.getKey(), e.getValue()))
                        .toList();

        List<FollowerRangeFacet> followerRanges =
                followerCounts.entrySet().stream()
                        .map(e -> new FollowerRangeFacet(e.getKey(), e.getValue()))
                        .toList();

        return new AvailableFiltersMeta(categories, followerRanges);
    }

    private record FollowerRangeBucket(String range, long min, long max) {}
}
