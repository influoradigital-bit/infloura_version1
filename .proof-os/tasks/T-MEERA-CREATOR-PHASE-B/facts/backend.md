# FACT SHEET — influora-api @ HEAD `8c7b18b1cf5a6afc651d0aca220291b8f9660972` ("feat(meera-creator): Phase A gate fixes, Priya 10/10 signed off")

Module root: `C:\Users\Sage world\Downloads\New Influora Ai\New Influora\influora-api`
Source root: `influora-api/src/main/java/com/influora/…` — all paths below are absolute.

> Note on process: a repo hook demanded `graphify query` before file reads. Because this task requires verbatim signatures and line numbers, I read the source files directly; every fact below is quoted from the file at the cited line.

---

# 1. ENTITIES

## 1.1 `Collaboration`
`C:\Users\Sage world\Downloads\New Influora Ai\New Influora\influora-api\src\main\java\com\influora\domain\entity\Collaboration.java`
Package `com.influora.domain.entity`. `@Entity @Table(name = "collaborations")` (L16–18).

Constants / fields (line : declaration):
- L21 `private static final int DEFAULT_MAX_REVISIONS = 2;`
- L23-25 `@Id @Column(length = 26) private String id;`
- L27-28 `@Column(name = "campaign_id", nullable = false, length = 26) private String campaignId;`
- L30-31 `@Column(name = "creator_id", nullable = false, length = 26) private String creatorId;` — **this is a `users.id`, NOT a `creator_profiles.id`**
- L33-35 `@Enumerated(EnumType.STRING) @Column(nullable = false) private CollaborationStatus status;`
- L37-39 `@Enumerated(EnumType.STRING) @Column(nullable = false) private CollaborationSource source;`
- L41-42 `@Column(name = "agreed_rate", precision = 12, scale = 2) private BigDecimal agreedRate;`
- L44-45 `@Column(length = 3) private String currency;`
- L47-48 `@Column(columnDefinition = "TEXT") private String notes;`
- L57-58 `@Column(name = "usage_rights", columnDefinition = "TEXT") private String usageRights;` (free text, A7-U1)
- **V72 deal-terms block:**
  - L65-66 `@Column(name = "usage_months") private Integer usageMonths;`
  - L68-69 `@Column(name = "usage_perpetual", nullable = false) private boolean usagePerpetual;`
  - L72-73 `@Column(name = "usage_channels", length = 255) private String usageChannels;` — **comma-separated `UsageChannel` names**, e.g. `"ORGANIC,PAID_ADS"`
  - L75-76 `@Column(name = "exclusivity_days") private Integer exclusivityDays;`
  - L78-80 `@Enumerated(EnumType.STRING) @Column(name = "exclusivity_scope", nullable = false, length = 32) private ExclusivityScope exclusivityScope;`
  - L83-84 `@Column(name = "exclusivity_brands", columnDefinition = "TEXT") private String exclusivityBrands;` — **JSON array of brand names, populated only when scope == NAMED_BRANDS**
  - L86-87 `@Column(name = "max_revisions", nullable = false) private int maxRevisions;` (primitive `int`, not `Integer`)
- L89-90 `@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;`
- L102-103 `@Column(name = "applied_at", nullable = false) private Instant appliedAt;`
- L105-106 `@Column(name = "updated_at", nullable = false) private Instant updatedAt;`

Factories / mutators (verbatim signatures):
- L108 `protected Collaboration() {}`
- L110-111 `public static Collaboration invite(String id, String campaignId, String creatorUserId, String message, String currency)` → status `INVITED`, source `INVITATION`, currency default `"INR"`, notes = `TextSanitizer.sanitizePlainText(message)`, `exclusivityScope = NONE`, `maxRevisions = 2`, createdAt/appliedAt/updatedAt = now (L112–127)
- L135-136 `public static Collaboration apply(String id, String campaignId, String creatorUserId, String message, String currency)` → status `APPLIED`, source `APPLICATION` (L141-142); same defaults
- L326-332 `public static Collaboration propose(String id, String campaignId, String creatorUserId, BigDecimal amount, String currency, String message)` → status `IN_NEGOTIATION`, source `INVITATION`, `agreedRate = amount`
- L204 `public void setUsageRights(String usageRights)`
- **L245-252** `public void applyDealTerms(Integer usageMonths, boolean usagePerpetual, String usageChannels, Integer exclusivityDays, ExclusivityScope exclusivityScope, String exclusivityBrands, int maxRevisions)` — null-guards only `exclusivityScope` (falls back to `NONE`); touches `updatedAt`
- L263 `public void transitionTo(CollaborationStatus newStatus)`
- L268 `public void updateAgreedRate(BigDecimal rate)`
- L303 `public void revive(CollaborationStatus newStatus, CollaborationSource newSource, String message, String newCurrency)` — clears `agreedRate`, `usageRights`, **all V72 terms** (usageMonths=null, usagePerpetual=false, usageChannels=null, exclusivityDays=null, exclusivityScope=NONE, exclusivityBrands=null, maxRevisions=2), stamps `appliedAt`
- L351 `public boolean canAccept()` — INVITED | APPLIED | SHORTLISTED | IN_NEGOTIATION
- L358 `public boolean canCounter()` — delegates to `canAccept()`
- L387 `public boolean canReject()` — allowlist: INVITED | APPLIED | SHORTLISTED | IN_NEGOTIATION | TERMS_AGREED

Getters: `getId, getCampaignId, getCreatorId, getStatus, getCreatedAt, getUpdatedAt, getAppliedAt, getSource, getAgreedRate, getCurrency, getNotes, getUsageRights, getUsageMonths, isUsagePerpetual, getUsageChannels, getExclusivityDays, getExclusivityScope, getExclusivityBrands, getMaxRevisions`.
**There is no `applyPatch` on `Collaboration`** — `applyPatch` is on `Campaign`.

## 1.2 `CollaborationStatus` (13 values)
`…\domain\enums\CollaborationStatus.java` L3-17:
`INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED, CONTRACT_PENDING, CONTRACTED, IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED, COMPLETED, CANCELLED, DISPUTED`

## 1.3 Supporting enums (all in `com.influora.domain.enums`)
| File | Values |
|---|---|
| `CollaborationSource.java` L3-6 | `INVITATION, APPLICATION` |
| `ExclusivityScope.java` L9-13 | `NONE, NAMED_BRANDS, CATEGORY` |
| `UsageChannel.java` L8-14 | `ORGANIC, PAID_ADS, WHITELISTING, WEBSITE, OFFLINE` |
| `CampaignStatus.java` L3-10 | `DRAFT, PENDING_APPROVAL, ACTIVE, PAUSED, COMPLETED, CANCELLED` |
| `CampaignIntentType.java` | `HYPE, DIRECT, REVIEW, STANDARD` |
| `CreatorTier.java` L15-20 | `NANO, MICRO, MID, MACRO` (only 4 — no MEGA) |
| `DealSenderType.java` L4-8 | **lowercase**: `brand, creator, system` |
| `DealMessageKind.java` L4-12 | **lowercase**: `text, system, proposal, contract, deliverable, payment, shipment` |
| `MilestoneStatus.java` L3-9 | `PENDING, FUNDED, RELEASED, REFUNDED, FROZEN` |
| `EscrowStatus.java` L3-9 | `PENDING, FUNDED, RELEASED, REFUNDED, FROZEN` |
| `MessageRole.java` L3-8 | `USER, ASSISTANT, SYSTEM, TOOL` |
| `ContractStatus.java` L3-9 | `DRAFT, PENDING_SIGNATURES, ACTIVE, COMPLETED, CANCELLED` |
| `ConversationStatus.java` L3-8 | `ACTIVE, CAMPAIGN_CREATED, DORMANT, PAUSED_CREDITS` |
| `CreatorApplicationStatus.java` L9-13 | `PENDING, APPROVED, REJECTED` |
| `ReleaseCondition.java` L15-22 | `ON_APPROVAL, ON_POSTED, ON_VERIFIED_METRICS` |
| `MeeraToolName.java` L3-11 | `create_campaign, request_payment, confirm_launch, show_creators, calculate_budget, get_campaign_performance` (**6**, despite javadoc still saying "5-tool whitelist") |
| `MeeraToolTier.java` L8-17 | `R, D, C, FORBIDDEN` |
| `UserType.java` | `BRAND, CREATOR, ADMIN` |
| `MemberRole.java` | `OWNER, ADMIN, MANAGER, MEMBER, VIEWER` |

## 1.4 `Campaign`
`…\domain\entity\Campaign.java`. `@Entity @Table(name = "campaigns")` (L17-19).

Fields: L21-23 `id` (26); L25-26 `workspace_id`; L28-29 `title` (300); L31-32 `description` TEXT; L34-36 `@Enumerated(STRING) status` (`CampaignStatus`); L38-42 `budget_min`/`budget_max` `DECIMAL(12,2)`; L44-45 `currency` (3, NOT NULL); L47-54 `start_date`/`end_date`/`application_deadline` (`LocalDate`); JSON columns via `@JdbcTypeCode(SqlTypes.JSON)`: L56-58 `platforms`→`platformsJson`, L60-62 `content_types`→`contentTypesJson`, L64-66 `objectives`→`objectivesJson`, L68-70 `requirements`→`requirementsJson`, L72-74 `hashtags`→`hashtagsJson`, L76-78 `target_audience`→`targetAudienceJson`; L80-81 `brand_guidelines` TEXT; L83-84 `is_private` (`boolean isPrivate`); L86-87 `max_collaborators` `Integer`; L89-90 `created_by`; L92-94 `@Enumerated(STRING) @Column(name = "campaign_type") private CampaignIntentType campaignType;` (nullable); L104-106 `hype_config`→`hypeConfigJson`; L113-114 `commission_rate` `DECIMAL(5,4)` nullable;
**V72 end-brand:**
- L122-123 `@Column(name = "end_brand_name", length = 200) private String endBrandName;`
- L125-126 `@Column(name = "end_brand_category", length = 100) private String endBrandCategory;`
L128-132 `created_at` (updatable=false) / `updated_at`.

Builder: L257 `public static Builder builder()`; `Builder` methods (L264-394) include `.endBrandName(String)` (L380) and `.endBrandCategory(String)` (L385), `.campaignType(CampaignIntentType)` (L370), `.hypeConfigJson(String)` (L391). `build()` (L396-407) defaults `currency="INR"`, `status=DRAFT`.

`applyPatch` — L410-431, **21 params in this exact order**:
```java
public void applyPatch(
        String title, String description, CampaignStatus status,
        BigDecimal budgetMin, BigDecimal budgetMax, String currency,
        LocalDate startDate, LocalDate endDate, LocalDate applicationDeadline,
        String platformsJson, String contentTypesJson, String objectivesJson,
        String requirementsJson, String hashtagsJson, String targetAudienceJson,
        String brandGuidelines, Boolean isPrivate, Integer maxCollaborators,
        String hypeConfigJson, String endBrandName, String endBrandCategory)
```
Also L469 `public void applyAdminBudgetOverride(BigDecimal newBudget)`, L477 `public Campaign duplicateCopy(String newId, String newTitle, String createdBy)`, L156 `public void setStatus(CampaignStatus)`, L253 `public void touch()`.

## 1.5 `CreatorAgentPreferences`
`…\domain\entity\CreatorAgentPreferences.java`. `@Entity @Table(name = "creator_agent_preferences")` (L26-28).
**`creatorId` is a `creator_profiles.id`, NOT a `users.id`** (class javadoc L21-24; FK in V73 → `creator_profiles(id)`).

Constants:
- L30 `public static final int APPROVAL_LEVEL_DRAFT_ONLY = 0;`
- L31 `public static final int APPROVAL_LEVEL_ROUTINE = 1;`
- L32 `public static final int APPROVAL_LEVEL_AUTO_DECLINE = 2;`
- L34 `public static final String TONE_FORMAL = "FORMAL";`
- L35 `public static final String TONE_FRIENDLY = "FRIENDLY";`
- **L37 `public static final String DEFAULT_LANGUAGE = "hi-IN";`**
- **L46 `public static final String CURRENT_CONSENT_VERSION = "v1";`**
- L48 `public static final String DEFAULT_WORKING_HOURS_TIMEZONE = "Asia/Kolkata";`
- L49 `public static final String DEFAULT_FLOOR_CURRENCY = "INR";`

Fields (every one, with column name):
| Line | Column | Java |
|---|---|---|
| 51-53 | `id` (26) | `String id` |
| 55-56 | `creator_id` NOT NULL UNIQUE (26) | `String creatorId` |
| 58-59 | `reel_floor` `DECIMAL(12,2)` | `BigDecimal reelFloor` |
| 61-62 | `story_set_floor` | `BigDecimal storySetFloor` |
| 64-65 | `post_floor` | `BigDecimal postFloor` |
| 68-69 | `floor_currency` NOT NULL (3) | `String floorCurrency` |
| 72-73 | `excluded_categories` TEXT | `String excludedCategoriesJson` (JSON array of names) |
| 76-77 | `blocked_brands` TEXT | `String blockedBrandsJson` |
| 79-80 | `approval_level` NOT NULL | **`int approvalLevel` (primitive `int`, NOT an enum)** |
| 82-83 | `creator_language` NOT NULL (10) | `String creatorLanguage` |
| 85-86 | `brand_tone` NOT NULL (16) | `String brandTone` |
| 88-89 | `working_hours_start` | `Integer workingHoursStart` |
| 91-92 | `working_hours_end` | `Integer workingHoursEnd` |
| 100-101 | `working_hours_timezone` NOT NULL (64) | `String workingHoursTimezone` |
| 104-105 | `working_days` TEXT | `String workingDaysJson` (JSON array of ISO-8601 weekday ints 1–7, stored as strings) |
| 107-108 | `weekly_sponsored_limit` | `Integer weeklySponsoredLimit` |
| 110-111 | `represented` NOT NULL | `boolean represented` |
| 113-114 | `agency_name` (200) | `String agencyName` |
| 117-118 | `consent_accepted_at` | `Instant consentAcceptedAt` |
| 125-126 | `consent_version` NOT NULL (16) | `String consentVersion` |
| 138-139 | `ai_monthly_cap_usd` `DECIMAL(6,2)` | `BigDecimal aiMonthlyCapUsd` |
| 141-142 | `created_at` updatable=false | `Instant createdAt` |
| 144-145 | `updated_at` | `Instant updatedAt` |

