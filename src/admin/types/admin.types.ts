/**
 * INFLUORA ADMIN PANEL — Type Definitions
 * Owner: Priya (CTO)
 * Reference: docs/ADMIN-PANEL-SPEC.md
 */

// ============================================
// ENUMS
// ============================================

export enum AdminRole {
  SUPER_ADMIN = 'SUPER_ADMIN',
  ADMIN = 'ADMIN',
  SUPPORT = 'SUPPORT'
}

export enum KycStatus {
  PENDING = 'PENDING',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED'
}

export enum CreatorApplicationStatus {
  PENDING = 'PENDING',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED'
}

export enum ContentFlagStatus {
  PENDING = 'PENDING',
  ESCALATED = 'ESCALATED',
  REVIEWED = 'REVIEWED',
  ACTIONED = 'ACTIONED'
}

export enum TicketStatus {
  OPEN = 'OPEN',
  IN_PROGRESS = 'IN_PROGRESS',
  WAITING_USER = 'WAITING_USER',
  RESOLVED = 'RESOLVED',
  CLOSED = 'CLOSED'
}

export enum TicketPriority {
  LOW = 'LOW',
  MEDIUM = 'MEDIUM',
  HIGH = 'HIGH',
  URGENT = 'URGENT'
}

export enum WorkflowType {
  CREATOR_APPLICATION = 'CREATOR_APPLICATION',
  BRAND_KYC = 'BRAND_KYC',
  DELIVERABLE_DISPUTE = 'DELIVERABLE_DISPUTE',
  ESCROW_RELEASE = 'ESCROW_RELEASE',
  CONTENT_MODERATION = 'CONTENT_MODERATION',
  ACCOUNT_SUSPENSION = 'ACCOUNT_SUSPENSION'
}

export enum ErrorSeverity {
  ERROR = 'ERROR',
  WARN = 'WARN',
  CRITICAL = 'CRITICAL'
}

export enum EmailStatus {
  PENDING = 'PENDING',
  SENT = 'SENT',
  FAILED = 'FAILED',
  RETRYING = 'RETRYING'
}

// ============================================
// ADMIN USER
// ============================================

export interface AdminUser {
  id: string;
  email: string;
  role: AdminRole;
  mfaEnabled: boolean;
  lastLogin: string | null;
  createdAt: string;
}

export interface AdminLoginRequest {
  email: string;
  password: string;
  mfaCode?: string;
}

export interface AdminLoginResponse {
  token: string;
  refreshToken: string;
  user: AdminUser;
}

// ============================================
// RBAC — ROLE CAPABILITIES
// ============================================

/**
 * Role → allowed actions. Higher roles do NOT structurally inherit lower roles here; each role's
 * full effective set is listed explicitly so the map is auditable at a glance and matches the
 * matrix exactly. Keep this in lockstep with `ROLE_PERMISSIONS` in `useAdminAuth.ts`.
 */
// ============================================
// DASHBOARD / KPIs
// ============================================

export interface CeoPulseData {
  gmv: number;
  /** Percentage WoW change. `null` means no `kpi_daily_snapshot` baseline exists yet for
   *  this metric — render an honest "—", never fabricate a `0`/flat value from it. */
  gmvChange: number | null;
  revenue: number;
  /** Percentage WoW change. `null` = no snapshot baseline yet (render "—"), NOT zero change. */
  revenueChange: number | null;
  activeCampaigns: number;
  /** Percentage WoW change. `null` = no snapshot baseline yet (render "—"), NOT zero change. */
  activeCampaignsChange: number | null;
  escrowFloat: number;
  supportQueueDepth: number;
  mauBrands: number;
  mauCreators: number;
  redFlags: RedFlag[];
}

export interface RedFlag {
  id: string;
  type: 'ESCROW_LOW' | 'SLA_BREACH' | 'PAYOUT_DELAY' | 'REVIEW_BACKLOG' | 'SUPPORT_AGING';
  message: string;
  severity: 'WARNING' | 'CRITICAL';
  createdAt: string;
  entityId?: string;
  entityType?: string;
}

