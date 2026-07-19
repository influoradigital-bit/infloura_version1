'use client';

import * as React from 'react';
import { formatDistanceToNow } from 'date-fns';
import { CheckCircle2, X, MessageCircle, FileText, Clock, IndianRupee } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import type { ProposalFormData } from './proposal-form';

interface ProposalEventData extends ProposalFormData {
  senderType: 'brand' | 'creator';
  senderName: string;
  senderAvatar?: string;
  status: 'pending' | 'accepted' | 'declined' | 'countered';
  timestamp: string;
}

interface ProposalCardProps {
  data: ProposalEventData;
  currentUserType: 'brand' | 'creator';
  onAccept?: () => void;
  onDecline?: () => void;
  onCounter?: () => void;
  onMessage?: () => void;
  isFromBrand?: boolean;
}

export function ProposalCard({
  data,
  currentUserType,
  onAccept,
  onDecline,
  onCounter,
  onMessage,
  isFromBrand = true,
}: ProposalCardProps) {
  const isAccepted = data.status === 'accepted';
  const isDeclined = data.status === 'declined';
  const isCountered = data.status === 'countered';

  // Calculate total cost for display
  const totalBudget = data.budget;
  const platformFee = (totalBudget * 10) / 100;
  const gstOnFee = (platformFee * 18) / 100;
  const totalCost = totalBudget + platformFee + gstOnFee;

  // Deliverables summary
  const totalDeliverables = data.deliverables.reduce((sum, d) => sum + d.count, 0);

  const canRespond = !isAccepted && !isDeclined && currentUserType !== 'brand' && data.senderType === 'brand';

  return (
    <Card
      className={cn(
        'border-primary/20 bg-gradient-to-r from-primary/5 to-transparent mb-4',
        isDeclined && 'opacity-60 border-destructive-foreground/20 from-destructive/5'
      )}
    >
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-3">
            <Avatar className="h-10 w-10">
              {data.senderAvatar ? (
                <AvatarImage src={data.senderAvatar} />
              ) : (
                <AvatarFallback className="text-xs bg-primary/10 text-primary">
                  {data.senderName.charAt(0).toUpperCase()}
                </AvatarFallback>
              )}
            </Avatar>
            <div>
              <p className="font-medium text-sm">
                {data.senderName}
                {isFromBrand ? ' sent a proposal' : ' sent a counter proposal'}
              </p>
              <p className="text-xs text-muted-foreground">
                {formatDistanceToNow(new Date(data.timestamp), { addSuffix: true })}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {isAccepted && (
              <Badge className="bg-success text-white gap-1">
                <CheckCircle2 className="h-3 w-3" />
                Accepted
              </Badge>
            )}
            {isDeclined && (
              <Badge variant="destructive" className="gap-1">
                <X className="h-3 w-3" />
                Declined
              </Badge>
            )}
            {isCountered && (
              <Badge variant="outline" className="gap-1 border-warning text-warning">
                <MessageCircle className="h-3 w-3" />
                Countered
              </Badge>
            )}
            {!isAccepted && !isDeclined && !isCountered && currentUserType !== data.senderType && (
              <Badge variant="outline" className="gap-1 border-info text-info">
                Pending response
              </Badge>
            )}
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {/* Amount - Prominent */}
        <div className="flex items-baseline gap-2 p-3 rounded-lg bg-primary/5 border border-primary/10">
          <IndianRupee className="h-5 w-5 text-primary shrink-0" />
          <div>
            <p className="text-xs text-muted-foreground">Proposed Amount</p>
            <p className="text-2xl font-bold text-primary">₹{totalBudget.toLocaleString('en-IN')}</p>
          </div>
          <div className="ml-auto text-right">
            <p className="text-xs text-muted-foreground">You Pay Total</p>
            <p className="text-lg font-semibold">₹{Math.round(totalCost).toLocaleString('en-IN')}</p>
          </div>
        </div>

        {/* Deliverables Summary Grid */}
        <div className="grid grid-cols-3 gap-3">
          <div className="p-2 rounded-lg bg-muted/50 text-center">
            <p className="text-xs text-muted-foreground">Deliverables</p>
            <p className="text-lg font-semibold">{totalDeliverables}</p>
          </div>
          <div className="p-2 rounded-lg bg-muted/50 text-center">
            <p className="text-xs text-muted-foreground">Revisions</p>
            <p className="text-lg font-semibold">{data.revisionCap}</p>
          </div>
          <div className="p-2 rounded-lg bg-muted/50 text-center">
            <p className="text-xs text-muted-foreground">Duration</p>
            <p className="text-lg font-semibold whitespace-nowrap text-sm">
              {data.usageRightsDuration === '6-months' ? '6 mo' : data.usageRightsDuration === '3-months' ? '3 mo' : data.usageRightsDuration === '12-months' ? '12 mo' : 'Forever'}
            </p>
          </div>
        </div>

        {/* Deliverables Breakdown */}
        <div>
          <p className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">Deliverables</p>
          <div className="space-y-1">
            {data.deliverables.map((del, idx) => (
              <div key={idx} className="flex items-center justify-between text-sm p-1.5 rounded bg-muted/30">
                <span className="text-foreground">{del.type}</span>
                <Badge variant="outline" className="ml-2">
                  ×{del.count}
                </Badge>
              </div>
            ))}
          </div>
        </div>

        {/* Usage Rights & Add-ons */}
        <div>
          <p className="text-xs font-semibold text-muted-foreground mb-2 uppercase tracking-wide">Terms</p>
          <div className="space-y-1.5 text-sm">
            <div className="flex items-center gap-2">
              <Clock className="h-4 w-4 text-muted-foreground shrink-0" />
              <span>Deadline: {data.deadline || 'Not specified'}</span>
            </div>
            {data.usageRightsAddOns.length > 0 && (
              <div className="flex flex-wrap gap-1 pt-1">
                {data.usageRightsAddOns.map((addonId) => (
                  <Badge key={addonId} variant="secondary" className="text-xs">
                    {addonId === 'whitelisting' && 'Whitelisting'}
                    {addonId === 'extended' && 'Extended'}
                    {addonId === 'perpetual' && 'Perpetual'}
                    {addonId === 'repurposing' && 'Repurposing'}
                    {addonId === 'exclusive' && 'Exclusive'}
                  </Badge>
                ))}
              </div>
            )}
            {data.exclusivity !== 'none' && (
              <div className="text-xs text-muted-foreground">
                Exclusivity: {data.exclusivity === '3-months' ? '3 months' : data.exclusivity === '6-months' ? '6 months' : 'Perpetual'}
              </div>
            )}
          </div>
        </div>

        {/* Custom Clauses */}
        {data.customClauses && (
          <div className="p-3 rounded-lg border border-border/50 bg-muted/20">
            <p className="text-xs font-semibold text-muted-foreground mb-1">Custom Terms</p>
            <p className="text-sm leading-relaxed text-foreground">{data.customClauses}</p>
          </div>
        )}

        {/* Action Buttons */}
        {canRespond && (
          <div className="flex gap-2 pt-4 border-t border-border flex-wrap">
            <Button size="sm" onClick={onAccept} className="flex-1 min-w-fit">
              <CheckCircle2 className="h-3.5 w-3.5 mr-1.5" />
              Accept
            </Button>
            <Button size="sm" variant="outline" onClick={onCounter} className="flex-1 min-w-fit">
              <MessageCircle className="h-3.5 w-3.5 mr-1.5" />
              Counter
            </Button>
            <Button size="sm" variant="ghost" onClick={onDecline} className="flex-1 min-w-fit">
              <X className="h-3.5 w-3.5 mr-1.5" />
              Decline
            </Button>
          </div>
        )}

        {isAccepted && (
          <div className="pt-2 text-sm text-success font-medium flex items-center gap-1.5">
            <CheckCircle2 className="h-4 w-4" />
            Both parties agreed to these terms
          </div>
        )}

        {isDeclined && (
          <div className="pt-2 text-sm text-destructive-foreground font-medium">
            This proposal was declined
          </div>
        )}

        {isCountered && (
          <div className="pt-2 text-sm text-warning font-medium flex items-center gap-1.5">
            <MessageCircle className="h-4 w-4" />
            A counter proposal was sent
          </div>
        )}
      </CardContent>
    </Card>
  );
}
