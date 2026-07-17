# Creator Discovery Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Discovery Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BRAND DISCOVERS CREATOR                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  🔍 Search Creators                                                   │   │
│  │  ┌────────────────────────────────────────────────────────────────┐  │   │
│  │  │ Search by name, niche, or location...                          │  │   │
│  │  └────────────────────────────────────────────────────────────────┘  │   │
│  │                                                                       │   │
│  │  Filters:                                                             │   │
│  │  [Niche ▼] [Followers ▼] [Engagement ▼] [Location ▼] [Rate ▼]       │   │
│  │                                                                       │   │
│  │  Sort by: [Relevance ▼]                                              │   │
│  │                                                                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐                   │
│  │ 📸 Riya S.   │  │ 📸 Arjun K.  │  │ 📸 Priya M.  │                   │
│  │ @riya_fitness│  │ @arjun_tech  │  │ @priya_food  │                   │
│  │ 125K | 4.2%  │  │ 89K | 3.8%   │  │ 210K | 5.1%  │                   │
│  │ ₹15K/post    │  │ ₹12K/post    │  │ ₹25K/post    │                   │
│  │ [View] [Inv] │  │ [View] [Inv] │  │ [View] [Inv] │                   │
│  └───────────────┘  └───────────────┘  └───────────────┘                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Search & Filter Architecture

### 2.1 Search Flow

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Brand   │ →  │  Search  │ →  │  Filter  │ →  │  Rank &  │
│  Query   │    │  Parse   │    │  Apply   │    │  Sort    │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                                                      ↓
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Cache   │ ←  │  Format  │ ←  │  Public  │ ←  │  Paginate│
│  Results │    │  Response│    │  Filter  │    │  Results │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
```

### 2.2 Search Index (Elasticsearch)

```json
{
  "creator_index": {
    "mappings": {
      "properties": {
        "id": { "type": "keyword" },
        "username": { "type": "keyword" },
        "displayName": { "type": "text", "analyzer": "standard" },
        "bio": { "type": "text", "analyzer": "standard" },
        "categories": { "type": "keyword" },
        "languages": { "type": "keyword" },
        "city": { "type": "keyword" },
        "state": { "type": "keyword" },
        "country": { "type": "keyword" },
        "location": { "type": "geo_point" },
        "totalFollowers": { "type": "integer" },
        "engagementRate": { "type": "float" },
        "qualityScore": { "type": "float" },
        "authenticityScore": { "type": "float" },
        "brandSafetyScore": { "type": "float" },
        "rateInstagramPost": { "type": "integer" },
        "rateInstagramReel": { "type": "integer" },
        "rateYoutubeVideo": { "type": "integer" },
        "isDiscoverable": { "type": "boolean" },
        "acceptingCollabs": { "type": "boolean" },
        "verificationStatus": { "type": "keyword" },
        "platforms": { "type": "keyword" },
        "lastActiveAt": { "type": "date" },
        "createdAt": { "type": "date" }
      }
    }
  }
}
```

---

## 3. Database Schema

### 3.1 CreatorSearchIndex Entity

```java
@Entity
@Table(name = "creator_search_index")
public class CreatorSearchIndex {
    
    @Id
    private String creatorId;
    
    // Searchable text fields
    private String displayName;
    private String username;
    private String bio;
    
    // Categories and languages
    @Convert(converter = JsonListConverter.class)
    private List<String> categories;
    
    @Convert(converter = JsonListConverter.class)
    private List<String> languages;
    
    // Location
    private String city;
    private String state;
    private String country;
    private Double latitude;
    private Double longitude;
    
    // Stats (denormalized for fast search)
    private Integer totalFollowers;
    private Double engagementRate;
    private Double qualityScore;
    private Double authenticityScore;
    private Double brandSafetyScore;
    
    // Rates
    private Integer rateInstagramPost;
    private Integer rateInstagramReel;
    private Integer rateInstagramStory;
    private Integer rateYoutubeVideo;
    private Integer rateYoutubeShort;
    
    // Connected platforms
    @Convert(converter = JsonListConverter.class)
    private List<String> connectedPlatforms;  // ["INSTAGRAM", "YOUTUBE"]
    
    // Discoverability
    private Boolean isDiscoverable;
    private Boolean acceptingCollabs;
    private Boolean isVerified;
    private Boolean isFeatured;
    
    // Activity
    private Instant lastActiveAt;
    private Integer completedCampaigns;
    private Double avgRating;
    
