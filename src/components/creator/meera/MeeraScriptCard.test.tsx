/**
 * PHASE-C-SPEC.md §2/§4 — MeeraScriptCard: renders every field, Copy writes the exact original
 * text (never a re-serialized version of the parsed fields), and "Another hook" only ever
 * prefills the composer — it must never itself trigger a send.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraScriptCard } from './MeeraScriptCard';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';

const SCRIPT: ParsedMeeraScript = {
  title: '3 saffron mistakes to avoid',
  length: 30,
  hook: 'Stop buying saffron until you watch this',
  beats: [
    { from: 0, to: 10, text: 'Show the box, ask "is your saffron real?"' },
    { from: 10, to: 20, text: 'Do the warm-water color test on camera' },
    { from: 20, to: 30, text: 'Show the certificate and say why it matters' },
  ],
  cta: 'Link in bio for real Kashmiri saffron',
  why: 'Problem-agitate-solve structure, curiosity-gap hook template',
};

const RAW_TEXT = [
  'SCRIPT',
  'Title: 3 saffron mistakes to avoid',
  'Length: 30s',
  'Hook: Stop buying saffron until you watch this',
  '0-10s: Show the box, ask "is your saffron real?"',
  '10-20s: Do the warm-water color test on camera',
  '20-30s: Show the certificate and say why it matters',
  'CTA: Link in bio for real Kashmiri saffron',
  'Why: Problem-agitate-solve structure, curiosity-gap hook template',
].join('\n');

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MeeraScriptCard', () => {
  it('renders the title, length, hook, every beat, CTA and Why', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onPrefill={vi.fn()} />);

    expect(screen.getByTestId('script-card-title')).toHaveTextContent(SCRIPT.title);
    expect(screen.getByTestId('script-card-length')).toHaveTextContent('30s');
    expect(screen.getByTestId('script-card-hook')).toHaveTextContent(SCRIPT.hook);

    const beatRows = screen.getAllByTestId('script-card-beat');
    expect(beatRows).toHaveLength(3);
    expect(beatRows[0]).toHaveTextContent('0-10s');
    expect(beatRows[0]).toHaveTextContent(SCRIPT.beats[0].text);
    expect(beatRows[2]).toHaveTextContent('20-30s');

    expect(screen.getByTestId('script-card-cta')).toHaveTextContent(SCRIPT.cta);
    expect(screen.getByTestId('script-card-why')).toHaveTextContent(SCRIPT.why!);
  });

  it('omits the Why line entirely when the script has none', () => {
    render(
      <MeeraScriptCard
        script={{ ...SCRIPT, why: undefined }}
        rawText={RAW_TEXT}
        language="en-IN"
        onPrefill={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('script-card-why')).toBeNull();
  });

  it('Copy writes the exact original text to the clipboard, then shows Copied for a moment', async () => {
    // `userEvent.setup()` installs its own clipboard stub, so the mock MUST be defined after
    // setup() runs, or setup() silently replaces it and every call below lands on user-event's
    // own stub instead of this spy.
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onPrefill={vi.fn()} />);
    await user.click(screen.getByTestId('script-card-copy'));

    expect(writeText).toHaveBeenCalledWith(RAW_TEXT);
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('"Another hook" prefills the composer with the fixed prompt and never sends anything', async () => {
    const onPrefill = vi.fn();
    const sendTurnMock = vi.fn();
    const user = userEvent.setup();

    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onPrefill={onPrefill} />);
    await user.click(screen.getByTestId('script-card-another-hook'));

    expect(onPrefill).toHaveBeenCalledWith('Give me another hook for this script');
    expect(onPrefill).toHaveBeenCalledTimes(1);
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('shows a short Hindi LABEL but prefills the full Hindi sentence', async () => {
    const onPrefill = vi.fn();
    const user = userEvent.setup();
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="hi-IN" onPrefill={onPrefill} />);

    // Seen in the browser: using the whole sentence as the button label made the card look
    // like a wall of text. The label is short; what lands in the composer is unchanged.
    const button = screen.getByTestId('script-card-another-hook');
    expect(button).toHaveTextContent('दूसरा हुक');
    await user.click(button);
    expect(onPrefill).toHaveBeenCalledWith('इस स्क्रिप्ट के लिए एक और हुक दो');
  });

  it('has no Save button — this phase has no backend store to save to', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onPrefill={vi.fn()} />);
    expect(screen.queryByText(/save/i)).toBeNull();
  });
});
