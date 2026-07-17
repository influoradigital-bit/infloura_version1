'use client';

import * as React from 'react';
import { formatDistanceToNow } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { IndianRupee, Lock, CheckCircle2 } from 'lucide-react';

export function PaymentEventCard({
  event,
}: {
  event: TimelineEvent;
}) {
  const meta = event.metadata;
  const paymentType = meta?.paymentType || 'escrow_locked';

  const paymentConfig: Record<
    string,
    { label: string; color: string; icon: React.ReactNode; description: string }
  > = {
    escrow_locked: {
      label: 'Escrow Locked',
      color: 'bg-yellow-100 text-yellow-900',
      icon: <Lock className="h-4 w-4" />,
      description: 'Funds locked in escrow pending content approval',
    },
    milestone_released: {
      label: 'Milestone Released',
      color: 'bg-stage-outreach text-blue-900',
      icon: <CheckCircle2 className="h-4 w-4" />,
      description: 'Payment released to creator',
    },
    final_payout: {
      label: 'Final Payout',
      color: 'bg-success/20 text-success',
      icon: <CheckCircle2 className="h-4 w-4" />,
      description: 'Complete payment delivered',
    },
  };

  const config = paymentConfig[paymentType] || paymentConfig.escrow_locked;

  return (
    <Card className="border-primary/20">
      <CardContent className="pt-6">
        <div className="flex items-start justify-between gap-4">
          <div className="flex gap-3 flex-1">
            {/* Status Icon */}
            <div className={`rounded-full p-2 ${config.color}`}>{config.icon}</div>

            {/* Content */}
            <div className="flex-1">
              <p className="font-medium text-sm">{config.label}</p>
              <p className="text-xs text-muted-foreground mt-1">{config.description}</p>
              <p className="text-xs text-muted-foreground mt-2">
                {formatDistanceToNow(new Date(event.timestamp), { addSuffix: true })}
              </p>
            </div>
          </div>

          {/* Amount */}
          <div className="text-right">
            <div className="flex items-center gap-1 justify-end">
              <IndianRupee className="h-4 w-4 text-primary" />
              <p className="text-xl font-bold">
                {(meta?.paymentAmount || 0).toLocaleString('en-IN')}
              </p>
            </div>
            <Badge className={config.color}>{config.label}</Badge>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