export interface KpiCard {
  title: string;
  value: number | string;
  /** `null` = no comparison baseline available yet (render "—", no arrow/color).
   *  A real `0` is a genuine flat/no-change reading and must render as "0%". */
  change?: number | null;
  changeType?: 'positive' | 'negative' | 'neutral' | 'unknown';
  icon?: string;
}

// ============================================
// BRAND MANAGEMENT
// ============================================

export interface Brand {
  id: string;
  name: string;
  email: string;
  industry: string;
  size: 'STARTUP' | 'SMB' | 'ENTERPRISE';
  kycStatus: KycStatus;
  kycReviewedBy?: string;
  kycReviewedAt?: string;
  kycRejectionReason?: string;
  campaignCount: number;
  totalSpend: number;
  isSuspended: boolean;
  createdAt: string;
}

export interface BrandDetail extends Brand {
  gstNumber?: string;
  panNumber?: string;
  incorporationDoc?: string;
  billingAddress?: string;
  teamMembers: TeamMember[];
  campaigns: CampaignSummary[];
  paymentHistory: PaymentRecord[];
}

export interface TeamMember {
  id: string;
  name: string;
  email: string;
  role: string;
}

export interface BrandKycAction {
  brandId: string;
  action: 'APPROVE' | 'REJECT';
  // Required: every KYC decision is a mutation that MUST log a reason to audit_logs (spec:
  // "mandatory reason field"). For APPROVE, pass a short justification (e.g. "Docs verified").
  reason: string;
}

// ============================================
// CREATOR MANAGEMENT
// ============================================

export interface Creator {
  id: string;
  name: string;
  email: string;
  niche: string[];
  followers: number;
  engagementRate: number;
  applicationStatus: CreatorApplicationStatus;
  instagramVerified: boolean;
  qualityScore: number;
  tier: 'NANO' | 'MICRO' | 'MID' | 'MACRO';
  isSuspended: boolean;
  createdAt: string;
}

/**
 * Row shape for the admin creator list/search table (`GET /admin/creators`, Task #3) — matches
 * `AdminCreatorDtos.CreatorSummaryDto` on the backend exactly (id, name, email, instagramHandle,
 * followers, applicationStatus, tier, isSuspended, createdAt). Deliberately NOT the same shape as
 * `Creator` above: the list endpoint is a lighter summary and does not carry
 * niche/engagementRate/instagramVerified/qualityScore — those only exist on `CreatorDetail`,
 * fetched per-row via `creatorApi.getById()` once a row is opened (see CreatorProfile.tsx).
 * Rendering `Creator`-only fields against this response would be a fabricated contract
 * (TECH-STACK.md rule 7).
 */
export interface CreatorSummary {
  id: string;
  name: string;
  email: string;
  instagramHandle: string;
  followers: number;
  applicationStatus: CreatorApplicationStatus;
  tier: 'NANO' | 'MICRO' | 'MID' | 'MACRO';
  isSuspended: boolean;
  createdAt: string;
}

export interface CreatorDetail extends Creator {
  bio: string;
  location: string;
  instagramHandle: string;
  instagramUserId?: string;
  instagramOauthAt?: string;
  platformStats: PlatformStats;
  collaborationHistory: CollaborationRecord[];
  qualityMetrics: CreatorQualityMetrics;
  /**
   * Count of open (`PENDING` + `REVIEWED`) `ContentFlag` rows whose flagged content
   * belongs to this creator. This is a server-side rollup, not a full join: the admin
   * creator profile only renders a scalar KPI + "N flagged items pending review" badge
   * (src/admin/components/users/CreatorProfile.tsx), so the full `ContentFlag[]` list is
   * unnecessary here — the dedicated moderation queue owns per-flag detail. When
   * `AdminCreatorController` (GET /api/admin/creators/:id) ships, it must derive this from
   * the `ContentFlag` table filtered by `status IN (PENDING, REVIEWED)`.
   */
  flaggedContentCount: number;
  applicationReviewedBy?: string;
  applicationReviewedAt?: string;
  applicationRejectionReason?: string;
}

