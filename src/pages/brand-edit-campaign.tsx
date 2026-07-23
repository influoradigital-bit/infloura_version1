import * as React from 'react';
import { useParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';

import { api, ApiError } from '@/lib/api';
import type { CampaignType } from '@/lib/types';
import { CampaignForm } from '@/components/brand/campaigns/campaign-form';
import BrandNewHypeCampaignPage from '@/pages/brand-new-hype-campaign';

/**
 * Single edit entrypoint for /brand/campaigns/:id/edit — branches by the
 * loaded campaign's campaignType rather than adding a dedicated
 * `/edit/hype` route (Meera completion-flow build, 2026-07-23):
 * STANDARD (and OPEN/DIRECT) keep the existing step wizard; HYPE renders
 * the dedicated Hype form in resume mode so the human sets rate/slots and
 * explicitly launches. This means the create_campaign chat card's deep link
 * (`ToolResultRenderer.tsx`) can point here unconditionally for both types —
 * it doesn't need to know the campaign's type itself.
 *
 * This does one extra lightweight fetch (to learn the type) beyond whichever
 * child form re-fetches the full record — acceptable on an edit page, and
 * keeps CampaignForm/BrandNewHypeCampaignPage independent of each other.
 */
export default function BrandEditCampaignPage() {
  const { id } = useParams<{ id: string }>();
  // undefined = resolving type, null = resolved to "not Hype" (default/fallback)
  const [campaignType, setCampaignType] = React.useState<CampaignType | null | undefined>(undefined);
  const [typeLoadError, setTypeLoadError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!id) {
      setCampaignType(null);
      return;
    }
    let cancelled = false;
    setCampaignType(undefined);
    setTypeLoadError(null);
    (async () => {
      try {
        const c = await api.campaigns.get(id);
        if (cancelled) return;
        setCampaignType(c?.campaignType ?? null);
      } catch (e) {
        if (cancelled) return;
        // Fall back to the standard wizard on a failed type check — it has its
        // own fetch + load-error handling and will surface the real problem.
        setTypeLoadError(e instanceof ApiError ? e.message : 'Could not load this campaign.');
        setCampaignType(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  if (campaignType === undefined) {
    return (
      <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 p-6 text-center">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          Loading campaign…
        </div>
      </div>
    );
  }

  if (typeLoadError) {
    // Non-fatal: log context only, then hand off to CampaignForm's own fetch
    // (below) which will show the real error state.
    console.warn('[BrandEditCampaignPage] campaignType lookup failed, defaulting to standard wizard:', typeLoadError);
  }

  if (campaignType === 'HYPE') {
    return <BrandNewHypeCampaignPage campaignId={id} />;
  }

  return <CampaignForm campaignId={id} />;
}
