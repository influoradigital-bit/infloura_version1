# Creator Deliverables Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Deliverables Flow Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DELIVERABLE SUBMISSION FLOW                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │ Create   │ →  │ Upload   │ →  │ Submit   │ →  │ Brand    │             │
│  │ Content  │    │ Draft    │    │ for      │    │ Reviews  │             │
│  │          │    │          │    │ Review   │    │          │             │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘             │
│                                                       │                      │
│                         ┌─────────────────────────────┼───────────────┐      │
│                         ↓                             ↓               ↓      │
│                  ┌──────────┐               ┌──────────┐      ┌──────────┐ │
│                  │ Approved │               │ Revision │      │ Rejected │ │
│                  │          │               │ Requested│      │          │ │
│                  └──────────┘               └──────────┘      └──────────┘ │
│                         │                         │                         │
│                         ↓                         ↓                         │
│                  ┌──────────┐               ┌──────────┐                   │
│                  │ Post     │ ←───────────  │ Submit   │                   │
│                  │ Live     │               │ Revision │                   │
│                  └──────────┘               └──────────┘                   │
│                         │                                                    │
│                         ↓                                                    │
│                  ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│                  │ Report   │ →  │ Verify   │ →  │ Milestone│             │
│                  │ Metrics  │    │ Metrics  │    │ Complete │             │
│                  └──────────┘    └──────────┘    └──────────┘             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Deliverable States

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DELIVERABLE STATE MACHINE                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐                                                               │
│  │ PENDING  │ ──→ Creator needs to create & upload                         │
│  └──────────┘                                                               │
│       │                                                                      │
│       ↓                                                                      │
│  ┌──────────┐                                                               │
│  │ DRAFT    │ ──→ Content uploaded, not yet submitted                      │
│  └──────────┘                                                               │
│       │                                                                      │
│       ↓                                                                      │
│  ┌──────────┐                                                               │
│  │ SUBMITTED│ ──→ Awaiting brand review                                    │
│  └──────────┘                                                               │
│       │                                                                      │
│       ├──────→ APPROVED ──→ Ready to post                                  │
│       │                                                                      │
│       ├──────→ REVISION_REQUESTED ──→ Needs changes                        │
│       │              │                                                       │
│       │              ↓                                                       │
│       │         RESUBMITTED ──→ Back to review                             │
│       │                                                                      │
│       └──────→ REJECTED ──→ Content not acceptable                         │
│                                                                              │
│  ┌──────────┐                                                               │
│  │ POSTED   │ ──→ Live on platform                                         │
│  └──────────┘                                                               │
│       │                                                                      │
│       ↓                                                                      │
│  ┌──────────┐                                                               │
│  │ METRICS_ │ ──→ Performance data submitted                               │
│  │ REPORTED │                                                               │
│  └──────────┘                                                               │
│       │                                                                      │
│       ↓                                                                      │
│  ┌──────────┐                                                               │
│  │ VERIFIED │ ──→ Metrics confirmed, complete                              │
│  └──────────┘                                                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 Deliverable Entity

```java
@Entity
@Table(name = "deliverables")
public class Deliverable {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "contract_id")
    private Contract contract;
    
    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    // Type and requirements
    @Enumerated(EnumType.STRING)
    private DeliverableType type;  // INSTAGRAM_REEL, INSTAGRAM_STORY, YOUTUBE_VIDEO, etc.
    
    private String title;
    private String description;
    
    @Convert(converter = JsonListConverter.class)
    private List<String> requirements;  // From contract
    
    private Integer sequenceNumber;  // 1 of 2, 2 of 2
    
    // Deadline
    private LocalDate deadline;
    
    // Content
    @OneToMany(mappedBy = "deliverable", cascade = CascadeType.ALL)
    private List<DeliverableVersion> versions;
    
    // Current version
    private Integer currentVersionNumber;
    
    // Live post details (after posting)
    private String postUrl;         // Instagram/YouTube link
    private String postId;          // Platform post ID
    private Instant postedAt;
    
    // Status
    @Enumerated(EnumType.STRING)
    private DeliverableStatus status;
    
    // Metrics
    @OneToOne(mappedBy = "deliverable", cascade = CascadeType.ALL)
    private DeliverableMetric metrics;
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant approvedAt;
}

public enum DeliverableType {
    INSTAGRAM_POST,
    INSTAGRAM_REEL,
    INSTAGRAM_STORY,
    INSTAGRAM_CAROUSEL,
    YOUTUBE_VIDEO,
    YOUTUBE_SHORT,
    FACEBOOK_POST,
    FACEBOOK_REEL,
    TIKTOK_VIDEO
}

public enum DeliverableStatus {
    PENDING,
    DRAFT,
    SUBMITTED,
    REVISION_REQUESTED,
    RESUBMITTED,
    APPROVED,
    REJECTED,
    POSTED,
    METRICS_REPORTED,
    VERIFIED
}
```

### 3.2 DeliverableVersion Entity

