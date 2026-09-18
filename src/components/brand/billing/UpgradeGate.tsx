import * as React from 'react';
import { Lock, ShieldCheck, Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { api, ApiError } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

/**
 * UpgradeGate — F-0886 (paywall-with-no-way-to-pay).
 * ----------------------------------------------------------------------------
 * Four brand surfaces (analytics, team invites, report export, campaign templates) are
 * Pro-gated server-side but, before this component, simply refused the action with no way to
 * upgrade: no `<UpgradeGate>` existed anywhere in `src/`, and the only 402 consumer
 * (`useCreatorMetrics.ts`) just swapped in a nicer error string. This is the one shared surface
 * every gated feature renders instead — either proactively (the workspace's `GET /billing/plan`
 * already shows the limit is hit / the feature disabled) or reactively (the server answered 402
 * `UPGRADE_REQUIRED`).
 *
 * The 402 body carries only `code: 'UPGRADE_REQUIRED'` and a message — no entitlement, limit or
 * usage fields (confirmed with the backend lane). Copy here is therefore deliberately generic by
 * default; a call site MAY pass a `reason` built from real data it already has (e.g. a `seatLimit`
 * or `campaignTemplatesEnabled` straight off `GET /billing/plan`), but must never invent a number
 * the server didn't actually send.
 *
 * F-0132-shaped role gate: `BillingController` requires `requireRole(OWNER, ADMIN)` on
 * `POST /billing/checkout`, mirrored client-side by `canManageBilling` (useBrandBillingAccess.ts).
 * A MANAGER/MEMBER/VIEWER must be told why they can't act, never shown a button that will 403.
 */

export type UpgradeGateFeature = 'analytics' | 'team invites' | 'report exports' | 'campaign templates';

const FEATURE_COPY: Record<UpgradeGateFeature, string> = {
  analytics: "Pro is needed to view more creator analytics — you've reached your plan's monthly limit.",
  'team invites': 'Pro is needed to invite more teammates to this workspace.',
  'report exports': 'Pro is needed to export reports.',
  'campaign templates': 'Pro is needed to save and reuse campaign templates.',
};

export interface UpgradeGateProps {
  /** Which Pro-gated feature triggered this gate — selects the default copy. */
  feature: UpgradeGateFeature;
  /**
   * Optional call-site-specific copy, built only from real data the caller already has (e.g. a
   * `seatLimit` off `GET /billing/plan`). Falls back to the generic `FEATURE_COPY` message —
   * never invent a number here, the 402 body carries none.
   */
  reason?: string;
  /** Mirrors `useBrandBillingAccess().canManage` — false for MANAGER/MEMBER/VIEWER. */
  canManageBilling: boolean;
  className?: string;
}

/**
 * F-0070-shaped checkout: reuses the exact `api.billing.initiateCheckout('PRO')` +
 * `window.location.assign` path `brand-billing-settings.tsx`'s Upgrade button already uses,
 * rather than inventing a second call to the same endpoint.
 */
export function UpgradeGate({ feature, reason, canManageBilling, className }: UpgradeGateProps) {
  const [checkoutBusy, setCheckoutBusy] = React.useState(false);

  const handleUpgrade = async () => {
    setCheckoutBusy(true);
    try {
      const { checkoutUrl } = await api.billing.initiateCheckout('PRO');
      window.location.assign(checkoutUrl);
    } catch (err) {
      toast({
        title: 'Could not start checkout',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
      setCheckoutBusy(false);
    }
  };

  return (
    <Card className={className} data-testid="upgrade-gate">
      <CardHeader className="pb-3">
        <div className="flex items-center gap-2">
          <Lock className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <CardTitle className="text-base">Upgrade to Pro</CardTitle>
        </div>
        <CardDescription>{reason || FEATURE_COPY[feature]}</CardDescription>
      </CardHeader>
      <CardContent>
        {canManageBilling ? (
          <Button onClick={() => void handleUpgrade()} disabled={checkoutBusy}>
            {checkoutBusy ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                Starting checkout...
              </>
            ) : (
              'Upgrade to Pro'
            )}
          </Button>
        ) : (
          <div className="flex items-start gap-2 text-sm text-muted-foreground">
            <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
            <p>Only a workspace owner or admin can upgrade the plan. Ask them to upgrade to Pro.</p>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default UpgradeGate;
