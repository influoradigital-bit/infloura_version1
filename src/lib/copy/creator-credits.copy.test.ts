import { describe, expect, it } from 'vitest';

import { CREATOR_CREDITS_COPY, creditsCopy, creditsCopyWithCta, resolveCreatorCreditsLanguage } from './creator-credits';

const BANNED_WORDS = ['escrow', 'upgrade'];

describe('creator-credits copy (A51 — complete bilingual copy)', () => {
  it('has both en and hi for every key, non-empty', () => {
    const keys = Object.keys(CREATOR_CREDITS_COPY);
    expect(keys.length).toBeGreaterThan(0);
    keys.forEach((key) => {
      const entry = CREATOR_CREDITS_COPY[key as keyof typeof CREATOR_CREDITS_COPY];
      expect(entry.en, `${key}.en`).toBeTruthy();
      expect(entry.hi, `${key}.hi`).toBeTruthy();
    });
  });

  it('never says "escrow" or "upgrade" in either language', () => {
    Object.entries(CREATOR_CREDITS_COPY).forEach(([key, entry]) => {
      BANNED_WORDS.forEach((word) => {
        expect(entry.en.toLowerCase(), `${key}.en`).not.toContain(word);
        expect(entry.hi.toLowerCase(), `${key}.hi`).not.toContain(word);
      });
    });
  });

  it('resolves language from creator_language-shaped values', () => {
    expect(resolveCreatorCreditsLanguage('hi-IN')).toBe('hi');
    expect(resolveCreatorCreditsLanguage('hi')).toBe('hi');
    expect(resolveCreatorCreditsLanguage('en-IN')).toBe('en');
    expect(resolveCreatorCreditsLanguage(undefined)).toBe('en');
    expect(resolveCreatorCreditsLanguage(null)).toBe('en');
  });

  it('interpolates placeholders per language', () => {
    expect(creditsCopy('pill.normal', 'en', { n: 12 })).toBe('12 credits');
    expect(creditsCopy('pill.normal', 'hi-IN', { n: 12 })).toBe('12 क्रेडिट्स');
    expect(creditsCopy('sheet.balance', 'en', { total: 55, free: 40, paid: 15 })).toBe(
      'Current balance: 55 credits (40 free, 15 paid)',
    );
    expect(creditsCopy('expiring', 'en', { count: 20, date: '30 Sep' })).toBe(
      'Reminder: 20 paid credits expire on 30 Sep.',
    );
  });

  it('splits a trailing [CTA] off welcome/zeroBanner without losing the exact spec wording', () => {
    const welcome = creditsCopyWithCta('welcome', 'en');
    expect(welcome.cta).toBe('Start chatting');
    expect(welcome.message.endsWith('[Start chatting]')).toBe(false);
    expect(welcome.message).toContain('40 free credits');

    const zero = creditsCopyWithCta('zeroBanner', 'hi-IN');
    expect(zero.cta).toBe('खरीदें — ₹249');
    expect(zero.message).not.toContain('[');
  });

  it('the server refusal templates (exhausted/cap) match the spec wording verbatim', () => {
    expect(CREATOR_CREDITS_COPY.exhausted.en).toContain("You're out of credits");
    expect(CREATOR_CREDITS_COPY.cap.en).toContain("today's 30 credits");
  });
});
