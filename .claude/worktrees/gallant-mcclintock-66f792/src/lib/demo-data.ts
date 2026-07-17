/**
 * Influora demo data — single source for seeded demo entities across the
 * brand, creator, and admin flows. Consumed by the mock branches in
 * `lib/api.ts` and directly by pages that render demo state.
 *
 * Pages that predate this file keep their own inline mocks; new demo data
 * (Hype campaigns, admin console) lives here so all three roles stay
 * consistent with each other (same brands, creators, and deal amounts).
 */

import type {
  Campaign,
  CreatorProfile,
  Dispute,
  HypeConfig,
  TransactionStatus,
  UserStatus,
  UserType,
  VerificationStatus,
  WalletTransactionType,
} from './types';

const HOURS = 60 * 60 * 1000;
const DAYS = 24 * HOURS;

// ---------------------------------------------------------------------------
// HYPE CAMPAIGNS — 72-hr blitz campaigns (brand side)
// ---------------------------------------------------------------------------

export const demoHypeConfig: HypeConfig = {
  sourceReelUrl: 'https://instagram.com/reel/influora-launch-anthem',
  audioTrack: 'Bombay Dreams (Sped Up) — Ritviz',
  hashtag: '#GlowDropChallenge',
  formatLanes: ['Remix the hook', 'Duet reaction', 'Original spin'],
  perReelRate: 3500,
  currency: 'INR',
  slotCap: 100,
  slotsFilled: 63,
  liveUntil: new Date(Date.now() + 41 * HOURS),
};

export const demoHypeCampaign: Campaign & {
  collaboratorsCount: number;
  progress: number;
} = {
  id: 'hype-1',
  workspaceId: 'ws-1',
  title: 'Glow Drop Challenge',
  description:
    'Blitz launch for the Glow Drop serum — creators remix our anthem reel with the campaign audio and hashtag within the 72-hour window.',
  objectives: ['Viral reach', 'Launch buzz'],
  campaignType: 'HYPE',
  hype: demoHypeConfig,
  status: 'ACTIVE',
  budget: { min: 350000, max: 350000, currency: 'INR' },
  timeline: {
    startDate: new Date(Date.now() - 31 * HOURS),
    endDate: new Date(Date.now() + 41 * HOURS),
  },
  platforms: ['INSTAGRAM'],
  contentTypes: ['REEL'],
  isPrivate: false,
  maxCollaborators: 100,
  createdBy: 'user-1',
  createdAt: new Date(Date.now() - 2 * DAYS),
  updatedAt: new Date(Date.now() - 3 * HOURS),
  collaboratorsCount: 63,
  progress: 63,
};

/** Hype invite as it appears in the creator inbox (one-tap accept). */
export interface HypeInvite {
  id: string;
  brandName: string;
  brandVerified: boolean;
  campaignTitle: string;
  hashtag: string;
  audioTrack?: string;
  formatLanes: string[];
  perReelRate: number;
  slotCap: number;
  slotsFilled: number;
  liveUntil: Date;
  sourceReelUrl: string;
}

export const demoHypeInvite: HypeInvite = {
  id: 'hype-inv-1',
  brandName: 'Glow Naturals',
  brandVerified: true,
  campaignTitle: 'Glow Drop Challenge',
  hashtag: demoHypeConfig.hashtag,
  audioTrack: demoHypeConfig.audioTrack,
  formatLanes: demoHypeConfig.formatLanes,
  perReelRate: demoHypeConfig.perReelRate,
  slotCap: demoHypeConfig.slotCap,
  slotsFilled: demoHypeConfig.slotsFilled,
  liveUntil: demoHypeConfig.liveUntil,
  sourceReelUrl: demoHypeConfig.sourceReelUrl,
};

// ---------------------------------------------------------------------------
// ADMIN CONSOLE — users, disputes, transaction oversight, KYC queue
// ---------------------------------------------------------------------------

export interface AdminUserRow {
  id: string;
  displayName: string;
  email: string;
  userType: UserType;
  status: UserStatus;
  verificationStatus: VerificationStatus;
  totalDeals: number;
  gmv: number; // lifetime deal value through platform, INR
  joinedAt: Date;
  lastActiveAt: Date;
}

