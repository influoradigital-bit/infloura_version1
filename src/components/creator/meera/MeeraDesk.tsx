import * as React from 'react';
import { Link } from 'react-router-dom';
import { Briefcase, CheckCircle2, IndianRupee, Sparkles, TrendingUp, type LucideIcon } from 'lucide-react';

import { Skeleton } from '@/components/ui/skeleton';
import { cn, formatINR } from '@/lib/utils';
import { api } from '@/lib/api';
import { mapDealToDealsPageRow } from '@/lib/creator-deal-mappers';
import {
  computeDealAttentionCounts,
  fetchAwaitingSignatureCount,
  totalAttentionCount,
} from '@/lib/creator-needs-attention';
import {
  ASK_MY_RATE_PROMPT,
  DESK_HEADER,
  DESK_TILE_ALL_CAUGHT_UP,
  DESK_TILE_ASK_MY_RATE,
  DESK_TILE_AVAILABLE_BALANCE,
  DESK_TILE_PENDING_PAYOUTS,
  DESK_TILE_PROFILE_VIEWS,
  STARTER_PROMPTS,
  attentionLabel,
  pickLang,
} from '@/lib/copy/meera-chat';

/**
 * MEERA-CHAT-DESIGN-SPEC.md Part B — "Meera is on it" desk, shown in place of the empty screen
 * while a conversation has no creator messages yet.
 *
 * Every figure here is real API data or the tile is hidden — never a 0/₹0 standing in for a
 * fetch that failed. Each tile fetches independently and fails soft (its own `loading`/`value`
 * state), so one broken source never blanks the other three.
 *
 * Round 2 QA (F-0631 class): tile 1's count now goes through `@/lib/creator-needs-attention`
 * (`computeDealAttentionCounts` + `fetchAwaitingSignatureCount`), the SAME module
 * `creator-dashboard.tsx` calls for its own "N items need your attention" figure — round 1 of
 * this tile summed only unread + unsigned contracts, silently omitting submittable
 * deliverables, which is exactly the "two counts that can disagree" bug class F-0631 already
 * named once. Both surfaces now call the same functions on the same inputs.
 */

export interface MeeraDeskProps {
  language: string;
  /** Fill the composer, never send (RULINGS-U-0917.md R-U1) — a purely local prefill, since the
   *  desk lives inside the chat panel that owns the composer's `draft` state directly. */
  onPrefill: (text: string) => void;
  className?: string;
}

interface TileState<T> {
  loading: boolean;
  value: T | null;
}

const LOADING: TileState<never> = { loading: true, value: null };

/**
 * Tile 1, "N items need your attention" — the exact same figure `creator-dashboard.tsx` shows
 * (`pendingTotal`): unread messages over EVERY deal, submittable deliverables over ACTIVE deals
 * (batched), and unsigned contracts, all via `@/lib/creator-needs-attention` so this tile and
 * the dashboard's cannot independently drift (F-0631). `GET /deals` is mapped through the same
 * `mapDealToDealsPageRow` the deals page and dashboard use. All three sources already self-gate
 * mock/live inside `api.ts`; a failure anywhere in the chain hides the whole tile rather than
 * showing a partial, possibly-misleading count.
 */
function useAttentionTile(): TileState<number> {
  const [state, setState] = React.useState<TileState<number>>(LOADING);
  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [dealRows, awaitingSignatureCount] = await Promise.all([
          api.deals.list('creator', 'all'),
          fetchAwaitingSignatureCount(),
        ]);
        if (cancelled) return;
        const deals = dealRows.map(mapDealToDealsPageRow);
        const counts = await computeDealAttentionCounts(deals);
        if (cancelled) return;
        setState({ loading: false, value: totalAttentionCount(counts, awaitingSignatureCount) });
      } catch {
        if (cancelled) return;
        setState({ loading: false, value: null });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);
  return state;
}

interface WalletTileValue {
  amount: number;
  /** True when this is `pendingPayouts` (money on its way); false when it fell back to
   *  `availableBalance` because there was no pending payout. */
  pending: boolean;
}

/** Tile 2, "Money on its way / balance" — `GET /wallet`. Prefers `pendingPayouts` (the exact
 *  field the Wallet page labels "Pending Payouts"); falls back to `availableBalance` ("Available
 *  Balance") only when there is genuinely no pending payout, per the spec. */
function useWalletTile(): TileState<WalletTileValue> {
  const [state, setState] = React.useState<TileState<WalletTileValue>>(LOADING);
  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const wallet = await api.wallet.get('creator');
        if (cancelled) return;
        const pending = wallet.pendingPayouts > 0;
        setState({
          loading: false,
          value: { amount: pending ? wallet.pendingPayouts : wallet.availableBalance, pending },
        });
      } catch {
        if (cancelled) return;
        setState({ loading: false, value: null });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);
  return state;
}

interface EngagementTileValue {
  views: number;
  /** Only rendered as an "up" chip when this is a real positive delta from the API — never
   *  invented (spec: "NO 'up from last month' unless the API returns the previous value"). */
  deltaPercent: number;
}

