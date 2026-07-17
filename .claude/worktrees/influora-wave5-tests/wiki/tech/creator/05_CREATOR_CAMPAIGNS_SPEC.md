# Creator Campaigns Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Campaign Discovery Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CREATOR CAMPAIGN DISCOVERY                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  🎯 Find Campaigns                                                    │   │
│  │  ┌────────────────────────────────────────────────────────────────┐  │   │
│  │  │ Search campaigns...                                            │  │   │
│  │  └────────────────────────────────────────────────────────────────┘  │   │
│  │                                                                       │   │
│  │  Quick Filters:                                                       │   │
│  │  [All] [For You] [Your Niche] [High Budget] [New This Week]          │   │
│  │                                                                       │   │
│  │  Categories:                                                          │   │
│  │  [Fitness] [Fashion] [Beauty] [Tech] [Food] [Travel] [+More]         │   │
│  │                                                                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  💼 Summer Fitness Challenge                            ENDS IN 5 DAYS│   │
│  │  HealthKart                                                           │   │
│  │                                                                       │   │
│  │  Budget: ₹2,00,000 - ₹5,00,000   |   Platform: Instagram, YouTube    │   │
│  │                                                                       │   │
│  │  Looking for fitness creators to promote our new protein range.      │   │
│  │  Reels + Stories required. Must have 50K+ followers.                 │   │
│  │                                                                       │   │
│  │  ⭐ 92% Match   📍 Pan-India   👥 10-15 Creators                     │   │
│  │                                                                       │   │
│  │  [View Details]                                    [Apply Now →]      │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Campaign States (Creator View)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CAMPAIGN STATES FOR CREATOR                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  BROWSABLE STATES:                                                           │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐                              │
│  │  OPEN    │    │ INVITED  │    │ MATCHED  │                              │
│  │  (apply) │    │ (respond)│    │ (AI rec) │                              │
│  └──────────┘    └──────────┘    └──────────┘                              │
│                                                                              │
│  APPLICATION STATES:                                                         │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │ APPLIED  │ →  │SHORTLIST │ →  │NEGOTIAT- │ →  │ ACCEPTED │             │
│  │          │    │   ED     │    │   ING    │    │          │             │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘             │
│       ↓                               ↓                ↓                    │
│  ┌──────────┐                   ┌──────────┐    ┌──────────┐             │
│  │ REJECTED │                   │COUNTER-  │    │CONTRACT  │             │
│  │          │                   │  OFFER   │    │  SENT    │             │
│  └──────────┘                   └──────────┘    └──────────┘             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 Campaign Entity (Read-Only for Creator)

```java
@Entity
@Table(name = "campaigns")
public class Campaign {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "brand_id")
    private BrandProfile brand;
    
    // Basic Info
    private String title;
    private String description;
    private String briefUrl;  // Detailed brief PDF/link
    
    // Categories and targeting
    @Convert(converter = JsonListConverter.class)
    private List<String> categories;  // ["fitness", "health"]
    
    @Convert(converter = JsonListConverter.class)
    private List<String> targetLanguages;
    
    @Convert(converter = JsonListConverter.class)
    private List<String> targetLocations;  // Cities/states
    
    // Budget
    private BigDecimal budgetMin;
    private BigDecimal budgetMax;
    @Enumerated(EnumType.STRING)
    private BudgetType budgetType;  // TOTAL, PER_CREATOR
    
    // Timeline
    private LocalDate applicationDeadline;
    private LocalDate campaignStartDate;
    private LocalDate campaignEndDate;
    
    // Requirements
    @Convert(converter = JsonListConverter.class)
    private List<String> requiredPlatforms;  // ["INSTAGRAM", "YOUTUBE"]
    
    @Convert(converter = JsonListConverter.class)
    private List<DeliverableRequirement> deliverables;
    // [{ type: "INSTAGRAM_REEL", quantity: 2, description: "..." }]
    
    private Integer minFollowers;
    private Double minEngagementRate;
    private Integer creatorsNeeded;
    
    // Status
    @Enumerated(EnumType.STRING)
    private CampaignStatus status;  // DRAFT, OPEN, PAUSED, CLOSED, COMPLETED
    
    // Visibility
    private Boolean isPublic;  // Visible in browse
    private Boolean inviteOnly;  // Only invited creators can apply
    
    // Stats
    private Integer totalApplications;
    private Integer shortlistedCount;
    private Integer acceptedCount;
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
}

public enum CampaignStatus {
    DRAFT,
    OPEN,
    PAUSED,
    CLOSED,
    COMPLETED,
    CANCELLED
}
```

### 3.2 CampaignApplication Entity

```java
@Entity
@Table(name = "campaign_applications")
public class CampaignApplication {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    // Application details
    private String coverLetter;  // Why they're a good fit
    
    @Convert(converter = JsonListConverter.class)
    private List<String> portfolioLinks;  // Relevant past work
    
    // Proposed rates (creator's quote)
    @Convert(converter = JsonMapConverter.class)
    private Map<String, BigDecimal> proposedRates;
    // { "INSTAGRAM_REEL": 25000, "INSTAGRAM_STORY": 5000 }
    
    private BigDecimal totalProposedAmount;
    
    // Timeline proposal
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    
    // Status
    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;
    
    private String rejectionReason;
    private Instant rejectedAt;
    
    // Invitation (if brand invited)
    private Boolean wasInvited;
    private Instant invitedAt;
    private String invitationMessage;
    
    // Match score (AI calculated)
    private Double matchScore;
    
    @Convert(converter = JsonListConverter.class)
    private List<String> matchReasons;
    
    // Timestamps
    private Instant appliedAt;
    private Instant shortlistedAt;
    private Instant updatedAt;
}

public enum ApplicationStatus {
    PENDING,        // Just applied, awaiting review
    SHORTLISTED,    // Brand is interested
    NEGOTIATING,    // In negotiation phase
    ACCEPTED,       // Accepted, awaiting contract
    REJECTED,       // Application rejected
    WITHDRAWN,      // Creator withdrew
    EXPIRED         // Application deadline passed
}
```

