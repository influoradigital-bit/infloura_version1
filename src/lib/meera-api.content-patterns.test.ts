/**
 * T22 (Meera intelligence v1 spec §4.6/§7) — get_my_content_patterns' TypeScript contract:
 * `isGetMyContentPatternsPayload` accepts the real fixture (a Jackson dump of
 * `CreatorToolDtos.GetMyContentPatternsResult`, the same fixture T20 checks Java's own
 * serialisation against) and rejects one missing a required array or boolean, and English +
 * Hindi work-trail labels exist for the tool.
 *
 * Run: node node_modules/vitest/vitest.mjs run src/lib/meera-api.content-patterns.test.ts
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { isGetMyContentPatternsPayload } from './meera-api';
import { TOOL_TRAIL_LABELS } from './copy/meera-chat';

const FIXTURE_PATH = join(
  process.cwd(),
  'influora-ai',
  'tests',
  'fixtures',
  'creator_tools',
  'get_my_content_patterns.real.json',
);

/** The real Java-serialised fixture, read fresh each time — never a hand-built object
 *  (feedback_hand_built_fixture_hides_missing_field.md: a hand-built fixture can drift from
 *  the wire shape it is supposed to stand in for without any test noticing). */
function realFixture(): Record<string, unknown> {
  return JSON.parse(readFileSync(FIXTURE_PATH, 'utf8')) as Record<string, unknown>;
}

function without(fixture: Record<string, unknown>, key: string): Record<string, unknown> {
  const copy = { ...fixture };
  delete copy[key];
  return copy;
}

describe('isGetMyContentPatternsPayload', () => {
  it('accepts the real fixture (T20 wire shape)', () => {
    expect(isGetMyContentPatternsPayload(realFixture())).toBe(true);
  });

  it('rejects a payload missing what_works', () => {
    expect(isGetMyContentPatternsPayload(without(realFixture(), 'what_works'))).toBe(false);
  });

  it('rejects a payload missing baseline, best_posts or weak_posts', () => {
    for (const key of ['baseline', 'best_posts', 'weak_posts']) {
      expect(isGetMyContentPatternsPayload(without(realFixture(), key)), `missing ${key}`).toBe(
        false,
      );
    }
  });

  it('rejects a payload missing the available or enough_data booleans', () => {
    expect(isGetMyContentPatternsPayload(without(realFixture(), 'available'))).toBe(false);
    expect(isGetMyContentPatternsPayload(without(realFixture(), 'enough_data'))).toBe(false);
  });

  it('rejects a list field that arrived as something other than an array', () => {
    expect(
      isGetMyContentPatternsPayload({ ...realFixture(), what_works: 'not-an-array' }),
    ).toBe(false);
  });

  it('rejects non-object input', () => {
    expect(isGetMyContentPatternsPayload(null)).toBe(false);
    expect(isGetMyContentPatternsPayload(undefined)).toBe(false);
    expect(isGetMyContentPatternsPayload('nope')).toBe(false);
    expect(isGetMyContentPatternsPayload(42)).toBe(false);
  });

  it('accepts the not-connected / not-enough-data shapes, which carry no post lists at all data-wise but still send [] for each', () => {
    expect(
      isGetMyContentPatternsPayload({
        available: false,
        reason: 'NOT_CONNECTED',
        enough_data: false,
        settled_posts: 0,
        unsettled_posts: 0,
        min_posts_needed: 10,
        lookback_days: 90,
        baseline: [],
        best_posts: [],
        weak_posts: [],
        what_works: [],
      }),
    ).toBe(true);
  });
});

describe('get_my_content_patterns work-trail labels', () => {
  it('has non-empty English and Hindi text for running/done/failed', () => {
    const labels = TOOL_TRAIL_LABELS.get_my_content_patterns;
    expect(labels).toBeTruthy();
    for (const state of ['running', 'done', 'failed'] as const) {
      expect(labels[state].en.length).toBeGreaterThan(0);
      expect(labels[state].hi.length).toBeGreaterThan(0);
    }
  });
});
