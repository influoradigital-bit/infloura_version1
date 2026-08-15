import * as React from 'react';

export interface FundEscrowStatusProps {
  /** true while the brand's fundable campaigns are still loading */
  loading: boolean;
  /** campaigns the brand can still fund (after filtering out already-locked ones) */
  selectableCount: number;
  /** total active campaigns — distinguishes "none exist" from "all already funded" */
  totalCount: number;
  /** the campaign id currently chosen (the FundEscrowButton is shown), or '' */
  selectedId: string;
  /** the chosen campaign's title, if resolvable */
  selectedTitle?: string;
}

/**
 * F-0131 (no-live-region, money-path): the single source of truth for what a screen reader should
 * hear as the Fund Campaign Escrow card changes state. Pure so it can be unit-pinned.
 */
export function fundEscrowAnnouncement(p: FundEscrowStatusProps): string {
  if (p.loading) return 'Loading your campaigns.';
  if (p.selectableCount === 0) {
    return p.totalCount === 0
      ? 'No active campaigns to fund right now.'
      : 'Every active campaign already has funds locked.';
  }
  if (p.selectedId) {
    return `Ready to lock funds for ${p.selectedTitle ?? 'the selected campaign'}. Continue to fund escrow.`;
  }
  return 'Select a campaign to fund.';
}

/**
 * A visually-hidden polite live region for the Fund Campaign Escrow card. Screen readers announce
 * the card's state transitions — loading → ready/empty and, critically, the FundEscrowButton's
 * appearance after a campaign is picked. That button mounting is an otherwise-silent DOM insertion
 * (its own `aria-live` sits on the element being inserted, so it cannot announce its own arrival),
 * and the loading/empty states were plain `<p>`s with no role. This region, mounted alongside the
 * card, catches every transition without changing the visible layout.
 */
export function FundEscrowStatus(props: FundEscrowStatusProps) {
  return (
    <div role="status" aria-live="polite" className="sr-only">
      {fundEscrowAnnouncement(props)}
    </div>
  );
}
