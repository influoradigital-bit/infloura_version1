import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { usePageConsent } from '@/components/site/PageConsentBar';

/**
 * [Kabir H-6] Pins the consent RECORD's contract — scope, versioning, timestamp, and the
 * fail-closed direction of every unparseable case.
 *
 * The bug this guards: consent used to persist as the bare string `'accepted'` under a key shared
 * by every Festival Box edition. That recorded a decision while recording nothing that makes it
 * scopable or demonstrable, so a yes given to edition 01's three sponsors silently authorised
 * edition 02's entirely different sponsors, with no bar shown to the visitor.
 *
 * These tests exercise the hook directly rather than the page, because the invariants being pinned
 * are the storage layer's, and a page-level test would prove them only for whatever sponsor
 * fixtures happen to exist today (currently: none with a pixel id — see festival-editions.ts).
 */
describe('usePageConsent — consent record (H-6)', () => {
  const KEY = 'influora:festival-box:mumbai-2026:sponsor-pixel-consent';

  beforeEach(() => {
    window.localStorage.clear();
  });

  it('stores a versioned record with a timestamp and the consented scope, not a bare string', () => {
    const { result } = renderHook(() => usePageConsent(KEY, ['111', '222']));

    act(() => result.current.accept());

    const raw = window.localStorage.getItem(KEY);
    expect(raw).not.toBe('accepted');

    const record = JSON.parse(raw ?? '{}');
    expect(record.choice).toBe('accepted');
    expect(record.v).toBe(1);
    expect(record.scope).toEqual(['111', '222']);
    // Demonstrable consent needs a WHEN. Parseable as a real date, not just any string.
    expect(Number.isNaN(Date.parse(record.at))).toBe(false);
  });

  it('a stored acceptance does NOT cover a pixel id the visitor never saw', () => {
    const { result } = renderHook(() => usePageConsent(KEY, ['111']));
    act(() => result.current.accept());
    expect(result.current.consent).toBe('accepted');

    // A fourth sponsor joins the live edition. Their pixel is a party this visitor never agreed
    // to, so the page must be back to "not answered" — bar shown, nothing firing.
    const { result: withNewSponsor } = renderHook(() => usePageConsent(KEY, ['111', '999']));
    expect(withNewSponsor.current.consent).toBeNull();
  });

  it('a stored acceptance still covers a NARROWER scope (a sponsor leaving does not re-prompt)', () => {
    const { result } = renderHook(() => usePageConsent(KEY, ['111', '222']));
    act(() => result.current.accept());

    const { result: fewer } = renderHook(() => usePageConsent(KEY, ['111']));
    expect(fewer.current.consent).toBe('accepted');
  });

  it('scope comparison ignores ordering', () => {
    const { result } = renderHook(() => usePageConsent(KEY, ['222', '111']));
    act(() => result.current.accept());

    const { result: reordered } = renderHook(() => usePageConsent(KEY, ['111', '222']));
    expect(reordered.current.consent).toBe('accepted');
  });

  it('a DECLINE is honoured even when the scope widens — adding a sponsor must not nag', () => {
    const { result } = renderHook(() => usePageConsent(KEY, ['111']));
    act(() => result.current.decline());

    const { result: widened } = renderHook(() => usePageConsent(KEY, ['111', '999']));
    expect(widened.current.consent).toBe('declined');
  });

  it('each edition has its own record — accepting one does not authorise another', () => {
    const editionOne = 'influora:festival-box:mumbai-2026:sponsor-pixel-consent';
    const editionTwo = 'influora:festival-box:delhi-2027:sponsor-pixel-consent';

    const { result } = renderHook(() => usePageConsent(editionOne, ['111']));
    act(() => result.current.accept());

    const { result: other } = renderHook(() => usePageConsent(editionTwo, ['777']));
    expect(other.current.consent).toBeNull();
  });

  it('the pre-H-6 bare-string format reads as NOT consented, never as accepted', () => {
    window.localStorage.setItem(KEY, 'accepted');

    const { result } = renderHook(() => usePageConsent(KEY, ['111']));
    expect(result.current.consent).toBeNull();
  });

  it('a record from a future/unknown version reads as NOT consented', () => {
    window.localStorage.setItem(
      KEY,
      JSON.stringify({ v: 99, choice: 'accepted', at: new Date().toISOString(), scope: ['111'] }),
    );

    const { result } = renderHook(() => usePageConsent(KEY, ['111']));
    expect(result.current.consent).toBeNull();
  });

  it.each([
    ['not json at all', '{{{'],
    ['a record with no timestamp', JSON.stringify({ v: 1, choice: 'accepted', scope: ['111'] })],
    ['a record with a non-array scope', JSON.stringify({ v: 1, choice: 'accepted', at: 'x', scope: '111' })],
    ['a record with an unknown choice', JSON.stringify({ v: 1, choice: 'maybe', at: 'x', scope: ['111'] })],
  ])('%s reads as NOT consented', (_label, stored) => {
    window.localStorage.setItem(KEY, stored);

    const { result } = renderHook(() => usePageConsent(KEY, ['111']));
    expect(result.current.consent).toBeNull();
  });

  it('reset clears the record entirely', () => {
    const { result } = renderHook(() => usePageConsent(KEY, ['111']));
    act(() => result.current.accept());
    act(() => result.current.reset());

    expect(window.localStorage.getItem(KEY)).toBeNull();
    expect(result.current.consent).toBeNull();
  });
});