export interface PlatformStats {
  avgReach: number;
  avgEngagement: number;
  postsLast30Days: number;
  reelsLast30Days: number;
}

export interface CreatorQualityMetrics {
  deadlineAdherence: number; // percentage
  revisionRate: number; // avg revisions per deliverable
  disputeRate: number; // percentage
  overallScore: number; // 0-100
}

export interface CollaborationRecord {
  id: string;
  campaignName: string;
  brandName: string;
  amount: number;
  status: string;
  completedAt?: string;
}

export interface CreatorApplicationAction {
  creatorId: string;
  action: 'APPROVE' | 'REJECT';
  // Required: application decisions are a quality-gate mutation and MUST carry an auditable reason
  // (spec: "mandatory reason field"). For APPROVE, a brief note (e.g. "Meets tier criteria").
  reason: string;
  qualityScore?: number;
}

export interface CreatorTierAdjustment {
  creatorId: string;
  newTier: 'NANO' | 'MICRO' | 'MID' | 'MACRO';
  newQualityScore?: number;
  reason: string;
}

// ============================================
// CAMPAIGNS
// ============================================

export interface CampaignSummary {
  id: string;
  name: string;
  brandName: string;
  type: 'STANDARD' | 'HYPE';
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  budget: number;
  spent: number;
  creatorCount: number;
  deliverablesPending: number;
  deliverablesApproved: number;
  slaBreachRate: number;
  createdAt: string;
  endsAt?: string;
}

export interface CampaignDetail extends CampaignSummary {
  brief: string;
  deliverables: DeliverableRequirement[];
  creators: CampaignCreator[];
  escrowBalance: number;
  timelineStatus: 'ON_TRACK' | 'AT_RISK' | 'DELAYED';
}

export interface DeliverableRequirement {
  id: string;
  type: string;
  description: string;
  quantity: number;
  deadline: string;
}

export interface CampaignCreator {
  creatorId: string;
  creatorName: string;
  status: 'INVITED' | 'NEGOTIATING' | 'CONTRACTED' | 'DELIVERING' | 'COMPLETED';
  amount: number;
}

// ============================================
// FINANCE
// ============================================

export interface RevenueSnapshot {
  period: string;
  gmv: number;
  platformFees: number;
  setupFees: number;
  totalRevenue: number;
}

export interface EscrowSummary {
  totalLocked: number;
  pendingRelease: number;
  flaggedTransactions: number;
  averageReleaseTime: number; // hours
}

export interface PayoutQueueItem {
  id: string;
  creatorId: string;
  creatorName: string;
  amount: number;
  grossAmount: number;
  tdsAmount: number;
  tdsSection: '194C' | '194R';
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  campaignName: string;
  createdAt: string;
  processedAt?: string;
  failureReason?: string;
}

export interface PaymentRecord {
  id: string;
  type: 'CREDIT' | 'DEBIT';
  amount: number;
  description: string;
  status: string;
  createdAt: string;
}

export interface ReconciliationItem {
  id: string;
  razorpayId: string;
  internalId: string;
  razorpayAmount: number;
  internalAmount: number;
  variance: number;
  status: 'MATCHED' | 'MISMATCH' | 'PENDING';
  createdAt: string;
}

// ============================================
// PLATFORM FEE CONFIG
// ============================================
//
// Backs FeeControlPanel.tsx (src/admin/components/finance/FeeControlPanel.tsx).
// Swapnil-approved fee ruling per SHARED_CONTEXT.md ("Fee config (P0)" entry):
// 10% brand / 15% creator, platform absorbs Razorpay's processing cost
// (Option A). Vikram's `PlatformFeeAdminController` is the eventual backing
// endpoint — not built this cycle, see useFeeConfig.ts for the mock-data
// swap-in point.

