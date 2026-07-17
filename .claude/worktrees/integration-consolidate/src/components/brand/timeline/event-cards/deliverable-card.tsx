'use client';

import * as React from 'react';
import { formatDistanceToNow } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Play, MessageSquare, AlertCircle, CheckCircle2, Package } from 'lucide-react';
import { DeliverableReviewPanel, type DeliverableReviewResult } from '../panels/deliverable-review-panel';

export function DeliverableEventCard({
  event,
  currentUserType,
  onReviewSuccess,
}: {
  event: TimelineEvent;
  currentUserType: 'brand' | 'creator';
  onReviewSuccess?: (result: DeliverableReviewResult) => void;
}) {
  const meta = event.metadata;
  const status = meta?.deliverableStatus || 'submitted';
  const [panelOpen, setPanelOpen] = React.useState(false);
  const platformEmojis: Record<string, string> = {
    instagram: '📷',
    youtube: '▶️',
    tiktok: '🎵',
  };

  const statusConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
    submitted: {
      label: 'Submitted',
      color: 'bg-stage-outreach text-blue-900',
      icon: <Package className="h-4 w-4" />,
    },
    under_review: {
      label: 'Under Review',
      color: 'bg-yellow-100 text-yellow-900',
      icon: <MessageSquare className="h-4 w-4" />,
    },
    revision_requested: {
      label: 'Revision Requested',
      color: 'bg-orange-100 text-orange-900',
      icon: <AlertCircle className="h-4 w-4" />,
    },
    approved: {
      label: 'Approved',
      color: 'bg-success/20 text-success',
      icon: <CheckCircle2 className="h-4 w-4" />,
    },
  };

  const config = statusConfig[status] || statusConfig.submitted;

  return (
    <Card className="border-primary/20 overflow-hidden">
      <div className="grid grid-cols-3 gap-0">
        {/* Thumbnail placeholder */}
        <div className="col-span-1 bg-muted aspect-square flex items-center justify-center border-r border-border">
          <Play className="h-8 w-8 text-muted-foreground" />
        </div>

        {/* Content */}
        <CardContent className="col-span-2 py-4">
          <div className="space-y-3">
            {/* Header */}
            <div className="flex items-start justify-between gap-2">
              <div>
                <p className="font-medium text-sm">
                  {platformEmojis[meta?.platform || 'instagram'] || '📹'} Reel #{meta?.deliverableNumber || 1}
                </p>
                <p className="text-xs text-muted-foreground">
                  {meta?.platform ? meta.platform.charAt(0).toUpperCase() + meta.platform.slice(1) : 'Platform'}
                </p>
              </div>
              <Badge className={config.color}>{config.label}</Badge>
            </div>

            {/* Status and Timestamp */}
            <p className="text-xs text-muted-foreground">
              {formatDistanceToNow(new Date(event.timestamp), { addSuffix: true })}
            </p>

            {/* Revision Info */}
            {meta?.revisionCount !== undefined && meta?.revisionLimit !== undefined && (
              <p className="text-xs text-muted-foreground">
                Revisions: {meta.revisionCount}/{meta.revisionLimit} used
              </p>
            )}

            {/* Revision Feedback */}
            {meta?.revisionFeedback && (
              <div className="bg-muted/50 p-2 rounded text-xs">
                <p className="text-muted-foreground font-medium">Feedback:</p>
                <p className="text-muted-foreground mt-1">{meta.revisionFeedback}</p>
              </div>
            )}

            {/* Actions */}
            <div className="flex gap-2 pt-2">
              {currentUserType === 'brand' && (
                status === 'submitted' ||
                (status === 'revision_requested' &&
                  (meta?.revisionCount ?? 0) < (meta?.revisionLimit ?? 2))
              ) ? (
                <>
                  <Button size="sm" variant="outline" className="flex-1" onClick={() => setPanelOpen(true)}>
                    Review
                  </Button>
                </>
              ) : null}
              {status === 'approved' && (
                <Button size="sm" variant="outline" className="w-full" onClick={() => setPanelOpen(true)}>
                  View
                </Button>
              )}
              {currentUserType === 'creator' &&
                status === 'revision_requested' &&
                (meta?.revisionCount ?? 0) < (meta?.revisionLimit ?? 2) && (
                <Button size="sm" className="w-full" onClick={() => setPanelOpen(true)}>
                  Upload Revision
                </Button>
              )}
            </div>

            {/* Deliverable Review Panel */}
            <DeliverableReviewPanel
              open={panelOpen}
              onOpenChange={setPanelOpen}
              event={event}
              currentUserType={currentUserType}
              onReviewSuccess={onReviewSuccess}
            />
          </div>
        </CardContent>
      </div>
    </Card>
  );
}
