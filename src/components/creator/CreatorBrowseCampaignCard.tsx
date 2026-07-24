import { Link } from 'react-router-dom';
import {
  ArrowRight,
  Calendar,
  IndianRupee,
  MapPin,
  Users,
} from 'lucide-react';

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import type { CreatorCampaignListItem } from '@/lib/api';
import { getApplicationStatusBadgeProps } from '@/lib/application-status';
import { cn, formatINR } from '@/lib/utils';

function daysUntilDeadline(deadline: string | null): number | null {
  if (!deadline) return null;
  const end = new Date(deadline);
  if (Number.isNaN(end.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  end.setHours(0, 0, 0, 0);
  return Math.ceil((end.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
}

function formatPlatformLabel(platform: string): string {
  return platform.charAt(0) + platform.slice(1).toLowerCase();
}

interface CreatorBrowseCampaignCardProps {
  campaign: CreatorCampaignListItem;
  className?: string;
}

export function CreatorBrowseCampaignCard({ campaign, className }: CreatorBrowseCampaignCardProps) {
  const daysLeft = daysUntilDeadline(campaign.applicationDeadline);
  const brandName = campaign.brand?.name ?? 'Brand';
  const brandInitial = brandName.charAt(0).toUpperCase();

  return (
    <Card className={cn('overflow-hidden transition-shadow hover:shadow-md', className)}>
      <CardContent className="p-5 sm:p-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:gap-6">
          <Avatar className="h-14 w-14 shrink-0 rounded-lg sm:h-16 sm:w-16">
            {campaign.brand?.logoUrl ? (
              <AvatarImage src={campaign.brand.logoUrl} alt={brandName} />
            ) : null}
            <AvatarFallback className="rounded-lg text-lg">{brandInitial}</AvatarFallback>
          </Avatar>

          <div className="min-w-0 flex-1">
            <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <h3 className="truncate text-lg font-semibold">{campaign.title}</h3>
                <p className="text-sm text-muted-foreground">{brandName}</p>
              </div>
              {daysLeft != null && (
                <Badge variant={daysLeft <= 3 ? 'destructive' : 'secondary'} className="shrink-0">
                  {daysLeft <= 0 ? 'Deadline passed' : `${daysLeft} day${daysLeft === 1 ? '' : 's'} left`}
                </Badge>
              )}
            </div>

            <p className="mb-3 line-clamp-2 text-sm text-muted-foreground">{campaign.description}</p>

            <div className="mb-3 flex flex-wrap gap-x-4 gap-y-2 text-sm">
              {campaign.budget && (
                <div className="flex items-center gap-1">
                  <IndianRupee className="h-4 w-4 text-success-foreground" />
                  <span>
                    {formatINR(campaign.budget.min)} – {formatINR(campaign.budget.max)}
                  </span>
                </div>
              )}
              {campaign.platforms.length > 0 && (
                <div className="flex flex-wrap items-center gap-1">
                  {campaign.platforms.map((p) => (
                    <Badge key={p} variant="outline" className="text-xs">
                      {formatPlatformLabel(p)}
                    </Badge>
                  ))}
                </div>
              )}
              {campaign.maxCollaborators != null && (
                <div className="flex items-center gap-1 text-muted-foreground">
                  <Users className="h-4 w-4" />
                  <span>{campaign.maxCollaborators} creators</span>
                </div>
              )}
              {campaign.requirements[0] && (
                <div className="flex items-center gap-1 text-muted-foreground">
                  <MapPin className="h-4 w-4" />
                  <span className="truncate">{campaign.requirements[0]}</span>
                </div>
              )}
            </div>

            {campaign.applicationDeadline && (
              <div className="mb-4 flex items-center gap-1 text-xs text-muted-foreground">
                <Calendar className="h-3.5 w-3.5" />
                <span>
                  Apply by{' '}
                  {new Date(campaign.applicationDeadline).toLocaleDateString('en-IN', {
                    day: 'numeric',
                    month: 'short',
                    year: 'numeric',
                  })}
                </span>
              </div>
            )}

            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" size="sm" asChild>
                <Link to={`/creator/campaigns/${campaign.id}`}>View details</Link>
              </Button>
              {campaign.applicationStatus ? (
                (() => {
                  const badge = getApplicationStatusBadgeProps(campaign.applicationStatus);
                  return (
                    <Badge variant={badge.variant} className={badge.className}>
                      {badge.label}
                    </Badge>
                  );
                })()
              ) : (
                <Button size="sm" asChild>
                  <Link to={`/creator/campaigns/${campaign.id}?apply=1`}>
                    Apply now
                    <ArrowRight className="ml-1.5 h-4 w-4" />
                  </Link>
                </Button>
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export default CreatorBrowseCampaignCard;