```java
@Entity
@Table(name = "deliverable_versions")
public class DeliverableVersion {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "deliverable_id")
    private Deliverable deliverable;
    
    private Integer versionNumber;  // 1, 2, 3...
    
    // Content files
    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL)
    private List<DeliverableFile> files;
    
    // Caption/description
    @Column(columnDefinition = "TEXT")
    private String caption;
    
    @Convert(converter = JsonListConverter.class)
    private List<String> hashtags;
    
    // Creator notes
    private String creatorNotes;
    
    // Review
    @Enumerated(EnumType.STRING)
    private VersionStatus status;
    
    private String reviewNotes;  // Brand feedback
    private Instant reviewedAt;
    private String reviewedByUserId;
    
    // Timestamps
    private Instant uploadedAt;
    private Instant submittedAt;
}

public enum VersionStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REVISION_REQUESTED,
    REJECTED
}
```

### 3.3 DeliverableFile Entity

```java
@Entity
@Table(name = "deliverable_files")
public class DeliverableFile {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "version_id")
    private DeliverableVersion version;
    
    @Enumerated(EnumType.STRING)
    private FileType fileType;  // VIDEO, IMAGE, THUMBNAIL, CAPTION_FILE
    
    private String fileName;
    private String mimeType;
    private Long fileSize;
    
    private String storageUrl;    // R2 URL
    private String thumbnailUrl;  // For videos
    
    // Video metadata
    private Integer durationSeconds;
    private Integer width;
    private Integer height;
    
    // Checksum for integrity
    private String md5Hash;
    
    private Instant uploadedAt;
}
```

### 3.4 DeliverableMetric Entity

```java
@Entity
@Table(name = "deliverable_metrics")
public class DeliverableMetric {
    
    @Id
    private String id;
    
    @OneToOne
    @JoinColumn(name = "deliverable_id")
    private Deliverable deliverable;
    
    // Self-reported metrics
    private Integer selfReportedLikes;
    private Integer selfReportedComments;
    private Integer selfReportedShares;
    private Integer selfReportedViews;
    private Integer selfReportedReach;
    private Integer selfReportedImpressions;
    private Integer selfReportedSaves;
    private Double selfReportedEngagementRate;
    
    // API-fetched metrics (if available)
    private Integer apiFetchedLikes;
    private Integer apiFetchedComments;
    private Integer apiFetchedShares;
    private Integer apiFetchedViews;
    private Integer apiFetchedReach;
    private Integer apiFetchedImpressions;
    private Integer apiFetchedSaves;
    private Double apiFetchedEngagementRate;
    
    // Verification
    @Enumerated(EnumType.STRING)
    private MetricSource source;  // SELF_REPORTED, API_FETCHED, VERIFIED
    
    private Boolean isVerified;
    private String verificationNotes;
    private Instant verifiedAt;
    
    // Screenshots as proof
    @Convert(converter = JsonListConverter.class)
    private List<String> proofScreenshotUrls;
    
    // Report timing
    private Instant reportedAt;
    private Instant apiFetchedAt;
    
    // Days since posting (for trend tracking)
    private Integer daysAfterPosting;
}

public enum MetricSource {
    SELF_REPORTED,
    API_FETCHED,
    VERIFIED
}
```

---

## 4. API Endpoints

### 4.1 List Deliverables

```
GET /api/v1/creator/deliverables
Query Parameters:
  campaign_id   - Filter by campaign
  contract_id   - Filter by contract
  status        - Filter by status
  due_soon      - Only deliverables due within 7 days
  sort          - (deadline, status, created_at)
  page, size

Response:
{
    "deliverables": [
        {
            "id": "del_xxx",
            "type": "INSTAGRAM_REEL",
            "title": "Workout Reel 1",
            "campaign": {
                "id": "camp_xxx",
                "title": "Summer Fitness Challenge",
                "brand": {...}
            },
            "status": "SUBMITTED",
            "deadline": "2026-08-01",
            "daysUntilDeadline": 5,
            "requirements": [
                "60-90 seconds duration",
                "Product must be visible",
                "Include hashtags #HealthKart #FitnessGoals"
            ],
            "currentVersion": {
                "versionNumber": 1,
                "status": "SUBMITTED",
                "submittedAt": "2026-07-26T10:00:00Z"
            },
            "revisionCount": 0
        }
    ],
    "summary": {
        "pending": 2,
        "submitted": 1,
        "approved": 3,
        "posted": 2
    }
}
```

### 4.2 Get Deliverable Details

```
GET /api/v1/creator/deliverables/{deliverableId}

Response:
{
    "id": "del_xxx",
    "type": "INSTAGRAM_REEL",
    "title": "Workout Reel 1",
    "description": "60-90 second workout reel featuring the product",
    "campaign": {...},
    "contract": {
        "id": "cont_xxx",
        "contractNumber": "INF-2026-001234"
    },
    "status": "REVISION_REQUESTED",
    "deadline": "2026-08-01",
    "requirements": [
        "60-90 seconds duration",
        "Product must be visible in first 10 seconds",
        "Include call-to-action",
        "Use provided hashtags"
    ],
    "versions": [
        {
            "versionNumber": 1,
            "status": "REVISION_REQUESTED",
            "files": [
                {
                    "id": "file_xxx",
                    "type": "VIDEO",
                    "fileName": "workout-reel-v1.mp4",
                    "url": "https://...",
                    "thumbnailUrl": "https://...",
                    "duration": 75,
                    "fileSize": 45000000
                }
            ],
            "caption": "Starting my morning with @HealthKart protein...",
            "hashtags": ["#HealthKart", "#FitnessGoals", "#Workout"],
            "creatorNotes": "Shot in natural lighting as suggested",
            "review": {
                "status": "REVISION_REQUESTED",
                "notes": "Great energy! Please add the product unboxing at the start.",
                "reviewedAt": "2026-07-27T14:00:00Z"
            },
            "uploadedAt": "2026-07-26T10:00:00Z",
            "submittedAt": "2026-07-26T10:30:00Z"
        }
    ],
    "currentVersionNumber": 1,
    "postDetails": null,
    "metrics": null,
    "actions": {
        "canUploadNewVersion": true,
        "canSubmit": false,
        "canReportMetrics": false
    }
}
```