/**
 * The live fee schedule applied to brand campaign spend and creator payouts.
 * `effectiveDate` is when this schedule took/takes effect; `updatedBy` is the
 * admin identity (id or email) that set it.
 */
export interface PlatformFeeConfig {
  /** Percentage fee charged to brands on campaign spend (0-100). */
  brandFeePercent: number;
  /** Percentage fee charged to creators on payouts (0-100). */
  creatorFeePercent: number;
  /** ISO 8601 date this fee schedule takes/took effect. */
  effectiveDate: string;
  /** True when the platform (not the brand/creator) absorbs Razorpay's
   *  payment processing cost — the approved Option A ruling. */
  razorpayAbsorbedByPlatform: boolean;
  /** Admin id/email who last set this configuration. */
  updatedBy: string;
}

/**
 * Payload for a fee-schedule change. Required: this is a revenue-critical
 * mutation and MUST carry an auditable reason (same "mandatory reason field"
 * pattern as `BrandKycAction` / `CreatorApplicationAction` above).
 */
export interface PlatformFeeUpdateRequest {
  brandFeePercent: number;
  creatorFeePercent: number;
  razorpayAbsorbedByPlatform: boolean;
  reason: string;
  /**
   * Optimistic-concurrency token: the `effectiveDate` this admin observed on the config the
   * last time they loaded/refreshed it (i.e. `PlatformFeeConfig.effectiveDate` from the most
   * recent successful `getFeeConfig()`/`refresh()`). Threaded through so a stale-baseline write
   * can be rejected server-side (HTTP 409 / `FEE_CONFIG_CONFLICT`) instead of silently
   * overwriting a change another SUPER_ADMIN made in between.
   *
   * NOT YET ENFORCED SERVER-SIDE — flagged for Vikram: `UpdatePlatformFeeConfigRequest`
   * (`influora-api/.../web/dto/admin/PlatformFeeConfigDtos.java`) doesn't declare this field
   * yet, so Jackson currently drops it on arrival (fail-on-unknown-properties is off) rather
   * than validating it, and `PlatformFeeAdminService#update`'s only real guard today is
   * Hibernate `@Version` inside its own load-then-save transaction — which only 409s when two
   * PUTs race within that transaction window, not the realistic "two admins minutes apart"
   * case. Add a matching field to that DTO and compare it against the freshly-loaded row inside
   * `update()` to close the gap. Sending it now is forward-compatible and harmless meanwhile.
   */
  expectedEffectiveDate: string;
}

/** One row of the fee change-history audit trail shown in FeeControlPanel. */
export interface PlatformFeeHistoryEntry {
  id: string;
  brandFeePercent: number;
  creatorFeePercent: number;
  previousBrandFeePercent: number;
  previousCreatorFeePercent: number;
  razorpayAbsorbedByPlatform: boolean;
  reason: string;
  changedBy: string;
  changedAt: string;
}

/**
 * Raw wire shape of `PlatformFeeConfigDto` (`PlatformFeeConfigDtos.java`, Vikram) as it actually
 * arrives over HTTP. The percent fields are backed by `java.math.BigDecimal` server-side, which
 * Jackson can serialize as either a bare JSON number OR a string (e.g. `"10.00"`) depending on
 * precision/serializer config — never assume it lands as `number`. Coerced to `PlatformFeeConfig`
 * via `normalizeFeeConfig()` in useFeeConfig.ts before the rest of the app ever sees it.
 */
export interface PlatformFeeConfigWire {
  brandFeePercent: number | string;
  creatorFeePercent: number | string;
  effectiveDate: string;
  razorpayAbsorbedByPlatform: boolean;
  updatedBy: string;
}

