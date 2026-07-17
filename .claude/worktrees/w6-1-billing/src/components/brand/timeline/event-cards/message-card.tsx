'use client';

import * as React from 'react';
import { formatDistanceToNow } from 'date-fns';
import { TimelineEvent } from '@/lib/types';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';
import { Download, FileIcon } from 'lucide-react';

export function MessageEventCard({
  event,
  currentUserType,
}: {
  event: TimelineEvent;
  currentUserType: 'brand' | 'creator';
}) {
  const isFromCurrentUser =
    (currentUserType === 'brand' && event.senderType === 'brand') ||
    (currentUserType === 'creator' && event.senderType === 'creator');

  return (
    <div className={cn('flex gap-3', isFromCurrentUser && 'flex-row-reverse')}>
      {/* Avatar */}
      <Avatar className="h-8 w-8 shrink-0">
        <AvatarFallback className="text-xs">
          {event.senderName?.charAt(0).toUpperCase() || 'U'}
        </AvatarFallback>
      </Avatar>

      {/* Message Bubble */}
      <div className={cn('flex flex-col gap-1', isFromCurrentUser && 'items-end')}>
        <div className="flex gap-2 items-baseline">
          <p className="text-xs font-medium text-muted-foreground">{event.senderName}</p>
          <p className="text-xs text-muted-foreground/70">
            {formatDistanceToNow(new Date(event.timestamp), { addSuffix: true })}
          </p>
        </div>

        {/* Message bubble with text */}
        <div
          className={cn(
            'rounded-lg px-3 py-2 max-w-xs break-words',
            isFromCurrentUser
              ? 'bg-primary text-primary-foreground'
              : 'bg-muted text-foreground'
          )}
        >
          <p className="text-sm">{event.content}</p>
        </div>

        {/* Attachments */}
        {event.attachments && event.attachments.length > 0 && (
          <div className="flex flex-col gap-2 mt-2">
            {event.attachments.map((att) => (
              <div
                key={att.name}
                className={cn(
                  'flex items-center gap-2 p-2 rounded border',
                  isFromCurrentUser ? 'border-primary/30 bg-primary/5' : 'border-border bg-muted/50'
                )}
              >
                <FileIcon className="h-4 w-4 text-muted-foreground" />
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium truncate">{att.name}</p>
                  <p className="text-xs text-muted-foreground">{(att.size / 1024 / 1024).toFixed(1)} MB</p>
                </div>
                <Download className="h-4 w-4 text-primary cursor-pointer hover:opacity-70" />
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
