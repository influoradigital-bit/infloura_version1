// Creator Collaboration OS - Type Definitions
// Based on MODELS.md specification

// ============================================
// ENUMS
// ============================================

export type UserType = 'BRAND' | 'CREATOR' | 'ADMIN';
export type UserStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';
export type VerificationStatus = 'UNVERIFIED' | 'PENDING' | 'VERIFIED' | 'REJECTED';

export type WorkspaceType = 'BRAND' | 'AGENCY';
export type MemberRole = 'OWNER' | 'ADMIN' | 'MANAGER' | 'MEMBER' | 'VIEWER';

export type CampaignType = 'OPEN' | 'DIRECT' | 'HYPE';

export type CampaignStatus =
  | 'DRAFT' 
  | 'PENDING_APPROVAL' 
  | 'ACTIVE' 
  | 'PAUSED' 
  | 'COMPLETED' 
  | 'CANCELLED';

export type CollaborationStatus =
  | 'INVITED'
  | 'APPLIED'
  | 'SHORTLISTED'
  | 'IN_NEGOTIATION'
  | 'TERMS_AGREED'
  | 'CONTRACT_PENDING'
  | 'CONTRACTED'
  | 'IN_PROGRESS'
  | 'REVIEW_PENDING'
  | 'REVISION_REQUESTED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'DISPUTED';

export type ProposalStatus = 'DRAFT' | 'SENT' | 'VIEWED' | 'COUNTERED' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED';

export type ContractStatus = 'DRAFT' | 'PENDING_SIGNATURES' | 'ACTIVE' | 'COMPLETED' | 'TERMINATED' | 'DISPUTED';

/** Mirrors backend DeliverableStatus.java exactly (10 values) — do not add FE-only phantom states. */
export type DeliverableStatus =
  | 'PENDING'
  | 'DRAFT'
  | 'SUBMITTED'
  | 'REVISION_REQUESTED'
  | 'RESUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'POSTED'
  | 'METRICS_REPORTED'
  | 'VERIFIED';

export type ContentType = 'IMAGE' | 'VIDEO' | 'STORY' | 'REEL' | 'POST' | 'ARTICLE' | 'PODCAST' | 'LIVE_STREAM';

export type Platform = 'INSTAGRAM' | 'YOUTUBE' | 'TIKTOK' | 'TWITTER' | 'LINKEDIN' | 'FACEBOOK' | 'TWITCH' | 'OTHER';

export type WalletTransactionType = 
  | 'DEPOSIT' 
  | 'WITHDRAWAL' 
  | 'ESCROW_HOLD' 
  | 'ESCROW_RELEASE' 
  | 'PAYMENT' 
  | 'REFUND' 
  | 'FEE';

export type TransactionStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type DisputeStatus = 'OPEN' | 'UNDER_REVIEW' | 'MEDIATION' | 'RESOLVED' | 'ESCALATED' | 'CLOSED';
export type DisputeType = 'DELIVERABLE_QUALITY' | 'PAYMENT' | 'TIMELINE' | 'CONTRACT_BREACH' | 'OTHER';

// ============================================
// ANALYTICS  (influora-api AnalyticsController / CreatorAnalyticsController)
// Numeric fields: Java BigDecimal/long -> JSON number. Instant -> ISO string.
// null variants reflect @JsonInclude(NON_NULL) omission on the Java DTOs.
// ============================================

/** Client-side date window; hooks serialize start/end as ISO via .toISOString(). */
export interface AnalyticsDateRange {
  start: Date;
  end: Date;
}

/** AnalyticsDtos.MetricDataPoint. */
export interface MetricDataPoint {
  date: string;
  followers: number;
  impressions: number;
  reach: number;
  engagementRate: number;
}

/** AnalyticsDtos.CreatorMetricsResponse. trendData empty unless a date range is supplied. */
export interface CreatorMetrics {
  totalReach: number;
  totalImpressions: number;
  totalEngagements: number;
  engagementRate: number | null;
  followerGrowth: number;
  avgViewsPerPost: number | null;
  trendData: MetricDataPoint[];
}

/** AnalyticsDtos.CreatorScoresResponse. brandSafety/garm/sentiment are null until that service lands. */
export interface CreatorScores {
  authenticityScore: number | null;
  fakeFollowerReasons: string[];
  qualityScore: number | null;
  engagementConsistency: number | null;
  postingFrequency: number | null;
  audienceMatchScore: number | null;
  brandSafetyScore: number | null;
  garmFlags: string[] | null;
  contentSentiment: number | null;
  estimatedRateMin: number | null;
  estimatedRateMax: number | null;
  rateCurrency: string | null;
  rateConfidence: number | null;
  algorithmVersion: string | null;
  computedAt: string | null;
}

