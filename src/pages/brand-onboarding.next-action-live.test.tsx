/**
 * F-0341 — the "You're in" step's next-action cards were dead controls.
 *
 * Step 3 of brand onboarding says "Pick where to go first:" and then renders two cards —
 * *Create your first campaign* and *Discover creators*. Both shipped as plain `<div>`s: no
 * `onClick`, no `href`, no `navigate`, and no handler passed at either call site. The single
 * screen in the product that asks a brand-new user to choose a destination had nothing clickable
 * on it except the button that ignores the choice. This is the last guidance surface before the
 * dashboard the user then reports being confused by.
 *
 * What this pins:
 *   1. Each card is a real, enabled control.
 *   2. Picking one asks to complete onboarding AND names that card's destination — a card that
 *      completes onboarding but still dumps the user on the dashboard is the same defect wearing
 *      a button.
 *
 * `YoureInStep` is driven directly rather than through the page: reaching it requires clearing
 * `CompanyDetailsStep`'s required fields, and a test that has to fill a form to reach the control
 * under examination breaks for reasons that have nothing to do with this defect.
 *
 * Run: npx vitest run src/pages/brand-onboarding.next-action-live.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { YoureInStep } from './brand-onboarding';

const onComplete = vi.fn();

function renderStep(isSubmitting = false) {
  render(<YoureInStep firstName="Tejas" onComplete={onComplete} isSubmitting={isSubmitting} />);
  return userEvent.setup();
}

/** The card the copy tells the user to pick, as the control it must be. */
function card(label: string): HTMLButtonElement | null {
  return screen.getByText(label).closest('button');
}

beforeEach(() => {
  onComplete.mockClear();
});

describe('brand onboarding — terminal step next actions (F-0341)', () => {
  it('still asks the user to pick a destination', () => {
    renderStep();
    // If this copy ever goes away the rest of these assertions stop being about a dead control,
    // so it is pinned rather than assumed.
    expect(screen.getByText(/Pick where to go first/i)).toBeInTheDocument();
  });

  it('renders "Create your first campaign" as an enabled control, not an inert div', () => {
    renderStep();
    expect(card('Create your first campaign')).not.toBeNull();
    expect(card('Create your first campaign')).toBeEnabled();
  });

  it('renders "Discover creators" as an enabled control, not an inert div', () => {
    renderStep();
    expect(card('Discover creators')).not.toBeNull();
    expect(card('Discover creators')).toBeEnabled();
  });

  it('sends the user to the campaign builder when the campaign card is picked', async () => {
    const user = renderStep();
    await user.click(card('Create your first campaign')!);
    expect(onComplete).toHaveBeenCalledWith('/brand/campaigns/new');
  });

  it('sends the user to discovery when the creators card is picked', async () => {
    const user = renderStep();
    await user.click(card('Discover creators')!);
    expect(onComplete).toHaveBeenCalledWith('/brand/discover');
  });

  it('passes a destination string, never a click event, to onComplete', async () => {
    // `onClick={onComplete}` type-checks against a handler and would hand navigate() the
    // MouseEvent — the exact regression that would silently break every card at once.
    const user = renderStep();
    await user.click(card('Discover creators')!);
    expect(typeof onComplete.mock.calls[0][0]).toBe('string');
  });

  it('disables every card while the completion request is in flight', () => {
    renderStep(true);
    expect(card('Create your first campaign')).toBeDisabled();
    expect(card('Discover creators')).toBeDisabled();
  });
});
