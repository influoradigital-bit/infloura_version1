/**
 * /meera — the intro page must keep to the same U-4 line as /meera-for-creators: Meera advises,
 * the creator decides. On top of that list, this page must not promise DM automation (the
 * 2026-09-22 ruling was "Coming soon: DM help", suggestions only), and the DM card must carry
 * its "Coming soon" label.
 *
 * Checks the RENDERED document (body, meta description, JSON-LD), not the source, so the header
 * comment that names the banned claims cannot trip it.
 *
 * Run: npx vitest run src/pages/meera.claims.test.tsx
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

// Remotion does not run in jsdom; the page's own copy is what this file checks.
vi.mock('@/components/site/MeeraIntroPlayer', () => ({
  MeeraIntroPlayer: ({ portrait }: { portrait: boolean }) => (
    <div data-testid="meera-intro-player" data-portrait={String(portrait)} />
  ),
  MeeraIntroPoster: () => <div data-testid="meera-intro-poster" />,
}));
vi.mock('@/components/site/SiteHeader', () => ({ SiteHeader: () => null }));
vi.mock('@/components/site/SiteFooter', () => ({ SiteFooter: () => null }));
vi.mock('@/components/motion', () => {
  const PassThrough = ({ children, className }: { children?: React.ReactNode; className?: string }) => (
    <div className={className}>{children}</div>
  );
  return { FadeUp: PassThrough, StaggerContainer: PassThrough, StaggerItem: PassThrough };
});

import MeeraPage from './meera';

const BANNED: Array<[string, RegExp]> = [
  ['reply drafting', /drafts? the reply/i],
  ['"drafted with Meera" label', /drafted with meera/i],
  ['"PR manager" framing', /pr[- ]?manager/i],
  ['"tells you what to charge"', /tells you what to charge/i],
  ['Meera sending on the creator’s behalf', /\b(sends?|replies|answers|posts?|pitches)\b[^.]{0,40}\b(for you|on your behalf)/i],
  ['DM automation', /automat/i],
  ['auto-reply', /auto-?repl/i],
];

function renderPage() {
  return render(
    <MemoryRouter>
      <MeeraPage />
    </MemoryRouter>,
  );
}

describe('MeeraPage — no promises the product does not keep', () => {
  it('renders none of the banned claims anywhere in the document', async () => {
    renderPage();
    expect(await screen.findByTestId('meera-intro-poster')).toBeInTheDocument();

    // Guard the guard: the sections the claims could sit in really rendered.
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Meera. One AI for your whole creator life.');
    expect(screen.getByRole('heading', { name: 'Scripts and hooks' })).toBeInTheDocument();
    const description = document.head.querySelector('meta[name="description"]')?.getAttribute('content') ?? '';
    const jsonLd = Array.from(document.querySelectorAll('script[type="application/ld+json"]'))
      .map((s) => s.textContent ?? '')
      .join('\n');
    expect(description).toContain('Coming soon on Influora');
    expect(jsonLd).toContain('brand discovery');

    const rendered = [document.body.textContent ?? '', description, jsonLd].join('\n');
    for (const [name, pattern] of BANNED) {
      expect(pattern.test(rendered), `renders the ${name} claim`).toBe(false);
    }
    expect(rendered).toContain('Meera advises.');
  });

  it('labels DM help as coming soon, and only DM help', async () => {
    renderPage();
    const dm = (await screen.findByRole('heading', { name: 'DM help' })).parentElement as HTMLElement;
    expect(within(dm).getByText('Coming soon')).toBeInTheDocument();
    expect(screen.getAllByText('Coming soon')).toHaveLength(1);
  });

  it('opens the intro film in a dialog from the hero button', async () => {
    const user = userEvent.setup();
    renderPage();
    expect(screen.queryByTestId('meera-intro-player')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Watch the intro' }));

    const dialog = await screen.findByRole('dialog', { name: 'Meera intro film' });
    expect(await within(dialog).findByTestId('meera-intro-player')).toHaveAttribute('data-portrait', 'false');

    await user.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