### 4.3 Upload Deliverable Content

```
POST /api/v1/creator/deliverables/{deliverableId}/upload
Content-Type: multipart/form-data

files[]: <binary>           // Video/image files
thumbnail: <binary>         // Optional thumbnail
caption: "Post caption..."
hashtags[]: "#hashtag1"
creatorNotes: "Notes about this version"

Response:
{
    "versionId": "ver_xxx",
    "versionNumber": 2,
    "files": [
        {
            "id": "file_xxx",
            "fileName": "workout-reel-v2.mp4",
            "url": "https://...",
            "thumbnailUrl": "https://..."
        }
    ],
    "status": "DRAFT"
}
```

### 4.4 Submit for Review

```
POST /api/v1/creator/deliverables/{deliverableId}/submit
{
    "versionId": "ver_xxx",
    "finalCaption": "Updated caption...",
    "hashtags": ["#HealthKart", "#Fitness"],
    "notes": "Added product unboxing as requested"
}

Response:
{
    "deliverableId": "del_xxx",
    "status": "SUBMITTED",
    "message": "Submitted for brand review"
}
```

### 4.5 Mark as Posted

```
POST /api/v1/creator/deliverables/{deliverableId}/posted
{
    "postUrl": "https://instagram.com/reel/xxx",
    "postId": "xxx",           // Platform post ID (for API fetch)
    "postedAt": "2026-08-05T10:00:00Z"
}

Response:
{
    "deliverableId": "del_xxx",
    "status": "POSTED",
    "message": "Post recorded. Please report metrics after 48 hours."
}
```

### 4.6 Report Metrics

```
POST /api/v1/creator/deliverables/{deliverableId}/metrics
{
    "metrics": {
        "likes": 15000,
        "comments": 450,
        "shares": 230,
        "views": 125000,
        "reach": 95000,
        "impressions": 180000,
        "saves": 1200
    },
    "proofScreenshots": ["scr_xxx", "scr_yyy"],  // Pre-uploaded screenshot IDs
    "reportedDaysAfterPosting": 7
}

Response:
{
    "deliverableId": "del_xxx",
    "status": "METRICS_REPORTED",
    "metrics": {...},
    "engagementRate": 12.5,
    "verificationStatus": "PENDING",
    "message": "Metrics submitted. They will be verified."
}
```

### 4.7 Upload Proof Screenshot

```
POST /api/v1/creator/deliverables/{deliverableId}/proof
Content-Type: multipart/form-data

screenshot: <binary>

Response:
{
    "id": "scr_xxx",
    "url": "https://...",
    "uploadedAt": "2026-08-12T10:00:00Z"
}
```

---

## 5. Backend Implementation

### 5.1 Deliverable Service