    // Timestamps
    private Instant indexedAt;
}
```

### 3.2 FeaturedCreator Entity

```java
@Entity
@Table(name = "featured_creators")
public class FeaturedCreator {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    private String featuredCategory;  // "top_fitness", "rising_star", "editors_pick"
    private Integer displayOrder;
    
    private Instant featuredFrom;
    private Instant featuredUntil;
    
    private Boolean isActive;
    
    private String featuredByUserId;  // Admin who featured
    private String featuredReason;
}
```

---

## 4. API Endpoints

### 4.1 Search Creators

```
GET /api/v1/creators/search
Query Parameters:
  q                 - Search query (name, bio, categories)
  categories[]      - Filter by categories (multiple allowed)
  followers_min     - Minimum followers
  followers_max     - Maximum followers
  engagement_min    - Minimum engagement rate
  engagement_max    - Maximum engagement rate
  rate_min          - Minimum rate (any platform)
  rate_max          - Maximum rate (any platform)
  location          - City, state, or country
  languages[]       - Languages spoken
  platforms[]       - Connected platforms (INSTAGRAM, YOUTUBE, FACEBOOK)
  verified_only     - Only verified creators
  accepting_collabs - Only accepting new collaborations
  sort              - Sort field (relevance, followers, engagement, rate, rating)
  order             - Sort order (asc, desc)
  page              - Page number (0-indexed)
  size              - Page size (default 20, max 50)

Response:
{
    "creators": [
        {
            "id": "cr_xxx",
            "username": "riya_fitness",
            "displayName": "Riya Sharma",
            "bio": "Certified fitness trainer...",
            "profilePhoto": "https://...",
            "categories": ["fitness", "lifestyle"],
            "location": {
                "city": "Mumbai",
                "state": "Maharashtra",
                "country": "India"
            },
            "stats": {
                "followers": 125000,
                "engagementRate": 4.2,
                "avgLikes": 5250
            },
            "scores": {
                "quality": 8.5,
                "authenticity": 9.2,
                "brandSafety": 9.8
            },
            "rates": {
                "instagramPost": 15000,
                "instagramReel": 25000
            },
            "platforms": ["INSTAGRAM", "YOUTUBE"],
            "isVerified": true,
            "acceptingCollabs": true,
            "completedCampaigns": 12,
            "avgRating": 4.8
        }
    ],
    "pagination": {
        "page": 0,
        "size": 20,
        "totalElements": 1250,
        "totalPages": 63
    },
    "filters": {
        "applied": {
            "categories": ["fitness"],
            "followers_min": 10000
        },
        "available": {
            "categories": [
                { "id": "fitness", "count": 1250 },
                { "id": "lifestyle", "count": 890 }
            ],
            "followers_ranges": [
                { "range": "1K-10K", "count": 5000 },
                { "range": "10K-100K", "count": 2500 },
                { "range": "100K-1M", "count": 800 }
            ]
        }
    }
}
```

### 4.2 Get Creator Public Profile

```
GET /api/v1/creators/{username}

Response:
{
    "id": "cr_xxx",
    "username": "riya_fitness",
    "displayName": "Riya Sharma",
    "bio": "Certified fitness trainer helping busy professionals...",
    "profilePhoto": "https://...",
    "coverPhoto": "https://...",
    "categories": ["fitness", "lifestyle", "health"],
    "languages": ["en", "hi"],
    "location": {
        "city": "Mumbai",
        "state": "Maharashtra",
        "country": "India"
    },
    "stats": {
        "instagram": {
            "followers": 125000,
            "engagementRate": 4.2,
            "avgLikes": 5250,
            "avgComments": 128
        },
        "youtube": {
            "subscribers": 45000,
            "avgViews": 12000
        }
    },
    "scores": {
        "quality": 8.5,
        "authenticity": 9.2,
        "brandSafety": 9.8
    },
    "rates": {
        "instagramPost": 15000,
        "instagramReel": 25000,
        "instagramStory": 5000,
        "youtubeVideo": 50000
    },
    "portfolio": [
        {
            "id": "port_xxx",
            "platform": "INSTAGRAM",
            "type": "REEL",
            "brandName": "Nike",
            "thumbnailUrl": "https://...",
            "metrics": {
                "likes": 15000,
                "comments": 450
            }
        }
    ],
    "reviews": {
        "avgRating": 4.8,
        "totalReviews": 12,
        "recent": [
            {
                "brandName": "Nike India",
                "rating": 5,
                "comment": "Excellent content quality and professionalism",
                "date": "2026-06-15"
            }
        ]
    },
    "badges": ["verified", "top_creator", "quick_responder"],
    "isVerified": true,
    "acceptingCollabs": true,
    "responseTime": "< 2 hours"
}
```

### 4.3 Get Featured Creators

```
GET /api/v1/creators/featured
Query Parameters:
  category  - Featured category (top_fitness, rising_star, editors_pick)
  limit     - Number of results (default 10)