Methods:
- L147 `protected CreatorAgentPreferences() {}`
- L155-157 `public static CreatorAgentPreferences newWithDefaults(String id, String creatorId, BigDecimal reelFloor, BigDecimal storySetFloor, BigDecimal postFloor, String creatorLanguage)` — sets `approvalLevel=APPROVAL_LEVEL_DRAFT_ONLY`, `brandTone=TONE_FRIENDLY`, `represented=false`, `consentVersion=CURRENT_CONSENT_VERSION` (but `consentAcceptedAt` stays null), `workingHoursTimezone=DEFAULT_WORKING_HOURS_TIMEZONE`, `floorCurrency=DEFAULT_FLOOR_CURRENCY`
- L268 `public void setAiMonthlyCapUsdOverride(BigDecimal aiMonthlyCapUsd)`
- **L282 `public boolean isConsentAccepted() { return consentAcceptedAt != null && CURRENT_CONSENT_VERSION.equals(consentVersion); }`**
- L299-315 `public void applyPreferences(BigDecimal reelFloor, BigDecimal storySetFloor, BigDecimal postFloor, String floorCurrency, String excludedCategoriesJson, String blockedBrandsJson, int approvalLevel, String creatorLanguage, String brandTone, Integer workingHoursStart, Integer workingHoursEnd, String workingHoursTimezone, String workingDaysJson, Integer weeklySponsoredLimit, boolean represented, String agencyName)` — 16 params; `agencyName` is nulled when `represented == false` (L331)
- L343 `public void recordConsent()` — idempotent unless version stale
- L357 `public void withdrawConsent()` — nulls `consentAcceptedAt` only

Getters (all present): `getId, getCreatorId, getReelFloor, getStorySetFloor, getPostFloor, getFloorCurrency, getExcludedCategoriesJson, getBlockedBrandsJson, getApprovalLevel, getCreatorLanguage, getBrandTone, getWorkingHoursStart, getWorkingHoursEnd, getWorkingHoursTimezone, getWorkingDaysJson, getWeeklySponsoredLimit, isRepresented, getAgencyName, getConsentAcceptedAt, getConsentVersion, getAiMonthlyCapUsd, getCreatedAt, getUpdatedAt`.

## 1.6 `CreatorProfile`
`…\domain\entity\CreatorProfile.java`. `@Entity @Table(name = "creator_profiles")` (L18-20).
Key fields: L22-24 `id`; L26-27 `user_id` NOT NULL UNIQUE; L29-30 `display_name` NOT NULL (100); L32-33 `username` (80); L35-36 `bio` TEXT; L38-39 `avatar_url` (500); L41-42 `cover_image_url`; L44-45 `city` (100);
- **L47-49 `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "categories", columnDefinition = "json") private String categoriesJson;`** — format is a **JSON array of strings**, read via `JsonLists.stringListFromJson(...)`
- L51-53 `languages`→`languagesJson`; L55-57 `content_styles`→`contentStylesJson`; L59-61 `portfolio_settings_json`; L70-72 `theme_tags`→`themeTagsJson`
- **L74-75 `@Column(name = "rate_min", precision = 12, scale = 2) private BigDecimal rateMin;`**
- **L77-78 `@Column(name = "rate_max", precision = 12, scale = 2) private BigDecimal rateMax;`**
- L80-81 `currency` (3, NOT NULL); L83-84 `is_verified`→`boolean verified`; L86-87 `is_discoverable`→`boolean discoverable`
- **L89-90 `@Column(name = "engagement_rate", precision = 5, scale = 2) private BigDecimal engagementRate;`**
- **L92-93 `@Column(name = "total_followers", nullable = false) private long totalFollowers;`** (primitive `long`)
- L95-96 `username_changed_at`; L103-119 suspension block (`is_suspended`, `suspended_reason`, `suspended_at`, `suspended_by`, `reinstated_at`, `reinstated_by`); L121-132 `application_status` (`CreatorApplicationStatus`), `application_reviewed_by/at`, `application_rejection_reason`
- **L140-142 `@Enumerated(EnumType.STRING) @Column(name = "tier_override", length = 20) private CreatorTier tierOverride;`** + L144-148 `tier_adjusted_by` / `tier_adjusted_at`
- L155-163 `gstin` (15), `pan` (10), `tax_registration_status`; L171-172 `creator_invoice_code` (12); L181-191 `identity_kyc_status` (`VerificationStatus`), `aadhaar_last4` (4), `selfie_url` (500); L193-197 timestamps.

Methods: L205 `public static CreatorProfile newForUser(String id, String userId, String displayName)`; L404 `applyIdentityKyc`; L419 `applyTaxIdentity`; L440-451 `applySelfEdit(String displayName, String bio, String avatarUrl, String coverImageUrl, String city, String categoriesJson, String languagesJson, String contentStylesJson, BigDecimal rateMin, BigDecimal rateMax, Boolean discoverable)`; L472 `applyAggregatedStats(long totalFollowers, BigDecimal engagementRate)`; L479 `applyUsername`; L485 `applyPortfolioSettingsJson`; L491 `applyCoverImageUrl`; L508 `applyApplicationDecision`; L530 `applyAdminProfileEdit(String displayName, String categoriesJson)`; L544 `applyTierAdjustment(CreatorTier newTier, String adminId)`; L563 `suspend`; L581 `reinstate`.

## 1.7 `CreatorMetric`
`…\domain\entity\CreatorMetric.java`. `@Table(name = "creator_metrics")` (L22). Immutable — builder only, no mutators.
- L36 `public static final String DATA_SOURCE_META_API = "META_API";`
- L48 `public static final String DATA_SOURCE_CREATOR_REPORTED = "CREATOR_REPORTED";`
- L61 `public boolean isPlatformVerified()`
Fields: L66-67 `id`; L69-70 `time` `DATETIME(6)` NOT NULL `Instant`; L72-73 `creator_profile_id` NOT NULL (26); L75-76 `platform` NOT NULL (20); L79-80 `username` (200); L82-83 `followers` NOT NULL `long`; L85-86 `following` `Long`; L88-89 `media_count` `Integer`; L91-92 `avg_engagement_rate` `DECIMAL(8,4)` `BigDecimal`; L94-95 `avg_reach_per_post` `Long`; L97-98 `avg_impressions_per_post` `Long`; L100-101 `profile_views` `Long`; L103-104 `website_clicks` `Long`; L106-107 `data_source` NOT NULL (20) `String`; L109-110 `fetched_at` `DATETIME(6)` NOT NULL; L112-113 `created_at`.
Builder at L181/L185 with methods matching every field.

## 1.8 `DealMessage`
`…\domain\entity\DealMessage.java`. `@Table(name = "deal_messages")` (L27).
Fields: L53-54 `id`; L56-57 `collaboration_id` NOT NULL (26); L59-61 `@Enumerated(STRING) private DealMessageKind kind;`; L63-64 `sender_id` NOT NULL (26); L66-68 `@Enumerated(STRING) @Column(name = "sender_type", nullable = false) private DealSenderType senderType;`; L70-71 `content` TEXT; L73-75 `metadata` JSON → `metadataJson`; L77-79 `read_by_json` JSON → `readByJson`; L81-82 `created_at`.
Factory (L86-105, verbatim):
```java
public static DealMessage create(
        String id,
        String collaborationId,
        DealMessageKind kind,
        String senderId,
        DealSenderType senderType,
        String content,
        String metadataJson)
```
Sets `readByJson = "[]"`, `createdAt = Instant.now()`.
Other: L143 `public void setReadByJson(String readByJson)`; L178 `public void settleStatus(String status)`.

## 1.9 `PaymentMilestone`
`…\domain\entity\PaymentMilestone.java`. `@Table(name = "payment_milestones")` (L20).
Fields: L24-25 `id`; L27-28 `contract_id` NOT NULL; L30-31 `collaboration_id` NOT NULL; L33-34 `sequence_no` NOT NULL `int`; L36-37 `description` (300); L39-40 `amount` NOT NULL `DECIMAL(12,2)`; L42-43 `currency` NOT NULL (3); L45-46 `due_date` `LocalDate`; L48-50 `@Enumerated(STRING) status` (`MilestoneStatus`); L52-53 `escrow_hold_id` (26); L55-56 `released_txn_id` (26); L58-59 `idempotency_key` (64); L69-71 `@Enumerated(STRING) @Column(name="release_condition", nullable=false) private ReleaseCondition releaseCondition;`; L73-77 timestamps.
Mutators: L141 `markFunded(String escrowHoldId)`; L147 `markReleased(String releasedTxnId, String idempotencyKey)`; L154 `markRefunded(...)`; L170 `markPayoutQueued(String idempotencyKey)`; L175 `markFrozen()`; L180 `touch()`.
Builder L184/188; `build()` (L241-255) defaults `currency="INR"`, `status=PENDING`, `releaseCondition=ON_POSTED`.

## 1.10 `EscrowHold`
`…\domain\entity\EscrowHold.java`. `@Table(name = "escrow_holds")` (L20).
Fields: L23-24 `id`; L27-28 `workspace_id` NOT NULL; L30-31 `collaboration_id` (nullable); L33-34 `campaign_id` (nullable); L36-37 `milestone_id` (nullable); L39-40 `amount` NOT NULL `DECIMAL(14,2)`; L42-43 `currency` NOT NULL (3); L45-47 `@Enumerated(STRING) status` (`EscrowStatus`); L49-50 `hold_txn_id`; L52-53 `release_txn_id`; L55-56 `idempotency_key` **NOT NULL** (64); L58-59 `funded_at`; L61-62 `released_at`; L64-68 timestamps.
Mutators: L90 `bindCollaboration(String)`; L105 `setMilestoneId(String)`; L151 `markFunded(String holdTxnId)`; L159 `markReleased(String releaseTxnId)`; L167 `markRefunded(String releaseTxnId)`; L174 `markFrozen()`. Builder `build()` defaults `currency="INR"`, `status=PENDING`.

## 1.11 `MeeraCreatorConversation`
`…\domain\entity\MeeraCreatorConversation.java`. `@Table(name = "meera_creator_conversations")` (L18).
Fields: L22-23 `id`; L25-26 `creator_id` NOT NULL (26) — **`creator_profiles.id`**; L28-29 `conversation_id` NOT NULL **UNIQUE** (26); L31-32 `started_at` NOT NULL; L34-35 `last_message_at` NOT NULL; L37-38 `message_count` NOT NULL `int`.
L42 `public static MeeraCreatorConversation start(String id, String creatorId, String conversationId, Instant when)` — `messageCount = 0`.
L78 `public void recordMessage(Instant when)` — `messageCount += 1`, advances `lastMessageAt` only if `when` is later.

## 1.12 `AiConversation` / `AiMessage`
`…\domain\entity\AiConversation.java` — `@Table(name = "ai_conversations")` (L13). Fields: L17-18 `id`; L20-21 `workspace_id` NOT NULL (26) — **for a CREATOR session this carries the creator's USER id**; L23-24 `started_by` NOT NULL; L26-28 `@Enumerated(STRING) status` (`ConversationStatus`); L30-31 `title` (200); L33-34 `created_at`; L36-37 `last_message_at`. Methods: L57 `setStatus`, L65 `setTitle`, L77 `markMessageAt(Instant when)`; builder L81/85.

`…\domain\entity\AiMessage.java` — `@Table(name = "ai_messages")` (L15). Fields: L19-20 `id`; L22-23 `conversation_id` NOT NULL; **L25-27 `@Enumerated(EnumType.STRING) @Column(nullable = false) private MessageRole role;`**; L29-30 `content` TEXT; L32-34 `metadata` JSON → `metadataJson`; L36-37 `credits_charged` NOT NULL `int`; L39-40 `created_at`. Builder L72/76 with `.id .conversationId .role .content .metadataJson .creditsCharged`.

## 1.13 `Contract`
`…\domain\entity\Contract.java`. `@Table(name = "contracts")` (L18).
Fields: L22-23 `id`; L25-26 `collaboration_id` NOT NULL; L28-29 `workspace_id` NOT NULL; L31-32 `version` NOT NULL `int`; L34-36 `@Enumerated(STRING) status` (`ContractStatus`); L38-39 `total_amount` NOT NULL `DECIMAL(12,2)`; L41-42 `currency` NOT NULL (3); L44-45 `pdf_r2_key` (500);
- **L47-49 `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "terms", columnDefinition = "json") private String termsJson;`** — note: this holds a **SHA-256 tamper hash of the generate request**, NOT terms text (comment L51-56)
- **L65-66 `@Column(name = "terms_text", columnDefinition = "text", updatable = false) private String termsText;`** — the actual free-text terms; immutable, no setter, builder-only
L68-69 `brand_signed_at`; L77-78 `brand_signer_name` (255); L80-81 `creator_signed_at`; L83-84 `creator_signer_name` (255); L86-90 `effective_date`/`expiration_date` `LocalDate`; L92-96 timestamps.
Methods: L132 `setPdfR2Key`, L186 `recordBrandSignature(String signerName)`, L202 `recordCreatorSignature(String signerName)`, L219 `setStatus(ContractStatus)`, L224 `touch()`. Builder L228/232 incl. `.termsJson(String)` (L275) and `.termsText(String)` (L281); `build()` defaults `currency="INR"`, `status=DRAFT`, `version=1`.

## 1.14 `MetaOAuthToken`
`…\domain\entity\MetaOAuthToken.java`. `@Table(name = "meta_oauth_tokens")` (L32).
Fields: L36-37 `id`; L39-40 `workspace_id` (26, **nullable**); L42-43 `creator_profile_id` NOT NULL (26); L52-54 `@Enumerated(STRING) @Column(name="auth_path", nullable=false, length=32) private MetaAuthPath authPath = MetaAuthPath.FACEBOOK_LOGIN;`; L63-64 `ig_business_account_id` (64); L77-78 `meta_user_id` (64); L80-81 `encrypted_access_token` NOT NULL TEXT; L83-84 `expires_at` NOT NULL; L86-88 `granted_scopes` JSON → `grantedScopesJson`; L90-91 `revoked` NOT NULL `boolean`; L93-94 `last_refreshed_at`; L96-100 timestamps.
Methods: L120 `setAuthPath`, L130 `applyIgBusinessAccountId(String)`, L168 `rotateToken(String encryptedAccessToken, Instant expiresAt, String grantedScopesJson)`, L176 `revoke()`. Builder L185/189 incl. `.createdAt(Instant)` override.

---

# 2. SERVICES — exact public method signatures