### 3.3 CampaignInvitation Entity

```java
@Entity
@Table(name = "campaign_invitations")
public class CampaignInvitation {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    private String message;  // Personal invite message
    
    @Enumerated(EnumType.STRING)
    private InvitationStatus status;  // PENDING, ACCEPTED, DECLINED, EXPIRED
    
    private Instant invitedAt;
    private Instant respondedAt;
    private Instant expiresAt;
    
    private String declineReason;
}
```

---

## 4. API Endpoints

### 4.1 Browse Campaigns

```
GET /api/v1/creator/campaigns
Query Parameters:
  q                   - Search query
  categories[]        - Filter by categories
  platforms[]         - Required platforms
  budget_min          - Minimum budget
  budget_max          - Maximum budget
  min_match_score     - Minimum AI match score
  location            - Target location
  sort                - (deadline, budget, match_score, created_at)
  filter              - (all, for_you, invited, applied)
  page, size          - Pagination

Response:
{
    "campaigns": [
        {
            "id": "camp_xxx",
            "title": "Summer Fitness Challenge",
            "brand": {
                "id": "brand_xxx",
                "name": "HealthKart",
                "logo": "https://...",
                "isVerified": true
            },
            "description": "Looking for fitness creators...",
            "categories": ["fitness", "health"],
            "budget": {
                "min": 200000,
                "max": 500000,
                "type": "TOTAL"
            },
            "deliverables": [
                { "type": "INSTAGRAM_REEL", "quantity": 2 },
                { "type": "INSTAGRAM_STORY", "quantity": 3 }
            ],
            "requirements": {
                "platforms": ["INSTAGRAM"],
                "minFollowers": 50000,
                "minEngagement": 3.0,
                "locations": ["Pan-India"],
                "languages": ["en", "hi"]
            },
            "timeline": {
                "applicationDeadline": "2026-07-15",
                "startDate": "2026-07-20",
                "endDate": "2026-08-15"
            },
            "stats": {
                "creatorsNeeded": 15,
                "applicationsReceived": 45,
                "spotsRemaining": 8
            },
            "matchScore": 0.92,
            "matchReasons": [
                "Your niche matches campaign category",
                "Your engagement rate exceeds requirement",
                "You've worked with similar brands"
            ],
            "applicationStatus": null,  // Or "PENDING", "SHORTLISTED", etc.
            "isInvited": false,
            "daysUntilDeadline": 5,
            "createdAt": "2026-07-01T10:00:00Z"
        }
    ],
    "pagination": {
        "page": 0,
        "size": 20,
        "totalElements": 150,
        "totalPages": 8
    }
}
```

### 4.2 Get Campaign Details

```
GET /api/v1/creator/campaigns/{campaignId}

Response:
{
    "id": "camp_xxx",
    "title": "Summer Fitness Challenge",
    "brand": {
        "id": "brand_xxx",
        "name": "HealthKart",
        "logo": "https://...",
        "description": "India's leading health supplement brand...",
        "website": "https://healthkart.com",
        "isVerified": true,
        "pastCampaigns": 24,
        "avgRating": 4.7
    },
    "description": "Looking for fitness creators to promote our new protein range...",
    "brief": {
        "objectives": [
            "Increase brand awareness among fitness enthusiasts",
            "Drive traffic to product page"
        ],
        "keyMessages": [
            "100% natural ingredients",
            "No added sugar"
        ],
        "dos": [
            "Showcase product in workout setting",
            "Share personal experience"
        ],
        "donts": [
            "Make medical claims",
            "Compare with competitors"
        ],
        "contentGuidelines": "...",
        "sampleContent": ["https://instagram.com/p/xxx"],
        "productInfo": "https://healthkart.com/protein-powder"
    },
    "categories": ["fitness", "health"],
    "budget": {
        "min": 200000,
        "max": 500000,
        "type": "TOTAL",
        "perCreatorEstimate": {
            "min": 15000,
            "max": 35000
        }
    },
    "deliverables": [
        {
            "type": "INSTAGRAM_REEL",
            "quantity": 2,
            "description": "60-90 second workout reel featuring product",
            "requirements": {
                "minDuration": 60,
                "maxDuration": 90,
                "mustInclude": ["product visible", "workout content"]
            }
        },
        {
            "type": "INSTAGRAM_STORY",
            "quantity": 3,
            "description": "Story sequence with swipe-up link"
        }
    ],
    "requirements": {
        "platforms": ["INSTAGRAM"],
        "minFollowers": 50000,
        "minEngagement": 3.0,
        "locations": ["Pan-India"],
        "languages": ["en", "hi"],
        "ageRange": { "min": 21, "max": null },
        "contentType": ["fitness", "workout", "healthy lifestyle"]
    },
    "timeline": {
        "applicationDeadline": "2026-07-15T23:59:59Z",
        "selectionBy": "2026-07-18",
        "contractSigningBy": "2026-07-20",
        "contentCreation": {
            "start": "2026-07-21",
            "end": "2026-08-01"
        },
        "posting": {
            "start": "2026-08-05",
            "end": "2026-08-15"
        }
    },
    "payment": {
        "escrowRequired": true,
        "milestones": [
            { "name": "Content Approval", "percentage": 50 },
            { "name": "Final Metrics", "percentage": 50 }
        ],
        "paymentTerms": "Released within 7 days of milestone completion"
    },
    "stats": {
        "creatorsNeeded": 15,
        "applicationsReceived": 45,
        "shortlisted": 20,
        "accepted": 7,
        "spotsRemaining": 8
    },
    "matchScore": 0.92,
    "matchAnalysis": {
        "score": 0.92,
        "breakdown": {
            "nicheMatch": 0.95,
            "audienceMatch": 0.88,
            "engagementMatch": 0.90,
            "rateMatch": 0.85,
            "pastPerformance": 0.92
        },
        "reasons": [
            "Your fitness content aligns perfectly with campaign goals",
            "Your engagement rate (4.2%) exceeds requirement (3.0%)",
            "You've successfully completed similar campaigns"
        ],
        "suggestions": [
            "Include more workout content in your application"
        ]
    },
    "yourEligibility": {
        "isEligible": true,
        "checks": {
            "platformConnected": true,
            "followerRequirement": true,
            "engagementRequirement": true,
            "locationMatch": true,
            "categoryMatch": true
        }
    },
    "application": null,  // Or application details if already applied
    "invitation": null,   // Or invitation details if invited
    "relatedCampaigns": [...],
    "createdAt": "2026-07-01T10:00:00Z"
}
```

