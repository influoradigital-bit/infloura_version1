/**
 * src/lib/phone.ts — PHONE-0904 shared Indian-mobile normalize/validate helper.
 *
 * Mirrors influora-api's IndianPhoneUtils (influora-api/src/main/java/com/influora/common/
 * IndianPhoneUtils.java) test-for-test: every accepted/rejected form here must match what
 * the server's normalize()/isValid() pair does, since this file exists specifically to
 * close the parity gap Kavya's phone-0904 review found (client rejecting numbers the
 * server would accept).
 *
 * Run: npx vitest run src/lib/phone.test.ts
 */

import { describe, it, expect } from 'vitest';
import { normalizePhone, isValidPhone, filterPhoneInput } from './phone';

describe('normalizePhone', () => {
  it.each([
    ['+91 98765 43210', '9876543210'],
    ['+919876543210', '9876543210'],
    ['09876543210', '9876543210'],
    ['98765 43210', '9876543210'],
    ['9876543210', '9876543210'],
    ['  9876543210  ', '9876543210'],
  ])('normalizes %s to %s (matches IndianPhoneUtils.normalize)', (raw, expected) => {
    expect(normalizePhone(raw)).toBe(expected);
  });

  it('returns "" for null/undefined/blank input', () => {
    expect(normalizePhone(null)).toBe('');
    expect(normalizePhone(undefined)).toBe('');
    expect(normalizePhone('')).toBe('');
    expect(normalizePhone('   ')).toBe('');
  });

  it('does not drop a leading 91 unless the digit count is exactly 12 (avoids mangling a real number that happens to start with 91)', () => {
    // 10 digits starting with 91 is not a 12-digit input, so it must pass through untouched.
    expect(normalizePhone('9199999999')).toBe('9199999999');
  });

  it('does not drop a leading 0 unless the digit count is exactly 11', () => {
    // A bare 10-digit number never has the leading-0 rule applied (it isn't 11 digits).
    expect(normalizePhone('9876543210')).toBe('9876543210');
  });
});

describe('isValidPhone', () => {
  it.each(['9876543210', '6000000000', '9999999999'])(
    'accepts %s (10 digits, starts 6-9)',
    (normalized) => {
      expect(isValidPhone(normalized)).toBe(true);
    },
  );

  it.each([
    ['12345', 'too short'],
    ['98765432100', 'too long (11 digits)'],
    ['1876543210', 'leading digit 1'],
    ['5876543210', 'leading digit 5'],
    ['', 'empty'],
  ])('rejects %s (%s)', (normalized) => {
    expect(isValidPhone(normalized)).toBe(false);
  });
});

describe('normalizePhone + isValidPhone together (full accept/reject matrix)', () => {
  it.each([
    '+91 98765 43210',
    '+919876543210',
    '09876543210',
    '98765 43210',
    '9876543210',
  ])('accepts pasted/typed form %s', (raw) => {
    const normalized = normalizePhone(raw);
    expect(normalized).toBe('9876543210');
    expect(isValidPhone(normalized)).toBe(true);
  });

  it.each([
    '123456789', // 9 digits, too short
    '12345678901', // 11 digits, doesn't start with 0 so leading-0 rule doesn't apply -> still too long
    '+91 1876543210', // normalizes to 10 digits but leading digit 1
    '5876543210', // leading digit 5
  ])('rejects genuinely bad input %s', (raw) => {
    const normalized = normalizePhone(raw);
    expect(isValidPhone(normalized)).toBe(false);
  });
});

/**
 * PHONE-0904 re-run item 2 (Priya) — parity mirror of
 * `influora-api/src/test/java/com/influora/common/IndianPhoneUtilsTest.java`.
 *
 * The Java suite pins 29 cases; before this block, 5 of the 10 signoffProbeInputs() rows
 * were not asserted anywhere in this file as a single normalizePhone()+isValidPhone() call
 * (91-9876543210, the trailing-space leading-0 form, Devanagari digits, the embedded-letters
 * form, and a 12-digit number not prefixed with 91), so a future edit to normalizePhone()
 * could silently diverge from IndianPhoneUtils.normalize() without a red test on either side.
 * This block pins the full 10-row table plus the same boundary branches Java pins, using the
 * exact same raw inputs, so the two files can be diffed row-for-row.
 *
 * One deliberate difference, not a bug: Java's normalize() returns `null` for blank input;
 * normalizePhone() here returns `''` (documented in phone.ts, kept for form state). The table
 * below asserts the TS contract ('') for every row, never null.
 */
