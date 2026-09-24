/**
 * PHASE-C-SPEC.md §1/§4 — `parseMeeraScript`/`parseMeeraReview` happy paths and every refusal
 * case the spec names. A half-parsed card must never render, so every negative case here asserts
 * `null`, never a partially-filled object.
 */
import { describe, expect, it } from 'vitest';

import { parseMeeraReview, parseMeeraScript } from './meera-result-cards';

const VALID_SCRIPT = [
  'SCRIPT',
  'Title: 3 saffron mistakes to avoid',
  'Length: 30s',
  'Hook: Stop buying saffron until you watch this',
  '0-10s: Show the box, ask "is your saffron real?"',
  '10-20s: Do the warm-water color test on camera',
  '20-30s: Show the certificate and say why it matters',
  'CTA: Link in bio for real Kashmiri saffron',
  'Why: Problem-agitate-solve structure, curiosity-gap hook template',
].join('\n');

const VALID_REVIEW = [
  'REVIEW',
  'Working: Your reels get strong watch time in the first 3 seconds',
  'Not working: Your bio has no clear call to action',
  'Next 1: Add a link-in-bio call to action today',
  'Next 2: Post one reel this week using the saffron hook template',
  'Next 3: Reply to your last 5 comments to lift engagement',
].join('\n');

describe('parseMeeraScript — happy paths', () => {
  it('parses a well-formed 30s script with 3 beats', () => {
    const result = parseMeeraScript(VALID_SCRIPT);
    expect(result).not.toBeNull();
    expect(result).toEqual({
      title: '3 saffron mistakes to avoid',
      length: 30,
      hook: 'Stop buying saffron until you watch this',
      beats: [
        { from: 0, to: 10, text: 'Show the box, ask "is your saffron real?"' },
        { from: 10, to: 20, text: 'Do the warm-water color test on camera' },
        { from: 20, to: 30, text: 'Show the certificate and say why it matters' },
      ],
      cta: 'Link in bio for real Kashmiri saffron',
      why: 'Problem-agitate-solve structure, curiosity-gap hook template',
    });
  });

  it('parses a 6-beat 60s script', () => {
    const text = [
      'SCRIPT',
      'Title: Six quick shots for a 60 second reel',
      'Length: 60s',
      'Hook: This is the fastest way to shoot a reel today',
      '0-10s: Open on your face, say the hook line',
      '10-20s: Cut to the product on the table',
      '20-30s: Show it in use, hands only',
      '30-40s: Cut back to your face for a reaction',
      '40-50s: Show the result close up',
      '50-60s: End on your face for the CTA',
      'CTA: Follow for more reel breakdowns',
    ].join('\n');
    const result = parseMeeraScript(text);
    expect(result).not.toBeNull();
    expect(result?.beats).toHaveLength(6);
    expect(result?.why).toBeUndefined();
  });

  it('is tolerant of \\r\\n line endings', () => {
    const crlf = VALID_SCRIPT.split('\n').join('\r\n');
    expect(parseMeeraScript(crlf)).toEqual(parseMeeraScript(VALID_SCRIPT));
  });

  it('is tolerant of leading/trailing blank lines', () => {
    const padded = `\n\n${VALID_SCRIPT}\n\n`;
    expect(parseMeeraScript(padded)).toEqual(parseMeeraScript(VALID_SCRIPT));
  });

  it('is tolerant of extra spacing around the colon and mixed key case', () => {
    const spaced = VALID_SCRIPT.replace('Title:', 'title :').replace('Length: 30s', 'LENGTH:  30s').replace(
      'SCRIPT',
      'script',
    );
    const result = parseMeeraScript(spaced);
    expect(result).not.toBeNull();
    expect(result?.title).toBe('3 saffron mistakes to avoid');
    expect(result?.length).toBe(30);
  });

  it('accepts Hindi text in the values', () => {
    const hindi = [
      'SCRIPT',
      'Title: केसर खरीदने से पहले ये 3 गलतियाँ न करें',
      'Length: 15s',
      'Hook: असली केसर की पहचान अभी देखें',
      '0-5s: डिब्बा दिखाएं और सवाल पूछें',
      '5-10s: पानी में टेस्ट करें',
      '10-15s: सर्टिफिकेट दिखाएं',
      'CTA: बायो में लिंक देखें',
    ].join('\n');
    const result = parseMeeraScript(hindi);
    expect(result).not.toBeNull();
    expect(result?.hook).toBe('असली केसर की पहचान अभी देखें');
  });

  it('accepts a script with no Why line', () => {
    const noWhy = VALID_SCRIPT.split('\n').filter((line) => !line.startsWith('Why:')).join('\n');
    const result = parseMeeraScript(noWhy);
    expect(result).not.toBeNull();
    expect(result?.why).toBeUndefined();
  });
});