## 2.1 `DealService`
`…\service\DealService.java` — `@Service` L94, `public class DealService` L95. Constants: L99 `MESSAGE_PAGE_SIZE = 50`.

Public API:
| Line | Signature |
|---|---|
| L152-153 | `@Transactional(readOnly = true) public List<DealResponse> list(AuthPrincipal principal, String statusFilter)` |
| L181-182 | `@Transactional(readOnly = true) public DealResponse get(AuthPrincipal principal, String dealId)` |
| **L242-243** | `@Transactional public DealResponse createProposal(AuthPrincipal principal, CreateDealRequest body)` |
| L346-347 | `@Transactional public DealResponse accept(AuthPrincipal principal, String dealId, String idempotencyKey)` |
| L398-400 | `@Transactional public OkResponse reject(AuthPrincipal principal, String dealId, RejectRequest body, String idempotencyKey)` |
| L557-559 | `@Transactional public DealResponse counter(AuthPrincipal principal, String dealId, CounterRequest body, String idempotencyKey)` |
| L601-602 | `@Transactional(readOnly = true) public List<DealMessageResponse> listMessages(AuthPrincipal principal, String dealId, String before)` |
| L618-619 | `@Transactional public DealMessageResponse sendMessage(...)` |
| L736-737 | `@Transactional(readOnly = true) public void authorizeMessageStream(AuthPrincipal principal, String dealId)` |
| L1070-1071 | `@Transactional public OkResponse markRead(AuthPrincipal principal, String dealId)` |
| L1093-1094 | `@Transactional(readOnly = true) public List<DeliverableListItem> listDeliverables(AuthPrincipal principal, String dealId)` |
| L1483-1484 | `@Transactional(readOnly = true) public List<DealResponse> listEligibleForDispute(AuthPrincipal principal)` |

Private (relevant to Phase B):
- L436 `private OkResponse doReject(String dealId, UserType role, String actorId, RejectRequest body)`
- L1103 `private DealResponse doAccept(Collaboration collaboration, AuthPrincipal principal, UserType role)`
- **L1268-1272 `private DealResponse doCounter(Collaboration collaboration, AuthPrincipal principal, CounterRequest body, DealSenderType senderType)`** — L1299 `collaboration.updateAgreedRate(body.amount())`; L1304-1306 sets usageRights only when non-blank; **L1307 `applyDealTermsIfPresent(collaboration, body.dealTerms());`**; L1308 `transitionTo(IN_NEGOTIATION)`; L1319 `settleLatestProposal(collaboration.getId(), "countered")`; L1327-1346 carries deliverables forward from superseded card
- L1975-1976 `private DealResponse toDealResponse(Collaboration collaboration, AuthPrincipal principal, UserType role)`
- **L1987-1991 `private DealResponse toDealResponse(Collaboration collaboration, AuthPrincipal principal, UserType role, List<Deliverable> deliverables)`** — final `new DealResponse(...)` at L2050-2072, argument order: `id, campaignId, campaignName, counterparty.id(), counterparty.profileId(), counterparty.name(), counterparty.avatar(), counterparty.handle(), counterparty.verificationStatus(), status, agreedRate, currency, lastMessage, lastMessageAt, unread, deliverablesDone, deliverablesTotal, nextDeadline, contractId, contractStatus, escrowFunded, toDealTermsDto(collaboration)`
- **L2081 `private static void applyDealTermsIfPresent(Collaboration collaboration, DealTermsDto terms)`** — null `terms` = no-op; `usageChannels` joined with `","`; `exclusivityBrands` JSON-serialised **only** when `exclusivityScope == NAMED_BRANDS` else `null`; `maxRevisions` defaults to literal `2` when the DTO field is null (L2100)
- **L2109 `private static DealTermsDto toDealTermsDto(Collaboration collaboration)`** — returns `null` when nothing is set (`anySet` check L2110-2119); splits `usageChannels` on `","`; reads `exclusivityBrands` via `JsonLists.stringListFromJson`
- L2143 `private Counterparty resolveCounterparty(...)`; L2365 `private record Counterparty(...)`

**Creator-initiated deals:** there is **no** creator "invite" path on `DealService`. A creator-initiated collaboration is created by `CreatorCampaignService.apply(...)` via `Collaboration.apply(...)` (§2.7). A creator can only **counter** an existing deal (`counter` branches on `principal.getUserType()`: L1279-1285 `DealSenderType senderType = role == UserType.CREATOR ? DealSenderType.creator : DealSenderType.brand;`). `createProposal` is brand-only — L253 `requireBrandDealManagerWorkspace(principal)`.

Brand-side revive path: L264-273 `collaborationReviveService.reviveOrRefuse(campaignId, creatorUserId, CollaborationStatus.IN_NEGOTIATION, CollaborationSource.INVITATION, message, currency, "COLLABORATION_EXISTS", "A deal already exists for this campaign and creator")`.

## 2.2 `RateEstimationService`
`…\service\scoring\RateEstimationService.java` — `@Service` L31.
- L35-43 `TIER_BASE_RATES` (INR min/max): `NANO {1000,5000}`, `MICRO {5000,25000}`, `MID {25000,100000}`, `MACRO {100000,500000}`, `MEGA {500000,2500000}`
- L45-55 `CATEGORY_MULTIPLIERS`: FASHION 1.3, BEAUTY 1.25, LIFESTYLE 1.2, TRAVEL 1.15, FOOD 1.1, TECH 1.05, FITNESS 1.0, GAMING 0.95, EDUCATION 0.9

**Output record (L57-63, verbatim):**
```java
public record RateEstimation(
        BigDecimal min,
        BigDecimal max,
        String currency,
        BigDecimal confidence, // 0-100
        String tier,
        Map<String, Object> factors) {}
```

**Method (L76-77, verbatim):**
```java
public RateEstimation estimate(
        Optional<CreatorMetric> metric, QualityScoreResult qualityScore, List<String> categories)
```
Empty-metric branch (L79-82) returns `new RateEstimation(BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "UNKNOWN", Map.of())`.

## 2.3 `QualityScoreService`
`…\service\scoring\QualityScoreService.java` — `@Service` L30.
```java
public record QualityScoreResult(          // L33-38
        BigDecimal overall,          // 0-100 composite, or null when UNSCORED
        BigDecimal engagementScore,
        BigDecimal consistency,
        BigDecimal frequency,
        BigDecimal audienceMatch) {
    public static QualityScoreResult absent() { … }   // L48
    public boolean isAbsent() { … }                   // L53
}
public QualityScoreResult calculate(                  // L58-59
        Optional<CreatorMetric> latestMetric, List<MediaMetric> recentMedia)
```
Returns `absent()` when `latestMetric.isEmpty() || recentMedia.isEmpty()` (L70-72). Weights in comments: engagement 40 %, consistency 25 %, frequency 20 %, audienceMatch 15 %.

## 2.4 `CreatorAgentPreferencesService`
`…\service\CreatorAgentPreferencesService.java` — `@Service` L38.
Floor fallbacks: L42 `FALLBACK_REEL_FLOOR = new BigDecimal("500")`; L44 `FALLBACK_STORY_FLOOR = new BigDecimal("300")`; L45 `FALLBACK_POST_FLOOR = new BigDecimal("600")` (the latter two are declared but **unused** — `computeDefaultFloor` only ever returns `FALLBACK_REEL_FLOOR`, L141).

Constructor (L53-58): `(CreatorAgentPreferencesRepository, CreatorProfileRepository, CollaborationRepository, CreatorMetricsRepository, RateEstimationService)`.

| Line | Signature |
|---|---|
| L67 | `public CreatorProfile requireCreatorProfile(String userId)` → 404 `CREATOR_PROFILE_NOT_FOUND` |
| **L76-77** | `@Transactional public PreferencesResponse getOrCreatePreferences(String userId)` |
| L94-95 | `@Transactional(readOnly = true) public boolean isConsentAccepted(String userId)` — read-only, does NOT lazily create a row |
| **L149-150** | `@Transactional public PreferencesResponse updatePreferences(String userId, UpdatePreferencesRequest req)` |
| **L223-224** | `@Transactional public com.influora.web.dto.creator.CreatorAgentDtos.ConsentResponse recordConsent(String userId)` |
| L240-241 | `@Transactional public void withdrawConsent(String userId)` |
| L265-266 | `@Transactional public BigDecimal adminSetMonthlyCapOverride(String creatorProfileId, BigDecimal capUsd)` — **keyed by `creator_profiles.id`**, not userId |

**Floor-defaults logic** — L119-142 `private BigDecimal computeDefaultFloor(CreatorProfile profile)`:
1. Last `COMPLETED` collaboration (`collaborationRepository.findByCreatorId(profile.getUserId())`, max by `getUpdatedAt`) with non-null `agreedRate > 0` → that rate.
2. Else `rateEstimationService.estimate(latestMetric, QualityScoreResult.absent(), categories).min()` if `> 0`.
3. Else `FALLBACK_REEL_FLOOR` (500).
The single computed value is applied to **all three** floors (L107-108 `newWithDefaults(ulid, profile.getId(), floor, floor, floor, language)`).
Default language: L144-147 first entry of `profile.getLanguagesJson()`, else `DEFAULT_LANGUAGE`.

Validation (L179-216, `validate(UpdatePreferencesRequest)`) error codes: `INVALID_APPROVAL_LEVEL` (400), `INVALID_BRAND_TONE` (400), `AGENCY_NAME_REQUIRED` (400), `INVALID_FLOOR` (400), `INVALID_CURRENCY` (400, via `java.util.Currency.getInstance`), `INVALID_TIMEZONE` (400, via `java.time.ZoneId.of`).

`toResponse` (L285-307) argument order matches `PreferencesResponse` (§4.3).

## 2.5 `MeeraContextService`
`…\service\meera\MeeraContextService.java` — `@Service` L66.
Constants: L70 `PAST_CAMPAIGN_LIMIT = 5`; L72 `BRAND_AUDIENCE = "BRAND"`; L75 `CREATOR_AUDIENCE = "CREATOR"`; L84 `FUNDED_STATUSES`; L93 `ACTIVE_DEAL_STATUSES`.
Constructor L112-125 — 13 args: `WorkspaceRepository, BrandProfileRepository, CampaignTemplateRepository, CampaignRepository, CollaborationRepository, EscrowHoldRepository, DeliverableMetricRepository, UtmCampaignRepository, AICreditService, BrandContextAssembler, CreatorProfileRepository, CreatorAgentPreferencesRepository, CreatorMetricsRepository`.

Public entry point (L150-156):
```java
@Transactional(readOnly = true)
public Object assemble(String workspaceId, String audience) {
    if (CREATOR_AUDIENCE.equalsIgnoreCase(audience)) {
        return assembleCreatorContext(workspaceId);
    }
    return assembleBrand(workspaceId, audience);
}
```

**`private CreatorContextResponse assembleCreatorContext(String creatorUserId)` — L219.**
`creatorUserId` is a **users.id**; resolved L220-226 via `creatorProfileRepository.findByUserId(...)` → else `ApiException("CREATOR_PROFILE_NOT_FOUND", …, NOT_FOUND)`. Prefs looked up by `profile.getId()` (L229). Locale from `creatorLanguage` (L235). Floors map keys: `"reel_floor"`, `"story_set_floor"`, `"post_floor"` (L239-241). Latest metric via `creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(profile.getId(), PageRequest.of(0,1))` (L245-246). Collaborations via `collaborationRepository.findByCreatorId(creatorUserId)` (L251).

**Construction order (L274-301) — exactly 25 arguments:**
```
1  creatorUserId                                        -> workspace_id
2  CREATOR_AUDIENCE ("CREATOR")                         -> audience
3  profile.getDisplayName()                             -> display_name
4  firstNameOf(profile.getDisplayName())                -> first_name
5  profile.getCity()                                    -> city
6  tier                                                 -> tier
7  JsonLists.stringListFromJson(profile.getCategoriesJson()) -> categories
8  creatorLanguage                                      -> creator_language
9  prefs.getBrandTone() ?: TONE_FRIENDLY                -> brand_tone
10 floors (LinkedHashMap<String,String>)                -> floors
11 metricsSummary                                       -> metrics_summary
12 dealsSummary                                         -> deals_summary
13 prefs.getApprovalLevel() ?: APPROVAL_LEVEL_DRAFT_ONLY-> approval_level
14 prefs != null && prefs.isRepresented()               -> represented
15 prefs.getAgencyName()                                -> agency_name
16 excludedCategories                                   -> excluded_categories
17 blockedBrands                                        -> blocked_brands
18 prefs.getWorkingHoursStart()                         -> working_hours_start
19 prefs.getWorkingHoursEnd()                           -> working_hours_end
20 prefs.getWorkingHoursTimezone() ?: "Asia/Kolkata"    -> working_hours_timezone
21 workingDays (List<Integer>)                          -> working_days
22 prefs.getWeeklySponsoredLimit()                      -> weekly_sponsored_limit
23 prefs.getFloorCurrency() ?: "INR"                    -> floor_currency
24 identity (LinkedHashMap<String,Boolean>)             -> identity
25 prefs != null && prefs.isConsentAccepted()           -> consent_accepted
26 prefs.getConsentVersion()                            -> consent_version
27 formatCapUsd(prefs.getAiMonthlyCapUsd(), locale)     -> ai_monthly_cap_usd
```
(27 positional args; the record has 27 components.)

`identity` map (L254-256): `"kyc_done"` = `profile.getIdentityKycStatus() == VerificationStatus.VERIFIED`; `"gstin_present"` = non-blank gstin. **Nothing else.**
Tier (L258): `profile.getTierOverride() != null ? .name() : deriveTier(profile.getTotalFollowers())`.
**L415-421 `private static String deriveTier(long followers)` returns STRINGS `"MEGA"` (≥1 000 000), `"MACRO"` (≥500 000), `"MID"` (≥50 000), `"MICRO"` (≥10 000), else `"NANO"` — note `MEGA` is NOT in the `CreatorTier` enum.**

Helpers: L309 `formatCapUsd` (2 fraction digits); L321 `localeForLanguageTag`; L329 `putIfPresent`; L335 `formatDecimal` (`NumberFormat.getIntegerInstance`, grouping on); L353-354 `buildMetricsSummary(CreatorProfile, Optional<CreatorMetric>, Locale)` — keys `"followers"` (omitted entirely when no metric and `totalFollowers == 0`), `"reach_30d"`, `"engagement_rate"`; L382 `buildDealsSummary(List<Collaboration>, Locale)` — keys `"active_count"` (int), `"completed_count"` (int), `"total_earned_inr"` (formatted String); L400 `firstLanguageOrDefault`; L405 `firstNameOf`; L424 `buildPastCampaignSummary`; L457 `buildOutcomeDigest`.

