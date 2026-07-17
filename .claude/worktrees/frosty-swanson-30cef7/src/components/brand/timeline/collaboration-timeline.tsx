'use client';

import * as React from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Collaboration, TimelineEvent } from '@/lib/types';
import { TimelineEventCard } from './timeline-event';
import {
  addPersistedMessage,
  getPersistedMessages,
  toDealRoomId,
} from '@/lib/creator-deal-messages';
import {
  MessageCircle,
  Handshake,
  FileText,
  Package,
  DollarSign,
  Bell,
  Send,
} from 'lucide-react';

function buildDefaultEvents(
  collaboration: Collaboration,
  peerName?: string,
): TimelineEvent[] {
  const brandLabel = peerName ?? 'Brand';
  return [
    {
      id: 'evt-1',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24 * 2),
      senderId: 'brand',
      senderType: 'brand',
      senderName: brandLabel,
      tag: 'system',
      content: 'Campaign invite sent',
      metadata: { message: 'Campaign invite sent', severity: 'info' },
      status: 'read',
    },
    {
      id: 'evt-2',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24 * 2 + 1000 * 60 * 30),
      senderId: collaboration.creatorId,
      senderType: 'creator',
      senderName: collaboration.creatorName || 'Creator',
      tag: 'message',
      content: 'Hi! I am very interested in this collaboration. Can you tell me more?',
      status: 'read',
    },
    {
      id: 'evt-3',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24 + 1000 * 60 * 60 * 2),
      senderId: 'brand',
      senderType: 'brand',
      senderName: brandLabel,
      tag: 'proposal',
      metadata: {
        proposalId: 'prop-1',
        amount: 50000,
        deliverables: 3,
        deadline: '2024-02-15',
        status: 'accepted',
      },
      status: 'read',
    },
    {
      id: 'evt-4',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24 + 1000 * 60 * 60 * 4),
      senderId: collaboration.creatorId,
      senderType: 'creator',
      senderName: collaboration.creatorName || 'Creator',
      tag: 'message',
      content: 'This looks perfect! I accept the proposal.',
      status: 'read',
    },
    {
      id: 'evt-5',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 20),
      senderId: 'system',
      senderType: 'system',
      tag: 'contract',
      metadata: {
        contractId: 'cont-1',
        contractStatus: 'brand_signed',
      },
      status: 'read',
    },
    {
      id: 'evt-6',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 6),
      senderId: collaboration.creatorId,
      senderType: 'creator',
      senderName: collaboration.creatorName || 'Creator',
      tag: 'message',
      content: 'I have started working on the first reel. Will send it soon!',
      status: 'delivered',
    },
  ];
}

function persistedToTimelineEvent(
  collaborationId: string,
  msg: ReturnType<typeof getPersistedMessages>[number],
  creatorName: string,
  brandName: string,
): TimelineEvent {
  return {
    id: msg.id,
    collaborationId,
    timestamp: new Date(msg.timestamp),
    senderId: msg.sender,
    senderType: msg.sender,
    senderName: msg.sender === 'creator' ? creatorName : brandName,
    tag: 'message',
    content: msg.content,
    status: 'sent',
  };
}

