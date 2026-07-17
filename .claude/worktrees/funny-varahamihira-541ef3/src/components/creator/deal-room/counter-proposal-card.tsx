'use client';

import * as React from 'react';
import { formatINR } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Check, X, MessageSquare } from 'lucide-react';

interface CounterProposalCardProps {
  amount: number;
  deliverables: Array<{ title: string; quantity: number }>;
  usageRights: string;
  status: 'pending' | 'accepted' | 'declined';
  createdAt: Date;
  message?: string;
  onAccept?: () => void;
  onDecline?: () => void;
  onReply?: () => void;
}

export function CounterProposalCard({
  amount,
  deliverables,
  usageRights,
  status,
  createdAt,
  message,
  onAccept,
  onDecline,
  onReply,
}: CounterProposalCardProps) {
  // Calculate creator's net earnings
  const calculateEarnings = (gross: number) => {
    const platformFee = gross * 0.1;
    const gstOnFee = platformFee * 0.18;
    const tds = gross * 0.1;
    const netEarnings = gross - platformFee - gstOnFee - tds;
    return netEarnings;
  };

  const netEarnings = calculateEarnings(amount);

  const getStatusColor = () => {
    switch (status) {
      case 'accepted':
        return 'bg-green-50 border-green-200';
      case 'declined':
        return 'bg-red-50 border-red-200';
      default:
        return 'bg-amber-50 border-stage-negotiating-border';
    }
  };

  const getStatusBadge = () => {
    switch (status) {
      case 'accepted':
        return <Badge className="bg-stage-approved-fg hover:opacity-90 text-white">Accepted</Badge>;
      case 'declined':
        return <Badge className="bg-red-600 hover:bg-red-700">Declined</Badge>;
      default:
        return <Badge className="bg-amber-600 hover:bg-amber-700">Pending Response</Badge>;
    }
  };

  return (
    <Card className={`${getStatusColor()} border p-4 space-y-4`}>
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h4 className="font-semibold">Your Counter Proposal</h4>
          <p className="text-xs text-muted-foreground mt-1">
            {createdAt.toLocaleDateString()} at {createdAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </p>
        </div>
        {getStatusBadge()}
      </div>

      {/* Amount */}
      <div className="bg-white/50 rounded p-3 space-y-2">
        <div className="flex justify-between items-center">
          <span className="text-muted-foreground">Proposed Amount:</span>
          <span className="text-2xl font-bold text-foreground">{formatINR(amount)}</span>
        </div>
        <div className="flex justify-between items-center pt-2 border-t">
          <span className="text-muted-foreground text-sm">You Receive (after fees):</span>
          <span className="font-semibold text-stage-approved-fg text-lg">{formatINR(netEarnings)}</span>
        </div>
      </div>

      {/* Details */}
      <div className="grid grid-cols-2 gap-3 text-sm">
        <div className="bg-white/50 p-3 rounded">
          <p className="text-muted-foreground text-xs mb-1">Deliverables</p>
          <p className="font-semibold">{deliverables.length} items</p>
          <ul className="text-xs mt-1 space-y-1">
            {deliverables.map((d, i) => (
              <li key={i} className="text-muted-foreground">
                {d.title} (x{d.quantity})
              </li>
            ))}
          </ul>
        </div>
        <div className="bg-white/50 p-3 rounded">
          <p className="text-muted-foreground text-xs mb-1">Usage Rights</p>
          <p className="font-semibold text-xs">{usageRights}</p>
        </div>
      </div>

      {/* Message */}
      {message && (
        <div className="bg-white/50 rounded p-3 border-l-4 border-blue-400">
          <div className="flex items-start gap-2">
            <MessageSquare className="h-4 w-4 text-stage-outreach-fg mt-0.5 flex-shrink-0" />
            <div>
              <p className="text-xs text-muted-foreground mb-1">Your Message:</p>
              <p className="text-sm">{message}</p>
            </div>
          </div>
        </div>
      )}

      {/* Breakdown Details */}
      <details className="bg-white/50 rounded p-3">
        <summary className="text-sm font-semibold text-muted-foreground cursor-pointer hover:text-foreground">
          View Earnings Breakdown
        </summary>
        <div className="mt-3 space-y-1.5 text-xs">
          <div className="flex justify-between">
            <span className="text-muted-foreground">Proposed Amount:</span>
            <span>{formatINR(amount)}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Platform Fee (10%):</span>
            <span className="text-stage-disputed-fg">-{formatINR(amount * 0.1)}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">GST on Fee (18%):</span>
            <span className="text-stage-disputed-fg">-{formatINR(amount * 0.1 * 0.18)}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">TDS (10%):</span>
            <span className="text-stage-disputed-fg">-{formatINR(amount * 0.1)}</span>
          </div>
          <div className="flex justify-between font-semibold border-t pt-1.5">
            <span>You Receive:</span>
            <span className="text-stage-approved-fg">{formatINR(netEarnings)}</span>
          </div>
        </div>
      </details>

      {/* Actions */}
      {status === 'pending' && (onAccept || onDecline || onReply) && (
        <div className="flex gap-2 pt-2">
          {onAccept && (
            <Button
              size="sm"
              variant="default"
              className="flex-1 gap-1.5"
              onClick={onAccept}
            >
              <Check className="h-4 w-4" />
              Accept
            </Button>
          )}
          {onDecline && (
            <Button
              size="sm"
              variant="outline"
              className="flex-1 gap-1.5"
              onClick={onDecline}
            >
              <X className="h-4 w-4" />
              Decline
            </Button>
          )}
          {onReply && (
            <Button
              size="sm"
              variant="outline"
              className="flex-1"
              onClick={onReply}
            >
              Reply
            </Button>
          )}
        </div>
      )}

      {/* Status Message */}
      {status === 'accepted' && (
        <div className="bg-stage-approved/50 text-green-800 text-sm p-3 rounded">
          Brand accepted your counter proposal. Contract is ready to sign.
        </div>
      )}
      {status === 'declined' && (
        <div className="bg-red-100/50 text-red-800 text-sm p-3 rounded">
          Brand declined your counter proposal. You can send another counter offer.
        </div>
      )}
    </Card>
  );
}
