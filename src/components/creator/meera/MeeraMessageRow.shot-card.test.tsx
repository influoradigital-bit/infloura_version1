/**
 * Integration seam (spec v2 Phase 6): a Meera reply WITH the "Shot cards:" block, parsed by the real
 * parser and rendered through the real message row, shows each beat's shot card, and the card's
 * "Ask Meera" button reaches the chat's `onPrefill` (prefill only, never a send). Before the
 * integration the row did not pass `onPrefill` to the script card, so the button never showed.
 *
 * Run: npx vitest run src/components/creator/meera/MeeraMessageRow.shot-card.test.tsx
 */
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { parseMeeraScript } from '@/lib/meera-result-cards';
import { MeeraMessageRow, type ChatMessage } from './MeeraMessageRow';

const REPLY = [
  'Idea: 3 saffron mistakes to avoid',
  'Plan: for new saffron buyers; curiosity; grow followers; 30s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
  'Action: hold up the saffron box and speak to camera',
  'Success looks like: reel gets watched to the end and shared to a friend',
  'Script:',
  '0-10s. Shot: Close-up on your face - hold up the saffron box. Say: "Is your saffron even real?". On screen: REAL vs FAKE',
  '10-20s. Shot: Overhead on your hands - do the warm-water color test. Say: "Watch what happens in water". On screen: THE WATER TEST',
  '20-30s. Shot: Close-up on the certificate - hold it next to the box. Say: "This is what real Kashmiri saffron looks like". On screen: GI CERTIFIED',
  'Shot cards:',
  'S1: size=MCU; height=eye; distance=0.8-1 m; place=Bedroom desk; light=window-left; stand=centre; headroom=small; eyes=lens; background=plain wall; space=right; text=top; prop=right-hand; move=still',
  'S2: size=OVERHEAD; height=?; distance=?; place=Kitchen counter; light=window-left; stand=centre; headroom=cropped; eyes=product; background=clean counter; space=none; text=lower_middle; prop=centre-table; move=?',
  'Caption: Would you have spotted the fake one? Link in bio for real Kashmiri saffron. #saffron #kashmir',
  'Before you shoot: 1) Charge your phone to 100% 2) Wipe the counter clean 3) Keep the certificate within reach',
  'Why this works: Problem-agitate-solve structure, curiosity-gap hook template',
].join('\n');

function row(onPrefill: (text: string) => void) {
  const script = parseMeeraScript(REPLY);
  expect(script, 'the reply parses as a script card').toBeDefined();
  const message: ChatMessage = { id: 'm1', role: 'meera', text: REPLY, resultCard: { kind: 'script', script: script! } };
  return render(
    <MeeraMessageRow
      message={message}
      language="en-IN"
      isLivePhotoCheck={false}
      photoCheckBusy={false}
      creditsBalance={null}
      onPrefill={onPrefill}
      onToggleRaw={() => {}}
      onCoachAnswer={() => {}}
      onPhotoRetake={() => {}}
      onCredited={() => {}}
    />,
  );
}

describe('MeeraMessageRow: the shot card of a parsed reply', () => {
  it('shows a card per S-line and its Ask Meera button prefills through the row, once', async () => {
    const user = userEvent.setup();
    const onPrefill = vi.fn();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    row(onPrefill);

    const beats = screen.getAllByTestId('script-card-beat');
    expect(beats).toHaveLength(3);
    expect(within(beats[0]).getByTestId('script-card-shot-card-toggle')).toBeInTheDocument();
    // Beat 3 has no S-line: no card for it, nothing guessed.
    expect(within(beats[2]).queryByTestId('script-card-shot-card-toggle')).toBeNull();

    // Beat 2 has 3 unknown fields: the card counts them and asks, never guesses.
    await user.click(within(beats[1]).getByTestId('script-card-shot-card-toggle'));
    expect(within(beats[1]).getByTestId('shot-card-field-height')).toHaveTextContent('Not set yet');
    await user.click(within(beats[1]).getByRole('button', { name: 'Ask Meera' }));

    expect(onPrefill).toHaveBeenCalledTimes(1);
    expect(onPrefill.mock.calls[0][0]).toMatch(/^Shot card for beat 2 \(10-20s\): ask me what you need to fill in /);
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
