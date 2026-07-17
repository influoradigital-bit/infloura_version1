import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, Megaphone, UserRoundSearch, Zap } from 'lucide-react';

import { cn } from '@/lib/utils';
import type { CampaignType } from '@/lib/types';
import { CampaignForm } from '@/components/brand/campaigns/campaign-form';
import { BrandKycPrompt } from '@/components/brand/campaigns/brand-kyc-prompt';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';

/**
 * Step 0 of campaign creation: pick the campaign type.
 * Open/Direct continue into the standard wizard; Hype has its own
 * dedicated flow (flat rate × slots, 72-hr window).
 */

interface TypeOption {
  type: CampaignType;
  title: string;
  description: string;
  icon: typeof Megaphone;
  hype?: boolean;
}

const TYPE_OPTIONS: TypeOption[] = [
  {
    type: 'OPEN',
    title: 'Open Campaign',
    description: 'Post a brief publicly — creators apply and you shortlist the best fits.',
    icon: Megaphone,
  },
  {
    type: 'DIRECT',
    title: 'Direct Deal',
    description: 'Invite specific creators and negotiate terms one-on-one in the Deal Room.',
    icon: UserRoundSearch,
  },
  {
    type: 'HYPE',
    title: 'Hype Campaign',
    description: 'A 72-hour blitz: many creators remix one reel at a flat per-reel rate.',
    icon: Zap,
    hype: true,
  },
];

export default function BrandNewCampaignPage() {
  const navigate = useNavigate();
  const [selectedType, setSelectedType] = React.useState<CampaignType | null>(null);

  const choose = (type: CampaignType) => {
    if (type === 'HYPE') {
      navigate('/brand/campaigns/new/hype');
      return;
    }
    setSelectedType(type);
  };

  if (selectedType) {
    return <CampaignForm />;
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">New campaign</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          How do you want to work with creators?
        </p>
      </div>

      {/* B-5: optional, dismissible KYC prompt (never blocks campaign creation). */}
      <BrandKycPrompt />

      <div className="grid gap-4 sm:grid-cols-3">
        {TYPE_OPTIONS.map((option) => {
          const Icon = option.icon;
          return (
            <Card
              key={option.type}
              role="button"
              tabIndex={0}
              onClick={() => choose(option.type)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  choose(option.type);
                }
              }}
              className={cn(
                'group cursor-pointer transition-all hover:-translate-y-0.5 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:hover:translate-y-0',
                option.hype && 'border-hype-border hover:hype-glow',
              )}
            >
              <CardContent className="flex h-full flex-col gap-3 p-5">
                <div
                  className={cn(
                    'flex h-10 w-10 items-center justify-center rounded-lg',
                    option.hype ? 'bg-hype text-hype-foreground' : 'bg-accent text-accent-foreground',
                  )}
                >
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <h2 className="font-semibold">{option.title}</h2>
                    {option.hype && (
                      <Badge className="gap-1 border-hype-border bg-hype text-[10px] text-hype-foreground hover:bg-hype">
                        <Zap className="h-2.5 w-2.5" aria-hidden="true" /> New
                      </Badge>
                    )}
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">{option.description}</p>
                </div>
                <div className="flex items-center justify-between">
                  {option.hype ? (
                    <HypeLiveIndicator hoursLeft={72} />
                  ) : (
                    <span />
                  )}
                  <ArrowRight
                    className="h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0"
                    aria-hidden="true"
                  />
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
