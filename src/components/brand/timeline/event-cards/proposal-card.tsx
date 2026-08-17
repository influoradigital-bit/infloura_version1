'use client';

import * as React from 'react';
import { formatDistanceToNow, format } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { IndianRupee, CheckCircle2, X, Loader2, AlertCircle, ArrowRight } from 'lucide-react';
import { deliverableCountLabel, deliverableSlotsLabel } from '@/lib/deliverable-slots';
import { deals as dealsApi, ApiError } from '@/lib/api';
import { toDealRoomId } from '@/lib/creator-deal-messages';

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

  // F-0289: real deal id these actions post to. `event.collaborationId` carries the raw
  // collaboration/deal id (see collaboration-timeline.tsx's liveEvents mapping); normalize it
  // through the same helper the parent uses for its own GET/POST /deals/:dealId/* calls
  // (toDealRoomId strips the mock-only 'collab-' prefix, a no-op on real ids).
  const dealId = React.useMemo(() => toDealRoomId(event.collaborationId), [event.collaborationId]);

  const [actionLoading, setActionLoading] = React.useState<'accept' | 'reject' | 'counter' | null>(null);
  const [actionError, setActionError] = React.useState<string | null>(null);
  // F-0289: the parent's own message stream (CollaborationTimeline) re-publishes the settled
  // proposal message under its ORIGINAL id with mutated metadata once the backend processes
  // accept/reject/counter — see the same pattern documented in deal-room-dashboard.tsx. This
  // component has no refetch of its own, so `actionDone` is only an honest "request succeeded"
  // acknowledgement, never a fabricated status flip of `meta` itself.
  const [actionDone, setActionDone] = React.useState<'accepted' | 'rejected' | 'countered' | null>(null);
  const [showCounterForm, setShowCounterForm] = React.useState(false);
  const [counterAmount, setCounterAmount] = React.useState(() => (meta?.amount ? String(meta.amount) : ''));
  const [counterMessage, setCounterMessage] = React.useState('');

  const handleAccept = async () => {
    setActionLoading('accept');
    setActionError(null);
    try {
      await dealsApi.accept(dealId, currentUserType);
      setActionDone('accepted');
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Could not accept the proposal. Try again.');
    } finally {
      setActionLoading(null);
    }
  };

  const handleReject = async () => {
    setActionLoading('reject');
    setActionError(null);
    try {
      await dealsApi.reject(dealId, undefined, currentUserType);
      setActionDone('rejected');
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Could not reject the proposal. Try again.');
    } finally {
      setActionLoading(null);
    }
  };

  const handleSendCounter = async () => {
    const amount = Number(counterAmount);
    if (!amount || amount <= 0) {
      setActionError('Enter a valid counter amount.');
      return;
    }
    setActionLoading('counter');
    setActionError(null);
    try {
      await dealsApi.counter(
        dealId,
        { amount, message: counterMessage.trim() || undefined },
        currentUserType,
      );
      setActionDone('countered');
      setShowCounterForm(false);
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Could not send the counter offer. Try again.');
    } finally {
      setActionLoading(null);
    }
  };

  const settled = isAccepted || isRejected || actionDone !== null;

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

        {actionError && (
          <div className="flex items-center gap-1.5 text-xs text-destructive-foreground">
            <AlertCircle className="h-3.5 w-3.5 shrink-0" />
            {actionError}
          </div>
        )}

        {/* Counter offer — a real POST /deals/:id/counter needs an amount, so this inline form
            collects one instead of a placeholder Counter button (F-0289). Mirrors the amount +
            optional message shape of the brand's own Counter Offer dialog
            (deal-room-dashboard.tsx). */}
        {showCounterForm && !settled && (
          <div className="space-y-2 rounded-md border border-border p-3">
            <label
              className="text-xs font-medium text-muted-foreground"
              htmlFor={`counter-amount-${event.id}`}
            >
              Your counter amount
            </label>
            <div className="relative">
              <IndianRupee className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                id={`counter-amount-${event.id}`}
                type="number"
                className="pl-8"
                placeholder="45000"
                value={counterAmount}
                onChange={(e) => setCounterAmount(e.target.value)}
                disabled={actionLoading === 'counter'}
              />
            </div>
            <Textarea
              placeholder="Message (optional)"
              rows={2}
              className="resize-none"
              value={counterMessage}
              onChange={(e) => setCounterMessage(e.target.value)}
              disabled={actionLoading === 'counter'}
            />
            <div className="flex gap-2">
              <Button
                size="sm"
                className="flex-1"
                onClick={handleSendCounter}
                disabled={actionLoading === 'counter'}
              >
                {actionLoading === 'counter' ? (
                  <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />
                ) : (
                  <ArrowRight className="h-3.5 w-3.5 mr-1" />
                )}
                Send Counter
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  setShowCounterForm(false);
                  setActionError(null);
                }}
                disabled={actionLoading === 'counter'}
              >
                Cancel
              </Button>
            </div>
          </div>
        )}

        {/* Action Buttons */}
        {!settled && !showCounterForm && currentUserType === 'creator' && (
          <div className="flex gap-2 pt-4 border-t border-border">
            <Button size="sm" className="flex-1" onClick={handleAccept} disabled={actionLoading !== null}>
              {actionLoading === 'accept' && <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />}
              Accept
            </Button>
            <Button
              size="sm"
              variant="outline"
              className="flex-1"
              onClick={() => setShowCounterForm(true)}
              disabled={actionLoading !== null}
            >
              Counter
            </Button>
            <Button
              size="sm"
              variant="ghost"
              className="flex-1"
              onClick={handleReject}
              disabled={actionLoading !== null}
            >
              {actionLoading === 'reject' && <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />}
              Reject
            </Button>
          </div>
        )}

        {isAccepted && (
          <div className="pt-2 text-sm text-success font-medium">✓ Both parties agreed to these terms</div>
        )}
        {!isAccepted && actionDone === 'accepted' && (
          <div className="pt-2 text-sm text-success font-medium">
            ✓ Accepted — the timeline will update shortly.
          </div>
        )}
        {!isRejected && actionDone === 'rejected' && (
          <div className="pt-2 text-sm text-muted-foreground font-medium">Proposal rejected.</div>
        )}
        {actionDone === 'countered' && (
          <div className="pt-2 text-sm text-muted-foreground font-medium">Counter offer sent.</div>
        )}
      </CardContent>
    </Card>
  );
}