/** Raw wire shape of `PlatformFeeHistoryEntryDto` — same BigDecimal caveat as `PlatformFeeConfigWire`. */
export interface PlatformFeeHistoryEntryWire {
  id: string;
  brandFeePercent: number | string;
  creatorFeePercent: number | string;
  previousBrandFeePercent: number | string;
  previousCreatorFeePercent: number | string;
  razorpayAbsorbedByPlatform: boolean;
  reason: string;
  changedBy: string;
  changedAt: string;
}

// ============================================
// SUPPORT
// ============================================

export interface SupportTicket {
  id: string;
  userId: string;
  userType: 'BRAND' | 'CREATOR';
  userName: string;
  category: string;
  subject: string;
  status: TicketStatus;
  priority: TicketPriority;
  assignedTo?: string;
  assignedToName?: string;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string;
}

export interface TicketDetail extends SupportTicket {
  messages: TicketMessage[];
  relatedEntities: RelatedEntity[];
}

export interface TicketMessage {
  id: string;
  senderId: string;
  senderName: string;
  senderType: 'USER' | 'ADMIN';
  content: string;
  createdAt: string;
}

export interface RelatedEntity {
  type: 'CAMPAIGN' | 'DELIVERABLE' | 'PAYMENT' | 'CONTRACT';
  id: string;
  name: string;
}

// ============================================
// MODERATION / APPROVALS
// ============================================

export interface ContentFlag {
  id: string;
  contentType: 'DELIVERABLE' | 'PROFILE' | 'MESSAGE';
  contentId: string;
  contentPreview?: string;
  flagReason: string;
  flaggedBy: 'AI' | 'USER' | 'ADMIN';
  status: ContentFlagStatus;
  actionTaken?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  createdAt: string;
}

export interface ApprovalWorkflow {
  id: string;
  type: WorkflowType;
  entityId: string;
  entityName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  submittedAt: string;
  reviewedBy?: string;
  reviewedAt?: string;
  notes?: string;
}

export interface AccountSuspension {
  id: string;
  userId: string;
  userType: 'BRAND' | 'CREATOR';
  userName: string;
  reason: string;
  suspendedBy: string;
  suspendedAt: string;
  appealStatus: 'NONE' | 'PENDING' | 'REVIEWED';
  appealNotes?: string;
  reinstatedAt?: string;
  reinstatedBy?: string;
}

export interface ModerationAction {
  entityId: string;
  entityType: string;
  action: 'APPROVE' | 'REJECT' | 'WARN' | 'REMOVE' | 'ESCALATE';
  reason: string;
}

// ============================================
// DISPUTES
// ============================================

// Mirrors backend `com.influora.domain.enums.DisputeStatus` (Task #34 / CEO §1.3):
// OPEN -> UNDER_REVIEW -> RESOLVED_*. Jackson serializes the enum by name, so these
// string values must stay in sync with the Java enum.
export enum DisputeStatus {
  OPEN = 'OPEN',
  UNDER_REVIEW = 'UNDER_REVIEW',
  RESOLVED_BRAND = 'RESOLVED_BRAND',
  RESOLVED_CREATOR = 'RESOLVED_CREATOR',
  RESOLVED_SPLIT = 'RESOLVED_SPLIT'
}

/**
 * Row shape for the admin disputes table (Task #4 — `GET /admin/disputes`).
 * Matches `AdminDisputeController.list()` -> `DisputeDtos.DisputeSummaryDto`.
 */
export interface DisputeSummary {
  disputeId: string;
  campaignId: string;
  brandName: string;
  creatorName: string;
  status: DisputeStatus;
  createdAt: string;
  updatedAt: string;
}

/**
 * Full dispute record returned by `POST /admin/disputes/:id/resolve`.
 * Matches `DisputeDtos.DisputeResponse` on the backend.
 */
export interface DisputeDetail {
  id: string;
  collaborationId: string;
  openedByType: string;
  openedByUserId: string;
  reason: string;
  status: string;
  createdAt: string;
  resolvedByAdminId?: string;
  resolutionNotes?: string;
  resolvedAt?: string;
}