/** AnalyticsDtos.CreatorDemographicsResponse. Breakdowns are Java Map<String,Long>. */
export interface CreatorDemographics {
  hasData: boolean;
  ageGenderBreakdown: Record<string, number> | null;
  countryBreakdown: Record<string, number> | null;
  cityBreakdown: Record<string, number> | null;
  localeBreakdown: Record<string, number> | null;
  fetchedAt: string | null;
}

export type NotificationType = 
  | 'CAMPAIGN_INVITE' 
  | 'PROPOSAL_RECEIVED' 
  | 'PROPOSAL_ACCEPTED' 
  | 'CONTRACT_READY' 
  | 'DELIVERABLE_SUBMITTED' 
  | 'PAYMENT_RECEIVED' 
  | 'SYSTEM_ALERT';

// ============================================
// CORE ENTITIES
// ============================================

export interface User {
  id: string;
  email: string;
  passwordHash?: string;
  userType: UserType;
  status: UserStatus;
  emailVerified: boolean;
  phoneNumber?: string;
  phoneVerified: boolean;
  avatarUrl?: string;
  displayName: string;
  firstName?: string;
  lastName?: string;
  timezone?: string;
  locale?: string;
  lastLoginAt?: Date;
  createdAt: Date;
  updatedAt: Date;
}

export interface Workspace {
  id: string;
  name: string;
  slug: string;
  type: WorkspaceType;
  logoUrl?: string;
  websiteUrl?: string;
  industry?: string;
  companySize?: string;
  description?: string;
  verificationStatus: VerificationStatus;
  verificationDocuments?: string[];
  billingEmail?: string;
  billingAddress?: Address;
  createdAt: Date;
  updatedAt: Date;
}

export interface Address {
  street?: string;
  city?: string;
  state?: string;
  country?: string;
  postalCode?: string;
}

// ============================================
// CAMPAIGN DOMAIN
// ============================================

/**
 * Hype Campaign config — 72-hr blitz where many creators remix one source
 * reel at a flat per-reel rate until slots run out.
 * Spec: docs/FRONTEND-DESIGN-TASK.md (Hype Campaign UI).
 */
export interface HypeConfig {
  sourceReelUrl: string;
  audioTrack?: string;
  hashtag: string;
  formatLanes: string[];
  perReelRate: number;
  currency: string;
  slotCap: number;
  slotsFilled: number;
  liveUntil: Date;
}

