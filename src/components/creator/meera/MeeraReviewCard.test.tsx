/**
 * PHASE-C-SPEC.md §2/§4 — MeeraReviewCard: renders every field, Copy writes the exact original
 * text, and the "What should I do first?" action only ever prefills the composer.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraReviewCard } from './MeeraReviewCard';
import type { ParsedMeeraReview } from '@/lib/meera-result-cards';

const REVIEW: ParsedMeeraReview = {
  working: 'Your reels get strong watch time in the first 3 seconds',
  notWorking: 'Your bio has no clear call to action',
  nextSteps: [
    'Add a link-in-bio call to action today',
    'Post one reel this week using the saffron hook template',
    'Reply to your last 5 comments to lift engagement',
  ],
};

const RAW_TEXT = [
  'REVIEW',
  'Working: Your reels get strong watch time in the first 3 seconds',
  'Not working: Your bio has no clear call to action',
  'Next 1: Add a link-in-bio call to action today',
  'Next 2: Post one reel this week using the saffron hook template',
  'Next 3: Reply to your last 5 comments to lift engagement',
].join('\n');

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MeeraReviewCard', () => {
  it('renders Working, Not working, and all 3 next steps as a numbered list', () => {
    render(<MeeraReviewCard review={REVIEW} rawText={RAW_TEXT} language="en-IN" onPrefill={vi.fn()} />);

    expect(screen.getByTestId('review-card-working')).toHaveTextContent(REVIEW.working);
    expect(screen.getByTestId('review-card-not-working')).toHaveTextContent(REVIEW.notWorking);

    const steps = screen.getAllByTestId('review-card-next-step');
    expect(steps).toHaveLength(3);
    expect(steps[0]).toHaveTextContent(REVIEW.nextSteps[0]);
    expect(steps[1]).toHaveTextContent(REVIEW.nextSteps[1]);
    expect(steps[2]).toHaveTextContent(REVIEW.nextSteps[2]);
  });

  it('Copy writes the exact original text to the clipboard, then shows Copied for a moment', async () => {
    // `userEvent.setup()` installs its own clipboard stub, so the mock MUST be defined after
    // setup() runs, or setup() silently replaces it and every call below lands on user-event's
    // own stub instead of this spy.
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    render(<MeeraReviewCard review={REVIEW} rawText={RAW_TEXT} language="en-IN" onPrefill={vi.fn()} />);
    await user.click(screen.getByTestId('review-card-copy'));

    expect(writeText).toHaveBeenCalledWith(RAW_TEXT);
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('"What should I do first?" prefills the composer and never sends anything', async () => {
    const onPrefill = vi.fn();
    const sendTurnMock = vi.fn();
    const user = userEvent.setup();

    render(<MeeraReviewCard review={REVIEW} rawText={RAW_TEXT} language="en-IN" onPrefill={onPrefill} />);
    await user.click(screen.getByTestId('review-card-what-first'));

    expect(onPrefill).toHaveBeenCalledWith('What should I do first?');
    expect(onPrefill).toHaveBeenCalledTimes(1);
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('has no Save button — this phase has no backend store to save to', () => {
    render(<MeeraReviewCard review={REVIEW} rawText={RAW_TEXT} language="en-IN" onPrefill={vi.fn()} />);
    expect(screen.queryByText(/save/i)).toBeNull();
  });
});
