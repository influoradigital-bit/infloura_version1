/**
 * U-4 (2026-09-17) — nothing previously scanned the Remotion demo scripts themselves for the
 * "Meera drafts/sends for you" promises Wave U cut. `meera-for-creators.claims.test.tsx` only
 * checks the RENDERED marketing page; the phone demo's own script files
 * (`script.ts`/`script.en.ts`/`script.mr.ts`) had no equivalent guard, which is exactly how the
 * money scene's "I will send the 72-hour reach to the brand" survived the first U-4 pass and was
 * only caught by Nisha's full read-through (NISHA-U4-RECHECK-0917.md §5).
 *
 * Reads each script file's SOURCE TEXT directly — these are plain data files, not components, so
 * there is no render to check against, and scanning the source is the direct equivalent of the
 * page's rendered-DOM scan.
 *
 * Run: npx vitest run src/remotion/script.claims.test.ts
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (p: string) => readFileSync(p, 'utf-8');

const FILES: Record<'hi' | 'en' | 'mr', string> = {
  hi: 'src/remotion/script.ts',
  en: 'src/remotion/script.en.ts',
  mr: 'src/remotion/script.mr.ts',
};

/**
 * Each removed "Meera drafts/sends on her own" promise, as a pattern loose enough to catch a
 * light rewording. Every pattern is checked against EVERY file — a promise cut from one locale
 * that quietly reappears in another is exactly the failure mode a per-file list would miss.
 */
const REMOVED_PROMISES: Array<[string, RegExp]> = [
  ['"Drafted with Meera" footer', /drafted with meera/i],
  ['English: "I am drafting the reply"', /drafting the reply/i],
  ['English: "Sent. Once the brand opens the link..."', /sent\. once the brand opens the link/i],
  ['English: "...I will send the 72-hour reach to the brand" (NISHA-U4-RECHECK-0917.md §5)', /send the 72-hour reach/i],
  ['Hinglish: "Reply draft kar rahi hoon"', /reply draft kar rahi hoon/i],
  ['Hinglish: "Bhej diya" (approve/sent confirmation)', /bhej diya/i],
  ['Hinglish: "...brand ko bhej dungi" (72-hour reach, NISHA-U4-RECHECK-0917.md §5)', /brand ko bhej dungi/i],
  ['Marathi: "मी reply draft करते आहे"', /reply draft करते आहे/],
  ['Marathi: "पाठवलं" (sent confirmation)', /पाठवलं/],
  ['Marathi: "...reach मी brand ला पाठवेन" (NISHA-U4-RECHECK-0917.md §5)', /reach\s+मी\s+brand\s+ला\s+पाठवेन/],
];

describe('Remotion demo scripts — no Meera-drafts-or-sends promises (U-4)', () => {
  it('contains none of the removed send/draft claims, in any of the three script files', () => {
    for (const [lang, path] of Object.entries(FILES)) {
      const src = read(path);
      for (const [name, pattern] of REMOVED_PROMISES) {
        expect(pattern.test(src), `${path} (${lang}) still contains the "${name}" claim`).toBe(false);
      }
    }
  });

  it('sanity: the files actually have content to scan (guards the guard)', () => {
    for (const path of Object.values(FILES)) {
      expect(read(path).length).toBeGreaterThan(500);
    }
  });
});
