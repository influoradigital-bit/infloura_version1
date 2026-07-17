import { useMemo } from 'react';
import { useReducedMotion, motion } from 'framer-motion';
import { BarChart3, MousePointerClick, ShoppingCart, Tag } from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { CouponResponse, TrackingLinkResponse } from '@/lib/api';

interface CampaignROICardProps {
  links: TrackingLinkResponse[];
  coupons: CouponResponse[];
  loading?: boolean;
  className?: string;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    notation: amount >= 100000 ? 'compact' : 'standard',
    maximumFractionDigits: amount >= 100000 ? 1 : 0,
  }).format(amount);
}

/**
 * Conversion/ROI summary built strictly from what CampaignTrackingController
 * actually returns: click count, conversion count, and revenue attributed per
 * UTM link, plus coupon usage counts. There is no impressions or add-to-cart
 * data anywhere in this system (no ad-server/impression pixel exists), so
 * this deliberately does NOT render the spec's full
 * impressions -> clicks -> add-to-cart -> purchase funnel — that would
 * require fabricating two of its four stages. Instead this shows the two
 * real stages (clicks -> conversions) plus the revenue and coupon-usage
 * totals the API actually provides.
 */
export function CampaignROICard({ links, coupons, loading = false, className }: CampaignROICardProps) {
  const shouldReduceMotion = useReducedMotion();

  const totals = useMemo(() => {
    const clicks = links.reduce((sum, l) => sum + l.clickCount, 0);
    const conversions = links.reduce((sum, l) => sum + l.conversionCount, 0);
    const revenue = links.reduce((sum, l) => sum + l.revenueAttributed, 0);
    const couponRedemptions = coupons.reduce((sum, c) => sum + c.usageCount, 0);
    const conversionRate = clicks > 0 ? (conversions / clicks) * 100 : 0;
    return { clicks, conversions, revenue, couponRedemptions, conversionRate };
  }, [links, coupons]);

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-32" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-24 w-full" />
        </CardContent>
      </Card>
    );
  }

  const hasData = links.length > 0 || coupons.length > 0;

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BarChart3 className="h-5 w-5" aria-hidden="true" />
          Clicks &amp; Conversions
        </CardTitle>
        <CardDescription>
          Aggregated across all tracking links{coupons.length > 0 ? ' and coupons' : ''} on this campaign
        </CardDescription>
      </CardHeader>
      <CardContent>
        {!hasData ? (
          <p className="text-sm text-muted-foreground">
            Generate a tracking link or coupon to start seeing performance here.
          </p>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
              <div className="space-y-1">
                <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <MousePointerClick className="h-3.5 w-3.5" aria-hidden="true" />
                  Clicks
                </div>
                <p className="text-2xl font-bold">{totals.clicks.toLocaleString('en-IN')}</p>
              </div>
              <div className="space-y-1">
                <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <ShoppingCart className="h-3.5 w-3.5" aria-hidden="true" />
                  Conversions
                </div>
                <p className="text-2xl font-bold">{totals.conversions.toLocaleString('en-IN')}</p>
              </div>
              <div className="space-y-1">
                <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <Tag className="h-3.5 w-3.5" aria-hidden="true" />
                  Coupon Redemptions
                </div>
                <p className="text-2xl font-bold">{totals.couponRedemptions.toLocaleString('en-IN')}</p>
              </div>
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">Revenue Attributed</div>
                <p className="text-2xl font-bold text-primary">{formatCurrency(totals.revenue)}</p>
              </div>
            </div>

            {totals.clicks > 0 && (
              <div className="mt-6 space-y-1.5">
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">Click-to-conversion rate</span>
                  <span className="font-semibold">{totals.conversionRate.toFixed(1)}%</span>
                </div>
                <div
                  className="relative h-3 overflow-hidden rounded-full bg-muted"
                  role="img"
                  aria-label={`${totals.conversions} conversions from ${totals.clicks} clicks, a ${totals.conversionRate.toFixed(1)}% rate`}
                >
                  <motion.div
                    className={cn('absolute inset-y-0 left-0 rounded-full bg-primary')}
                    initial={{ width: 0 }}
                    animate={{ width: `${Math.min(100, totals.conversionRate)}%` }}
                    transition={{ duration: shouldReduceMotion ? 0 : 0.6, ease: 'easeOut' }}
                  />
                </div>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

export default CampaignROICard;