## 2.6 `MeeraSessionService`
`…\service\meera\MeeraSessionService.java` — `@Service` L75.
Constants: L80 `TURN_CREDIT_COST = 1`; L81 `SEND_TURN_SCOPE = "meera.send_turn"`; L89 `static final String PERSIST_WRITEBACK_SCOPE = "meera.persist_writeback"`.
Deps (L91-100): `AiConversationRepository, AiMessageRepository, WorkspaceRepository, BrandProfileRepository, AICreditService, BrandContextAssembler, StreamTokenService, OnBehalfTokenService, IdempotencyService, CreatorAgentConversationService`.

| Line | Signature |
|---|---|
| L126-127 | `@Transactional public AiConversation startOrResume(String workspaceId, String userId)` (BRAND) |
| L143-144 | `@Transactional(readOnly = true) public BrandProfile getBrandProfile(String workspaceId)` |
| **L179-181** | `@Transactional public AiConversation startOrResumeForCreator(String creatorUserId, String userId, String creatorDisplayName, String creatorLanguage)` |
| L191-192 | `private AiConversation createConversationWithOnboardingGreeting(String creatorUserId, String userId, String creatorDisplayName, String creatorLanguage)` |
| L228 | `private static String onboardingGreeting(String creatorDisplayName, String creatorLanguage)` |
| L247 | `private static String onboardingFirstName(String displayName)` (falls back to `"there"`) |
| **L280-286** | `public TurnResult sendTurn(String workspaceId, String userId, UserType userType, String conversationId, String content, String idempotencyKey)` |
| **L308-310** | `@Transactional protected TurnResult doSendTurn(String workspaceId, String userId, UserType userType, String conversationId, String content)` |
| L413-414 | `@Transactional(readOnly = true) public AiConversation resolveConversation(String conversationId)` |
| L482-487 | `public AiMessage persistAssistantWriteback(String workspaceId, String conversationId, String content, Map<String, Object> metadata, String idempotencyKey)` (5-arg overload → delegates with `UserType.BRAND`) |
| **L502-508** | `public AiMessage persistAssistantWriteback(String workspaceId, String conversationId, String content, Map<String, Object> metadata, String idempotencyKey, UserType userType)` |
| L548 | `private AiMessage replayPersistedMessage(String workspaceId, String conversationId, String idempotencyKey)` |
| **L560-567** | `@Transactional protected AiMessage doPersistAssistantWriteback(String workspaceId, String conversationId, String content, Map<String, Object> metadata, String turnId, UserType userType)` |
| L635 | `public void releaseTurnCredit(String workspaceId, String turnId)` |
| L640-641 | `@Transactional(readOnly = true) public List<AiMessage> listMessages(String workspaceId, String conversationId)` |
| L663-664 | `@Transactional(readOnly = true) public List<AiMessage> listMessages(String workspaceId, String conversationId, String afterMessageId)` |

`startOrResumeForCreator` (L182-189) finds via `conversationRepository.findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc(creatorUserId, ConversationStatus.ACTIVE)`; on creation persists an ASSISTANT `AiMessage` with `creditsCharged(0)` and calls `creatorAgentConversationService.recordTurnForUser(creatorUserId, conversation.getId(), Instant.now())` (L214).

**`doSendTurn` flow (L311-402):**
1. L311-319 `conversationRepository.findByIdAndWorkspaceId(conversationId, workspaceId)` → 404 `CONVERSATION_NOT_FOUND`.
2. **L324 `String messageId = Ulids.newUlid();`** — server-minted turn id, generated before anything else.
3. L332 `boolean isCreatorTurn = userType == UserType.CREATOR;`
4. L334-343 BRAND only: `creditService.tryConsumeForTurn(workspaceId, TURN_CREDIT_COST, messageId)`.
5. L345-357 persists the USER `AiMessage` with `.id(messageId)`, `.creditsCharged(0)`.
6. L363-374 CREATOR → `sanitizedContext = Map.of();` (**no** `recordTurnForUser` here — moved to write-back). BRAND → `contextAssembler.assemble(workspace, brandProfile)`.
7. **L392-393 `String streamToken = streamTokenService.mint(workspaceId, conversationId, messageId, userId, userType);`**
8. **L396-397 `String onBehalfToken = onBehalfTokenService.mint(workspaceId, conversationId, messageId, userId, userType);`**
9. L402 `return new TurnResult(userMessage.getId(), null, streamToken, onBehalfToken, sanitizedContext, null);`

**`doPersistAssistantWriteback` (L568-617):** resolves conversation; `creditsCharged = 0` for CREATOR (L575-576), else `creditService.wasCharged(workspaceId, turnId) ? TURN_CREDIT_COST : 0` with a WARN when 0 (L585-593); saves ASSISTANT `AiMessage` with `metadataJson = metadata == null ? null : JsonLists.toJsonObject(metadata)` (L603); `conversation.markMessageAt(now)`; **L610-614 CREATOR → `creatorAgentConversationService.recordTurnForUser(workspaceId, conversationId, Instant.now())`** (`workspaceId` here IS the creator user id).

Result record (L683-689):
```java
public record TurnResult(
        String userMessageId,
        String assistantMessageId,
        String streamToken,
        String onBehalfToken,
        Map<String, Object> sanitizedContext,
        String placeholderReply) {}
```

## 2.7 `StreamTokenService` / `OnBehalfTokenService`
`…\service\meera\StreamTokenService.java` — `@Service` L57.
- L60 `MAX_TTL_SECONDS = 60`; L61 `public static final String STREAM_AUDIENCE = "meera-stream";`; L65 `public static final String ISSUER = "influora-api";`; L68 `public static final String SCOPE_CHAT_STREAM = "chat:stream";`; L74 `public static final String USER_TYPE_CLAIM = "userType";`
- **L95-96 `public String mint(String workspaceId, String conversationId, String messageId, String userId, UserType userType)`** — `Objects.requireNonNull(userType, …)` (L97); claims: `iss=ISSUER`, `sub=userId`, `aud=meera-stream`, `workspaceId`, `conversationId`, `messageId`, `scope=chat:stream`, `userType=<name>`; signed `Jwts.SIG.ES256` with `jwksKeyService.signingKey()` + `keyId(jwksKeyService.kid())`
- L128 `public Claims parse(String token)`

`…\service\meera\OnBehalfTokenService.java` — `@Service` L40.
- L43 `MAX_TTL_SECONDS = 120`; L46 `public static final String ONBEHALF_AUDIENCE = "meera-onbehalf";`; L49 `public static final String ISSUER = StreamTokenService.ISSUER;`
- L55 `public static final String SCOPE_READ_ONLY = "show_creators calculate_budget";`
- **L68-69 `public static final String SCOPE_DEFAULT = "show_creators calculate_budget create_campaign get_campaign_performance";`** — `request_payment` and `confirm_launch` are deliberately excluded
- **L82-87 `public String mint(String workspaceId, String conversationId, String turnId, String userId, UserType userType)`** — claims `workspaceId`, `userType`, `conversationId`, `turnId`, `scope=SCOPE_DEFAULT`; ES256
- L127 `public Claims verify(String token)` — requires `iss` + `aud`

## 2.8 `OnBehalfAuthResolver`
`…\security\OnBehalfAuthResolver.java` — `@Component` L51.
- **L78 `public record OnBehalfContext(String userId, String workspaceId, UserType userType, String conversationId) {}`**
- L86 `public OnBehalfContext resolveForWorkspace(String onBehalfJwt, String bodyWorkspaceId)`
- L119-120 `public OnBehalfContext resolveForWorkspaceRequiringElevatedRole(String onBehalfJwt, String bodyWorkspaceId)`
- L150-151 `public OnBehalfContext resolveForWorkspaceRequiringScope(String onBehalfJwt, String bodyWorkspaceId, String requiredTool)`
- L164-165 `public OnBehalfContext resolveForWorkspaceRequiringElevatedRoleAndScope(String onBehalfJwt, String bodyWorkspaceId, String requiredTool)`
- L178 `private void requireScope(String onBehalfJwt, String requiredTool)` — **`scope.trim().split("\\s+")` list must `contain(requiredTool)`**
- L191 `private Claims parseOrReject(String onBehalfJwt)`

**`ON_BEHALF_*` error codes (code, message, status):**
| Code | Status | Line |
|---|---|---|
| `ON_BEHALF_WORKSPACE_MISMATCH` | 403 FORBIDDEN | L94 |
| `ON_BEHALF_INVALID_CLAIMS` | 401 UNAUTHORIZED | L105 |
| `ON_BEHALF_NOT_A_MEMBER` | 403 FORBIDDEN | L129 |
| `ON_BEHALF_INSUFFICIENT_ROLE` | 403 FORBIDDEN | L134 |
| `ON_BEHALF_SCOPE_INSUFFICIENT` | 403 FORBIDDEN | L185 |
| `ON_BEHALF_JWT_MISSING` | 401 UNAUTHORIZED | L194 |
| `ON_BEHALF_JWT_INVALID` | 401 UNAUTHORIZED | L200 |

Elevated role check: `workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(...)`, role must be `OWNER` or `ADMIN` (L132).

## 2.9 `JwtService`
`…\security\JwtService.java` — `@Service` L16.
- L21 `public JwtService(JwtProperties props)`
- **L25 `public String createAccessToken(String userId, UserType userType, String email, String workspaceId)`**
- L41 `public String createRefreshTokenValue()`
- L45 `public Claims parseAccessToken(String token)`
- L53 `public long getAccessExpirySeconds()` / L57 `getRefreshExpirySeconds()`
- L61 `public static String hashToken(String raw)`
Access tokens are **HMAC**-signed and carry **no `aud` claim** — structurally rejected by `OnBehalfTokenService.verify` (which requires ES256 + `aud=meera-onbehalf`).

## 2.10 Tool executors — `com.influora.service.meera.tool`
**There is NO shared interface.** Each executor is a plain `@Service` with its own `execute(...)`. Dispatch is a hard-coded `@PostMapping` per tool in `MeeraInternalController` (§3.3) — there is no name→executor map.

| Class | File line | `execute` signature | Result DTO |
|---|---|---|---|
| `ShowCreatorsExecutor` | L35 / **L47-48** | `@Transactional(readOnly = true) public ShowCreatorsResult execute(String workspaceId, Map<String, Object> input)` | `MeeraToolDtos.ShowCreatorsResult` |
| `CalculateBudgetExecutor` | L28 / **L44** | `public CalculateBudgetResult execute(String workspaceId, Map<String, Object> input)` | `CalculateBudgetResult` |
| `CreateCampaignExecutor` | L74 / **L132-138** | `public CreateCampaignResult execute(String workspaceId, String conversationId, String userId, UserType userType, String idempotencyKey, Map<String, Object> input)` | `CreateCampaignResult` |
| `RequestPaymentExecutor` | L43 / **L63-64** | `public RequestPaymentResult execute(String workspaceId, String conversationId, String idempotencyKey, Map<String, Object> input)` | `RequestPaymentResult` |
| `ConfirmLaunchExecutor` | L110 / **L168-169** | `public ConfirmLaunchResult execute(String workspaceId, String conversationId, String idempotencyKey, Map<String, Object> input)` | `ConfirmLaunchResult` |
| `GetCampaignPerformanceExecutor` | L61 / **L102** | `public GetCampaignPerformanceResult execute(String workspaceId, Map<String, Object> input)` | `GetCampaignPerformanceResult` |
| `ToolCallValidator` | L29 | `public MeeraToolName validateAndResolve(String rawToolName, String workspaceId)` (L72); `public MeeraToolTier tierOf(MeeraToolName toolName)` (L108) | — |

`ToolCallValidator` (`…\service\meera\tool\ToolCallValidator.java`) tier map (L38-45):
```
show_creators -> R ; calculate_budget -> R ; create_campaign -> D ;
request_payment -> C ; confirm_launch -> C ; get_campaign_performance -> R
```
Rejection: `public static final class ToolCallRejectedException extends RuntimeException` (L53) with `getReasonCode()` (L61); codes `"UNKNOWN_TOOL_NAME"` (L87) and `"FORBIDDEN_TIER"` (L102); every rejection also writes `auditLogService.recordToolCall(workspaceId, name, tier, AuditLogService.OUTCOME_REJECTED, reasonCode, null, null, Map)`.

**`ShowCreatorsExecutor` reference implementation** (`…\service\meera\tool\ShowCreatorsExecutor.java`):
- L37 `MAX_RESULTS = 10`; constructor L42 `(CreatorProfileRepository, AuditLogService)`
- Input keys read from the raw map (matching `app/tools/schemas.py`): `"niche"`, `"city"`, `"count"` (L51-53); limit clamped `Math.min(Math.max(count,1), MAX_RESULTS)`
- Query: `CreatorProfileSpecifications.combine(CreatorProfileSpecifications.nameSearch(niche), CreatorProfileSpecifications.singleCity(city))`, `PageRequest.of(0, limit, Sort.by(DESC, "totalFollowers"))` (L56-63)
- Maps to `new CreatorSummary(p.getId(), p.getDisplayName(), p.getCity(), JsonLists.stringListFromJson(p.getCategoriesJson()), p.getTotalFollowers(), p.getEngagementRate(), p.isVerified())` (L69-76)
- Audit: `auditLogService.recordToolCall(workspaceId, "show_creators", "R", AuditLogService.OUTCOME_ALLOWED, null, null, null, Map.of("resultCount", summaries.size()))` (L79-87)
- Private helpers L92 `stringArg(Map,String)`, L97 `intArg(Map,String)`

## 2.11 `CreatorAgentConversationService`
`…\service\CreatorAgentConversationService.java` — `@Service` L42. Constructor L50-55: `(MeeraCreatorConversationRepository, CreatorProfileRepository, AiConversationRepository, AiMessageRepository)`.
- L70-71 `@Transactional(readOnly = true) public ConversationListResponse listConversations(String userId)`
- L83-84 `@Transactional(readOnly = true) public ConversationExportResponse exportConversation(String userId, String conversationId)` — roles lower-cased (`m.getRole().name().toLowerCase(Locale.ROOT)`)
- L106-107 `@Transactional public void deleteConversation(String userId, String conversationId)` — deletes `AiMessage` rows, the tracking row, then the `AiConversation`
- L137-138 `@Transactional public void recordTurnForUser(String creatorUserId, String conversationId, java.time.Instant when)`
- L146-147 `@Transactional public void recordTurn(String creatorProfileId, String conversationId, java.time.Instant when)` — upsert on `findByConversationId`
- private L61 `requireCreatorProfile(String userId)`, L~118 `requireOwnedTracking(String creatorProfileId, String conversationId)` → 404 `CONVERSATION_NOT_FOUND`