Response:
{
    "featured": [
        {
            "category": "top_fitness",
            "title": "Top Fitness Creators",
            "creators": [...]
        },
        {
            "category": "rising_star",
            "title": "Rising Stars",
            "creators": [...]
        }
    ]
}
```

### 4.4 Get Similar Creators

```
GET /api/v1/creators/{username}/similar
Query Parameters:
  limit  - Number of results (default 6)

Response:
{
    "similar": [
        {
            "id": "cr_yyy",
            "username": "arjun_fitness",
            "matchScore": 0.85,
            "matchReasons": ["same_niche", "similar_followers", "same_location"]
        }
    ]
}
```

### 4.5 Creator Suggestions (AI-Powered)

```
POST /api/v1/creators/suggestions
{
    "campaignGoals": "Increase brand awareness for protein supplement",
    "targetAudience": "Fitness enthusiasts, 18-35, male",
    "budget": 200000,
    "platforms": ["INSTAGRAM", "YOUTUBE"]
}

Response:
{
    "suggestions": [
        {
            "creator": {...},
            "matchScore": 0.92,
            "reasons": [
                "Strong audience overlap with your target demographic",
                "Previous successful campaigns in supplements niche",
                "High engagement rate indicates authentic audience"
            ],
            "estimatedReach": 450000,
            "estimatedCost": 75000
        }
    ]
}
```

---

## 5. Backend Implementation

### 5.1 Creator Search Service

```java
@Service
public class CreatorSearchService {
    
    private final ElasticsearchOperations esOperations;
    private final CreatorProfileRepository profileRepo;
    private final CacheManager cacheManager;
    
    public Page<CreatorSearchResult> search(CreatorSearchRequest request) {
        // Build Elasticsearch query
        BoolQueryBuilder query = QueryBuilders.boolQuery();
        
        // Text search
        if (StringUtils.hasText(request.getQuery())) {
            query.must(QueryBuilders.multiMatchQuery(request.getQuery())
                .field("displayName", 3.0f)
                .field("username", 2.0f)
                .field("bio", 1.0f)
                .field("categories", 2.0f)
                .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                .fuzziness(Fuzziness.AUTO));
        }
        
        // Filters
        if (request.getCategories() != null && !request.getCategories().isEmpty()) {
            query.filter(QueryBuilders.termsQuery("categories", request.getCategories()));
        }
        
        if (request.getFollowersMin() != null) {
            query.filter(QueryBuilders.rangeQuery("totalFollowers")
                .gte(request.getFollowersMin()));
        }
        
        if (request.getFollowersMax() != null) {
            query.filter(QueryBuilders.rangeQuery("totalFollowers")
                .lte(request.getFollowersMax()));
        }
        
        if (request.getEngagementMin() != null) {
            query.filter(QueryBuilders.rangeQuery("engagementRate")
                .gte(request.getEngagementMin()));
        }
        
        if (request.getRateMin() != null || request.getRateMax() != null) {
            BoolQueryBuilder rateQuery = QueryBuilders.boolQuery();
            if (request.getRateMin() != null) {
                rateQuery.should(QueryBuilders.rangeQuery("rateInstagramPost").gte(request.getRateMin()));
                rateQuery.should(QueryBuilders.rangeQuery("rateInstagramReel").gte(request.getRateMin()));
                rateQuery.should(QueryBuilders.rangeQuery("rateYoutubeVideo").gte(request.getRateMin()));
            }
            query.filter(rateQuery);
        }
        
        if (request.getLocation() != null) {
            query.filter(QueryBuilders.boolQuery()
                .should(QueryBuilders.matchQuery("city", request.getLocation()))
                .should(QueryBuilders.matchQuery("state", request.getLocation()))
                .should(QueryBuilders.matchQuery("country", request.getLocation())));
        }
        
        if (request.getLanguages() != null) {
            query.filter(QueryBuilders.termsQuery("languages", request.getLanguages()));
        }
        
        if (request.getPlatforms() != null) {
            query.filter(QueryBuilders.termsQuery("connectedPlatforms", request.getPlatforms()));
        }
        
        if (Boolean.TRUE.equals(request.getVerifiedOnly())) {
            query.filter(QueryBuilders.termQuery("isVerified", true));
        }
        
        if (Boolean.TRUE.equals(request.getAcceptingCollabs())) {
            query.filter(QueryBuilders.termQuery("acceptingCollabs", true));
        }
        
        // Always filter discoverable
        query.filter(QueryBuilders.termQuery("isDiscoverable", true));
        
        // Build sort
        SortBuilder<?> sort = buildSort(request.getSort(), request.getOrder());
        
        // Execute search
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
            .withQuery(query)
            .withSort(sort)
            .withPageable(PageRequest.of(request.getPage(), request.getSize()))
            .withAggregations(buildAggregations())
            .build();
        
        SearchHits<CreatorSearchIndex> hits = esOperations.search(searchQuery, CreatorSearchIndex.class);
        
        // Build response with facets
        return buildPageResponse(hits, request);
    }
    
