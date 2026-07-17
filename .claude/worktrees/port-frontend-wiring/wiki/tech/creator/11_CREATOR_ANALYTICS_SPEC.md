# Creator Analytics Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Analytics Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CREATOR ANALYTICS DASHBOARD                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │                    PERFORMANCE OVERVIEW                              │     │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐           │     │
│  │  │ 125K     │  │ 4.2%     │  │ Rs. 7.5L │  │ 12       │           │     │
│  │  │ Followers│  │ Eng Rate │  │ Earned   │  │ Campaigns│           │     │
│  │  │ +8.5%    │  │ +0.3%    │  │ +45%     │  │ +3       │           │     │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘           │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  ┌────────────────────────────┐  ┌────────────────────────────────────┐    │
│  │   FOLLOWER GROWTH          │  │   EARNINGS TREND                   │    │
│  │   ┌───────────────────┐    │  │   ┌───────────────────────────┐   │    │
│  │   │    📈 /\  /\      │    │  │   │    📈    /\               │   │    │
│  │   │      /  \/  \     │    │  │   │        /  \     /\       │   │    │
│  │   │     /        \_/\ │    │  │   │       /    \   /  \      │   │    │
│  │   │    /             \│    │  │   │      /      \_/    \     │   │    │
│  │   └───────────────────┘    │  │   └───────────────────────────┘   │    │
│  │   Jan Feb Mar Apr May Jun  │  │   Jan Feb Mar Apr May Jun         │    │
│  └────────────────────────────┘  └────────────────────────────────────┘    │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │                    AI GROWTH INSIGHTS                                │     │
│  │  💡 "Your engagement peaks at 7-9 PM. Post more reels then."       │     │
│  │  💡 "Fitness content gets 2.5x more saves. Focus on tutorials."     │     │
│  │  💡 "You're 15% below market rate for your tier. Consider raising." │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Analytics Categories

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ANALYTICS STRUCTURE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. AUDIENCE ANALYTICS                                                       │
│     ├── Follower growth                                                     │
│     ├── Engagement trends                                                   │
│     ├── Audience demographics                                               │
│     └── Best posting times                                                  │
│                                                                              │
│  2. EARNINGS ANALYTICS                                                       │
│     ├── Revenue trends                                                      │
│     ├── Campaign breakdown                                                  │
│     ├── Rate comparison                                                     │
│     └── Affiliate performance                                               │
│                                                                              │
│  3. CAMPAIGN ANALYTICS                                                       │
│     ├── Campaign performance                                                │
│     ├── Brand relationships                                                 │
│     ├── Content performance                                                 │
│     └── Deliverable metrics                                                 │
│                                                                              │
│  4. AI INSIGHTS (Future)                                                    │
│     ├── Growth recommendations                                              │
│     ├── Rate optimization                                                   │
│     ├── Content strategy                                                    │
│     └── Brand matching                                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 CreatorAnalytics Entity (Aggregated)

```java
@Entity
@Table(name = "creator_analytics")
public class CreatorAnalytics {
    
    @Id
    private String id;
    
    @OneToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    // Current stats (updated daily)
    private Integer totalFollowers;
    private Double avgEngagementRate;
    private Double avgLikesPerPost;
    private Double avgCommentsPerPost;
    
    // Growth (calculated)
    private Integer followersGrowth7d;
    private Integer followersGrowth30d;
    private Integer followersGrowth90d;
    private Double growthRate7d;
    private Double growthRate30d;
    private Double growthRate90d;
    
    // Engagement trends
    private Double engagementRate7d;
    private Double engagementRate30d;
    private Double engagementChange7d;
    private Double engagementChange30d;
    
    // Campaign stats
    private Integer totalCampaignsCompleted;
    private Integer totalCampaignsActive;
    private Double avgCampaignRating;
    private Integer totalBrandsWorkedWith;
    
    // Earnings — ALL values are NET of the platform fee (see 10_CREATOR_PAYMENTS_SPEC §1A).
    // Creators see what they actually keep. Optionally expose gross + fee for transparency.
    private Long totalLifetimeEarnings;   // net
    private Long earnings30d;              // net
    private Long earnings90d;              // net
    private Long earningsYtd;              // net
    private Long totalPlatformFeePaid;    // lifetime fee paid to Influora (transparency)
    
    // Rates
    private BigDecimal avgRateCharged;
    private BigDecimal marketRateForTier;
    private Double rateComparisonPct;  // +15% or -10% vs market
    
    // Activity
    private Integer totalPostsThisMonth;
    private Integer totalReelsThisMonth;
    private Integer totalStoriesThisMonth;
    
    // Scores
    private Double profileCompleteness;
    private Double responseRate;
    private Double onTimeDeliveryRate;
    
    // Timestamps
    private Instant calculatedAt;
    private Instant lastActivityAt;
}
```

### 3.2 AnalyticsSnapshot Entity (Historical)