export interface Campaign {
  id: string;
  workspaceId: string;
  title: string;
  description?: string;
  objectives?: string[];
  campaignType?: CampaignType;
  hype?: HypeConfig;
  status: CampaignStatus;
  budget: BudgetRange;
  timeline: CampaignTimeline;
  targetAudience?: TargetAudience;
  platforms: Platform[];
  contentTypes: ContentType[];
  requirements?: string[];
  hashtags?: string[];
  brandGuidelines?: string;
  isPrivate: boolean;
  maxCollaborators?: number;
  applicationDeadline?: Date;
  createdBy: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface BudgetRange {
  min: number;
  max: number;
  currency: string;
}

export interface CampaignTimeline {
  startDate: Date;
  endDate: Date;
  milestones?: Milestone[];
}

export interface Milestone {
  id: string;
  title: string;
  dueDate: Date;
  description?: string;
}

export interface TargetAudience {
  ageRange?: { min: number; max: number };
  genders?: string[];
  locations?: string[];
  interests?: string[];
  languages?: string[];
}

// ============================================
// COLLABORATION DOMAIN
// ============================================

export interface Collaboration {
  id: string;
  campaignId: string;
  creatorId: string;
  creatorName?: string;
  status: CollaborationStatus;
  source: 'INVITATION' | 'APPLICATION';
  agreedRate?: number;
  currency?: string;
  notes?: string;
  statusHistory: StatusHistoryEntry[];
  createdAt: Date;
  updatedAt: Date;
}

export interface StatusHistoryEntry {
  status: CollaborationStatus;
  changedBy: string;
  changedAt: Date;
  reason?: string;
}

export interface Proposal {
  id: string;
  collaborationId: string;
  version: number;
  status: ProposalStatus;
  proposedBy: 'BRAND' | 'CREATOR';
  rate: number;
  currency: string;
  deliverables: ProposedDeliverable[];
  timeline?: ProposedTimeline;
  terms?: string;
  message?: string;
  expiresAt?: Date;
  viewedAt?: Date;
  respondedAt?: Date;
  createdAt: Date;
}

export interface ProposedDeliverable {
  contentType: ContentType;
  platform: Platform;
  quantity: number;
  description?: string;
}

export interface ProposedTimeline {
  startDate: Date;
  endDate: Date;
  deliveryDates?: Date[];
}

// ============================================
// CONTRACT DOMAIN
// ============================================

export interface Contract {
  id: string;
  collaborationId: string;
  templateId?: string;
  version: number;
  status: ContractStatus;
  content: string;
  terms: ContractTerms;
  brandSignature?: Signature;
  creatorSignature?: Signature;
  effectiveDate?: Date;
  expirationDate?: Date;
  createdAt: Date;
  updatedAt: Date;
}

export interface ContractTerms {
  totalAmount: number;
  currency: string;
  paymentSchedule: PaymentMilestone[];
  deliverables: ContractDeliverable[];
  exclusivity?: ExclusivityTerms;
  usageRights?: UsageRights;
  cancellationTerms?: string;
}

export interface PaymentMilestone {
  id: string;
  description: string;
  amount: number;
  dueDate: Date;
  status: 'PENDING' | 'PAID' | 'OVERDUE';
}

export interface ContractDeliverable {
  id: string;
  contentType: ContentType;
  platform: Platform;
  description: string;
  dueDate: Date;
  requirements?: string[];
}

export interface ExclusivityTerms {
  isExclusive: boolean;
  duration?: number; // in days
  categories?: string[];
}

export interface UsageRights {
  duration: number; // in months
  territories?: string[];
  channels?: string[];
  canSublicense: boolean;
}

export interface Signature {
  signedBy: string;
  signedAt: Date;
  ipAddress?: string;
  signatureData?: string;
}

// ============================================
// DELIVERABLE DOMAIN
// ============================================

export interface Deliverable {
  id: string;
  collaborationId: string;
  contractDeliverableId?: string;
  contentType: ContentType;
  platform: Platform;
  status: DeliverableStatus;
  title: string;
  description?: string;
  dueDate: Date;
  submittedAt?: Date;
  approvedAt?: Date;
  currentRevisionId?: string;
  revisionCount: number;
  maxRevisions: number;
  createdAt: Date;
  updatedAt: Date;
}

// ============================================
// FINANCIAL DOMAIN
// ============================================

export interface Wallet {
  id: string;
  ownerId: string;
  ownerType: 'USER' | 'WORKSPACE';
  balance: number;
  escrowBalance: number;
  currency: string;
  isActive: boolean;
  createdAt: Date;
  updatedAt: Date;
}

export interface WalletTransaction {
  id: string;
  walletId: string;
  type: WalletTransactionType;
  amount: number;
  fee?: number;
  status: TransactionStatus;
  referenceType?: string;
  referenceId?: string;
  description?: string;
  metadata?: Record<string, unknown>;
  createdAt: Date;
  completedAt?: Date;
}

// ============================================
// DISPUTE DOMAIN
// ============================================

export interface Dispute {
  id: string;
  collaborationId: string;
  raisedBy: string;
  type: DisputeType;
  status: DisputeStatus;
  title: string;
  description: string;
  resolution?: DisputeResolution;
  assignedTo?: string;
  createdAt: Date;
  updatedAt: Date;
  resolvedAt?: Date;
}

export interface DisputeResolution {
  outcome: 'FAVOR_BRAND' | 'FAVOR_CREATOR' | 'MUTUAL_AGREEMENT' | 'NO_RESOLUTION';
  summary: string;
  actions?: string[];
  resolvedBy: string;
}

// ============================================
// SYSTEM DOMAIN
// ============================================

export interface Notification {
  id: string;
  userId: string;
  type: NotificationType;
  title: string;
  message: string;
  data?: Record<string, unknown>;
  isRead: boolean;
  readAt?: Date;
  actionUrl?: string;
  createdAt: Date;
}

// ============================================
// CREATOR PROFILE (for discovery)
// ============================================

/**
 * BR-18 — denormalized read of `creator_scores` (append-only, one row per creator per run).
 * Mirrors the backend's canonical `DiscoveryDtos.CreatorScores(quality, authenticity,
 * brandSafety)` projection exactly — do not add loose score fields elsewhere. Per Priya's
 * Score Exposure rule, an absent score is `null`, never `0` — render it as "not yet scored".
 * `brandSafety` is `null` for every creator until BR-42 ships.
 */
export interface CreatorScoresSummary {
  quality: number | null;
  authenticity: number | null;
  brandSafety: number | null;
}

export interface CreatorProfile {
  id: string;
  userId: string;
  displayName: string;
  bio?: string;
  avatarUrl?: string;
  coverImageUrl?: string;
  location?: string;
  categories: string[];
  platforms: PlatformStats[];
  totalFollowers: number;
  engagementRate: number;
  averageRate?: number;
  currency?: string;
  isVerified: boolean;
  /** Present on list responses when the brand has bookmarked this creator */
  saved?: boolean;
  portfolioItems: PortfolioItem[];
  languages?: string[];
  contentStyles?: string[];
  /** Nullable nested scores object (BR-18) — absent/not-yet-computed scores are `null`, not `0`. */
  scores?: CreatorScoresSummary | null;
}

export interface PlatformStats {
  platform: Platform;
  handle: string;
  followers: number;
  engagementRate: number;
  isVerified: boolean;
  profileUrl?: string;
}

// ============================================
// UNIFIED TIMELINE (Collaboration Events)
// ============================================

export interface Attachment {
  name: string;
  url: string;                         // Cloudflare R2 presigned URL
  type: string;                        // 'image/jpeg', 'video/mp4', etc.
  size: number;                        // bytes
  uploadedAt: Date;
}

/** One line of a proposal's deliverable list — mirrors `DealDtos.DeliverableSlot` (`qty`, not `quantity`). */
export interface DeliverableSlot {
  type: string;
  qty: number;
}

export interface TimelineEventMetadata {
  brandName?: string;
  campaignName?: string;
  creatorName?: string;

