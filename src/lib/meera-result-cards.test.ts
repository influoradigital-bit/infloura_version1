/**
 * `parseMeeraScript`/`parseMeeraReview` happy paths and every refusal case named in this file's
 * own doc comment. A half-parsed card must never render, so every negative script case asserts
 * `undefined` (the review parser is unchanged Phase-C work and still asserts `null`).
 *
 * `VALID_SCRIPT` below is built directly from `influora-ai/app/prompt/creator_persona.py`'s "Full
 * script format" section — see `meera-result-cards.sync.test.ts` for the automated guard that
 * keeps the two from drifting apart.
 */
import { describe, expect, it } from 'vitest';

import { parseMeeraReview, parseMeeraScript } from './meera-result-cards';

const VALID_SCRIPT = [
  'Idea: 3 saffron mistakes to avoid',
  'Plan: for new saffron buyers; curiosity; grow followers; 30s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
  'Action: hold up the saffron box and speak to camera',
  'Success looks like: reel gets watched to the end and shared to a friend',
  'Script:',
  '0-10s. Shot: Close-up on your face - hold up the saffron box. Say: "Is your saffron even real?". On screen: REAL vs FAKE',
  '10-20s. Shot: Overhead on your hands - do the warm-water color test. Say: "Watch what happens in water". On screen: THE WATER TEST',
  '20-30s. Shot: Close-up on the certificate - hold it next to the box. Say: "This is what real Kashmiri saffron looks like". On screen: GI CERTIFIED',
  'Caption: Would you have spotted the fake one? Link in bio for real Kashmiri saffron. #saffron #kashmir',
  'Before you shoot: 1) Charge your phone to 100% 2) Wipe the counter clean 3) Keep the certificate within reach',
  'Why this works: Problem-agitate-solve structure, curiosity-gap hook template',
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
  it('parses a well-formed rich-format script with 3 beats', () => {
    const result = parseMeeraScript(VALID_SCRIPT);
    expect(result).not.toBeUndefined();
    expect(result).toEqual({
      idea: '3 saffron mistakes to avoid',
      plan: 'for new saffron buyers; curiosity; grow followers; 30s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
      action: 'hold up the saffron box and speak to camera',
      successLooksLike: 'reel gets watched to the end and shared to a friend',
      beats: [
        {
          from: 0,
          to: 10,
          shot: 'Close-up on your face - hold up the saffron box',
          say: 'Is your saffron even real?',
          onScreen: 'REAL vs FAKE',
        },
        {
          from: 10,
          to: 20,
          shot: 'Overhead on your hands - do the warm-water color test',
          say: 'Watch what happens in water',
          onScreen: 'THE WATER TEST',
        },
        {
          from: 20,
          to: 30,
          shot: 'Close-up on the certificate - hold it next to the box',
          say: 'This is what real Kashmiri saffron looks like',
          onScreen: 'GI CERTIFIED',
        },
      ],
      caption: 'Would you have spotted the fake one? Link in bio for real Kashmiri saffron. #saffron #kashmir',
      beforeYouShoot: [
        'Charge your phone to 100%',
        'Wipe the counter clean',
        'Keep the certificate within reach',
      ],
      whyThisWorks: 'Problem-agitate-solve structure, curiosity-gap hook template',
      followUp: undefined,
    });
  });

  it('parses a 6-beat script with a trailing follow-up question', () => {
    const text = [
      'Idea: Six quick shots for a longer reel',
      'Plan: for your regular audience; excitement; saves; 60s, vertical 9:16; listicle; direct-promise hook',
      'Action: on camera the whole time, hands only for the product shots',
      'Script:',
      '0-10s. Shot: Open on your face - say the hook line. Say: "Five things I wish I knew sooner". On screen: 5 THINGS',
      '10-20s. Shot: Cut to the product on the table - place it down. Say: "Number one". On screen: TIP 1',
      '20-30s. Shot: Hands only - show it in use. Say: "Number two". On screen: TIP 2',
      '30-40s. Shot: Cut back to your face - react. Say: "Number three". On screen: TIP 3',
      '40-50s. Shot: Close up on the result. Say: "Number four". On screen: TIP 4',
      '50-60s. Shot: End on your face - the CTA. Say: "Save this for later". On screen: SAVE THIS',
      'Caption: Which tip did you not know? Save this for later.',
      'Before you shoot: 1) Clear the table 2) Charge your phone 3) Have the product ready',
      'Why this works: Listicle structure, direct-promise hook template',
      'Which language should the voice-over be in — Hindi or English?',
    ].join('\n');
    const result = parseMeeraScript(text);
    expect(result).not.toBeUndefined();
    expect(result?.beats).toHaveLength(6);
    expect(result?.successLooksLike).toBeUndefined();
    expect(result?.followUp).toBe('Which language should the voice-over be in — Hindi or English?');
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
    const spaced = VALID_SCRIPT.replace('Idea:', 'idea :').replace('Plan:', 'PLAN:  ').replace(
      'Before you shoot:',
      'before   you shoot :',
    );
    const result = parseMeeraScript(spaced);
    expect(result).not.toBeUndefined();
    expect(result?.idea).toBe('3 saffron mistakes to avoid');
  });

  it('is tolerant of a beat line wrapped in straight or curly quotes', () => {
    const lines = VALID_SCRIPT.split('\n');
    const beatIndex = lines.findIndex((l) => l.startsWith('0-10s.'));
    const straightQuoted = [...lines];
    straightQuoted[beatIndex] = `"${lines[beatIndex]}"`;
    expect(parseMeeraScript(straightQuoted.join('\n'))).toEqual(parseMeeraScript(VALID_SCRIPT));

    const curlyQuoted = [...lines];
    curlyQuoted[beatIndex] = `“${lines[beatIndex]}”`;
    expect(parseMeeraScript(curlyQuoted.join('\n'))).toEqual(parseMeeraScript(VALID_SCRIPT));
  });

  it('is tolerant of curly double quotes around the Say text', () => {
    const curlySay = VALID_SCRIPT.replace(
      'Say: "Is your saffron even real?"',
      'Say: “Is your saffron even real?”',
    );
    const result = parseMeeraScript(curlySay);
    expect(result).not.toBeUndefined();
    expect(result?.beats[0].say).toBe('Is your saffron even real?');
  });

  it('accepts Hindi text in the values while the labels stay English', () => {
    const hindi = [
      'Idea: केसर खरीदने से पहले ये 3 गलतियाँ न करें',
      'Plan: नए ग्राहकों के लिए; जिज्ञासा; फॉलोअर्स; 15s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
      'Action: डिब्बा हाथ में लेकर कैमरे से बात करें',
      'Script:',
      '0-5s. Shot: Close-up on your face - डिब्बा दिखाएं. Say: "क्या यह असली केसर है?". On screen: असली या नकली',
      '5-10s. Shot: Overhead on your hands - पानी में टेस्ट करें. Say: "पानी में देखिए". On screen: पानी वाला टेस्ट',
      '10-15s. Shot: Close-up on the certificate - सर्टिफिकेट दिखाएं. Say: "यह असली प्रमाण पत्र है". On screen: प्रमाणित',
      'Caption: क्या आपने असली पहचान लिया? बायो में लिंक देखें।',
      'Before you shoot: 1) फोन चार्ज करें 2) काउंटर साफ करें 3) सर्टिफिकेट पास रखें',
      'Why this works: समस्या-समाधान संरचना, जिज्ञासा हुक',
    ].join('\n');
    const result = parseMeeraScript(hindi);
    expect(result).not.toBeUndefined();
    expect(result?.idea).toBe('केसर खरीदने से पहले ये 3 गलतियाँ न करें');
    expect(result?.beats[0].say).toBe('क्या यह असली केसर है?');
  });

  it('accepts a script with no Success looks like line', () => {
    const noSuccess = VALID_SCRIPT.split('\n')
      .filter((line) => !line.startsWith('Success looks like:'))
      .join('\n');
    const result = parseMeeraScript(noSuccess);
    expect(result).not.toBeUndefined();
    expect(result?.successLooksLike).toBeUndefined();
  });

  it('accepts beat lines carrying the Stress/Pause markers the persona added', () => {
    const withStressPause = VALID_SCRIPT.split('\n')
      .map((line) => {
        if (line.startsWith('0-10s.')) {
          return line.replace(
            'Say: "Is your saffron even real?". On screen: REAL vs FAKE',
            'Say: "Is your saffron even real?". Stress: even real. Pause: after "saffron", or none. On screen: REAL vs FAKE',
          );
        }
        return line;
      })
      .join('\n');
    const result = parseMeeraScript(withStressPause);
    expect(result).not.toBeUndefined();
    expect(result?.beats[0].stress).toBe('even real');
    expect(result?.beats[0].pause).toBe('after "saffron", or none');
    expect(result?.beats[0].onScreen).toBe('REAL vs FAKE');
    // The other two beats never had the markers — still optional, per beat.
    expect(result?.beats[1].stress).toBeUndefined();
    expect(result?.beats[1].pause).toBeUndefined();
  });
});