### 4.3 Apply to Campaign

```
POST /api/v1/creator/campaigns/{campaignId}/apply
{
    "coverLetter": "I'm excited to apply for this campaign...",
    "portfolioLinks": [
        "https://instagram.com/p/xxx",
        "https://youtube.com/watch?v=xxx"
    ],
    "proposedRates": {
        "INSTAGRAM_REEL": 25000,
        "INSTAGRAM_STORY": 5000
    },
    "totalProposedAmount": 55000,
    "proposedStartDate": "2026-07-22",
    "proposedEndDate": "2026-08-10",
    "availability": "Full availability during campaign period",
    "additionalNotes": "I've worked with similar brands before..."
}

Response:
{
    "applicationId": "app_xxx",
    "status": "PENDING",
    "appliedAt": "2026-07-07T14:30:00Z",
    "message": "Application submitted successfully!"
}
```

### 4.4 Get My Applications

```
GET /api/v1/creator/applications
Query Parameters:
  status    - Filter by status (PENDING, SHORTLISTED, ACCEPTED, etc.)
  sort      - (applied_at, status, deadline)
  page, size

Response:
{
    "applications": [
        {
            "id": "app_xxx",
            "campaign": {
                "id": "camp_xxx",
                "title": "Summer Fitness Challenge",
                "brand": {...},
                "deadline": "2026-07-15"
            },
            "status": "SHORTLISTED",
            "proposedAmount": 55000,
            "appliedAt": "2026-07-05T10:00:00Z",
            "statusUpdatedAt": "2026-07-06T15:00:00Z",
            "nextAction": "Await brand response or counter-offer"
        }
    ]
}
```

### 4.5 Withdraw Application

```
DELETE /api/v1/creator/applications/{applicationId}
{
    "reason": "Schedule conflict"
}

Response:
{
    "success": true,
    "message": "Application withdrawn"
}
```

### 4.6 Respond to Invitation

```
POST /api/v1/creator/invitations/{invitationId}/respond
{
    "action": "ACCEPT",  // or "DECLINE"
    "message": "Thank you for the invitation!",
    "declineReason": null  // Required if declining
}
```

---

## 5. Backend Implementation

### 5.1 Campaign Browse Service

```java
@Service
public class CreatorCampaignService {
    
    private final CampaignRepository campaignRepo;
    private final ApplicationRepository applicationRepo;
    private final CampaignMatchingService matchingService;
    
    public Page<CampaignListView> browseCampaigns(
        String creatorId,
        CampaignBrowseRequest request
    ) {
        CreatorProfile creator = creatorRepo.findById(creatorId)
            .orElseThrow(() -> new CreatorNotFoundException(creatorId));
        
        // Build base query
        Specification<Campaign> spec = Specification.where(
            CampaignSpecs.isOpen()
            .and(CampaignSpecs.isPublicOrInvited(creatorId))
            .and(CampaignSpecs.deadlineNotPassed())
        );
        
        // Apply filters
        if (request.getCategories() != null) {
            spec = spec.and(CampaignSpecs.hasCategories(request.getCategories()));
        }
        
        if (request.getPlatforms() != null) {
            spec = spec.and(CampaignSpecs.requiresPlatforms(request.getPlatforms()));
        }
        
        if (request.getBudgetMin() != null) {
            spec = spec.and(CampaignSpecs.budgetAtLeast(request.getBudgetMin()));
        }
        
        if (request.getLocation() != null) {
            spec = spec.and(CampaignSpecs.targetsLocation(request.getLocation()));
        }
        
        // Filter by creator eligibility
        if ("for_you".equals(request.getFilter())) {
            spec = spec.and(CampaignSpecs.creatorEligible(creator));
        }
        
        if ("invited".equals(request.getFilter())) {
            spec = spec.and(CampaignSpecs.hasInvitation(creatorId));
        }
        
        // Fetch campaigns
        Page<Campaign> campaigns = campaignRepo.findAll(
            spec,
            PageRequest.of(request.getPage(), request.getSize(), buildSort(request))
        );
        
        // Enhance with match scores and application status
        return campaigns.map(campaign -> {
            CampaignListView view = mapper.toListView(campaign);
            
            // Calculate match score
            MatchResult match = matchingService.calculateMatch(creator, campaign);
            view.setMatchScore(match.getScore());
            view.setMatchReasons(match.getReasons());
            
            // Check application status
            applicationRepo.findByCreatorIdAndCampaignId(creatorId, campaign.getId())
                .ifPresent(app -> view.setApplicationStatus(app.getStatus()));
            
            // Check invitation
            invitationRepo.findByCreatorIdAndCampaignId(creatorId, campaign.getId())
                .ifPresent(inv -> view.setIsInvited(true));
            
            return view;
        });
    }
}
```

