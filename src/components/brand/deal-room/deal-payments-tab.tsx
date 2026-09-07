import * as React from 'react';
import { Link } from 'react-router-dom';
import { IndianRupee, Lock, Shield, Unlock } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { FundEscrowButton } from '@/components/feature/meera/FundEscrowButton';
import { api, ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import type { ContractMilestone } from '@/lib/api';
import { formatINR } from '@/lib/utils';
import type { DealContractStatus } from './deal-contract-tab';

interface PaymentMilestoneRow {
  id: string;
  label: string;
  amount: number;
  status: 'pending' | 'locked' | 'released';
  date?: string;
}

interface DealPaymentsTabProps {
  dealValue: number;
  contractStatus: DealContractStatus | null;
  deliverablesDone: number;
  deliverablesTotal: number;
  /**
   * F-0222 — the REAL escrow state, from `DealDtos.DealResponse.escrowFunded`.
   *
   * This panel used to derive "Escrow active" from `contractStatus` alone, which says only
   * that both parties signed. Signing does not move money: `CollaborationLifecycleService`
   * advances CONTRACTED on full signature and IN_PROGRESS only once `EscrowService` actually
   * funds. So a signed-but-unfunded deal rendered "Escrow active — ₹X is secured" over an
   * empty hold. Left optional so the creator room (which does not carry this field) keeps its
   * previous behaviour rather than silently reading `false` as "definitely not funded".
   */
  escrowFunded?: boolean;
  /**
   * F-0222 — supplying these turns on the funding control. The creator room mounts this same
   * component and passes neither, so it never renders a brand-only money action.
   *
   * `campaignId` is required by `POST /wallet/escrow/fund`; `milestones` come from
   * `GET /contracts/:id`. Funding WITHOUT a milestoneId is a different, deliberate backend
   * path — campaign-level pool funding, which leaves `collaboration_id` null and therefore
   * never advances the deal (EscrowService.initiateFund, the `milestoneForCollaborationBinding
   * == null` branch). That pool path is what /brand/wallet offers, and it is why funding there
   * could never unblock a signed deal. This control exists to fund a MILESTONE.
   */
  campaignId?: string;
  milestones?: ContractMilestone[];
  onFunded?: () => void;
}

/** Both signatures are in. Mirrors `creator-deal-contract-tab.tsx`'s `fullySigned`. */
function isFullySigned(status: DealContractStatus | null): boolean {
  return status === 'creator_signed' || status === 'active';
}

/**
 * The milestone a fund click should target: the first one still PENDING that carries a real
 * server id. FUNDED/RELEASED/REFUNDED/FROZEN are not re-fundable (`MilestoneStatus`), and a
 * milestone with no id was never persisted, so there is nothing for the server to resolve.
 */
function nextFundableMilestone(milestones: ContractMilestone[]): ContractMilestone | null {
  return (
    milestones.find(
      (m) => !!m.id && (m.status ?? 'PENDING').toUpperCase() === 'PENDING',
    ) ?? null
  );
}

export function DealPaymentsTab({
  dealValue,
  contractStatus,
  deliverablesDone,
  deliverablesTotal,
  escrowFunded,
  campaignId,
  milestones,
  onFunded,
}: DealPaymentsTabProps) {
  const fullySigned = isFullySigned(contractStatus);
  // When the caller knows the real escrow state, it wins. Otherwise fall back to the old
  // signature-derived guess so the creator room's behaviour is unchanged by this fix.
  const escrowLocked = escrowFunded ?? fullySigned;

  const realMilestones = milestones ?? [];
  const hasRealMilestones = realMilestones.length > 0;
  const fundable = nextFundableMilestone(realMilestones);
  const canFund = !!campaignId && fullySigned && !!fundable;

  // F-0224 — manual release. `POST /wallet/escrow/release` existed with ZERO callers anywhere in
  // the app: auto-release on approval (BrandDeliverableService) was the only path money could
  // take, and when it skipped (F-0223's eight conditions) nothing in the product could retry it.
  // The money sat in escrow with no screen able to move it.
  //
  // Deliberately NOT gated on the server's release preconditions. Those live in
  // EscrowService#releaseInternal (dispute freeze, release_condition, funded state), and
  // re-deriving them here is the CR-27 trap — a UI guess about server state that drifts from it.
  // The button is offered for any FUNDED milestone and the server's refusal is surfaced verbatim,
  // which is both honest and more useful than a hidden control.
  const [releasingId, setReleasingId] = React.useState<string | null>(null);
  const { toast } = useToast();

  const handleRelease = async (milestoneId: string) => {
    setReleasingId(milestoneId);
    try {
      await api.payments.releasePayout(milestoneId);
      toast({
        title: 'Payment released',
        description: 'The funds are on their way to the creator.',
      });
      onFunded?.();
    } catch (err) {
      toast({
        title: 'Could not release payment',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setReleasingId(null);
    }
  };

  // F-0437 — real contract milestones ONLY: ids, amounts and statuses straight from
  // `payment_milestones`. When the server sends none, this list stays EMPTY and the panel says so
  // (see the empty state below).
  //
  // What used to be here was a derived "pre-contract placeholder": a `Funds secured` row for the
  // full dealValue plus one `Deliverable N payout` row per deliverable at dealValue/deliverablesTotal.
  // It was labelled a placeholder in a comment, but nothing on screen distinguished those rupee
  // figures from real ones, and it did not stop at inventing AMOUNTS — it invented STATUSES too,
  // marking the first `deliverablesDone` rows `released` with today's date. A brand looking at the
  // payments tab of a deal with no contract saw a payment plan, and saw money already paid out,
  // when no milestone existed and nothing had been released. The sibling deal-contract-tab.tsx
  // renders "No milestones on this contract." for exactly this state; this tab now agrees with it.
  const rows: PaymentMilestoneRow[] = hasRealMilestones
    ? realMilestones.map((m, i) => {
        const raw = (m.status ?? 'PENDING').toUpperCase();
        return {
          id: m.id ?? `ms-${i + 1}`,
          label: m.description || `Milestone ${m.sequenceNo ?? i + 1}`,
          amount: m.amount,
          status:
            raw === 'RELEASED' ? 'released' : raw === 'FUNDED' ? 'locked' : 'pending',
        };
      })
    : [];

  const releasedTotal = rows
    .filter((m) => m.status === 'released')
    .reduce((s, m) => s + m.amount, 0);
  const lockedTotal = rows
    .filter((m) => m.status === 'locked')
    .reduce((s, m) => s + m.amount, 0);
  const inEscrow = hasRealMilestones ? lockedTotal : escrowLocked ? dealValue - releasedTotal : 0;

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        <Card className={escrowLocked ? 'border-success/30 bg-success/5' : 'border-muted'}>
          <CardContent className="pt-4 flex items-start gap-3">
            {escrowLocked ? (
              <Lock className="h-5 w-5 text-success-foreground shrink-0 mt-0.5" />
            ) : (
              <Shield className="h-5 w-5 text-muted-foreground shrink-0 mt-0.5" />
            )}
            <div>
              <p className="font-medium text-sm">
                {escrowLocked
                  ? 'Payment secured'
                  : fullySigned
                    ? 'Contract signed — funds not secured yet'
                    : 'Funds not secured yet'}
              </p>
              <p className="text-sm text-muted-foreground mt-1">
                {escrowLocked
                  ? `${formatINR(inEscrow)} is secured until deliverables are approved.`
                  : fullySigned
                    ? 'Both parties have signed. Fund the milestone below to start the work — the creator cannot submit deliverables until the funds are secured.'
                    : 'Secure the funds after both parties sign the contract.'}
              </p>
            </div>
          </CardContent>
        </Card>

        {/* F-0222 — the deal room's only path to a milestone-scoped fund. Before this, step 3
            of the progress bar ("Fund escrow") opened this panel and offered no control at
            all, so the sole reachable funding action in the product was the campaign-level
            pool on /brand/wallet, which never binds a collaboration and therefore never
            advances CONTRACTED -> IN_PROGRESS. */}
        {canFund && (
          <Card className="border-primary/30">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium">Fund this milestone</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">
                {fundable?.description || `Milestone ${fundable?.sequenceNo ?? 1}`} —{' '}
                {formatINR(fundable?.amount ?? 0)} moves from your wallet into secured funds. The
                amount is re-derived server-side.
              </p>
              <FundEscrowButton
                key={fundable?.id}
                campaignId={campaignId as string}
                milestoneId={fundable?.id}
                displayAmount={fundable?.amount}
                onFunded={onFunded}
              />
            </CardContent>
          </Card>
        )}

        <div className="grid grid-cols-2 gap-4">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                Secured
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold">{formatINR(inEscrow)}</p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">Released</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold text-success-foreground">{formatINR(releasedTotal)}</p>
            </CardContent>
          </Card>
        </div>

        <Separator />

        <div>
          <h3 className="font-semibold text-sm mb-3">Payment milestones</h3>
          <div className="space-y-2">
            {rows.map((m) => (
              <div
                key={m.id}
                className="flex items-center justify-between p-3 rounded-lg border bg-card"
              >
                <div className="flex items-center gap-2">
                  {m.status === 'released' ? (
                    <Unlock className="h-4 w-4 text-success-foreground" />
                  ) : m.status === 'locked' ? (
                    <Lock className="h-4 w-4 text-warning" />
                  ) : (
                    <IndianRupee className="h-4 w-4 text-muted-foreground" />
                  )}
                  <span className="text-sm">{m.label}</span>
                </div>
                <div className="flex items-center gap-2">
                  {/* F-0224 — brand-only (campaignId is the same brand-mount discriminator the
                      fund control uses; the creator room passes neither prop). Only on a real
                      server milestone: the derived placeholder rows have no id to release. */}
                  {!!campaignId && hasRealMilestones && m.status === 'locked' && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={releasingId === m.id}
                      onClick={() => void handleRelease(m.id)}
                    >
                      {releasingId === m.id ? 'Releasing…' : 'Release'}
                    </Button>
                  )}
                  <span className="text-sm font-medium">{formatINR(m.amount)}</span>
                  <Badge
                    variant="outline"
                    className={
                      m.status === 'released'
                        ? 'text-success-foreground border-success/30'
                        : m.status === 'locked'
                          ? 'text-warning border-warning/30'
                          : ''
                    }
                  >
                    {m.status}
                  </Badge>
                </div>
              </div>
            ))}
          </div>
          {/* F-0437 — the honest empty state that replaced the invented schedule. It NAMES what is
              absent (milestones / payment schedule) rather than only hinting that figures are
              approximate: the old copy said "Estimated from the deal value", which described
              fabricated rows as an estimate instead of saying no schedule exists. */}
          {!hasRealMilestones && (
            <p className="mt-2 text-xs text-muted-foreground">
              No payment milestones on this contract yet. The payment schedule appears here once
              the contract is generated — nothing is scheduled or paid until then.
            </p>
          )}
        </div>

        <Button variant="outline" className="w-full" asChild>
          <Link to="/brand/wallet">View wallet &amp; transactions</Link>
        </Button>
      </div>
    </ScrollArea>
  );
}
