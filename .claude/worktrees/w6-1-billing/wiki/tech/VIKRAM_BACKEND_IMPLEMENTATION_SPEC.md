# VIKRAM — Backend Implementation Specification

> **Author:** Priya (CTO)  
> **Date:** 2026-07-06  
> **Sprint:** 8 weeks (Weeks 1-8)  
> **Status:** Ready for implementation  
> **Security Reviewer:** Kabir  
> **QA Lead:** Kavya

---

## Executive Summary

This spec covers the ~40% of backend functionality currently missing from `influora-api`:
1. **Meta Graph API integration** — fetch real Instagram/Facebook metrics (replacing self-reported `deliverable_metrics`)
2. **TimescaleDB hypertables** — time-series storage for creator metrics
3. **Scoring algorithms** — fake follower detection, quality scoring, brand safety, rate estimation
4. **UTM & Coupon tracking** — attribution and conversion tracking for campaigns

Current state: `V19__deliverable_metrics.sql` stores creator-reported numbers with `source = CREATOR_REPORTED`. This spec adds platform-verified data pipelines.

---

## 1. Meta Graph API Integration Layer

### 1.1 Package Structure

Create new package: `com.influora.integration.meta/`

```
src/main/java/com/influora/integration/meta/
├── config/
│   ├── MetaApiProperties.java          // @ConfigurationProperties for Meta credentials
│   └── MetaApiConfig.java              // RestClient/WebClient bean configuration
├── client/
│   ├── MetaGraphApiClient.java         // Core HTTP client, handles auth, rate limits
│   ├── InstagramInsightsClient.java    // Instagram-specific API calls
│   └── FacebookPageClient.java         // Facebook Page API calls
├── dto/
│   ├── MetaTokenResponse.java          // OAuth token exchange response
│   ├── InstagramUserResponse.java      // /me?fields=...
│   ├── InstagramMediaResponse.java     // /media?fields=...
│   ├── InstagramInsightsResponse.java  // /insights endpoint response
│   ├── FacebookPageResponse.java       // Page data
│   └── AudienceDemographicsResponse.java // audience_demographics insight
├── exception/
│   ├── MetaApiException.java           // Base exception
│   ├── MetaRateLimitException.java     // 429 / rate limit hit
│   ├── MetaTokenExpiredException.java  // Token needs refresh
│   └── MetaPermissionDeniedException.java // Missing scope
├── oauth/
│   ├── MetaOAuthService.java           // OAuth flow orchestration
│   ├── MetaTokenStorage.java           // Encrypted token persistence
│   └── MetaTokenRefreshService.java    // Background token refresh
└── service/
    ├── InstagramMetricsFetcher.java    // High-level fetch orchestration
    └── MetaRateLimitTracker.java       // Track X-Business-Use-Case-Usage
```

### 1.2 MetaApiProperties.java

```java
package com.influora.integration.meta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influora.meta")
public record MetaApiProperties(
    String appId,
    String appSecret,
    String redirectUri,
    String graphApiVersion,  // Must be "v25.0" or higher for 2026 compliance
    int tokenRefreshDaysBeforeExpiry,  // default: 7
    int rateLimitAlertThreshold,       // default: 80
    int rateLimitThrottleThreshold     // default: 90
) {
    public MetaApiProperties {
        if (graphApiVersion == null) graphApiVersion = "v25.0";
        if (tokenRefreshDaysBeforeExpiry <= 0) tokenRefreshDaysBeforeExpiry = 7;
        if (rateLimitAlertThreshold <= 0) rateLimitAlertThreshold = 80;
        if (rateLimitThrottleThreshold <= 0) rateLimitThrottleThreshold = 90;
    }
}
```

### 1.3 MetaGraphApiClient.java

```java
package com.influora.integration.meta.client;

import com.influora.integration.meta.config.MetaApiProperties;
import com.influora.integration.meta.exception.*;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MetaGraphApiClient {

    private static final String BASE_URL = "https://graph.facebook.com";
    
    private final RestClient restClient;
    private final MetaApiProperties props;
    private final MetaRateLimitTracker rateLimitTracker;

    public MetaGraphApiClient(MetaApiProperties props, MetaRateLimitTracker rateLimitTracker) {
        this.props = props;
        this.rateLimitTracker = rateLimitTracker;
        this.restClient = RestClient.builder()
            .baseUrl(BASE_URL + "/" + props.graphApiVersion())
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /**
     * Generic GET with automatic rate-limit tracking.
     * Reads X-Business-Use-Case-Usage header from every response.
     */
    public <T> T get(String path, String accessToken, Class<T> responseType, String businessAccountId) {
        // Pre-flight rate limit check
        int currentUsage = rateLimitTracker.getCurrentUsage(businessAccountId);
        if (currentUsage >= props.rateLimitThrottleThreshold()) {
            throw new MetaRateLimitException(
                "Rate limit throttle engaged at " + currentUsage + "% for account " + businessAccountId
            );
        }
        if (currentUsage >= props.rateLimitAlertThreshold()) {
            // Log alert, but proceed
            log.warn("RATE_LIMIT_ALERT: Business account {} at {}% usage", businessAccountId, currentUsage);
        }

        try {
            ResponseEntity<T> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(path)
                    .queryParam("access_token", accessToken)
                    .build())
                .retrieve()
                .toEntity(responseType);

            // Extract and track rate limit from response header
            String rateLimitHeader = response.getHeaders().getFirst("X-Business-Use-Case-Usage");
            if (rateLimitHeader != null) {
                rateLimitTracker.update(businessAccountId, rateLimitHeader);
            }

            return response.getBody();
        } catch (HttpClientErrorException.TooManyRequests e) {
            rateLimitTracker.markLimited(businessAccountId);
            throw new MetaRateLimitException("Meta API rate limit exceeded", e);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new MetaTokenExpiredException("Access token expired or invalid", e);
        }
    }
}
```

### 1.4 InstagramInsightsClient.java

```java
package com.influora.integration.meta.client;

import com.influora.integration.meta.dto.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class InstagramInsightsClient {

    private final MetaGraphApiClient apiClient;

    // Required fields per endpoint — minimize data fetched
    private static final String USER_FIELDS = "id,username,name,biography,followers_count,follows_count,media_count,profile_picture_url,website";
    private static final String MEDIA_FIELDS = "id,caption,media_type,media_url,permalink,timestamp,like_count,comments_count";
    private static final String INSIGHTS_METRICS = "impressions,reach,engagement,saved,shares,video_views,likes,comments";
    
    // 2026 compliance: Page Viewer Metric migration deadline June 15
    private static final String AUDIENCE_METRICS = "audience_city,audience_country,audience_gender_age,audience_locale";

    public InstagramInsightsClient(MetaGraphApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Fetch basic profile data for an Instagram Business/Creator account.
     * Required permission: instagram_basic
     */
    public InstagramUserResponse getProfile(String igUserId, String accessToken) {
        String path = "/" + igUserId + "?fields=" + USER_FIELDS;
        return apiClient.get(path, accessToken, InstagramUserResponse.class, igUserId);
    }

    /**
     * Fetch recent media with basic metrics.
     * Required permission: instagram_basic
     * @param limit Max 100 per request (Meta API limit)
     */
    public InstagramMediaResponse getMedia(String igUserId, String accessToken, int limit) {
        limit = Math.min(limit, 100);
        String path = "/" + igUserId + "/media?fields=" + MEDIA_FIELDS + "&limit=" + limit;
        return apiClient.get(path, accessToken, InstagramMediaResponse.class, igUserId);
    }

    /**
     * Fetch insights for a specific media object.
     * Required permission: instagram_manage_insights
     * NOTE: Available metrics vary by media_type (IMAGE, VIDEO, CAROUSEL_ALBUM, REELS)
     */
    public InstagramInsightsResponse getMediaInsights(String mediaId, String accessToken, String businessAccountId) {
        String path = "/" + mediaId + "/insights?metric=" + INSIGHTS_METRICS;
        return apiClient.get(path, accessToken, InstagramInsightsResponse.class, businessAccountId);
    }

    /**
     * Fetch audience demographics (city, country, gender/age distribution).
     * Required permission: instagram_manage_insights
     * NOTE: Only available for accounts with 100+ followers
     */
    public AudienceDemographicsResponse getAudienceDemographics(String igUserId, String accessToken) {
        String path = "/" + igUserId + "/insights?metric=" + AUDIENCE_METRICS + "&period=lifetime";
        return apiClient.get(path, accessToken, AudienceDemographicsResponse.class, igUserId);
    }

    /**
     * Fetch account-level insights over a date range.
     * Required permission: instagram_manage_insights
     * @param since Unix timestamp
     * @param until Unix timestamp (max 30-day range)
     */
    public InstagramInsightsResponse getAccountInsights(
            String igUserId, String accessToken, long since, long until) {
        String path = "/" + igUserId + "/insights?metric=impressions,reach,profile_views,website_clicks" +
                      "&period=day&since=" + since + "&until=" + until;
        return apiClient.get(path, accessToken, InstagramInsightsResponse.class, igUserId);
    }
}
```

### 1.5 OAuth Flow Implementation

#### MetaOAuthService.java

```java
package com.influora.integration.meta.oauth;

import com.influora.integration.meta.config.MetaApiProperties;
import com.influora.integration.meta.dto.MetaTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MetaOAuthService {

    private final MetaApiProperties props;
    private final MetaTokenStorage tokenStorage;
    private final RestClient restClient;

    // Required OAuth scopes for full functionality
    public static final List<String> REQUIRED_SCOPES = List.of(
        "instagram_basic",           // Basic profile and media access
        "instagram_manage_insights", // Insights and demographics
        "pages_show_list",           // List connected Facebook Pages
        "pages_read_engagement"      // Page engagement metrics
    );

    public String buildAuthorizationUrl(String state) {
        return "https://www.facebook.com/" + props.graphApiVersion() + "/dialog/oauth" +
               "?client_id=" + props.appId() +
               "&redirect_uri=" + urlEncode(props.redirectUri()) +
               "&scope=" + String.join(",", REQUIRED_SCOPES) +
               "&response_type=code" +
               "&state=" + state;
    }

    /**
     * Exchange authorization code for access token.
     * Short-lived token (~1-2 hours) must be exchanged for long-lived token.
     */
    public MetaTokenResponse exchangeCodeForToken(String code) {
        String url = "https://graph.facebook.com/" + props.graphApiVersion() + "/oauth/access_token" +
                     "?client_id=" + props.appId() +
                     "&client_secret=" + props.appSecret() +
                     "&redirect_uri=" + urlEncode(props.redirectUri()) +
                     "&code=" + code;
        
        return restClient.get()
            .uri(url)
            .retrieve()
            .body(MetaTokenResponse.class);
    }

    /**
     * Exchange short-lived token for long-lived token (~60 days).
     */
    public MetaTokenResponse exchangeForLongLivedToken(String shortLivedToken) {
        String url = "https://graph.facebook.com/" + props.graphApiVersion() + "/oauth/access_token" +
                     "?grant_type=fb_exchange_token" +
                     "&client_id=" + props.appId() +
                     "&client_secret=" + props.appSecret() +
                     "&fb_exchange_token=" + shortLivedToken;
        
        return restClient.get()
            .uri(url)
            .retrieve()
            .body(MetaTokenResponse.class);
    }

    /**
     * Refresh a long-lived token before expiry.
     * Call this when token has < 7 days remaining (per tokenRefreshDaysBeforeExpiry).
     */
    public MetaTokenResponse refreshLongLivedToken(String currentToken) {
        // Same endpoint as long-lived exchange
        return exchangeForLongLivedToken(currentToken);
    }
}
```

