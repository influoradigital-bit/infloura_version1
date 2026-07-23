import type { IconBadgeVariant } from '@/components/shared/icon-badge';

/** Pastel icon badge variant per brand sidebar route */
export const brandNavIconVariant: Record<string, IconBadgeVariant> = {
  '/brand/dashboard': 'primary',
  '/brand/meera': 'primary',
  '/brand/campaigns': 'outreach',
  '/brand/discover': 'contracted',
  '/brand/chat': 'negotiating',
  '/brand/deals': 'negotiating',
  '/brand/messages': 'outreach',
  '/brand/contracts': 'review',
  '/brand/wallet': 'approved',
  '/brand/settings': 'muted',
  '/brand/pipeline': 'progress',
  '/brand/analytics': 'info',
  '/brand/reviews': 'approved',
  '/brand/disputes': 'disputed',
};

/** Pastel icon badge variant per creator sidebar route */
export const creatorNavIconVariant: Record<string, IconBadgeVariant> = {
  '/creator/inbox': 'outreach',
  '/creator/chat': 'negotiating',
  '/creator/active': 'progress',
  '/creator/wallet': 'approved',
  '/creator/profile': 'contracted',
  '/creator/settings': 'muted',
  '/creator/reviews': 'approved',
  '/creator/disputes': 'disputed',
  '/creator/coupons': 'outreach',
  '/creator/affiliate': 'info',
};

export function getBrandNavIconVariant(href: string): IconBadgeVariant {
  return brandNavIconVariant[href] ?? 'muted';
}

export function getCreatorNavIconVariant(href: string): IconBadgeVariant {
  return creatorNavIconVariant[href] ?? 'muted';
}

/** Map campaign state machine ids to icon badge variants */
export const campaignStateIconVariant: Record<string, IconBadgeVariant> = {
  DRAFT: 'draft',
  FUNDED: 'outreach',
  LIVE: 'progress',
  MATCHED: 'outreach',
  NEGOTIATING: 'negotiating',
  CONTRACTED: 'contracted',
  IN_PRODUCTION: 'progress',
  IN_REVIEW: 'review',
  SETTLED: 'approved',
};

export function getCampaignStateIconVariant(stateId: string): IconBadgeVariant {
  return campaignStateIconVariant[stateId] ?? 'draft';
}
