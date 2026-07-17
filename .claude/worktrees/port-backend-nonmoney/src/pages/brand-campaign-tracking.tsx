import * as React from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

import { Button } from '@/components/ui/button';

import { UTMGeneratorForm } from '@/components/campaigns/tracking/UTMGeneratorForm';
import { CouponCodeGenerator } from '@/components/campaigns/tracking/CouponCodeGenerator';
import { TrackingLinksTable } from '@/components/campaigns/tracking/TrackingLinksTable';
import { CouponsTable } from '@/components/campaigns/tracking/CouponsTable';
import { CampaignROICard } from '@/components/campaigns/tracking/CampaignROICard';

import { useCampaignTrackingLinks } from '@/hooks/analytics/useCampaignTrackingLinks';
import { useCampaignCoupons } from '@/hooks/analytics/useCampaignCoupons';

/**
 * Campaign tracking — UTM link + coupon generation and performance summary
 * for one campaign. Route: /brand/campaigns/:campaignId/tracking.
 *
 * Consumes influora-api CampaignTrackingController (Wave A task A1):
 *   POST/GET /campaigns/{campaignId}/tracking-links
 *   POST/GET /campaigns/{campaignId}/coupons
 *
 * Scope note (per Ananya's task brief, adapting
 * ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §1.5/§2.2 to this repo's real
 * React Router convention and the real endpoint contract): the spec's full
 * "impressions -> clicks -> add-to-cart -> purchase" ConversionFunnel and
 * per-creator "AI coupon suggestions" both assume data/services that don't
 * exist in this backend (no impression tracking anywhere in the system, no
 * AI code-suggestion endpoint). CampaignROICard here is rebuilt to show only
 * the two real funnel stages this API provides (clicks -> conversions) plus
 * revenue and coupon-redemption totals — nothing fabricated.
 */
export default function BrandCampaignTrackingPage() {
  const { campaignId } = useParams<{ campaignId: string }>();

  const trackingLinks = useCampaignTrackingLinks(campaignId);
  const coupons = useCampaignCoupons(campaignId);

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" asChild>
          <Link to={`/brand/campaigns/${campaignId}`} aria-label="Back to campaign">
            <ArrowLeft className="h-5 w-5" />
          </Link>
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Campaign Tracking</h1>
          <p className="text-muted-foreground">
            Generate tracking links and coupons, and see clicks, conversions, and revenue by creator.
          </p>
        </div>
      </div>

      <CampaignROICard
        links={trackingLinks.data}
        coupons={coupons.data}
        loading={trackingLinks.loading || coupons.loading}
      />

      <div className="grid gap-6 lg:grid-cols-2">
        <UTMGeneratorForm onCreate={trackingLinks.create} creating={trackingLinks.creating} />
        <CouponCodeGenerator onCreate={coupons.create} creating={coupons.creating} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <TrackingLinksTable
          links={trackingLinks.data}
          loading={trackingLinks.loading}
          error={trackingLinks.error}
        />
        <CouponsTable coupons={coupons.data} loading={coupons.loading} error={coupons.error} />
      </div>
    </div>
  );
}
