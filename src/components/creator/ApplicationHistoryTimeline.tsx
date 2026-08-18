import * as React from 'react';
import { Link } from 'react-router-dom';
import {
  AlertCircle,
  Archive,
  Ban,
  Banknote,
  CheckCircle2,
  Circle,
  Eye,
  FileSignature,
  FileText,
  Handshake,
  Inbox,
  PackageCheck,
  RefreshCw,
  Send,
  Truck,
  UploadCloud,
  Wallet,
  XCircle,
} from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Skeleton } from '@/components/ui/skeleton';
import { api, ApiError, type CreatorApplicationHistoryEvent } from '@/lib/api';
import { getApplicationStatusLabel, getDeclineWording } from '@/lib/application-status';
import { stageBadgeClass, statusToStage } from '@/lib/stage-colors';
import { cn } from '@/lib/utils';

interface ApplicationHistoryTimelineProps {
  /** Same `Collaboration` id as `CreatorApplicationRow.dealId` — what the history endpoint keys on. */
  dealId: string;
  /** So the "Brand" actor row can show a real name instead of the generic word "Brand". */
  brandName: string;
  className?: string;
}

/**
 * The full 14-value taxonomy, transcribed from the enum itself —
 * influora-api/src/main/java/com/influora/domain/enums/ApplicationHistoryEventType.java —
 * not from any description of it, so a rename there is caught by reading the source next time
 * this file is touched. All 14 are wired backend-side (see that file's own header comment for
 * the exact call site behind each one) — none is a future/unemitted placeholder anymore. Kept
 * as a lookup, not a TypeScript union, on purpose — `CreatorApplicationHistoryEvent.eventType`
 * is typed `string` (see src/lib/api.ts) so an event type this map doesn't yet know about
 * (a future 15th value) still renders via `prettify()` below instead of disappearing.
 */
const EVENT_TYPE_LABELS: Record<string, string> = {
  CAMPAIGN_APPLIED: 'Campaign Applied',
  APPLICATION_RECEIVED: 'Application Received',
  APPLICATION_VIEWED: 'Application Viewed',
  // F-0302 — CEO ruling Decision 3 (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md):
  // `DealService#doAccept` is a MUTUAL proposal-accept, so this event type fires for either
  // party. This entry is the BRAND-actor (and fallback/SYSTEM) wording; `eventTypeLabel` below
  // special-cases a CREATOR actor to "Proposal Accepted" instead of using this entry, because
  // "Application Accepted" / actor "You" reads as "you accepted your own application" —
  // nonsensical. The creator accepted the brand's TERMS, not their own application.
  APPLICATION_ACCEPTED: 'Application Accepted',
  // F-0303/F-0287 — the wire value stays `APPLICATION_REJECTED` (the truthful audit record of
  // what happened); the WORD shown to a creator is decline wording, and decline wording has
  // exactly one source of truth: src/lib/application-status.ts's DECLINE_WORDING /
  // getDeclineWording(). `eventTypeLabel`/`eventTypeIcon` below resolve this event type from
  // that shared constant rather than a literal here, specifically so this label can never drift
  // out of sync with the CANCELLED status badge the way it did before F-0303's fix — do not add
  // a hardcoded entry for this key back into this map.
  APPLICATION_WITHDRAWN: 'Withdrawn',
  DEAL_ROOM_ACTIVATED: 'Deal Room Activated',
  CONTRACT_GENERATED: 'Contract Generated',
  CONTRACT_SIGNED: 'Contract Signed',
  FUND_ESCROW: 'Fund Escrow',
  DELIVERABLE_SUBMITTED: 'Deliverable Submitted',
  DELIVERABLE_APPROVED: 'Deliverable Approved',
  DELIVER: 'Deliver',
  PAY: 'Pay',
};

/**
 * Every value gets its own icon — no two events should be visually indistinguishable.
 * APPLICATION_REJECTED is deliberately absent — see the EVENT_TYPE_LABELS comment above; its
 * icon is resolved from the same shared `getDeclineWording()` source in `eventTypeIcon` below,
 * not a literal here.
 */
const EVENT_TYPE_ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  CAMPAIGN_APPLIED: Send,
  APPLICATION_RECEIVED: Inbox,
  APPLICATION_VIEWED: Eye,
  APPLICATION_ACCEPTED: CheckCircle2,
  // XCircle stays imported for the genuinely creator-initiated Withdrawn row below, where a
  // definite end-state IS the creator's own doing — distinct from a brand decline either way
  // getDeclineWording() resolves (see eventTypeIcon).
  APPLICATION_WITHDRAWN: XCircle,
  DEAL_ROOM_ACTIVATED: Handshake,
  CONTRACT_GENERATED: FileText,
  CONTRACT_SIGNED: FileSignature,
  FUND_ESCROW: Wallet,
  DELIVERABLE_SUBMITTED: UploadCloud,
  DELIVERABLE_APPROVED: PackageCheck,
  DELIVER: Truck,
  PAY: Banknote,
};

