'use client';

import * as React from 'react';
import { formatDistanceToNow } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FileText, CheckCircle2, PenTool } from 'lucide-react';
import { ContractPanel } from '../panels/contract-panel';

export function ContractEventCard({
  event,
}: {
  event: TimelineEvent;
  /** Accepted for API symmetry; ContractPanel derives the viewer role itself. */
  currentUserType?: 'brand' | 'creator';
}) {
  const meta = event.metadata;
  const status = meta?.contractStatus || 'generated';
  const [panelOpen, setPanelOpen] = React.useState(false);

  const statusConfig: Record<
    string,
    { label: string; color: string; icon: React.ReactNode; description: string }
  > = {
    generated: {
      label: 'Generated',
      color: 'bg-stage-outreach text-blue-900',
      icon: <FileText className="h-4 w-4" />,
      description: 'Contract created and waiting for signatures',
    },
    brand_signed: {
      label: 'Brand Signed',
      color: 'bg-yellow-100 text-yellow-900',
      icon: <CheckCircle2 className="h-4 w-4" />,
      description: 'Awaiting creator signature',
    },
    creator_signed: {
      label: 'Creator Signed',
      color: 'bg-yellow-100 text-yellow-900',
      icon: <CheckCircle2 className="h-4 w-4" />,
      description: 'Awaiting brand signature',
    },
    active: {
      label: 'Active',
      color: 'bg-success/20 text-success',
      icon: <CheckCircle2 className="h-4 w-4" />,
      description: 'Contract is active and both parties are bound',
    },
  };

  const config = statusConfig[status] || statusConfig.generated;

  return (
    <Card className="border-primary/20">
      <CardContent className="pt-6">
        <div className="flex items-start justify-between gap-4">
          <div className="flex gap-3 flex-1">
            {/* Status Icon */}
            <div className={`rounded-full p-2 ${config.color}`}>{config.icon}</div>

            {/* Content */}
            <div className="flex-1">
              <p className="font-medium text-sm">Contract {config.label}</p>
              <p className="text-xs text-muted-foreground mt-1">{config.description}</p>
              <p className="text-xs text-muted-foreground mt-2">
                {formatDistanceToNow(new Date(event.timestamp), { addSuffix: true })}
              </p>
            </div>
          </div>

          {/* Status Badge */}
          <Badge className={config.color}>{config.label}</Badge>
        </div>

        {/* Contract Info */}
        {meta?.contractId && (
          <div className="mt-4 pt-4 border-t border-border space-y-2">
            <p className="text-xs text-muted-foreground">Contract ID: {meta.contractId}</p>

            {/* Signature Status */}
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="flex items-center gap-2">
                <div
                  className={`w-2 h-2 rounded-full ${
                    ['brand_signed', 'active'].includes(status) ? 'bg-success' : 'bg-muted'
                  }`}
                />
                <span>Brand</span>
              </div>
              <div className="flex items-center gap-2">
                <div
                  className={`w-2 h-2 rounded-full ${
                    ['creator_signed', 'active'].includes(status) ? 'bg-success' : 'bg-muted'
                  }`}
                />
                <span>Creator</span>
              </div>
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 mt-4">
          <Button size="sm" variant="outline" className="flex-1" onClick={() => setPanelOpen(true)}>
            View Contract
          </Button>
          {status === 'generated' && (
            <Button size="sm" className="flex-1 gap-1">
              <PenTool className="h-3 w-3" />
              Sign
            </Button>
          )}
        </div>

        {/* Contract Panel */}
        <ContractPanel open={panelOpen} onOpenChange={setPanelOpen} event={event} />
      </CardContent>
    </Card>
  );
}