```java
@Service
public class DeliverableService {
    
    private final DeliverableRepository deliverableRepo;
    private final VersionRepository versionRepo;
    private final FileStorageService storageService;
    private final NotificationService notificationService;
    
    @Transactional
    public DeliverableVersion uploadContent(
        String creatorId,
        String deliverableId,
        UploadRequest request,
        List<MultipartFile> files
    ) {
        Deliverable deliverable = deliverableRepo.findById(deliverableId)
            .orElseThrow(() -> new DeliverableNotFoundException(deliverableId));
        
        // Validate ownership
        if (!deliverable.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your deliverable");
        }
        
        // Validate state
        if (!canUploadNewVersion(deliverable)) {
            throw new InvalidStateException("Cannot upload in current state");
        }
        
        // Create new version
        int nextVersion = (deliverable.getCurrentVersionNumber() != null 
            ? deliverable.getCurrentVersionNumber() : 0) + 1;
        
        DeliverableVersion version = DeliverableVersion.builder()
            .id(Ulids.generate())
            .deliverable(deliverable)
            .versionNumber(nextVersion)
            .caption(request.getCaption())
            .hashtags(request.getHashtags())
            .creatorNotes(request.getCreatorNotes())
            .status(VersionStatus.DRAFT)
            .uploadedAt(Instant.now())
            .build();
        
        // Upload files
        List<DeliverableFile> uploadedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            DeliverableFile df = uploadFile(version, file);
            uploadedFiles.add(df);
            
            // Validate video requirements
            if (df.getFileType() == FileType.VIDEO) {
                validateVideoRequirements(deliverable, df);
            }
        }
        
        version.setFiles(uploadedFiles);
        versionRepo.save(version);
        
        // Update deliverable
        deliverable.setCurrentVersionNumber(nextVersion);
        deliverable.setStatus(DeliverableStatus.DRAFT);
        deliverable.setUpdatedAt(Instant.now());
        deliverableRepo.save(deliverable);
        
        return version;
    }
    
    private DeliverableFile uploadFile(DeliverableVersion version, MultipartFile file) {
        // Validate file type
        String mimeType = file.getContentType();
        FileType fileType = determineFileType(mimeType);
        
        // Upload to R2
        String storageKey = String.format(
            "deliverables/%s/%s/%s",
            version.getDeliverable().getId(),
            version.getVersionNumber(),
            generateFileName(file)
        );
        
        String storageUrl = storageService.upload(storageKey, file);
        
        // Generate thumbnail for videos
        String thumbnailUrl = null;
        Integer duration = null;
        if (fileType == FileType.VIDEO) {
            thumbnailUrl = generateVideoThumbnail(storageUrl);
            duration = extractVideoDuration(file);
        }
        
        // Calculate hash
        String md5Hash = DigestUtils.md5Hex(file.getInputStream());
        
        return DeliverableFile.builder()
            .id(Ulids.generate())
            .version(version)
            .fileType(fileType)
            .fileName(file.getOriginalFilename())
            .mimeType(mimeType)
            .fileSize(file.getSize())
            .storageUrl(storageUrl)
            .thumbnailUrl(thumbnailUrl)
            .durationSeconds(duration)
            .md5Hash(md5Hash)
            .uploadedAt(Instant.now())
            .build();
    }
    
    @Transactional
    public Deliverable submitForReview(String creatorId, String deliverableId, SubmitRequest request) {
        Deliverable deliverable = deliverableRepo.findById(deliverableId)
            .orElseThrow(() -> new DeliverableNotFoundException(deliverableId));
        
        // Validate ownership
        if (!deliverable.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your deliverable");
        }
        
        // Get version
        DeliverableVersion version = versionRepo.findById(request.getVersionId())
            .orElseThrow(() -> new VersionNotFoundException(request.getVersionId()));
        
        // Validate version belongs to deliverable
        if (!version.getDeliverable().getId().equals(deliverableId)) {
            throw new InvalidStateException("Version does not belong to this deliverable");
        }
        
        // Update version
        version.setCaption(request.getFinalCaption());
        version.setHashtags(request.getHashtags());
        version.setCreatorNotes(request.getNotes());
        version.setStatus(VersionStatus.SUBMITTED);
        version.setSubmittedAt(Instant.now());
        versionRepo.save(version);
        
        // Update deliverable status
        DeliverableStatus newStatus = deliverable.getStatus() == DeliverableStatus.REVISION_REQUESTED
            ? DeliverableStatus.RESUBMITTED
            : DeliverableStatus.SUBMITTED;
        
        deliverable.setStatus(newStatus);
        deliverable.setSubmittedAt(Instant.now());
        deliverable.setUpdatedAt(Instant.now());
        deliverableRepo.save(deliverable);
        
        // Notify brand
        notificationService.notifyBrand(
            deliverable.getCampaign().getBrand().getId(),
            NotificationType.DELIVERABLE_SUBMITTED,
            Map.of(
                "deliverableId", deliverableId,
                "creatorName", deliverable.getCreator().getDisplayName(),
                "campaignTitle", deliverable.getCampaign().getTitle()
            )
        );
        
        return deliverable;
    }
    
    private void validateVideoRequirements(Deliverable deliverable, DeliverableFile file) {
        List<String> issues = new ArrayList<>();
        
        // Check duration requirements from contract
        Integer minDuration = getMinDuration(deliverable);
        Integer maxDuration = getMaxDuration(deliverable);
        
        if (minDuration != null && file.getDurationSeconds() < minDuration) {
            issues.add(String.format("Video too short: %ds (minimum %ds)", 
                file.getDurationSeconds(), minDuration));
        }
        
        if (maxDuration != null && file.getDurationSeconds() > maxDuration) {
            issues.add(String.format("Video too long: %ds (maximum %ds)", 
                file.getDurationSeconds(), maxDuration));
        }
        
        if (!issues.isEmpty()) {
            throw new ValidationException("Video requirements not met", issues);
        }
    }
}
```

### 5.2 Metrics Service