    private SortBuilder<?> buildSort(String sortField, String order) {
        SortOrder sortOrder = "asc".equalsIgnoreCase(order) ? SortOrder.ASC : SortOrder.DESC;
        
        return switch (sortField) {
            case "followers" -> SortBuilders.fieldSort("totalFollowers").order(sortOrder);
            case "engagement" -> SortBuilders.fieldSort("engagementRate").order(sortOrder);
            case "rate" -> SortBuilders.fieldSort("rateInstagramPost").order(sortOrder);
            case "rating" -> SortBuilders.fieldSort("avgRating").order(sortOrder);
            default -> SortBuilders.scoreSort().order(SortOrder.DESC);  // Relevance
        };
    }
}
```

### 5.2 Index Sync Service

```java
@Service
public class CreatorIndexSyncService {
    
    private final CreatorProfileRepository profileRepo;
    private final SocialStatsRepository statsRepo;
    private final ElasticsearchOperations esOperations;
    
    @Scheduled(fixedRate = 60000)  // Every minute
    public void syncRecentlyUpdated() {
        Instant since = Instant.now().minusMinutes(5);
        List<CreatorProfile> updated = profileRepo.findByUpdatedAtAfter(since);
        
        for (CreatorProfile profile : updated) {
            indexCreator(profile);
        }
    }
    
    @Transactional(readOnly = true)
    public void indexCreator(CreatorProfile profile) {
        // Skip non-discoverable profiles
        if (!Boolean.TRUE.equals(profile.getIsDiscoverable())) {
            esOperations.delete(profile.getId(), CreatorSearchIndex.class);
            return;
        }
        
        // Build index document
        CreatorSearchIndex index = CreatorSearchIndex.builder()
            .creatorId(profile.getId())
            .displayName(profile.getDisplayName())
            .username(profile.getUsername())
            .bio(profile.getBio())
            .categories(profile.getCategories())
            .languages(profile.getLanguages())
            .city(profile.getCity())
            .state(profile.getState())
            .country(profile.getCountry())
            .totalFollowers(profile.getTotalFollowers())
            .engagementRate(profile.getAvgEngagementRate())
            .qualityScore(profile.getQualityScore())
            .authenticityScore(profile.getAuthenticityScore())
            .brandSafetyScore(profile.getBrandSafetyScore())
            .rateInstagramPost(profile.getRateInstagramPost())
            .rateInstagramReel(profile.getRateInstagramReel())
            .rateYoutubeVideo(profile.getRateYoutubeVideo())
            .isDiscoverable(profile.getIsDiscoverable())
            .acceptingCollabs(profile.getAcceptingCollabs())
            .isVerified(profile.getVerificationStatus() == VerificationStatus.VERIFIED)
            .connectedPlatforms(getConnectedPlatforms(profile.getId()))
            .lastActiveAt(profile.getUpdatedAt())
            .completedCampaigns(getCompletedCampaignCount(profile.getId()))
            .avgRating(getAvgRating(profile.getId()))
            .indexedAt(Instant.now())
            .build();
        
        esOperations.save(index);
    }
    