```java
@Entity
@Table(name = "analytics_snapshots")
public class AnalyticsSnapshot {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    // Snapshot date
    private LocalDate snapshotDate;
    
    @Enumerated(EnumType.STRING)
    private SnapshotPeriod period;  // DAILY, WEEKLY, MONTHLY
    
    // Followers
    private Integer followers;
    private Integer followersChange;
    
    // Engagement
    private Double engagementRate;
    private Integer totalLikes;
    private Integer totalComments;
    private Integer totalShares;
    
    // Content
    private Integer postsPublished;
    private Integer reelsPublished;
    private Integer storiesPublished;
    
    // Earnings
    private Long earnings;
    private Integer campaignsCompleted;
    
    // Performance
    private Double avgPostReach;
    private Double avgReelViews;
    
    private Instant createdAt;
}

public enum SnapshotPeriod {
    DAILY,
    WEEKLY,
    MONTHLY
}
```

### 3.3 CampaignPerformance Entity

```java
@Entity
@Table(name = "campaign_performance")
public class CampaignPerformance {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;
    
    @ManyToOne
    @JoinColumn(name = "contract_id")
    private Contract contract;
    
    // Campaign details
    private String brandName;
    private String campaignTitle;
    @Convert(converter = JsonListConverter.class)
    private List<String> categories;
    
    // Contract value
    private Long contractValue;
    private Long earnedAmount;
    
    // Performance
    private Integer totalDeliverables;
    private Integer completedDeliverables;
    
    // Aggregated metrics
    private Long totalReach;
    private Long totalImpressions;
    private Long totalEngagements;
    private Long totalViews;
    private Double avgEngagementRate;
    
    // Individual deliverable performance
    @Convert(converter = JsonListConverter.class)
    private List<DeliverablePerformance> deliverableMetrics;
    
    // Rating
    private Integer brandRating;  // 1-5
    private String brandFeedback;
    
    // Timeline
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate completedDate;
    
    // Status
    @Enumerated(EnumType.STRING)
    private CampaignPerformanceStatus status;
    
    private Instant calculatedAt;
}

@Embeddable
public class DeliverablePerformance {
    private String deliverableId;
    private String type;
    private Integer likes;
    private Integer comments;
    private Integer shares;
    private Integer views;
    private Integer reach;
    private Double engagementRate;
}
```

### 3.4 AiInsight Entity

```java
@Entity
@Table(name = "ai_insights")
public class AiInsight {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @Enumerated(EnumType.STRING)
    private InsightType type;  // GROWTH, RATE, CONTENT, ENGAGEMENT, OPPORTUNITY
    
    @Enumerated(EnumType.STRING)
    private InsightPriority priority;  // HIGH, MEDIUM, LOW
    
    private String title;
    private String description;
    
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> data;
    // { "currentRate": 15000, "suggestedRate": 18000, "marketAvg": 17000 }
    
    // Action
    private String actionText;  // "Update your rates"
    private String actionUrl;   // "/creator/settings/rates"
    
    // Status
    private Boolean isDismissed;
    private Boolean isActedUpon;
    private Instant dismissedAt;
    private Instant actedUponAt;
    
    // Validity
    private Instant validFrom;
    private Instant validUntil;
    
    private Instant generatedAt;
}

public enum InsightType {
    GROWTH,         // Follower growth insights
    RATE,           // Rate optimization
    CONTENT,        // Content strategy
    ENGAGEMENT,     // Engagement improvement
    OPPORTUNITY,    // Campaign opportunities
    PERFORMANCE     // Performance improvement
}
```

---

## 4. API Endpoints

### 4.1 Get Analytics Dashboard

```
GET /api/v1/creator/analytics/dashboard
Query Parameters:
  period  - (7d, 30d, 90d, ytd, all)

Response:
{
    "overview": {
        "followers": {
            "current": 125000,
            "change": 8500,
            "changePercent": 7.3,
            "trend": "UP"
        },
        "engagementRate": {
            "current": 4.2,
            "change": 0.3,
            "changePercent": 7.7,
            "trend": "UP"
        },
        "earnings": {
            "current": 750000,
            "change": 225000,
            "changePercent": 43,
            "trend": "UP"
        },
        "campaigns": {
            "completed": 12,
            "active": 2,
            "change": 3,
            "trend": "UP"
        }
    },
    "charts": {
        "followerGrowth": {
            "labels": ["Jan", "Feb", "Mar", "Apr", "May", "Jun"],
            "data": [95000, 102000, 108000, 112000, 118000, 125000]
        },
        "earningsTrend": {
            "labels": ["Jan", "Feb", "Mar", "Apr", "May", "Jun"],
            "data": [45000, 65000, 55000, 120000, 95000, 145000]
        },
        "engagementTrend": {
            "labels": ["Jan", "Feb", "Mar", "Apr", "May", "Jun"],
            "data": [3.8, 3.9, 4.0, 4.1, 4.0, 4.2]
        }
    },
    "quickStats": {
        "avgPostReach": 45000,
        "avgReelViews": 125000,
        "bestPostingTime": "7-9 PM",
        "topContentType": "Reels",
        "responseRate": 95,
        "onTimeDelivery": 98
    },
    "period": "30d",
    "lastUpdated": "2026-07-07T06:00:00Z"
}
```

