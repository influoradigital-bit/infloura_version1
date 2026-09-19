/**
 * U-4 — `/meera-for-creators` must not promise reply drafting, a brand-facing "drafted with
 * Meera" label, or that Meera decides anything on the creator's behalf (sends messages, sets the
 * price). Reply drafting is Wave D (D-1, D-2, D-4) and the label is D-3 + D-5
 * (ASSIGN-PENDING-0917.md); until then Meera only advises and the creator decides.
 *
 * Asserts against the RENDERED document — body text, the hoisted `<meta name="description">` and
 * the JSON-LD script — not against the source file, because the page keeps a comment naming each
 * removed claim so it can come back honestly later, and a source grep would trip on that comment.
 *
 * Run: npx vitest run src/pages/meera-for-creators.claims.test.tsx
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// The phone demo lazy-loads Remotion; the header and footer pull in session and nav state. None
// of them is the page's own copy, which is what this file checks.
vi.mock('@/components/site/MeeraDemoPlayer', () => ({
  MeeraDemoPlayer: () => <div data-testid="meera-demo-player" />,
}));
vi.mock('@/components/site/SiteHeader', () => ({ SiteHeader: () => null }));
vi.mock('@/components/site/SiteFooter', () => ({ SiteFooter: () => null }));
// The motion wrappers need IntersectionObserver, which jsdom does not have. Pass-through keeps
// every child — and so every line of copy — in the document.
vi.mock('@/components/motion', () => {
  const PassThrough = ({ children, className }: { children?: React.ReactNode; className?: string }) => (
    <div className={className}>{children}</div>
  );
  return { FadeUp: PassThrough, StaggerContainer: PassThrough, StaggerItem: PassThrough };
});

import MeeraForCreatorsPage from './meera-for-creators';

/** Each removed claim, as a pattern loose enough to catch a light rewording of it. */
const REMOVED_CLAIMS: Array<[string, RegExp]> = [
  ['"drafts the reply" (card, hero, meta description)', /drafts? the reply/i],
  ['hero: "The send button is always yours."', /send button is always yours/i],
  ['promise: "Every reply is labelled"', /every reply is label/i],
  ['promise: "drafted with Meera, approved by you"', /drafted with meera/i],
  ['JSON-LD: "approval-gated replies"', /approval-gated repl/i],
  // U-4 (2026-09-17) second pass: Meera advises, she does not decide or act on her own.
  ['"Meera never sends anything on her own"', /never sends anything on her own/i],
  ['hero: "tells you what to charge"', /tells you what to charge/i],
  ['promise: "Every message goes out only after…"', /every message goes out only after/i],
  ['any "Drafted with Meera" label copy', /drafted with meera/i],
];

describe('MeeraForCreatorsPage — no promises the product does not keep (U-4)', () => {
  it('renders none of the removed reply-drafting or labelling claims, anywhere in the document', async () => {
    render(
      <MemoryRouter>
        <MeeraForCreatorsPage />
      </MemoryRouter>,
    );
    // Let the lazy phone demo settle inside act, so the whole page is in the document.
    expect(await screen.findByTestId('meera-demo-player')).toBeInTheDocument();

    // The page really rendered, including the sections the claims used to sit in.
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Your own PR manager. On your side.',
    );
    expect(screen.getByText('Paste a brief, get a straight answer')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Two promises' })).toBeInTheDocument();

    const description =
      document.head.querySelector('meta[name="description"]')?.getAttribute('content') ?? '';
    const jsonLd = Array.from(
      document.querySelectorAll('script[type="application/ld+json"]'),
    )
      .map((s) => s.textContent ?? '')
      .join('\n');
    // Guard the guard: if the meta or JSON-LD stopped rendering, "not found" would pass vacuously.
    expect(description).toContain('Paste a brand brief');
    expect(jsonLd).toContain('brief reading');

    const rendered = [document.body.textContent ?? '', description, jsonLd].join('\n');
    for (const [name, pattern] of REMOVED_CLAIMS) {
      expect(pattern.test(rendered), `still renders the ${name} claim`).toBe(false);
    }
  });

  it('pins the U-4 second-pass replacement copy (2026-09-17)', async () => {
    render(
      <MemoryRouter>
        <MeeraForCreatorsPage />
      </MemoryRouter>,
    );
    expect(await screen.findByTestId('meera-demo-player')).toBeInTheDocument();

    const description =
      document.head.querySelector('meta[name="description"]')?.getAttribute('content') ?? '';
    const rendered = document.body.textContent ?? '';

    expect(rendered).toContain('Five jobs. Meera advises; you decide.');
    expect(rendered).toContain(
      'Meera brings a list of brands that fit you, and says why each one fits.',
    );
    expect(rendered).toContain('Meera reads it and suggests what to charge.');
    expect(description).toContain('Meera reads it and suggests a rate.');
    expect(rendered).toContain('You decide.');
    expect(rendered).toContain('Meera tells you what she thinks.');
    expect(rendered).toContain('What you say to a brand is up to you.');
  });
});