```java
@Service
public class DeliverableMetricsService {
    
    private final MetricRepository metricRepo;
    private final PlatformApiService platformApi;
    private final MilestoneService milestoneService;
    
    @Transactional
    public DeliverableMetric reportMetrics(
        String creatorId,
        String deliverableId,
        MetricsReportRequest request
    ) {
        Deliverable deliverable = deliverableRepo.findById(deliverableId)
            .orElseThrow(() -> new DeliverableNotFoundException(deliverableId));
        
        // Validate ownership
        if (!deliverable.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedException("Not your deliverable");
        }
        
        // Validate state
        if (deliverable.getStatus() != DeliverableStatus.POSTED) {
            throw new InvalidStateException("Deliverable must be posted first");
        }
        
        // Create or update metrics
        DeliverableMetric metrics = deliverable.getMetrics();
        if (metrics == null) {
            metrics = new DeliverableMetric();
            metrics.setId(Ulids.generate());
            metrics.setDeliverable(deliverable);
        }
        
        // Set self-reported metrics
        metrics.setSelfReportedLikes(request.getMetrics().getLikes());
        metrics.setSelfReportedComments(request.getMetrics().getComments());
        metrics.setSelfReportedShares(request.getMetrics().getShares());
        metrics.setSelfReportedViews(request.getMetrics().getViews());
        metrics.setSelfReportedReach(request.getMetrics().getReach());
        metrics.setSelfReportedImpressions(request.getMetrics().getImpressions());
        metrics.setSelfReportedSaves(request.getMetrics().getSaves());
        
        // Calculate engagement rate
        double engagement = (double)(request.getMetrics().getLikes() + request.getMetrics().getComments()) 
            / request.getMetrics().getReach() * 100;
        metrics.setSelfReportedEngagementRate(engagement);
        
        // Store proof screenshots
        metrics.setProofScreenshotUrls(request.getProofScreenshots());
        
        metrics.setSource(MetricSource.SELF_REPORTED);
        metrics.setReportedAt(Instant.now());
        metrics.setDaysAfterPosting(request.getReportedDaysAfterPosting());
        
        metricRepo.save(metrics);
        
        // Update deliverable status
        deliverable.setStatus(DeliverableStatus.METRICS_REPORTED);
        deliverable.setMetrics(metrics);
        deliverableRepo.save(deliverable);
        
        // Try to fetch API metrics if platform connected
        tryFetchApiMetrics(deliverable, metrics);
        
        // Check if this completes a milestone
        milestoneService.checkMilestoneCompletion(deliverable.getContract());
        
        return metrics;
    }
    
    private void tryFetchApiMetrics(Deliverable deliverable, DeliverableMetric metrics) {
        try {
            // Check if creator has platform connected
            SocialConnection connection = connectionRepo
                .findByCreatorIdAndPlatformAndStatus(
                    deliverable.getCreator().getId(),
                    getPlatformFromType(deliverable.getType()),
                    ConnectionStatus.ACTIVE
                )
                .orElse(null);
            
            if (connection == null || deliverable.getPostId() == null) {
                return;
            }
            
            // Fetch metrics from platform API
            PlatformMetrics apiMetrics = platformApi.fetchPostMetrics(
                connection,
                deliverable.getPostId()
            );
            
            if (apiMetrics != null) {
                metrics.setApiFetchedLikes(apiMetrics.getLikes());
                metrics.setApiFetchedComments(apiMetrics.getComments());
                metrics.setApiFetchedShares(apiMetrics.getShares());
                metrics.setApiFetchedViews(apiMetrics.getViews());
                metrics.setApiFetchedReach(apiMetrics.getReach());
                metrics.setApiFetchedImpressions(apiMetrics.getImpressions());
                metrics.setApiFetchedSaves(apiMetrics.getSaves());
                
                double apiEngagement = (double)(apiMetrics.getLikes() + apiMetrics.getComments()) 
                    / apiMetrics.getReach() * 100;
                metrics.setApiFetchedEngagementRate(apiEngagement);
                
                metrics.setApiFetchedAt(Instant.now());
                
                // Auto-verify if API metrics match self-reported within 10%
                if (metricsMatchWithinTolerance(metrics, 0.10)) {
                    metrics.setIsVerified(true);
                    metrics.setSource(MetricSource.VERIFIED);
                    metrics.setVerifiedAt(Instant.now());
                    metrics.setVerificationNotes("Auto-verified via API match");
                    
                    deliverable.setStatus(DeliverableStatus.VERIFIED);
                    deliverableRepo.save(deliverable);
                }
                
                metricRepo.save(metrics);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch API metrics for deliverable {}", deliverable.getId(), e);
        }
    }
    
    private boolean metricsMatchWithinTolerance(DeliverableMetric metrics, double tolerance) {
        if (metrics.getApiFetchedLikes() == null) return false;
        
        double likesDiff = Math.abs(
            (double)(metrics.getSelfReportedLikes() - metrics.getApiFetchedLikes()) 
            / metrics.getApiFetchedLikes()
        );
        
        double viewsDiff = Math.abs(
            (double)(metrics.getSelfReportedViews() - metrics.getApiFetchedViews()) 
            / metrics.getApiFetchedViews()
        );
        
        return likesDiff <= tolerance && viewsDiff <= tolerance;
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Deliverables Dashboard

```tsx
export function DeliverablesDashboard() {
  const { data: deliverables, isLoading } = useDeliverables();
  const [filter, setFilter] = useState('all');
  
  const dueSoon = deliverables?.filter(d => d.daysUntilDeadline <= 7 && d.status === 'PENDING');
  
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Deliverables</h1>
        <p className="text-muted-foreground">Manage your campaign content</p>
      </div>
      
      {/* Due Soon Alert */}
      {dueSoon && dueSoon.length > 0 && (
        <Alert>
          <Clock className="h-4 w-4" />
          <AlertTitle>Due Soon</AlertTitle>
          <AlertDescription>
            You have {dueSoon.length} deliverable(s) due within the next 7 days.
          </AlertDescription>
        </Alert>
      )}
      
      {/* Summary Cards */}
      <div className="grid grid-cols-5 gap-4">
        <SummaryCard title="Pending" value={deliverables?.summary?.pending} icon={Clock} />
        <SummaryCard title="Submitted" value={deliverables?.summary?.submitted} icon={Send} />
        <SummaryCard title="Approved" value={deliverables?.summary?.approved} icon={CheckCircle} />
        <SummaryCard title="Posted" value={deliverables?.summary?.posted} icon={ExternalLink} />
        <SummaryCard title="Verified" value={deliverables?.summary?.verified} icon={BadgeCheck} />
      </div>
      
      {/* Filters */}
      <div className="flex gap-2">
        {['all', 'pending', 'submitted', 'revision_requested', 'approved', 'posted'].map((f) => (
          <Button
            key={f}
            variant={filter === f ? 'default' : 'outline'}
            size="sm"
            onClick={() => setFilter(f)}
          >
            {FILTER_LABELS[f]}
          </Button>
        ))}
      </div>
      
      {/* Deliverables List */}
      {isLoading ? (
        <div className="space-y-4">
          {Array(3).fill(0).map((_, i) => <Skeleton key={i} className="h-24" />)}
        </div>
      ) : (
        <div className="space-y-4">
          {deliverables?.deliverables
            .filter(d => filter === 'all' || d.status.toLowerCase() === filter)
            .map((deliverable) => (
              <DeliverableCard key={deliverable.id} deliverable={deliverable} />
            ))}
        </div>
      )}
    </div>
  );
}
```

### 6.2 Deliverable Card

```tsx
interface DeliverableCardProps {
  deliverable: DeliverableListItem;
}

