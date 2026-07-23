import { Link } from 'react-router-dom';
import { IndianRupee, Music2, Zap } from 'lucide-react';

import { cn, formatINR } from '@/lib/utils';
import type { Campaign, CampaignStatus } from '@/lib/types';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { SlotProgressBar } from '@/components/ui/slot-progress-bar';

interface HypeCampaignCardProps {
  campaign: Campaign;
  className?: string;
}

/** Mirrors campaigns-list.tsx's statusConfig for the plain-card fallback below. */
const statusConfig: Record<CampaignStatus, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  DRAFT: { label: 'Draft', variant: 'secondary' },
  PENDING_APPROVAL: { label: 'Pending', variant: 'outline' },
  ACTIVE: { label: 'Active', variant: 'default' },
  PAUSED: { label: 'Paused', variant: 'outline' },
  COMPLETED: { label: 'Completed', variant: 'secondary' },
  CANCELLED: { label: 'Cancelled', variant: 'destructive' },
};

/**
 * Cyan-glow campaign card used in the brand campaigns list for HYPE type
 * campaigns. Falls back gracefully to a plain campaign card (title,
 * description, status badge, View link) when the hype config is missing,
 * so this component never silently drops a campaign for any caller.
 */
export function HypeCampaignCard({ campaign, className }: HypeCampaignCardProps) {
  const hype = campaign.hype;

  if (!hype) {
    return (
      <Card className={cn('group overflow-hidden transition-colors hover:border-primary/50', className)}>
        <CardHeader className="pb-3">
          <div className="flex items-start justify-between gap-2">
            <div className="space-y-1">
              <Link
                to={`/brand/campaigns/${campaign.id}`}
                className="line-clamp-1 font-semibold hover:underline"
              >
                {campaign.title}
              </Link>
              <Badge variant={statusConfig[campaign.status].variant}>
                {statusConfig[campaign.status].label}
              </Badge>
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          {campaign.description && (
            <p className="text-sm text-muted-foreground line-clamp-2">{campaign.description}</p>
          )}
          <Link
            to={`/brand/campaigns/${campaign.id}`}
            className="text-xs font-medium text-primary hover:underline"
          >
            View
          </Link>
        </CardContent>
      </Card>
    );
  }

  const hoursLeft = Math.max(0, Math.round((new Date(hype.liveUntil).getTime() - Date.now()) / 3600000));
  const isLive = campaign.status === 'ACTIVE' && hoursLeft > 0;

  return (
    <Link to={`/brand/campaigns/${campaign.id}`} className="block focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hype-solid rounded-lg">
      <Card className={cn('border-hype-border transition-shadow hover:hype-glow', isLive && 'hype-glow', className)}>
        <CardHeader className="pb-3">
          <div className="flex items-start justify-between gap-2">
            <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
              <Zap className="h-3 w-3" aria-hidden="true" /> Hype
            </Badge>
            {isLive && <HypeLiveIndicator hoursLeft={hoursLeft} />}
          </div>
          <h3 className="mt-2 font-semibold leading-tight">{campaign.title}</h3>
          <p className="text-sm text-muted-foreground line-clamp-2">{campaign.description}</p>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1 font-medium text-foreground">
              <IndianRupee className="h-3 w-3" aria-hidden="true" />
              {formatINR(hype.perReelRate)} / reel
            </span>
            {hype.audioTrack && (
              <span className="inline-flex items-center gap-1 truncate">
                <Music2 className="h-3 w-3 shrink-0" aria-hidden="true" />
                {hype.audioTrack}
              </span>
            )}
            <span className="font-mono">{hype.hashtag}</span>
          </div>
          <SlotProgressBar filled={hype.slotsFilled} total={hype.slotCap} />
        </CardContent>
      </Card>
    </Link>
  );
}
