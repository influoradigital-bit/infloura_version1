/**
 * `get_my_audience` (owner decision F, 2026-09-26) on the app side: the tool name is known (so its
 * step shows in the work trail instead of nothing), both label tables name it, and
 * `isGetMyAudiencePayload` accepts the shared payload shape (followers / engaged_this_month, each
 * {available, reason, as_of, age[{band,pct}], gender[{label,pct}], top_cities[string],
 * top_countries[{code,pct}]}) and rejects a wrong-typed one.
 */
import { describe, expect, it } from 'vitest';

import { TOOL_TRAIL_LABELS } from './copy/meera-chat';
import { CREATOR_TOOL_NAMES, isCreatorToolName, isGetMyAudiencePayload } from './meera-api';

const AVAILABLE = {
  available: true,
  reason: null,
  as_of: '2026-09-21',
  age: [
    { band: '18-24', pct: 41 },
    { band: '25-34', pct: 38 },
  ],
  gender: [
    { label: 'Women', pct: 64 },
    { label: 'Men', pct: 33 },
    { label: 'Unknown', pct: 3 },
  ],
  top_cities: ['Mumbai, Maharashtra', 'Pune, Maharashtra'],
  top_countries: [{ code: 'IN', pct: 88 }],
};

const NOT_AVAILABLE = {
  available: false,
  reason: 'Fewer than 100 engagements this month, so Instagram does not share this yet.',
  as_of: null,
  age: [],
  gender: [],
  top_cities: [],
  top_countries: [],
};

describe('get_my_audience is a known creator tool', () => {
  it('is in CREATOR_TOOL_NAMES, so its step is not dropped', () => {
    expect(CREATOR_TOOL_NAMES).toContain('get_my_audience');
    expect(isCreatorToolName('get_my_audience')).toBe(true);
  });

  it('has non-empty English and Hindi work-trail labels, running says "Checking your audience…"', () => {
    const labels = TOOL_TRAIL_LABELS.get_my_audience;
    expect(labels.running.en).toBe('Checking your audience…');
    for (const state of ['running', 'done', 'failed'] as const) {
      expect(labels[state].en.length).toBeGreaterThan(0);
      expect(labels[state].hi.length).toBeGreaterThan(0);
    }
  });
});

describe('isGetMyAudiencePayload', () => {
  it('accepts both sections available, and engaged not available with its reason', () => {
    expect(isGetMyAudiencePayload({ followers: AVAILABLE, engaged_this_month: AVAILABLE })).toBe(true);
    expect(isGetMyAudiencePayload({ followers: AVAILABLE, engaged_this_month: NOT_AVAILABLE })).toBe(true);
  });

  it('reads null and absent the same (a NON_NULL Java record omits nulls)', () => {
    const omitted = { available: false, reason: 'Instagram is not connected.' };
    expect(isGetMyAudiencePayload({ followers: omitted, engaged_this_month: omitted })).toBe(true);
    const nulls = { ...NOT_AVAILABLE, age: null, gender: null, top_cities: null, top_countries: null };
    expect(isGetMyAudiencePayload({ followers: nulls, engaged_this_month: nulls })).toBe(true);
  });

  it('rejects a payload missing either section', () => {
    expect(isGetMyAudiencePayload({ followers: AVAILABLE })).toBe(false);
    expect(isGetMyAudiencePayload({ engaged_this_month: AVAILABLE })).toBe(false);
    expect(isGetMyAudiencePayload(null)).toBe(false);
    expect(isGetMyAudiencePayload([])).toBe(false);
  });

  it('rejects a section without a boolean available', () => {
    const { available: _a, ...noFlag } = AVAILABLE;
    expect(isGetMyAudiencePayload({ followers: noFlag, engaged_this_month: AVAILABLE })).toBe(false);
    expect(isGetMyAudiencePayload({ followers: { ...AVAILABLE, available: 'yes' }, engaged_this_month: AVAILABLE })).toBe(false);
  });

  it.each([
    ['age item with a number band', { age: [{ band: 18, pct: 41 }] }],
    ['gender pct as a string', { gender: [{ label: 'Women', pct: '64' }] }],
    ['country without a code', { top_countries: [{ pct: 88 }] }],
    ['a city that is not a string', { top_cities: [{ name: 'Mumbai' }] }],
    ['age not a list', { age: { band: '18-24', pct: 41 } }],
    ['reason as an object', { reason: { text: 'x' } }],
    ['as_of as a number', { as_of: 20260921 }],
    ['pct not finite', { age: [{ band: '18-24', pct: Number.NaN }] }],
  ])('rejects a wrong-typed field: %s', (_name, patch) => {
    expect(
      isGetMyAudiencePayload({ followers: AVAILABLE, engaged_this_month: { ...AVAILABLE, ...patch } }),
    ).toBe(false);
  });
});