### 5.2 Campaign Matching Service

```java
@Service
public class CampaignMatchingService {
    
    private final OpenAiClient aiClient;
    
    public MatchResult calculateMatch(CreatorProfile creator, Campaign campaign) {
        double score = 0;
        List<String> reasons = new ArrayList<>();
        Map<String, Double> breakdown = new HashMap<>();
        
        // 1. Niche/Category Match (25%)
        double nicheScore = calculateNicheMatch(creator.getCategories(), campaign.getCategories());
        breakdown.put("nicheMatch", nicheScore);
        score += nicheScore * 0.25;
        if (nicheScore > 0.8) {
            reasons.add("Your content niche aligns well with campaign goals");
        }
        
        // 2. Audience Match (20%)
        double audienceScore = calculateAudienceMatch(creator, campaign);
        breakdown.put("audienceMatch", audienceScore);
        score += audienceScore * 0.20;
        
        // 3. Engagement Rate (20%)
        double engagementScore = 0;
        if (creator.getAvgEngagementRate() >= campaign.getMinEngagementRate()) {
            engagementScore = Math.min(1.0, creator.getAvgEngagementRate() / (campaign.getMinEngagementRate() * 2));
            reasons.add(String.format("Your engagement rate (%.1f%%) exceeds requirement (%.1f%%)",
                creator.getAvgEngagementRate(), campaign.getMinEngagementRate()));
        }
        breakdown.put("engagementMatch", engagementScore);
        score += engagementScore * 0.20;
        
        // 4. Rate Match (15%)
        double rateScore = calculateRateMatch(creator, campaign);
        breakdown.put("rateMatch", rateScore);
        score += rateScore * 0.15;
        
        // 5. Past Performance (20%)
        double performanceScore = calculatePastPerformance(creator, campaign);
        breakdown.put("pastPerformance", performanceScore);
        score += performanceScore * 0.20;
        if (performanceScore > 0.8) {
            reasons.add("You've successfully completed similar campaigns");
        }
        
        return MatchResult.builder()
            .score(score)
            .breakdown(breakdown)
            .reasons(reasons)
            .build();
    }
    
    private double calculateNicheMatch(List<String> creatorCategories, List<String> campaignCategories) {
        if (creatorCategories == null || campaignCategories == null) return 0;
        
        long matches = creatorCategories.stream()
            .filter(campaignCategories::contains)
            .count();
        
        return (double) matches / campaignCategories.size();
    }
}
```

### 5.3 Application Service

