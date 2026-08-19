/**
 * One source of truth for the six-step flow.
 *
 * The copy in `@/content/how-it-works-steps` is rendered by four surfaces — the two public
 * marketing pages and the two in-app pages — and the public pages additionally build their
 * `HowTo` JSON-LD from the same objects. That schema is what Perplexity, ChatGPT and AI
 * Overviews lift close to verbatim, so a second, drifted copy of these steps is not a tidiness
 * problem: it is the in-app explanation and the machine-readable public one describing different
 * products, with nothing failing and nobody noticing.
 *
 * The structural assertions below are deliberately about the SOURCE, not the render. Two copies
 * of this text render identically right up until someone edits one of them — which is exactly
 * the moment a render test still passes and the drift has already happened.
 *
 * Run: npx vitest run src/content/__tests__/how-it-works-single-source.test.tsx
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';

import { BRAND_STEPS, CREATOR_STEPS } from '@/content/how-it-works-steps';
import { HowItWorksFlow } from '@/components/shared/HowItWorksFlow';

const read = (p: string) => readFileSync(p, 'utf-8');

const RENDERERS = [
  'src/pages/how-it-works-brands.tsx',
  'src/pages/how-it-works-creators.tsx',
  'src/pages/brand-how-it-works.tsx',
  'src/pages/creator-how-it-works.tsx',
];

describe('how-it-works content', () => {
  it('has six ordered, uniquely-numbered steps per role', () => {
    for (const steps of [BRAND_STEPS, CREATOR_STEPS]) {
      expect(steps).toHaveLength(6);
      expect(steps.map((s) => s.step)).toEqual(['01', '02', '03', '04', '05', '06']);
      expect(new Set(steps.map((s) => s.title)).size).toBe(6);
      for (const s of steps) {
        expect(s.title.trim()).not.toBe('');
        expect(s.body.trim()).not.toBe('');
      }
    }
  });
});

describe('single source of truth', () => {
  it.each(RENDERERS)('%s defines no local copy of the steps', (path) => {
    const src = read(path);
    expect(src).not.toMatch(/const STEPS\s*=\s*\[/);
  });

  it.each(RENDERERS)('%s imports the shared steps module', (path) => {
    expect(read(path)).toMatch(/from '@\/content\/how-it-works-steps'/);
  });

  it('still builds the public HowTo schema from the same array the page renders', () => {
    // The SEO value of these pages depends on this line surviving refactors of the content
    // module — a schema hand-written from a second list is the drift this file exists to stop.
    for (const path of ['src/pages/how-it-works-brands.tsx', 'src/pages/how-it-works-creators.tsx']) {
      const src = read(path);
      expect(src).toMatch(/steps: STEPS\.map\(/);
    }
  });
});

describe('in-app routes exist', () => {
  it.each([
    ['/brand/how-it-works', 'BrandHowItWorksPage'],
    ['/creator/how-it-works', 'CreatorHowItWorksPage'],
  ])('%s is registered and guarded', (path, component) => {
    const app = read('src/App.tsx');
    expect(app).toContain(`path="${path}"`);
    expect(app).toContain(`<${component} />`);
    // Registered but unguarded would put an authenticated shell on a public route.
    const guard = path.startsWith('/brand') ? 'BrandLayoutWrapper' : 'CreatorProtectedRoute';
    const idx = app.indexOf(`path="${path}"`);
    expect(app.slice(idx, idx + 400)).toContain(guard);
  });

  it('is reachable from the shell, not only from the first-run checklist', () => {
    // A user who dismissed the checklist, or who comes back weeks later, still needs a way in.
    expect(read('src/components/brand/brand-layout.tsx')).toContain("href: '/brand/how-it-works'");
    expect(read('src/components/creator/creator-layout.tsx')).toContain("href: '/creator/how-it-works'");
  });

  it('does not send a signed-in user out to the public marketing page', () => {
    // The creator checklist originally linked /how-it-works/creators — a logged-out page, which
    // drops the user out of the app shell mid-onboarding.
    for (const p of [
      'src/components/brand/dashboard/BrandFirstRunChecklist.tsx',
      'src/components/creator/CreatorFirstRunChecklist.tsx',
    ]) {
      expect(read(p)).not.toMatch(/flowHref="\/how-it-works\//);
    }
  });
});

describe('HowItWorksFlow rendering', () => {
  it('renders every step, title and body', () => {
    render(<HowItWorksFlow steps={BRAND_STEPS} />);
    for (const s of BRAND_STEPS) {
      expect(screen.getByText(s.title)).toBeInTheDocument();
      expect(screen.getByText(s.body)).toBeInTheDocument();
    }
  });

  it('marks the current step when the caller knows it', () => {
    render(<HowItWorksFlow steps={BRAND_STEPS} currentStep={3} />);
    expect(screen.getByText("You're here")).toBeInTheDocument();
  });

  it('marks nothing when the current step is unknown — an unmarked list beats a wrong mark', () => {
    render(<HowItWorksFlow steps={BRAND_STEPS} />);
    expect(screen.queryByText("You're here")).toBeNull();
  });
});
