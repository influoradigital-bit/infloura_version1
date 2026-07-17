'use client';

import * as React from 'react';
import { formatDistanceToNow } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Info, AlertTriangle, AlertCircle } from 'lucide-react';

export function SystemEventCard({
  event,
}: {
  event: TimelineEvent;
}) {
  const meta = event.metadata;
  const severity = meta?.severity || 'info';

  const severityConfig: Record<string, { color: string; icon: React.ReactNode }> = {
    info: {
      color: 'border-stage-outreach-border bg-blue-50',
      icon: <Info className="h-4 w-4 text-stage-outreach-fg" />,
    },
    warning: {
      color: 'border-yellow-200 bg-yellow-50',
      icon: <AlertTriangle className="h-4 w-4 text-yellow-600" />,
    },
    alert: {
      color: 'border-red-200 bg-red-50',
      icon: <AlertCircle className="h-4 w-4 text-stage-disputed-fg" />,
    },
  };

  const config = severityConfig[severity] || severityConfig.info;

  return (
    <Alert className={config.color}>
      <div className="flex gap-3 items-start">
        {config.icon}
        <div className="flex-1">
          <AlertDescription className="text-sm font-medium">
            {meta?.message || event.content || 'System notification'}
          </AlertDescription>
          <p className="text-xs text-muted-foreground mt-1">
            {formatDistanceToNow(new Date(event.timestamp), { addSuffix: true })}
          </p>
        </div>
      </div>
    </Alert>
  );
}
