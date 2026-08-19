import * as React from 'react';

import { api } from '@/lib/api';
import { FirstRunChecklist, type FirstRunStep } from '@/components/shared/FirstRunChecklist';
import type { CreatorDealsPageRow } from '@/lib/creator-deal-mappers';

/**
 * The creator half of the first-run ladder — the mirror of BrandFirstRunChecklist, mapping the
 * flow published on `/how-it-works/creators` onto real account state.
 *
 * Everything except the Instagram connection is derived from data the dashboard has already
 * fetched. The connection is re-verified against the backend rather than read from the
 * localStorage mirror: per `metaOAuth.getLocalConnectionState`'s contract (CR-107) that mirror
 * never expires and does not notice a revoke or disconnect, so trusting it here would tick a step
 * for a creator whose Instagram is no longer connected.
 */

/** A deal at or beyond CONTRACTED — i.e. the contract exists, so the work stage has been reached. */
const CONTRACTED_OR_BEYOND: ReadonlySet<CreatorDealsPageRow['status']> = new Set([
  'contracted',
  'in_progress',
  'review',
  'completed',
  // A disputed deal was necessarily contracted first — the step happened, whatever went wrong
  // afterwards. Omitting it would un-tick a completed step at the worst possible moment.
  'disputed',
]);

interface CreatorFirstRunChecklistProps {
  deals: CreatorDealsPageRow[];
  /** Public portfolio handle, or `null` when there is none / the portfolio call failed. */
  username: string | null;
  /** `false` while the dashboard load is in flight or has failed. */
  ready: boolean;
}

export function CreatorFirstRunChecklist({
  deals,
  username,
  ready,
}: CreatorFirstRunChecklistProps) {
  // `null` until the backend answers — a failed status check leaves the step undeterminable
  // rather than telling a connected creator to connect again, or an unconnected one they are done.
  const [instagramConnected, setInstagramConnected] = React.useState<boolean | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    api.metaOAuth
      .status()
      .then((res) => {
        if (cancelled) return;
        setInstagramConnected(res.connected);
      })
      .catch(() => {
        // Left as null. The dashboard reports its own load failures; a guidance widget adding a
        // second error surface for a non-critical signal is noise.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const steps: FirstRunStep[] = [
    {
      id: 'profile',
      title: 'Publish your profile and rate card',
      subtitle: 'Your rates, past work and audience — this is the page brands search and shortlist.',
      href: '/creator/profile',
      cta: 'Set up',
      done: ready ? username !== null : null,
    },
    {
      id: 'instagram',
      title: 'Connect Instagram',
      subtitle: 'Verified reach and engagement. Brands filter on it, so unconnected profiles rank lower.',
      href: '/creator/settings',
      cta: 'Connect',
      done: instagramConnected,
    },
    {
      id: 'firstdeal',
      title: 'Apply to a campaign or accept a Hype slot',
      subtitle: 'Browse live campaigns and apply, or take a Hype slot in one tap — no negotiation.',
      href: '/creator/campaigns',
      cta: 'Browse',
      done: ready ? deals.length > 0 : null,
    },
    {
      id: 'deliver',
      title: 'Sign the contract, then submit your work',
      subtitle: 'Your payment is locked before you start. Submit the deliverable and the brand approves.',
      href: '/creator/deals',
      cta: 'Open Deals',
      done: ready ? deals.some((d) => CONTRACTED_OR_BEYOND.has(d.status)) : null,
    },
  ];

  return (
    <FirstRunChecklist
      title="Get your first paid deal"
      subtitle="Four steps from an empty profile to money in your wallet. We'll tick these off as you go."
      steps={steps}
      storageKey="creator_first_run_checklist_dismissed"
      flowHref="/creator/how-it-works"
      walkthroughRole="creator"
    />
  );
}

export default CreatorFirstRunChecklist;