### 4.2 Get Follower Analytics

```
GET /api/v1/creator/analytics/followers
Query Parameters:
  period      - (7d, 30d, 90d, 1y)
  granularity - (daily, weekly, monthly)

Response:
{
    "current": {
        "total": 125000,
        "instagram": 125000,
        "youtube": 45000,
        "combined": 170000
    },
    "growth": {
        "period": "30d",
        "change": 8500,
        "changePercent": 7.3,
        "avgDaily": 283
    },
    "history": [
        { "date": "2026-07-01", "followers": 116500 },
        { "date": "2026-07-02", "followers": 116800 },
        // ...
    ],
    "demographics": {
        "ageRanges": {
            "18-24": 35,
            "25-34": 45,
            "35-44": 15,
            "45+": 5
        },
        "genders": {
            "female": 65,
            "male": 35
        },
        "topCities": [
            { "city": "Mumbai", "percentage": 18 },
            { "city": "Delhi", "percentage": 15 },
            { "city": "Bangalore", "percentage": 12 }
        ],
        "topCountries": [
            { "country": "India", "percentage": 92 },
            { "country": "USA", "percentage": 3 },
            { "country": "UAE", "percentage": 2 }
        ]
    },
    "benchmark": {
        "yourGrowthRate": 7.3,
        "categoryAvg": 5.2,
        "topCreatorsAvg": 12.5,
        "percentile": 75
    }
}
```

### 4.3 Get Engagement Analytics

```
GET /api/v1/creator/analytics/engagement
Query Parameters:
  period  - (7d, 30d, 90d)

Response:
{
    "current": {
        "engagementRate": 4.2,
        "avgLikes": 5250,
        "avgComments": 128,
        "avgShares": 85,
        "avgSaves": 320
    },
    "trends": {
        "engagementRate": [
            { "date": "2026-07-01", "rate": 4.0 },
            // ...
        ],
        "interactions": [
            { "date": "2026-07-01", "likes": 5100, "comments": 120 },
            // ...
        ]
    },
    "contentPerformance": {
        "byType": {
            "reels": { "avgEngagement": 5.2, "count": 15 },
            "posts": { "avgEngagement": 3.8, "count": 8 },
            "stories": { "avgEngagement": 2.5, "count": 45 },
            "carousels": { "avgEngagement": 4.5, "count": 5 }
        },
        "byCategory": {
            "fitness": { "avgEngagement": 4.8, "count": 20 },
            "lifestyle": { "avgEngagement": 3.5, "count": 10 }
        }
    },
    "topPosts": [
        {
            "id": "post_xxx",
            "platform": "INSTAGRAM",
            "type": "REEL",
            "engagement": 8.5,
            "likes": 15000,
            "comments": 450,
            "thumbnailUrl": "https://...",
            "postedAt": "2026-06-28T10:00:00Z"
        }
    ],
    "bestTimes": {
        "monday": ["7PM", "8PM", "9PM"],
        "tuesday": ["6PM", "7PM", "8PM"],
        // ...
    },
    "benchmark": {
        "yourRate": 4.2,
        "categoryAvg": 3.5,
        "topCreatorsAvg": 6.0,
        "percentile": 80
    }
}
```

### 4.4 Get Earnings Analytics

```
GET /api/v1/creator/analytics/earnings
Query Parameters:
  period  - (30d, 90d, ytd, 1y, all)

Response:
{
    "summary": {
        "totalEarnings": 750000,
        "campaignEarnings": 720000,
        "affiliateEarnings": 25000,
        "bonuses": 5000,
        "periodChange": 43
    },
    "trend": [
        { "month": "Jan", "earnings": 45000 },
        { "month": "Feb", "earnings": 65000 },
        // ...
    ],
    "byCategory": {
        "fitness": 450000,
        "lifestyle": 200000,
        "health": 100000
    },
    "byBrand": [
        { "brand": "HealthKart", "earnings": 250000, "campaigns": 4 },
        { "brand": "Nike India", "earnings": 180000, "campaigns": 3 },
        // ...
    ],
    "byContentType": {
        "INSTAGRAM_REEL": 400000,
        "YOUTUBE_VIDEO": 200000,
        "INSTAGRAM_POST": 100000,
        "INSTAGRAM_STORY": 50000
    },
    "rateAnalysis": {
        "avgRateCharged": 22000,
        "marketAvgForTier": 19000,
        "comparison": "+15.8%",
        "ratesByType": {
            "INSTAGRAM_REEL": { "your": 25000, "market": 22000 },
            "INSTAGRAM_POST": { "your": 15000, "market": 14000 },
            "YOUTUBE_VIDEO": { "your": 50000, "market": 45000 }
        }
    },
    "projections": {
        "monthlyAvg": 125000,
        "projectedYearly": 1500000,
        "growthRate": 12
    }
}
```