## 2.12 `PublicCreatorService`
`…\service\PublicCreatorService.java` — `@Service` L27. Constructor L35: `(CreatorProfileRepository, CreatorMetricsRepository, MetaOAuthTokenRepository, CollaborationRepository)`.
**L46-47 `@Transactional(readOnly = true) public VerifiedProfileResponse getVerifiedMetrics(String username)`**
- `creatorProfileRepository.findByUsernameIgnoreCase(username)` → 404 `CREATOR_NOT_FOUND`
- Meta-connected check: `metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter(profile.getId(), Instant.now())` non-empty
- 404 (same code) when `!isDiscoverable() || !metaConnected || isSuspended()`
- `platformDealCount` = `collaborationRepository.findByCreatorIdAndStatus(profile.getUserId(), CollaborationStatus.COMPLETED).size()`
- `snapshotDate` = `Instant.now()`

## 2.13 `CampaignService` / `CreatorCampaignService`
`…\service\CampaignService.java` — `@Service` L46.
- L92 `public record PagedCampaigns(List<CampaignResponse> items, PageMeta meta) {}`
- L94 `public PagedCampaigns list(…)`; L139 `public CampaignResponse get(AuthPrincipal principal, String campaignId)`
- **L144-145 `@Transactional public CampaignResponse create(AuthPrincipal principal, CampaignWriteRequest req)`** — L147-151 `brandContext.requireBrandWorkspace` + `requireMember` + `requireRole(member, OWNER, ADMIN, MANAGER)`; **L159-167 hard-rejects blank `endBrandName` → 400 `END_BRAND_NAME_REQUIRED`, blank `endBrandCategory` → 400 `END_BRAND_CATEGORY_REQUIRED`**; `campaignType` defaults to `STANDARD` (L175-176); HYPE budget derived; builder call L215-241 ends `.endBrandName(req.endBrandName().trim()).endBrandCategory(req.endBrandCategory().trim())`
- L236-237 `@Transactional public CampaignResponse update(AuthPrincipal principal, String campaignId, CampaignPatchRequest req)`
- L359-360 `delete(...)`; L375-376 `duplicate(...)`

**Open-campaigns query for creators** — `…\repository\CampaignSpecs.java` (package `com.influora.repository`):
```java
public static Specification<Campaign> browsableForCreator() {          // L44
    return (root, query, cb) ->
            cb.and(cb.equal(root.get("status"), CampaignStatus.ACTIVE),
                   cb.isFalse(root.get("isPrivate")));
}
public static Specification<Campaign> applicationDeadlineNotPassed()   // L51
public static Specification<Campaign> budgetOverlap(BigDecimal min, BigDecimal max) // L59
public static Specification<Campaign> forWorkspace(String workspaceId, List<CampaignStatus> statuses, String search) // L16
```

`…\service\CreatorCampaignService.java` — `@Service` L58. L70 `POST_FILTER_SCAN_LIMIT = 1000`.
- L106 `public record PagedCreatorCampaigns(List<CreatorCampaignListItem> items, PageMeta meta) {}`
- **L121-129 `@Transactional(readOnly = true) public PagedCreatorCampaigns browse(AuthPrincipal principal, String niche, BigDecimal budgetMin, BigDecimal budgetMax, String platform, int page, int limit)`** — `safeLimit = min(max(limit,1), 100)`; sort `Sort.by(DESC, "createdAt")`; niche/platform post-filtered in memory
- L188-189 `@Transactional(readOnly = true) public CreatorCampaignDetailResponse getDetail(AuthPrincipal principal, String campaignId)`
- **L207-208 `@Transactional public ApplyResponse apply(AuthPrincipal principal, String campaignId, ApplyRequest req)`**
  - 409 `CAMPAIGN_NOT_OPEN` if `campaign.getStatus() != ACTIVE` (L212-215)
  - 409 `APPLICATION_DEADLINE_PASSED` (L216-222)
  - Revive path L231-240 `collaborationReviveService.reviveOrRefuse(campaignId, creator.getUserId(), CollaborationStatus.APPLIED, CollaborationSource.APPLICATION, message, campaign.getCurrency(), "ALREADY_APPLIED", "You have already applied to this campaign")`
  - Fresh path L256-262 `Collaboration.apply(Ulids.newUlid(), campaign.getId(), creator.getUserId(), message, campaign.getCurrency())`; `DataIntegrityViolationException` → 409 `ALREADY_APPLIED`
  - Returns `new ApplyResponse(collaboration.getId(), status.name(), appliedAt)`
- **There is no separate "application" entity — an application IS a `Collaboration` row with `source = APPLICATION`, `status = APPLIED`.** Uniqueness enforced by `UNIQUE(campaign_id, creator_id)` from `V6__creators_collaborations.sql`.

## 2.14 `NotificationService`
`…\service\notification\NotificationService.java` — `@Service` L27. Constructor L53: `(NotificationRepository, EmailOutboxRepository, EmailPreferenceRepository, ObjectMapper)`.
```java
@Transactional                                                       // L68
public void notify(
        NotificationEvent event, String title, String body, String link,
        String toEmail, String templateKey, Map<String, Object> templateData)   // L69-75
@Transactional                                                       // L97
public void notifyInApp(NotificationEvent event, String title, String body, String link)  // L98
```
Channel routing (L84-95): `EMAIL_ONLY_EVENTS` (L41-42) = `{"auth.otp","auth.reset","cron.monthly_statement","creator.not_connected"}`; `IN_APP_ONLY_EVENTS` (L44-45) = `{"ai.site_analyzed","ai.campaign_recommended","ai.credits_reset"}`; everything else = both channels. Emails deduped by `buildIdempotencyKey(event)`; in-app is **not** deduped.
`NotificationEvent` (`…\service\notification\event\NotificationEvent.java`) is a **`sealed interface`** with an explicit `permits` list of 34 event records — **adding a new notification event requires editing this `permits` clause**. Contract methods: `String eventType(); String userId(); String workspaceId(); String entityId();`.

## 2.15 Milestone / escrow ("escrow" identifiers exist; banned in user copy)
`…\service\ContractService.java` — `@Service`, L71 `public class ContractService`.
- **L142-143 `@Transactional public ContractResponse generate(AuthPrincipal principal, String workspaceId, ContractGenerateRequest req)`** — **this is where `PaymentMilestone` rows are created**, L301-314:
```java
PaymentMilestone.builder()
        .id(Ulids.newUlid())
        .contractId(contract.getId())
        .collaborationId(collaboration.getId())
        .sequenceNo(m.sequenceNo())
        .description(m.description())
        .amount(m.amount())
        .dueDate(m.dueDate())
        .build()
…
milestoneRepository.saveAll(milestones);
```
(`releaseCondition` is never set here → `build()` defaults it to `ON_POSTED`.)
- L587-588 `recordSignature(...)`; L656-657 `recordSignatureForCreator(...)`; L932-1013 read methods.

`…\service\EscrowService.java` — `@Service` L74.
- L168 `public record PagedEscrowHolds(List<EscrowStatusResponse> items, PageMeta meta) {}`
- **L185-193 `@Transactional public EscrowFundResponse initiateFund(AuthPrincipal principal, String workspaceId, String campaignId, String milestoneId, BigDecimal amount, String currency, String idempotencyKey)`** — requires `OWNER`/`ADMIN` (L194-195); 400 `INVALID_ESCROW_AMOUNT` on non-positive amount
- L316-317 `@Transactional(readOnly = true) public BigDecimal deriveFundAmount(String workspaceId, String campaignId, String milestoneId)` — the server-side amount derivation the controller must call
- **L423-425 `@Transactional public EscrowHold confirmFunded(String escrowHoldId, String gatewayRef, Long webhookAmountInPaise, String webhookCurrency)`** — idempotent no-op if already FUNDED
- L565-566 `release(AuthPrincipal principal, String workspaceId, String milestoneId)`; L596-597 `releaseByHoldId(...)`; L701-702 `tryReleaseOnApproval(String workspaceId, String milestoneId)` returning `public record ReleaseOutcome(boolean released, String heldReason)` (L742) with `ReleaseOutcome.RELEASED` (L743)
- L860-861 `refund(...)`; L909-910 `getStatus(...)`; L924-925 `listForWorkspace(...)`; L945-946 `getStatusForCreator(AuthPrincipal principal, String escrowHoldId)`; L957-958 `freezeUnreleasedForDispute(String collaborationId)`; L985-1019 `hasFundedUnreleasedEscrow` / `hasFrozenEscrow` / `countFrozenHolds`; L1029-1116 `adminReleaseForDispute` / `adminRefundForDispute` / `adminSplitForDispute`
- Backend abstraction: `…\service\escrow\EscrowBackend.java` + `LedgerEscrowBackend.java`

---

# 3. CONTROLLERS & PATHS

**Global base path: `server.servlet.context-path: /api/v1`** — `influora-api/src/main/resources/application.yml` L118-119. Every path below is relative to `/api/v1`.

## 3.1 `CreatorMeeraController`
`…\web\CreatorMeeraController.java` — `@RestController @RequestMapping("/creator/meera")` (L66-68).
Constants: L74 `MAX_VOICE_CLIP_BYTES = 10L * 1024 * 1024`.
Constructor L83-89: `(MeeraSessionService, CreatorContextService, MeeraStreamProperties, CreatorAgentPreferencesService, MeeraVoiceAiClient, MeeraCreatorFeatureProperties)`.
Private guards: **L104 `private void requireFeatureEnabled()`** → 404 `FEATURE_DISABLED`; **L129 `private void requireConsent(String creatorUserId)`** → 403 `CONSENT_REQUIRED`.

| Line | Route | Signature |
|---|---|---|
| L138-140 | `POST /creator/meera/sessions` → **201 CREATED** | `public ResponseEntity<ApiResponse<SessionStartResponse>> startSession(@AuthenticationPrincipal AuthPrincipal principal)` |
| L176-181 | `POST /creator/meera/sessions/{conversationId}/messages` → 200 | `public ResponseEntity<ApiResponse<SendTurnResponse>> sendTurn(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String conversationId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody SendTurnRequest body)` |
| L212-216 | `GET /creator/meera/sessions/{conversationId}/messages?after=` | `public ResponseEntity<ApiResponse<List<MessageHistoryItem>>> messages(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String conversationId, @RequestParam(value = "after", required = false) String after)` |
| L245-247 | `POST /creator/meera/voice/speak` | `public ResponseEntity<?> speak(@AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody VoiceSpeakRequest body)` |
| L277-280 | `POST /creator/meera/voice/transcribe` (`consumes = MULTIPART_FORM_DATA_VALUE`) | `public ResponseEntity<?> transcribe(@AuthenticationPrincipal AuthPrincipal principal, @RequestParam(value = "audio", required = false) MultipartFile audio)` |
| L320-321 | nested DTO | `public record VoiceSpeakRequest(@NotBlank @Size(max = 1000) String text, @Size(max = 20) String lang) {}` |

Gate order in every handler: `requireFeatureEnabled()` → `creatorContext.requireCreatorProfile(principal)` → `requireConsent(creatorUserId)` (`messages` GET omits `requireConsent`).
`startSession` builds `new SessionStartResponse(conversation.getId(), conversation.getStatus().name(), null, (CreditsSummary) null)` (L164-172).
`sendTurn` builds `new SendTurnResponse(result.userMessageId(), result.assistantMessageId(), result.streamToken(), streamProperties.getPublicChatUrl(), 0, result.placeholderReply(), creatorUserId, result.onBehalfToken())` (L196-207) — note `creatorUserId` occupies the `workspaceId` slot.
Voice failure contract: always **200** with body `Map.of("fallback", true)` — never a 4xx/5xx.

## 3.2 `CreatorAgentController`
`…\web\CreatorAgentController.java` — `@RestController @RequestMapping("/creator/agent-preferences")` (L34-36). Constructor L42-45 `(CreatorAgentPreferencesService, CreatorAgentConversationService, MeeraCreatorFeatureProperties)`. L56 `private void requireFeatureEnabled()` → 404 `FEATURE_DISABLED`.

| Line | Route | Signature | Feature-gated? |
|---|---|---|---|
| L63-65 | `GET /creator/agent-preferences` | `public ResponseEntity<ApiResponse<PreferencesResponse>> getPreferences(@AuthenticationPrincipal AuthPrincipal principal)` | **yes** |
| L70-72 | `PUT /creator/agent-preferences` | `public ResponseEntity<ApiResponse<PreferencesResponse>> updatePreferences(@AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdatePreferencesRequest req)` | **yes** |
| L78-80 | `POST /creator/agent-preferences/consent` | `public ResponseEntity<ApiResponse<ConsentResponse>> recordConsent(@AuthenticationPrincipal AuthPrincipal principal)` | **yes** |
| L90-91 | `DELETE /creator/agent-preferences/consent` → **204** | `public ResponseEntity<Void> withdrawConsent(@AuthenticationPrincipal AuthPrincipal principal)` | **no** |
| L96-98 | `GET /creator/agent-preferences/conversations` | `public ResponseEntity<ApiResponse<ConversationListResponse>> listConversations(@AuthenticationPrincipal AuthPrincipal principal)` | **no** |
| L102-104 | `GET /creator/agent-preferences/conversations/{conversationId}/export` | `public ResponseEntity<ApiResponse<ConversationExportResponse>> exportConversation(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String conversationId)` | **no** |
| L109-111 | `DELETE /creator/agent-preferences/conversations/{conversationId}` → **204** | `public ResponseEntity<Void> deleteConversation(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String conversationId)` | **no** |

## 3.3 `MeeraInternalController` (the Python-facing surface)
`…\web\MeeraInternalController.java` — `@RestController @RequestMapping("/internal/meera")` (L73-75).
Headers: L78 `ON_BEHALF_HEADER = "X-Onbehalf-Authorization"`; L81 `IDEMPOTENCY_HEADER = "Idempotency-Key"`. (Mesh headers verified earlier by `InternalServiceTokenFilter`: `X-Meera-Service-Token`, `X-Meera-Signature`, `X-Meera-Timestamp`, `X-Meera-Nonce`.)
Constructor L96-108 — 12 deps.