```java
@Service
public class CampaignApplicationService {
    
    private final ApplicationRepository applicationRepo;
    private final CampaignRepository campaignRepo;
    private final NotificationService notificationService;
    
    @Transactional
    public CampaignApplication applyToCampaign(
        String creatorId,
        String campaignId,
        ApplicationRequest request
    ) {
        Campaign campaign = campaignRepo.findById(campaignId)
            .orElseThrow(() -> new CampaignNotFoundException(campaignId));
        
        // Validate campaign is open
        if (campaign.getStatus() != CampaignStatus.OPEN) {
            throw new CampaignNotOpenException(campaignId);
        }
        
        // Check deadline
        if (campaign.getApplicationDeadline().isBefore(LocalDate.now())) {
            throw new ApplicationDeadlinePassedException(campaignId);
        }
        
        // Check if already applied
        if (applicationRepo.existsByCreatorIdAndCampaignId(creatorId, campaignId)) {
            throw new AlreadyAppliedException(campaignId);
        }
        
        // Validate eligibility
        CreatorProfile creator = creatorRepo.findById(creatorId).orElseThrow();
        validateEligibility(creator, campaign);
        
        // Create application
        CampaignApplication application = CampaignApplication.builder()
            .id(Ulids.generate())
            .campaign(campaign)
            .creator(creator)
            .coverLetter(request.getCoverLetter())
            .portfolioLinks(request.getPortfolioLinks())
            .proposedRates(request.getProposedRates())
            .totalProposedAmount(request.getTotalProposedAmount())
            .proposedStartDate(request.getProposedStartDate())
            .proposedEndDate(request.getProposedEndDate())
            .status(ApplicationStatus.PENDING)
            .wasInvited(invitationRepo.existsByCreatorIdAndCampaignId(creatorId, campaignId))
            .appliedAt(Instant.now())
            .build();
        
        // Calculate match score
        MatchResult match = matchingService.calculateMatch(creator, campaign);
        application.setMatchScore(match.getScore());
        application.setMatchReasons(match.getReasons());
        
        applicationRepo.save(application);
        
        // Update campaign stats
        campaign.setTotalApplications(campaign.getTotalApplications() + 1);
        campaignRepo.save(campaign);
        
        // Notify brand
        notificationService.notifyBrand(campaign.getBrand().getId(),
            NotificationType.NEW_APPLICATION,
            Map.of("campaignId", campaignId, "creatorName", creator.getDisplayName())
        );
        
        return application;
    }
    
    private void validateEligibility(CreatorProfile creator, Campaign campaign) {
        List<String> issues = new ArrayList<>();
        
        // Check follower requirement
        if (creator.getTotalFollowers() < campaign.getMinFollowers()) {
            issues.add("Minimum followers requirement not met");
        }
        
        // Check engagement rate
        if (creator.getAvgEngagementRate() < campaign.getMinEngagementRate()) {
            issues.add("Minimum engagement rate requirement not met");
        }
        
        // Check platform connection
        List<String> connectedPlatforms = getConnectedPlatforms(creator.getId());
        if (!connectedPlatforms.containsAll(campaign.getRequiredPlatforms())) {
            issues.add("Required platform not connected");
        }
        
        if (!issues.isEmpty()) {
            throw new EligibilityNotMetException(issues);
        }
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Campaign Browse Page

```tsx
export function CampaignBrowsePage() {
  const [filter, setFilter] = useState('for_you');
  const [categories, setCategories] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  
  const { data, isLoading, fetchNextPage, hasNextPage } = useInfiniteQuery({
    queryKey: ['campaigns', filter, categories, searchQuery],
    queryFn: ({ pageParam = 0 }) => fetchCampaigns({
      filter,
      categories,
      q: searchQuery,
      page: pageParam,
    }),
    getNextPageParam: (lastPage) =>
      lastPage.pagination.page < lastPage.pagination.totalPages - 1
        ? lastPage.pagination.page + 1
        : undefined,
  });
  
  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold">Find Campaigns</h1>
        <p className="text-muted-foreground">
          Discover brand campaigns that match your profile
        </p>
      </div>
      
      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="Search campaigns..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="pl-10"
        />
      </div>
      
      {/* Quick Filters */}
      <div className="flex gap-2">
        {[
          { id: 'for_you', label: 'For You', icon: Sparkles },
          { id: 'all', label: 'All Campaigns', icon: Grid },
          { id: 'invited', label: 'Invited', icon: Mail },
          { id: 'high_budget', label: 'High Budget', icon: DollarSign },
        ].map((f) => (
          <Button
            key={f.id}
            variant={filter === f.id ? 'default' : 'outline'}
            size="sm"
            onClick={() => setFilter(f.id)}
          >
            <f.icon className="h-4 w-4 mr-1" />
            {f.label}
          </Button>
        ))}
      </div>
      
      {/* Category Pills */}
      <div className="flex flex-wrap gap-2">
        {CATEGORIES.map((cat) => (
          <Badge
            key={cat.id}
            variant={categories.includes(cat.id) ? 'default' : 'outline'}
            className="cursor-pointer"
            onClick={() => {
              setCategories(
                categories.includes(cat.id)
                  ? categories.filter(c => c !== cat.id)
                  : [...categories, cat.id]
              );
            }}
          >
            {cat.label}
          </Badge>
        ))}
      </div>
      
      {/* Campaign List */}
      {isLoading ? (
        <div className="space-y-4">
          {Array(3).fill(0).map((_, i) => (
            <Skeleton key={i} className="h-48" />
          ))}
        </div>
      ) : (
        <div className="space-y-4">
          {data?.pages.flatMap(page => page.campaigns).map((campaign) => (
            <CampaignCard key={campaign.id} campaign={campaign} />
          ))}
          
          {hasNextPage && (
            <Button
              variant="outline"
              className="w-full"
              onClick={() => fetchNextPage()}
            >
              Load More
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
```

### 6.2 Campaign Card

```tsx
interface CampaignCardProps {
  campaign: CampaignListView;
}

export function CampaignCard({ campaign }: CampaignCardProps) {
  const router = useRouter();
  const daysLeft = campaign.daysUntilDeadline;
  
  return (
    <Card className="hover:shadow-md transition-shadow">
      <CardContent className="p-6">
        <div className="flex gap-6">
          {/* Brand Logo */}
          <Avatar className="h-16 w-16 rounded-lg">
            <AvatarImage src={campaign.brand.logo} />
            <AvatarFallback>{campaign.brand.name[0]}</AvatarFallback>
          </Avatar>
          
          <div className="flex-1 min-w-0">
            {/* Header */}
            <div className="flex items-start justify-between mb-2">
              <div>
                <h3 className="font-semibold text-lg">{campaign.title}</h3>
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <span>{campaign.brand.name}</span>
                  {campaign.brand.isVerified && (
                    <BadgeCheck className="h-4 w-4 text-blue-500" />
                  )}
                </div>
              </div>
              
              {/* Deadline Badge */}
              <Badge variant={daysLeft <= 3 ? 'destructive' : 'secondary'}>
                {daysLeft <= 0 ? 'Deadline passed' : `${daysLeft} days left`}
              </Badge>
            </div>
            
            {/* Description */}
            <p className="text-sm text-muted-foreground mb-3 line-clamp-2">
              {campaign.description}
            </p>
            
            {/* Stats Row */}
            <div className="flex flex-wrap gap-4 text-sm mb-3">
              <div className="flex items-center gap-1">
                <DollarSign className="h-4 w-4 text-green-500" />
                <span>
                  {formatCurrency(campaign.budget.min)} - {formatCurrency(campaign.budget.max)}
                </span>
              </div>
              
              <div className="flex items-center gap-1">
                {campaign.deliverables.map((d, i) => (
                  <span key={i} className="flex items-center gap-1">
                    <PlatformIcon platform={d.type.split('_')[0]} className="h-4 w-4" />
                    {d.quantity}x {d.type.split('_')[1]}
                  </span>
                ))}
              </div>
              
              <div className="flex items-center gap-1">
                <Users className="h-4 w-4" />
                <span>{campaign.stats.spotsRemaining} spots left</span>
              </div>
              
              <div className="flex items-center gap-1">
                <MapPin className="h-4 w-4" />
                <span>{campaign.requirements.locations.join(', ')}</span>
              </div>
            </div>
            
            {/* Match Score */}
            {campaign.matchScore > 0 && (
              <div className="flex items-center gap-2 mb-3">
                <div className="flex items-center gap-1">
                  <Sparkles className="h-4 w-4 text-amber-500" />
                  <span className="text-sm font-medium">
                    {Math.round(campaign.matchScore * 100)}% Match
                  </span>
                </div>
                <div className="flex gap-1">
                  {campaign.matchReasons.slice(0, 2).map((reason, i) => (
                    <Badge key={i} variant="outline" className="text-xs">
                      {reason}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
            
            {/* Actions */}
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                onClick={() => router.push(`/creator/campaigns/${campaign.id}`)}
              >
                View Details
              </Button>
              
              {campaign.applicationStatus ? (
                <Badge variant="secondary">
                  {APPLICATION_STATUS_LABELS[campaign.applicationStatus]}
                </Badge>
              ) : campaign.isInvited ? (
                <Button>
                  <Mail className="h-4 w-4 mr-2" />
                  Respond to Invite
                </Button>
              ) : (
                <Button>
                  Apply Now
                  <ArrowRight className="h-4 w-4 ml-2" />
                </Button>
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
```

### 6.3 Campaign Detail Page

```tsx
export function CampaignDetailPage({ campaignId }: { campaignId: string }) {
  const { data: campaign, isLoading } = useCampaign(campaignId);
  const [applyOpen, setApplyOpen] = useState(false);
  
  if (isLoading) return <CampaignDetailSkeleton />;
  if (!campaign) return <NotFound />;
  
  return (
    <div className="max-w-4xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex items-start gap-6">
        <Avatar className="h-20 w-20 rounded-lg">
          <AvatarImage src={campaign.brand.logo} />
        </Avatar>
        
        <div className="flex-1">
          <h1 className="text-2xl font-bold">{campaign.title}</h1>
          <div className="flex items-center gap-2 text-muted-foreground">
            <span>{campaign.brand.name}</span>
            {campaign.brand.isVerified && (
              <BadgeCheck className="h-5 w-5 text-blue-500" />
            )}
            <span>•</span>
            <span>{campaign.brand.pastCampaigns} past campaigns</span>
            <span>•</span>
            <span>{campaign.brand.avgRating} rating</span>
          </div>
        </div>
        
        <div className="text-right">
          <Badge variant={campaign.daysUntilDeadline <= 3 ? 'destructive' : 'default'}>
            {campaign.daysUntilDeadline} days left to apply
          </Badge>
        </div>
      </div>
      
      {/* Match Score Card */}
      {campaign.matchScore > 0 && (
        <Card className="bg-gradient-to-r from-amber-50 to-orange-50 border-amber-200">
          <CardContent className="p-4">
            <div className="flex items-center gap-4">
              <div className="text-center">
                <div className="text-3xl font-bold text-amber-600">
                  {Math.round(campaign.matchScore * 100)}%
                </div>
                <div className="text-sm text-amber-700">Match</div>
              </div>
              
              <div className="flex-1">
                <p className="font-medium text-amber-900 mb-2">Why this is a great fit:</p>
                <ul className="space-y-1">
                  {campaign.matchAnalysis.reasons.map((reason, i) => (
                    <li key={i} className="text-sm text-amber-800 flex items-center gap-2">
                      <Check className="h-4 w-4" />
                      {reason}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          </CardContent>
        </Card>
      )}
      
      {/* Main Content */}
      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          {/* Description */}
          <Card>
            <CardHeader>
              <CardTitle>About This Campaign</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="whitespace-pre-wrap">{campaign.description}</p>
            </CardContent>
          </Card>
          
          {/* Brief */}
          <Card>
            <CardHeader>
              <CardTitle>Campaign Brief</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <h4 className="font-medium mb-2">Objectives</h4>
                <ul className="list-disc pl-5 space-y-1">
                  {campaign.brief.objectives.map((obj, i) => (
                    <li key={i}>{obj}</li>
                  ))}
                </ul>
              </div>
              
              <div>
                <h4 className="font-medium mb-2">Key Messages</h4>
                <ul className="list-disc pl-5 space-y-1">
                  {campaign.brief.keyMessages.map((msg, i) => (
                    <li key={i}>{msg}</li>
                  ))}
                </ul>
              </div>
              
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <h4 className="font-medium mb-2 text-green-600">Do's</h4>
                  <ul className="space-y-1">
                    {campaign.brief.dos.map((item, i) => (
                      <li key={i} className="flex items-center gap-2 text-sm">
                        <Check className="h-4 w-4 text-green-500" />
                        {item}
                      </li>
                    ))}
                  </ul>
                </div>
                <div>
                  <h4 className="font-medium mb-2 text-red-600">Don'ts</h4>
                  <ul className="space-y-1">
                    {campaign.brief.donts.map((item, i) => (
                      <li key={i} className="flex items-center gap-2 text-sm">
                        <X className="h-4 w-4 text-red-500" />
                        {item}
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </CardContent>
          </Card>
          
          {/* Deliverables */}
          <Card>
            <CardHeader>
              <CardTitle>Required Deliverables</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                {campaign.deliverables.map((deliverable, i) => (
                  <div key={i} className="flex items-start gap-4 p-4 bg-muted rounded-lg">
                    <div className="h-10 w-10 rounded-full bg-primary/10 flex items-center justify-center">
                      <PlatformIcon platform={deliverable.type.split('_')[0]} />
                    </div>
                    <div>
                      <h4 className="font-medium">
                        {deliverable.quantity}x {formatDeliverableType(deliverable.type)}
                      </h4>
                      <p className="text-sm text-muted-foreground">
                        {deliverable.description}
                      </p>
                      {deliverable.requirements && (
                        <div className="flex gap-2 mt-2">
                          {deliverable.requirements.mustInclude?.map((req, j) => (
                            <Badge key={j} variant="outline" className="text-xs">
                              {req}
                            </Badge>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>
        
        {/* Sidebar */}
        <div className="space-y-4">
          {/* Apply Card */}
          <Card className="sticky top-4">
            <CardContent className="p-4 space-y-4">
              <div>
                <p className="text-sm text-muted-foreground">Budget</p>
                <p className="text-xl font-bold">
                  {formatCurrency(campaign.budget.min)} - {formatCurrency(campaign.budget.max)}
                </p>
                <p className="text-xs text-muted-foreground">
                  Est. {formatCurrency(campaign.budget.perCreatorEstimate.min)} - {formatCurrency(campaign.budget.perCreatorEstimate.max)} per creator
                </p>
              </div>
              
              <Separator />
              
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Creators needed</span>
                  <span>{campaign.stats.creatorsNeeded}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Applications</span>
                  <span>{campaign.stats.applicationsReceived}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Spots remaining</span>
                  <span className="font-medium text-green-600">{campaign.stats.spotsRemaining}</span>
                </div>
              </div>
              
              <Separator />
              
              {/* Eligibility Check */}
              <div>
                <p className="text-sm font-medium mb-2">Your Eligibility</p>
                <div className="space-y-1">
                  {Object.entries(campaign.yourEligibility.checks).map(([key, passed]) => (
                    <div key={key} className="flex items-center gap-2 text-sm">
                      {passed ? (
                        <Check className="h-4 w-4 text-green-500" />
                      ) : (
                        <X className="h-4 w-4 text-red-500" />
                      )}
                      <span>{ELIGIBILITY_LABELS[key]}</span>
                    </div>
                  ))}
                </div>
              </div>
              
              <Button
                className="w-full"
                size="lg"
                disabled={!campaign.yourEligibility.isEligible}
                onClick={() => setApplyOpen(true)}
              >
                Apply Now
              </Button>
              
              <p className="text-xs text-center text-muted-foreground">
                Deadline: {formatDate(campaign.timeline.applicationDeadline)}
              </p>
            </CardContent>
          </Card>
          
          {/* Timeline */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Timeline</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <TimelineItem
                label="Application Deadline"
                date={campaign.timeline.applicationDeadline}
              />
              <TimelineItem
                label="Selection By"
                date={campaign.timeline.selectionBy}
              />
              <TimelineItem
                label="Content Creation"
                date={`${formatDate(campaign.timeline.contentCreation.start)} - ${formatDate(campaign.timeline.contentCreation.end)}`}
              />
              <TimelineItem
                label="Posting Period"
                date={`${formatDate(campaign.timeline.posting.start)} - ${formatDate(campaign.timeline.posting.end)}`}
              />
            </CardContent>
          </Card>
        </div>
      </div>
      
      {/* Apply Modal */}
      <ApplyModal
        campaign={campaign}
        open={applyOpen}
        onClose={() => setApplyOpen(false)}
      />
    </div>
  );
}
```

### 6.4 Apply Modal

```tsx
export function ApplyModal({
  campaign,
  open,
  onClose,
}: {
  campaign: CampaignDetail;
  open: boolean;
  onClose: () => void;
}) {
  const [step, setStep] = useState(1);
  const [formData, setFormData] = useState<ApplicationForm>({
    coverLetter: '',
    portfolioLinks: [],
    proposedRates: {},
    totalProposedAmount: 0,
  });
  
  const { mutate: apply, isLoading } = useMutation({
    mutationFn: (data: ApplicationForm) =>
      submitApplication(campaign.id, data),
    onSuccess: () => {
      toast.success('Application submitted!');
      onClose();
    },
  });
  
  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Apply to {campaign.title}</DialogTitle>
        </DialogHeader>
        
        {/* Step Indicator */}
        <div className="flex gap-2 mb-6">
          {['Cover Letter', 'Portfolio', 'Rates'].map((label, i) => (
            <div
              key={i}
              className={cn(
                "flex-1 h-1 rounded",
                i + 1 <= step ? "bg-primary" : "bg-muted"
              )}
            />
          ))}
        </div>
        
        {step === 1 && (
          <div className="space-y-4">
            <div>
              <Label>Why are you a great fit for this campaign?</Label>
              <Textarea
                value={formData.coverLetter}
                onChange={(e) => setFormData({ ...formData, coverLetter: e.target.value })}
                placeholder="Tell the brand why you're perfect for this campaign..."
                rows={6}
              />
              <p className="text-xs text-muted-foreground mt-1">
                {formData.coverLetter.length}/1000 characters
              </p>
            </div>
          </div>
        )}
        
        {step === 2 && (
          <div className="space-y-4">
            <Label>Add relevant portfolio links</Label>
            <p className="text-sm text-muted-foreground">
              Share links to similar work you've done
            </p>
            
            {formData.portfolioLinks.map((link, i) => (
              <div key={i} className="flex gap-2">
                <Input
                  value={link}
                  onChange={(e) => {
                    const links = [...formData.portfolioLinks];
                    links[i] = e.target.value;
                    setFormData({ ...formData, portfolioLinks: links });
                  }}
                  placeholder="https://instagram.com/p/..."
                />
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => {
                    const links = formData.portfolioLinks.filter((_, j) => j !== i);
                    setFormData({ ...formData, portfolioLinks: links });
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            
            <Button
              variant="outline"
              onClick={() => {
                setFormData({
                  ...formData,
                  portfolioLinks: [...formData.portfolioLinks, '']
                });
              }}
            >
              <Plus className="h-4 w-4 mr-2" />
              Add Link
            </Button>
          </div>
        )}
        
        {step === 3 && (
          <div className="space-y-4">
            <Label>Your proposed rates</Label>
            <p className="text-sm text-muted-foreground mb-4">
              Based on the deliverables required, enter your rates
            </p>
            
            {campaign.deliverables.map((deliverable) => (
              <div key={deliverable.type} className="flex items-center gap-4">
                <div className="flex-1">
                  <p className="font-medium">
                    {deliverable.quantity}x {formatDeliverableType(deliverable.type)}
                  </p>
                </div>
                <div className="w-40">
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
                      
                    </span>
                    <Input
                      type="number"
                      className="pl-6"
                      value={formData.proposedRates[deliverable.type] || ''}
                      onChange={(e) => {
                        const rates = {
                          ...formData.proposedRates,
                          [deliverable.type]: parseInt(e.target.value) || 0
                        };
                        const total = Object.values(rates).reduce((a, b) => a + b, 0);
                        setFormData({ ...formData, proposedRates: rates, totalProposedAmount: total });
                      }}
                    />
                  </div>
                </div>
              </div>
            ))}
            
            <Separator />
            
            <div className="flex justify-between text-lg font-semibold">
              <span>Total</span>
              <span>{formatCurrency(formData.totalProposedAmount)}</span>
            </div>
            
            <p className="text-xs text-muted-foreground">
              Campaign budget: {formatCurrency(campaign.budget.perCreatorEstimate.min)} - {formatCurrency(campaign.budget.perCreatorEstimate.max)} per creator
            </p>
          </div>
        )}
        
        <DialogFooter>
          {step > 1 && (
            <Button variant="outline" onClick={() => setStep(step - 1)}>
              Back
            </Button>
          )}
          
          {step < 3 ? (
            <Button onClick={() => setStep(step + 1)}>
              Continue
            </Button>
          ) : (
            <Button onClick={() => apply(formData)} disabled={isLoading}>
              {isLoading ? <Spinner /> : 'Submit Application'}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Campaign Access Control
- **Public Campaigns:** Visible to all authenticated creators
- **Invite-Only Campaigns:** Only visible to invited creators
- **Brand Privacy:** Brand contact info never exposed in campaign listings

### 7.2 Application Security
- **Rate Limiting:** Max 10 applications per hour per creator
- **Spam Prevention:** Minimum 100 character cover letter
- **Duplicate Prevention:** One application per campaign per creator

### 7.3 Data Protection
- **Portfolio Links:** Validated as URLs, no script injection
- **Rates:** Server-side validation against min/max bounds
- **Cover Letters:** Sanitized for XSS

---

## 8. Test Cases (Kavya)

```java
// Browse Tests
@Test void shouldListOpenCampaigns()
@Test void shouldFilterByCategory()
@Test void shouldFilterByBudget()
@Test void shouldSortByDeadline()
@Test void shouldCalculateMatchScore()
@Test void shouldShowInvitedCampaigns()
@Test void shouldNotShowClosedCampaigns()

// Application Tests
@Test void shouldApplyToCampaign()
@Test void shouldRejectDuplicateApplication()
@Test void shouldRejectAfterDeadline()
@Test void shouldRejectIneligibleCreator()
@Test void shouldWithdrawApplication()
@Test void shouldTrackApplicationStatus()

// Invitation Tests
@Test void shouldShowInvitedCampaignDetails()
@Test void shouldAcceptInvitation()
@Test void shouldDeclineInvitation()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/campaigns` | GET | JWT | Browse campaigns |
| `/creator/campaigns/{id}` | GET | JWT | Get campaign details |
| `/creator/campaigns/{id}/apply` | POST | JWT | Apply to campaign |
| `/creator/applications` | GET | JWT | List my applications |
| `/creator/applications/{id}` | DELETE | JWT | Withdraw application |
| `/creator/invitations/{id}/respond` | POST | JWT | Respond to invitation |
