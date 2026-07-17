package com.influora.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Enables Spring's {@code @Cacheable} abstraction backed by Redis (src/admin/TASK_ASSIGNMENTS.md
 * P1 "Redis cache setup" — docker-compose.yml {@code redis} service, {@code spring.cache.type:
 * redis} in application.yml).
 *
 * <p>Customizes the Boot auto-configured {@link org.springframework.data.redis.cache.RedisCacheManager}
 * via {@link RedisCacheManagerBuilderCustomizer} rather than replacing it wholesale, so
 * {@code spring.data.redis.*} connection properties keep working unchanged and any future
 * {@code @Cacheable} usage that doesn't need a bespoke TTL just falls back to Boot's defaults
 * (60 min TTL, JDK serialization) automatically.
 *
 * <p>Two caches are configured explicitly today:
 *
 * <ul>
 *   <li>{@link #ADMIN_PULSE_CACHE}, consumed by {@code AdminDashboardStatsCache.pulseStats()} —
 *       a short 45s TTL appropriate for a live/near-real-time admin dashboard (long enough to
 *       absorb repeated polling from {@code AdminLayout}'s auto-refresh without the numbers ever
 *       going stale by more than ~45s).
 *   <li>{@link #DISCOVERY_FACETS_CACHE} (M-17), consumed by {@code
 *       CreatorDiscoveryFacetsCache.computeFacets()} — a 5-minute TTL. This replaces a
 *       per-request, up-to-5,000-row in-memory scan that used to re-run on every single {@code
 *       GET /creators/search} call ({@code CreatorDiscoveryService.buildAvailableFacets}); the
 *       category/follower-range counts it produces are approximate filter-sidebar metadata, not a
 *       real-time figure, so a 5-minute-stale count is an acceptable trade for not re-scanning the
 *       discoverable pool on every search request.
 * </ul>
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /** Cache name for {@code AdminDashboardStatsCache.pulseStats()}. */
    public static final String ADMIN_PULSE_CACHE = "adminPulse";

    /** Cache name for {@code CreatorDiscoveryFacetsCache.computeFacets()} (M-17). */
    public static final String DISCOVERY_FACETS_CACHE = "discoveryFacets";

    private static final Duration ADMIN_PULSE_TTL = Duration.ofSeconds(45);
    private static final Duration DISCOVERY_FACETS_TTL = Duration.ofMinutes(5);

    @Bean
    RedisCacheManagerBuilderCustomizer adminPulseCacheCustomizer() {
        RedisCacheConfiguration adminPulseConfig =
                jsonCacheConfig().entryTtl(ADMIN_PULSE_TTL);
        return builder -> builder.withCacheConfiguration(ADMIN_PULSE_CACHE, adminPulseConfig);
    }

    @Bean
    RedisCacheManagerBuilderCustomizer discoveryFacetsCacheCustomizer() {
        RedisCacheConfiguration discoveryFacetsConfig =
                jsonCacheConfig().entryTtl(DISCOVERY_FACETS_TTL);
        return builder -> builder.withCacheConfiguration(DISCOVERY_FACETS_CACHE, discoveryFacetsConfig);
    }

    private RedisCacheConfiguration jsonCacheConfig() {
        RedisSerializationContext.SerializationPair<Object> jsonValueSerializer =
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer());
        RedisSerializationContext.SerializationPair<String> stringKeySerializer =
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer());

        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(stringKeySerializer)
                .serializeValuesWith(jsonValueSerializer);
    }
}