export const demoAdminUsers: AdminUserRow[] = [
  { id: 'u_1',  displayName: 'Nykaa Fashion',   email: 'partnerships@nykaa.com',   userType: 'BRAND',   status: 'ACTIVE',               verificationStatus: 'VERIFIED',   totalDeals: 34, gmv: 1850000, joinedAt: new Date('2025-11-02'), lastActiveAt: new Date(Date.now() - 2 * HOURS) },
  { id: 'u_2',  displayName: 'Glow Naturals',   email: 'growth@glownaturals.in',   userType: 'BRAND',   status: 'ACTIVE',               verificationStatus: 'VERIFIED',   totalDeals: 12, gmv: 640000,  joinedAt: new Date('2026-01-18'), lastActiveAt: new Date(Date.now() - 30 * 60 * 1000) },
  { id: 'u_3',  displayName: 'BoAt Lifestyle',  email: 'creators@boat.in',         userType: 'BRAND',   status: 'ACTIVE',               verificationStatus: 'VERIFIED',   totalDeals: 21, gmv: 1260000, joinedAt: new Date('2025-12-09'), lastActiveAt: new Date(Date.now() - 5 * HOURS) },
  { id: 'u_4',  displayName: 'UrbanKart',       email: 'admin@urbankart.shop',     userType: 'BRAND',   status: 'PENDING_VERIFICATION', verificationStatus: 'PENDING',    totalDeals: 0,  gmv: 0,       joinedAt: new Date(Date.now() - 2 * DAYS), lastActiveAt: new Date(Date.now() - 1 * DAYS) },
  { id: 'cr_1', displayName: 'Priya Creates',   email: 'priya@creates.in',         userType: 'CREATOR', status: 'ACTIVE',               verificationStatus: 'VERIFIED',   totalDeals: 45, gmv: 1420000, joinedAt: new Date('2025-10-21'), lastActiveAt: new Date(Date.now() - 15 * 60 * 1000) },
  { id: 'cr_2', displayName: 'Arjun Kapoor',    email: 'arjun@techtakes.in',       userType: 'CREATOR', status: 'ACTIVE',               verificationStatus: 'VERIFIED',   totalDeals: 28, gmv: 2100000, joinedAt: new Date('2025-11-30'), lastActiveAt: new Date(Date.now() - 8 * HOURS) },
  { id: 'cr_3', displayName: 'Sneha Reddy',     email: 'sneha@wellness.co',        userType: 'CREATOR', status: 'ACTIVE',               verificationStatus: 'PENDING',    totalDeals: 9,  gmv: 310000,  joinedAt: new Date('2026-03-14'), lastActiveAt: new Date(Date.now() - 1 * DAYS) },
  { id: 'cr_4', displayName: 'Rahul Verma',     email: 'rahul@vermafilms.in',      userType: 'CREATOR', status: 'SUSPENDED',            verificationStatus: 'REJECTED',   totalDeals: 3,  gmv: 95000,   joinedAt: new Date('2026-02-02'), lastActiveAt: new Date(Date.now() - 12 * DAYS) },
];

export interface AdminDisputeRow extends Pick<Dispute, 'id' | 'type' | 'status' | 'title'> {
  brandName: string;
  creatorName: string;
  amountAtStake: number;
  openedAt: Date;
  slaHoursLeft: number;
}

export const demoAdminDisputes: AdminDisputeRow[] = [
  { id: 'dsp_1', type: 'DELIVERABLE_QUALITY', status: 'UNDER_REVIEW', title: 'Reel does not match agreed brief (2 revisions exhausted)', brandName: 'BoAt Lifestyle', creatorName: 'Rahul Verma',  amountAtStake: 45000, openedAt: new Date(Date.now() - 2 * DAYS), slaHoursLeft: 22 },
  { id: 'dsp_2', type: 'PAYMENT',             status: 'OPEN',         title: 'Escrow release delayed 6 days past approval',              brandName: 'UrbanKart',      creatorName: 'Sneha Reddy',  amountAtStake: 28000, openedAt: new Date(Date.now() - 1 * DAYS), slaHoursLeft: 46 },
  { id: 'dsp_3', type: 'TIMELINE',            status: 'MEDIATION',    title: 'Deadline extension disagreement — festival campaign',      brandName: 'Nykaa Fashion',  creatorName: 'Arjun Kapoor', amountAtStake: 75000, openedAt: new Date(Date.now() - 4 * DAYS), slaHoursLeft: 8 },
  { id: 'dsp_4', type: 'CONTRACT_BREACH',     status: 'RESOLVED',     title: 'Exclusivity clause violation claim (dismissed)',           brandName: 'Glow Naturals',  creatorName: 'Priya Creates', amountAtStake: 35000, openedAt: new Date(Date.now() - 9 * DAYS), slaHoursLeft: 0 },
];

export interface AdminTransactionRow {
  id: string;
  type: WalletTransactionType;
  status: TransactionStatus;
  amount: number;
  tds?: number;
  platformFee?: number;
  brandName?: string;
  creatorName?: string;
  reference: string;
  createdAt: Date;
}