function normalizeKey(value: string): string {
  return value.trim().toUpperCase().replace(/[\s-]+/g, '_');
}

/** Title-cases an unrecognized raw value instead of hiding it — every real event must render. */
function prettify(value: string): string {
  return value
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .trim();
}

/**
 * `actorType` is required (not optional) on purpose — F-0302 needs it to disambiguate
 * APPLICATION_ACCEPTED, and a call site that forgets to pass it should fail to compile rather
 * than silently fall back to the brand wording for a creator's own accept.
 */
function eventTypeLabel(eventType: string, actorType: CreatorApplicationHistoryEvent['actorType']): string {
  const key = normalizeKey(eventType);
  // F-0302 — CEO ruling Decision 3: same event type, actor-dependent wording.
  if (key === 'APPLICATION_ACCEPTED' && actorType === 'CREATOR') {
    return 'Proposal Accepted';
  }
  // F-0303/F-0287 — decline wording has one source of truth; see EVENT_TYPE_LABELS' comment.
  if (key === 'APPLICATION_REJECTED') {
    return getDeclineWording().eventLabel;
  }
  return EVENT_TYPE_LABELS[key] ?? prettify(eventType);
}

function eventTypeIcon(eventType: string): React.ComponentType<{ className?: string }> {
  const key = normalizeKey(eventType);
  if (key === 'APPLICATION_REJECTED') {
    // 'neutral' (default/arbitration) -> Archive, matching the "Closed" wording's non-alarming
    // tone. 'explicit' (spec override) -> Ban, a clearer decline glyph matching "Rejected".
    return getDeclineWording().icon === 'neutral' ? Archive : Ban;
  }
  return EVENT_TYPE_ICONS[key] ?? Circle;
}

/**
 * `metadata` is a single free-text slot on the wire, and only `APPLICATION_REJECTED` /
 * `APPLICATION_WITHDRAWN` (`DealService#doReject`, `sanitizedReason`) currently populate it with
 * genuine, creator-facing text — every other event type sends `null` there (see the field's own
 * doc in src/lib/api.ts for the full, current per-event breakdown; that is the source of truth,
 * this comment is intentionally short so there is only one place to keep in sync).
 *
 * This allowlist stayed deliberately narrow (only the two genuine-reason event types) even
 * after the six contract/escrow/deliverable/payout sites that used to pass an opaque entity id
 * here (contract/hold/deliverable/milestone id — a real defect, fixed backend-side 2026-08-17)
 * were changed to pass `null` instead. Narrow-allowlist, not id-blocklist, on purpose: a future
 * event type defaults to "not shown" — the safe default — rather than "shown as if it were
 * text" until proven otherwise, so this function does not need to change again if a ninth
 * event type starts populating metadata with something that isn't a reason.
 */
const REASON_METADATA_EVENT_TYPES = new Set(['APPLICATION_REJECTED', 'APPLICATION_WITHDRAWN']);

function metadataIsHumanReason(eventType: string): boolean {
  return REASON_METADATA_EVENT_TYPES.has(normalizeKey(eventType));
}

function actorLabel(actorType: CreatorApplicationHistoryEvent['actorType'], brandName: string): string {
  switch (actorType) {
    case 'CREATOR':
      return 'You';
    case 'BRAND':
      return brandName || 'Brand';
    case 'SYSTEM':
      return 'Influora';
    default:
      return actorType;
  }
}

/**
 * Labels for the server-computed `dealPhase` (`DealPhaseCalculator`, mirrors `getDealPhase` in
 * deal-room-step-progress.tsx). Same 5 words that stepper already uses, so a creator who has
 * seen one recognizes the other. Deliberately has no entry for `null` — CANCELLED/DISPUTED
 * events carry `dealPhase: null` precisely because no phase is honest for them, and the render
 * site below skips the badge entirely rather than looking up a fallback.
 */
const DEAL_PHASE_LABELS: Record<'negotiate' | 'contract' | 'escrow' | 'deliver' | 'pay', string> = {
  negotiate: 'Negotiate',
  contract: 'Contract',
  escrow: 'Fund Escrow',
  deliver: 'Deliver',
  pay: 'Pay',
};

function ctaLabel(href: string): string {
  if (href.includes('/creator/chat')) return 'Open Deal Room';
  if (href.includes('/creator/campaigns')) return 'View Campaign';
  return 'View details';
}