export interface DisputeFilters {
  status?: DisputeStatus;
  campaignId?: string;
  brandId?: string;
  creatorId?: string;
}

/** Request body for `POST /admin/disputes/:id/resolve` — matches `ResolveDisputeRequest`. */
export interface DisputeResolution {
  resolution: DisputeStatus;
  notes?: string;
  /**
   * REQUIRED when `resolution === DisputeStatus.RESOLVED_SPLIT` — the percentage (0-100) of the
   * frozen escrow paid to the creator, net of the standard creator-side platform fee; the brand
   * effectively receives the remainder. Matches `ResolveDisputeRequest.creatorSplitPercent`
   * (`BigDecimal`, `@DecimalMin(0)/@DecimalMax(100)`), validated again server-side in
   * `EscrowService.adminSplitForDispute` — that validation is the source of truth; the UI check in
   * `DisputeResolveModal` is a UX affordance only. Omit (or ignore) for any other resolution value;
   * the backend only consults this field on the RESOLVED_SPLIT branch (`DisputeService.resolveDispute`).
   */
  creatorSplitPercent?: number;
}

/** `GET /admin/disputes` envelope — matches `DisputeDtos.PagedDisputeSummaryDto`. */
export type DisputeListResponse = PaginatedResponse<DisputeSummary>;

// ============================================
// AUDIT LOG
// ============================================

/**
 * Kabir finding 2.1 (red-team): distinguishes rows the server itself wrote
 * (authoritative) from rows a client submitted (self-reported, unverified) so
 * a super-admin can't pass off a fabricated entry as authoritative in the
 * viewer. Must be rendered, not just carried — see AuditLogPage.tsx.
 */
export type AuditLogSource = 'SERVER_INTERNAL' | 'CLIENT_REPORTED';

export interface AuditLogEntry {
  id: string;
  adminId: string;
  adminEmail: string;
  action: string;
  entityType: string;
  entityId: string;
  oldValue?: string;
  newValue?: string;
  reason?: string;
  ipAddress: string;
  timestamp: string;
  source: AuditLogSource;
}

// ============================================
// ERROR LOG
// ============================================

export interface ErrorLogEntry {
  id: string;
  severity: ErrorSeverity;
  message: string;
  stackTrace?: string;
  endpoint?: string;
  userId?: string;
  resolved: boolean;
  resolvedBy?: string;
  resolvedAt?: string;
  createdAt: string;
}

// ============================================
// EMAIL QUEUE
// ============================================

export interface EmailQueueItem {
  id: string;
  recipient: string;
  templateId: string;
  templateName: string;
  status: EmailStatus;
  retryCount: number;
  scheduledAt: string;
  sentAt?: string;
  errorMessage?: string;
}

// ============================================
// MARKETING METRICS (CMO)
// ============================================

export interface AcquisitionMetrics {
  cac: {
    brands: { paid: number; organic: number; referral: number };
    creators: { paid: number; organic: number; referral: number };
  };
  conversionRates: {
    creatorApplicationToApproval: number;
    brandSignupToFirstCampaign: number;
  };
  sourceAttribution: SourceAttribution[];
}

export interface SourceAttribution {
  source: string;
  brandSignups: number;
  creatorSignups: number;
  revenue: number;
}

export interface GrowthMetrics {
  funnel: {
    signups: number;
    /** Omitted by GET /admin/marketing/growth — no profile-complete flag exists on the entities. */
    profileComplete?: number;
    firstCampaign: number;
    repeatCampaign: number;
  };
  /**
   * Data-backed conversion rates (0..1) served by GET /admin/marketing/growth's `GrowthMetricsDto`:
   * approved/total creator applications, and brands-with-a-campaign / total brands.
   */
  conversionRates: {
    creatorApplicationToApproval: number;
    brandSignupToFirstCampaign: number;
  };
  /** Omitted by the backend — no retention/activity-event tracking exists yet (see AdminMarketingDtos). */
  cohortRetention?: CohortRetention[];
  /** Omitted by the backend — no Referral table exists yet (see AdminMarketingDtos). */
  referralStats?: {
    invitesSent: number;
    conversions: number;
    conversionRate: number;
    revenueAttributed: number;
  };
}