#### MetaTokenStorage.java (Encrypted Token Storage)

```java
package com.influora.integration.meta.oauth;

import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * [SEC: Kabir requirement] — AES-256-GCM encryption for all Meta OAuth tokens.
 * NEVER store plain tokens in database.
 */
@Service
public class MetaTokenStorage {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final MetaOAuthTokenRepository repository;
    private final AuditLogService auditLog;
    private final byte[] encryptionKey; // Loaded from config, NEVER hardcoded

    public MetaTokenStorage(
            MetaOAuthTokenRepository repository,
            AuditLogService auditLog,
            @Value("${influora.meta.token-encryption-key}") String base64Key) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.encryptionKey = Base64.getDecoder().decode(base64Key);
        if (encryptionKey.length != 32) {
            throw new IllegalStateException("Token encryption key must be 256 bits (32 bytes)");
        }
    }

    /**
     * Store encrypted token for a creator.
     * @param creatorProfileId FK to creator_profiles.id
     */
    @Transactional
    public void storeToken(String creatorProfileId, String workspaceId, String accessToken, 
                           Instant expiresAt, List<String> grantedScopes) {
        String encrypted = encrypt(accessToken);
        
        MetaOAuthToken entity = MetaOAuthToken.builder()
            .id(Ulids.newUlid())
            .creatorProfileId(creatorProfileId)
            .workspaceId(workspaceId)
            .encryptedAccessToken(encrypted)
            .expiresAt(expiresAt)
            .grantedScopesJson(JsonLists.toJson(grantedScopes))
            .lastRefreshedAt(Instant.now())
            .build();
        
        repository.save(entity);
        
        auditLog.recordToolCall(
            workspaceId, "META_OAUTH_TOKEN_STORED", "SENSITIVE",
            AuditLogService.OUTCOME_ALLOWED, null, null, null,
            Map.of("creatorProfileId", creatorProfileId, "scopeCount", grantedScopes.size())
        );
    }

    /**
     * Retrieve decrypted token for API calls.
     * Returns Optional.empty() if token doesn't exist or is expired.
     */
    @Transactional(readOnly = true)
    public Optional<String> getValidToken(String creatorProfileId) {
        return repository.findByCreatorProfileId(creatorProfileId)
            .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
            .map(t -> decrypt(t.getEncryptedAccessToken()));
    }

    /**
     * Find tokens expiring within N days (for proactive refresh).
     */
    public List<MetaOAuthToken> findTokensExpiringSoon(int days) {
        Instant threshold = Instant.now().plus(Duration.ofDays(days));
        return repository.findByExpiresAtBeforeAndRevokedFalse(threshold);
    }

    private String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    private String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), spec);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed", e);
        }
    }
}
```

### 1.6 Rate Limit Tracking

```java
package com.influora.integration.meta.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tracks X-Business-Use-Case-Usage header from Meta API responses.
 * Example header value: {"ads_management":{"call_count":28,"total_cputime":25,"total_time":45}}
 */
@Component
public class MetaRateLimitTracker {

    private final ConcurrentHashMap<String, RateLimitState> accountLimits = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record RateLimitState(
        int callCount,        // 0-100 percentage
        int totalCpuTime,     // 0-100 percentage
        int totalTime,        // 0-100 percentage
        Instant updatedAt,
        boolean isLimited
    ) {
        public int getMaxUsage() {
            return Math.max(callCount, Math.max(totalCpuTime, totalTime));
        }
    }

    /**
     * Parse and store rate limit state from header.
     */
    public void update(String businessAccountId, String headerValue) {
        try {
            JsonNode root = objectMapper.readTree(headerValue);
            // Sum across all use cases (ads_management, instagram_basic, etc.)
            int maxCallCount = 0, maxCpuTime = 0, maxTotalTime = 0;
            
            for (JsonNode useCase : root) {
                maxCallCount = Math.max(maxCallCount, useCase.path("call_count").asInt(0));
                maxCpuTime = Math.max(maxCpuTime, useCase.path("total_cputime").asInt(0));
                maxTotalTime = Math.max(maxTotalTime, useCase.path("total_time").asInt(0));
            }
            
            accountLimits.put(businessAccountId, new RateLimitState(
                maxCallCount, maxCpuTime, maxTotalTime, Instant.now(), false
            ));
        } catch (Exception e) {
            // Log but don't fail the request
            log.warn("Failed to parse rate limit header for {}: {}", businessAccountId, e.getMessage());
        }
    }

    public int getCurrentUsage(String businessAccountId) {
        RateLimitState state = accountLimits.get(businessAccountId);
        if (state == null) return 0;
        // If data is stale (> 5 minutes), assume reset
        if (state.updatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            return 0;
        }
        return state.getMaxUsage();
    }

    public void markLimited(String businessAccountId) {
        RateLimitState current = accountLimits.get(businessAccountId);
        if (current != null) {
            accountLimits.put(businessAccountId, new RateLimitState(
                100, 100, 100, Instant.now(), true
            ));
        }
    }

    public void resetAll() {
        accountLimits.clear();
    }
}
```

### 1.7 Required OAuth Permissions

| Permission | Purpose | Required For |
|------------|---------|--------------|
| `instagram_basic` | Read profile info, media list | All creators |
| `instagram_manage_insights` | Read insights, demographics | Verified metrics |
| `pages_show_list` | List connected FB Pages | FB integration |
| `pages_read_engagement` | Read Page engagement data | FB metrics |

### 1.8 2026 Compliance Notes

1. **Graph API Version**: Use `v25.0` or higher. Older versions deprecated.
2. **Page Viewer Metric Migration**: By June 15, 2026, `page_views_total` changes format. Update `FacebookPageClient` before deadline.
3. **Instagram Content Publishing API**: If Influora adds content scheduling, requires `instagram_content_publish` scope and App Review.

---

## 2. Database Migrations (TimescaleDB)

### 2.1 Prerequisites

Add TimescaleDB to pom.xml (verify with Priya for approval):

```xml
<!-- TimescaleDB JDBC driver (same as PostgreSQL) -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

**NOTE:** Current codebase uses MySQL. TimescaleDB requires PostgreSQL. Two options:
1. **Option A**: Migrate entire database to PostgreSQL + TimescaleDB (major effort)
2. **Option B**: Use separate TimescaleDB instance for time-series data only (recommended)

CTO Decision Required: Confirm Option B before proceeding.

### 2.2 V20__timescale_hypertables.sql

```sql
-- TimescaleDB hypertables for time-series metrics
-- Run against PostgreSQL + TimescaleDB instance (separate from MySQL main DB)

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Creator-level metrics (daily rollups)
CREATE TABLE creator_metrics (
    time                    TIMESTAMPTZ NOT NULL,
    creator_profile_id      VARCHAR(26) NOT NULL,
    platform                VARCHAR(20) NOT NULL, -- INSTAGRAM, FACEBOOK, YOUTUBE
    followers               BIGINT NOT NULL,
    following               BIGINT,
    media_count             INT,
    avg_engagement_rate     DECIMAL(8,4),  -- as percentage: 4.5200 = 4.52%
    avg_reach_per_post      BIGINT,
    avg_impressions_per_post BIGINT,
    profile_views           BIGINT,
    website_clicks          BIGINT,
    data_source             VARCHAR(20) NOT NULL DEFAULT 'META_API', -- META_API, MANUAL, ESTIMATED
    fetched_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, creator_profile_id, platform)
);

-- Convert to hypertable (chunks by week)
SELECT create_hypertable('creator_metrics', 'time', chunk_time_interval => INTERVAL '7 days');

-- Create indexes for common queries
CREATE INDEX idx_creator_metrics_creator ON creator_metrics (creator_profile_id, time DESC);
CREATE INDEX idx_creator_metrics_platform ON creator_metrics (platform, time DESC);

-- Media-level metrics (per-post analytics)
CREATE TABLE media_metrics (
    time                    TIMESTAMPTZ NOT NULL,
    media_id                VARCHAR(50) NOT NULL,  -- Meta's media ID
    creator_profile_id      VARCHAR(26) NOT NULL,
    platform                VARCHAR(20) NOT NULL,
    media_type              VARCHAR(20) NOT NULL,  -- IMAGE, VIDEO, CAROUSEL_ALBUM, REELS, STORY
    permalink               VARCHAR(500),
    impressions             BIGINT,
    reach                   BIGINT,
    engagement              BIGINT,  -- likes + comments + saves + shares
    likes                   BIGINT,
    comments                BIGINT,
    saves                   BIGINT,
    shares                  BIGINT,
    video_views             BIGINT,  -- NULL for non-video
    avg_watch_time_seconds  DECIMAL(10,2),
    posted_at               TIMESTAMPTZ,
    data_source             VARCHAR(20) NOT NULL DEFAULT 'META_API',
    fetched_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, media_id)
);

SELECT create_hypertable('media_metrics', 'time', chunk_time_interval => INTERVAL '7 days');

CREATE INDEX idx_media_metrics_creator ON media_metrics (creator_profile_id, time DESC);
CREATE INDEX idx_media_metrics_type ON media_metrics (media_type, time DESC);

-- Continuous aggregate for faster dashboard queries (daily summary)
CREATE MATERIALIZED VIEW creator_metrics_daily
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 day', time) AS day,
    creator_profile_id,
    platform,
    LAST(followers, time) AS followers,
    AVG(avg_engagement_rate) AS avg_engagement_rate,
    SUM(profile_views) AS total_profile_views
FROM creator_metrics
GROUP BY 1, 2, 3
WITH NO DATA;

-- Refresh policy: refresh daily, keep last 90 days
SELECT add_continuous_aggregate_policy('creator_metrics_daily',
    start_offset => INTERVAL '90 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 day');

-- Retention policy: drop raw data older than 2 years
SELECT add_retention_policy('creator_metrics', INTERVAL '2 years');
SELECT add_retention_policy('media_metrics', INTERVAL '2 years');
```

### 2.3 V21__audience_demographics.sql

```sql
-- Audience demographics (refreshed weekly)
CREATE TABLE audience_demographics (
    time                    TIMESTAMPTZ NOT NULL,
    creator_profile_id      VARCHAR(26) NOT NULL,
    platform                VARCHAR(20) NOT NULL,
    
    -- Age/Gender breakdown (stored as JSONB for flexibility)
    gender_age_distribution JSONB,  -- {"F.18-24": 15.2, "M.25-34": 22.1, ...}
    
    -- Geographic breakdown
    top_cities              JSONB,  -- [{"name": "Mumbai", "pct": 12.5}, ...]
    top_countries           JSONB,  -- [{"code": "IN", "pct": 65.2}, ...]
    
    -- Language breakdown
    locale_distribution     JSONB,  -- [{"locale": "en_US", "pct": 45.0}, ...]
    
    -- Audience quality indicators
    estimated_real_pct      DECIMAL(5,2),  -- % estimated real followers (from FakeFollowerDetection)
    
    data_source             VARCHAR(20) NOT NULL DEFAULT 'META_API',
    fetched_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, creator_profile_id, platform)
);

SELECT create_hypertable('audience_demographics', 'time', chunk_time_interval => INTERVAL '30 days');