describe('parseMeeraScript — refusals', () => {
  it('returns undefined when Idea is missing (old Phase-C SCRIPT shape)', () => {
    const oldShape = [
      'SCRIPT',
      'Title: 3 saffron mistakes to avoid',
      'Length: 30s',
      'Hook: Stop buying saffron until you watch this',
      '0-10s: Show the box, ask "is your saffron real?"',
      '10-20s: Do the warm-water color test on camera',
      '20-30s: Show the certificate and say why it matters',
      'CTA: Link in bio for real Kashmiri saffron',
    ].join('\n');
    expect(parseMeeraScript(oldShape)).toBeUndefined();
  });

  it('returns undefined when a required key is missing (Action dropped)', () => {
    const missingAction = VALID_SCRIPT.split('\n').filter((line) => !line.startsWith('Action:')).join('\n');
    expect(parseMeeraScript(missingAction)).toBeUndefined();
  });

  it('returns undefined when Caption is missing', () => {
    const missingCaption = VALID_SCRIPT.split('\n').filter((line) => !line.startsWith('Caption:')).join('\n');
    expect(parseMeeraScript(missingCaption)).toBeUndefined();
  });

  it('returns undefined when the Script: line itself carries a value', () => {
    expect(parseMeeraScript(VALID_SCRIPT.replace('Script:', 'Script: here it is'))).toBeUndefined();
  });

  it('returns undefined when a beat line is malformed', () => {
    expect(
      parseMeeraScript(
        VALID_SCRIPT.replace(
          '0-10s. Shot: Close-up on your face - hold up the saffron box. Say: "Is your saffron even real?". On screen: REAL vs FAKE',
          '0 to 10s: Show the box',
        ),
      ),
    ).toBeUndefined();
  });

  it('returns undefined when a beat line has Stress but not Pause (the markers are one pair)', () => {
    expect(
      parseMeeraScript(
        VALID_SCRIPT.replace(
          'Say: "Is your saffron even real?". On screen: REAL vs FAKE',
          'Say: "Is your saffron even real?". Stress: even real. On screen: REAL vs FAKE',
        ),
      ),
    ).toBeUndefined();
  });

  it('returns undefined when the beats leave a gap', () => {
    expect(
      parseMeeraScript(
        VALID_SCRIPT.replace(
          '10-20s. Shot: Overhead on your hands - do the warm-water color test. Say: "Watch what happens in water". On screen: THE WATER TEST',
          '12-20s. Shot: Overhead on your hands - do the warm-water color test. Say: "Watch what happens in water". On screen: THE WATER TEST',
        ),
      ),
    ).toBeUndefined();
  });

  it('returns undefined when the beats do not start at 0s', () => {
    const lines = VALID_SCRIPT.split('\n');
    const beatIndex = lines.findIndex((l) => l.startsWith('0-10s.'));
    lines[beatIndex] = lines[beatIndex].replace('0-10s.', '2-10s.');
    expect(parseMeeraScript(lines.join('\n'))).toBeUndefined();
  });

  it('returns undefined with only 2 beats', () => {
    const twoBeats = [
      'Idea: Too short a script',
      'Plan: for a small audience; urgency; saves; 15s, vertical 9:16; listicle; direct-promise hook',
      'Action: hold the product up',
      'Script:',
      '0-8s. Shot: Close-up on your face - hold up the product. Say: "Watch this". On screen: WATCH',
      '8-15s. Shot: Close-up on the product - show it in use. Say: "That is it". On screen: DONE',
      'Caption: Save this for later.',
      'Before you shoot: 1) Charge your phone 2) Clear the table 3) Keep the product ready',
      'Why this works: Direct-promise hook template',
    ].join('\n');
    expect(parseMeeraScript(twoBeats)).toBeUndefined();
  });

  it('returns undefined when Before you shoot does not have exactly 3 numbered items', () => {
    expect(
      parseMeeraScript(
        VALID_SCRIPT.replace(
          'Before you shoot: 1) Charge your phone to 100% 2) Wipe the counter clean 3) Keep the certificate within reach',
          'Before you shoot: 1) Charge your phone to 100% 2) Wipe the counter clean',
        ),
      ),
    ).toBeUndefined();
  });

  it('returns undefined when Why this works is missing', () => {
    const missingWhy = VALID_SCRIPT.split('\n').filter((line) => !line.startsWith('Why this works:')).join('\n');
    expect(parseMeeraScript(missingWhy)).toBeUndefined();
  });

  it('returns undefined for plain conversational text', () => {
    expect(parseMeeraScript('Sure, here is what I think about your last post...')).toBeUndefined();
  });

  it('returns undefined for an empty string', () => {
    expect(parseMeeraScript('')).toBeUndefined();
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

describe('parseMeeraScript — a fixed start and end (persona rule, merge review 2026-09-24)', () => {
  // The persona says: start with the Idea line, nothing before it; the one closing question
  // goes after Why this works and nothing follows it. Any extra chat around the script must make
  // the reply fall back to the plain bubble rather than a card that hides part of the reply.
  const WITH_QUESTION = `${VALID_SCRIPT}\nWhich language do you want the voice-over in?`;

  it('accepts the one closing question after Why this works', () => {
    expect(parseMeeraScript(WITH_QUESTION)?.followUp).toBe('Which language do you want the voice-over in?');
  });

  it('returns undefined when anything comes before the Idea line', () => {
    expect(parseMeeraScript(`Here's your script:\n${VALID_SCRIPT}`)).toBeUndefined();
  });

  it('returns undefined when anything follows the closing question', () => {
    expect(parseMeeraScript(`${WITH_QUESTION}\nHope this helps!`)).toBeUndefined();
  });

  it('keeps a quote inside the spoken line (the Say text ends at the quote before ". On screen:")', () => {
    const nested = VALID_SCRIPT.replace(
      'Say: "Is your saffron even real?"',
      'Say: "My nani said "check the colour" first"',
    );
    expect(parseMeeraScript(nested)?.beats[0].say).toBe('My nani said "check the colour" first');
  });
});