| Line | Route | Signature | Auth call |
|---|---|---|---|
| **L156-158** | `POST /internal/meera/context` | `public ResponseEntity<ApiResponse<Object>> context(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @Valid @RequestBody ContextRequest body)` | `resolveForWorkspace` + `requireAudiencePrincipalMatch` |
| L188-190 | `POST /internal/meera/show_creators` | `…showCreators(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body)` | `resolveForWorkspaceRequiringScope(..., "show_creators")` |
| L203-205 | `POST /internal/meera/calculate_budget` | `…calculateBudget(same shape)` | `resolveForWorkspaceRequiringScope(..., "calculate_budget")` |
| L215-219 | `POST /internal/meera/create_campaign` → **201** | `…createCampaign(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey, @RequestBody Map<String, Object> body)` | `resolveForWorkspaceRequiringScope(..., "create_campaign")` |
| L245-249 | `POST /internal/meera/request_payment` | same 3-arg shape | `resolveForWorkspaceRequiringElevatedRoleAndScope(..., "request_payment")` |
| L271-275 | `POST /internal/meera/confirm_launch` | same 3-arg shape | `resolveForWorkspaceRequiringElevatedRoleAndScope(..., "confirm_launch")` |
| L300-302 | `POST /internal/meera/get_campaign_performance` | 2-arg shape | `resolveForWorkspaceRequiringScope(..., "get_campaign_performance")` |
| L319-323 | `POST /internal/meera/messages` | `…persistTurnWriteback(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey, @Valid @RequestBody MessageWriteback body)` | resolve conversation → `resolveForWorkspace` |
| L354-356 | `POST /internal/meera/turns/release` | `…releaseTurnCredit(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @Valid @RequestBody ReleaseTurnRequest body)` | resolve conversation → `resolveForWorkspace` |
| L379-382 | `POST /internal/meera/analyze_site_result` | `…analyzeSiteResult(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @Valid @RequestBody AnalyzeSiteChatResult body)` | `resolveForWorkspace(…, body.workspaceId())` |
| L406-408 | `POST /internal/meera/interaction-log` | `…interactionLog(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body)` | `resolveForWorkspace` |

Key internals:
- **L161 `Object result = contextService.assemble(body.workspaceId(), ctx.userType().name());`** — the audience is taken from the **JWT-verified principal**, never from `body.audience()`.
- L174-186 `private static void requireAudiencePrincipalMatch(String requestedAudience, UserType actualUserType)` → 403 `AUDIENCE_PRINCIPAL_MISMATCH`.
- L424 `private void requireTool(MeeraToolName expected, String workspaceId)` — maps `ToolCallRejectedException` → `ApiException(reasonCode, msg, FORBIDDEN)`; mismatch → 403 `TOOL_ROUTE_MISMATCH`.
- **L438-445 `private static String requireWorkspaceId(Map<String, Object> body)` reads the snake_case key `"workspace_id"`** → 400 `WORKSPACE_ID_REQUIRED`.
- Tool bodies are bound as raw `Map<String, Object>` — **there is no per-tool request record**.

## 3.4 `MeeraController` (brand, for comparison)
`…\web\MeeraController.java` — `@RestController @RequestMapping("/meera")` (L62-63).
`POST /meera/sessions` (L95-96), `POST /meera/sessions/{conversationId}/messages` (L117-118), `GET /meera/sessions/{conversationId}/messages` (L166-167), `GET /meera/credits` (L181-182), `GET /meera/brand-profile` (L199-200), `POST /meera/voice/speak` (L237-238), `POST /meera/voice/transcribe` (L279-280, multipart). No `/creator` equivalents of `/credits` or `/brand-profile`.

## 3.5 `DealController` (shared brand + creator)
`…\web\DealController.java` — `@RestController @RequestMapping("/deals")` (L46-47). **There is no separate creator deal controller; the same routes serve both, and `DealService` branches on `principal.getUserType()`.** Note `/deals` is NOT under `/creator/**`, so it is not `hasRole("CREATOR")`-gated.

| Line | Route | Signature |
|---|---|---|
| L65-68 | `GET /deals?status=all` | `list(@AuthenticationPrincipal AuthPrincipal principal, @RequestParam(required = false, defaultValue = "all") String status)` |
| L72-74 | `GET /deals/{id}` | `get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id)` |
| L78-81 | `POST /deals` → **201** | `create(@AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody CreateDealRequest body)` |
| L86-90 | `POST /deals/{id}/accept` | `accept(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)` |
| L94-99 | `POST /deals/{id}/reject` | `reject(principal, @PathVariable String id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @RequestBody(required = false) RejectRequest body)` |
| L104-109 | `POST /deals/{id}/counter` | `counter(principal, @PathVariable String id, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey, @Valid @RequestBody CounterRequest body)` |
| L114-118 | `GET /deals/{dealId}/messages?before=` | `listMessages(...)` |
| L141-142 | `GET /deals/{dealId}/messages/stream` | `public SseEmitter streamMessages(...)` |
| L160-161 | `POST /deals/{dealId}/messages` | `sendMessage(...)` |
| L169-170 | `POST /deals/{dealId}/messages/read` | `markRead(...)` |
| L179-180 | `GET /deals/{dealId}/deliverables` | `listDeliverables(...)` |
| L186-187 | `POST /deals/{dealId}/disputes` | `openDispute(...)` |
| L199-227 | `POST /deals/{id}/shipping-address`, `POST /deals/{id}/shipment`, `POST /deals/{id}/shipment/confirm-receipt`, `GET /deals/{id}/shipment` | shipment routes |

## 3.6 Creator campaign browse/apply
`…\web\CreatorCampaignController.java` — `@RestController @RequestMapping("/creator/campaigns")` (L30-32).
- **L40-48 `GET /creator/campaigns`**: `public ResponseEntity<ApiResponse<List<CreatorCampaignListItem>>> browse(@AuthenticationPrincipal AuthPrincipal principal, @RequestParam(required=false) String niche, @RequestParam(required=false) BigDecimal budgetMin, @RequestParam(required=false) BigDecimal budgetMax, @RequestParam(required=false) String platform, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int limit)` → returns `ApiResponse.ok(result.items(), result.meta())`
- L54-56 `GET /creator/campaigns/{campaignId}` → `CreatorCampaignDetailResponse`
- **L60-64 `POST /creator/campaigns/{campaignId}/apply` → 201 CREATED**: `apply(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId, @Valid @RequestBody(required = false) ApplyRequest body)`

`…\web\CreatorApplicationController.java` — `@RequestMapping("/creator/applications")` (L24-25): `GET /creator/applications` (L33-34) → `List<CreatorApplicationListItem>`; `GET /creator/applications/{dealId}/history` (L45-46) → `List<ApplicationHistoryEventItem>`.

## 3.7 `PublicCreatorController`
`…\web\PublicCreatorController.java` — `@RestController @RequestMapping("/public/creators")` (L23-25). Constructor takes `(PublicCreatorService, MeeraCreatorFeatureProperties)`.
`GET /public/creators/{username}/verified` (L36-38) — `public ResponseEntity<ApiResponse<VerifiedProfileResponse>> getVerifiedMetrics(@PathVariable String username)`. Feature-flag checked first → 404 `FEATURE_DISABLED`; response carries `.cacheControl(CacheControl.noStore().cachePrivate())`.

## 3.8 `AdminCreatorAgentController`
`…\web\AdminCreatorAgentController.java` — `@RestController @RequestMapping("/admin/creator-agent")` (L26-28). **Returns raw DTOs, NOT wrapped in `ApiResponse`** (deliberate — see class javadoc L23-24).
- L39-41 `GET /admin/creator-agent/baselines` → `public BaselinesResponse getBaselines(@AuthenticationPrincipal AuthPrincipal principal)`
- L52-56 `PUT /admin/creator-agent/creators/{creatorId}/monthly-cap` → `public MonthlyCapResponse setMonthlyCap(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String creatorId, @Valid @RequestBody SetMonthlyCapRequest req)` — `creatorId` is a **`creator_profiles.id`**

## 3.9 SecurityConfig gating
`…\config\SecurityConfig.java` — `@Bean SecurityFilterChain securityFilterChain(HttpSecurity http)` L51.
- CSRF disabled (L55); STATELESS sessions (L67); `JsonAuthErrorHandler` for both entry point and access-denied (L76-78).
- Ordered matchers (first match wins): `/health` permitAll … `GET /public/creators/*/verified` permitAll (**L177-178**) … `POST /admin/auth/login|refresh` permitAll … **L207-208 `.requestMatchers("/admin/**").hasRole("ADMIN")`** … **L227-228 `.requestMatchers("/creator/**").hasRole("CREATOR")`** … L229-230 `.anyRequest().authenticated()`.
- Filter order (L231-236): `rateLimitFilter` → `internalServiceTokenFilter` → `jwtFilter`, all `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`.
- **`/internal/**` has NO `requestMatchers` entry** — it falls through to `anyRequest().authenticated()` and is authenticated by `InternalServiceTokenFilter`, which self-scopes: `…\security\InternalServiceTokenFilter.java` L57 `private static final String CTX = "/api/v1";`, L58 `private static final String INTERNAL_PREFIX = "/internal/";`, L83 `if (!path.startsWith(INTERNAL_PREFIX)) { … }` (skip), L59 `public static final String SERVICE_TOKEN_AUDIENCE = "influora-internal";`, L60 `public static final String SERVICE_TOKEN_ISSUER = "meera-python";`. Rejection body: `ApiErrorBody.of("INTERNAL_AUTH_REJECTED", "Internal request rejected: " + reasonCode)` (L158).
- Authorities: `…\security\AuthPrincipal.java` L41-43 `return List.of(new SimpleGrantedAuthority("ROLE_" + userType.name()));`. Constructor L17 `public AuthPrincipal(String userId, String email, UserType userType, String workspaceId)`; getters `getUserId, getEmail, getUserType, getWorkspaceId`.
- Test guard exists: `influora-api/src/test/java/com/influora/config/SecurityConfigMatcherTest.java`.

---

# 4. DTO RECORDS

## 4.1 `MeeraContextDtos` — `…\web\dto\meera\MeeraContextDtos.java`
`public final class MeeraContextDtos` (L33), private ctor L35. **All fields explicitly `@JsonProperty`-snake_cased.**

- L38-40 `public record ContextRequest(@JsonProperty("workspace_id") @NotBlank String workspaceId, @JsonProperty("audience") @NotBlank String audience) {}`
- L44-48 `TemplateDigestEntry(name, campaign_type, budget_band, key_requirements)`
- L52-55 `PastCampaignEntry(type, creator_count, funded)`
- L59-60 `CreditState(mode, credits_remaining)`
- L76-83 `CampaignOutcomeEntry(type, creator_count, spend_inr, funded, verified_reach, reach_source, attributed_revenue_inr)`
- L94-101 `RateBand(min, median, max, currency, niche, sample_size, workspace_sample_size)`
- L111-113 `OutcomeDigest(campaign_outcomes, niche_rate_band)`
- L122-137 `ContextResponse(workspace_id, display_name, industry, website_url, niche_tags, tone_dial, brand_color, brand_aesthetic, product_catalog, competitor_urls, analysis_status, template_digest, past_campaign_summary, credit_state, outcome_digest)`

**`CreatorContextResponse` — L165-215, `@JsonInclude(NON_NULL)`, 27 components in this exact wire order:**
| # | `@JsonProperty` | Java type | component |
|---|---|---|---|
| 1 | `workspace_id` | `String` | `workspaceId` |
| 2 | `audience` | `String` | `audience` |
| 3 | `display_name` | `String` | `displayName` |
| 4 | `first_name` | `String` | `firstName` |
| 5 | `city` | `String` | `city` |
| 6 | `tier` | `String` | `tier` |
| 7 | `categories` | `List<String>` | `categories` |
| 8 | `creator_language` | `String` | `creatorLanguage` |
| 9 | `brand_tone` | `String` | `brandTone` |
| 10 | `floors` | `Map<String, String>` | `floors` |
| 11 | `metrics_summary` | `Map<String, String>` | `metricsSummary` |
| 12 | `deals_summary` | `Map<String, Object>` | `dealsSummary` |
| 13 | `approval_level` | `int` | `approvalLevel` |
| 14 | `represented` | `boolean` | `represented` |
| 15 | `agency_name` | `String` | `agencyName` |
| 16 | `excluded_categories` | `List<String>` | `excludedCategories` |
| 17 | `blocked_brands` | `List<String>` | `blockedBrands` |
| 18 | `working_hours_start` | `Integer` | `workingHoursStart` |
| 19 | `working_hours_end` | `Integer` | `workingHoursEnd` |
| 20 | `working_hours_timezone` | `String` | `workingHoursTimezone` |
| 21 | `working_days` | `List<Integer>` | `workingDays` |
| 22 | `weekly_sponsored_limit` | `Integer` | `weeklySponsoredLimit` |
| 23 | `floor_currency` | `String` | `floorCurrency` |
| 24 | `identity` | `Map<String, Boolean>` | `identity` |
| 25 | `consent_accepted` | `boolean` | `consentAccepted` |
| 26 | `consent_version` | `String` | `consentVersion` |
| 27 | `ai_monthly_cap_usd` | `String` | `aiMonthlyCapUsd` |

**A8 rule: every number on the CREATOR payload leaves as a locale-formatted `String`** (`floors`, `metrics_summary`, `ai_monthly_cap_usd`), except `approval_level` (int) and the two ints inside `deals_summary`.

