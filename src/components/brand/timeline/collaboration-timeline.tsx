'use client';

import * as React from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Collaboration, TimelineEvent, type TimelineEventMetadata } from '@/lib/types';
import { TimelineEventCard } from './timeline-event';
import type { DeliverableReviewResult } from './panels/deliverable-review-panel';
import { api, ApiError, isApiLive, type DealMessage } from '@/lib/api';
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
  Loader2,
  AlertCircle,
  RefreshCw,
  Info,
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
    {
      id: 'evt-del-1',
      collaborationId: collaboration.id,
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 2),
      senderId: collaboration.creatorId,
      senderType: 'creator',
      senderName: collaboration.creatorName || 'Creator',
      tag: 'deliverable',
      metadata: {
        deliverableId: `del_${collaboration.id}_1`,
        deliverableNumber: 1,
        platform: 'instagram',
        submittedFilename: 'summer-reel-v1.mp4',
        deliverableStatus: 'submitted',
        revisionCount: 0,
        revisionLimit: 2,
      },
      status: 'read',
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
  const dealId = React.useMemo(() => toDealRoomId(collaboration.id), [collaboration.id]);

  const [selectedTag, setSelectedTag] = React.useState<string>('all');
  const [draft, setDraft] = React.useState('');
  const [isSending, setIsSending] = React.useState(false);
  const [refreshKey, setRefreshKey] = React.useState(0);
  const [deliverableOverrides, setDeliverableOverrides] = React.useState<
    Record<string, NonNullable<TimelineEvent['metadata']>>
  >({});
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  // Live mode: real messages + proposal/system history, fetched from
  // `GET /deals/:dealId/messages`. That endpoint returns every persisted
  // `DealMessage` row for the deal — chat text, plus real `proposal` and
  // `system` events the backend writes on proposal/counter/accept/reject
  // (see `DealService.persistProposalMessage`/`appendSystemMessage`).
  // Demo mode below is untouched and keeps using `buildDefaultEvents` +
  // localStorage-persisted messages.
  const [liveMessages, setLiveMessages] = React.useState<DealMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = React.useState(false);
  const [messagesError, setMessagesError] = React.useState<string | null>(null);
  const [sendError, setSendError] = React.useState<string | null>(null);

  const loadMessages = React.useCallback(async () => {
    if (!isApiLive()) return;
    setMessagesLoading(true);
    setMessagesError(null);
    try {
      const msgs = await api.messages.list(currentUserType, dealId);
      setLiveMessages(msgs);
      void api.messages.markRead(currentUserType, dealId).catch(() => undefined);
    } catch (e) {
      setLiveMessages([]);
      setMessagesError(
        e instanceof ApiError ? e.message : 'Could not load activity. Try again.',
      );
    } finally {
      setMessagesLoading(false);
    }
  }, [currentUserType, dealId]);

  React.useEffect(() => {
    if (!isApiLive()) return;
    void loadMessages();
  }, [loadMessages]);

  // T-1 (BrandF.md §61): the timeline used to fetch once on mount and never open a
  // realtime connection at all — a creator's reply or a system event (proposal/
  // accept/counter) written by the other party never appeared until the brand
  // reloaded the page. Mirrors brand-chat.tsx's stream effect (same transport,
  // same reconnect-recovery shape), scoped to this one collaboration's dealId.
  React.useEffect(() => {
    if (!isApiLive()) return;

    const handle = api.messages.stream(currentUserType, dealId, {
      onMessage: (incoming) => {
        setLiveMessages((prev) => {
          const idx = prev.findIndex((m) => m.id === incoming.id);
          if (idx === -1) return [...prev, incoming];
          const next = prev.slice();
          next[idx] = incoming;
          return next;
        });
      },
      onError: (err) => {
        // Non-fatal — the initial `loadMessages()` fetch already rendered the thread;
        // the stream only adds realtime updates on top of it.
        console.debug('[collaboration-timeline] message stream error for deal', dealId, err);
      },
      onReconnect: () => {
        // Nothing published during the gap is recoverable from the transport — re-fetch
        // to catch up on whatever the stream missed while it was down.
        void loadMessages();
      },
    });

    return () => {
      handle.close();
    };
  }, [dealId, currentUserType, loadMessages]);

  const baseEvents = React.useMemo(
    () => buildDefaultEvents(collaboration, brandLabel),
    [collaboration, brandLabel],
  );

  // Real backend kinds today are only `text`, `proposal`, and `system` —
  // no `contract`/`deliverable`/`payment`/`shipment` rows are ever written
  // (verified: nothing outside `DealService` touches `dealMessageRepository`).
  // Those tags are mapped for forward-compatibility but will render empty
  // until a backend activity-log for contract/deliverable/payment events
  // exists — see the gap notice rendered below the tag filter bar.
  const liveEvents = React.useMemo<TimelineEvent[]>(() => {
    if (!isApiLive()) return [];
    return liveMessages.map((m): TimelineEvent => {
      const tag: TimelineEvent['tag'] =
        m.kind === 'text'
          ? 'message'
          : m.kind === 'proposal' || m.kind === 'contract' || m.kind === 'deliverable' || m.kind === 'payment'
            ? m.kind
            : 'system';
      return {
        id: m.id,
        collaborationId: collaboration.id,
        timestamp: new Date(m.createdAt),
        senderId: m.senderId,
        senderType: m.senderType,
        senderName:
          m.senderType === 'brand' ? brandLabel : m.senderType === 'creator' ? creatorName : 'System',
        tag,
        content: m.content,
        metadata: m.metadata as TimelineEvent['metadata'],
        status: m.readBy.length > 0 ? 'read' : 'delivered',
      };
    });
  }, [liveMessages, collaboration.id, brandLabel, creatorName]);

  const events = React.useMemo(() => {
    const raw = isApiLive()
      ? liveEvents
      : (() => {
          const roomId = toDealRoomId(collaboration.id);
          const persisted = persistMessages
            ? getPersistedMessages(roomId).map((m) =>
                persistedToTimelineEvent(roomId, m, creatorName, brandLabel),
              )
            : [];
          return [...baseEvents, ...persisted];
        })();

    const merged = raw.map((event) => {
      const deliverableId = event.metadata?.deliverableId;
      if (!deliverableId || !deliverableOverrides[deliverableId]) {
        return event;
      }
      return {
        ...event,
        metadata: {
          ...event.metadata,
          ...deliverableOverrides[deliverableId],
        },
      };
    });
    const seen = new Set<string>();
    return merged.filter((e) => {
      if (seen.has(e.id)) return false;
      seen.add(e.id);
      return true;
    });
  }, [baseEvents, liveEvents, collaboration.id, creatorName, brandLabel, persistMessages, refreshKey, deliverableOverrides]);

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

  const handleDeliverableReviewSuccess = React.useCallback(
    (result: DeliverableReviewResult) => {
      setDeliverableOverrides((prev) => {
        const existing = prev[result.deliverableId] ?? {};
        const revisionCount =
          result.status === 'revision_requested'
            ? (existing.revisionCount ?? 0) + 1
            : existing.revisionCount;

        return {
          ...prev,
          [result.deliverableId]: {
            ...existing,
            deliverableStatus: result.status as TimelineEventMetadata['deliverableStatus'],
            revisionFeedback: result.feedback ?? existing.revisionFeedback,
            revisionCount,
          },
        };
      });
      setRefreshKey((k) => k + 1);
    },
    [],
  );

  const handleSend = async () => {
    const text = draft.trim();
    if (!text || isSending) return;

    if (!isApiLive()) {
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
      return;
    }

    setIsSending(true);
    setSendError(null);
    try {
      const sent = await api.messages.send(currentUserType, dealId, text);
      setLiveMessages((prev) => [...prev, sent]);
      setDraft('');
    } catch (e) {
      setSendError(e instanceof ApiError ? e.message : 'Could not send message. Try again.');
    } finally {
      setIsSending(false);
    }
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

      {isApiLive() && (
        <Alert className="shrink-0 py-2">
          <Info className="h-4 w-4" />
          <AlertDescription className="text-xs">
            Messages and proposals below are real. Contract signing, deliverable, and payment
            history aren't tracked as activity events yet — check the Contract and Deliverables
            panels for current status.
          </AlertDescription>
        </Alert>
      )}

      <ScrollArea className="flex-1 min-h-0">
        <div className="pr-4 space-y-4 pb-2">
          {isApiLive() && messagesLoading && liveMessages.length === 0 && (
            <div className="flex items-center justify-center py-6 gap-2 text-xs text-muted-foreground">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              Loading activity…
            </div>
          )}
          {isApiLive() && messagesError && (
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle className="text-sm">Could not load activity</AlertTitle>
              <AlertDescription className="text-xs space-y-2">
                <p>{messagesError}</p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  onClick={() => void loadMessages()}
                >
                  <RefreshCw className="h-3.5 w-3.5 mr-1" />
                  Retry
                </Button>
              </AlertDescription>
            </Alert>
          )}
          {sortedEvents.length === 0 && !(isApiLive() && (messagesLoading || messagesError)) ? (
            <div className="flex items-center justify-center py-8">
              <p className="text-muted-foreground text-sm">No events to show</p>
            </div>
          ) : (
            sortedEvents.map((event) => (
              <TimelineEventCard
                key={event.id}
                event={event}
                currentUserType={currentUserType}
                onDeliverableReviewSuccess={handleDeliverableReviewSuccess}
              />
            ))
          )}
          <div ref={messagesEndRef} />
        </div>
      </ScrollArea>

      {isApiLive() && sendError && (
        <Alert variant="destructive" className="shrink-0 py-2">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription className="text-xs">{sendError}</AlertDescription>
        </Alert>
      )}

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