CREATE INDEX idx_audience_demo_creator ON audience_demographics (creator_profile_id, time DESC);
```

### 2.4 V22__creator_scores.sql

```sql
-- Creator quality scores (computed by scoring algorithms)
CREATE TABLE creator_scores (
    time                    TIMESTAMPTZ NOT NULL,
    creator_profile_id      VARCHAR(26) NOT NULL,
    
    -- Fake Follower Detection
    fake_follower_score     DECIMAL(5,2),  -- 0-100, higher = more suspicious
    fake_follower_reasons   JSONB,         -- ["sudden_spike", "low_engagement_ratio", ...]
    
    -- Quality Score (composite)
    quality_score           DECIMAL(5,2),  -- 0-100, higher = better
    engagement_consistency  DECIMAL(5,2),  -- std dev of engagement over time
    posting_frequency       DECIMAL(5,2),  -- posts per week, normalized
    audience_match_score    DECIMAL(5,2),  -- how well audience matches typical brand targets
    
    -- Brand Safety Score
    brand_safety_score      DECIMAL(5,2),  -- 0-100, higher = safer
    garm_flags              JSONB,         -- ["adult_content", "controversial_topics", ...]
    content_sentiment       DECIMAL(5,2),  -- -1 to +1, average sentiment
    
    -- Rate Estimation
    estimated_rate_min      DECIMAL(12,2),
    estimated_rate_max      DECIMAL(12,2),
    rate_currency           VARCHAR(3) DEFAULT 'INR',
    rate_confidence         DECIMAL(5,2),  -- 0-100
    
    -- Meta
    algorithm_version       VARCHAR(20) NOT NULL,  -- e.g., "v1.2.0"
    computed_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, creator_profile_id)
);

SELECT create_hypertable('creator_scores', 'time', chunk_time_interval => INTERVAL '30 days');

CREATE INDEX idx_creator_scores_creator ON creator_scores (creator_profile_id, time DESC);
CREATE INDEX idx_creator_scores_quality ON creator_scores (quality_score DESC, time DESC);
```

### 2.5 V23__utm_campaigns.sql

```sql
-- UTM campaign tracking (links generated per creator per campaign)
CREATE TABLE utm_campaigns (
    id                      VARCHAR(26) PRIMARY KEY,
    campaign_id             VARCHAR(26) NOT NULL,  -- FK to campaigns.id
    collaboration_id        VARCHAR(26) NOT NULL,  -- FK to collaborations.id
    creator_profile_id      VARCHAR(26) NOT NULL,
    
    -- UTM parameters
    base_url                VARCHAR(1000) NOT NULL,
    utm_source              VARCHAR(100) NOT NULL, -- 'instagram', 'youtube', etc.
    utm_medium              VARCHAR(100) NOT NULL, -- 'influencer', 'creator_post'
    utm_campaign            VARCHAR(100) NOT NULL, -- campaign slug
    utm_content             VARCHAR(100),          -- specific post/story identifier
    utm_term                VARCHAR(100),          -- optional: keywords
    
    -- Generated link
    full_tracking_url       VARCHAR(2000) NOT NULL,
    short_url               VARCHAR(200),          -- optional: bit.ly or similar
    
    -- Stats (updated by ConversionTrackingService)
    click_count             BIGINT NOT NULL DEFAULT 0,
    unique_visitors         BIGINT NOT NULL DEFAULT 0,
    conversion_count        BIGINT NOT NULL DEFAULT 0,
    revenue_attributed      DECIMAL(14,2) DEFAULT 0.00,
    
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ,  -- optional: auto-expire tracking
    
    UNIQUE(campaign_id, creator_profile_id)
);

CREATE INDEX idx_utm_campaign ON utm_campaigns (campaign_id);
CREATE INDEX idx_utm_creator ON utm_campaigns (creator_profile_id);
CREATE INDEX idx_utm_collab ON utm_campaigns (collaboration_id);
```

### 2.6 V24__coupon_codes.sql

```sql
-- Coupon codes for conversion tracking
CREATE TABLE coupon_codes (
    id                      VARCHAR(26) PRIMARY KEY,
    campaign_id             VARCHAR(26) NOT NULL,
    collaboration_id        VARCHAR(26),           -- NULL if campaign-wide code
    creator_profile_id      VARCHAR(26),           -- NULL if campaign-wide
    
    -- Code details
    code                    VARCHAR(50) NOT NULL UNIQUE,  -- e.g., "PRIYA15"
    code_type               VARCHAR(20) NOT NULL,  -- PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING
    discount_value          DECIMAL(10,2) NOT NULL, -- 15 for 15%, or 500 for INR 500
    
    -- Usage limits
    max_uses                INT,                   -- NULL = unlimited
    max_uses_per_user       INT DEFAULT 1,
    current_uses            INT NOT NULL DEFAULT 0,
    
    -- Revenue tracking
    total_revenue_driven    DECIMAL(14,2) DEFAULT 0.00,
    avg_order_value         DECIMAL(12,2),
    
    -- Validity
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until             TIMESTAMPTZ,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coupon_campaign ON coupon_codes (campaign_id);
CREATE INDEX idx_coupon_creator ON coupon_codes (creator_profile_id) WHERE creator_profile_id IS NOT NULL;
CREATE INDEX idx_coupon_code ON coupon_codes (code);
CREATE INDEX idx_coupon_active ON coupon_codes (is_active, valid_until);

-- Coupon redemptions (for idempotency and audit)
CREATE TABLE coupon_redemptions (
    id                      VARCHAR(26) PRIMARY KEY,
    coupon_id               VARCHAR(26) NOT NULL REFERENCES coupon_codes(id),
    order_id                VARCHAR(100) NOT NULL, -- external order ID from brand's system
    order_amount            DECIMAL(12,2) NOT NULL,
    discount_applied        DECIMAL(12,2) NOT NULL,
    customer_id             VARCHAR(100),          -- optional: for per-user limit tracking
    redeemed_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    idempotency_key         VARCHAR(100) NOT NULL UNIQUE,  -- [SEC: Kabir] prevent double-counting
    
    metadata                JSONB                  -- additional brand-provided data
);

CREATE INDEX idx_redemption_coupon ON coupon_redemptions (coupon_id);
CREATE INDEX idx_redemption_order ON coupon_redemptions (order_id);
CREATE INDEX idx_redemption_time ON coupon_redemptions (redeemed_at DESC);
```

### 2.7 Entity Classes

Create these entities in `com.influora.domain.entity.metrics/`:

| Entity Class | Table | Notes |
|--------------|-------|-------|
| `CreatorMetric.java` | `creator_metrics` | Immutable, time-series |
| `MediaMetric.java` | `media_metrics` | Immutable, time-series |
| `AudienceDemographics.java` | `audience_demographics` | Weekly snapshots |
| `CreatorScore.java` | `creator_scores` | Computed scores |
| `UtmCampaign.java` | `utm_campaigns` | Mutable (click counts) |
| `CouponCode.java` | `coupon_codes` | Mutable (usage counts) |
| `CouponRedemption.java` | `coupon_redemptions` | Immutable, idempotent |

---

## 3. Scheduled Jobs

Create package: `com.influora.job/`

### 3.1 MetricsPollingJob.java

```java
package com.influora.job;

import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.metrics.CreatorMetricRepository;
import com.influora.service.AuditLogService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls Instagram/Facebook metrics for all connected creators.
 * Schedule: Every 6 hours (rate limit safe)
 */
@Component
public class MetricsPollingJob {

    private final CreatorProfileRepository creatorRepo;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient igClient;
    private final CreatorMetricRepository metricRepo;
    private final AuditLogService auditLog;

    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    public void pollMetrics() {
        List<String> connectedCreators = creatorRepo.findAllWithMetaTokens();
        
        for (String creatorId : connectedCreators) {
            try {
                Optional<String> token = tokenStorage.getValidToken(creatorId);
                if (token.isEmpty()) {
                    log.warn("No valid token for creator {}, skipping", creatorId);
                    continue;
                }
                
                // Fetch profile metrics
                InstagramUserResponse profile = igClient.getProfile(creatorId, token.get());
                
                CreatorMetric metric = CreatorMetric.builder()
                    .time(Instant.now())
                    .creatorProfileId(creatorId)
                    .platform("INSTAGRAM")
                    .followers(profile.followersCount())
                    .following(profile.followsCount())
                    .mediaCount(profile.mediaCount())
                    .dataSource("META_API")
                    .build();
                
                metricRepo.save(metric);
                
                // Fetch recent media metrics (last 25 posts)
                InstagramMediaResponse media = igClient.getMedia(creatorId, token.get(), 25);
                for (var post : media.data()) {
                    try {
                        var insights = igClient.getMediaInsights(post.id(), token.get(), creatorId);
                        // Save media metrics...
                    } catch (Exception e) {
                        log.warn("Failed to fetch insights for media {}: {}", post.id(), e.getMessage());
                    }
                }
                
            } catch (MetaRateLimitException e) {
                log.warn("Rate limited for creator {}, will retry next cycle", creatorId);
            } catch (MetaTokenExpiredException e) {
                log.warn("Token expired for creator {}, needs re-auth", creatorId);
                // Could trigger notification to creator here
            } catch (Exception e) {
                log.error("Failed to poll metrics for creator {}", creatorId, e);
            }
        }
        
        auditLog.recordMoneyEvent(
            null, "METRICS_POLLING_COMPLETED", null, null, null, null,
            Map.of("creatorsPolled", connectedCreators.size())
        );
    }
}
```

### 3.2 AudienceDemographicsJob.java

```java
@Component
public class AudienceDemographicsJob {

    @Scheduled(cron = "0 0 3 * * SUN") // Every Sunday at 3 AM
    public void refreshDemographics() {
        // Only fetch for creators with 100+ followers (Meta API requirement)
        List<String> eligibleCreators = creatorRepo.findWithFollowersGreaterThan(100);
        
        for (String creatorId : eligibleCreators) {
            Optional<String> token = tokenStorage.getValidToken(creatorId);
            if (token.isEmpty()) continue;
            
            try {
                AudienceDemographicsResponse demo = igClient.getAudienceDemographics(creatorId, token.get());
                
                AudienceDemographics entity = AudienceDemographics.builder()
                    .time(Instant.now())
                    .creatorProfileId(creatorId)
                    .platform("INSTAGRAM")
                    .genderAgeDistribution(demo.genderAge())
                    .topCities(demo.cities())
                    .topCountries(demo.countries())
                    .localeDistribution(demo.locales())
                    .dataSource("META_API")
                    .build();
                
                demographicsRepo.save(entity);
            } catch (Exception e) {
                log.warn("Failed to fetch demographics for {}: {}", creatorId, e.getMessage());
            }
        }
    }
}
```

### 3.3 ScoreCalculationJob.java

```java
@Component
public class ScoreCalculationJob {

    private final FakeFollowerDetectionService fakeDetector;
    private final QualityScoreService qualityScorer;
    private final BrandSafetyScoreService brandSafetyScorer;
    private final RateEstimationService rateEstimator;
    private final CreatorScoreRepository scoreRepo;

    @Scheduled(cron = "0 0 4 * * *") // Daily at 4 AM
    public void calculateScores() {
        List<String> allCreators = creatorRepo.findAllDiscoverable();
        
        for (String creatorId : allCreators) {
            try {
                // Get latest metrics for scoring
                Optional<CreatorMetric> latestMetric = metricRepo.findLatestByCreator(creatorId);
                List<MediaMetric> recentMedia = mediaRepo.findRecentByCreator(creatorId, 30);
                Optional<AudienceDemographics> demo = demographicsRepo.findLatestByCreator(creatorId);
                
                // Calculate all scores
                FakeFollowerResult fakeResult = fakeDetector.analyze(latestMetric, recentMedia);
                QualityScoreResult qualityResult = qualityScorer.calculate(latestMetric, recentMedia);
                BrandSafetyResult safetyResult = brandSafetyScorer.analyze(creatorId);
                RateEstimation rateResult = rateEstimator.estimate(latestMetric, qualityResult);
                
                CreatorScore score = CreatorScore.builder()
                    .time(Instant.now())
                    .creatorProfileId(creatorId)
                    .fakeFollowerScore(fakeResult.score())
                    .fakeFollowerReasons(fakeResult.reasons())
                    .qualityScore(qualityResult.overall())
                    .engagementConsistency(qualityResult.consistency())
                    .postingFrequency(qualityResult.frequency())
                    .brandSafetyScore(safetyResult.score())
                    .garmFlags(safetyResult.flags())
                    .estimatedRateMin(rateResult.min())
                    .estimatedRateMax(rateResult.max())
                    .rateCurrency(rateResult.currency())
                    .rateConfidence(rateResult.confidence())
                    .algorithmVersion("v1.0.0")
                    .build();
                
                scoreRepo.save(score);
            } catch (Exception e) {
                log.error("Failed to calculate scores for creator {}", creatorId, e);
            }
        }
    }
}
```

### 3.4 StaleTokenCleanupJob.java

```java
@Component
public class StaleTokenCleanupJob {

    private final MetaOAuthTokenRepository tokenRepo;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
    public void cleanupStaleTokens() {
        // Find tokens expired > 30 days ago
        Instant threshold = Instant.now().minus(Duration.ofDays(30));
        List<MetaOAuthToken> staleTokens = tokenRepo.findExpiredBefore(threshold);
        
        for (MetaOAuthToken token : staleTokens) {
            // Mark as revoked (soft delete)
            token.markRevoked();
            tokenRepo.save(token);
            
            // Notify creator to reconnect
            notificationService.send(
                token.getCreatorProfileId(),
                NotificationType.META_RECONNECT_NEEDED,
                Map.of("platform", "Instagram")
            );
        }
        
        log.info("Cleaned up {} stale Meta tokens", staleTokens.size());
    }
}
```

### 3.5 RateLimitResetJob.java

```java
@Component
public class RateLimitResetJob {

    private final MetaRateLimitTracker rateLimitTracker;

    /**
     * Meta resets rate limits hourly. Clear our tracking state to avoid stale throttling.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void resetRateLimits() {
        rateLimitTracker.resetAll();
        log.debug("Rate limit tracker reset");
    }
}
```

### 3.6 ContentLibraryDiscoveryJob.java

```java
@Component
public class ContentLibraryDiscoveryJob {

    /**
     * Discovers public creators via Meta Content Library API (requires special access).
     * This is for finding NEW creators, not polling existing ones.
     * 
     * NOTE: Meta Content Library API has strict access requirements.
     * Verify Influora has access before implementing.
     */
    @Scheduled(cron = "0 0 5 * * MON") // Weekly on Monday at 5 AM
    public void discoverCreators() {
        // Implementation depends on Content Library API access
        // This job may need to be deferred until API access is confirmed
        log.info("Content Library discovery job - implementation pending API access");
    }
}
```

---

## 4. Scoring Algorithms Service Layer

Create package: `com.influora.service.scoring/`

### 4.1 FakeFollowerDetectionService.java

```java
package com.influora.service.scoring;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/**
 * Detects likely fake/bot followers using statistical anomaly detection.
 * Scoring: 0 = definitely real, 100 = definitely fake
 */
@Service
public class FakeFollowerDetectionService {

    public record FakeFollowerResult(
        BigDecimal score,         // 0-100
        List<String> reasons,     // Explanations
        Map<String, Object> debug // Raw signal values
    ) {}

    /**
     * Analyzes a creator's metrics for fake follower indicators.
     */
    public FakeFollowerResult analyze(
            Optional<CreatorMetric> latestMetric,
            List<MediaMetric> recentMedia,
            List<CreatorMetric> historicalMetrics) {
        
        List<String> reasons = new ArrayList<>();
        double score = 0.0;
        Map<String, Object> debug = new HashMap<>();
        
        if (latestMetric.isEmpty()) {
            return new FakeFollowerResult(BigDecimal.ZERO, List.of("No metrics available"), Map.of());
        }
        
        CreatorMetric metric = latestMetric.get();
        
        // === Signal 1: Engagement Rate Anomaly ===
        // Typical healthy rate: 1-5%. Below 0.5% or above 15% is suspicious.
        double engagementRate = calculateEngagementRate(metric, recentMedia);
        debug.put("engagementRate", engagementRate);
        
        if (engagementRate < 0.5) {
            score += 25;
            reasons.add("Extremely low engagement rate (" + String.format("%.2f%%", engagementRate) + ")");
        } else if (engagementRate > 15 && metric.getFollowers() > 10000) {
            score += 15;
            reasons.add("Suspiciously high engagement rate for follower count");
        }
        
        // === Signal 2: Follower Growth Spike ===
        // Sudden jumps (>20% in a day) suggest purchased followers
        if (historicalMetrics.size() >= 7) {
            double maxDailyGrowth = calculateMaxDailyGrowthRate(historicalMetrics);
            debug.put("maxDailyGrowthPct", maxDailyGrowth);
            
            if (maxDailyGrowth > 20) {
                score += 30;
                reasons.add("Sudden follower spike detected (" + String.format("%.1f%%", maxDailyGrowth) + " in one day)");
            } else if (maxDailyGrowth > 10) {
                score += 15;
                reasons.add("Unusual follower growth pattern");
            }
        }
        
        // === Signal 3: Follower-to-Following Ratio ===
        // Healthy creators: followers >> following. Bots often follow many to get followbacks.
        double ffRatio = metric.getFollowing() > 0 
            ? (double) metric.getFollowers() / metric.getFollowing() 
            : metric.getFollowers();
        debug.put("followerFollowingRatio", ffRatio);
        
        if (ffRatio < 0.5 && metric.getFollowers() > 1000) {
            score += 20;
            reasons.add("Low follower-to-following ratio (" + String.format("%.2f", ffRatio) + ")");
        }
        
        // === Signal 4: Comment Quality (if available) ===
        // Generic comments ("Nice!", emoji-only) suggest bots
        // This requires NLP integration - placeholder for now
        
        // === Signal 5: Posting Consistency ===
        // Real influencers post regularly. Accounts that post rarely but have high followers = suspicious
        if (recentMedia.size() < 3 && metric.getFollowers() > 50000) {
            score += 15;
            reasons.add("Very few recent posts for a large account");
        }
        
        // Cap at 100
        score = Math.min(score, 100);
        
        return new FakeFollowerResult(
            BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP),
            reasons,
            debug
        );
    }

    private double calculateEngagementRate(CreatorMetric metric, List<MediaMetric> recentMedia) {
        if (recentMedia.isEmpty() || metric.getFollowers() == 0) return 0;
        
        double totalEngagement = recentMedia.stream()
            .mapToLong(m -> m.getLikes() + m.getComments())
            .average()
            .orElse(0);
        
        return (totalEngagement / metric.getFollowers()) * 100;
    }

    private double calculateMaxDailyGrowthRate(List<CreatorMetric> metrics) {
        // Sort by time
        List<CreatorMetric> sorted = new ArrayList<>(metrics);
        sorted.sort(Comparator.comparing(CreatorMetric::getTime));
        
        double maxGrowth = 0;
        for (int i = 1; i < sorted.size(); i++) {
            long prev = sorted.get(i - 1).getFollowers();
            long curr = sorted.get(i).getFollowers();
            if (prev > 0) {
                double growth = ((double) (curr - prev) / prev) * 100;
                maxGrowth = Math.max(maxGrowth, growth);
            }
        }
        return maxGrowth;
    }
}
```

### 4.2 QualityScoreService.java

```java
package com.influora.service.scoring;

