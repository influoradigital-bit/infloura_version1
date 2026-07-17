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
 * <p>Only one cache is configured explicitly today — {@link #ADMIN_PULSE_CACHE}, consumed by
 * {@code AdminDashboardStatsCache.pulseStats()} — with a short 45s TTL appropriate for a
 * live/near-real-time admin dashboard (long enough to absorb repeated polling from
 * {@code AdminLayout}'s auto-refresh without the numbers ever going stale by more than ~45s).
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /** Cache name for {@code AdminDashboardStatsCache.pulseStats()}. */
    public static final String ADMIN_PULSE_CACHE = "adminPulse";

    private static final Duration ADMIN_PULSE_TTL = Duration.ofSeconds(45);

    @Bean
    RedisCacheManagerBuilderCustomizer adminPulseCacheCustomizer() {
        RedisSerializationContext.SerializationPair<Object> jsonValueSerializer =
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer());
        RedisSerializationContext.SerializationPair<String> stringKeySerializer =
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer());

        RedisCacheConfiguration adminPulseConfig =
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ADMIN_PULSE_TTL)
                        .disableCachingNullValues()
                        .serializeKeysWith(stringKeySerializer)
                        .serializeValuesWith(jsonValueSerializer);

        return builder -> builder.withCacheConfiguration(ADMIN_PULSE_CACHE, adminPulseConfig);
    }
}
