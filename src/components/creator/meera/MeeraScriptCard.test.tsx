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

  it('shows Stress/Pause on a beat that has them, and omits the row on beats that do not', () => {
    const withEmphasis: ParsedMeeraScript = {
      ...SCRIPT,
      beats: [
        { ...SCRIPT.beats[0], stress: 'even real', pause: 'after "saffron", or none' },
        SCRIPT.beats[1],
        SCRIPT.beats[2],
      ],
    };
    render(<MeeraScriptCard script={withEmphasis} rawText={RAW_TEXT} language="en-IN" />);

    const emphasisRows = screen.getAllByTestId('script-card-beat-emphasis');
    expect(emphasisRows).toHaveLength(1);
    expect(emphasisRows[0]).toHaveTextContent('even real');
    expect(emphasisRows[0]).toHaveTextContent('after "saffron", or none');
  });

  it('renders no Stress/Pause row at all when no beat carries the markers', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);
    expect(screen.queryByTestId('script-card-beat-emphasis')).toBeNull();
  });
});

describe('MeeraScriptCard — the optional Set-up line (persona 2026-09-25)', () => {
  it('shows Set-up between Action and Success looks like when the script has it', () => {
    const setup =
      'sit facing the window, light on your left; phone at eye height, an arm away, 1x lens; lock focus and exposure; walk to the counter for the last shot';
    render(<MeeraScriptCard script={{ ...SCRIPT, setup }} rawText={RAW_TEXT} language="en-IN" />);

    const row = screen.getByTestId('script-card-setup');
    expect(row).toHaveTextContent('Set-up:');
    expect(row).toHaveTextContent(setup);
    // Order on the card follows the persona's layout: Action, then Set-up, then Success looks like.
    const action = screen.getByTestId('script-card-action');
    const success = screen.getByTestId('script-card-success');
    expect(action.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(row.compareDocumentPosition(success) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('has no Set-up row for an older script without that line', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);
    expect(screen.queryByTestId('script-card-setup')).toBeNull();
  });
});

describe('MeeraScriptCard — "Check my set-up" per shot (photo check inside Meera)', () => {
  it('renders no set-up button when onCheckShot is not passed', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);
    expect(screen.queryByTestId('script-card-check-shot')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Check my set-up' })).toBeNull();
  });

  it('renders one 44px button per beat, and each calls onCheckShot with its own beat index', async () => {
    const user = userEvent.setup();
    const onCheckShot = vi.fn();
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onCheckShot={onCheckShot} />);

    const beats = screen.getAllByTestId('script-card-beat');
    const buttons = screen.getAllByRole('button', { name: 'Check my set-up' });
    expect(buttons).toHaveLength(SCRIPT.beats.length);
    buttons.forEach((button, i) => {
      // Inside its own beat row, at least 44px tall, described by that beat's timing and shot.
      expect(beats[i]).toContainElement(button);
      expect(button.className).toContain('min-h-11');
      expect(button).toHaveAccessibleDescription(`${SCRIPT.beats[i].from}-${SCRIPT.beats[i].to}s · ${SCRIPT.beats[i].shot}`);
    });

    await user.click(buttons[2]);
    await user.click(buttons[0]);
    expect(onCheckShot.mock.calls).toEqual([[2], [0]]);
  });

  it('shows every set-up button as disabled while the chat is busy, and a tap does nothing', async () => {
    const user = userEvent.setup();
    const onCheckShot = vi.fn();
    const { rerender } = render(
      <MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onCheckShot={onCheckShot} checkShotDisabled />,
    );
    const buttons = screen.getAllByTestId('script-card-check-shot');
    for (const button of buttons) expect(button).toBeDisabled();
    await user.click(buttons[0]);
    expect(onCheckShot).not.toHaveBeenCalled();

    rerender(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onCheckShot={onCheckShot} />);
    for (const button of screen.getAllByTestId('script-card-check-shot')) expect(button).toBeEnabled();
  });

  it('says "सेट-अप चेक करें" for a Hindi creator (Devanagari, like the rest of the card)', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="hi-IN" onCheckShot={() => {}} />);
    expect(screen.getAllByRole('button', { name: 'सेट-अप चेक करें' })).toHaveLength(SCRIPT.beats.length);
    expect(screen.queryByRole('button', { name: 'Check my set-up' })).toBeNull();
  });

  it('never says Co-pilot or Copilot on the card', () => {
    const { container } = render(
      <MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onCheckShot={() => {}} />,
    );
    expect(container.textContent ?? '').not.toMatch(/co-?pilot/i);
  });
});
