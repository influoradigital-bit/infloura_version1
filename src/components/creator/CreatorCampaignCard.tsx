import * as React from 'react';
import { Check, Copy, ExternalLink } from 'lucide-react';

import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { cn, isSafeHttpUrl } from '@/lib/utils';
import type { CreatorCouponResponse } from '@/lib/api';

interface CreatorCampaignCardProps {
  coupon: CreatorCouponResponse;
  className?: string;
}

/**
 * Adapted from ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §15 "Creator Coupon
 * Dashboard" (CreatorCampaignCard.tsx). The spec's prop shape invents fields
 * the backend does not have yet (`stats.clicks/sales/revenue/commission`,
 * `paymentModel`, `commissionPercent`, per-card share/media-kit actions) — none
 * of that is tracked per-coupon anywhere in `CouponCode`/`TrackingDtos`
 * (see influora-api CampaignTrackingController + TrackingDtos.java). This
 * version only surfaces what `CreatorCouponResponse` actually carries: the
 * code, discount, usage count/limit, expiry, and — when present — a tracking
 * link. No fabricated stats.
 */
export function CreatorCampaignCard({ coupon, className }: CreatorCampaignCardProps) {
  const [copiedCoupon, setCopiedCoupon] = React.useState(false);
  const [copiedLink, setCopiedLink] = React.useState(false);

  const copyToClipboard = async (text: string, type: 'coupon' | 'link') => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      return;
    }
    if (type === 'coupon') {
      setCopiedCoupon(true);
      setTimeout(() => setCopiedCoupon(false), 2000);
    } else {
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 2000);
    }
  };

  const discountLabel =
    coupon.discountType === 'percentage'
      ? `${coupon.discountValue}% off`
      : `₹${coupon.discountValue.toLocaleString('en-IN')} off`;

  const usageLabel =
    coupon.usageLimit != null
      ? `${coupon.usageCount} / ${coupon.usageLimit} used`
      : `${coupon.usageCount} used`;

  // Prefer the click-tracked redirect URL (counts the click) over the raw
  // brand-site tracking URL — falls back gracefully until the backend ships
  // `redirectUrl` for every coupon (see CreatorCouponResponse in lib/api.ts).
  // `trackingUrl` is brand-supplied and unvalidated — a `javascript:...` (or
  // other dangerous-scheme) value must never reach an <a href> or the
  // clipboard, so only an http(s) URL is treated as usable.
  const rawShareUrl = coupon.redirectUrl ?? coupon.trackingUrl;
  const shareUrl = isSafeHttpUrl(rawShareUrl) ? rawShareUrl! : undefined;

  return (
    <Card className={cn('p-6', className)}>
      <div className="mb-4 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate font-semibold text-lg">{coupon.campaignName}</h3>
          <p className="text-sm text-muted-foreground">{coupon.brandName}</p>
        </div>
        <Badge variant="secondary" className="shrink-0">
          {discountLabel}
        </Badge>
      </div>

      {/* Coupon Code */}
      <div className="mb-4">
        <Label className="text-xs text-muted-foreground">YOUR COUPON CODE</Label>
        <div className="mt-1 flex items-center gap-2">
          <code className="flex-1 rounded-md bg-muted px-4 py-2 font-mono text-lg tracking-wide">
            {coupon.code}
          </code>
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label="Copy coupon code"
            onClick={() => copyToClipboard(coupon.code, 'coupon')}
          >
            {copiedCoupon ? <Check className="h-4 w-4 text-success-foreground" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      {/* Tracking Link — only rendered when the backend actually returned one */}
      {shareUrl && (
        <div className="mb-4">
          <Label className="text-xs text-muted-foreground">YOUR TRACKING LINK</Label>
          <div className="mt-1 flex items-center gap-2">
            <Input readOnly value={shareUrl} className="font-mono text-sm" />
            <Button
              type="button"
              variant="outline"
              size="icon"
              aria-label="Copy tracking link"
              onClick={() => copyToClipboard(shareUrl, 'link')}
            >
              {copiedLink ? <Check className="h-4 w-4 text-success-foreground" /> : <Copy className="h-4 w-4" />}
            </Button>
            <Button type="button" variant="outline" size="icon" aria-label="Open tracking link" asChild>
              <a href={shareUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" />
              </a>
            </Button>
          </div>
        </div>
      )}

      {/* Usage — the only real per-coupon number the backend tracks today */}
      <div className="flex items-center justify-between border-t border-border pt-4 text-sm">
        <span className="text-muted-foreground">Usage</span>
        <span className="font-medium">{usageLabel}</span>
      </div>
      {coupon.expiresAt && (
        <div className="mt-1 flex items-center justify-between text-sm">
          <span className="text-muted-foreground">Expires</span>
          <span className="font-medium">
            {new Date(coupon.expiresAt).toLocaleDateString('en-IN', {
              day: 'numeric',
              month: 'short',
              year: 'numeric',
            })}
          </span>
        </div>
      )}
    </Card>
  );
}

export default CreatorCampaignCard;