/** Tile 3, "Engagement / reach" — `GET /me/portfolio/analytics`, labelled exactly as the
 *  dashboard's own `PublicPageCard` labels the same field ("Profile views · 30d"). */
function useEngagementTile(): TileState<EngagementTileValue> {
  const [state, setState] = React.useState<TileState<EngagementTileValue>>(LOADING);
  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const analytics = await api.portfolio.analytics();
        if (cancelled) return;
        setState({
          loading: false,
          value: { views: analytics.pageViews.last30Days, deltaPercent: analytics.pageViews.deltaPercent },
        });
      } catch {
        if (cancelled) return;
        setState({ loading: false, value: null });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);
  return state;
}

function TileSkeleton() {
  return <Skeleton data-testid="desk-tile-skeleton" className="h-[72px] min-h-11 w-full rounded-xl" />;
}

interface TileShellProps {
  testId: string;
  icon: LucideIcon;
  label: string;
  value?: string;
  delta?: string;
}

function TileContent({ icon: Icon, label, value, delta }: Omit<TileShellProps, 'testId'>) {
  return (
    <>
      <Icon className="h-4 w-4 text-primary" aria-hidden />
      {value !== undefined && (
        <p className="text-base font-semibold tabular-nums text-foreground">
          {value}
          {delta && <span className="ml-1 text-xs font-medium text-success-foreground">{delta}</span>}
        </p>
      )}
      <p className="text-xs text-muted-foreground">{label}</p>
    </>
  );
}

const TILE_CLASSES =
  'flex min-h-11 flex-col justify-center gap-1 rounded-xl border border-border bg-card p-3 text-left transition-[box-shadow,border-color] duration-150 ease-out hover:border-primary/40 hover:shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary';

function DeskTileLink({ testId, to, ...content }: TileShellProps & { to: string }) {
  return (
    <Link to={to} data-testid={testId} className={TILE_CLASSES}>
      <TileContent {...content} />
    </Link>
  );
}

function DeskTileButton({ testId, onClick, ...content }: TileShellProps & { onClick: () => void }) {
  return (
    <button type="button" data-testid={testId} onClick={onClick} className={TILE_CLASSES}>
      <TileContent {...content} />
    </button>
  );
}

export function MeeraDesk({ language, onPrefill, className }: MeeraDeskProps) {
  const attention = useAttentionTile();
  const wallet = useWalletTile();
  const engagement = useEngagementTile();

  const askMyRate = () => onPrefill(pickLang(language, ASK_MY_RATE_PROMPT));

  return (
    <div data-testid="meera-desk" className={cn('space-y-3', className)}>
      <p className="text-sm font-medium text-muted-foreground">{pickLang(language, DESK_HEADER)}</p>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {attention.loading ? (
          <TileSkeleton />
        ) : attention.value === null ? null : attention.value === 0 ? (
          // Round 2 QA — zero is a real, good state: never a bare "0", a check + "All caught up".
          <Link to="/creator/deals" data-testid="desk-tile-attention" className={TILE_CLASSES}>
            <CheckCircle2 className="h-4 w-4 text-success-foreground" aria-hidden />
            <p className="text-sm font-medium text-foreground">{pickLang(language, DESK_TILE_ALL_CAUGHT_UP)}</p>
          </Link>
        ) : (
          <DeskTileLink
            testId="desk-tile-attention"
            to="/creator/deals"
            icon={Briefcase}
            label={attentionLabel(language, attention.value)}
            value={String(attention.value)}
          />
        )}

        {wallet.loading ? (
          <TileSkeleton />
        ) : wallet.value !== null ? (
          <DeskTileLink
            testId="desk-tile-wallet"
            to="/creator/wallet"
            icon={IndianRupee}
            label={pickLang(
              language,
              wallet.value.pending ? DESK_TILE_PENDING_PAYOUTS : DESK_TILE_AVAILABLE_BALANCE,
            )}
            value={formatINR(wallet.value.amount)}
          />
        ) : null}

        {engagement.loading ? (
          <TileSkeleton />
        ) : engagement.value !== null ? (
          <DeskTileLink
            testId="desk-tile-engagement"
            to="/creator/portfolio"
            icon={TrendingUp}
            label={pickLang(language, DESK_TILE_PROFILE_VIEWS)}
            value={engagement.value.views.toLocaleString('en-IN')}
            delta={engagement.value.deltaPercent > 0 ? `+${engagement.value.deltaPercent}%` : undefined}
          />
        ) : null}

        <DeskTileButton
          testId="desk-tile-ask-rate"
          onClick={askMyRate}
          icon={Sparkles}
          label={pickLang(language, DESK_TILE_ASK_MY_RATE)}
        />
      </div>

      <div className="flex flex-wrap gap-2">
        {STARTER_PROMPTS.map((prompt) => (
          <button
            key={prompt.key}
            type="button"
            data-testid="desk-starter-prompt"
            onClick={() => onPrefill(pickLang(language, prompt.text))}
            className="min-h-11 rounded-full border border-border bg-card px-3 py-2 text-xs font-medium text-foreground transition-colors duration-150 ease-out hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            {pickLang(language, prompt.text)}
          </button>
        ))}
      </div>
    </div>
  );
}

export default MeeraDesk;
