/**
 * U-3 bar items 2, 3 and 4 (PRIYA-LASTCALL-U3-U5-0917.md UF3-1) — none of these were proven by any
 * existing test. Priya mutated the source and all 74 tests across the eight files that touch this
 * hook stayed green:
 *   - Item 2: `dismiss()` refuses a non-dismissible flag (`useRiskFlagDismissals.ts` ~L136). The
 *     card never draws a dismiss control for such a flag, so the line only runs on a direct call
 *     — no test made one.
 *   - Item 3: a `sessionStorage` read/write failure (private mode, blocked site data) falls back
 *     to memory instead of throwing during render (~L73-78, ~L88-92). No test made storage throw.
 *   - Item 4 (hook half): an `undefined` scope disables dismissal — `dismiss`/`restore` are both
 *     `undefined` — rather than sharing one bucket across unrelated deals/briefs (~L121, ~L134,
 *     ~L145). No test called the hook directly at all.
 *
 * The module-level store (`storageUnavailable`/`memoryFallback`/`cachedRaw`, ~L43-51) never
 * resets once `storageUnavailable` latches true, so the storage-failure case uses
 * `vi.resetModules()` and re-imports the hook fresh — otherwise it would poison, or be poisoned
 * by, the other cases in this file.
 *
 * Run: npx vitest run src/hooks/useRiskFlagDismissals.test.ts
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RiskFlag } from '@/lib/api';

const STORAGE_KEY = 'influora.riskFlagDismissals.v1';

function flag(overrides: Partial<RiskFlag> & Pick<RiskFlag, 'code' | 'dismissible'>): RiskFlag {
  return {
    severity: 'WARN',
    title: 'A risk',
    detail: 'Detail.',
    action: 'Do something.',
    data: {},
    ...overrides,
  };
}

/** Fresh module per case — required for the storage-failure case (see file header), used
 *  everywhere else too so no case can leak real sessionStorage state into another. */
async function freshHook() {
  vi.resetModules();
  const mod = await import('./useRiskFlagDismissals');
  return mod.useRiskFlagDismissals;
}

beforeEach(() => {
  window.sessionStorage.clear();
});

afterEach(() => {
  window.sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('useRiskFlagDismissals', () => {
  it('item 2: dismiss() refuses a non-dismissible flag — storage untouched, hiddenCount stays 0', async () => {
    const useRiskFlagDismissals = await freshHook();
    const nonDismissible = flag({ code: 'HIDE_DISCLOSURE', dismissible: false });
    const { result } = renderHook(() => useRiskFlagDismissals('DEAL:d1', [nonDismissible]));

    expect(result.current.dismiss).toBeDefined();
    act(() => result.current.dismiss?.(nonDismissible));

    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(result.current.hiddenCount).toBe(0);
    expect(result.current.visibleFlags).toEqual([nonDismissible]);
  });

  it('item 3: sessionStorage throwing on read AND write falls back to memory — never throws, dismiss/restore still work', async () => {
    const useRiskFlagDismissals = await freshHook();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    const dismissible = flag({ code: 'EXCLUSIVITY_LONG', dismissible: true });
    // If either try/catch were missing, this call itself throws synchronously and the test fails
    // right here — that IS the "renders without throwing" assertion, not a separate wrapper.
    const { result } = renderHook(() => useRiskFlagDismissals('DEAL:d2', [dismissible]));

    // Renders, flags visible, despite storage being unusable.
    expect(result.current.visibleFlags).toEqual([dismissible]);
    expect(result.current.hiddenCount).toBe(0);

    // dismiss()/restore() still work — via the in-memory fallback, not sessionStorage.
    expect(() => act(() => result.current.dismiss?.(dismissible))).not.toThrow();
    expect(result.current.hiddenCount).toBe(1);
    expect(result.current.visibleFlags).toEqual([]);

    expect(() => act(() => result.current.restore?.())).not.toThrow();
    expect(result.current.hiddenCount).toBe(0);
    expect(result.current.visibleFlags).toEqual([dismissible]);
  });

  it('item 3, N1 (PRIYA-LASTCALL-U3-U5-0917.md re-check): sessionStorage throwing on WRITE ONLY (read succeeds) still falls back to memory without throwing', async () => {
    // The read-throws case above dies at the very first `getSnapshot()` call (on mount), so it
    // can never exercise `write()`'s own try/catch (~L88-92) — removing ONLY that one stayed
    // green against it. This case leaves `getItem` real (it succeeds, storage starts empty) and
    // only makes `setItem` throw, so `write()`'s try/catch is the ONLY thing standing between a
    // successful mount and an uncaught throw on the first `dismiss()`.
    const useRiskFlagDismissals = await freshHook();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    const dismissible = flag({ code: 'EXCLUSIVITY_LONG', dismissible: true });
    const { result } = renderHook(() => useRiskFlagDismissals('DEAL:d3', [dismissible]));
    expect(result.current.visibleFlags).toEqual([dismissible]);

    // If the write try/catch were missing, this line throws synchronously and the test fails.
    expect(() => act(() => result.current.dismiss?.(dismissible))).not.toThrow();
    expect(result.current.hiddenCount).toBe(1);
    expect(result.current.visibleFlags).toEqual([]);
    // Nothing landed in real storage — the failed write fell back to memory instead.
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();

    expect(() => act(() => result.current.restore?.())).not.toThrow();
    expect(result.current.hiddenCount).toBe(0);
    expect(result.current.visibleFlags).toEqual([dismissible]);
  });

  it('item 4: an undefined scope disables dismissal — dismiss and restore are both undefined, flags still render', async () => {
    const useRiskFlagDismissals = await freshHook();
    const dismissible = flag({ code: 'EXCLUSIVITY_LONG', dismissible: true });
    const { result } = renderHook(() => useRiskFlagDismissals(undefined, [dismissible]));

    expect(result.current.dismiss).toBeUndefined();
    expect(result.current.restore).toBeUndefined();
    expect(result.current.visibleFlags).toEqual([dismissible]);
    expect(result.current.hiddenCount).toBe(0);
  });
});
