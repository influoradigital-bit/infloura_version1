/**
 * MeeraWorkspace — the brand colour that tints the workspace must never be a mock constant.
 *
 * THE DEFECT: `MeeraWorkspace.tsx:42` called
 *   `useBrandTheme(rootRef, MOCK_BRAND_SNAPSHOT.brandColorHex)`
 * unconditionally — outside the `isApiLive()` gate every other consumer of that
 * module respects (`StageSnapshot.tsx` renders its mock card only under
 * `if (!live)`). So in live mode EVERY real brand's Meera page was tinted
 * `#E8927C`, the accent of "Kavala Skincare", a company that does not exist:
 * `--brand`, `--meera-accent`, `--meera-accent-hover/-press/-soft/-glow` on the
 * workspace root all derived from it.
 *
 * THE FIX: live mode reads the brand's OWN colour from the `analyze_site`
 * tool_result already threaded into `stagePayloads.snapshot`, and passes
 * `undefined` when none has landed — `useBrandTheme` then keeps Meera's default
 * indigo. No colour beats a wrong colour.
 *
 * WHY THESE FAIL AGAINST THE PRE-FIX COMPONENT: the pre-fix component passes
 * `MOCK_BRAND_SNAPSHOT.brandColorHex` on every render regardless of mode, so
 *   - "never passes a mock constant in live mode" fails — `#E8927C` is the first
 *     and only value `useBrandTheme` ever receives;
 *   - "uses the brand's own detected colour" fails — the real `#2B6CB0` from the
 *     analyze_site payload never reaches the hook, `#E8927C` still does;
 *   - "falls back to no colour" fails for the same reason.
 * The mock-mode case passes both before and after ON PURPOSE: it proves the fix
 * is a live-mode GATE, not a blanket deletion of the demo tint.
 *
 * Run: npx vitest run src/components/feature/meera/MeeraWorkspace.brand-colour.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * Hoisted so the `vi.mock` factories below (which vitest lifts above the
 * imports) can reach the same objects the tests mutate.
 */
const harness = vi.hoisted(() => ({
  /** Flipped per test — vitest.config.ts pins VITE_API_MODE=mock, so the real one is useless here. */
  live: true,
  /** Every `brandHex` argument `useBrandTheme` was called with, in order. */
  themeCalls: [] as Array<string | null | undefined>,
  /** What the stubbed chat panel hands to `onFunctionCall('analyze_site', …)`. */
  analyzeSitePayload: undefined as unknown,
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, isApiLive: () => harness.live };
});

// The assertion surface: capture the hex handed to the theme hook instead of
// reading CSS vars back off the DOM, so the test states the contract
// ("what colour does the workspace ask to be tinted with") rather than
// useBrandTheme's derivation maths, which has its own tests.
vi.mock('@/hooks/useBrandTheme', () => ({
  useBrandTheme: (_ref: unknown, brandHex?: string | null) => {
    harness.themeCalls.push(brandHex);
    return {
      accent: '#6D5AE6',
      accentHover: '#6D5AE6',
      accentPress: '#6D5AE6',
      accentSoft: '#6D5AE6',
      accentGlow: 'rgba(109, 90, 230, 0.35)',
      gradientStart: '#6D5AE6',
      gradientEnd: '#6D5AE6',
    };
  },
}));

// The real chat panel opens SSE, reads tokens and pulls in framer-motion; none
// of that is under test. This stub keeps the ONE seam that matters: the
// `onFunctionCall` callback MeeraWorkspace passes as `advance`, which is how a
// real `analyze_site` tool_result reaches `stagePayloads.snapshot`
// (MeeraChatPanel.tsx:881).
vi.mock('@/components/feature/meera/MeeraChatPanel', () => ({
  MeeraChatPanel: ({
    onFunctionCall,
  }: {
    onFunctionCall: (name: string, data?: unknown) => void;
  }) => (
    <button type="button" onClick={() => onFunctionCall('analyze_site', harness.analyzeSitePayload)}>
      fire analyze_site
    </button>
  ),
}));

vi.mock('@/components/feature/meera/LivingCanvas', () => ({
  LivingCanvas: () => <div data-testid="living-canvas" />,
}));

import { MeeraWorkspace } from './MeeraWorkspace';
import { liveBrandColorHex } from '@/lib/meera-brand-colour';
import { MOCK_BRAND_SNAPSHOT } from '@/data/meera-mock';

