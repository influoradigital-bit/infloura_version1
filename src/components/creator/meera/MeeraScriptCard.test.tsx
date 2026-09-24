/**
 * MeeraScriptCard: renders every field of the rich-format `ParsedMeeraScript`, and Copy writes the
 * exact original reply text (never a re-serialized version of the parsed fields).
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraScriptCard } from './MeeraScriptCard';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';

const SCRIPT: ParsedMeeraScript = {
  idea: '3 saffron mistakes to avoid',
  plan: 'for new saffron buyers; curiosity; grow followers; 30s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
  action: 'hold up the saffron box and speak to camera',
  successLooksLike: 'reel gets watched to the end and shared to a friend',
  beats: [
    {
      from: 0,
      to: 10,
      shot: 'Close-up on your face - hold up the saffron box',
      say: 'Is your saffron even real?',
      onScreen: 'REAL vs FAKE',
    },
    {
      from: 10,
      to: 20,
      shot: 'Overhead on your hands - do the warm-water color test',
      say: 'Watch what happens in water',
      onScreen: 'THE WATER TEST',
    },
    {
      from: 20,
      to: 30,
      shot: 'Close-up on the certificate - hold it next to the box',
      say: 'This is what real Kashmiri saffron looks like',
      onScreen: 'GI CERTIFIED',
    },
  ],
  caption: 'Would you have spotted the fake one? Link in bio for real Kashmiri saffron. #saffron #kashmir',
  beforeYouShoot: [
    'Charge your phone to 100%',
    'Wipe the counter clean',
    'Keep the certificate within reach',
  ],
  whyThisWorks: 'Problem-agitate-solve structure, curiosity-gap hook template',
  followUp: 'Which language should the voice-over be in — Hindi or English?',
};

const RAW_TEXT = [
  'Idea: 3 saffron mistakes to avoid',
  'Plan: for new saffron buyers; curiosity; grow followers; 30s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
  'Action: hold up the saffron box and speak to camera',
  'Success looks like: reel gets watched to the end and shared to a friend',
  'Script:',
  '0-10s. Shot: Close-up on your face - hold up the saffron box. Say: "Is your saffron even real?". On screen: REAL vs FAKE',
  '10-20s. Shot: Overhead on your hands - do the warm-water color test. Say: "Watch what happens in water". On screen: THE WATER TEST',
  '20-30s. Shot: Close-up on the certificate - hold it next to the box. Say: "This is what real Kashmiri saffron looks like". On screen: GI CERTIFIED',
  'Caption: Would you have spotted the fake one? Link in bio for real Kashmiri saffron. #saffron #kashmir',
  'Before you shoot: 1) Charge your phone to 100% 2) Wipe the counter clean 3) Keep the certificate within reach',
  'Why this works: Problem-agitate-solve structure, curiosity-gap hook template',
  'Which language should the voice-over be in — Hindi or English?',
].join('\n');

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MeeraScriptCard', () => {
  it('renders Idea, Plan, Action, Success looks like, every beat, Caption, Before you shoot, Why this works and the follow-up', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);

    expect(screen.getByTestId('script-card-idea')).toHaveTextContent(SCRIPT.idea);
    expect(screen.getByTestId('script-card-plan')).toHaveTextContent(SCRIPT.plan);
    expect(screen.getByTestId('script-card-action')).toHaveTextContent(SCRIPT.action);
    expect(screen.getByTestId('script-card-success')).toHaveTextContent(SCRIPT.successLooksLike!);

    const beatRows = screen.getAllByTestId('script-card-beat');
    expect(beatRows).toHaveLength(3);
    expect(beatRows[0]).toHaveTextContent('0-10s');
    expect(beatRows[0]).toHaveTextContent(SCRIPT.beats[0].shot);
    expect(beatRows[0]).toHaveTextContent(SCRIPT.beats[0].say);
    expect(beatRows[0]).toHaveTextContent(SCRIPT.beats[0].onScreen);
    expect(beatRows[2]).toHaveTextContent('20-30s');

    expect(screen.getByTestId('script-card-caption')).toHaveTextContent(SCRIPT.caption);

    const beforeItems = screen.getAllByTestId('script-card-before-item');
    expect(beforeItems).toHaveLength(3);
    expect(beforeItems[0]).toHaveTextContent(SCRIPT.beforeYouShoot[0]);
    expect(beforeItems[2]).toHaveTextContent(SCRIPT.beforeYouShoot[2]);

    expect(screen.getByTestId('script-card-why')).toHaveTextContent(SCRIPT.whyThisWorks);
    expect(screen.getByTestId('script-card-follow-up')).toHaveTextContent(SCRIPT.followUp!);
  });

  it('omits Success looks like and the follow-up line when the script has neither', () => {
    render(
      <MeeraScriptCard
        script={{ ...SCRIPT, successLooksLike: undefined, followUp: undefined }}
        rawText={RAW_TEXT}
        language="en-IN"
      />,
    );
    expect(screen.queryByTestId('script-card-success')).toBeNull();
    expect(screen.queryByTestId('script-card-follow-up')).toBeNull();
  });

  it('Copy writes the exact original text to the clipboard, then shows Copied for a moment', async () => {
    // `userEvent.setup()` installs its own clipboard stub, so the mock MUST be defined after
    // setup() runs, or setup() silently replaces it and every call below lands on user-event's
    // own stub instead of this spy.
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);
    await user.click(screen.getByTestId('script-card-copy'));

    expect(writeText).toHaveBeenCalledWith(RAW_TEXT);
    // The button shows Copied, and a polite live region announces it to screen readers.
    await waitFor(() => expect(screen.getByTestId('script-card-copy')).toHaveTextContent('Copied'));
    expect(screen.getByText('Copied', { selector: '[aria-live]' })).toBeInTheDocument();
    // The button's accessible name stays "Copy" throughout.
    expect(screen.getByTestId('script-card-copy')).toHaveAccessibleName('Copy');
  });

  it('has no Save button — this phase has no backend store to save to', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);
    expect(screen.queryByText(/save/i)).toBeNull();
  });
});
