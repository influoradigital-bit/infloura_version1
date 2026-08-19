import * as React from 'react';

import { api } from '@/lib/api';
import { FirstRunChecklist, type FirstRunStep } from '@/components/shared/FirstRunChecklist';
import { countAtOrBeyond, type PipelineStageLabel } from '@/lib/brand-pipeline-progress';

/**
 * The brand half of the first-run ladder — maps the six-step flow already published on
 * `/how-it-works/brands` onto real account state, so a new brand sees the order of operations
 * instead of five independent zero-states.
 *
 * Every `done` is derived, never stored. Steps 2–5 read the pipeline the dashboard already
 * fetched; step 1 needs a campaign count, which no other card on this page needs, so this
 * component fetches that one signal itself rather than making the dashboard's critical-path
 * load wait on it.
 */

interface BrandFirstRunChecklistProps {
  pipeline: Array<{ stage: string; count: number }>;
  /** `false` while the pipeline fetch is loading or has failed — every pipeline-derived step
   *  then reports `null` (undeterminable) rather than a fabricated "not done yet". */
  pipelineReady: boolean;
  escrowLocked: number;
  walletReady: boolean;
}

export function BrandFirstRunChecklist({
  pipeline,
  pipelineReady,
  escrowLocked,
  walletReady,
}: BrandFirstRunChecklistProps) {
  // `null` until the count is known — including on failure, so a campaign the brand really did
  // create is never crossed out on the strength of a request that did not come back.
  const [campaignCount, setCampaignCount] = React.useState<number | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    // `Promise.resolve().then(...)` rather than a bare call: it converts a SYNCHRONOUS throw
    // into a rejection the `.catch` below can absorb. Without it, an `api.campaigns` that is
    // missing — renamed, tree-shaken, or absent from a partial mock — throws during the effect
    // and takes the entire brand dashboard down with it. A guidance widget must never be able
    // to white-screen the page it is trying to help on.
    //
    // limit: 1 — only `meta.total` is used; there is no reason to transfer a page of campaigns
    // to answer "are there any?".
    Promise.resolve()
      .then(() => api.campaigns.list({ page: 1, limit: 1 }))
      .then((res) => {
        if (cancelled) return;
        setCampaignCount(res.meta.total ?? res.campaigns.length);
      })
      .catch(() => {
        // Swallowed on purpose: the dashboard already surfaces its own load failures, and a
        // second toast for a guidance widget would be noise. The step stays undeterminable —
        // never silently "done", which would cross out a campaign the brand has not created.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const fromPipeline = (stage: PipelineStageLabel): boolean | null =>
    pipelineReady ? countAtOrBeyond(pipeline, stage) > 0 : null;

  // Contracted-or-beyond proves the contract was signed; escrow proves it was funded. Either is
  // sufficient evidence the step happened — a deal can be funded before the pipeline refreshes.
  const contractedOrFunded = (): boolean | null => {
    const byStage = fromPipeline('Contracted');
    if (byStage === true) return true;
    if (walletReady && escrowLocked > 0) return true;
    // Neither source proved it; if either source is still unknown, so is the answer.
    return byStage === null || !walletReady ? null : false;
  };

  const steps: FirstRunStep[] = [
    {
      id: 'campaign',
      title: 'Create your first campaign',
      subtitle: 'Set the brief, budget and timeline. Creators apply to what you publish.',
      href: '/brand/campaigns/new',
      cta: 'Create',
      done: campaignCount === null ? null : campaignCount > 0,
    },
    {
      id: 'discover',
      title: 'Find creators and send a proposal',
      subtitle: 'Search the verified Indian creator network, or wait for applicants to come to you.',
      href: '/brand/discover',
      cta: 'Discover',
      done: fromPipeline('Outreach'),
    },
    {
      id: 'negotiate',
      title: 'Agree the terms in the Deal Room',
      subtitle: 'Rate, deliverables and dates get settled in one thread — no lost DMs.',
      href: '/brand/chat',
      cta: 'Open Deals',
      done: fromPipeline('Negotiating'),
    },
    {
      id: 'contract',
      title: 'Sign the contract and fund the deal',
      subtitle: 'The contract generates itself. Your money is held safely until you approve the work.',
      href: '/brand/contracts',
      cta: 'Contracts',
      done: contractedOrFunded(),
    },
    {
      id: 'approve',
      title: 'Approve the work — the payment releases',
      subtitle: 'Review what the creator submits. Approving releases the payment automatically.',
      href: '/brand/pipeline',
      cta: 'Pipeline',
      done: fromPipeline('Settled'),
    },
  ];

  return (
    <FirstRunChecklist
      title="Get your first campaign live"
      subtitle="Five steps from brief to a posted reel. We'll tick these off as you go."
      steps={steps}
      storageKey="brand_first_run_checklist_dismissed"
      flowHref="/brand/how-it-works"
      walkthroughRole="brand"
    />
  );
}

export default BrandFirstRunChecklist;