/**
 * Resolves a per-event CTA. Prefers the server-provided `targetRoute` (the endpoint's own
 * word on where this event leads). Falls back to the established `/creator/chat?deal=<id>`
 * deal-room convention (CreatorApplicationCard.tsx) only when the event carries a real
 * `dealRoomId` — never fabricates a destination for an event that has neither.
 */
function resolveCta(event: CreatorApplicationHistoryEvent): { href: string; label: string } | null {
  if (event.targetRoute) {
    return { href: event.targetRoute, label: ctaLabel(event.targetRoute) };
  }
  if (event.dealRoomId) {
    return { href: `/creator/chat?deal=${event.dealRoomId}`, label: 'Open Deal Room' };
  }
  return null;
}

function formatEventTime(iso: string): { relative: string; absolute: string } {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return { relative: iso, absolute: iso };
  }
  const absolute = d.toLocaleString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
  const minutes = Math.floor((Date.now() - d.getTime()) / (1000 * 60));
  let relative: string;
  if (minutes < 1) relative = 'Just now';
  else if (minutes < 60) relative = `${minutes}m ago`;
  else if (minutes < 60 * 24) relative = `${Math.floor(minutes / 60)}h ago`;
  else if (minutes < 60 * 24 * 30) relative = `${Math.floor(minutes / (60 * 24))}d ago`;
  else relative = absolute;
  return { relative, absolute };
}

/**
 * Full chronological journey for one application — My Applications' per-card activity feed.
 * Fetches `GET /creator/applications/:dealId/history` and renders exactly what the server
 * returned, in the order it returned it. Never synthesizes an event from `status`: an
 * application whose journey the endpoint hasn't populated yet renders the honest empty state
 * below, not a fabricated "Applied" step reconstructed from the summary row.
 */