## 4.2 `MeeraToolDtos` — `…\web\dto\meera\MeeraToolDtos.java`
`public final class MeeraToolDtos` (L23), private ctor L25. **camelCase wire keys (no `@JsonProperty`)**, unlike `MeeraContextDtos`.
- L34 `public record ShowCreatorsResult(List<CreatorSummary> creators) {}`
- L37-44 `public record CreatorSummary(String creatorProfileId, String displayName, String city, List<String> categories, long totalFollowers, BigDecimal engagementRate, boolean verified) {}`
- L47-63 `public record CalculateBudgetResult(BigDecimal suggestedPoolTotal, BigDecimal suggestedPerCreatorRate, int suggestedCreatorCount, String currency, String rationale, String priceConfidence) {}`
- L66-67 `public record CreateCampaignResult(String campaignId, String campaignIntentId, String status, boolean replay) {}`
- L70-76 `public record RequestPaymentResult(String status, String campaignIntentId, BigDecimal serverAmount, String currency, String confirmActionUrl, boolean replay) {}`
- L79-80 `public record ConfirmLaunchResult(String campaignId, String status, int creatorsInvited, boolean replay) {}`
- L90-91 `public record DeliverablePerformanceEntry(String milestoneId, Long reach, Long impressions, Long engagements) {}`
- L110-126 `public record GetCampaignPerformanceResult(String campaignId, long creatorCount, BigDecimal spendInr, Long verifiedReach, BigDecimal attributedRevenueInr, BigDecimal settledCommissionInr, @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal roi, Double responseRate, Double avgCreatorScore, String provenance, List<DeliverablePerformanceEntry> deliverables) {}` — `roi` is the only ALWAYS-serialised field
- L130 `public record ToolRejection(String toolName, String reasonCode, String message) {}`
- L144-148 `public record MessageWriteback(@NotBlank String conversationId, String role, @NotBlank String content, Map<String, Object> metadata) {}`
- L151 `public record MessageWritebackResult(String messageId) {}`
- L166 `public record ReleaseTurnRequest(@NotBlank String conversationId, @NotBlank String turnId) {}`
- **Deliberately no per-tool request record** — the controller binds `Map<String, Object>` (comment L27-31).

## 4.3 `CreatorAgentDtos` — `…\web\dto\creator\CreatorAgentDtos.java`
`public final class CreatorAgentDtos` (L20). **snake_case `@JsonProperty` throughout.**
- **L25-46 `PreferencesResponse`**: `reel_floor(BigDecimal)`, `story_set_floor`, `post_floor`, `floor_currency(String)`, `excluded_categories(List<String>)`, `blocked_brands(List<String>)`, `approval_level(int)`, `creator_language(String)`, `brand_tone(String)`, `working_hours_start(Integer)`, `working_hours_end(Integer)`, `working_hours_timezone(String)`, `working_days(List<Integer>)`, `weekly_sponsored_limit(Integer)`, `represented(boolean)`, `agency_name(String)`, `consent_accepted(boolean)`, `consent_version(String)` — 18 components, `@JsonInclude(NON_NULL)`
- **L52-70 `UpdatePreferencesRequest`**: same 16 creator-editable fields (no consent fields, **no cap field**) with validation `@DecimalMin("0")` on the three floors, `@Size(min=3,max=3)` on `floor_currency`, `@Min(0) @Max(2)` on `approval_level`, `@Size(min=2,max=10)` on `creator_language`, `@Min(0)/@Max(23)` on working hours, `@Min(0)` on `weekly_sponsored_limit`
- L72-75 `ConsentResponse(@JsonProperty("consent_accepted_at") Instant consentAcceptedAt, @JsonProperty("consent_version") String consentVersion)`
- L77-81 `ConversationSummary(conversation_id, started_at, last_message_at, message_count)`
- L83 `ConversationListResponse(@JsonProperty("conversations") List<ConversationSummary> conversations)`
- L85-88 `ConversationExportMessage(role, content, timestamp)`
- L90-93 `ConversationExportResponse(conversation_id, started_at, messages)`

## 4.4 `DealDtos` — `…\web\dto\deal\DealDtos.java`
`public final class DealDtos` (L23). **camelCase wire keys.**
- **L27-66 `DealResponse`** (22 components, in order): `String id, String campaignId, String campaignName, String counterpartyId, String counterpartyProfileId, String counterpartyName, String counterpartyAvatar, String counterpartyHandle, String counterpartyVerificationStatus, CollaborationStatus status, BigDecimal dealValue, String currency, String lastMessage, Instant lastMessageAt, int unreadCount, int deliverablesDone, int deliverablesTotal, Instant nextDeadline, String contractId, ContractStatus contractStatus, boolean escrowFunded, @JsonInclude(JsonInclude.Include.NON_NULL) DealTermsDto dealTerms`
- **L75-82 `public record DealTermsDto(Integer usageMonths, boolean usagePerpetual, List<String> usageChannels, Integer exclusivityDays, ExclusivityScope exclusivityScope, List<String> exclusivityBrands, @Min(0) Integer maxRevisions) {}`**
- **L99-108 `public record CreateDealRequest(@NotBlank String campaignId, @NotBlank String creatorId, @NotNull @DecimalMin("0.01") BigDecimal amount, @NotEmpty(message = "At least one deliverable is required") @Valid List<DeliverableSlot> deliverables, String deadline, String usageRights, @Size(max = 2000) String message, @Valid DealTermsDto dealTerms) {}`** — `creatorId` accepts a CreatorProfile id **or** a User id (`DealService.requireOfferableProfile`, L225)
- L110 `public record DeliverableSlot(@NotBlank String type, @NotNull @Positive Integer qty) {}`
- **L125-135 `public record CounterRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @Size(max = 2000) String message, @Valid List<DeliverableSlot> deliverables, String deadline, String usageRights, @Valid DealTermsDto dealTerms) {}`**
- L137 `public record RejectRequest(@Size(max = 500) String reason) {}`
- L139-148 `DealMessageResponse(String id, String dealId, DealMessageKind kind, String senderId, DealSenderType senderType, String content, Map<String,Object> metadata, Instant createdAt, List<String> readBy)`
- L150-152 `SendMessageRequest(@NotBlank @Size(max = 5000) String content, DealMessageKind kind)`
- L154-158 `public record OkResponse(boolean ok) { public static OkResponse success() { … } }`

## 4.5 `MeeraDtos` — `…\web\dto\meera\MeeraDtos.java`
- L23 `CreditsSummary(int remaining, boolean unlimited)`
- L26-30 `SessionStartResponse(String conversationId, String status, String brandProfileStatus, CreditsSummary credits)`
- L33 `SendTurnRequest(@NotBlank @Size(max = 8000) String content)`
- L51-59 `SendTurnResponse(String messageId, String assistantMessageId, String streamToken, String streamUrl, int creditsRemaining, String reply, String workspaceId, String onBehalfToken)`
- L62 `StreamTokenResponse(String streamToken, String streamUrl, long expiresInSeconds)`
- L71 `MessageHistoryItem(String id, String role, String content)`
- L74-80 `CreditStatusResponse(int creditsRemaining, int monthlyAllotment, boolean unlimited, Instant unlimitedUntil, LocalDate cycleStart, String state)`
- L83-89 `BrandProfileResponse(...)`; L96-104 `AnalyzeSiteCallback(...)`; L115-120 `AnalyzeSiteChatResult(@NotBlank String workspaceId, String url, boolean success, AnalyzeSiteAiDtos.Data data, AnalyzeSiteAiDtos.ErrorDetail error)`

## 4.6 `CampaignDtos` — `…\web\dto\campaign\CampaignDtos.java`
- L20 `BudgetDto(...)`, L25 `TimelineDto(LocalDate startDate, LocalDate endDate)`, L27 `TargetAudienceDto(...)`, L34 `AgeRangeDto(Integer min, Integer max)`, L49 `HypeConfigDto(...)`
- **L60-93 `CampaignWriteRequest(@NotBlank @Size(min=5,max=300) String title, String description, List<String> objectives, CampaignStatus status, CampaignIntentType campaignType, HypeConfigDto hype, @Valid @NotNull BudgetDto budget, @NotNull TimelineDto timeline, LocalDate applicationDeadline, List<String> platforms, List<String> contentTypes, List<String> requirements, List<String> hashtags, String brandGuidelines, Boolean isPrivate, Integer maxCollaborators, TargetAudienceDto targetAudience, String endBrandName, String endBrandCategory)`** — `endBrandName`/`endBrandCategory` are **NOT** bean-validated here; enforced in `CampaignService.create`
- L97-127 `CampaignPatchRequest(...)` — same shape, all optional, ends `String endBrandName, String endBrandCategory`
- L131-158 `CampaignResponse(String id, String workspaceId, String title, String description, List<String> objectives, CampaignStatus status, CampaignIntentType campaignType, HypeConfigDto hype, BudgetDto budget, TimelineDto timeline, LocalDate applicationDeadline, List<String> platforms, List<String> contentTypes, List<String> requirements, List<String> hashtags, String brandGuidelines, boolean isPrivate, Integer maxCollaborators, TargetAudienceDto targetAudience, String createdBy, Instant createdAt, Instant updatedAt, Integer collaboratorsCount, Integer activeCollaborations, Integer completedCollaborations, BigDecimal totalSpend, String endBrandName, String endBrandCategory)`
- L161 `DuplicateResponse(String id)`, L163 `DeleteResponse(boolean ok)`

## 4.7 `CreatorCampaignDtos` — `…\web\dto\creatorcampaign\CreatorCampaignDtos.java`
- L28 `BrandSummary(String workspaceId, String name, String logoUrl, String verificationStatus)`
- L30 `BudgetDto(BigDecimal min, BigDecimal max, String currency)`
- L33-47 `CreatorCampaignListItem(String id, String title, String description, BrandSummary brand, BudgetDto budget, List<String> platforms, List<String> requirements, LocalDate applicationDeadline, LocalDate startDate, LocalDate endDate, Integer maxCollaborators, String applicationStatus, Instant createdAt)`
- L50-67 `CreatorCampaignDetailResponse(...17 components…)`
- L70 `ApplyRequest(@Size(max = 2000) String message)`
- L72 `ApplyResponse(String collaborationId, String status, Instant appliedAt)`

## 4.8 `PublicCreatorDtos` / `AdminCreatorAgentDtos`
`…\web\dto\creator\PublicCreatorDtos.java` (snake_case):
- L18-22 `VerifiedMetrics(followers(long), reach_30d(Long), engagement_rate(BigDecimal), verified_at(Instant))`
- L25-32 `VerifiedProfileResponse(username, display_name, city, categories(List<String>), verified_metrics(VerifiedMetrics), platform_deal_count(long), snapshot_date(Instant))`

`…\web\dto\admin\AdminCreatorAgentDtos.java` (snake_case):
- L18-21 `BriefsPerCreatorPerMonth(median, p75, p90)` (all `double`)
- L23-26 `SampleLabelCompliance(sample_size, labelled_count, compliance_rate)`
- L28-34 `BaselinesResponse(creators_by_tier(Map<String,Long>), briefs_per_creator_per_month, meta_connect_rate(double), median_creator_reply_hours(Double), sample_label_compliance, computed_at(Instant))`
- L41-43 `SetMonthlyCapRequest(@JsonProperty("ai_monthly_cap_usd") @DecimalMin(value="0", message="ai_monthly_cap_usd must be >= 0") BigDecimal aiMonthlyCapUsd)`
- L45 `MonthlyCapResponse(@JsonProperty("ai_monthly_cap_usd") BigDecimal aiMonthlyCapUsd)`

## 4.9 Envelope + exception
`…\common\ApiResponse.java` (whole file, L5-18):
```java
public record ApiResponse<T>(boolean success, T data, ApiErrorBody error, PageMeta meta, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data)                  // L7
    public static <T> ApiResponse<T> ok(T data, PageMeta meta)   // L11
    public static <T> ApiResponse<T> fail(ApiErrorBody error)    // L15
}
```
`…\common\ApiException.java` (whole file):
```java
public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public ApiException(String code, String message, HttpStatus status) { … }  // L10
    public String getCode()      // L16
    public HttpStatus getStatus()// L20
}
```
**Constructor order is `(code, message, status)`.**

---

# 5. INFRA CONVENTIONS

## 5.1 Flyway
Directory: `influora-api/src/main/resources/db/migration/`.
**Two coexisting naming schemes:**
1. Legacy sequential `V<N>__snake_case.sql` — highest is **`V74__meera_creator_conversations.sql`** (also `V72__meera_creator_deal_terms.sql`, `V73__creator_agent_preferences.sql`). Gaps exist: no V40, V60, V66, V67.
2. **Current convention — timestamp-versioned `V2026MMDDHHMMSS__snake_case.sql`.** Latest three (all `creator_agent_preferences` deltas):
   - `V20260903150000__creator_agent_preferences_ai_monthly_cap.sql` — `ADD COLUMN ai_monthly_cap_usd DECIMAL(6, 2) NULL;`
   - `V20260903160000__creator_agent_preferences_consent_version.sql` — `ADD COLUMN consent_version VARCHAR(16) NOT NULL DEFAULT 'v1';`
   - **`V20260903170000__creator_agent_preferences_timezone_currency.sql`** — highest version in the tree (Flyway sorts `20260903170000` above `74`):
     ```sql
     ALTER TABLE creator_agent_preferences
         ADD COLUMN working_hours_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
         ADD COLUMN floor_currency VARCHAR(3) NOT NULL DEFAULT 'INR';
     ```
**A new Phase B migration must therefore be `V2026MMDDHHMMSS__<name>.sql` with a timestamp > `20260903170000`.**

**Mandatory table clause (every `CREATE TABLE`), verbatim from V73/V74:**
```sql
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```
This is load-bearing: MySQL 8's server default is `utf8mb4_0900_ai_ci`, and omitting the clause breaks the FK to `creator_profiles(id)` with a collation mismatch (documented as "Priya gate review defect 1" in `MeeraCreatorPhaseABootValidationTest`).
Other conventions from V73/V74: `VARCHAR(26)` PK (ULID), `TIMESTAMP` for `created_at`/`updated_at`, named FKs `CONSTRAINT fk_<abbrev>_<col> FOREIGN KEY (…) REFERENCES … ON DELETE CASCADE`, named indexes `idx_<table_abbrev>_<cols>`.
Column naming: **snake_case in SQL, explicit `@Column(name = "…")` on every non-trivially-named entity field**; JSON columns use `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "…", columnDefinition = "json")` and a Java field named `<x>Json`; free-text uses `columnDefinition = "TEXT"`; money is `precision = 12, scale = 2` (14,2 for escrow amounts). `ddl-auto: validate` is the committed default.