### 4.5 Get Campaign Analytics

```
GET /api/v1/creator/analytics/campaigns
Query Parameters:
  period  - (30d, 90d, ytd, all)
  status  - (completed, active, all)

Response:
{
    "summary": {
        "totalCampaigns": 15,
        "completedCampaigns": 12,
        "activeCampaigns": 2,
        "pendingCampaigns": 1,
        "avgRating": 4.8,
        "totalBrands": 8,
        "repeatClients": 3
    },
    "campaigns": [
        {
            "id": "camp_xxx",
            "title": "Summer Fitness Challenge",
            "brand": {
                "name": "HealthKart",
                "logo": "https://..."
            },
            "earnings": 55000,
            "performance": {
                "totalReach": 450000,
                "totalEngagements": 18000,
                "avgEngagementRate": 4.0
            },
            "deliverables": {
                "total": 5,
                "completed": 5
            },
            "rating": 5,
            "completedAt": "2026-06-30"
        }
    ],
    "topPerforming": [
        {
            "campaignId": "camp_xxx",
            "title": "Summer Fitness Challenge",
            "metric": "engagement",
            "value": 4.0
        }
    ],
    "brandRelationships": [
        {
            "brandName": "HealthKart",
            "totalCampaigns": 4,
            "totalEarnings": 250000,
            "avgRating": 4.75,
            "lastCampaign": "2026-06-30"
        }
    ],
    "contentPerformance": {
        "avgReachPerDeliverable": 90000,
        "avgEngagementPerDeliverable": 3600,
        "topPerformingType": "INSTAGRAM_REEL"
    }
}
```

### 4.6 Get AI Insights

```
GET /api/v1/creator/analytics/insights
Query Parameters:
  type      - Filter by type
  priority  - Filter by priority
  limit     - Number of insights (default 5)

Response:
{
    "insights": [
        {
            "id": "ins_xxx",
            "type": "RATE",
            "priority": "HIGH",
            "title": "Your rates are below market",
            "description": "Based on your follower count (125K) and engagement rate (4.2%), you're charging 15% below market average for your tier.",
            "data": {
                "currentRate": 15000,
                "suggestedRate": 18000,
                "marketAvg": 17000
            },
            "action": {
                "text": "Update your rates",
                "url": "/creator/settings/rates"
            },
            "generatedAt": "2026-07-07T00:00:00Z"
        },
        {
            "id": "ins_yyy",
            "type": "CONTENT",
            "priority": "MEDIUM",
            "title": "Reels outperform your posts",
            "description": "Your Reels get 2.5x more engagement than static posts. Consider creating more Reel content.",
            "data": {
                "reelEngagement": 5.2,
                "postEngagement": 2.1
            },
            "action": {
                "text": "See content insights",
                "url": "/creator/analytics/engagement"
            },
            "generatedAt": "2026-07-07T00:00:00Z"
        },
        {
            "id": "ins_zzz",
            "type": "ENGAGEMENT",
            "priority": "MEDIUM",
            "title": "Peak engagement at 7-9 PM",
            "description": "Your posts perform best between 7-9 PM. Schedule your content during these hours for maximum reach.",
            "data": {
                "peakHours": ["7PM", "8PM", "9PM"],
                "avgEngagementPeak": 5.5,
                "avgEngagementOther": 3.2
            },
            "generatedAt": "2026-07-07T00:00:00Z"
        }
    ],
    "dismissedCount": 2
}
```

### 4.7 Dismiss/Act on Insight

```
POST /api/v1/creator/analytics/insights/{insightId}/dismiss
POST /api/v1/creator/analytics/insights/{insightId}/acted
```

---

## 5. Backend Implementation

### 5.1 Analytics Calculation Service