export function ApplicationHistoryTimeline({ dealId, brandName, className }: ApplicationHistoryTimelineProps) {
  const [events, setEvents] = React.useState<CreatorApplicationHistoryEvent[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [loaded, setLoaded] = React.useState(false);
  // CR-04 pattern (src/pages/creator-chat.tsx) — drives the Radix ScrollArea viewport's own
  // scrollTop directly. This lives inside a per-card `Collapsible` on a page that stacks many
  // application cards, so `scrollIntoView` here would walk every scrollable ancestor — the
  // page itself included — and yank the whole viewport to whichever card the creator just
  // expanded instead of scrolling only this timeline.
  const viewportRef = React.useRef<HTMLDivElement>(null);

  const load = React.useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.creatorApplications.history(dealId);
      setEvents(result);
      setLoaded(true);
    } catch (e) {
      setEvents([]);
      setError(e instanceof ApiError ? e.message : 'Could not load this application’s journey. Try again.');
    } finally {
      setLoading(false);
    }
  }, [dealId]);

  React.useEffect(() => {
    void load();
  }, [load]);

  // Requirement: "newest events stay visible; older events remain reachable by scrolling".
  // The list itself stays in the server's chronological (oldest-first) order — reversing it
  // client-side would misrepresent a contract that explicitly promises chronological order —
  // instead the view auto-scrolls to the newest (last) entry once it loads, by setting the
  // viewport's own `scrollTop` (never `scrollIntoView` — see the CR-04 note on the ref above).
  React.useEffect(() => {
    if (loading || events.length === 0) return;
    const viewport = viewportRef.current;
    if (!viewport) return;
    // Feature-detected the same way CR-04 does: jsdom (this component's own test suite)
    // implements neither smooth `Element.scrollTo` nor a real layout, so the unguarded call
    // would throw inside this effect under test.
    if (typeof viewport.scrollTo === 'function') {
      viewport.scrollTo({ top: viewport.scrollHeight, behavior: 'smooth' });
    } else {
      viewport.scrollTop = viewport.scrollHeight;
    }
  }, [loading, events.length]);

  if (loading) {
    return (
      <div className={cn('space-y-3 py-2', className)} aria-live="polite" aria-busy="true">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="flex gap-3">
            <Skeleton className="h-8 w-8 shrink-0 rounded-full" />
            <div className="flex-1 space-y-2 py-1">
              <Skeleton className="h-3.5 w-1/3" />
              <Skeleton className="h-3 w-2/3" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="destructive" className={cn('py-2', className)}>
        <AlertCircle className="h-4 w-4" />
        <AlertTitle className="text-sm">Could not load this application&rsquo;s journey</AlertTitle>
        <AlertDescription className="text-xs space-y-2">
          <p>{error}</p>
          <Button type="button" variant="outline" size="sm" className="h-7 text-xs" onClick={() => void load()}>
            <RefreshCw className="h-3.5 w-3.5 mr-1" />
            Retry
          </Button>
        </AlertDescription>
      </Alert>
    );
  }

  if (loaded && events.length === 0) {
    return (
      <p className={cn('py-4 text-center text-sm text-muted-foreground', className)}>
        No journey events recorded for this application yet.
      </p>
    );
  }

  return (
    <ScrollArea className={cn('max-h-80', className)} viewportRef={viewportRef}>
      <ol className="space-y-0 pr-3" aria-label="Application journey">
        {events.map((event, index) => {
          const Icon = eventTypeIcon(event.eventType);
          const stage = statusToStage(event.eventStatus);
          const { relative, absolute } = formatEventTime(event.createdAt);
          const cta = resolveCta(event);
          const isLast = index === events.length - 1;

          return (
            <li key={event.historyId} className="relative flex gap-3 pb-5 last:pb-0">
              {!isLast && (
                <span
                  className="absolute left-4 top-8 bottom-0 w-px -translate-x-1/2 bg-border"
                  aria-hidden="true"
                />
              )}
              <span className="relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-border bg-background">
                <Icon className="h-4 w-4 text-muted-foreground" />
              </span>
              <div className="min-w-0 flex-1 pt-0.5">
                <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium">{eventTypeLabel(event.eventType, event.actorType)}</span>
                    {/* Text and colour are two separate lookups on purpose — do not merge them.
                        Text: application-status.ts's getApplicationStatusLabel is the single
                        canonical creator-facing label map (CANCELLED -> decline wording via
                        getDeclineWording(), TERMS_AGREED -> "Accepted", etc.) — the same map
                        CreatorApplicationCard.tsx's badge and this event's own bucket derive
                        from, so a declined application can't show one word on the card and a
                        different one here. Falls back to the raw
                        status string for anything the map does not know (eventStatus is typed
                        string, not the closed CollaborationStatus union) — that fallback lives
                        inside getApplicationStatusLabel itself; do not layer prettify() on top of
                        it here, that would just be a second, competing fallback.
                        Colour: stageBadgeClass(stage) below is a completely separate lookup
                        (src/lib/stage-colors.ts), keyed off the same raw event.eventStatus via
                        statusToStage above — changing the label text must never change this. */}
                    <Badge variant="outline" className={cn('text-[10px]', stageBadgeClass(stage))}>
                      {getApplicationStatusLabel(event.eventStatus)}
                    </Badge>
                    {/* Server-computed dealPhase (DealPhaseCalculator) — null for CANCELLED/
                        DISPUTED events, which have no honest phase. Never falls back to
                        'negotiate' or any other phase for those; the badge simply omits. */}
                    {event.dealPhase && (
                      <Badge variant="outline" className="text-[10px] text-muted-foreground">
                        {DEAL_PHASE_LABELS[event.dealPhase]}
                      </Badge>
                    )}
                  </div>
                  <span className="shrink-0 text-xs text-muted-foreground" title={absolute}>
                    {relative}
                  </span>
                </div>
                {event.description && <p className="mt-0.5 text-sm text-muted-foreground">{event.description}</p>}
                {/* The server calls this slot `metadata` (a String), not `reason` — see
                    `metadataIsHumanReason`'s comment above for why this ALSO gates on eventType:
                    metadata is a genuine decline/withdrawal reason only for APPLICATION_REJECTED
                    and APPLICATION_WITHDRAWN. The other event types send `null` here today, but
                    six of them (contract/escrow/deliverable/payout) used to send an opaque
                    internal id instead — a real defect (a raw ULID rendered in the theme's error
                    token, as if something had gone wrong), fixed backend-side, not by this gate.
                    This gate is the second layer of defense, kept even though the six sites are
                    fixed: it costs nothing and stops that class of bug from reaching a creator
                    again if a call site regresses. NOT "hide metadata" as a blanket rule — a
                    real rejection/withdrawal reason still renders, styled as the decline text it
                    genuinely is. */}
                {event.metadata && metadataIsHumanReason(event.eventType) && (
                  <p className="mt-0.5 text-xs text-destructive-foreground">{event.metadata}</p>
                )}
                <p className="mt-1 text-xs text-muted-foreground">{actorLabel(event.actorType, brandName)}</p>
                {cta && (
                  <Button variant="link" size="sm" className="h-auto px-0 py-1 text-xs" asChild>
                    <Link to={cta.href}>{cta.label}</Link>
                  </Button>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </ScrollArea>
  );
}

export default ApplicationHistoryTimeline;
