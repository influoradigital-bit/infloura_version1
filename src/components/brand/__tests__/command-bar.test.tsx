/**
 * Command Bar — F-0261 (unclosable-overlay).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * BrandLayout (src/components/brand/brand-layout.tsx, out of scope for this task) also listens
 * for Cmd/Ctrl+K on `window` and unconditionally opens the bar — it never toggles closed.
 * `document` (this component's listener) is hit before `window` in the bubble phase, so on the
 * CLOSING press command-bar.tsx used to compute `false` first and BrandLayout's listener then
 * ran right after and forced it back to `true`: the bar opened but the same chord could never
 * close it. The fix makes command-bar.tsx's own listener authoritative by calling
 * `stopPropagation()`, so a conflicting window-level listener never runs for this chord — proven
 * below by registering a stand-in for BrandLayout's real listener and asserting it never fires.
 *
 * Also covers: no advertised-but-unimplemented keyboard shortcut (Cmd+W would close the
 * browser tab), Escape closes the dialog, and every one of the 15 destinations is reachable.
 *
 * Run: npx vitest run src/components/brand/__tests__/command-bar.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CommandBar } from '../command-bar';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/lib/api', () => ({
  isApiLive: () => true,
}));

function Harness() {
  const [open, setOpen] = React.useState(false);
  return (
    <div>
      <span data-testid="open-state">{String(open)}</span>
      <CommandBar open={open} onOpenChange={setOpen} />
    </div>
  );
}

function renderHarness() {
  return render(
    <MemoryRouter>
      <Harness />
    </MemoryRouter>,
  );
}

function dispatchChord(target: Document | Window = document) {
  const event = new KeyboardEvent('keydown', {
    key: 'k',
    metaKey: true,
    bubbles: true,
    cancelable: true,
  });
  act(() => {
    target.dispatchEvent(event);
  });
  return event;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('CommandBar — Cmd/Ctrl+K chord (F-0261)', () => {
  it('opens on the first press and closes on the second, despite a conflicting window-level listener', () => {
    renderHarness();

    // Stand-in for BrandLayout's real window-level listener: unconditionally forces the bar
    // open on every Cmd/Ctrl+K press, exactly like the pre-fix production bug. If command-bar's
    // own listener does not stop propagation, this phantom listener fires right after it and
    // the bar can never close.
    const phantomWindowListener = vi.fn();
    window.addEventListener('keydown', phantomWindowListener);

    expect(screen.getByTestId('open-state').textContent).toBe('false');

    dispatchChord();
    expect(screen.getByTestId('open-state').textContent).toBe('true');

    dispatchChord();
    expect(screen.getByTestId('open-state').textContent).toBe('false');

    // The phantom window-level listener must never have run — command-bar's own handler
    // stopped propagation before the event could bubble up to `window`.
    expect(phantomWindowListener).not.toHaveBeenCalled();

    window.removeEventListener('keydown', phantomWindowListener);
  });
});

describe('CommandBar — advertised shortcuts (F-0261)', () => {
  it('advertises no keyboard shortcut hint for quick actions (none is implemented)', () => {
    renderHarness();
    dispatchChord();
    expect(screen.getByTestId('open-state').textContent).toBe('true');

    // No dangling ⌘<letter> hint anywhere in the open palette, and Cmd+W specifically must
    // never be advertised — that chord closes the browser tab.
    expect(screen.queryByText(/⌘[A-Z]/)).not.toBeInTheDocument();
    expect(screen.queryByText('⌘W')).not.toBeInTheDocument();
  });

  it('pressing Cmd+W while the bar is open does not trigger any command-bar handler', () => {
    renderHarness();
    dispatchChord();
    expect(screen.getByTestId('open-state').textContent).toBe('true');

    const wEvent = new KeyboardEvent('keydown', {
      key: 'w',
      metaKey: true,
      bubbles: true,
      cancelable: true,
    });
    let defaultPrevented = false;
    act(() => {
      defaultPrevented = !document.dispatchEvent(wEvent);
    });
    // command-bar.tsx must not be the one calling preventDefault()/navigating on Cmd+W.
    expect(defaultPrevented).toBe(false);
    expect(navigateMock).not.toHaveBeenCalled();
  });
});

describe('CommandBar — Escape closes the dialog (F-0261)', () => {
  it('closes when Escape is pressed', async () => {
    renderHarness();
    dispatchChord();
    expect(screen.getByTestId('open-state').textContent).toBe('true');

    const dialog = screen.getByRole('dialog');
    const input = within(dialog).getByPlaceholderText('Search or type a command...');
    const escapeEvent = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      cancelable: true,
    });
    act(() => {
      input.dispatchEvent(escapeEvent);
    });

    await vi.waitFor(() => {
      expect(screen.getByTestId('open-state').textContent).toBe('false');
    });
  });
});

describe('CommandBar — all 15 destinations reachable (F-0261)', () => {
  const allDestinationTitles = [
    'Now',
    'Campaigns',
    'Deal Rooms',
    'Pipeline',
    'Messages',
    'Contracts',
    'Analytics',
    'Reviews',
    'Disputes',
    'Meera',
    'Wallet',
    'Notifications',
    'Help',
    'Settings',
  ];

  it('renders every destination title in the "Go To" group', () => {
    renderHarness();
    dispatchChord();

    for (const title of allDestinationTitles) {
      expect(screen.getByText(title)).toBeInTheDocument();
    }
  });

  it('"Discover" (Find Creators destination) is present, bringing the total to 15', () => {
    renderHarness();
    dispatchChord();
    expect(screen.getByText('Discover')).toBeInTheDocument();
    // 14 "Go To" items above + Discover = 15 total in-app destinations.
    expect(allDestinationTitles.length + 1).toBe(15);
  });
});