```java
@Service
public class AnalyticsCalculationService {
    
    private final CreatorAnalyticsRepository analyticsRepo;
    private final SnapshotRepository snapshotRepo;
    private final SocialStatsRepository statsRepo;
    
    @Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
    public void calculateDailyAnalytics() {
        log.info("Starting daily analytics calculation");
        
        List<CreatorProfile> activeCreators = creatorRepo.findAllActive();
        
        for (CreatorProfile creator : activeCreators) {
            try {
                calculateCreatorAnalytics(creator);
                createDailySnapshot(creator);
            } catch (Exception e) {
                log.error("Failed to calculate analytics for creator {}", creator.getId(), e);
            }
        }
    }
    
    @Transactional
    public void calculateCreatorAnalytics(CreatorProfile creator) {
        CreatorAnalytics analytics = analyticsRepo.findByCreatorId(creator.getId())
            .orElseGet(() -> createNewAnalytics(creator));
        
        // Get social stats
        List<SocialStats> socialStats = statsRepo.findByCreatorId(creator.getId());
        
        // Calculate follower totals
        int totalFollowers = socialStats.stream()
            .mapToInt(SocialStats::getFollowers)
            .sum();
        
        // Calculate engagement rate (weighted average)
        double avgEngagement = socialStats.stream()
            .mapToDouble(s -> s.getEngagementRate() * s.getFollowers())
            .sum() / totalFollowers;
        
        analytics.setTotalFollowers(totalFollowers);
        analytics.setAvgEngagementRate(avgEngagement);
        
        // Calculate growth
        AnalyticsSnapshot snapshot7d = snapshotRepo
            .findByCreatorIdAndSnapshotDate(creator.getId(), LocalDate.now().minusDays(7))
            .orElse(null);
        
        AnalyticsSnapshot snapshot30d = snapshotRepo
            .findByCreatorIdAndSnapshotDate(creator.getId(), LocalDate.now().minusDays(30))
            .orElse(null);
        
        if (snapshot7d != null) {
            analytics.setFollowersGrowth7d(totalFollowers - snapshot7d.getFollowers());
            analytics.setGrowthRate7d(
                (double)(totalFollowers - snapshot7d.getFollowers()) / snapshot7d.getFollowers() * 100
            );
        }
        
        if (snapshot30d != null) {
            analytics.setFollowersGrowth30d(totalFollowers - snapshot30d.getFollowers());
            analytics.setGrowthRate30d(
                (double)(totalFollowers - snapshot30d.getFollowers()) / snapshot30d.getFollowers() * 100
            );
        }
        
        // Calculate campaign stats
        List<Contract> completedContracts = contractRepo
            .findByCreatorIdAndStatus(creator.getId(), ContractStatus.COMPLETED);
        
        analytics.setTotalCampaignsCompleted(completedContracts.size());
        analytics.setTotalCampaignsActive(
            contractRepo.countByCreatorIdAndStatus(creator.getId(), ContractStatus.ACTIVE)
        );
        
        // Calculate earnings
        Long earnings30d = calculateEarnings(creator.getId(), 30);
        Long earnings90d = calculateEarnings(creator.getId(), 90);
        
        analytics.setEarnings30d(earnings30d);
        analytics.setEarnings90d(earnings90d);
        analytics.setTotalLifetimeEarnings(
            walletRepo.findByCreatorId(creator.getId())
                .map(CreatorWallet::getTotalEarned)
                .orElse(0L)
        );
        
        // Calculate average rate
        BigDecimal avgRate = calculateAverageRate(creator.getId());
        analytics.setAvgRateCharged(avgRate);
        
        // Market comparison
        BigDecimal marketRate = getMarketRateForTier(
            totalFollowers,
            avgEngagement,
            creator.getCategories()
        );
        analytics.setMarketRateForTier(marketRate);
        analytics.setRateComparisonPct(
            (avgRate.doubleValue() - marketRate.doubleValue()) / marketRate.doubleValue() * 100
        );
        
        // Performance metrics
        analytics.setResponseRate(calculateResponseRate(creator.getId()));
        analytics.setOnTimeDeliveryRate(calculateOnTimeDeliveryRate(creator.getId()));
        
        analytics.setCalculatedAt(Instant.now());
        analyticsRepo.save(analytics);
    }
    
    private void createDailySnapshot(CreatorProfile creator) {
        CreatorAnalytics analytics = analyticsRepo.findByCreatorId(creator.getId()).orElseThrow();
        
        AnalyticsSnapshot yesterday = snapshotRepo
            .findByCreatorIdAndSnapshotDate(creator.getId(), LocalDate.now().minusDays(1))
            .orElse(null);
        
        AnalyticsSnapshot snapshot = AnalyticsSnapshot.builder()
            .id(Ulids.generate())
            .creator(creator)
            .snapshotDate(LocalDate.now())
            .period(SnapshotPeriod.DAILY)
            .followers(analytics.getTotalFollowers())
            .followersChange(yesterday != null 
                ? analytics.getTotalFollowers() - yesterday.getFollowers() 
                : 0)
            .engagementRate(analytics.getAvgEngagementRate())
            .earnings(analytics.getEarnings30d() / 30)  // Approximate daily
            .createdAt(Instant.now())
            .build();
        
        snapshotRepo.save(snapshot);
    }
}
```

### 5.2 AI Insights Service