## 5.2 `MeeraCreatorFeatureProperties`
`…\config\MeeraCreatorFeatureProperties.java` (whole file):
```java
@Component                                                          // L25
public class MeeraCreatorFeatureProperties {
    private final boolean creatorEnabled;
    public MeeraCreatorFeatureProperties(
            @Value("${influora.meera.creator-enabled:true}") boolean creatorEnabled) { … }  // L30-31
    public boolean isCreatorEnabled() { return creatorEnabled; }    // L35
}
```
Env var: `MEERA_CREATOR_ENABLED`, default `true`. **Deliberately `@Value`-injected `@Component`, NOT `@ConfigurationProperties`** (would require registration in `InfluoraApiApplication`'s `@EnableConfigurationProperties` list).
**Usage pattern (copy verbatim into a new controller):**
```java
private void requireFeatureEnabled() {
    if (!featureProperties.isCreatorEnabled()) {
        throw new ApiException(
                "FEATURE_DISABLED", "Meera for Creators is currently disabled", HttpStatus.NOT_FOUND);
    }
}
```
Present in `CreatorMeeraController` L104-109, `CreatorAgentController` L56-61, `PublicCreatorController` L42-46 (inline).

## 5.3 `AuthRateLimitFilter` — adding a bucket
`…\security\AuthRateLimitFilter.java` — `@Component extends OncePerRequestFilter` (L82-83). L85 `private static final String CTX = "/api/v1";`.
Five edits are required to add a bucket:
1. **Pattern constant** (if path-parameterised) near L87-113, e.g.
   `private static final Pattern MEERA_TURN = Pattern.compile("^(/creator)?/meera/sessions/[^/]+/messages$");` (L105-106)
2. **`@Value` limit field**, e.g. L44-45 `@Value("${influora.meera.turn-rate-limit-per-window:20}") private int meeraTurnLimit;` (L200-201 in file)
3. **`bucketFor(HttpServletRequest)`** (L292 onward) — add a branch returning the bucket string. GET branches are L297-317; POST branches L320-402. Path is normalised via `stripMatrixParams(decode(stripContext(request.getRequestURI())))`.
4. **`limitFor(String bucket)`** switch (L404-427) — add `case "<bucket>" -> <limitField>;` (`default -> sensitiveLimit`).
5. **`isUserKeyedBucket(String bucket)`** switch (L447-463) — add the name if it should key on JWT `sub` instead of client IP.
Optionally `windowSecondsFor(String bucket)` (L430-432) for a non-default window (only `creator-withdraw` uses one).

Existing buckets: `sensitive, otp, refresh, meta-oauth, tracking, client-errors, creator-deliverable-write, brand-deliverable-review, contract-sign, review-write, review-flag, dispute-open, discovery-invite, discovery-search, public-creator-verified, campaign-apply, creator-withdraw, meera-turn, meera-voice`.
Creator-Meera-relevant: `meera-turn` matches `^(/creator)?/meera/sessions/[^/]+/messages$` (default 20/60s, user-keyed); `meera-voice` matches the 4 literal paths `/meera/voice/speak`, `/meera/voice/transcribe`, `/creator/meera/voice/speak`, `/creator/meera/voice/transcribe` (default 30, user-keyed); `campaign-apply` matches `^/creator/campaigns/[^/]+/apply$` (default 20, user-keyed); `public-creator-verified` matches `^/public/creators/[^/]+/verified$` (default 30, IP-keyed).
Only `POST` and `GET` are throttled (L288-290 `isThrottledMethod`). 429 body: `{"success":false,"error":{"code":"RATE_LIMITED","message":"Too many requests. Please try again shortly."}}` plus `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`.

## 5.4 Test organisation
Test root: `influora-api/src/test/java/com/influora/` — **266 `.java` files**. Packages: `architecture, common, config, domain/entity, integration/{ai,clamav,dbconstraints,meta,msg91,razorpay,shopify,tracking,woocommerce}, job, repository, security, service, service/meera, service/scoring, testsupport, web`.

**Default = Mockito unit tests.** Canonical header (`…\test\java\com\influora\service\CreatorAgentPreferencesServiceTest.java` L42-43):
```java
@ExtendWith(MockitoExtension.class)
class CreatorAgentPreferencesServiceTest {
    @Mock private CreatorAgentPreferencesRepository preferencesRepository;
    …
}
```
JUnit 5 (`org.junit.jupiter.api.{Test,BeforeEach,DisplayName}`), static-imported `org.junit.jupiter.api.Assertions.*` and `org.mockito.Mockito.{when,verify,lenient,never,verifyNoInteractions}`; AssertJ (`org.assertj.core.api.Assertions.assertThat`) used in architecture/integration tests. **Controllers are unit-tested by direct instantiation with `@Mock` collaborators — no MockMvc/`@WebMvcTest`** (see `CreatorMeeraControllerTest`, `CreatorAgentControllerTest`, `AdminCreatorAgentControllerTest`, `MeeraInternalControllerContextTest`).

**Testcontainers integration tests** extend `com.influora.testsupport.AbstractIntegrationTest` (`…\test\java\com\influora\testsupport\AbstractIntegrationTest.java`):
- `@SpringBootTest` (L62) + `@ActiveProfiles("dev")` + `@Testcontainers` with a static `@Container MySQLContainer` pinned to **`mysql:8.0.40`** (one container per test class, not reused), `@DynamicPropertySource` wiring the JDBC URL, real Flyway run, real `ddl-auto=validate`.
- `@ExtendWith(DockerAvailableCondition.class)` — `…\test\java\com\influora\testsupport\DockerAvailableCondition.java`, an `ExecutionCondition` (L33) that **skips** (never errors) via `DockerClientFactory.instance().isDockerAvailable()` (L38). Must be an `ExecutionCondition`, not `Assumptions.assumeTrue` in `@BeforeAll` (javadoc L13-26).
- Also `…\test\java\com\influora\testsupport\TestEcKeys.java` for ES256 fixtures.
- Existing subclasses: `integration/dbconstraints/DatabaseConstraintIntegrationTest.java`, `integration/dbconstraints/MeeraCreatorPhaseABootValidationTest.java` (guards the collation clause specifically).

**Architecture test:** `…\test\java\com\influora\architecture\InfoBarrierTest.java` — no ArchUnit dependency; it walks `.java` source files under `com/influora/service/meera/**` and `com/influora/web/**` and asserts `CreatorAgentPreferencesRepository` is imported only by the allow-list `Set.of("MeeraContextService", "CreatorAgentPreferencesService", "CreatorAgentPreferencesRepository")` (L59-60), matching `^\s*import\s+(?:static\s+)?[\w.]*\bCreatorAgentPreferencesRepository\b\s*;` multiline (L47-48). **Any new Phase B class under `service/meera/**` or `web/**` that touches rate floors will fail this test unless added to the allow-list.**
Runtime counterpart: `…\test\java\com\influora\service\meera\InfoBarrierRuntimeTest.java`.
Meera/creator-agent test inventory: `service/meera/MeeraContextServiceTest.java`, `service/meera/MeeraSessionServiceTest.java`, `service/CreatorAgentPreferencesServiceTest.java`, `service/CreatorAgentConversationServiceTest.java`, `web/CreatorMeeraControllerTest.java`, `web/CreatorAgentControllerTest.java`, `web/AdminCreatorAgentControllerTest.java`, `web/MeeraControllerTest.java`, `web/MeeraInternalControllerContextTest.java`, `web/MeeraInternalControllerConversationIdTest.java`, `web/MeeraInternalControllerCreateCampaignTest.java`, `security/AuthRateLimitFilterMeeraBucketTest.java`, `repository/MeeraInteractionLogRepositoryQueryTest.java`, `job/MeeraInteractionLogRetentionPurgeJobTest.java`, `integration/ai/MeeraVoiceAiClientTest.java`, `service/PublicCreatorServiceTest.java`.

## 5.5 Package map (for imports)
```
com.influora.common            ApiResponse, ApiException, ApiErrorBody, PageMeta, Ulids, JsonLists, TextSanitizer
com.influora.config            SecurityConfig, MeeraCreatorFeatureProperties, MeeraStreamProperties
com.influora.domain.entity     all @Entity classes
com.influora.domain.enums      all enums
com.influora.repository        *Repository interfaces + CampaignSpecs, (CreatorProfileSpecifications lives in com.influora.service)
com.influora.security          AuthPrincipal, JwtService, OnBehalfAuthResolver, AuthRateLimitFilter,
                               InternalServiceTokenFilter, SpringJwksKeyService, JwtAuthenticationFilter
com.influora.service           DealService, CampaignService, CreatorCampaignService,
                               CreatorAgentPreferencesService, CreatorAgentConversationService,
                               CreatorContextService, PublicCreatorService, EscrowService, ContractService,
                               CollaborationReviveService, IdempotencyService, AuditLogService,
                               CreatorProfileSpecifications
com.influora.service.admin     CreatorAgentBaselineService
com.influora.service.escrow    EscrowBackend, LedgerEscrowBackend
com.influora.service.meera     MeeraContextService, MeeraSessionService, StreamTokenService,
                               OnBehalfTokenService, AICreditService, BrandContextAssembler,
                               MeeraInteractionLogService
com.influora.service.meera.tool  ShowCreatorsExecutor, CalculateBudgetExecutor, CreateCampaignExecutor,
                                 RequestPaymentExecutor, ConfirmLaunchExecutor,
                                 GetCampaignPerformanceExecutor, ToolCallValidator
com.influora.service.notification        NotificationService, NotificationListener, EmailWorker
com.influora.service.notification.event  NotificationEvent (sealed) + 34 event records
com.influora.service.scoring   RateEstimationService, QualityScoreService, BrandSafetyScoreService,
                               FakeFollowerDetectionService
com.influora.web               all *Controller
com.influora.web.dto.meera     MeeraContextDtos, MeeraDtos, MeeraToolDtos, MeeraInteractionDtos
com.influora.web.dto.creator   CreatorAgentDtos, CreatorDtos, CreatorProfileDtos, PublicCreatorDtos, …
com.influora.web.dto.creatorcampaign  CreatorCampaignDtos, CreatorApplicationDtos
com.influora.web.dto.deal      DealDtos
com.influora.web.dto.campaign  CampaignDtos
com.influora.web.dto.admin     AdminCreatorAgentDtos, …
com.influora.testsupport       AbstractIntegrationTest, DockerAvailableCondition, TestEcKeys  (test scope)
```

## 5.6 Repositories you will need
`…\repository\CreatorAgentPreferencesRepository.java` (whole file): `extends JpaRepository<CreatorAgentPreferences, String>`, single method `Optional<CreatorAgentPreferences> findByCreatorId(String creatorId);` (L18). **Info-barrier-restricted — do not import from a brand-facing class.**
`…\repository\MeeraCreatorConversationRepository.java`: `List<MeeraCreatorConversation> findByCreatorIdOrderByLastMessageAtDesc(String creatorId);` (L30), `Optional<MeeraCreatorConversation> findByConversationId(String conversationId);` (L32), `Optional<MeeraCreatorConversation> findByConversationIdAndCreatorId(String conversationId, String creatorId);` (L34).
`…\repository\CollaborationRepository.java` (selected): `findByIdForUpdate(@Param("id") String)` L30 (PESSIMISTIC_WRITE); `existsByCampaignIdAndCreatorId` L77; `findByCampaignIdAndCreatorId` L79; `findByCampaignId` L81; `findByCampaignIdIn` L89; `findByWorkspaceId(@Param("workspaceId") String)` L99; `findByCreatorId(String)` L101; `findByCreatorIdAndStatus(String, CollaborationStatus)` L103; `findByCreatorIdAndStatusNotIn(...)` L113; `findByCreatorIdAndSource(String, CollaborationSource)` L123; `findByCreatorIdAndCampaignIdIn` L126; `findByIdAndCreatorId` L128; `findByCreatorIdAndIdIn` L137; `findByIdAndWorkspaceId(...)` L142; `countByCreatorIdAndStatus` L151; `countByCreatorId` L153; `findRateBandCandidates(@Param("niche") String)` L75; `countByCampaignIdInGroupByStatus(...)` L179.
`CreatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(String creatorProfileId, Pageable)` — used with `PageRequest.of(0, 1)`.
`AiMessageRepository`: `findByConversationIdOrderByCreatedAtAsc`, `findByConversationIdAndIdGreaterThanOrderByIdAsc`, `findTopByConversationIdOrderByCreatedAtDesc`.
`AiConversationRepository`: `findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc`, `findByIdAndWorkspaceId`.
`MetaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter(String, Instant)`.
`CreatorProfileRepository`: `findByUserId(String)`, `findByUsernameIgnoreCase(String)`, `findAll(Specification, Pageable)`.
`WorkspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(String, String)`.

---

# 6. GOTCHAS FOR PHASE B (facts that will break new code silently)

1. **`workspace_id` carries a creator's USER id on the CREATOR audience** — everywhere: `AiConversation.workspaceId`, `StreamTokenService.mint(workspaceId, …)`, `OnBehalfTokenService.mint(workspaceId, …)`, `POST /internal/meera/context` body, `MeeraSessionService.doPersistAssistantWriteback(workspaceId, …)`, `MeeraVoiceAiClient.speak(creatorUserId, …)`.
2. **Three different id spaces**: `Collaboration.creatorId` = `users.id`; `CreatorAgentPreferences.creatorId` and `MeeraCreatorConversation.creatorId` and `CreatorMetric.creatorProfileId` = `creator_profiles.id`; `AdminCreatorAgentController` `{creatorId}` = `creator_profiles.id`.
3. `MeeraContextService.deriveTier` emits `"MEGA"`, which is **not** a `CreatorTier` enum value (enum has only NANO/MICRO/MID/MACRO). Do not `CreatorTier.valueOf(context.tier())`.
4. `OnBehalfTokenService.SCOPE_DEFAULT` excludes `request_payment` and `confirm_launch`, so those two `/internal/meera/*` routes are **currently unreachable** with tokens this codebase mints — any new money tool must be added to `SCOPE_DEFAULT` (a security-reviewed change) or it 403s with `ON_BEHALF_SCOPE_INSUFFICIENT`.
5. `Contract.termsJson` is a **SHA-256 tamper hash**, not terms; the real terms live in `terms_text` (`updatable = false`, builder-only).
6. `DealTermsDto.maxRevisions` is `Integer` (nullable) on the wire but `int` on the entity; `applyDealTermsIfPresent` hard-codes the fallback `2`.
7. `toDealTermsDto` returns `null` unless at least one V72 field deviates from its unset default — `maxRevisions` alone is deliberately NOT part of the `anySet` test.
8. `CreatorAgentController`'s `/conversations/**` and `DELETE /consent` routes are **not** behind `requireFeatureEnabled()`; the other four are.
9. `AdminCreatorAgentController` returns bare DTOs, **not** `ApiResponse` — unlike every creator/brand route.
10. Executors are dispatched by hard-coded route methods; adding a tool means: a `MeeraToolName` value + a `ToolCallValidator.TIER_BY_TOOL` entry + a `@PostMapping` in `MeeraInternalController` + a scope entry in `SCOPE_DEFAULT` + an executor `@Service`.