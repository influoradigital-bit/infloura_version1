/**
 * MeeraScriptCard: renders every field of the rich-format `ParsedMeeraScript`, and Copy writes the
 * exact original reply text (never a re-serialized version of the parsed fields), minus only the
 * machine `Shot cards:` block.
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraScriptCard } from './MeeraScriptCard';
import { parseMeeraScript, SHOT_CARD_KEYS, type ParsedMeeraScript, type ShotCard } from '@/lib/meera-result-cards';

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

  it.each([
    ['LF', '\n'],
    ['CRLF', '\r\n'],
  ])('Copy leaves out the machine "Shot cards:" block and keeps every other byte (%s)', async (_name, eol) => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    const lines = RAW_TEXT.split('\n');
    const captionAt = lines.findIndex((line) => line.startsWith('Caption:'));
    const withCards = [
      ...lines.slice(0, captionAt),
      '**Shot cards:**',
      'S1: size=MCU; height=eye; distance=0.8-1 m; place=?; light=window; stand=left; headroom=small; eyes=lens; background=plain wall; space=right; text=top; prop=?; move=still',
      'S2: size=OVERHEAD; height=overhead; distance=?; place=?; light=?; stand=?; headroom=?; eyes=?; background=?; space=none; text=none; prop=?; move=still',
      ...lines.slice(captionAt),
    ].join(eol);

    render(<MeeraScriptCard script={SCRIPT} rawText={withCards} language="en-IN" />);
    await user.click(screen.getByTestId('script-card-copy'));

    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(lines.join(eol));
  });

  it('Copy writes the reply unchanged when it does not parse as a script', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    const notAScript = 'Shot cards:\nS1: size=MCU\nCaption: hello';
    render(<MeeraScriptCard script={SCRIPT} rawText={notAScript} language="en-IN" />);
    await user.click(screen.getByTestId('script-card-copy'));
    expect(writeText).toHaveBeenCalledWith(notAScript);
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

describe('MeeraScriptCard — the optional Made for line (owner decision B, 2026-09-26)', () => {
  const MADE_FOR =
    'your followers - mostly women, 18-24, Mumbai (Instagram) · Topic: Mumbai street breakfast (your best-performing topic) · Goal: grow followers';

  it('shows Made for as the very first line of the card, above the Idea', () => {
    render(<MeeraScriptCard script={{ ...SCRIPT, madeFor: MADE_FOR }} rawText={RAW_TEXT} language="en-IN" />);
    const card = screen.getByTestId('meera-script-card');
    const row = screen.getByTestId('script-card-made-for');
    expect(card.firstElementChild).toBe(row);
    expect(row).toHaveTextContent(`Made for: ${MADE_FOR}`);
    const idea = screen.getByTestId('script-card-idea');
    expect(row.compareDocumentPosition(idea) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('renders the value as plain text: markup in it is shown literally, never as HTML', () => {
    const hostile = '<b>bold</b> <img src=x onerror=alert(1)> **stars**';
    render(<MeeraScriptCard script={{ ...SCRIPT, madeFor: hostile }} rawText={RAW_TEXT} language="en-IN" />);
    const row = screen.getByTestId('script-card-made-for');
    expect(row).toHaveTextContent(hostile);
    expect(row.querySelector('b, img, strong')).toBeNull();
  });

  it('has no Made for row for an older script without that line', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" />);
    expect(screen.queryByTestId('script-card-made-for')).toBeNull();
    expect(screen.getByTestId('meera-script-card').firstElementChild).toBe(screen.getByTestId('script-card-idea'));
  });

  it('labels the line in Devanagari for a Hindi creator', () => {
    render(<MeeraScriptCard script={{ ...SCRIPT, madeFor: MADE_FOR }} rawText={RAW_TEXT} language="hi-IN" />);
    expect(screen.getByTestId('script-card-made-for')).toHaveTextContent(`किसके लिए: ${MADE_FOR}`);
  });

  it('a real reply with the line parses and renders it, and Copy keeps the line', async () => {
    const lines = RAW_TEXT.split('\n');
    lines.splice(1, 0, `Made for: ${MADE_FOR}`);
    const raw = lines.join('\n');
    const parsed = parseMeeraScript(raw);
    expect(parsed?.madeFor).toBe(MADE_FOR);
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    render(<MeeraScriptCard script={parsed!} rawText={raw} language="en-IN" />);
    expect(screen.getByTestId('script-card-made-for')).toHaveTextContent(MADE_FOR);
    await user.click(screen.getByTestId('script-card-copy'));
    expect(writeText).toHaveBeenCalledWith(raw);
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

describe('MeeraScriptCard — shot card per beat (spec v2 Phase 6)', () => {
  const FULL_CARD: ShotCard = {
    size: 'MCU',
    height: 'eye',
    distance: '0.8-1 m',
    place: 'Bedroom desk',
    light: 'window-left',
    stand: 'centre',
    headroom: 'small',
    eyes: 'lens',
    background: 'plain wall',
    space: 'right',
    text: 'top',
    prop: 'right-hand',
    move: 'still',
  };
  const PARTIAL_CARD: ShotCard = { ...FULL_CARD, height: '?', move: '?' };
  const WITH_CARDS: ParsedMeeraScript = {
    ...SCRIPT,
    beats: [SCRIPT.beats[0], { ...SCRIPT.beats[1], card: PARTIAL_CARD }, { ...SCRIPT.beats[2], card: FULL_CARD }],
  };

  it('renders no shot card for a reply without the Shot cards block (older replies)', () => {
    render(<MeeraScriptCard script={SCRIPT} rawText={RAW_TEXT} language="en-IN" onPrefill={() => {}} />);
    expect(screen.queryByTestId('script-card-shot-card')).toBeNull();
    expect(screen.queryByRole('button', { name: /shot card/i })).toBeNull();
  });

  it('gives only the beats with a card a collapsed disclosure button', async () => {
    const user = userEvent.setup();
    render(<MeeraScriptCard script={WITH_CARDS} rawText={RAW_TEXT} language="en-IN" />);
    const beats = screen.getAllByTestId('script-card-beat');
    expect(within(beats[0]).queryByTestId('script-card-shot-card')).toBeNull();

    const toggle = within(beats[2]).getByTestId('script-card-shot-card-toggle');
    const panel = within(beats[2]).getByTestId('script-card-shot-card-panel');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveAttribute('aria-controls', panel.id);
    expect(toggle.className).toContain('min-h-11');
    expect(panel).not.toBeVisible();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(panel).toBeVisible();
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(panel).not.toBeVisible();
  });

  it('shows the 13 fields in plain words, in the wire order', async () => {
    const user = userEvent.setup();
    render(<MeeraScriptCard script={WITH_CARDS} rawText={RAW_TEXT} language="en-IN" />);
    const beat = screen.getAllByTestId('script-card-beat')[2];
    await user.click(within(beat).getByTestId('script-card-shot-card-toggle'));

    const rows = within(beat).getAllByTestId(/^shot-card-field-/);
    expect(rows.map((row) => row.getAttribute('data-testid'))).toEqual(
      SHOT_CARD_KEYS.map((key) => `shot-card-field-${key}`),
    );
    const text = (key: string) => within(beat).getByTestId(`shot-card-field-${key}`).textContent;
    expect(text('size')).toBe('SizeMedium close-up (chest up)');
    expect(text('height')).toBe('Camera heighteye level');
    expect(text('light')).toBe('Lightwindow, your left');
    expect(text('prop')).toBe('Propin your right hand');
    expect(text('place')).toBe('WhereBedroom desk');
    expect(text('distance')).toBe('Distance0.8-1 m');
    // A fully set card has nothing to ask.
    expect(within(beat).queryByTestId('shot-card-not-set')).toBeNull();
    expect(within(beat).getByTestId('script-card-shot-card-toggle')).toHaveTextContent(/^Shot card$/);
  });

  it('shows "?" as "Not set yet" in words with a dashed border, and counts it on the button', async () => {
    const user = userEvent.setup();
    render(<MeeraScriptCard script={WITH_CARDS} rawText={RAW_TEXT} language="en-IN" />);
    const beat = screen.getAllByTestId('script-card-beat')[1];
    const toggle = within(beat).getByTestId('script-card-shot-card-toggle');
    expect(toggle).toHaveTextContent('Shot card · 2 not set yet');
    await user.click(toggle);

    const notSet = within(beat).getAllByTestId('shot-card-not-set');
    expect(notSet).toHaveLength(2);
    for (const badge of notSet) {
      expect(badge).toHaveTextContent('Not set yet');
      expect(badge.className).toContain('border-dashed');
    }
    expect(within(beat).getByTestId('shot-card-field-height')).toHaveTextContent('Camera heightNot set yet');
    expect(within(beat).getByTestId('shot-card-field-move')).toHaveTextContent('MovementNot set yet');
    // Never a guessed value in its place.
    expect(within(beat).getByTestId('shot-card-field-move')).not.toHaveTextContent(/still|sitting|standing/);
  });

  it('Ask Meera only prefills the message box with the missing fields, and sends nothing', async () => {
    const user = userEvent.setup();
    const onPrefill = vi.fn();
    const onCheckShot = vi.fn();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    render(
      <MeeraScriptCard
        script={WITH_CARDS}
        rawText={RAW_TEXT}
        language="en-IN"
        onPrefill={onPrefill}
        onCheckShot={onCheckShot}
      />,
    );
    const beats = screen.getAllByTestId('script-card-beat');
    await user.click(within(beats[1]).getByTestId('script-card-shot-card-toggle'));
    const ask = within(beats[1]).getByRole('button', { name: 'Ask Meera' });
    expect(ask.className).toContain('min-h-11');
    await user.click(ask);

    expect(onPrefill).toHaveBeenCalledTimes(1);
    expect(onPrefill).toHaveBeenCalledWith(
      'Shot card for beat 2 (10-20s): ask me what you need to fill in camera height, movement.',
    );
    expect(onCheckShot).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();

    // A fully set card has no Ask button.
    await user.click(within(beats[2]).getByTestId('script-card-shot-card-toggle'));
    expect(within(beats[2]).queryByTestId('script-card-shot-card-ask')).toBeNull();
  });

  it('has no Ask button when the chat passes no onPrefill', async () => {
    const user = userEvent.setup();
    render(<MeeraScriptCard script={WITH_CARDS} rawText={RAW_TEXT} language="en-IN" />);
    const beat = screen.getAllByTestId('script-card-beat')[1];
    await user.click(within(beat).getByTestId('script-card-shot-card-toggle'));
    expect(within(beat).queryByTestId('script-card-shot-card-ask')).toBeNull();
  });

  it('keeps "Check my set-up" on a beat with a card, still opening that beat', async () => {
    const user = userEvent.setup();
    const onCheckShot = vi.fn();
    render(<MeeraScriptCard script={WITH_CARDS} rawText={RAW_TEXT} language="en-IN" onCheckShot={onCheckShot} />);
    const beat = screen.getAllByTestId('script-card-beat')[2];
    await user.click(within(beat).getByRole('button', { name: 'Check my set-up' }));
    expect(onCheckShot).toHaveBeenCalledWith(2);
  });

  it('writes the card in Devanagari for a Hindi creator, enum values never shown raw', async () => {
    const user = userEvent.setup();
    const onPrefill = vi.fn();
    render(<MeeraScriptCard script={WITH_CARDS} rawText={RAW_TEXT} language="hi-IN" onPrefill={onPrefill} />);
    const beat = screen.getAllByTestId('script-card-beat')[1];
    const toggle = within(beat).getByTestId('script-card-shot-card-toggle');
    expect(toggle).toHaveTextContent('शॉट कार्ड · 2 अभी तय नहीं');
    await user.click(toggle);
    expect(within(beat).getByTestId('shot-card-field-light')).toHaveTextContent('रोशनीखिड़की, आपकी बाईं ओर');
    expect(within(beat).getByTestId('shot-card-field-prop')).toHaveTextContent('प्रॉपआपके दाएँ हाथ में');
    expect(within(beat).getAllByTestId('shot-card-not-set')[0]).toHaveTextContent('अभी तय नहीं');
    expect(within(beat).getByTestId('script-card-shot-card-panel').textContent).not.toMatch(/window-left|right-hand/);

    await user.click(within(beat).getByRole('button', { name: 'Meera से पूछें' }));
    expect(onPrefill).toHaveBeenCalledWith(
      'बीट 2 (10-20s) का शॉट कार्ड: कैमरे की ऊँचाई, मूवमेंट भरने के लिए मुझसे जो जानना है, पूछें।',
    );
  });

  it('shows an out-of-contract value as "Not set yet", not as raw text', async () => {
    const user = userEvent.setup();
    const odd = { ...FULL_CARD, light: 'candle-up', prop: 'right-shelf' } as unknown as ShotCard;
    render(
      <MeeraScriptCard
        script={{ ...SCRIPT, beats: [{ ...SCRIPT.beats[0], card: odd }, SCRIPT.beats[1], SCRIPT.beats[2]] }}
        rawText={RAW_TEXT}
        language="en-IN"
      />,
    );
    const beat = screen.getAllByTestId('script-card-beat')[0];
    await user.click(within(beat).getByTestId('script-card-shot-card-toggle'));
    expect(within(beat).getByTestId('shot-card-field-light')).toHaveTextContent('LightNot set yet');
    expect(within(beat).getByTestId('shot-card-field-prop')).toHaveTextContent('PropNot set yet');
  });
});