    @Scheduled(cron = "0 0 3 * * *")  // Daily at 3 AM
    public void fullReindex() {
        log.info("Starting full creator index rebuild");
        
        // Create new index with timestamp
        String newIndex = "creators_" + Instant.now().toEpochMilli();
        
        // Index all discoverable creators
        profileRepo.streamByIsDiscoverableTrue().forEach(this::indexCreator);
        
        // Swap alias to new index
        esOperations.indexOps(CreatorSearchIndex.class).updateAliases(
            AliasActions.of(
                AliasAction.removeIndex("creators_*"),
                AliasAction.addIndex(newIndex, "creators")
            )
        );
        
        log.info("Full creator index rebuild complete");
    }
}
```

### 5.3 Public vs Connected Views

```java
@Service
public class CreatorViewService {
    
    public CreatorPublicView getPublicProfile(String username) {
        CreatorProfile profile = profileRepo.findByUsername(username)
            .filter(p -> Boolean.TRUE.equals(p.getIsDiscoverable()))
            .orElseThrow(() -> new CreatorNotFoundException(username));
        
        return CreatorPublicView.builder()
            .id(profile.getId())
            .username(profile.getUsername())
            .displayName(profile.getDisplayName())
            .bio(profile.getBio())
            .profilePhoto(profile.getProfilePhotoUrl())
            .coverPhoto(profile.getCoverPhotoUrl())
            .categories(profile.getCategories())
            .languages(profile.getLanguages())
            .location(buildLocation(profile))
            .stats(buildPublicStats(profile))
            .scores(buildScores(profile))
            .rates(buildRates(profile))
            .portfolio(getPublicPortfolio(profile.getId()))
            .reviews(getPublicReviews(profile.getId()))
            .badges(getBadges(profile))
            .isVerified(profile.getVerificationStatus() == VerificationStatus.VERIFIED)
            .acceptingCollabs(profile.getAcceptingCollabs())
            .responseTime(calculateResponseTime(profile.getId()))
            .build();
    }
    
