import * as React from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, CheckCircle2, Loader2, ShieldCheck } from 'lucide-react';

import { api, ApiError } from '@/lib/api';
import { isWorkspaceNotVerified } from '@/lib/api-errors';
import type { Campaign } from '@/lib/types';
import { FundEscrowButton } from '@/components/feature/meera/FundEscrowButton';
import { Button } from '@/components/ui/button';
import { cn, formatINR } from '@/lib/utils';

/**
 * F-0848 (Priya's ruling b, wiki/tech/BUILD-PLAN-F0848-MEMORY-0917.md §1.4) — the brand publish
 * route is now: save as DRAFT → secure the funds (this step) → set ACTIVE through the ordinary
 * `api.campaigns.update`, where the server's activation guard checks the funds and charges the
 * platform fee. Creating a campaign as ACTIVE is refused server-side
 * (`CAMPAIGN_CREATE_STATUS_NOT_ALLOWED`), so no caller in the app may send it.
 *
 * Money rules this component keeps:
 *  - It never funds twice. Once the server confirms the funds, `FundEscrowButton` is unmounted
 *    (a remount would reset its hook to idle and allow a second hold), and every later retry is
 *    `update` only. A resumed draft (`checkExistingFunds`) first looks for a FUNDED hold on this
 *    campaign and, if one exists, never shows the fund control at all.
 *  - A concurrent second publish (double-click, retry while in flight) is dropped by a ref, not
 *    by `disabled`, which only lands after a re-render.
 *  - The fee amount is NOT shown here. `GET /brand/platform-fee` returns the global config, while
 *    the charge resolves the plan rate (Pro = 7%) — showing that number would be wrong for Pro
 *    brands (ledger F-0849). The copy says when the fee is charged, never a made-up figure.
 *
 * Brand-facing copy must never say "escrow" (the component name above is internal only).
 */

export const CAMPAIGN_CREATE_STATUS_NOT_ALLOWED = 'CAMPAIGN_CREATE_STATUS_NOT_ALLOWED';

/** True when the server refused a create because it carried a status other than DRAFT. */
export function isCreateStatusNotAllowed(err: unknown): boolean {
  return err instanceof ApiError && err.code === CAMPAIGN_CREATE_STATUS_NOT_ALLOWED;
}

/** Toast copy for `CAMPAIGN_CREATE_STATUS_NOT_ALLOWED` — shared by both campaign forms. */
export const CREATE_STATUS_NOT_ALLOWED_TOAST = {
  title: 'Your campaign was not saved',
  description:
    'Publishing now happens in two steps: save the campaign, then secure the funds. Your details are still on this page — refresh to load the latest version, then press Publish again.',
} as const;

/** How many 100-row pages of holds a resumed draft scans before giving up (bounded read). */
const MAX_HOLD_PAGES = 10;
const HOLD_PAGE_SIZE = 100;

async function hasFundedHold(campaignId: string): Promise<boolean> {
  for (let page = 1; page <= MAX_HOLD_PAGES; page++) {
    const rows = await api.wallet.escrowList(page, HOLD_PAGE_SIZE);
    if (rows.some((r) => r.campaignId === campaignId && r.status === 'FUNDED')) return true;
    if (rows.length < HOLD_PAGE_SIZE) return false;
  }
  return false;
}

function describePublishError(err: unknown): { message: string; walletLink: boolean } {
  if (isWorkspaceNotVerified(err)) {
    return {
      message: 'Your workspace must be verified before this campaign can go live.',
      walletLink: false,
    };
  }
  if (err instanceof ApiError) {
    if (err.code === 'INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH') {
      return { message: err.message, walletLink: true };
    }
    if (err.code === 'ESCROW_NOT_FUNDED') {
      return {
        message: 'We could not confirm the secured funds yet. Wait a moment, then press Publish campaign again.',
        walletLink: false,
      };
    }
    if (err.message && !/escrow/i.test(err.message)) {
      return { message: err.message, walletLink: false };
    }
  }
  return { message: 'Could not publish the campaign. Please try again.', walletLink: false };
}

type Phase = 'checking' | 'check_failed' | 'needs_funds' | 'ready' | 'publishing' | 'publish_failed' | 'published';

export interface SecureAndPublishStepProps {
  /** The saved DRAFT to fund and publish. */
  campaignId: string;
  /** Budget maximum — display only; the server derives the amount it secures. */
  budgetAmount: number;
  /**
   * True for a campaign that existed before this screen (a resumed draft): funds may already be
   * secured, so look before offering the fund control. A just-created draft cannot have any.
   */
  checkExistingFunds: boolean;
  /** Called once the server has set the campaign ACTIVE. */
  onPublished: (campaign: Campaign) => void | Promise<void>;
  /** Leave the flow; the campaign stays a draft. */
  onKeepDraft: () => void;
  className?: string;
}