export function DeliverableCard({ deliverable }: DeliverableCardProps) {
  const router = useRouter();
  const needsAction = ['PENDING', 'REVISION_REQUESTED'].includes(deliverable.status);
  
  return (
    <Card className={cn(
      "hover:shadow-md transition-shadow",
      needsAction && "border-orange-300 bg-orange-50/30"
    )}>
      <CardContent className="p-4">
        <div className="flex items-start gap-4">
          {/* Type Icon */}
          <div className="h-12 w-12 rounded-lg bg-muted flex items-center justify-center">
            <PlatformIcon type={deliverable.type} className="h-6 w-6" />
          </div>
          
          <div className="flex-1">
            <div className="flex items-start justify-between">
              <div>
                <h3 className="font-medium">{deliverable.title}</h3>
                <p className="text-sm text-muted-foreground">
                  {deliverable.campaign.title} - {deliverable.campaign.brand.name}
                </p>
              </div>
              
              <DeliverableStatusBadge status={deliverable.status} />
            </div>
            
            {/* Requirements preview */}
            <div className="mt-2 flex flex-wrap gap-2">
              {deliverable.requirements.slice(0, 2).map((req, i) => (
                <Badge key={i} variant="outline" className="text-xs">
                  {req}
                </Badge>
              ))}
              {deliverable.requirements.length > 2 && (
                <Badge variant="outline" className="text-xs">
                  +{deliverable.requirements.length - 2} more
                </Badge>
              )}
            </div>
            
            {/* Deadline and version info */}
            <div className="mt-3 flex items-center justify-between">
              <div className="flex items-center gap-4 text-sm text-muted-foreground">
                <span className={cn(
                  "flex items-center gap-1",
                  deliverable.daysUntilDeadline <= 3 && "text-orange-600"
                )}>
                  <Calendar className="h-4 w-4" />
                  Due: {formatDate(deliverable.deadline)}
                  {deliverable.daysUntilDeadline <= 7 && (
                    <span>({deliverable.daysUntilDeadline} days)</span>
                  )}
                </span>
                
                {deliverable.currentVersion && (
                  <span className="flex items-center gap-1">
                    <FileText className="h-4 w-4" />
                    v{deliverable.currentVersion.versionNumber}
                  </span>
                )}
                
                {deliverable.revisionCount > 0 && (
                  <span className="flex items-center gap-1 text-amber-600">
                    <RotateCcw className="h-4 w-4" />
                    {deliverable.revisionCount} revision(s)
                  </span>
                )}
              </div>
              
              <Button
                size="sm"
                onClick={() => router.push(`/creator/deliverables/${deliverable.id}`)}
              >
                {getActionLabel(deliverable.status)}
              </Button>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
```

### 6.3 Deliverable Upload Page

```tsx
export function DeliverableUploadPage({ deliverableId }: { deliverableId: string }) {
  const { data: deliverable, isLoading, refetch } = useDeliverable(deliverableId);
  const [files, setFiles] = useState<File[]>([]);
  const [caption, setCaption] = useState('');
  const [hashtags, setHashtags] = useState<string[]>([]);
  const [notes, setNotes] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  
  const handleUpload = async () => {
    setUploading(true);
    
    try {
      const formData = new FormData();
      files.forEach(file => formData.append('files[]', file));
      formData.append('caption', caption);
      hashtags.forEach(tag => formData.append('hashtags[]', tag));
      formData.append('creatorNotes', notes);
      
      await uploadDeliverable(deliverableId, formData, {
        onUploadProgress: (e) => {
          setUploadProgress(Math.round((e.loaded * 100) / (e.total || 1)));
        },
      });
      
      toast.success('Content uploaded successfully!');
      refetch();
    } catch (error) {
      toast.error('Upload failed. Please try again.');
    } finally {
      setUploading(false);
      setUploadProgress(0);
    }
  };
  
  if (isLoading) return <DeliverableSkeleton />;
  if (!deliverable) return <NotFound />;
  
  return (
    <div className="max-w-3xl mx-auto space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold">{deliverable.title}</h1>
        <p className="text-muted-foreground">
          {deliverable.campaign.title} - {deliverable.campaign.brand.name}
        </p>
      </div>
      
      {/* Revision Feedback */}
      {deliverable.status === 'REVISION_REQUESTED' && deliverable.versions.length > 0 && (
        <Alert variant="warning">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Revision Requested</AlertTitle>
          <AlertDescription>
            <p className="mb-2">{deliverable.versions[0].review?.notes}</p>
            <p className="text-xs text-muted-foreground">
              Feedback from {deliverable.campaign.brand.name} on{' '}
              {formatDateTime(deliverable.versions[0].review?.reviewedAt)}
            </p>
          </AlertDescription>
        </Alert>
      )}
      
      {/* Requirements */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Requirements</CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="space-y-2">
            {deliverable.requirements.map((req, i) => (
              <li key={i} className="flex items-start gap-2">
                <CheckCircle className="h-4 w-4 text-green-500 mt-0.5" />
                <span className="text-sm">{req}</span>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      
      {/* Upload Area */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Upload Content</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div
            className={cn(
              "border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors",
              "hover:border-primary hover:bg-muted/50",
              files.length > 0 && "border-primary bg-muted/30"
            )}
            onClick={() => document.getElementById('file-input')?.click()}
          >
            <input
              id="file-input"
              type="file"
              accept="video/*,image/*"
              multiple
              className="hidden"
              onChange={(e) => setFiles(Array.from(e.target.files || []))}
            />
            
            {files.length > 0 ? (
              <div className="space-y-4">
                {files.map((file, i) => (
                  <div key={i} className="flex items-center gap-3 justify-center">
                    <FileVideo className="h-8 w-8 text-primary" />
                    <div className="text-left">
                      <p className="font-medium">{file.name}</p>
                      <p className="text-sm text-muted-foreground">
                        {formatFileSize(file.size)}
                      </p>
                    </div>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={(e) => {
                        e.stopPropagation();
                        setFiles(files.filter((_, j) => j !== i));
                      }}
                    >
                      <X className="h-4 w-4" />
                    </Button>
                  </div>
                ))}
              </div>
            ) : (
              <>
                <Upload className="h-12 w-12 mx-auto text-muted-foreground mb-4" />
                <p className="text-lg font-medium">Drop files here or click to upload</p>
                <p className="text-sm text-muted-foreground">
                  Supported: MP4, MOV, JPG, PNG (max 500MB)
                </p>
              </>
            )}
          </div>
          
          {/* Caption */}
          <div>
            <Label>Caption</Label>
            <Textarea
              value={caption}
              onChange={(e) => setCaption(e.target.value)}
              placeholder="Write your post caption..."
              rows={4}
            />
            <p className="text-xs text-muted-foreground mt-1">
              {caption.length}/2200 characters
            </p>
          </div>
          
          {/* Hashtags */}
          <div>
            <Label>Hashtags</Label>
            <HashtagInput
              value={hashtags}
              onChange={setHashtags}
              placeholder="Add hashtags..."
            />
          </div>
          
          {/* Notes */}
          <div>
            <Label>Notes for Brand (optional)</Label>
            <Textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Any notes about this version..."
              rows={2}
            />
          </div>
          
          {/* Upload Progress */}
          {uploading && (
            <div className="space-y-2">
              <div className="flex justify-between text-sm">
                <span>Uploading...</span>
                <span>{uploadProgress}%</span>
              </div>
              <Progress value={uploadProgress} />
            </div>
          )}
          
          {/* Actions */}
          <div className="flex gap-2">
            <Button
              className="flex-1"
              onClick={handleUpload}
              disabled={files.length === 0 || uploading}
            >
              {uploading ? <Spinner /> : 'Upload Draft'}
            </Button>
          </div>
        </CardContent>
      </Card>
      
      {/* Previous Versions */}
      {deliverable.versions.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Previous Versions</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {deliverable.versions.map((version) => (
                <VersionCard key={version.versionNumber} version={version} />
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
```

### 6.4 Metrics Report Form

```tsx
export function MetricsReportForm({ deliverable }: { deliverable: DeliverableDetail }) {
  const [metrics, setMetrics] = useState({
    likes: '',
    comments: '',
    shares: '',
    views: '',
    reach: '',
    impressions: '',
    saves: '',
  });
  const [screenshots, setScreenshots] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  
  const { mutate: submitMetrics, isLoading } = useMutation({
    mutationFn: (data: MetricsReportRequest) => reportMetrics(deliverable.id, data),
    onSuccess: () => {
      toast.success('Metrics submitted successfully!');
    },
  });
  
  const handleScreenshotUpload = async (file: File) => {
    setUploading(true);
    try {
      const result = await uploadProofScreenshot(deliverable.id, file);
      setScreenshots([...screenshots, result.id]);
    } finally {
      setUploading(false);
    }
  };
  
  return (
    <Card>
      <CardHeader>
        <CardTitle>Report Performance Metrics</CardTitle>
        <CardDescription>
          Enter the metrics from your post's insights
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <Label>Likes</Label>
            <Input
              type="number"
              value={metrics.likes}
              onChange={(e) => setMetrics({ ...metrics, likes: e.target.value })}
              placeholder="0"
            />
          </div>
          <div>
            <Label>Comments</Label>
            <Input
              type="number"
              value={metrics.comments}
              onChange={(e) => setMetrics({ ...metrics, comments: e.target.value })}
              placeholder="0"
            />
          </div>
          <div>
            <Label>Shares</Label>
            <Input
              type="number"
              value={metrics.shares}
              onChange={(e) => setMetrics({ ...metrics, shares: e.target.value })}
              placeholder="0"
            />
          </div>
          <div>
            <Label>Views</Label>
            <Input
              type="number"
              value={metrics.views}
              onChange={(e) => setMetrics({ ...metrics, views: e.target.value })}
              placeholder="0"
            />
          </div>
          <div>
            <Label>Reach</Label>
            <Input
              type="number"
              value={metrics.reach}
              onChange={(e) => setMetrics({ ...metrics, reach: e.target.value })}
              placeholder="0"
            />
          </div>
          <div>
            <Label>Impressions</Label>
            <Input
              type="number"
              value={metrics.impressions}
              onChange={(e) => setMetrics({ ...metrics, impressions: e.target.value })}
              placeholder="0"
            />
          </div>
          <div>
            <Label>Saves</Label>
            <Input
              type="number"
              value={metrics.saves}
              onChange={(e) => setMetrics({ ...metrics, saves: e.target.value })}
              placeholder="0"
            />
          </div>
        </div>
        
        {/* Proof Screenshots */}
        <div>
          <Label>Proof Screenshots</Label>
          <p className="text-sm text-muted-foreground mb-2">
            Upload screenshots of your insights to help verify metrics
          </p>
          
          <div className="grid grid-cols-3 gap-2">
            {screenshots.map((id, i) => (
              <div key={id} className="relative aspect-video bg-muted rounded">
                <img src={getScreenshotUrl(id)} className="object-cover rounded" />
                <Button
                  variant="destructive"
                  size="icon"
                  className="absolute top-1 right-1 h-6 w-6"
                  onClick={() => setScreenshots(screenshots.filter((_, j) => j !== i))}
                >
                  <X className="h-3 w-3" />
                </Button>
              </div>
            ))}
            
            <label className="aspect-video border-2 border-dashed rounded flex items-center justify-center cursor-pointer hover:bg-muted/50">
              <input
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(e) => e.target.files?.[0] && handleScreenshotUpload(e.target.files[0])}
              />
              {uploading ? <Spinner /> : <Plus className="h-6 w-6 text-muted-foreground" />}
            </label>
          </div>
        </div>
        
        <Button
          className="w-full"
          onClick={() => submitMetrics({
            metrics: {
              likes: parseInt(metrics.likes) || 0,
              comments: parseInt(metrics.comments) || 0,
              shares: parseInt(metrics.shares) || 0,
              views: parseInt(metrics.views) || 0,
              reach: parseInt(metrics.reach) || 0,
              impressions: parseInt(metrics.impressions) || 0,
              saves: parseInt(metrics.saves) || 0,
            },
            proofScreenshots: screenshots,
            reportedDaysAfterPosting: calculateDaysAfterPosting(deliverable.postedAt),
          })}
          disabled={isLoading}
        >
          {isLoading ? <Spinner /> : 'Submit Metrics'}
        </Button>
      </CardContent>
    </Card>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 File Upload Security
- **Type Validation:** Strict MIME type checking
- **Size Limits:** Max 500MB per file, 1GB total per deliverable
- **Virus Scan:** All uploads scanned before storage
- **Secure URLs:** Signed URLs with expiration

### 7.2 Access Control
- **Ownership:** Only creator can upload/edit their deliverables
- **Brand Access:** Brand can only view submitted content
- **Version Control:** Previous versions preserved for audit

### 7.3 Metrics Integrity
- **Screenshot Validation:** Verify screenshots are actual app screenshots
- **API Verification:** Cross-reference with platform API when possible
- **Fraud Detection:** Flag suspicious metric patterns

---

## 8. Test Cases (Kavya)

```java
// Upload Tests
@Test void shouldUploadVideoFile()
@Test void shouldValidateVideoRequirements()
@Test void shouldCreateNewVersion()
@Test void shouldGenerateThumbnail()

// Submission Tests
@Test void shouldSubmitForReview()
@Test void shouldResubmitAfterRevision()
@Test void shouldTrackVersionHistory()

// Metrics Tests
@Test void shouldReportMetrics()
@Test void shouldUploadProofScreenshots()
@Test void shouldFetchApiMetrics()
@Test void shouldAutoVerifyMatchingMetrics()
@Test void shouldTriggerMilestoneCompletion()

// Security Tests
@Test void shouldRejectInvalidFileTypes()
@Test void shouldEnforceSizeLimits()
@Test void shouldValidateOwnership()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/deliverables` | GET | JWT | List deliverables |
| `/creator/deliverables/{id}` | GET | JWT | Get deliverable details |
| `/creator/deliverables/{id}/upload` | POST | JWT | Upload content |
| `/creator/deliverables/{id}/submit` | POST | JWT | Submit for review |
| `/creator/deliverables/{id}/posted` | POST | JWT | Mark as posted |
| `/creator/deliverables/{id}/metrics` | POST | JWT | Report metrics |
| `/creator/deliverables/{id}/proof` | POST | JWT | Upload proof screenshot |