export const demoAdminTransactions: AdminTransactionRow[] = [
  { id: 'tx_9001', type: 'DEPOSIT',        status: 'COMPLETED', amount: 350000, brandName: 'Glow Naturals',  reference: 'Hype: Glow Drop Challenge — escrow funding', createdAt: new Date(Date.now() - 2 * DAYS) },
  { id: 'tx_9002', type: 'ESCROW_HOLD',    status: 'COMPLETED', amount: 45000,  brandName: 'Nykaa Fashion',  creatorName: 'Priya Creates', reference: 'Deal deal-new-1 — Summer Collection', createdAt: new Date(Date.now() - 26 * HOURS) },
  { id: 'tx_9003', type: 'ESCROW_RELEASE', status: 'COMPLETED', amount: 50000,  tds: 5000, platformFee: 4000, brandName: 'Mamaearth', creatorName: 'Priya Creates', reference: 'Deal deal-active-1 — milestone 1 of 3', createdAt: new Date(Date.now() - 18 * HOURS) },
  { id: 'tx_9004', type: 'WITHDRAWAL',     status: 'PENDING',   amount: 82000,  tds: 8200, creatorName: 'Arjun Kapoor', reference: 'Payout to HDFC ****4521', createdAt: new Date(Date.now() - 6 * HOURS) },
  { id: 'tx_9005', type: 'PAYMENT',        status: 'COMPLETED', amount: 3500,   tds: 350,  creatorName: 'Sneha Reddy', brandName: 'Glow Naturals', reference: 'Hype reel payout — slot 41/100', createdAt: new Date(Date.now() - 3 * HOURS) },
  { id: 'tx_9006', type: 'REFUND',         status: 'COMPLETED', amount: 28000,  brandName: 'UrbanKart', reference: 'Cancelled deal deal-x1 — full escrow refund', createdAt: new Date(Date.now() - 5 * DAYS) },
  { id: 'tx_9007', type: 'FEE',            status: 'FAILED',    amount: 1200,   brandName: 'BoAt Lifestyle', reference: 'Wallet recharge fee — gateway timeout, retrying', createdAt: new Date(Date.now() - 90 * 60 * 1000) },
];

export interface AdminKycRow {
  id: string;
  name: string;
  userType: UserType;
  document: string;
  submittedAt: Date;
  status: VerificationStatus;
}

export const demoAdminKycQueue: AdminKycRow[] = [
  { id: 'kyc_1', name: 'UrbanKart',   userType: 'BRAND',   document: 'GSTIN + PAN',        submittedAt: new Date(Date.now() - 1 * DAYS),  status: 'PENDING' },
  { id: 'kyc_2', name: 'Sneha Reddy', userType: 'CREATOR', document: 'PAN + Aadhaar (L4)', submittedAt: new Date(Date.now() - 2 * DAYS),  status: 'PENDING' },
  { id: 'kyc_3', name: 'FitFuel Co',  userType: 'BRAND',   document: 'GSTIN + PAN',        submittedAt: new Date(Date.now() - 4 * HOURS), status: 'PENDING' },
];

export interface AdminPlatformStats {
  totalUsers: number;
  activeBrands: number;
  activeCreators: number;
  liveCampaigns: number;
  liveHypeCampaigns: number;
  escrowUnderManagement: number;
  gmvThisMonth: number;
  openDisputes: number;
  pendingKyc: number;
  pendingPayouts: number;
}

export const demoAdminStats: AdminPlatformStats = {
  totalUsers: 12480,
  activeBrands: 342,
  activeCreators: 8915,
  liveCampaigns: 87,
  liveHypeCampaigns: 6,
  escrowUnderManagement: 18400000,
  gmvThisMonth: 42600000,
  openDisputes: demoAdminDisputes.filter((d) => d.status !== 'RESOLVED' && d.status !== 'CLOSED').length,
  pendingKyc: demoAdminKycQueue.length,
  pendingPayouts: 1240000,
};

// ---------------------------------------------------------------------------
// CREATOR DISCOVERY — richer seed for api.creators.search mock branch
// ---------------------------------------------------------------------------

export const demoCreators: CreatorProfile[] = [
  {
    id: 'cr_1',
    userId: 'cr_1',
    displayName: 'Priya Creates',
    bio: 'Fashion creator helping brands tell stories through aesthetic content.',
    location: 'Mumbai',
    categories: ['Fashion & Lifestyle', 'Beauty'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@priya_creates', followers: 125000, engagementRate: 4.2, isVerified: true },
      { platform: 'YOUTUBE', handle: 'Priya Creates', followers: 50000, engagementRate: 3.8, isVerified: true },
    ],
    totalFollowers: 175000,
    engagementRate: 4.1,
    averageRate: 35000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'English', 'Marathi'],
  },
  {
    id: 'cr_2',
    userId: 'cr_2',
    displayName: 'Arjun Kapoor',
    bio: 'Tech reviews people actually finish watching.',
    location: 'Bangalore',
    categories: ['Tech', 'Gadgets'],
    platforms: [
      { platform: 'YOUTUBE', handle: 'TechTakes', followers: 480000, engagementRate: 5.1, isVerified: true },
      { platform: 'INSTAGRAM', handle: '@arjun.techtakes', followers: 92000, engagementRate: 3.4, isVerified: true },
    ],
    totalFollowers: 572000,
    engagementRate: 4.8,
    averageRate: 90000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['English', 'Hindi'],
  },
  {
    id: 'cr_3',
    userId: 'cr_3',
    displayName: 'Sneha Reddy',
    bio: 'Wellness, yoga and honest morning routines from Hyderabad.',
    location: 'Hyderabad',
    categories: ['Wellness', 'Fitness'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@sneha.moves', followers: 68000, engagementRate: 6.2, isVerified: false },
    ],
    totalFollowers: 68000,
    engagementRate: 6.2,
    averageRate: 18000,
    currency: 'INR',
    isVerified: false,
    portfolioItems: [],
    languages: ['Telugu', 'English'],
  },
];