```java
@Service
public class AiInsightsService {
    
    private final AiInsightRepository insightRepo;
    private final AnthropicClient aiClient;
    
    @Scheduled(cron = "0 0 4 * * *")  // Daily at 4 AM
    public void generateDailyInsights() {
        List<CreatorProfile> creators = creatorRepo.findAllActive();
        
        for (CreatorProfile creator : creators) {
            try {
                generateInsightsForCreator(creator);
            } catch (Exception e) {
                log.error("Failed to generate insights for creator {}", creator.getId(), e);
            }
        }
    }
    
    @Transactional
    public void generateInsightsForCreator(CreatorProfile creator) {
        // Clear old insights
        insightRepo.expireOldInsights(creator.getId(), Instant.now());
        
        CreatorAnalytics analytics = analyticsRepo.findByCreatorId(creator.getId()).orElse(null);
        if (analytics == null) return;
        
        // Generate rate insight
        if (analytics.getRateComparisonPct() < -10) {
            createInsight(creator, InsightType.RATE, InsightPriority.HIGH,
                "Your rates are below market",
                String.format("Based on your follower count (%sK) and engagement rate (%.1f%%), " +
                    "you're charging %.0f%% below market average for your tier.",
                    analytics.getTotalFollowers() / 1000,
                    analytics.getAvgEngagementRate(),
                    Math.abs(analytics.getRateComparisonPct())),
                Map.of(
                    "currentRate", analytics.getAvgRateCharged(),
                    "marketAvg", analytics.getMarketRateForTier(),
                    "suggestedRate", analytics.getMarketRateForTier().multiply(new BigDecimal("1.05"))
                ),
                "Update your rates",
                "/creator/settings/rates"
            );
        }
        
        // Generate growth insight
        if (analytics.getGrowthRate30d() < 2) {
            createInsight(creator, InsightType.GROWTH, InsightPriority.MEDIUM,
                "Your growth is slowing",
                String.format("Your follower growth of %.1f%% this month is below the category average of 5%%. " +
                    "Try posting more Reels and engaging with your audience.",
                    analytics.getGrowthRate30d()),
                Map.of(
                    "yourGrowth", analytics.getGrowthRate30d(),
                    "categoryAvg", 5.0
                ),
                "See growth tips",
                "/creator/analytics/followers"
            );
        }
        
        // Generate content insights based on performance
        generateContentInsights(creator, analytics);
        
        // Generate engagement time insights
        generateTimingInsights(creator);
    }
    
    private void generateContentInsights(CreatorProfile creator, CreatorAnalytics analytics) {
        // Compare content type performance
        Map<String, Double> contentPerformance = calculateContentTypePerformance(creator.getId());
        
        String bestType = contentPerformance.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
        
        String worstType = contentPerformance.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
        
        if (bestType != null && worstType != null) {
            double ratio = contentPerformance.get(bestType) / contentPerformance.get(worstType);
            
            if (ratio > 2.0) {
                createInsight(creator, InsightType.CONTENT, InsightPriority.MEDIUM,
                    String.format("%s outperform your %s", 
                        formatContentType(bestType), formatContentType(worstType).toLowerCase()),
                    String.format("Your %s get %.1fx more engagement than %s. Consider creating more %s content.",
                        formatContentType(bestType).toLowerCase(),
                        ratio,
                        formatContentType(worstType).toLowerCase(),
                        formatContentType(bestType).toLowerCase()),
                    Map.of(
                        "bestType", bestType,
                        "bestEngagement", contentPerformance.get(bestType),
                        "worstType", worstType,
                        "worstEngagement", contentPerformance.get(worstType)
                    ),
                    "See content insights",
                    "/creator/analytics/engagement"
                );
            }
        }
    }
    
    private void createInsight(
        CreatorProfile creator,
        InsightType type,
        InsightPriority priority,
        String title,
        String description,
        Map<String, Object> data,
        String actionText,
        String actionUrl
    ) {
        AiInsight insight = AiInsight.builder()
            .id(Ulids.generate())
            .creator(creator)
            .type(type)
            .priority(priority)
            .title(title)
            .description(description)
            .data(data)
            .actionText(actionText)
            .actionUrl(actionUrl)
            .isDismissed(false)
            .isActedUpon(false)
            .validFrom(Instant.now())
            .validUntil(Instant.now().plus(7, ChronoUnit.DAYS))
            .generatedAt(Instant.now())
            .build();
        
        insightRepo.save(insight);
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Analytics Dashboard

```tsx
export function AnalyticsDashboard() {
  const [period, setPeriod] = useState<Period>('30d');
  const { data: analytics, isLoading } = useAnalyticsDashboard(period);
  const { data: insights } = useInsights();
  
  if (isLoading) return <AnalyticsSkeleton />;
  
  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Analytics</h1>
          <p className="text-muted-foreground">Track your growth and performance</p>
        </div>
        
        <Select value={period} onValueChange={setPeriod}>
          <SelectTrigger className="w-32">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7d">Last 7 days</SelectItem>
            <SelectItem value="30d">Last 30 days</SelectItem>
            <SelectItem value="90d">Last 90 days</SelectItem>
            <SelectItem value="ytd">Year to date</SelectItem>
          </SelectContent>
        </Select>
      </div>
      
      {/* Overview Cards */}
      <div className="grid grid-cols-4 gap-4">
        <MetricCard
          title="Followers"
          value={formatNumber(analytics.overview.followers.current)}
          change={analytics.overview.followers.changePercent}
          trend={analytics.overview.followers.trend}
          icon={Users}
        />
        <MetricCard
          title="Engagement Rate"
          value={`${analytics.overview.engagementRate.current}%`}
          change={analytics.overview.engagementRate.changePercent}
          trend={analytics.overview.engagementRate.trend}
          icon={Heart}
        />
        <MetricCard
          title="Earnings"
          value={formatCurrency(analytics.overview.earnings.current)}
          change={analytics.overview.earnings.changePercent}
          trend={analytics.overview.earnings.trend}
          icon={DollarSign}
        />
        <MetricCard
          title="Campaigns"
          value={analytics.overview.campaigns.completed}
          change={analytics.overview.campaigns.change}
          trend={analytics.overview.campaigns.trend}
          icon={Briefcase}
          subtitle={`${analytics.overview.campaigns.active} active`}
        />
      </div>
      
      {/* Charts Row */}
      <div className="grid grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Follower Growth</CardTitle>
          </CardHeader>
          <CardContent>
            <LineChart
              data={analytics.charts.followerGrowth}
              color="hsl(var(--primary))"
            />
          </CardContent>
        </Card>
        
        <Card>
          <CardHeader>
            <CardTitle>Earnings Trend</CardTitle>
          </CardHeader>
          <CardContent>
            <BarChart
              data={analytics.charts.earningsTrend}
              color="hsl(var(--chart-2))"
            />
          </CardContent>
        </Card>
      </div>
      
      {/* AI Insights */}
      {insights && insights.insights.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Sparkles className="h-5 w-5 text-amber-500" />
              AI Insights
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {insights.insights.slice(0, 3).map((insight) => (
                <InsightCard key={insight.id} insight={insight} />
              ))}
            </div>
          </CardContent>
        </Card>
      )}
      
      {/* Quick Stats */}
      <div className="grid grid-cols-4 gap-4">
        <QuickStatCard
          title="Avg Post Reach"
          value={formatNumber(analytics.quickStats.avgPostReach)}
          icon={Eye}
        />
        <QuickStatCard
          title="Avg Reel Views"
          value={formatNumber(analytics.quickStats.avgReelViews)}
          icon={Play}
        />
        <QuickStatCard
          title="Best Time to Post"
          value={analytics.quickStats.bestPostingTime}
          icon={Clock}
        />
        <QuickStatCard
          title="Top Content"
          value={analytics.quickStats.topContentType}
          icon={TrendingUp}
        />
      </div>
    </div>
  );
}
```

### 6.2 Insight Card

```tsx
interface InsightCardProps {
  insight: AiInsight;
}

