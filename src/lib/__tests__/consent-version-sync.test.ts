/**
 * UF6-2 (PRIYA-LASTCALL-U1R-U6-0917.md, required before the Wave U commit) — three places must
 * agree on the DPDP consent version: `ConsentScreen.tsx`'s `CONSENT_TEXT_VERSION`, the frontend's
 * own mock (`src/lib/api.ts`'s `MOCK_CREATOR_AGENT_PREFS.consent_version`), and the backend's
 * `CreatorAgentPreferences.CURRENT_CONSENT_VERSION`. A U-7 Case B/v3 edit briefly put the
 * frontend a version ahead of the backend while the tree was uncommitted — this is the tripwire
 * for that not happening silently again.
 *
 * KAVYA-U6-RESTORE-REVERIFY-0917.md MEDIUM — the first version of `extractConsentVersion` only
 * skipped lines that themselves STARTED with `//`/`*`/`/*`, so a declaration-shaped line sitting
 * INSIDE a multi-line `/* ... *\/` block comment (indented, not itself starting with `*`) was
 * matched as if it were real code. Fixed by stripping block comments from the WHOLE source first
 * (so no per-line heuristic has to guess whether a line is "inside" one), then line comments per
 * line — never a `//` that sits inside a string literal — and by requiring EXACTLY ONE surviving
 * declaration, failing loudly instead of silently taking the first (or last) of several.
 *
 * Reads the Java file as TEXT ONLY — this repo's frontend build never compiles or runs Java, and
 * this test does not either.
 *
 * Run: npx vitest run src/lib/__tests__/consent-version-sync.test.ts
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { api, isApiLive } from '@/lib/api';
import { CONSENT_TEXT_VERSION } from '@/components/meera/ConsentScreen';

const JAVA_PATH = 'influora-api/src/main/java/com/influora/domain/entity/CreatorAgentPreferences.java';

const DECLARATION_PATTERN =
  /public\s+static\s+final\s+String\s+CURRENT_CONSENT_VERSION\s*=\s*"([^"]*)"\s*;/g;

/**
 * Removes every Java comment from `source`, so declaration-matching never has to reason about
 * "am I inside a comment right now" itself:
 *   1. Block comments (`/* ... *\/`), which can span multiple lines — removed FIRST and in one
 *      pass over the whole text, so a declaration-shaped line inside one (not itself starting
 *      with `*`) is gone before any per-line logic runs.
 *   2. Line comments (`//...`), removed per remaining line — but a `//` inside a string literal
 *      is not a comment start. Tracked per line via unescaped `"` toggling "inside a string",
 *      cheap and correct enough for this one field's plain `"vN"` values and any other string
 *      literal elsewhere in the file.
 */
export function stripJavaComments(source: string): string {
  const withoutBlockComments = source.replace(/\/\*[\s\S]*?\*\//g, '');
  return withoutBlockComments
    .split('\n')
    .map((line) => {
      let inString = false;
      for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (ch === '"' && line[i - 1] !== '\\') inString = !inString;
        if (!inString && ch === '/' && line[i + 1] === '/') return line.slice(0, i);
      }
      return line;
    })
    .join('\n');
}

/**
 * The one real `CURRENT_CONSENT_VERSION` value in `source`, comments stripped first. Throws
 * loudly on zero or on more than one surviving declaration, rather than taking the first (or
 * last) match — either shape means something is wrong with the file this parser cannot resolve
 * on its own, and a silently-wrong pick is worse than a failing test.
 */
export function extractConsentVersion(source: string): string {
  const stripped = stripJavaComments(source);
  const matches = [...stripped.matchAll(DECLARATION_PATTERN)];
  if (matches.length !== 1) {
    throw new Error(
      `Expected exactly one CURRENT_CONSENT_VERSION declaration, found ${matches.length}`,
    );
  }
  return matches[0][1];
}

function backendConsentVersion(): string {
  return extractConsentVersion(readFileSync(JAVA_PATH, 'utf-8'));
}

describe('Consent version stays in sync — frontend text, frontend mock, backend constant', () => {
  it('CONSENT_TEXT_VERSION, the api.ts mock consent_version, and the Java CURRENT_CONSENT_VERSION are all equal', async () => {
    const backend = backendConsentVersion();
    expect(backend.length, 'backend version string should not be empty').toBeGreaterThan(0);

    expect(
      CONSENT_TEXT_VERSION,
      `ConsentScreen.tsx's CONSENT_TEXT_VERSION ("${CONSENT_TEXT_VERSION}") does not match the backend's CURRENT_CONSENT_VERSION ("${backend}")`,
    ).toBe(backend);

    // Sanity: this is reading the MOCK path, not a live server.
    expect(isApiLive()).toBe(false);
    const prefs = await api.creatorAgentPrefs.getPreferences();
    expect(
      prefs.consent_version,
      `api.ts's MOCK_CREATOR_AGENT_PREFS.consent_version ("${prefs.consent_version}") does not match the backend's CURRENT_CONSENT_VERSION ("${backend}")`,
    ).toBe(backend);
  });
});

/**
 * KAVYA-U6-RESTORE-REVERIFY-0917.md MEDIUM — every look-alike is a STRING FIXTURE here, never a
 * mutation of the real Java file. `extractConsentVersion` is a pure function of its `source`
 * argument, so there is nothing to restore or sha256-check for any of these.
 */
describe('extractConsentVersion — comment-stripping parser', () => {
  it('(c) ignores a look-alike "// NAME = value" comment placed above the real declaration', () => {
    const source = `
      // CURRENT_CONSENT_VERSION = "v2"
      public static final String CURRENT_CONSENT_VERSION = "v9";
    `;
    expect(extractConsentVersion(source)).toBe('v9');
  });

  it('(d) ignores a declaration-shaped line inside a /* block comment */ that does not itself start with *', () => {
    const source = `
      /* old
           public static final String CURRENT_CONSENT_VERSION = "v9";
      */
      public static final String CURRENT_CONSENT_VERSION = "v2";
    `;
    expect(extractConsentVersion(source)).toBe('v2');
  });

  it('(e) ignores a trailing "// ... = \\"v9\\"" comment on a different, unrelated line', () => {
    const source = `
      public static final String CURRENT_CONSENT_VERSION = "v2";
      public static final String LEGACY_NOTE = "unused"; // used to be = "v9"
    `;
    expect(extractConsentVersion(source)).toBe('v2');
  });

  it('(f) fails loudly on two real declarations instead of silently taking the first', () => {
    const source = `
      public static final String CURRENT_CONSENT_VERSION = "v2";
      public static final String CURRENT_CONSENT_VERSION = "v3";
    `;
    expect(() => extractConsentVersion(source)).toThrow(/found 2/);
  });

  it('fails loudly on zero declarations rather than returning undefined/empty', () => {
    expect(() => extractConsentVersion('// nothing to see here')).toThrow(/found 0/);
  });

  it('a trailing line comment on the SAME line as the real declaration is stripped, not matched', () => {
    const source = `public static final String CURRENT_CONSENT_VERSION = "v2"; // was "v9" once`;
    expect(extractConsentVersion(source)).toBe('v2');
  });
});
