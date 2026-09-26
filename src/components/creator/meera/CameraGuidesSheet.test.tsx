/**
 * CameraGuidesSheet: the camera's Guides sheet (spec v2 Phase 5a + 5b): a labelled dialog with the
 * Shot guides switch, the "Camera grid" radio group, the always-on safe zone as text, and focus back
 * on the Guides button when it closes.
 *
 * Run: npx vitest run src/components/creator/meera/CameraGuidesSheet.test.tsx
 */
import * as React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { CameraGrid } from '@/lib/shoot-check/guide-prefs';
import { CameraGuidesSheet } from './CameraGuidesSheet';

function Harness({
  initialGrid = 'thirds',
  onGrid = () => {},
  onShotGuides = () => {},
  lang = 'en-IN',
}: {
  initialGrid?: CameraGrid;
  onGrid?: (g: CameraGrid) => void;
  onShotGuides?: (on: boolean) => void;
  lang?: 'en-IN' | 'hi-IN';
}) {
  const [open, setOpen] = React.useState(false);
  const [grid, setGrid] = React.useState<CameraGrid>(initialGrid);
  const [shotGuides, setShotGuides] = React.useState(true);
  const ref = React.useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={ref} type="button" onClick={() => setOpen(true)}>
        Guides
      </button>
      <CameraGuidesSheet
        open={open}
        onOpenChange={setOpen}
        lang={lang}
        shotGuides={shotGuides}
        onShotGuidesChange={(on) => {
          setShotGuides(on);
          onShotGuides(on);
        }}
        grid={grid}
        onGridChange={(g) => {
          setGrid(g);
          onGrid(g);
        }}
        returnFocusRef={ref}
      />
    </>
  );
}

async function openSheet(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Guides' }));
  return screen.findByRole('dialog', { name: 'Guides' });
}

describe('CameraGuidesSheet', () => {
  it('is a labelled dialog with the switch, the grid radiogroup and the always-on safe zone as text', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const dialog = await openSheet(user);

    expect(within(dialog).getByRole('switch', { name: 'Shot guides' })).toHaveAttribute('aria-checked', 'true');

    const group = within(dialog).getByRole('radiogroup', { name: 'Camera grid' });
    const radios = within(group).getAllByRole('radio');
    expect(radios).toHaveLength(3);
    expect(within(group).getByRole('radio', { name: /Rule of thirds/ })).toHaveAttribute('aria-checked', 'true');
    expect(within(group).getByRole('radio', { name: /Golden grid/ })).toBeInTheDocument();
    expect(within(group).getByRole('radio', { name: 'Off' })).toBeInTheDocument();
    // "Default" is on the Rule of thirds option only.
    expect(within(group).getAllByText('Default')).toHaveLength(1);

    // The safe zone has no control at all: text only.
    const safe = within(dialog).getByTestId('guides-safe-zone');
    expect(within(safe).getByText('Safe zone: Always on')).toBeInTheDocument();
    expect(within(safe).queryAllByRole('switch')).toHaveLength(0);
    expect(within(safe).queryAllByRole('checkbox')).toHaveLength(0);
    expect(within(safe).getByText(/The green area keeps your text visible/)).toBeInTheDocument();
    expect(within(safe).getByText(/Every phone and app version is a little different/)).toBeInTheDocument();
    expect(within(dialog).getAllByText('Your choice is remembered on this phone.').length).toBeGreaterThan(0);
  });

  it('reports the grid choice and the switch', async () => {
    const user = userEvent.setup();
    const onGrid = vi.fn();
    const onShotGuides = vi.fn();
    render(<Harness onGrid={onGrid} onShotGuides={onShotGuides} />);
    const dialog = await openSheet(user);

    await user.click(within(dialog).getByRole('radio', { name: /Golden grid/ }));
    expect(onGrid).toHaveBeenLastCalledWith('golden');
    expect(within(dialog).getByRole('radio', { name: /Golden grid/ })).toHaveAttribute('aria-checked', 'true');

    await user.click(within(dialog).getByRole('radio', { name: 'Off' }));
    expect(onGrid).toHaveBeenLastCalledWith('off');

    await user.click(within(dialog).getByRole('switch', { name: 'Shot guides' }));
    expect(onShotGuides).toHaveBeenLastCalledWith(false);
  });

  it('returns focus to the Guides button when closed with Done or Esc', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const guides = screen.getByRole('button', { name: 'Guides' });

    let dialog = await openSheet(user);
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(guides).toHaveFocus());

    dialog = await openSheet(user);
    expect(dialog).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(guides).toHaveFocus());
  });

  it('is in Hindi for a Hindi chat', async () => {
    const user = userEvent.setup();
    render(<Harness lang="hi-IN" />);
    await user.click(screen.getByRole('button', { name: 'Guides' }));
    const dialog = await screen.findByRole('dialog', { name: 'गाइड' });
    expect(within(dialog).getByRole('radiogroup', { name: 'कैमरा ग्रिड' })).toBeInTheDocument();
  });
});