  // For 'proposal' tag:
  proposalId?: string;
  amount?: number;
  /**
   * The proposal's deliverable slots — NOT a count, and NOT renderable as a React child.
   *
   * `DealService.persistProposalMessage` (DealService.java:1132) has stored the
   * `DealDtos.DeliverableSlot` list here since 2026-07-26; before that it stored the slot count as
   * a plain number, and those older messages are still in the table. This type said `number` until
   * a live proposal reached the deal-room timeline and React threw error #31 ("Objects are not
   * valid as a React child") on `{meta?.deliverables || 0} pieces`, taking the whole route down.
   *
   * Read it through `deliverableCountOf` / `deliverableSlotsLabel` (src/lib/deliverable-slots.ts),
   * never directly.
   */
  deliverables?: DeliverableSlot[] | number;
  /** Slot count the backend writes alongside `deliverables` (DealService.java:1133). */
  deliverableCount?: number;
  deadline?: string;
  status?: 'pending' | 'accepted' | 'rejected' | 'countered';
  
  // For 'contract' tag:
  contractId?: string;
  // Kept in sync with DealContractStatus (src/components/brand/deal-room/deal-contract-tab.tsx)
  // — not imported directly to avoid a component -> lib -> component cycle. 'pending_signature'
  // (F-0250 follow-up): PENDING_SIGNATURES from EITHER party, signer unknown.
  contractStatus?: 'generated' | 'pending_signature' | 'brand_signed' | 'creator_signed' | 'active';
  
  // For 'deliverable' tag:
  deliverableId?: string;
  deliverableNumber?: number;        // Reel #1, #2, etc.
  platform?: 'instagram' | 'youtube' | 'tiktok';
  submittedUrl?: string;             // Link to R2 storage
  submittedFilename?: string;
  deliverableStatus?: 'submitted' | 'under_review' | 'revision_requested' | 'approved';
  revisionCount?: number;
  revisionLimit?: number;            // Default 2
  revisionFeedback?: string;
  
  // For 'payment' tag:
  paymentAmount?: number;
  paymentType?: 'escrow_locked' | 'milestone_released' | 'final_payout';
  
  // For 'system' tag:
  message?: string;                  // Auto-notification text
  severity?: 'info' | 'warning' | 'alert';
}

export interface TimelineEvent {
  id: string;                          // e.g., "evt-123"
  collaborationId: string;             // Links to Collaboration
  timestamp: Date;
  senderId: string;                    // User ID (or 'system')
  senderType: 'brand' | 'creator' | 'system';
  senderName?: string;                 // Display name for UI
  senderAvatar?: string;               // Avatar URL
  
  // EVENT TAG -- determines what renders
  tag: 'message' | 'proposal' | 'contract' | 'deliverable' | 'payment' | 'system';
  
  // Content -- varies by tag
  content?: string;                    // For 'message' tag
  attachments?: Attachment[];
  
  // Tag-specific metadata
  metadata?: TimelineEventMetadata;
  
  status: 'sent' | 'read' | 'delivered';
  archived?: boolean;
}


export interface PortfolioItem {
  id: string;
  title: string;
  description?: string;
  thumbnailUrl: string;
  mediaUrl?: string;
  platform?: Platform;
  metrics?: {
    views?: number;
    likes?: number;
    comments?: number;
    shares?: number;
  };
}
