/**
 * PHONE-0904 — shared Indian-mobile normalization/validation for the client, mirroring
 * `IndianPhoneUtils` (influora-api/src/main/java/com/influora/common/IndianPhoneUtils.java)
 * EXACTLY so the frontend can never be more restrictive than the server.
 *
 * Before this file existed, three surfaces (creator-onboarding.tsx, onboarding-steps.tsx,
 * creator-settings.tsx) each hand-rolled the same `.replace(/\s+/g, '')` +
 * `/^[6-9]\d{9}$/` check, none of which stripped a `+91` country code or leading trunk
 * `0` the way the backend does. A user pasting `+91 9876543210` from their contacts (a
 * very common way Indian numbers are stored) got blocked client-side even though the
 * server would have normalized and accepted it. Route every phone field through this
 * module instead of re-deriving the rule a fourth time.
 */

const VALID = /^[6-9]\d{9}$/;

/**
 * Strips everything but digits, then drops a leading `91` country code (12 digits -> 10)
 * or a single leading trunk `0` (11 digits -> 10) — the same three noise patterns
 * `IndianPhoneUtils.normalize` strips: spaces, `+91`, and a leading `0`.
 *
 * Does NOT itself validate that the result is a real 10-digit Indian mobile number —
 * callers must check {@link isValidPhone} on the return value before submitting.
 * Returns '' for null/blank/undefined input (no value entered).
 */
export function normalizePhone(raw: string | null | undefined): string {
  if (!raw) return '';
  let digits = raw.replace(/[^0-9]/g, '');
  if (digits.length === 12 && digits.startsWith('91')) {
    digits = digits.slice(2);
  } else if (digits.length === 11 && digits.startsWith('0')) {
    digits = digits.slice(1);
  }
  return digits;
}

/** Exactly 10 digits, first digit 6-9 — matches the backend's `IndianPhoneUtils.isValid`. */
export function isValidPhone(normalized: string): boolean {
  return VALID.test(normalized);
}

/**
 * Filter for an input's `onChange`: keeps digits, spaces, and `+` (so a pasted
 * `+91 98765 43210` is never mangled mid-keystroke), and strips everything else. Do NOT
 * use this output directly for validation or submission — normalize it first with
 * {@link normalizePhone}.
 */
export function filterPhoneInput(raw: string): string {
  return raw.replace(/[^0-9+\s]/g, '');
}
