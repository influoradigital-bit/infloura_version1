import { describe, expect, it } from 'vitest';

import {
  ASK_MY_RATE_PROMPT,
  COMPOSER_PLACEHOLDER,
  DESK_HEADER,
  DESK_TILE_ALL_CAUGHT_UP,
  DESK_TILE_ASK_MY_RATE,
  DESK_TILE_ATTENTION_PLURAL,
  DESK_TILE_ATTENTION_SINGULAR,
  DESK_TILE_AVAILABLE_BALANCE,
  DESK_TILE_PENDING_PAYOUTS,
  DESK_TILE_PROFILE_VIEWS,
  HEADER_STATUS_LISTENING,
  HEADER_STATUS_ONLINE,
  HEADER_STATUS_SPEAKING,
  HEADER_STATUS_WORKING,
  STARTER_PROMPTS,
  TOOL_TRAIL_LABELS,
  TRAIL_HIDE,
  TRAIL_SHOW,
  TRAIL_UNDERSTANDING,
  TRUST_LINE,
  attentionLabel,
  pickLang,
  trailSummary,
  type BilingualText,
} from './meera-chat';
import { CREATOR_TOOL_NAMES } from '@/lib/meera-api';

/** Every plain `{ en, hi }` pair this file exports, collected once so the "has both languages"
 *  and "no escrow" checks below cover every string without hand-maintaining a second list. */
const SIMPLE_PAIRS: Record<string, BilingualText> = {
  HEADER_STATUS_ONLINE,
  HEADER_STATUS_WORKING,
  HEADER_STATUS_LISTENING,
  HEADER_STATUS_SPEAKING,
  TRUST_LINE,
  COMPOSER_PLACEHOLDER,
  TRAIL_UNDERSTANDING,
  TRAIL_SHOW,
  TRAIL_HIDE,
  DESK_HEADER,
  DESK_TILE_ATTENTION_SINGULAR,
  DESK_TILE_ATTENTION_PLURAL,
  DESK_TILE_ALL_CAUGHT_UP,
  DESK_TILE_PENDING_PAYOUTS,
  DESK_TILE_AVAILABLE_BALANCE,
  DESK_TILE_PROFILE_VIEWS,
  DESK_TILE_ASK_MY_RATE,
  ASK_MY_RATE_PROMPT,
};

describe('meera-chat copy — every key has en and hi', () => {
  it.each(Object.entries(SIMPLE_PAIRS))('%s has a non-empty en and hi string', (_name, pair) => {
    expect(typeof pair.en).toBe('string');
    expect(pair.en.trim().length).toBeGreaterThan(0);
    expect(typeof pair.hi).toBe('string');
    expect(pair.hi.trim().length).toBeGreaterThan(0);
  });

  it('has a trail label set (running/done/failed, en+hi) for every creator tool name', () => {
    for (const name of CREATOR_TOOL_NAMES) {
      const set = TOOL_TRAIL_LABELS[name];
      expect(set, `missing trail labels for ${name}`).toBeDefined();
      for (const state of ['running', 'done', 'failed'] as const) {
        expect(set[state].en.trim().length).toBeGreaterThan(0);
        expect(set[state].hi.trim().length).toBeGreaterThan(0);
      }
    }
  });

  it('has en and hi text for every starter prompt', () => {
    expect(STARTER_PROMPTS.length).toBe(4);
    for (const prompt of STARTER_PROMPTS) {
      expect(prompt.text.en.trim().length).toBeGreaterThan(0);
      expect(prompt.text.hi.trim().length).toBeGreaterThan(0);
    }
  });
});

describe('meera-chat copy — no "escrow" anywhere in user copy', () => {
  const allStrings: string[] = [
    ...Object.values(SIMPLE_PAIRS).flatMap((p) => [p.en, p.hi]),
    ...Object.values(TOOL_TRAIL_LABELS).flatMap((set) => [
      set.running.en,
      set.running.hi,
      set.done.en,
      set.done.hi,
      set.failed.en,
      set.failed.hi,
    ]),
    ...STARTER_PROMPTS.flatMap((p) => [p.text.en, p.text.hi]),
  ];

  it.each(allStrings)('does not mention escrow: %s', (text) => {
    expect(text.toLowerCase()).not.toContain('escrow');
  });
});

describe('pickLang', () => {
  it('reads the English half for an en-* language tag', () => {
    expect(pickLang('en-IN', TRUST_LINE)).toBe(TRUST_LINE.en);
  });

  it('reads the Hindi half for an hi-* language tag', () => {
    expect(pickLang('hi-IN', TRUST_LINE)).toBe(TRUST_LINE.hi);
  });
});

describe('trailSummary', () => {
  it('uses singular English for one thing', () => {
    expect(trailSummary('en-IN', 1)).toBe('Meera did 1 thing');
  });

  it('uses plural English for more than one thing', () => {
    expect(trailSummary('en-IN', 3)).toBe('Meera did 3 things');
  });

  it('renders a Hindi summary for hi-IN', () => {
    expect(trailSummary('hi-IN', 3)).toContain('3');
    expect(trailSummary('hi-IN', 3).toLowerCase()).not.toContain('escrow');
  });
});

describe('attentionLabel', () => {
  it('uses the singular English label for exactly 1', () => {
    expect(attentionLabel('en-IN', 1)).toBe(DESK_TILE_ATTENTION_SINGULAR.en);
  });

  it('uses the plural English label for 0 or more than 1', () => {
    expect(attentionLabel('en-IN', 0)).toBe(DESK_TILE_ATTENTION_PLURAL.en);
    expect(attentionLabel('en-IN', 4)).toBe(DESK_TILE_ATTENTION_PLURAL.en);
  });

  it('uses the Hindi label for hi-IN', () => {
    expect(attentionLabel('hi-IN', 1)).toBe(DESK_TILE_ATTENTION_SINGULAR.hi);
    expect(attentionLabel('hi-IN', 4)).toBe(DESK_TILE_ATTENTION_PLURAL.hi);
  });
});
