/**
 * T-FRONTEND-REWORK-0905 W1 — regression coverage for the landing hero's
 * WebGL replacement.
 *
 * Two things matter for a prerendered marketing page:
 *   1. All five Deal Room stages are in the DOM at rest, regardless of the
 *      `prefers-reduced-motion` branch — that is what a headless, GPU-less
 *      prerender snapshot captures (constraint 2 in the W1 spec).
 *   2. `useReducedMotion()` renders every stage statically and equally
 *      weighted, never a frozen mid-cycle frame (constraint 5).
 *
 * Run: npx vitest run src/components/site/DealRoomHeroThread.test.tsx
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

// jsdom does not implement `window.matchMedia`, which `useReducedMotion` (framer-motion) calls
// at mount. `reducedMotionMatches` is mutated per test before rendering so both branches are
// covered without re-mocking the module.
let reducedMotionMatches = false;

beforeEach(() => {
  reducedMotionMatches = false;
  window.matchMedia = (query: string) => ({
    matches: reducedMotionMatches,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
});

import { DealRoomHeroThread } from './DealRoomHeroThread';

const STAGE_TITLES = [
  'Proposal sent',
  'Counter-offer',
  'Contract signed',
  'Deliverable approved',
  'Payment released',
];

describe('DealRoomHeroThread', () => {
  it('renders all five Deal Room stages at rest (motion enabled)', () => {
    reducedMotionMatches = false;
    render(<DealRoomHeroThread />);

    for (const title of STAGE_TITLES) {
      expect(screen.getByText(title)).toBeInTheDocument();
    }
    // The card is explicitly labelled illustrative — copy constraint from the W1 spec.
    expect(screen.getByText('Example')).toBeInTheDocument();
    expect(
      screen.getByText(/illustrative deal/i),
    ).toBeInTheDocument();
  });

  it('renders all five stages statically when prefers-reduced-motion is set', () => {
    reducedMotionMatches = true;
    render(<DealRoomHeroThread />);

    for (const title of STAGE_TITLES) {
      expect(screen.getByText(title)).toBeInTheDocument();
    }
  });

  it('never renders the retired "escrow" wording', () => {
    reducedMotionMatches = false;
    const { container } = render(<DealRoomHeroThread />);
    expect(container.textContent).not.toMatch(/escrow/i);
  });

  it('mounts no canvas / WebGL surface', () => {
    reducedMotionMatches = false;
    const { container } = render(<DealRoomHeroThread />);
    expect(container.querySelectorAll('canvas')).toHaveLength(0);
  });
});
