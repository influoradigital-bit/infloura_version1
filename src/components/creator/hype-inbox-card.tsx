import * as React from 'react';
import { CheckCircle2, Loader2, Music2, Zap } from 'lucide-react';

import { cn, formatINR } from '@/lib/utils';
import type { HypeInvite } from '@/lib/demo-data';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { SlotProgressBar } from '@/components/ui/slot-progress-bar';
import { VerifiedBadge } from '@/components/ui/verified-badge';

interface HypeInboxCardProps {
  invite: HypeInvite;
  onAccept: (invite: HypeInvite) => Promise<void> | void;
  className?: string;
}

/**
 * Creator-inbox card for Hype campaign invites: cyan glow border, ⚡ Hype
 * badge, and a single-tap Accept (no negotiation — flat per-reel rate).
 */
export function HypeInboxCard({ invite, onAccept, className }: HypeInboxCardProps) {
  const [state, setState] = React.useState<'idle' | 'loading' | 'accepted'>('idle');

  const hoursLeft = Math.max(0, Math.round((new Date(invite.liveUntil).getTime() - Date.now()) / 3600000));
  const slotsLeft = Math.max(0, invite.slotCap - invite.slotsFilled);

  const handleAccept = async () => {
    setState('loading');
    try {
      await onAccept(invite);
      setState('accepted');
    } catch {
      setState('idle');
    }
  };

  return (
    <Card className={cn('border-hype-border hype-glow', className)}>
      <CardContent className="p-4 space-y-3">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                <Zap className="h-3 w-3" aria-hidden="true" /> Hype
              </Badge>
              <HypeLiveIndicator hoursLeft={hoursLeft} />
            </div>
            <div className="mt-2 flex items-center gap-1.5">
              <p className="font-medium truncate">{invite.brandName}</p>
              {invite.brandVerified && <VerifiedBadge />}
            </div>
            <p className="text-sm text-muted-foreground truncate">{invite.campaignTitle}</p>
          </div>
          <div className="text-right shrink-0">
            <p className="text-base font-semibold">{formatINR(invite.perReelRate)}</p>
            <p className="text-[10px] text-muted-foreground">per reel</p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span className="font-mono text-hype-foreground">{invite.hashtag}</span>
          {invite.audioTrack && (
            <span className="inline-flex items-center gap-1 truncate">
              <Music2 className="h-3 w-3 shrink-0" aria-hidden="true" />
              {invite.audioTrack}
            </span>
          )}
        </div>

        <div className="flex flex-wrap gap-1.5">
          {invite.formatLanes.map((lane) => (
            <Badge key={lane} variant="outline" className="text-[10px] font-normal">
              {lane}
            </Badge>
          ))}
        </div>

        <SlotProgressBar filled={invite.slotsFilled} total={invite.slotCap} showLabel={false} />

        <div className="flex items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">
            <span className="font-medium text-hype-foreground">{slotsLeft}</span> slots left
          </p>
          {state === 'accepted' ? (
            <span className="inline-flex items-center gap-1 text-sm font-medium text-success-foreground">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" /> You're in
            </span>
          ) : (
            <Button
              size="sm"
              onClick={handleAccept}
              disabled={state === 'loading' || slotsLeft === 0}
              className="h-8 bg-hype-solid text-white hover:bg-hype-solid/90"
            >
              {state === 'loading' ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" aria-label="Accepting" />
              ) : (
                <>
                  <Zap className="h-3.5 w-3.5" aria-hidden="true" /> Accept
                </>
              )}
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