export function SecureAndPublishStep({
  campaignId,
  budgetAmount,
  checkExistingFunds,
  onPublished,
  onKeepDraft,
  className,
}: SecureAndPublishStepProps) {
  const [phase, setPhase] = React.useState<Phase>(checkExistingFunds ? 'checking' : 'needs_funds');
  const [fundsSecured, setFundsSecured] = React.useState(false);
  const [publishError, setPublishError] = React.useState<{ message: string; walletLink: boolean } | null>(null);
  const publishInFlightRef = React.useRef(false);
  const [checkAttempt, setCheckAttempt] = React.useState(0);

  React.useEffect(() => {
    if (!checkExistingFunds) return;
    let cancelled = false;
    setPhase('checking');
    hasFundedHold(campaignId)
      .then((funded) => {
        if (cancelled) return;
        setFundsSecured(funded);
        setPhase(funded ? 'ready' : 'needs_funds');
      })
      .catch(() => {
        // Never guess "not funded" on a failed read — offering the fund control here could
        // secure the same budget a second time.
        if (!cancelled) setPhase('check_failed');
      });
    return () => {
      cancelled = true;
    };
  }, [campaignId, checkExistingFunds, checkAttempt]);

  const publish = React.useCallback(async () => {
    if (publishInFlightRef.current) return;
    publishInFlightRef.current = true;
    setPublishError(null);
    setPhase('publishing');
    let updated: Campaign;
    try {
      updated = await api.campaigns.update(campaignId, { status: 'ACTIVE' });
    } catch (err) {
      setPublishError(describePublishError(err));
      setPhase('publish_failed');
      publishInFlightRef.current = false;
      return;
    }
    // The campaign IS live from here on. `publishInFlightRef` stays set, so nothing on this
    // step can send a second activation; a failure inside the parent's follow-up (invite,
    // navigation) must never be reported to the brand as a failed publish.
    setPhase('published');
    await onPublished(updated);
  }, [campaignId, onPublished]);

  const handleFunded = React.useCallback(() => {
    setFundsSecured(true);
    void publish();
  }, [publish]);

  const busy = phase === 'publishing' || phase === 'published';

  return (
    <section
      aria-labelledby="secure-and-publish-title"
      data-testid="secure-and-publish-step"
      className={cn('mt-8 rounded-xl border border-border bg-card p-5 sm:p-6', className)}
    >
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        <CheckCircle2 className="h-4 w-4 text-primary" aria-hidden="true" />
        Your campaign is saved as a draft. It is not live yet.
      </p>

      <h2 id="secure-and-publish-title" className="mt-3 flex items-center gap-2 text-lg font-semibold">
        <ShieldCheck className="h-5 w-5 text-primary" aria-hidden="true" />
        Secure the funds to publish
      </h2>
      <p className="mt-1 text-sm text-foreground">
        We hold {formatINR(budgetAmount)} securely until creators deliver. Your platform fee is charged from your
        wallet when the campaign goes live.
      </p>

      <ol className="mt-4 space-y-1.5 text-sm text-muted-foreground">
        <li>1. Secure {formatINR(budgetAmount)} from your wallet. If your balance is short, you can add the difference here.</li>
        <li>2. We publish the campaign and charge the platform fee from your wallet.</li>
        <li>3. Creators can start working with you.</li>
      </ol>

      <div className="mt-5 space-y-3">
        {phase === 'checking' && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            Checking whether funds are already secured for this campaign…
          </p>
        )}

        {phase === 'check_failed' && (
          <div className="space-y-2">
            <p className="flex items-start gap-2 text-sm text-destructive-foreground" role="alert">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              We could not check whether funds are already secured for this campaign, so nothing was charged. Please try
              again.
            </p>
            <Button type="button" onClick={() => setCheckAttempt((n) => n + 1)}>
              Check again
            </Button>
          </div>
        )}

        {phase === 'needs_funds' && !fundsSecured && (
          <>
            <FundEscrowButton campaignId={campaignId} displayAmount={budgetAmount} onFunded={handleFunded} />
            <p className="text-xs text-muted-foreground">
              Nothing goes live until the funds are secured. If a payment fails, your campaign stays saved as a draft
              and you can try again.
            </p>
          </>
        )}

        {fundsSecured && (
          <p className="flex items-center gap-2 text-sm font-medium text-foreground">
            <ShieldCheck className="h-4 w-4 text-primary" aria-hidden="true" />
            Funds secured for this campaign.
          </p>
        )}

        {phase === 'publishing' && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            Publishing your campaign…
          </p>
        )}

        {phase === 'publish_failed' && publishError && (
          <div className="space-y-1" role="alert">
            <p className="flex items-start gap-2 text-sm text-destructive-foreground">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              {publishError.message}
            </p>
            <p className="text-sm text-muted-foreground">
              The campaign is still a draft — nothing was published, and you will not be asked to secure the funds
              again.
              {publishError.walletLink && (
                <>
                  {' '}
                  <Link to="/brand/wallet" className="font-medium text-primary underline underline-offset-2">
                    Top up your wallet
                  </Link>
                  , then come back and publish.
                </>
              )}
            </p>
          </div>
        )}

        {fundsSecured && (phase === 'ready' || phase === 'publish_failed') && (
          <Button type="button" onClick={() => void publish()}>
            Publish campaign
          </Button>
        )}
      </div>

      <div className="mt-5 border-t border-border pt-4">
        <Button type="button" variant="outline" onClick={onKeepDraft} disabled={busy}>
          Keep as draft
        </Button>
      </div>
    </section>
  );
}

export default SecureAndPublishStep;