describe('PHONE-0904 Q3 parity — mirrors IndianPhoneUtilsTest.signoffProbeInputs()', () => {
  it.each([
    ['plain 10-digit, valid leading digit', '9876543210', '9876543210', true],
    ['+91 with spaces', '+91 98765 43210', '9876543210', true],
    ['+91 no spaces', '+919876543210', '9876543210', true],
    ['leading trunk 0', '09876543210', '9876543210', true],
    ['91 with hyphen separator', '91-9876543210', '9876543210', true],
    ['leading trunk 0 with trailing space', '098765 43210 ', '9876543210', true],
    [
      'Devanagari digits collapse to no digits at all (ASCII-only char class)',
      '+९८७६५४३२१०',
      '',
      false,
    ],
    ['embedded letters/extension pull in extra digits', '9876543210x123', '9876543210123', false],
    ['invalid leading digit 5', '5876543210', '5876543210', false],
    ['12 digits NOT prefixed with 91 stays 12 digits', '987654321012', '987654321012', false],
  ])('%s: %j -> normalized %j, valid=%s', (_caseName, raw, expectedNormalized, expectedValid) => {
    const normalized = normalizePhone(raw);
    expect(normalized).toBe(expectedNormalized);
    expect(isValidPhone(normalized)).toBe(expectedValid);
  });

  it('normalizes a lone "+" plus Devanagari digits to "" (matches Java\'s dedicated test), not null', () => {
    // Mirrors IndianPhoneUtilsTest.normalizeDevanagariDigitsOnly(). Java asserts non-null "";
    // the TS contract for blank/empty is also '' (see the deliberate-difference note above),
    // so both sides agree on this row specifically — only true null/undefined/'' input differs.
    const normalized = normalizePhone('+९८७६५४३२१०');
    expect(normalized).toBe('');
    expect(isValidPhone(normalized)).toBe(false);
  });

  // Boundary branches — same exact inputs as IndianPhoneUtilsTest's dedicated @Test methods.
  it('9 digits is left as-is (too short to be a strip candidate) and rejected', () => {
    const normalized = normalizePhone('987654321');
    expect(normalized).toBe('987654321');
    expect(isValidPhone(normalized)).toBe(false);
  });

  it('11 digits NOT starting with 0 is left as-is (trunk-0 strip does not fire) and rejected', () => {
    const normalized = normalizePhone('19876543210');
    expect(normalized).toBe('19876543210');
    expect(isValidPhone(normalized)).toBe(false);
  });

  it('12 digits starting with 91 strips the prefix to a valid 10-digit number', () => {
    const normalized = normalizePhone('919876543210');
    expect(normalized).toBe('9876543210');
    expect(isValidPhone(normalized)).toBe(true);
  });

  it('13+ digits is left as-is (no strip rule matches) and rejected', () => {
    const normalized = normalizePhone('1234567890123');
    expect(normalized).toBe('1234567890123');
    expect(isValidPhone(normalized)).toBe(false);
  });

  // Every leading digit 0-9 on an otherwise-valid 10-digit number — mirrors
  // IndianPhoneUtilsTest's two @ValueSource(chars) parameterized tests.
  it.each(['6', '7', '8', '9'])('leading digit %s: 10-digit number is accepted', (leadingDigit) => {
    const raw = `${leadingDigit}876543210`;
    const normalized = normalizePhone(raw);
    expect(normalized).toBe(raw);
    expect(isValidPhone(normalized)).toBe(true);
  });

  it.each(['0', '1', '2', '3', '4', '5'])(
    'leading digit %s: 10-digit number is rejected',
    (leadingDigit) => {
      const raw = `${leadingDigit}876543210`;
      const normalized = normalizePhone(raw);
      expect(normalized).toBe(raw);
      expect(isValidPhone(normalized)).toBe(false);
    },
  );

  // Null/undefined/empty/whitespace-only — mirrors IndianPhoneUtilsTest's normalizeNull/
  // normalizeEmpty/normalizeWhitespaceOnly, asserting the documented TS contract ('' not
  // null; see the deliberate-difference note above) rather than re-fixing it to match Java.
  it.each([null, undefined, '', '   '])('blank input %j normalizes to "" (TS contract, not null)', (raw) => {
    const normalized = normalizePhone(raw);
    expect(normalized).toBe('');
    expect(isValidPhone(normalized)).toBe(false);
  });
});

describe('filterPhoneInput', () => {
  it('keeps digits, spaces, and +', () => {
    expect(filterPhoneInput('+91 98765 43210')).toBe('+91 98765 43210');
  });

  it('strips letters and other punctuation but keeps +/spaces/digits', () => {
    expect(filterPhoneInput('+91-98765-43210abc')).toBe('+919876543210');
  });
});
