import * as React from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Calendar, ChevronDown } from 'lucide-react';

import { ApplicationHistoryTimeline } from '@/components/creator/ApplicationHistoryTimeline';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { bucketOf, getApplicationStatusBadgeProps } from '@/lib/application-status';
import type { CreatorApplicationRow } from '@/lib/api';
import { cn } from '@/lib/utils';

interface CreatorApplicationCardProps {
  application: CreatorApplicationRow;
  className?: string;
}

/**
 * Buckets whose click-through is the campaign detail page (application hasn't
 * turned into a deal in the creator's eyes yet, or is closed/view-only).
 * Everything else opens the deal room — same route/param convention as
 * creator-deals.tsx's `openDeal` (`/creator/chat?deal=<id>`).
 */
const CAMPAIGN_DETAIL_BUCKETS = new Set(['applied', 'shortlisted', 'closed']);

function formatAppliedDate(iso: string): { relative: string; absolute: string } {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return { relative: iso, absolute: iso };
  }
  const absolute = d.toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
  const days = Math.floor((Date.now() - d.getTime()) / (1000 * 60 * 60 * 24));
  let relative: string;
  if (days <= 0) relative = 'Applied today';
  else if (days === 1) relative = 'Applied yesterday';
  else if (days < 30) relative = `Applied ${days} days ago`;
  else relative = `Applied ${absolute}`;
  return { relative, absolute };
}

export function CreatorApplicationCard({ application, className }: CreatorApplicationCardProps) {
  const [journeyOpen, setJourneyOpen] = React.useState(false);
  const brandInitial = application.brandName.charAt(0).toUpperCase() || '?';
  const badge = getApplicationStatusBadgeProps(application.status);
  const bucket = bucketOf(application.status);
  const { relative, absolute } = formatAppliedDate(application.appliedAt);

  const clickThroughHref = CAMPAIGN_DETAIL_BUCKETS.has(bucket)
    ? `/creator/campaigns/${application.campaignId}`
    : `/creator/chat?deal=${application.dealId}`;
  const clickThroughLabel = CAMPAIGN_DETAIL_BUCKETS.has(bucket) ? 'View campaign' : 'Open deal room';

  return (
    <Card className={cn('overflow-hidden transition-shadow hover:shadow-md', className)}>
      <CardContent className="p-5 sm:p-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:gap-4">
          <Avatar className="h-12 w-12 shrink-0 rounded-lg">
            {application.brandLogoUrl ? (
              <AvatarImage src={application.brandLogoUrl} alt={application.brandName} />
            ) : null}
            <AvatarFallback className="rounded-lg text-base">{brandInitial}</AvatarFallback>
          </Avatar>

          <div className="min-w-0 flex-1">
            <div className="mb-1 flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <h3 className="truncate text-base font-semibold">{application.campaignTitle}</h3>
                <p className="text-sm text-muted-foreground">{application.brandName}</p>
              </div>
              <Badge variant={badge.variant} className={badge.className}>
                {badge.label}
              </Badge>
            </div>

            <div
              className="mb-4 flex items-center gap-1 text-xs text-muted-foreground"
              title={absolute}
            >
              <Calendar className="h-3.5 w-3.5" />
              <span>{relative}</span>
            </div>

            <Collapsible open={journeyOpen} onOpenChange={setJourneyOpen}>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="outline" size="sm" asChild>
                  <Link to={clickThroughHref}>
                    {clickThroughLabel}
                    <ArrowRight className="ml-1.5 h-4 w-4" />
                  </Link>
                </Button>

                <CollapsibleTrigger asChild>
                  <Button type="button" variant="ghost" size="sm" aria-expanded={journeyOpen}>
                    {journeyOpen ? 'Hide journey' : 'Show journey'}
                    <ChevronDown
                      className={cn('ml-1.5 h-4 w-4 transition-transform', journeyOpen && 'rotate-180')}
                    />
                  </Button>
                </CollapsibleTrigger>
              </div>

              <CollapsibleContent className="mt-3 border-t border-border pt-3">
                <ApplicationHistoryTimeline dealId={application.dealId} brandName={application.brandName} />
              </CollapsibleContent>
            </Collapsible>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export default CreatorApplicationCard;