export function CollaborationTimeline({
  collaboration,
  currentUserType,
  peerName,
  persistMessages = true,
}: {
  collaboration: Collaboration;
  currentUserType: 'brand' | 'creator';
  /** Brand name when viewer is creator (and vice versa for future brand use). */
  peerName?: string;
  persistMessages?: boolean;
}) {
  const brandLabel = peerName ?? 'Brand';
  const creatorName = collaboration.creatorName || 'You';

  const [selectedTag, setSelectedTag] = React.useState<string>('all');
  const [draft, setDraft] = React.useState('');
  const [isSending, setIsSending] = React.useState(false);
  const [refreshKey, setRefreshKey] = React.useState(0);
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  const baseEvents = React.useMemo(
    () => buildDefaultEvents(collaboration, brandLabel),
    [collaboration, brandLabel],
  );

  const events = React.useMemo(() => {
    const roomId = toDealRoomId(collaboration.id);
    const persisted = persistMessages
      ? getPersistedMessages(roomId).map((m) =>
          persistedToTimelineEvent(roomId, m, creatorName, brandLabel),
        )
      : [];
    const merged = [...baseEvents, ...persisted];
    const seen = new Set<string>();
    return merged.filter((e) => {
      if (seen.has(e.id)) return false;
      seen.add(e.id);
      return true;
    });
  }, [baseEvents, collaboration.id, creatorName, brandLabel, persistMessages, refreshKey]);

  const filteredEvents = React.useMemo(() => {
    if (selectedTag === 'all') return events;
    return events.filter((e) => e.tag === selectedTag);
  }, [events, selectedTag]);

  const sortedEvents = React.useMemo(
    () => [...filteredEvents].sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime()),
    [filteredEvents],
  );

  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [sortedEvents.length, refreshKey]);

  const tagCounts = React.useMemo(() => {
    const counts: Record<string, number> = { all: events.length };
    events.forEach((e) => {
      counts[e.tag] = (counts[e.tag] || 0) + 1;
    });
    return counts;
  }, [events]);

  const handleSend = async () => {
    const text = draft.trim();
    if (!text || isSending) return;

    setIsSending(true);
    if (persistMessages) {
      addPersistedMessage(
        toDealRoomId(collaboration.id),
        text,
        currentUserType === 'creator' ? 'creator' : 'brand',
      );
    }
    setDraft('');
    setRefreshKey((k) => k + 1);
    await new Promise((r) => setTimeout(r, 200));
    setIsSending(false);
  };

  const tags = [
    { id: 'all', label: 'All', icon: null },
    { id: 'message', label: 'Messages', icon: MessageCircle },
    { id: 'proposal', label: 'Proposals', icon: Handshake },
    { id: 'contract', label: 'Contracts', icon: FileText },
    { id: 'deliverable', label: 'Deliverables', icon: Package },
    { id: 'payment', label: 'Payments', icon: DollarSign },
    { id: 'system', label: 'Updates', icon: Bell },
  ];

  return (
    <div className="flex flex-col h-full min-h-0 gap-3">
      <div className="flex gap-2 overflow-x-auto pb-2 border-b border-border shrink-0">
        {tags.map((tag) => {
          const Icon = tag.icon;
          const count = tagCounts[tag.id] || 0;
          return (
            <Button
              key={tag.id}
              variant={selectedTag === tag.id ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setSelectedTag(tag.id)}
              className="gap-2 whitespace-nowrap"
            >
              {Icon && <Icon className="h-4 w-4" />}
              {tag.label}
              {count > 0 && (
                <Badge
                  variant={selectedTag === tag.id ? 'secondary' : 'outline'}
                  className="ml-1"
                >
                  {count}
                </Badge>
              )}
            </Button>
          );
        })}
      </div>

      <ScrollArea className="flex-1 min-h-0">
        <div className="pr-4 space-y-4 pb-2">
          {sortedEvents.length === 0 ? (
            <div className="flex items-center justify-center py-8">
              <p className="text-muted-foreground text-sm">No events to show</p>
            </div>
          ) : (
            sortedEvents.map((event) => (
              <TimelineEventCard
                key={event.id}
                event={event}
                currentUserType={currentUserType}
              />
            ))
          )}
          <div ref={messagesEndRef} />
        </div>
      </ScrollArea>

      <div className="shrink-0 flex items-end gap-2 border-t border-border pt-3">
        <Textarea
          placeholder="Type a message..."
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void handleSend();
            }
          }}
          className="min-h-[44px] max-h-28 resize-none"
          rows={1}
          disabled={isSending}
        />
        <Button
          type="button"
          size="icon"
          className="shrink-0 h-10 w-10"
          onClick={() => void handleSend()}
          disabled={!draft.trim() || isSending}
        >
          <Send className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
