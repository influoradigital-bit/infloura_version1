/**
 * `prop_position` in the photo check's `shot_context` (spec v2 Phase 6): the shot card's prop value
 * is sent, right after `sit_or_walk` in priority, a `?` card value is never sent, and influora-ai's
 * `frame_check.py` accepts every key the app sends (it silently drops a key it does not list, so a
 * key missing there would pass every app test and never reach the AI).
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { SHOT_CONTEXT_MAX_CHARS, serializeShotContext, type MeeraShotContext } from './meera-api';

const FULL: Required<MeeraShotContext> = {
  angle: 'Medium close-up',
  action: 'hold up the serum',
  prop: 'serum bottle',
  where: 'Bedroom desk',
  light: 'window, your left',
  on_camera: 'Talk to the lens',
  sit_or_walk: 'Sit',
  prop_position: 'right-hand',
  line: '3-8s. Medium close-up - show the serum',
};

/** The key order the app sends, read from the serializer's own output (not retyped). */
function sentKeyOrder(): string[] {
  const json = serializeShotContext(FULL);
  expect(json).not.toBeNull();
  return Object.keys(JSON.parse(json!));
}

describe('shot_context prop_position', () => {
  it('sends the card prop value as prop_position', () => {
    const json = serializeShotContext({ line: 'beat 2', prop_position: 'right-hand' });
    expect(JSON.parse(json!)).toEqual({ line: 'beat 2', prop_position: 'right-hand' });
    expect(JSON.parse(serializeShotContext({ prop_position: ' centre-table ' })!)).toEqual({
      prop_position: 'centre-table',
    });
  });

  it('sits right after sit_or_walk in priority', () => {
    const order = sentKeyOrder();
    expect(order).toHaveLength(Object.keys(FULL).length);
    expect(order.slice(0, 4)).toEqual(['line', 'on_camera', 'sit_or_walk', 'prop_position']);
  });

  it('survives the trim when long text fills the budget, before angle, where and the rest', () => {
    const long = 'x'.repeat(290);
    const json = serializeShotContext({
      ...FULL,
      line: long,
      angle: long,
      action: long,
      where: long,
      light: long,
      prop: long,
    });
    expect(json).not.toBeNull();
    expect(json!.length).toBeLessThanOrEqual(SHOT_CONTEXT_MAX_CHARS);
    const sent = JSON.parse(json!);
    expect(sent.prop_position).toBe('right-hand');
    expect(sent.sit_or_walk).toBe('Sit');
    // The lowest-priority keys went first.
    expect(sent.prop).toBeUndefined();
    expect(sent.light).toBeUndefined();
  });

  it('never sends a "?" (a shot card field that is not set yet)', () => {
    expect(serializeShotContext({ prop_position: '?' })).toBeNull();
    expect(JSON.parse(serializeShotContext({ line: 'beat 1', prop_position: ' ? ', light: '?' })!)).toEqual({
      line: 'beat 1',
    });
  });
});

describe('influora-ai accepts every shot_context key the app sends', () => {
  it('frame_check.py SHOT_CONTEXT_KEYS lists each key', () => {
    const source = readFileSync(join(process.cwd(), 'influora-ai', 'app', 'prompt', 'frame_check.py'), 'utf8');
    const tuple = source.match(/^SHOT_CONTEXT_KEYS[^=]*=\s*\(([\s\S]*?)\)/m)?.[1];
    if (!tuple) throw new Error('frame_check.py no longer declares SHOT_CONTEXT_KEYS as a tuple');
    const pythonKeys = [...tuple.matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
    expect(pythonKeys.length).toBeGreaterThan(0);
    const missing = sentKeyOrder().filter((key) => !pythonKeys.includes(key));
    expect(missing).toEqual([]);
  });
});
