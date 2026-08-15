'use client';

import * as React from 'react';
import { formatDistanceToNow, format } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { IndianRupee, CheckCircle2, X } from 'lucide-react';
import { deliverableCountLabel, deliverableSlotsLabel } from '@/lib/deliverable-slots';

export function ProposalEventCard({
  event,
  currentUserType,
}: {
  event: TimelineEvent;
  currentUserType: 'brand' | 'creator';
}) {
  const meta = event.metadata;
  const isAccepted = meta?.status === 'accepted';
  const isRejected = meta?.status === 'rejected';
  const slotsLabel = deliverableSlotsLabel(meta);

  return (
    <Card className="border-primary/20 bg-gradient-to-r from-primary/5 to-transparent">
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-3">
            <Avatar className="h-10 w-10">
              <AvatarFallback className="text-xs">
                {event.senderName?.charAt(0).toUpperCase() || 'U'}
              </AvatarFallback>
            </Avatar>
            <div>
              <p className="font-medium text-sm">{event.senderName} sent a proposal</p>
              <p className="text-xs text-muted-foreground">
                {formatDistanceToNow(new Date(event.timestamp), { addSuffix: true })}
              </p>
            </div>
          </div>
          {isAccepted && (
            <Badge className="bg-success text-white gap-1">
              <CheckCircle2 className="h-3 w-3" />
              Accepted
            </Badge>
          )}
          {isRejected && (
            <Badge variant="destructive" className="gap-1">
              <X className="h-3 w-3" />
              Rejected
            </Badge>
          )}
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {/* Amount */}
        <div className="flex items-center gap-2">
          <IndianRupee className="h-5 w-5 text-primary" />
          <div>
            <p className="text-xs text-muted-foreground">Proposed Amount</p>
            <p className="text-2xl font-bold">₹{(meta?.amount || 0).toLocaleString('en-IN')}</p>
          </div>
        </div>

        {/* Details grid */}
        <div className="grid grid-cols-2 gap-4 pt-2">
          <div>
            <p className="text-xs text-muted-foreground">Deliverables</p>
            {/* `meta.deliverables` is the DeliverableSlot[] the backend persists, not a count —
                rendering it straight into JSX threw React #31 and took the whole route down. */}
            <p className="text-lg font-semibold">{deliverableCountLabel(meta) ?? 'Not specified'}</p>
            {slotsLabel && <p className="text-xs text-muted-foreground mt-0.5">{slotsLabel}</p>}
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Deadline</p>
            <p className="text-lg font-semibold">
              {meta?.deadline ? format(new Date(meta.deadline), 'MMM dd') : 'TBD'}
            </p>
          </div>
        </div>

        {/* Action Buttons */}
        {!isAccepted && !isRejected && currentUserType === 'creator' && (
          <div className="flex gap-2 pt-4 border-t border-border">
            <Button size="sm" className="flex-1">
              Accept
            </Button>
            <Button size="sm" variant="outline" className="flex-1">
              Counter
            </Button>
            <Button size="sm" variant="ghost" className="flex-1">
              Reject
            </Button>
          </div>
        )}

        {isAccepted && (
          <div className="pt-2 text-sm text-success font-medium">✓ Both parties agreed to these terms</div>
        )}
      </CardContent>
    </Card>
  );
}