/** Every string sitting in the mock snapshot — none may ever reach the theme in live mode. */
const MOCK_STRINGS: string[] = [
  MOCK_BRAND_SNAPSHOT.name,
  MOCK_BRAND_SNAPSHOT.siteUrl,
  MOCK_BRAND_SNAPSHOT.logoInitials,
  MOCK_BRAND_SNAPSHOT.brandColorHex,
  ...MOCK_BRAND_SNAPSHOT.products.map((p) => p.name),
];

function renderWorkspace() {
  return render(
    <MemoryRouter>
      <MeeraWorkspace />
    </MemoryRouter>,
  );
}

/** The hex the tests treat as the real brand's — deliberately unlike the mock's #E8927C. */
const REAL_BRAND_HEX = '#2B6CB0';

function analyzeSiteResult(data: Record<string, unknown>) {
  return { success: true, data };
}

beforeEach(() => {
  harness.live = true;
  harness.themeCalls = [];
  harness.analyzeSitePayload = undefined;
});

describe('MeeraWorkspace brand colour (live mode)', () => {
  it('never passes a mock constant to useBrandTheme', async () => {
    harness.analyzeSitePayload = analyzeSiteResult({
      source_url: 'https://realbrand.example',
      brand_color: REAL_BRAND_HEX,
    });
    renderWorkspace();
    await userEvent.click(screen.getByRole('button', { name: /fire analyze_site/i }));

    expect(harness.themeCalls.length).toBeGreaterThan(0);
    for (const call of harness.themeCalls) {
      expect(MOCK_STRINGS).not.toContain(call);
    }
  });

  it("uses the brand's own detected colour once analyze_site has landed", async () => {
    harness.analyzeSitePayload = analyzeSiteResult({ brand_color: REAL_BRAND_HEX });
    renderWorkspace();

    // Before the tool result: nothing is loaded, so no colour at all.
    expect(harness.themeCalls.at(-1)).toBeUndefined();

    await userEvent.click(screen.getByRole('button', { name: /fire analyze_site/i }));
    expect(harness.themeCalls.at(-1)).toBe(REAL_BRAND_HEX);
  });

  it('falls back to no colour when nothing has been loaded', () => {
    renderWorkspace();
    expect(harness.themeCalls.at(-1)).toBeUndefined();
    expect(harness.themeCalls).not.toContain(MOCK_BRAND_SNAPSHOT.brandColorHex);
  });

  it('ignores an analyze_site result whose brand_color is missing or unusable', async () => {
    harness.analyzeSitePayload = analyzeSiteResult({
      source_url: 'https://realbrand.example',
      brand_color: 'rgb(255, 0, 0)',
    });
    renderWorkspace();
    await userEvent.click(screen.getByRole('button', { name: /fire analyze_site/i }));

    // Not a hex — `useBrandTheme` writes the RAW string into `--brand`, so a
    // non-hex value would land in the stylesheet. Better undefined.
    expect(harness.themeCalls.at(-1)).toBeUndefined();
  });
});

describe('MeeraWorkspace brand colour (mock mode)', () => {
  it('still tints the demo workspace with the mock snapshot colour', () => {
    harness.live = false;
    renderWorkspace();
    expect(harness.themeCalls.at(-1)).toBe(MOCK_BRAND_SNAPSHOT.brandColorHex);
  });
});

describe('liveBrandColorHex', () => {
  it('reads brand_color out of a successful analyze_site envelope', () => {
    expect(liveBrandColorHex({ success: true, data: { brand_color: '#2b6cb0' } })).toBe('#2b6cb0');
    expect(liveBrandColorHex({ success: true, data: { brand_color: '  #ABC  ' } })).toBe('#ABC');
  });

  it('returns undefined for anything it cannot trust', () => {
    expect(liveBrandColorHex(undefined)).toBeUndefined();
    expect(liveBrandColorHex(null)).toBeUndefined();
    expect(liveBrandColorHex('#E8927C')).toBeUndefined();
    expect(liveBrandColorHex({ success: false, data: { brand_color: '#2b6cb0' } })).toBeUndefined();
    expect(liveBrandColorHex({ success: true })).toBeUndefined();
    expect(liveBrandColorHex({ success: true, data: {} })).toBeUndefined();
    expect(liveBrandColorHex({ success: true, data: { brand_color: 123 } })).toBeUndefined();
    expect(liveBrandColorHex({ success: true, data: { brand_color: 'blue' } })).toBeUndefined();
    expect(liveBrandColorHex({ success: true, data: { brand_color: '#12345' } })).toBeUndefined();
    expect(
      liveBrandColorHex({ success: true, data: { brand_color: '#2b6cb0; --x: y' } }),
    ).toBeUndefined();
  });
});
