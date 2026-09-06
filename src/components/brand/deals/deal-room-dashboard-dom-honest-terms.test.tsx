/**
 * F-0669 round 3 — the DOM surface.
 *
 * DealRoomDashboard's "View Proposal" dialog (deal-room-dashboard.tsx:993-1042), shown only in
 * demo/mock mode (`!isApiLive()`), rendered a hardcoded "Terms" list the brand reads as the
 * actual deal terms:
 *
 *   :1033  Usage Rights: 3 months from delivery   (same no-source fabrication sitting right
 *          next to the named :1035 line — fixed alongside it rather than left as a partial fix)
 *   :1035  Revisions: Up to 2 rounds per deliverable
 *   (Exclusivity: 30 days (no competing brands) — same class, same block — fixed too)
 *
 * This dialog has no source at all for usage rights, exclusivity, or a revision cap — `Deal`
 * (src/lib/api.ts) carries none of those fields. This spec renders the real dashboard, drives
 * the real "View Proposal" click path, and asserts on the rendered dialog DOM — plus that the
 * dialog's genuinely real per-deal fields (budget, timeline) still render, so this isn't a
 * "delete everything" over-fix.
 *
 * Run: npx vitest run src/components/brand/deals/deal-room-dashboard-dom-honest-terms.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    // Demo/mock mode: dealRooms comes from the component's own internal mockDealRooms
    // fixture, never a network call — see deal-room-dashboard.tsx `dealRooms = isApiLive() ?
    // liveDeals : mockDealRooms`.
    isApiLive: () => false,
  };
});

const { DealRoomDashboard } = await import('./deal-room-dashboard');

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/brand/deals']}>
      <DealRoomDashboard />
    </MemoryRouter>,
  );
}

describe('DealRoomDashboard — "View Proposal" Terms block (F-0669 round 3, DOM surface)', () => {
  it('never renders the fabricated Usage Rights / Exclusivity / Revisions terms, while the real per-deal budget and timeline still render', async () => {
    const user = userEvent.setup({ delay: null });
    renderDashboard();

    // mockDealRooms[0]: Priya Sharma / Summer Collection Launch, budget 50000, timeline '30 days'.
    await user.click(await screen.findByText('Priya Sharma'));
    await user.click(await screen.findByRole('button', { name: /view proposal/i }));

    const dialog = await screen.findByRole('dialog');
    const dialogText = dialog.textContent ?? '';
    // The underlying page (behind the dialog) has its own real Budget/Timeline cards showing
    // the same values, so every assertion below is scoped to the dialog to avoid ambiguous
    // duplicate-element matches — not to dodge the underlying page.
    const dom = within(dialog);

    expect(dialogText).not.toContain('3 months from delivery');
    expect(dialogText).not.toContain('30 days (no competing brands)');
    expect(dialogText).not.toContain('Up to 2 rounds per deliverable');

    expect(dom.getByText('Usage Rights: Not specified')).toBeInTheDocument();
    expect(dom.getByText('Exclusivity: Not specified')).toBeInTheDocument();
    expect(dom.getByText('Revisions: Not specified')).toBeInTheDocument();

    // Positive control: this dialog's genuinely real per-deal data is untouched.
    expect(dom.getByText('₹50K')).toBeInTheDocument();
    expect(dom.getByText('30 days')).toBeInTheDocument(); // Timeline card, not the exclusivity fabrication
  });
});