    // Connected view includes more details (for brands with active collaboration)
    public CreatorConnectedView getConnectedProfile(String creatorId, String brandId) {
        // Verify brand has active collaboration with creator
        verifyActiveCollaboration(creatorId, brandId);
        
        CreatorProfile profile = profileRepo.findById(creatorId)
            .orElseThrow(() -> new CreatorNotFoundException(creatorId));
        
        return CreatorConnectedView.builder()
            .publicView(getPublicProfile(profile.getUsername()))
            // Additional connected-only fields
            .email(profile.getUser().getEmail())  // Only for active collabs
            .phone(profile.getUser().getPhone())  // Only for active collabs
            .detailedDemographics(getDemographics(creatorId))
            .contentCalendar(getContentCalendar(creatorId))
            .pastCampaigns(getPastCampaignsWithBrand(creatorId, brandId))
            .build();
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Creator Search Page

```tsx
export function CreatorSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [filters, setFilters] = useState<CreatorFilters>(parseFilters(searchParams));
  
  const { data, isLoading, fetchNextPage, hasNextPage } = useInfiniteQuery({
    queryKey: ['creators', filters],
    queryFn: ({ pageParam = 0 }) => searchCreators({ ...filters, page: pageParam }),
    getNextPageParam: (lastPage) => 
      lastPage.pagination.page < lastPage.pagination.totalPages - 1
        ? lastPage.pagination.page + 1
        : undefined,
  });
  
  return (
    <div className="flex gap-6">
      {/* Sidebar Filters */}
      <aside className="w-64 shrink-0">
        <CreatorFilters
          filters={filters}
          onChange={setFilters}
          facets={data?.pages[0]?.filters?.available}
        />
      </aside>
      
      {/* Results */}
      <main className="flex-1">
        {/* Search Header */}
        <div className="mb-6">
          <SearchInput
            value={filters.q}
            onChange={(q) => setFilters({ ...filters, q })}
            placeholder="Search by name, niche, or location..."
          />
          
          <div className="flex items-center justify-between mt-4">
            <p className="text-muted-foreground">
              {data?.pages[0]?.pagination?.totalElements?.toLocaleString()} creators found
            </p>
            
            <SortSelect
              value={filters.sort}
              onChange={(sort) => setFilters({ ...filters, sort })}
              options={[
                { value: 'relevance', label: 'Most Relevant' },
                { value: 'followers', label: 'Most Followers' },
                { value: 'engagement', label: 'Highest Engagement' },
                { value: 'rate', label: 'Lowest Rate' },
                { value: 'rating', label: 'Highest Rated' },
              ]}
            />
          </div>
        </div>
        
        {/* Results Grid */}
        {isLoading ? (
          <div className="grid grid-cols-3 gap-4">
            {Array(9).fill(0).map((_, i) => (
              <Skeleton key={i} className="h-72" />
            ))}
          </div>
        ) : (
          <>
            <div className="grid grid-cols-3 gap-4">
              {data?.pages.flatMap(page => page.creators).map((creator) => (
                <CreatorCard key={creator.id} creator={creator} />
              ))}
            </div>
            
            {hasNextPage && (
              <div className="mt-6 text-center">
                <Button variant="outline" onClick={() => fetchNextPage()}>
                  Load More
                </Button>
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}
```

### 6.2 Creator Card

```tsx
interface CreatorCardProps {
  creator: CreatorSearchResult;
  showInviteButton?: boolean;
}

export function CreatorCard({ creator, showInviteButton = true }: CreatorCardProps) {
  const router = useRouter();
  
  return (
    <Card className="overflow-hidden hover:shadow-lg transition-shadow">
      {/* Header with photo */}
      <div className="relative h-24 bg-gradient-to-r from-primary/20 to-primary/10">
        <Avatar className="absolute -bottom-8 left-4 h-16 w-16 border-4 border-background">
          <AvatarImage src={creator.profilePhoto} />
          <AvatarFallback>{creator.displayName[0]}</AvatarFallback>
        </Avatar>
        
        {creator.isVerified && (
          <Badge className="absolute top-2 right-2" variant="secondary">
            <BadgeCheck className="h-3 w-3 mr-1" />
            Verified
          </Badge>
        )}
      </div>
      
      <CardContent className="pt-10">
        {/* Name and username */}
        <div className="mb-2">
          <h3 className="font-semibold truncate">{creator.displayName}</h3>
          <p className="text-sm text-muted-foreground">@{creator.username}</p>
        </div>
        
        {/* Categories */}
        <div className="flex flex-wrap gap-1 mb-3">
          {creator.categories.slice(0, 2).map((cat) => (
            <Badge key={cat} variant="outline" className="text-xs">
              {cat}
            </Badge>
          ))}
          {creator.categories.length > 2 && (
            <Badge variant="outline" className="text-xs">
              +{creator.categories.length - 2}
            </Badge>
          )}
        </div>
        
        {/* Stats */}
        <div className="grid grid-cols-2 gap-2 text-center mb-4">
          <div className="p-2 bg-muted rounded">
            <p className="text-lg font-semibold">{formatNumber(creator.stats.followers)}</p>
            <p className="text-xs text-muted-foreground">Followers</p>
          </div>
          <div className="p-2 bg-muted rounded">
            <p className="text-lg font-semibold">{creator.stats.engagementRate}%</p>
            <p className="text-xs text-muted-foreground">Engagement</p>
          </div>
        </div>
        
        {/* Rate */}
        <div className="flex items-center justify-between text-sm mb-4">
          <span className="text-muted-foreground">Starting at</span>
          <span className="font-semibold">
            ₹{Math.min(
              creator.rates.instagramPost || Infinity,
              creator.rates.instagramReel || Infinity
            ).toLocaleString()}
          </span>
        </div>
        
        {/* Platforms */}
        <div className="flex gap-2 mb-4">
          {creator.platforms.includes('INSTAGRAM') && (
            <Instagram className="h-4 w-4 text-pink-500" />
          )}
          {creator.platforms.includes('YOUTUBE') && (
            <Youtube className="h-4 w-4 text-red-500" />
          )}
          {creator.platforms.includes('FACEBOOK') && (
            <Facebook className="h-4 w-4 text-blue-500" />
          )}
        </div>
        
        {/* Actions */}
        <div className="flex gap-2">
          <Button
            variant="outline"
            className="flex-1"
            onClick={() => router.push(`/creators/${creator.username}`)}
          >
            View Profile
          </Button>
          {showInviteButton && creator.acceptingCollabs && (
            <Button className="flex-1">
              Invite
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
```

### 6.3 Creator Filters

```tsx
interface CreatorFiltersProps {
  filters: CreatorFilters;
  onChange: (filters: CreatorFilters) => void;
  facets?: FilterFacets;
}

export function CreatorFilters({ filters, onChange, facets }: CreatorFiltersProps) {
  return (
    <div className="space-y-6">
      {/* Categories */}
      <div>
        <Label className="text-sm font-medium">Category</Label>
        <div className="mt-2 space-y-2">
          {CATEGORIES.map((category) => {
            const count = facets?.categories?.find(c => c.id === category.id)?.count;
            return (
              <label key={category.id} className="flex items-center gap-2">
                <Checkbox
                  checked={filters.categories?.includes(category.id)}
                  onCheckedChange={(checked) => {
                    const cats = filters.categories || [];
                    onChange({
                      ...filters,
                      categories: checked
                        ? [...cats, category.id]
                        : cats.filter(c => c !== category.id)
                    });
                  }}
                />
                <span className="text-sm">{category.label}</span>
                {count !== undefined && (
                  <span className="text-xs text-muted-foreground ml-auto">
                    ({count.toLocaleString()})
                  </span>
                )}
              </label>
            );
          })}
        </div>
      </div>
      
      {/* Followers Range */}
      <div>
        <Label className="text-sm font-medium">Followers</Label>
        <div className="mt-2 space-y-2">
          {FOLLOWER_RANGES.map((range) => (
            <label key={range.value} className="flex items-center gap-2">
              <Checkbox
                checked={filters.followers_min === range.min && filters.followers_max === range.max}
                onCheckedChange={(checked) => {
                  onChange({
                    ...filters,
                    followers_min: checked ? range.min : undefined,
                    followers_max: checked ? range.max : undefined,
                  });
                }}
              />
              <span className="text-sm">{range.label}</span>
            </label>
          ))}
        </div>
      </div>
      
      {/* Engagement Rate */}
      <div>
        <Label className="text-sm font-medium">Engagement Rate</Label>
        <div className="mt-2 space-y-2">
          <RangeSlider
            min={0}
            max={10}
            step={0.5}
            value={[filters.engagement_min || 0, filters.engagement_max || 10]}
            onValueChange={([min, max]) => {
              onChange({
                ...filters,
                engagement_min: min > 0 ? min : undefined,
                engagement_max: max < 10 ? max : undefined,
              });
            }}
          />
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{filters.engagement_min || 0}%</span>
            <span>{filters.engagement_max || 10}%+</span>
          </div>
        </div>
      </div>
      
      {/* Budget/Rate */}
      <div>
        <Label className="text-sm font-medium">Rate Range</Label>
        <div className="mt-2 grid grid-cols-2 gap-2">
          <Input
            type="number"
            placeholder="Min ₹"
            value={filters.rate_min || ''}
            onChange={(e) => onChange({ ...filters, rate_min: parseInt(e.target.value) || undefined })}
          />
          <Input
            type="number"
            placeholder="Max ₹"
            value={filters.rate_max || ''}
            onChange={(e) => onChange({ ...filters, rate_max: parseInt(e.target.value) || undefined })}
          />
        </div>
      </div>
      
      {/* Location */}
      <div>
        <Label className="text-sm font-medium">Location</Label>
        <div className="mt-2">
          <LocationAutocomplete
            value={filters.location}
            onChange={(location) => onChange({ ...filters, location })}
            placeholder="City or state..."
          />
        </div>
      </div>
      
      {/* Languages */}
      <div>
        <Label className="text-sm font-medium">Languages</Label>
        <div className="mt-2 space-y-2">
          {LANGUAGES.map((lang) => (
            <label key={lang.code} className="flex items-center gap-2">
              <Checkbox
                checked={filters.languages?.includes(lang.code)}
                onCheckedChange={(checked) => {
                  const langs = filters.languages || [];
                  onChange({
                    ...filters,
                    languages: checked
                      ? [...langs, lang.code]
                      : langs.filter(l => l !== lang.code)
                  });
                }}
              />
              <span className="text-sm">{lang.name}</span>
            </label>
          ))}
        </div>
      </div>
      
      {/* Connected Platforms */}
      <div>
        <Label className="text-sm font-medium">Platforms</Label>
        <div className="mt-2 space-y-2">
          {[
            { id: 'INSTAGRAM', label: 'Instagram', icon: Instagram },
            { id: 'YOUTUBE', label: 'YouTube', icon: Youtube },
            { id: 'FACEBOOK', label: 'Facebook', icon: Facebook },
          ].map((platform) => (
            <label key={platform.id} className="flex items-center gap-2">
              <Checkbox
                checked={filters.platforms?.includes(platform.id)}
                onCheckedChange={(checked) => {
                  const platforms = filters.platforms || [];
                  onChange({
                    ...filters,
                    platforms: checked
                      ? [...platforms, platform.id]
                      : platforms.filter(p => p !== platform.id)
                  });
                }}
              />
              <platform.icon className="h-4 w-4" />
              <span className="text-sm">{platform.label}</span>
            </label>
          ))}
        </div>
      </div>
      
      {/* Toggles */}
      <div className="space-y-3">
        <label className="flex items-center justify-between">
          <span className="text-sm">Verified only</span>
          <Switch
            checked={filters.verified_only}
            onCheckedChange={(checked) => onChange({ ...filters, verified_only: checked })}
          />
        </label>
        
        <label className="flex items-center justify-between">
          <span className="text-sm">Accepting collabs</span>
          <Switch
            checked={filters.accepting_collabs}
            onCheckedChange={(checked) => onChange({ ...filters, accepting_collabs: checked })}
          />
        </label>
      </div>
      
      {/* Clear Filters */}
      <Button
        variant="ghost"
        className="w-full"
        onClick={() => onChange({})}
      >
        Clear All Filters
      </Button>
    </div>
  );
}
```

### 6.4 Featured Creators Section

```tsx
export function FeaturedCreators() {
  const { data: featured } = useFeaturedCreators();
  
  return (
    <section className="space-y-8">
      {featured?.map((section) => (
        <div key={section.category}>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-bold">{section.title}</h2>
            <Button variant="ghost" size="sm">
              View All <ChevronRight className="h-4 w-4 ml-1" />
            </Button>
          </div>
          
          <div className="grid grid-cols-4 gap-4">
            {section.creators.map((creator) => (
              <CreatorCard key={creator.id} creator={creator} />
            ))}
          </div>
        </div>
      ))}
    </section>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Search Security
- **Rate Limiting:** 60 searches per minute per user, 200 per minute per IP
- **Query Sanitization:** Escape special characters in search queries
- **No SQL Injection:** Use parameterized Elasticsearch queries
- **Result Filtering:** Only return discoverable profiles

### 7.2 Profile Access Control
- **Public Profiles:** Basic info visible to anyone
- **Connected Profiles:** Contact info only to brands with active collaborations
- **Private Profiles:** Not indexed, not searchable

### 7.3 Data Protection
- **PII Masking:** Email/phone never exposed in search results
- **Location Granularity:** City-level only, no exact addresses
- **Opt-Out:** Creators can disable discoverability anytime

### 7.4 Abuse Prevention
- **Scraping Detection:** Flag unusual search patterns
- **Bot Protection:** CAPTCHA after 100 searches without pause
- **Export Limits:** No bulk data export via API

---

## 8. Test Cases (Kavya)

### 8.1 Search Tests

```java
@Test void shouldSearchByName()
@Test void shouldSearchByCategory()
@Test void shouldSearchByLocation()
@Test void shouldFilterByFollowerRange()
@Test void shouldFilterByEngagementRate()
@Test void shouldFilterByRate()
@Test void shouldFilterByPlatform()
@Test void shouldFilterByLanguage()
@Test void shouldCombineMultipleFilters()
@Test void shouldSortByRelevance()
@Test void shouldSortByFollowers()
@Test void shouldSortByEngagement()
@Test void shouldPaginateResults()
@Test void shouldReturnFacets()
```

### 8.2 Profile Tests

```java
@Test void shouldReturnPublicProfile()
@Test void shouldNotReturnNonDiscoverableProfile()
@Test void shouldReturnConnectedProfileWithContactInfo()
@Test void shouldNotExposeContactInfoToNonConnectedBrand()
@Test void shouldReturnPortfolioItems()
@Test void shouldReturnReviews()
@Test void shouldReturnSimilarCreators()
```

### 8.3 Index Tests

```java
@Test void shouldIndexNewCreator()
@Test void shouldUpdateIndexOnProfileChange()
@Test void shouldRemoveFromIndexWhenNotDiscoverable()
@Test void shouldReindexOnStatsUpdate()
@Test void shouldHandleFullReindex()
```

### 8.4 Security Tests

```java
@Test void shouldRateLimitSearches()
@Test void shouldSanitizeSearchQuery()
@Test void shouldNotReturnPrivateProfiles()
@Test void shouldNotExposeEmailInSearchResults()
@Test void shouldBlockExcessiveSearches()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creators/search` | GET | JWT (Brand) | Search and filter creators |
| `/creators/{username}` | GET | Optional | Get public profile |
| `/creators/{username}/connected` | GET | JWT (Brand) | Get connected profile |
| `/creators/featured` | GET | Optional | Get featured creators |
| `/creators/{username}/similar` | GET | Optional | Get similar creators |
| `/creators/suggestions` | POST | JWT (Brand) | AI-powered suggestions |