/**
 * Calculates overall creator quality based on engagement, consistency, and activity.
 * Score: 0 = poor quality, 100 = excellent
 */
@Service
public class QualityScoreService {

    public record QualityScoreResult(
        BigDecimal overall,          // 0-100 composite
        BigDecimal engagementScore,  // 0-100
        BigDecimal consistency,      // 0-100 (low std dev = high score)
        BigDecimal frequency,        // 0-100 (based on posts/week)
        BigDecimal audienceMatch     // 0-100 (placeholder for brand matching)
    ) {}

    public QualityScoreResult calculate(
            Optional<CreatorMetric> latestMetric,
            List<MediaMetric> recentMedia) {
        
        if (latestMetric.isEmpty()) {
            return new QualityScoreResult(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            );
        }
        
        // === Engagement Score (40% weight) ===
        // Based on engagement rate percentile
        double engagementRate = calculateEngagementRate(latestMetric.get(), recentMedia);
        double engagementScore = scoreEngagementRate(engagementRate);
        
        // === Consistency Score (25% weight) ===
        // Low variance in engagement = more predictable = higher score
        double consistencyScore = calculateConsistencyScore(recentMedia);
        
        // === Posting Frequency Score (20% weight) ===
        // Optimal: 3-7 posts per week
        double frequencyScore = calculateFrequencyScore(recentMedia);
        
        // === Audience Match (15% weight) ===
        // Placeholder - would compare audience demographics to brand targets
        double audienceMatchScore = 50.0; // Default neutral
        
        // === Composite ===
        double overall = (engagementScore * 0.40) + 
                        (consistencyScore * 0.25) + 
                        (frequencyScore * 0.20) + 
                        (audienceMatchScore * 0.15);
        
        return new QualityScoreResult(
            BigDecimal.valueOf(overall).setScale(2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(engagementScore).setScale(2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(consistencyScore).setScale(2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(frequencyScore).setScale(2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(audienceMatchScore).setScale(2, RoundingMode.HALF_UP)
        );
    }

    private double scoreEngagementRate(double rate) {
        // Sigmoid curve: 0% -> 0, 2% -> 50, 5%+ -> 90+
        if (rate <= 0) return 0;
        if (rate >= 10) return 100;
        return 100 * (1 - Math.exp(-rate / 2.5));
    }

    private double calculateConsistencyScore(List<MediaMetric> media) {
        if (media.size() < 3) return 50; // Not enough data
        
        // Calculate coefficient of variation (CV) of engagement
        List<Long> engagements = media.stream()
            .map(m -> m.getLikes() + m.getComments())
            .toList();
        
        double mean = engagements.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean == 0) return 50;
        
        double variance = engagements.stream()
            .mapToDouble(e -> Math.pow(e - mean, 2))
            .average()
            .orElse(0);
        double stdDev = Math.sqrt(variance);
        double cv = stdDev / mean;
        
        // Lower CV = more consistent = higher score
        // CV of 0.3 = 70 score, CV of 1.0 = 30 score
        return Math.max(0, Math.min(100, 100 - (cv * 70)));
    }

    private double calculateFrequencyScore(List<MediaMetric> media) {
        if (media.isEmpty()) return 0;
        
        // Calculate posts per week over last 30 days
        Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
        long recentPosts = media.stream()
            .filter(m -> m.getPostedAt() != null && m.getPostedAt().isAfter(thirtyDaysAgo))
            .count();
        
        double postsPerWeek = recentPosts / 4.0; // 30 days ≈ 4 weeks
        
        // Optimal: 3-7 posts/week
        if (postsPerWeek >= 3 && postsPerWeek <= 7) return 100;
        if (postsPerWeek < 1) return 20;
        if (postsPerWeek < 3) return 40 + (postsPerWeek * 20);
        if (postsPerWeek > 14) return 50; // Spam territory
        return 100 - ((postsPerWeek - 7) * 7); // Gradual decrease above 7
    }
}
```

### 4.3 BrandSafetyScoreService.java

```java
package com.influora.service.scoring;

/**
 * Brand safety scoring using GARM (Global Alliance for Responsible Media) framework.
 * Integrates with influora-ai for NLP analysis.
 * Score: 0 = dangerous, 100 = completely safe
 */
@Service
public class BrandSafetyScoreService {

    private final InfluoraAiClient aiClient; // Calls influora-ai FastAPI service

    // GARM categories
    public static final List<String> GARM_CATEGORIES = List.of(
        "adult_explicit",
        "arms_ammunition",
        "crime_harmful_acts",
        "death_injury",
        "online_piracy",
        "hate_speech",
        "military_conflict",
        "obscenity",
        "drugs_alcohol_tobacco",
        "spam_malware",
        "terrorism",
        "sensitive_social_issues"
    );

    public record BrandSafetyResult(
        BigDecimal score,           // 0-100
        List<String> garmFlags,     // Triggered categories
        BigDecimal contentSentiment,// -1 to +1
        Map<String, Object> debug
    ) {}

    /**
     * Analyze recent content for brand safety.
     */
    public BrandSafetyResult analyze(String creatorProfileId) {
        List<String> flags = new ArrayList<>();
        Map<String, Object> debug = new HashMap<>();
        double score = 100.0;
        
        // Fetch recent captions/content
        List<String> recentCaptions = mediaRepo.findRecentCaptions(creatorProfileId, 50);
        
        if (recentCaptions.isEmpty()) {
            return new BrandSafetyResult(
                BigDecimal.valueOf(75), // Unknown = moderate safety
                List.of(),
                BigDecimal.ZERO,
                Map.of("reason", "No content to analyze")
            );
        }
        
        // === Call influora-ai for content analysis ===
        ContentAnalysisResponse analysis = aiClient.analyzeContent(
            new ContentAnalysisRequest(
                creatorProfileId,
                recentCaptions,
                GARM_CATEGORIES
            )
        );
        
        // Process GARM flags
        for (var flag : analysis.detectedCategories()) {
            if (flag.confidence() > 0.7) {
                flags.add(flag.category());
                score -= flag.severity() * 10; // Severity: 1-5
            }
        }
        
        // Sentiment analysis
        double avgSentiment = analysis.avgSentiment(); // -1 to +1
        debug.put("avgSentiment", avgSentiment);
        
        if (avgSentiment < -0.3) {
            score -= 15;
            flags.add("negative_sentiment");
        }
        
        score = Math.max(0, score);
        
        return new BrandSafetyResult(
            BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP),
            flags,
            BigDecimal.valueOf(avgSentiment).setScale(4, RoundingMode.HALF_UP),
            debug
        );
    }
}
```

### 4.4 RateEstimationService.java

```java
package com.influora.service.scoring;

/**
 * Estimates fair market rates for creators based on:
 * - Niche/category (lifestyle > tech > fitness typically)
 * - Engagement rate
 * - Follower tier
 * - Quality score
 * - Market rates (India-specific)
 */
@Service
public class RateEstimationService {

    // Base rates per post by follower tier (INR)
    private static final Map<String, long[]> TIER_BASE_RATES = Map.of(
        "NANO",        new long[]{1000, 5000},      // 1K-10K followers
        "MICRO",       new long[]{5000, 25000},     // 10K-50K followers
        "MID",         new long[]{25000, 100000},   // 50K-500K followers
        "MACRO",       new long[]{100000, 500000},  // 500K-1M followers
        "MEGA",        new long[]{500000, 2500000}  // 1M+ followers
    );

    // Category multipliers
    private static final Map<String, Double> CATEGORY_MULTIPLIERS = Map.of(
        "FASHION",     1.3,
        "BEAUTY",      1.25,
        "LIFESTYLE",   1.2,
        "TRAVEL",      1.15,
        "FOOD",        1.1,
        "TECH",        1.05,
        "FITNESS",     1.0,
        "GAMING",      0.95,
        "EDUCATION",   0.9
    );

    public record RateEstimation(
        BigDecimal min,
        BigDecimal max,
        String currency,
        BigDecimal confidence,  // 0-100
        String tier,
        Map<String, Object> factors
    ) {}

    public RateEstimation estimate(
            Optional<CreatorMetric> metric,
            QualityScoreResult qualityScore,
            List<String> categories) {
        
        if (metric.isEmpty()) {
            return new RateEstimation(
                BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "UNKNOWN", Map.of()
            );
        }
        
        long followers = metric.get().getFollowers();
        String tier = determineTier(followers);
        long[] baseRange = TIER_BASE_RATES.get(tier);
        
        // Start with base range
        double minRate = baseRange[0];
        double maxRate = baseRange[1];
        
        // === Engagement multiplier ===
        // High engagement (>5%) = +30%, Low (<1%) = -30%
        double engagementRate = metric.get().getAvgEngagementRate().doubleValue();
        double engagementMultiplier = 1.0;
        if (engagementRate > 5) engagementMultiplier = 1.3;
        else if (engagementRate > 3) engagementMultiplier = 1.15;
        else if (engagementRate < 1) engagementMultiplier = 0.7;
        
        minRate *= engagementMultiplier;
        maxRate *= engagementMultiplier;
        
        // === Category multiplier ===
        double categoryMultiplier = categories.stream()
            .map(c -> CATEGORY_MULTIPLIERS.getOrDefault(c.toUpperCase(), 1.0))
            .max(Double::compare)
            .orElse(1.0);
        
        minRate *= categoryMultiplier;
        maxRate *= categoryMultiplier;
        
        // === Quality score adjustment ===
        // Quality > 80 = +20%, Quality < 40 = -20%
        double qualityMultiplier = 1.0;
        if (qualityScore.overall().doubleValue() > 80) qualityMultiplier = 1.2;
        else if (qualityScore.overall().doubleValue() < 40) qualityMultiplier = 0.8;
        
        minRate *= qualityMultiplier;
        maxRate *= qualityMultiplier;
        
        // === Confidence ===
        // Based on data completeness
        double confidence = 50.0;
        if (metric.get().getDataSource().equals("META_API")) confidence += 30;
        if (qualityScore.overall().doubleValue() > 0) confidence += 20;
        confidence = Math.min(100, confidence);
        
        Map<String, Object> factors = Map.of(
            "tier", tier,
            "engagementMultiplier", engagementMultiplier,
            "categoryMultiplier", categoryMultiplier,
            "qualityMultiplier", qualityMultiplier
        );
        
        return new RateEstimation(
            BigDecimal.valueOf(Math.round(minRate)),
            BigDecimal.valueOf(Math.round(maxRate)),
            "INR",
            BigDecimal.valueOf(confidence),
            tier,
            factors
        );
    }

    private String determineTier(long followers) {
        if (followers >= 1_000_000) return "MEGA";
        if (followers >= 500_000) return "MACRO";
        if (followers >= 50_000) return "MID";
        if (followers >= 10_000) return "MICRO";
        return "NANO";
    }
}
```

---

## 5. UTM & Coupon Tracking

Create package: `com.influora.service.tracking/`

### 5.1 CampaignLinkService.java

```java
package com.influora.service.tracking;

import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Generates UTM-tagged tracking links for each creator in a campaign.
 */
@Service
public class CampaignLinkService {

    private final UtmCampaignRepository utmRepo;

    /**
     * Generate a unique tracking URL for a creator's campaign participation.
     */
    @Transactional
    public UtmCampaign createTrackingLink(
            String campaignId,
            String collaborationId,
            String creatorProfileId,
            String baseUrl,
            String platform) {
        
        // Fetch campaign and creator for slug generation
        Campaign campaign = campaignRepo.findById(campaignId)
            .orElseThrow(() -> new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        CreatorProfile creator = creatorRepo.findById(creatorProfileId)
            .orElseThrow(() -> new ApiException("CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
        
        // Generate UTM parameters
        String utmSource = platform.toLowerCase(); // instagram, youtube, etc.
        String utmMedium = "influencer";
        String utmCampaign = SlugUtils.slugify(campaign.getName()); // e.g., "summer-sale-2026"
        String utmContent = SlugUtils.slugify(creator.getDisplayName()); // e.g., "priya-sharma"
        
        // Build full URL
        String separator = baseUrl.contains("?") ? "&" : "?";
        String fullUrl = baseUrl + separator +
            "utm_source=" + encode(utmSource) +
            "&utm_medium=" + encode(utmMedium) +
            "&utm_campaign=" + encode(utmCampaign) +
            "&utm_content=" + encode(utmContent);
        
        UtmCampaign entity = UtmCampaign.builder()
            .id(Ulids.newUlid())
            .campaignId(campaignId)
            .collaborationId(collaborationId)
            .creatorProfileId(creatorProfileId)
            .baseUrl(baseUrl)
            .utmSource(utmSource)
            .utmMedium(utmMedium)
            .utmCampaign(utmCampaign)
            .utmContent(utmContent)
            .fullTrackingUrl(fullUrl)
            .build();
        
        return utmRepo.save(entity);
    }

    /**
     * Record a click on a tracking link.
     * Called by conversion tracking webhook/pixel.
     */
    @Transactional
    public void recordClick(String utmCampaignId, String visitorId) {
        UtmCampaign utm = utmRepo.findById(utmCampaignId)
            .orElseThrow(() -> new ApiException("UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND));
        
        utm.incrementClickCount();
        
        // Track unique visitors (simplified - would use Redis HyperLogLog in production)
        if (visitorId != null) {
            utm.incrementUniqueVisitors(); // Simplified - real implementation needs deduplication
        }
        
        utmRepo.save(utm);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

### 5.2 CouponCodeService.java

```java
package com.influora.service.tracking;

/**
 * Generates and manages AI-generated unique coupon codes.
 * Format: CREATOR_DISCOUNT (e.g., "PRIYA15", "ARJUN20OFF")
 */
@Service
public class CouponCodeService {

    private final CouponCodeRepository couponRepo;
    private final InfluoraAiClient aiClient;

    /**
     * Generate a unique coupon code for a creator.
     */
    @Transactional
    public CouponCode createCouponCode(
            String campaignId,
            String collaborationId,
            String creatorProfileId,
            CouponCodeType type,
            BigDecimal discountValue,
            Integer maxUses,
            Instant validUntil) {
        
        CreatorProfile creator = creatorRepo.findById(creatorProfileId)
            .orElseThrow(() -> new ApiException("CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
        
        // Generate code using AI for uniqueness and brand-fit
        String code = generateUniqueCode(creator.getDisplayName(), discountValue);
        
        CouponCode entity = CouponCode.builder()
            .id(Ulids.newUlid())
            .campaignId(campaignId)
            .collaborationId(collaborationId)
            .creatorProfileId(creatorProfileId)
            .code(code)
            .codeType(type.name())
            .discountValue(discountValue)
            .maxUses(maxUses)
            .validUntil(validUntil)
            .build();
        
        return couponRepo.save(entity);
    }

    private String generateUniqueCode(String creatorName, BigDecimal discount) {
        // Extract first name, uppercase
        String firstName = creatorName.split(" ")[0].toUpperCase();
        firstName = firstName.replaceAll("[^A-Z]", ""); // Remove non-alpha
        if (firstName.length() > 8) firstName = firstName.substring(0, 8);
        
        // Add discount value
        String discountPart = discount.stripTrailingZeros().toPlainString();
        
        String baseCode = firstName + discountPart;
        
        // Check uniqueness, add suffix if needed
        int attempts = 0;
        String finalCode = baseCode;
        while (couponRepo.existsByCode(finalCode) && attempts < 10) {
            finalCode = baseCode + generateRandomSuffix();
            attempts++;
        }
        
        if (couponRepo.existsByCode(finalCode)) {
            throw new ApiException("CODE_GENERATION_FAILED", 
                "Could not generate unique code after 10 attempts", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        return finalCode;
    }

    private String generateRandomSuffix() {
        return String.valueOf((char) ('A' + new Random().nextInt(26)));
    }

    /**
     * Validate a coupon code for use.
     */
    @Transactional(readOnly = true)
    public CouponCode validateCode(String code) {
        CouponCode coupon = couponRepo.findByCode(code.toUpperCase())
            .orElseThrow(() -> new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND));
        
        if (!coupon.isActive()) {
            throw new ApiException("CODE_INACTIVE", "Coupon code is no longer active", HttpStatus.BAD_REQUEST);
        }
        
        if (coupon.getValidUntil() != null && coupon.getValidUntil().isBefore(Instant.now())) {
            throw new ApiException("CODE_EXPIRED", "Coupon code has expired", HttpStatus.BAD_REQUEST);
        }
        
        if (coupon.getMaxUses() != null && coupon.getCurrentUses() >= coupon.getMaxUses()) {
            throw new ApiException("CODE_LIMIT_REACHED", "Coupon code usage limit reached", HttpStatus.BAD_REQUEST);
        }
        
        return coupon;
    }
}
```

### 5.3 ConversionTrackingService.java

```java
package com.influora.service.tracking;

/**
 * Tracks the full funnel: click -> conversion -> sale
 */
@Service
public class ConversionTrackingService {

    /**
     * Record a conversion (purchase) attributed to a tracking link.
     */
    @Transactional
    public void recordConversion(
            String utmCampaignId,
            String orderId,
            BigDecimal orderAmount) {
        
        UtmCampaign utm = utmRepo.findById(utmCampaignId)
            .orElseThrow(() -> new ApiException("UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND));
        
        utm.incrementConversionCount();
        utm.addRevenue(orderAmount);
        utmRepo.save(utm);
        
        // Update campaign totals
        Campaign campaign = campaignRepo.findById(utm.getCampaignId()).orElseThrow();
        campaign.addAttribution(orderAmount);
        campaignRepo.save(campaign);
        
        // Audit log
        auditLog.recordMoneyEvent(
            null, "CONVERSION_TRACKED", orderAmount, null, null, 
            "conv:" + utm.getCampaignId() + ":" + orderId,
            Map.of(
                "utmCampaignId", utmCampaignId,
                "creatorId", utm.getCreatorProfileId(),
                "orderId", orderId
            )
        );
    }
}
```

### 5.4 RedemptionService.java

```java
package com.influora.service.tracking;

/**
 * Handles coupon redemption with idempotency.
 * [SEC: Kabir] - All redemption endpoints MUST use idempotency keys.
 */
@Service
public class RedemptionService {

    private final CouponRedemptionRepository redemptionRepo;
    private final CouponCodeRepository couponRepo;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLog;

    /**
     * Redeem a coupon code for an order.
     * Idempotent: same idempotency_key returns same result.
     */
    @Transactional
    public CouponRedemption redeem(
            String code,
            String orderId,
            BigDecimal orderAmount,
            String customerId,
            String idempotencyKey) {
        
        // [SEC] Idempotency check
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException("IDEMPOTENCY_KEY_REQUIRED",
                "idempotency_key is required for coupon redemption", HttpStatus.BAD_REQUEST);
        }
        
        // Check for existing redemption with this key
        Optional<CouponRedemption> existing = redemptionRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get(); // Return existing, don't double-count
        }
        
        // Validate coupon
        CouponCode coupon = couponCodeService.validateCode(code);
        
        // Check per-user limit if applicable
        if (coupon.getMaxUsesPerUser() != null && customerId != null) {
            long userUses = redemptionRepo.countByCustomerIdAndCouponId(customerId, coupon.getId());
            if (userUses >= coupon.getMaxUsesPerUser()) {
                throw new ApiException("USER_LIMIT_REACHED",
                    "You have already used this coupon the maximum number of times", HttpStatus.BAD_REQUEST);
            }
        }
        
        // Calculate discount
        BigDecimal discountApplied = calculateDiscount(coupon, orderAmount);
        
        // Create redemption record
        CouponRedemption redemption = CouponRedemption.builder()
            .id(Ulids.newUlid())
            .couponId(coupon.getId())
            .orderId(orderId)
            .orderAmount(orderAmount)
            .discountApplied(discountApplied)
            .customerId(customerId)
            .idempotencyKey(idempotencyKey)
            .build();
        
        redemptionRepo.save(redemption);
        
        // Update coupon usage stats
        coupon.incrementUsage();
        coupon.addRevenue(orderAmount);
        couponRepo.save(coupon);
        
        // Audit
        auditLog.recordMoneyEvent(
            null, "COUPON_REDEEMED", discountApplied, null, null,
            idempotencyKey,
            Map.of(
                "couponId", coupon.getId(),
                "code", code,
                "orderId", orderId,
                "creatorId", coupon.getCreatorProfileId()
            )
        );
        
        return redemption;
    }

    private BigDecimal calculateDiscount(CouponCode coupon, BigDecimal orderAmount) {
        return switch (coupon.getCodeType()) {
            case "PERCENTAGE" -> orderAmount.multiply(coupon.getDiscountValue())
                                           .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
            case "FIXED_AMOUNT" -> coupon.getDiscountValue().min(orderAmount);
            case "FREE_SHIPPING" -> BigDecimal.ZERO; // Handled separately
            default -> BigDecimal.ZERO;
        };
    }
}
```

---

## 6. New Controllers & DTOs

### 6.1 Controllers to Create

| Controller | Package | Endpoints |
|------------|---------|-----------|
| `MetaOAuthController.java` | `com.influora.web` | `/api/v1/auth/meta/authorize`, `/api/v1/auth/meta/callback` |
| `MetaAnalyticsController.java` | `com.influora.web` | `/api/v1/analytics/instagram/{creatorId}`, `/api/v1/analytics/demographics/{creatorId}` |
| `CreatorScoresController.java` | `com.influora.web` | `/api/v1/creators/{id}/scores`, `/api/v1/creators/{id}/fake-check` |
| `CampaignTrackingController.java` | `com.influora.web` | `/api/v1/campaigns/{id}/tracking-links`, `/api/v1/campaigns/{id}/coupons` |
| `ConversionWebhookController.java` | `com.influora.web` | `/api/v1/webhooks/conversion`, `/api/v1/webhooks/redemption` |

### 6.2 DTOs to Create

```
com.influora.web.dto/
├── meta/
│   ├── MetaOAuthCallbackRequest.java
│   ├── MetaOAuthStatusResponse.java
│   ├── CreatorMetricsResponse.java
│   ├── MediaMetricsResponse.java
│   └── AudienceDemographicsDto.java
├── scoring/
│   ├── CreatorScoresResponse.java
│   ├── FakeFollowerAnalysisResponse.java
│   ├── QualityScoreResponse.java
│   └── RateEstimationResponse.java
├── tracking/
│   ├── CreateTrackingLinkRequest.java
│   ├── TrackingLinkResponse.java
│   ├── CreateCouponRequest.java
│   ├── CouponCodeResponse.java
│   ├── RedeemCouponRequest.java
│   └── RedemptionResponse.java
└── webhook/
    ├── ConversionEventRequest.java
    └── ConversionEventResponse.java
```

### 6.3 MetaOAuthController.java (Example)

```java
@RestController
@RequestMapping("/api/v1/auth/meta")
public class MetaOAuthController {

    private final MetaOAuthService oauthService;
    private final MetaTokenStorage tokenStorage;

    /**
     * Initiates Meta OAuth flow for a creator.
     * Returns URL to redirect user to Facebook/Instagram login.
     */
    @GetMapping("/authorize")
    public ResponseEntity<Map<String, String>> authorize(
            @AuthPrincipal AuthPrincipal principal) {
        
        // Only creators can connect Meta accounts
        if (principal.getUserType() != UserType.CREATOR) {
            throw new ApiException("UNAUTHORIZED", 
                "Only creators can connect Meta accounts", HttpStatus.FORBIDDEN);
        }
        
        String state = generateSecureState(principal.getUserId());
        String authUrl = oauthService.buildAuthorizationUrl(state);
        
        return ResponseEntity.ok(Map.of("authorizationUrl", authUrl));
    }

    /**
     * OAuth callback handler.
     * Exchanges code for tokens and stores encrypted.
     */
    @GetMapping("/callback")
    public ResponseEntity<MetaOAuthStatusResponse> callback(
            @RequestParam String code,
            @RequestParam String state) {
        
        String userId = validateAndExtractUserId(state);
        
        // Exchange code for tokens
        MetaTokenResponse shortLived = oauthService.exchangeCodeForToken(code);
        MetaTokenResponse longLived = oauthService.exchangeForLongLivedToken(shortLived.accessToken());
        
        // Get creator profile ID
        String creatorProfileId = creatorRepo.findByUserId(userId)
            .orElseThrow(() -> new ApiException("CREATOR_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND))
            .getId();
        
        // Store encrypted token
        Instant expiresAt = Instant.now().plusSeconds(longLived.expiresIn());
        tokenStorage.storeToken(
            creatorProfileId,
            null, // workspace derived from creator
            longLived.accessToken(),
            expiresAt,
            MetaOAuthService.REQUIRED_SCOPES
        );
        
        return ResponseEntity.ok(new MetaOAuthStatusResponse(
            true,
            "Meta account connected successfully",
            expiresAt
        ));
    }
}
```

---

## 7. Security Requirements (from Kabir)

### 7.1 OAuth Token Encryption

- **Algorithm**: AES-256-GCM (authenticated encryption)
- **Key storage**: Environment variable `INFLUORA_META_TOKEN_ENCRYPTION_KEY` (base64-encoded 32 bytes)
- **IV**: Random 12 bytes per encryption, prepended to ciphertext
- **NEVER** store plaintext tokens in any log, database, or response

### 7.2 Idempotency Keys

All these endpoints MUST require `Idempotency-Key` header:
- `POST /api/v1/webhooks/conversion`
- `POST /api/v1/webhooks/redemption`
- `POST /api/v1/campaigns/{id}/coupons` (creation)
- `POST /api/v1/campaigns/{id}/tracking-links` (creation)

Use existing `IdempotencyService` pattern from payment flows.

### 7.3 Rate Limiting on Public Endpoints

Apply rate limiting (use existing `AuthRateLimitFilter` pattern):
- `/api/v1/creators/discover` — 100 req/min per IP
- `/api/v1/creators/{id}/public` — 60 req/min per IP
- Webhook endpoints — 1000 req/min per API key

### 7.4 Audit Logging

Log ALL analytics access to `audit_log`:
```java
auditLog.recordToolCall(
    workspaceId, 
    "ANALYTICS_ACCESS", 
    "READ",
    OUTCOME_ALLOWED, 
    null, 
    null, 
    null,
    Map.of("creatorId", creatorId, "endpoint", "instagram_insights")
);
```

### 7.5 Data Classification

| Data Type | Classification | Handling |
|-----------|----------------|----------|
| OAuth tokens | CRITICAL | AES-256-GCM encrypted, audit logged |
| Audience demographics | SENSITIVE | No PII in logs, aggregate only |
| Engagement metrics | BUSINESS | Standard access controls |
| Coupon codes | BUSINESS | Unique constraint enforced |

---

## 8. Testing Requirements (from Kavya)

### 8.1 Unit Tests

| Service | Test Class | Coverage Target |
|---------|------------|-----------------|
| `FakeFollowerDetectionService` | `FakeFollowerDetectionServiceTest` | 90% |
| `QualityScoreService` | `QualityScoreServiceTest` | 85% |
| `BrandSafetyScoreService` | `BrandSafetyScoreServiceTest` | 85% |
| `MetaTokenStorage` | `MetaTokenStorageTest` | 95% (crypto paths) |
| `CouponCodeService` | `CouponCodeServiceTest` | 90% |
| `RedemptionService` | `RedemptionServiceTest` | 95% (idempotency) |

### 8.2 Integration Tests

| Test Class | Dependencies | What to Mock |
|------------|--------------|--------------|
| `MetaGraphApiClientIT` | WireMock | Meta API responses |
| `MetricsPollingJobIT` | TestContainers + TimescaleDB | Meta API client |
| `ConversionWebhookIT` | Spring MockMvc | None (full flow) |
| `OAuthFlowIT` | Spring MockMvc + WireMock | Meta OAuth endpoints |

### 8.3 Load Tests (JMeter/Gatling)

| Scenario | Target | Acceptance |
|----------|--------|------------|
| Metrics polling (100 creators) | < 5 min | No rate limit hits |
| Concurrent coupon redemptions | 100 req/sec | Zero duplicates |
| Analytics dashboard queries | 50 req/sec | p99 < 500ms |

### 8.4 Coverage Requirements

- **Overall**: Minimum 80% line coverage before merge
- **Crypto code**: 95% (all encryption/decryption paths)
- **Idempotency code**: 95% (all deduplication paths)

---

## 9. Priority Order (8-Week Sprint Plan)

### Week 1-2: Meta OAuth + API Client + Token Storage

**Deliverables:**
- [ ] `MetaApiProperties` and configuration
- [ ] `MetaOAuthService` (OAuth flow)
- [ ] `MetaTokenStorage` (AES-256-GCM encryption)
- [ ] `MetaGraphApiClient` (base HTTP client)
- [ ] `InstagramInsightsClient` (profile + media endpoints)
- [ ] `MetaOAuthController` (authorize + callback)
- [ ] `MetaRateLimitTracker`
- [ ] Unit tests for all crypto paths

**Checkpoint:** Creator can connect Instagram, token stored encrypted.

### Week 3-4: Database Migrations + Entities + Polling Jobs

**Deliverables:**
- [ ] TimescaleDB instance setup (coordinate with DevOps)
- [ ] `V20__timescale_hypertables.sql`
- [ ] `V21__audience_demographics.sql`
- [ ] `V22__creator_scores.sql`
- [ ] Entity classes for all new tables
- [ ] `MetricsPollingJob` (every 6 hours)
- [ ] `AudienceDemographicsJob` (weekly)
- [ ] `StaleTokenCleanupJob` + `RateLimitResetJob`
- [ ] Integration tests with TestContainers

**Checkpoint:** Metrics flowing from Meta API into TimescaleDB.

### Week 5-6: Scoring Algorithms

**Deliverables:**
- [ ] `FakeFollowerDetectionService`
- [ ] `QualityScoreService`
- [ ] `BrandSafetyScoreService` (integrate with influora-ai)
- [ ] `RateEstimationService`
- [ ] `ScoreCalculationJob` (daily)
- [ ] `CreatorScoresController`
- [ ] Unit tests for all scoring algorithms
- [ ] Dashboard integration (coordinate with Ananya)

**Checkpoint:** Creators have computed scores visible in discovery.

### Week 7-8: UTM/Coupon System + Conversion Tracking

**Deliverables:**
- [ ] `V23__utm_campaigns.sql`
- [ ] `V24__coupon_codes.sql`
- [ ] `CampaignLinkService`
- [ ] `CouponCodeService`
- [ ] `ConversionTrackingService`
- [ ] `RedemptionService` (with idempotency)
- [ ] `CampaignTrackingController`
- [ ] `ConversionWebhookController`
- [ ] Load tests for concurrent redemptions
- [ ] End-to-end flow tests

**Checkpoint:** Full attribution funnel working: link -> click -> conversion -> revenue attribution.

---

## Appendix A: Configuration Properties

Add to `application.yml`:

```yaml
influora:
  meta:
    app-id: ${META_APP_ID}
    app-secret: ${META_APP_SECRET}
    redirect-uri: ${META_REDIRECT_URI:https://app.influora.co/auth/meta/callback}
    graph-api-version: v25.0
    token-refresh-days-before-expiry: 7
    rate-limit-alert-threshold: 80
    rate-limit-throttle-threshold: 90
    token-encryption-key: ${META_TOKEN_ENCRYPTION_KEY}  # Base64-encoded 32 bytes

  timescale:
    host: ${TIMESCALE_HOST:localhost}
    port: ${TIMESCALE_PORT:5432}
    database: ${TIMESCALE_DB:influora_metrics}
    username: ${TIMESCALE_USER}
    password: ${TIMESCALE_PASSWORD}
```

---

## Appendix B: Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `META_AUTH_FAILED` | 401 | OAuth flow failed |
| `META_TOKEN_EXPIRED` | 401 | Token needs refresh |
| `META_RATE_LIMITED` | 429 | API rate limit hit |
| `META_PERMISSION_DENIED` | 403 | Missing required scope |
| `COUPON_NOT_FOUND` | 404 | Invalid coupon code |
| `COUPON_EXPIRED` | 400 | Coupon past valid_until |
| `COUPON_LIMIT_REACHED` | 400 | Max uses exceeded |
| `IDEMPOTENCY_CONFLICT` | 409 | Duplicate request with same key |
| `CREATOR_NOT_CONNECTED` | 400 | Creator hasn't connected Meta |

---

## Sign-off

| Role | Name | Date | Signature |
|------|------|------|-----------|
| CTO | Priya | 2026-07-06 | Approved |
| Security Lead | Kabir | Pending | - |
| QA Lead | Kavya | Pending | - |
| Backend Dev | Vikram | Pending | - |

---

**Questions? Ping Priya in #tech-backend or raise in daily standup.**

---

# ADDENDUM: New Requirements (2026-07-06 Update)

> Added per Swapnil's review. These sections extend the original spec.

---

## 10. Unique Coupons Per Creator (CRITICAL FIX)

### Problem
Original spec allowed same coupon code for multiple creators → broken attribution.

### Solution
Every creator gets a UNIQUE coupon code per campaign.

### Schema Change (V24 update)

```sql
-- Coupons are per-creator, not per-campaign
CREATE TABLE coupon_codes (
    id VARCHAR(26) PRIMARY KEY,
    workspace_id VARCHAR(26) NOT NULL,
    campaign_id VARCHAR(26) NOT NULL REFERENCES campaigns(id),
    creator_id VARCHAR(26) NOT NULL REFERENCES creator_profiles(id),  -- KEY CHANGE
    code VARCHAR(50) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,  -- 'percentage' or 'fixed'
    discount_value DECIMAL(10,2) NOT NULL,
    usage_limit INTEGER,
    usage_count INTEGER DEFAULT 0,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(workspace_id, code),  -- Code unique per workspace
    UNIQUE(campaign_id, creator_id)  -- One code per creator per campaign
);
```

### Auto-Generation Service

```java
@Service
public class CouponCodeService {
    
    public String generateCreatorCoupon(Campaign campaign, CreatorProfile creator) {
        String baseCode = campaign.getCouponPrefix();  // "SUMMER25"
        String creatorSlug = creator.getSlug().toUpperCase();  // "RIYA"
        
        // Pattern: CREATOR_CAMPAIGN
        String code = creatorSlug + "_" + baseCode;  // "RIYA_SUMMER25"
        
        // Ensure uniqueness
        if (couponRepo.existsByWorkspaceAndCode(campaign.getWorkspaceId(), code)) {
            code = code + "_" + RandomStringUtils.randomAlphanumeric(4);
        }
        
        return code;
    }
    
    @Transactional
    public CouponCode addCreatorToCampaign(String campaignId, String creatorId) {
        var campaign = campaignRepo.findById(campaignId).orElseThrow();
        var creator = creatorRepo.findById(creatorId).orElseThrow();
        
        String code = generateCreatorCoupon(campaign, creator);
        
        var coupon = CouponCode.builder()
            .id(Ulids.generate())
            .workspaceId(campaign.getWorkspaceId())
            .campaignId(campaignId)
            .creatorId(creatorId)
            .code(code)
            .discountType(campaign.getDiscountType())
            .discountValue(campaign.getDiscountValue())
            .expiresAt(campaign.getEndsAt())
            .build();
            
        return couponRepo.save(coupon);
    }
}
```

---

## 11. Free Shopify Integration (No $99 Fee)

### Approach
Use Shopify Custom App (OAuth) — no App Store listing required.

### Package Structure

```
src/main/java/com/influora/integration/shopify/
├── config/
│   └── ShopifyProperties.java
├── client/
│   └── ShopifyClient.java
├── oauth/
│   └── ShopifyOAuthService.java
├── webhook/
│   └── ShopifyWebhookController.java
└── service/
    └── ShopifyIntegrationService.java
```

### ShopifyOAuthService.java

```java
@Service
public class ShopifyOAuthService {
    
    private final ShopifyProperties props;
    private final IntegrationRepository integrationRepo;
    
    public String buildAuthUrl(String shop) {
        return String.format(
            "https://%s/admin/oauth/authorize?client_id=%s&scope=%s&redirect_uri=%s",
            shop,
            props.clientId(),
            "read_orders,read_products",
            URLEncoder.encode(props.redirectUri(), UTF_8)
        );
    }
    
    @Transactional
    public void handleCallback(String shop, String code, String workspaceId) {
        // Exchange code for access token
        var token = exchangeToken(shop, code);
        
        // Store encrypted token
        var integration = WorkspaceIntegration.builder()
            .id(Ulids.generate())
            .workspaceId(workspaceId)
            .platform("shopify")
            .shopDomain(shop)
            .accessTokenEncrypted(encrypt(token.accessToken()))
            .build();
        integrationRepo.save(integration);
        
        // Auto-register webhook for order payments
        registerWebhook(shop, token.accessToken());
    }
    
    private void registerWebhook(String shop, String accessToken) {
        // POST https://{shop}/admin/api/2024-01/webhooks.json
        // Topic: orders/paid
        // Address: https://api.influora.com/webhooks/shopify/redemption
    }
}
```

### ShopifyConnectController.java

```java
@RestController
@RequestMapping("/connect/shopify")
public class ShopifyConnectController {
    
    @GetMapping("/install")
    public RedirectView startOAuth(
        @RequestParam String shop,
        @AuthenticationPrincipal AuthPrincipal principal
    ) {
        String authUrl = shopifyOAuthService.buildAuthUrl(shop);
        // Store workspace_id in session/state for callback
        return new RedirectView(authUrl);
    }
    
    @GetMapping("/callback")
    public RedirectView handleCallback(
        @RequestParam String shop,
        @RequestParam String code,
        @RequestParam String state  // Contains workspace_id
    ) {
        shopifyOAuthService.handleCallback(shop, code, state);
        return new RedirectView("/dashboard/integrations?connected=shopify");
    }
}
```

### Manual Webhook Option (No OAuth)

For brands who prefer manual setup:

```java
@RestController
@RequestMapping("/webhooks/shopify")
public class ShopifyWebhookController {
    
    @PostMapping("/redemption")
    public ResponseEntity<?> handleOrderPaid(
        @RequestHeader("X-Shopify-Hmac-SHA256") String hmac,
        @RequestHeader("X-Shopify-Shop-Domain") String shop,
        @RequestBody String rawBody
    ) {
        // 1. Verify HMAC signature
        if (!verifyHmac(rawBody, hmac, getSecretForShop(shop))) {
            return ResponseEntity.status(401).build();
        }
        
        // 2. Parse order
        var order = objectMapper.readValue(rawBody, ShopifyOrder.class);
        
        // 3. Extract coupon codes used
        for (var discount : order.getDiscountCodes()) {
            var coupon = couponRepo.findByCode(discount.getCode());
            if (coupon.isPresent()) {
                // 4. Record redemption with idempotency
                redemptionService.recordRedemption(
                    coupon.get(),
                    order.getId(),  // idempotency key
                    order.getTotalPrice(),
                    discount.getAmount()
                );
            }
        }
        
        return ResponseEntity.ok().build();
    }
}
```

---

## 12. Affiliate / Revenue Share Campaigns

### Schema Changes (V25)

```sql
-- Campaign payment models
ALTER TABLE campaigns ADD COLUMN payment_model VARCHAR(20) 
    CHECK (payment_model IN ('flat_fee', 'gifted', 'affiliate', 'hybrid'));
ALTER TABLE campaigns ADD COLUMN commission_percent DECIMAL(5,2);
ALTER TABLE campaigns ADD COLUMN commission_cap DECIMAL(12,2);

-- Affiliate earnings tracking
CREATE TABLE affiliate_earnings (
    id VARCHAR(26) PRIMARY KEY,
    workspace_id VARCHAR(26) NOT NULL,
    campaign_id VARCHAR(26) NOT NULL REFERENCES campaigns(id),
    creator_id VARCHAR(26) NOT NULL REFERENCES creator_profiles(id),
    coupon_redemption_id VARCHAR(26) REFERENCES coupon_redemptions(id),
    order_id VARCHAR(100) NOT NULL,
    order_total DECIMAL(12,2) NOT NULL,
    commission_percent DECIMAL(5,2) NOT NULL,
    commission_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',  -- pending, approved, paid, disputed
    settlement_id VARCHAR(26),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(workspace_id, order_id)  -- Idempotency
);

-- Monthly settlement batches
CREATE TABLE affiliate_settlements (
    id VARCHAR(26) PRIMARY KEY,
    workspace_id VARCHAR(26) NOT NULL,
    creator_id VARCHAR(26) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_sales INTEGER NOT NULL,
    total_revenue DECIMAL(12,2) NOT NULL,
    total_commission DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',  -- pending, processing, paid
    escrow_hold_id VARCHAR(26),
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### AffiliateEarningsService.java

```java
@Service
public class AffiliateEarningsService {
    
    @Transactional
    public void recordAffiliateEarning(CouponRedemption redemption) {
        var coupon = redemption.getCoupon();
        var campaign = coupon.getCampaign();
        
        if (campaign.getPaymentModel() != PaymentModel.AFFILIATE 
            && campaign.getPaymentModel() != PaymentModel.HYBRID) {
            return;  // Not an affiliate campaign
        }
        
        BigDecimal commission = redemption.getOrderTotal()
            .multiply(campaign.getCommissionPercent())
            .divide(BigDecimal.valueOf(100));
        
        // Apply commission cap if set
        if (campaign.getCommissionCap() != null 
            && commission.compareTo(campaign.getCommissionCap()) > 0) {
            commission = campaign.getCommissionCap();
        }
        
        var earning = AffiliateEarning.builder()
            .id(Ulids.generate())
            .workspaceId(campaign.getWorkspaceId())
            .campaignId(campaign.getId())
            .creatorId(coupon.getCreatorId())
            .couponRedemptionId(redemption.getId())
            .orderId(redemption.getOrderId())
            .orderTotal(redemption.getOrderTotal())
            .commissionPercent(campaign.getCommissionPercent())
            .commissionAmount(commission)
            .status(AffiliateEarningStatus.PENDING)
            .build();
            
        affiliateEarningRepo.save(earning);
    }
}
```

### AffiliateSettlementJob.java (Monthly)

```java
@Component
public class AffiliateSettlementJob {
    
    @Scheduled(cron = "0 0 0 1 * *")  // 1st of every month
    public void processMonthlySettlements() {
        var lastMonth = YearMonth.now().minusMonths(1);
        var startDate = lastMonth.atDay(1);
        var endDate = lastMonth.atEndOfMonth();
        
        // Group pending earnings by creator
        var earningsByCreator = affiliateEarningRepo
            .findPendingByPeriod(startDate, endDate)
            .stream()
            .collect(Collectors.groupingBy(AffiliateEarning::getCreatorId));
        
        for (var entry : earningsByCreator.entrySet()) {
            var creatorId = entry.getKey();
            var earnings = entry.getValue();
            
            var totalCommission = earnings.stream()
                .map(AffiliateEarning::getCommissionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // Create settlement
            var settlement = AffiliateSettlement.builder()
                .id(Ulids.generate())
                .workspaceId(earnings.get(0).getWorkspaceId())
                .creatorId(creatorId)
                .periodStart(startDate)
                .periodEnd(endDate)
                .totalSales(earnings.size())
                .totalRevenue(earnings.stream()
                    .map(AffiliateEarning::getOrderTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                .totalCommission(totalCommission)
                .status(SettlementStatus.PENDING)
                .build();
            
            settlementRepo.save(settlement);
            
            // Mark earnings as settled
            earnings.forEach(e -> e.setSettlementId(settlement.getId()));
            affiliateEarningRepo.saveAll(earnings);
            
            // Notify brand to fund escrow
            eventPublisher.publish(new SettlementPendingEvent(settlement));
        }
    }
}
```

---

## 13. Store Integration Health Check

### IntegrationHealthService.java

```java
@Service
public class IntegrationHealthService {
    
    public IntegrationStatus checkWorkspaceIntegration(String workspaceId) {
        // Check last webhook received
        var lastWebhook = webhookLogRepo.findLatestByWorkspace(workspaceId);
        
        // Check pixel pings
        var lastPixelPing = pixelEventRepo.findLatestByWorkspace(workspaceId);
        
        // Check API calls
        var apiCallCount = apiLogRepo.countRecentByWorkspace(workspaceId, Duration.ofDays(7));
        
        boolean connected = lastWebhook != null || lastPixelPing != null || apiCallCount > 0;
        
        return IntegrationStatus.builder()
            .connected(connected)
            .lastActivity(findMostRecent(lastWebhook, lastPixelPing))
            .webhookActive(lastWebhook != null && isRecent(lastWebhook.getCreatedAt()))
            .pixelActive(lastPixelPing != null && isRecent(lastPixelPing.getCreatedAt()))
            .apiActive(apiCallCount > 0)
            .build();
    }
    
    private boolean isRecent(Instant timestamp) {
        return timestamp.isAfter(Instant.now().minus(Duration.ofDays(7)));
    }
}
```

### Block Sale Campaign Without Integration

```java
// In CampaignService.java
@Transactional
public Campaign createCampaign(CreateCampaignRequest req, String workspaceId) {
    
    // Block sale campaigns if store not connected
    if (req.hasCoupons() || req.hasUtmTracking()) {
        var integration = integrationHealthService.checkWorkspaceIntegration(workspaceId);
        
        if (!integration.isConnected()) {
            throw new ApiException(400, "INTEGRATION_REQUIRED",
                "Store integration required for campaigns with coupon/UTM tracking. " +
                "Connect your store in Settings → Integrations.");
        }
    }
    
    // ... create campaign
}
```

---

## 14. AI Integration Tools (influora-ai)

### New Tools for tools/schemas.py

```python
# Add to TOOL_SCHEMAS list

{
    "name": "check_store_integration",
    "description": "Check if brand's store is properly connected for tracking",
    "input_schema": {
        "type": "object",
        "properties": {
            "workspace_id": {"type": "string"}
        },
        "required": ["workspace_id"]
    }
}

{
    "name": "generate_integration_code",
    "description": "Generate code snippet for brand's programming language to integrate with Influora",
    "input_schema": {
        "type": "object",
        "properties": {
            "language": {
                "type": "string",
                "enum": ["php", "python", "node", "ruby", "java", "csharp", "go"]
            },
            "integration_type": {
                "type": "string",
                "enum": ["coupon_redeem", "conversion_track", "utm_capture"]
            }
        },
        "required": ["language", "integration_type"]
    }
}

{
    "name": "generate_coupon_code",
    "description": "Generate a unique, memorable coupon code for a creator",
    "input_schema": {
        "type": "object",
        "properties": {
            "creator_name": {"type": "string"},
            "discount_percent": {"type": "integer"},
            "campaign_theme": {"type": "string"}
        },
        "required": ["creator_name", "discount_percent"]
    }
}
```

### Tool Executor in Spring (MeeraInternalController)

```java
@PostMapping("/internal/meera/check-store-integration")
public IntegrationStatus checkStoreIntegration(
    @RequestBody CheckIntegrationRequest req,
    @RequestHeader("X-Meera-Service-Token") String serviceToken
) {
    verifyServiceToken(serviceToken);
    return integrationHealthService.checkWorkspaceIntegration(req.workspaceId());
}

@PostMapping("/internal/meera/generate-integration-code")
public IntegrationCodeResponse generateIntegrationCode(
    @RequestBody GenerateCodeRequest req,
    @RequestHeader("X-Meera-Service-Token") String serviceToken
) {
    verifyServiceToken(serviceToken);
    return integrationCodeService.generateSnippet(req.language(), req.integrationType());
}
```

---

## 15. WooCommerce Free Integration

Same pattern as Shopify — webhook-based, no plugin store fee.

```java
@RestController
@RequestMapping("/webhooks/woocommerce")
public class WooCommerceWebhookController {
    
    @PostMapping("/redemption")
    public ResponseEntity<?> handleOrderCompleted(
        @RequestHeader("X-WC-Webhook-Signature") String signature,
        @RequestHeader("X-WC-Webhook-Source") String source,
        @RequestBody String rawBody
    ) {
        // 1. Verify signature
        if (!verifyWooCommerceSignature(rawBody, signature, source)) {
            return ResponseEntity.status(401).build();
        }
        
        // 2. Parse order and process same as Shopify
        var order = objectMapper.readValue(rawBody, WooCommerceOrder.class);
        processOrderCoupons(order);
        
        return ResponseEntity.ok().build();
    }
}
```

---

## Updated Sprint Schedule (Weeks 7-8)

| Task | Owner | Deliverable |
|------|-------|-------------|
| Unique coupon per creator schema | Vikram | V24 migration update |
| CouponCodeService auto-generation | Vikram | Service + tests |
| Shopify OAuth integration | Vikram | ShopifyOAuthService |
| Shopify webhook handler | Vikram | ShopifyWebhookController |
| WooCommerce webhook handler | Vikram | WooCommerceWebhookController |
| IntegrationHealthService | Vikram | Service + campaign validation |
| Affiliate campaign schema | Vikram | V25 migration |
| AffiliateEarningsService | Vikram | Service + monthly job |
| AI tools: check_store_integration | Vikram | MeeraInternalController |
| AI tools: generate_integration_code | Vikram | IntegrationCodeService |

---

**End of Addendum**