export interface CohortRetention {
  cohort: string; // e.g., "2026-06"
  day30: number;
  day60: number;
  day90: number;
}

export interface PlatformReputationScore {
  overall: number;
  creatorQualityAvg: number;
  brandSatisfactionAvg: number;
  disputeResolutionSpeed: number; // hours
  calculatedAt: string;
}

// ============================================
// API RESPONSE WRAPPERS
// ============================================

export interface PaginatedResponse<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
  message?: string;
}

// ============================================
// FILTER / SEARCH
// ============================================

export interface BrandFilters {
  search?: string;
  kycStatus?: KycStatus;
  isSuspended?: boolean;
  industry?: string;
  size?: string;
}

export interface CreatorFilters {
  search?: string;
  applicationStatus?: CreatorApplicationStatus;
  instagramVerified?: boolean;
  isSuspended?: boolean;
  niche?: string;
  tier?: string;
  minFollowers?: number;
  maxFollowers?: number;
}

export interface CampaignFilters {
  search?: string;
  status?: string;
  type?: 'STANDARD' | 'HYPE';
  brandId?: string;
  atRisk?: boolean;
}

export interface TicketFilters {
  search?: string;
  status?: TicketStatus;
  priority?: TicketPriority;
  userType?: 'BRAND' | 'CREATOR';
  assignedTo?: string;
  category?: string;
}

// ============================================
// BILLING (W6-1 — admin billing console)
// ============================================

/**
 * Matches `BillingMetricsDto` from AdminBillingDtos.java. The backend returns
 * BigDecimal fields for money amounts, which may serialize as `number | string`.
 * For now typed as `number` here — if Jackson sends strings, normalize in the hook.
 */
export interface BillingMetrics {
  mrrInr: number;
  arrInr: number;
  churnPercent: number;
  activeProCount: number;
  churnedInWindowCount: number;
  churnWindowDays: number;
}

/**
 * One subscription row from `GET /admin/billing/subscriptions`.
 * Matches `AdminSubscriptionRowDto`.
 */
export interface AdminSubscriptionRow {
  subscriptionId: string;
  workspaceId: string;
  workspaceName: string;
  planCode: string;
  status: string;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  seatsPurchased: number;
  isComp: boolean;
  compReason: string | null;
  compExpiresAt: string | null;
}

/**
 * Response envelope for `GET /admin/billing/subscriptions`.
 * Matches `PaginatedSubscriptionResponse` (AdminBillingDtos.java).
 */
export interface PaginatedSubscriptionResponse {
  data: AdminSubscriptionRow[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

/**
 * Request body for `POST /admin/billing/comp`.
 * Matches `CompSubscriptionRequest` (AdminBillingDtos.java).
 * Reason must be >=10 chars server-side.
 */
export interface CompSubscriptionRequest {
  workspaceId: string;
  planCode: string;
  reason: string;
  expiresAt?: string | null;
}

/**
 * Request body for `POST /admin/billing/override`.
 * Matches `OverrideSubscriptionRequest` (AdminBillingDtos.java).
 */
export interface OverrideSubscriptionRequest {
  workspaceId: string;
  planCode: string;
  reason: string;
  expiresAt?: string | null;
}

/**
 * Response from both `POST /admin/billing/comp` and `POST /admin/billing/override`.
 * Matches `AdminSubscriptionActionResultDto`.
 */
export interface AdminSubscriptionActionResult {
  subscriptionId: string;
  workspaceId: string;
  planCode: string;
  status: string;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  isComp: boolean;
  compReason: string | null;
  compExpiresAt: string | null;
}