describe('parseMeeraScript — refusals', () => {
  it('returns null when the first line is not SCRIPT', () => {
    expect(parseMeeraScript(VALID_SCRIPT.replace('SCRIPT', 'Here is your script:'))).toBeNull();
  });

  it('returns null when a required key is missing (Hook dropped)', () => {
    const missingHook = VALID_SCRIPT.split('\n').filter((line) => !line.startsWith('Hook:')).join('\n');
    expect(parseMeeraScript(missingHook)).toBeNull();
  });

  it('returns null when Length is not one of the fixed values', () => {
    expect(parseMeeraScript(VALID_SCRIPT.replace('Length: 30s', 'Length: 25s'))).toBeNull();
  });

  it('returns null when a timed line is malformed', () => {
    expect(
      parseMeeraScript(VALID_SCRIPT.replace('0-10s: Show the box, ask "is your saffron real?"', '0 to 10s: Show the box')),
    ).toBeNull();
  });

  it('returns null when the beats are out of order', () => {
    const lines = VALID_SCRIPT.split('\n');
    const [beat1, beat2] = [lines[4], lines[5]];
    lines[4] = beat2;
    lines[5] = beat1;
    expect(parseMeeraScript(lines.join('\n'))).toBeNull();
  });

  it('returns null when the beats overlap', () => {
    expect(
      parseMeeraScript(VALID_SCRIPT.replace('10-20s: Do the warm-water color test on camera', '5-20s: Do the warm-water color test on camera')),
    ).toBeNull();
  });

  it('returns null when the beats leave a gap', () => {
    expect(
      parseMeeraScript(VALID_SCRIPT.replace('10-20s: Do the warm-water color test on camera', '12-20s: Do the warm-water color test on camera')),
    ).toBeNull();
  });

  it('returns null when there are fewer than 3 beats', () => {
    const twoBeats = [
      'SCRIPT',
      'Title: Too short a script',
      'Length: 15s',
      'Hook: Watch this',
      '0-8s: First shot',
      '8-15s: Second shot',
      'CTA: Follow me',
    ].join('\n');
    expect(parseMeeraScript(twoBeats)).toBeNull();
  });

  it('returns null when the last beat does not end at the stated length', () => {
    const short = VALID_SCRIPT.replace('20-30s: Show the certificate and say why it matters', '20-25s: Show the certificate and say why it matters');
    expect(parseMeeraScript(short)).toBeNull();
  });

  it('returns null for plain conversational text', () => {
    expect(parseMeeraScript('Sure, here is what I think about your last post...')).toBeNull();
  });

  it('returns null for an empty string', () => {
    expect(parseMeeraScript('')).toBeNull();
  });
});

describe('parseMeeraReview — happy paths', () => {
  it('parses a well-formed review', () => {
    const result = parseMeeraReview(VALID_REVIEW);
    expect(result).toEqual({
      working: 'Your reels get strong watch time in the first 3 seconds',
      notWorking: 'Your bio has no clear call to action',
      nextSteps: [
        'Add a link-in-bio call to action today',
        'Post one reel this week using the saffron hook template',
        'Reply to your last 5 comments to lift engagement',
      ],
    });
  });

  it('is tolerant of \\r\\n, blank padding, case, and Hindi values', () => {
    const messy = `\r\n${VALID_REVIEW.split('\n').join('\r\n').replace('REVIEW', 'review').replace('Working:', 'working :')}\r\n\r\n`;
    expect(parseMeeraReview(messy)).toEqual(parseMeeraReview(VALID_REVIEW));

    const hindi = [
      'REVIEW',
      'Working: आपकी रील्स अच्छा वॉच टाइम पा रही हैं',
      'Not working: आपकी बायो में कोई CTA नहीं है',
      'Next 1: आज ही बायो में लिंक जोड़ें',
      'Next 2: इस हफ्ते एक रील पोस्ट करें',
      'Next 3: पिछले 5 कमेंट्स का जवाब दें',
    ].join('\n');
    const result = parseMeeraReview(hindi);
    expect(result).not.toBeNull();
    expect(result?.nextSteps).toHaveLength(3);
  });
});

describe('parseMeeraReview — refusals', () => {
  it('returns null when the first line is not REVIEW', () => {
    expect(parseMeeraReview(VALID_REVIEW.replace('REVIEW', 'Here is my review:'))).toBeNull();
  });

  it('returns null when a required key is missing (Not working dropped)', () => {
    const missing = VALID_REVIEW.split('\n').filter((line) => !line.startsWith('Not working:')).join('\n');
    expect(parseMeeraReview(missing)).toBeNull();
  });

  it('returns null with fewer than 3 next steps', () => {
    const twoSteps = VALID_REVIEW.split('\n').filter((line) => !line.startsWith('Next 3:')).join('\n');
    expect(parseMeeraReview(twoSteps)).toBeNull();
  });

  it('returns null when the next-step keys are out of order', () => {
    const lines = VALID_REVIEW.split('\n');
    const [next1, next2] = [lines[3], lines[4]];
    lines[3] = next2;
    lines[4] = next1;
    expect(parseMeeraReview(lines.join('\n'))).toBeNull();
  });

  it('returns null for plain conversational text', () => {
    expect(parseMeeraReview('Here is how your profile looks right now...')).toBeNull();
  });

  it('returns null for an empty string', () => {
    expect(parseMeeraReview('')).toBeNull();
  });
});