export function InsightCard({ insight }: InsightCardProps) {
  const { mutate: dismiss } = useDismissInsight();
  
  return (
    <div className={cn(
      "flex items-start gap-4 p-4 rounded-lg",
      insight.priority === 'HIGH' && "bg-orange-50 border border-orange-200",
      insight.priority === 'MEDIUM' && "bg-blue-50 border border-blue-200",
      insight.priority === 'LOW' && "bg-gray-50 border border-gray-200"
    )}>
      <div className={cn(
        "h-10 w-10 rounded-full flex items-center justify-center",
        insight.priority === 'HIGH' && "bg-orange-100 text-orange-600",
        insight.priority === 'MEDIUM' && "bg-blue-100 text-blue-600",
        insight.priority === 'LOW' && "bg-gray-100 text-gray-600"
      )}>
        {insight.type === 'RATE' && <DollarSign className="h-5 w-5" />}
        {insight.type === 'GROWTH' && <TrendingUp className="h-5 w-5" />}
        {insight.type === 'CONTENT' && <FileVideo className="h-5 w-5" />}
        {insight.type === 'ENGAGEMENT' && <Heart className="h-5 w-5" />}
      </div>
      
      <div className="flex-1">
        <h4 className="font-medium">{insight.title}</h4>
        <p className="text-sm text-muted-foreground mt-1">
          {insight.description}
        </p>
        
        {insight.action && (
          <Button
            variant="link"
            size="sm"
            className="p-0 h-auto mt-2"
            asChild
          >
            <Link href={insight.action.url}>
              {insight.action.text}
              <ArrowRight className="h-3 w-3 ml-1" />
            </Link>
          </Button>
        )}
      </div>
      
      <Button
        variant="ghost"
        size="icon"
        className="shrink-0"
        onClick={() => dismiss(insight.id)}
      >
        <X className="h-4 w-4" />
      </Button>
    </div>
  );
}
```

### 6.3 Earnings Analytics Page

```tsx
export function EarningsAnalyticsPage() {
  const [period, setPeriod] = useState<Period>('90d');
  const { data: earnings, isLoading } = useEarningsAnalytics(period);
  
  if (isLoading) return <Skeleton className="h-96" />;
  
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold">Earnings Analytics</h2>
        <PeriodSelector value={period} onChange={setPeriod} />
      </div>
      
      {/* Summary */}
      <div className="grid grid-cols-4 gap-4">
        <MetricCard
          title="Total Earnings"
          value={formatCurrency(earnings.summary.totalEarnings)}
          change={earnings.summary.periodChange}
        />
        <MetricCard
          title="Campaign Earnings"
          value={formatCurrency(earnings.summary.campaignEarnings)}
        />
        <MetricCard
          title="Affiliate Earnings"
          value={formatCurrency(earnings.summary.affiliateEarnings)}
        />
        <MetricCard
          title="Bonuses"
          value={formatCurrency(earnings.summary.bonuses)}
        />
      </div>
      
      {/* Trend Chart */}
      <Card>
        <CardHeader>
          <CardTitle>Earnings Over Time</CardTitle>
        </CardHeader>
        <CardContent>
          <BarChart
            data={{
              labels: earnings.trend.map(t => t.month),
              values: earnings.trend.map(t => t.earnings),
            }}
          />
        </CardContent>
      </Card>
      
      {/* By Category & Brand */}
      <div className="grid grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>By Category</CardTitle>
          </CardHeader>
          <CardContent>
            <PieChart
              data={Object.entries(earnings.byCategory).map(([cat, amount]) => ({
                label: cat,
                value: amount,
              }))}
            />
          </CardContent>
        </Card>
        
        <Card>
          <CardHeader>
            <CardTitle>Top Brands</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {earnings.byBrand.slice(0, 5).map((brand) => (
                <div key={brand.brand} className="flex items-center justify-between">
                  <div>
                    <p className="font-medium">{brand.brand}</p>
                    <p className="text-sm text-muted-foreground">
                      {brand.campaigns} campaign{brand.campaigns > 1 ? 's' : ''}
                    </p>
                  </div>
                  <p className="font-semibold">{formatCurrency(brand.earnings)}</p>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
      
      {/* Rate Analysis */}
      <Card>
        <CardHeader>
          <CardTitle>Rate Comparison</CardTitle>
          <CardDescription>
            How your rates compare to market average for your tier
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {Object.entries(earnings.rateAnalysis.ratesByType).map(([type, rates]) => (
              <div key={type}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium">{formatDeliverableType(type)}</span>
                  <span className={cn(
                    "text-sm",
                    rates.your > rates.market ? "text-green-600" : "text-orange-600"
                  )}>
                    {rates.your > rates.market ? '+' : ''}
                    {Math.round((rates.your - rates.market) / rates.market * 100)}%
                  </span>
                </div>
                <div className="flex gap-2 items-center">
                  <div className="flex-1 bg-muted rounded-full h-2">
                    <div
                      className="bg-primary rounded-full h-2"
                      style={{ width: `${Math.min(100, rates.your / rates.market * 50)}%` }}
                    />
                  </div>
                  <span className="text-sm w-20 text-right">{formatCurrency(rates.your)}</span>
                </div>
                <p className="text-xs text-muted-foreground mt-1">
                  Market avg: {formatCurrency(rates.market)}
                </p>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Data Access
- **Privacy:** Analytics only visible to the creator
- **Aggregation:** Sensitive data aggregated, not raw
- **API Security:** Rate limiting on analytics endpoints

### 7.2 AI Insights
- **Data Isolation:** AI only sees aggregated metrics
- **No PII:** Personal information never included in AI context
- **Audit Trail:** Log all AI insight generations

---

## 8. Test Cases (Kavya)

```java
// Analytics Calculation Tests
@Test void shouldCalculateFollowerGrowth()
@Test void shouldCalculateEngagementRate()
@Test void shouldCalculateEarnings()
@Test void shouldCreateDailySnapshot()
@Test void shouldCompareWithMarketRate()

// Insights Tests
@Test void shouldGenerateRateInsight()
@Test void shouldGenerateGrowthInsight()
@Test void shouldGenerateContentInsight()
@Test void shouldExpireOldInsights()
@Test void shouldDismissInsight()

// API Tests
@Test void shouldGetDashboardData()
@Test void shouldGetFollowerAnalytics()
@Test void shouldGetEarningsAnalytics()
@Test void shouldGetCampaignAnalytics()
@Test void shouldFilterByPeriod()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/analytics/dashboard` | GET | JWT | Get dashboard overview |
| `/creator/analytics/followers` | GET | JWT | Get follower analytics |
| `/creator/analytics/engagement` | GET | JWT | Get engagement analytics |
| `/creator/analytics/earnings` | GET | JWT | Get earnings analytics |
| `/creator/analytics/campaigns` | GET | JWT | Get campaign analytics |
| `/creator/analytics/insights` | GET | JWT | Get AI insights |
| `/creator/analytics/insights/{id}/dismiss` | POST | JWT | Dismiss insight |
| `/creator/analytics/insights/{id}/acted` | POST | JWT | Mark as acted upon |
