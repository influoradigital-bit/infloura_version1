'use client';

import * as React from 'react';
import {
  Video,
  Image as ImageIcon,
  File,
  Check,
  Clock,
  AlertCircle,
  MessageSquare,
  RotateCcw,
  Download,
  Eye,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { formatINR } from '@/lib/utils';

interface DeliverableCardProps {
  id: string;
  title: string;
  status: 'submitted' | 'approved' | 'revision_requested' | 'payment_released';
  caption: string;
  submittedAt: Date;
  thumbnailUrl?: string;
  fileType: 'video' | 'image' | 'document';
  revision?: {
    current: number;
    max: number;
  };
  brandFeedback?: string;
  paymentAmount?: number;
  onRevise?: () => void;
  onDownload?: () => void;
  onViewDetails?: () => void;
}

export function DeliverableCard({
  title,
  status,
  caption,
  submittedAt,
  thumbnailUrl,
  fileType,
  revision,
  brandFeedback,
  paymentAmount,
  onRevise,
  onDownload,
  onViewDetails,
}: DeliverableCardProps) {
  const statusConfig = {
    submitted: {
      label: 'Pending Review',
      color: 'bg-amber-50 border-stage-negotiating-border',
      badge: 'bg-amber-100 text-amber-800',
      icon: Clock,
    },
    approved: {
      label: 'Approved',
      color: 'bg-green-50 border-green-200',
      badge: 'bg-stage-approved text-green-800',
      icon: Check,
    },
    revision_requested: {
      label: 'Revision Requested',
      color: 'bg-orange-50 border-orange-200',
      badge: 'bg-orange-100 text-orange-800',
      icon: AlertCircle,
    },
    payment_released: {
      label: 'Payment Released',
      color: 'bg-green-50 border-green-200',
      badge: 'bg-stage-approved text-green-800',
      icon: Check,
    },
  };

  const config = statusConfig[status];
  const StatusIcon = config.icon;

  const getThumbnailPlaceholder = () => {
    switch (fileType) {
      case 'video':
        return <Video className="h-12 w-12 text-muted-foreground" />;
      case 'image':
        return <ImageIcon className="h-12 w-12 text-muted-foreground" />;
      case 'document':
        return <File className="h-12 w-12 text-muted-foreground" />;
    }
  };

  return (
    <div className="flex justify-center">
      <Card className={`w-full max-w-md border-2 ${config.color}`}>
        <CardContent className="p-4">
          {/* Header */}
          <div className="flex items-start justify-between mb-3">
            <div className="flex-1">
              <h3 className="font-semibold text-sm">{title}</h3>
              <p className="text-xs text-muted-foreground mt-1">
                Submitted {submittedAt.toLocaleDateString()} at{' '}
                {submittedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </p>
            </div>
            <Badge className={config.badge}>
              <StatusIcon className="h-3 w-3 mr-1" />
              {config.label}
            </Badge>
          </div>

          {/* Thumbnail/Preview */}
          {thumbnailUrl ? (
            <div className="mb-3 rounded-lg overflow-hidden bg-muted">
              <img src={thumbnailUrl} alt={title} className="w-full h-40 object-cover" />
            </div>
          ) : (
            <div className="mb-3 rounded-lg bg-muted flex items-center justify-center h-40">
              {getThumbnailPlaceholder()}
            </div>
          )}

          {/* Caption */}
          <p className="text-sm text-foreground mb-3 line-clamp-3">{caption}</p>

          {/* Revision Counter */}
          {revision && (
            <div className="mb-3 flex items-center justify-between text-xs">
              <span className="text-muted-foreground">
                Revision {revision.current}/{revision.max}
              </span>
              <div className="flex gap-1">
                {Array.from({ length: revision.max }).map((_, i) => (
                  <div
                    key={i}
                    className={`h-1.5 w-1.5 rounded-full ${
                      i < revision.current ? 'bg-green-600' : 'bg-muted-foreground/30'
                    }`}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Brand Feedback */}
          {brandFeedback && (
            <div className="mb-3 p-2 bg-white/50 rounded border border-current/10">
              <div className="flex gap-2 mb-1">
                <MessageSquare className="h-3.5 w-3.5 text-muted-foreground flex-shrink-0 mt-0.5" />
                <p className="text-xs font-medium">Brand Feedback:</p>
              </div>
              <p className="text-xs text-muted-foreground ml-5 italic">{brandFeedback}</p>
            </div>
          )}

          {/* Payment Released */}
          {status === 'payment_released' && paymentAmount && (
            <div className="mb-3 p-2 bg-stage-approved/50 rounded border border-green-200">
              <p className="text-xs font-semibold text-stage-approved-fg">
                Payment Released: {formatINR(paymentAmount)}
              </p>
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-2">
            {status === 'revision_requested' && onRevise && (
              <Button size="sm" variant="outline" className="flex-1 gap-1.5" onClick={onRevise}>
                <RotateCcw className="h-3.5 w-3.5" />
                Revise
              </Button>
            )}
            {onDownload && (
              <Button size="sm" variant="outline" className="flex-1 gap-1.5" onClick={onDownload}>
                <Download className="h-3.5 w-3.5" />
                Download
              </Button>
            )}
            {onViewDetails && (
              <Button size="sm" variant="outline" className="flex-1 gap-1.5" onClick={onViewDetails}>
                <Eye className="h-3.5 w-3.5" />
                Details
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
