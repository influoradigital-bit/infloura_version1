/**
 * Beta ruling (Swapnil, 2026-09-21): creators are paid by bank transfer only. No creator-facing
 * page may offer UPI as a payout option or promise a payout "to your UPI". Brands paying IN through
 * Razorpay's own checkout (which may offer UPI) is out of scope, and the brand wallet is the
 * positive control below: it must still mention UPI, or this scan is not reading what it claims.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const read = (p: string) => readFileSync(resolve(__dirname, '..', '..', p), 'utf8');

// Code comments may explain the history; only strings a creator can see count.
const withoutComments = (src: string) =>
  src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`])\/\/.*$/gm, '$1').replace(/\{\/\*[\s\S]*?\*\/\}/g, '');

const CREATOR_FACING = [
  'src/pages/creator-wallet.tsx',
  'src/pages/creator-settings.tsx',
  'src/pages/pricing.tsx',
  'src/pages/landing.tsx',
  'src/pages/how-it-works-creators.tsx',
];

const PAYOUT_UPI = [
  /SelectItem value="UPI"/,
  /UPI ID/,
  /UPI or (direct )?bank/i,
  /your UPI/i,
  /bank or UPI/i,
  /UPI, Bank/i,
];

describe('creator payout: no UPI option shown (beta)', () => {
  it.each(CREATOR_FACING)('%s offers no UPI payout', (file) => {
    const src = withoutComments(read(file));
    for (const pattern of PAYOUT_UPI) expect(src, `${file} matches ${pattern}`).not.toMatch(pattern);
  });

  it('positive control: the scan does find UPI where it still legitimately exists', () => {
    expect(read('src/pages/brand-wallet.tsx')).toMatch(/UPI/);
    expect('Add a UPI ID or bank account').toMatch(PAYOUT_UPI[1]);
  });
});
