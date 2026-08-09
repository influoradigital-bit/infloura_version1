import { describe, it, expect } from 'vitest';
import { runwayTone } from './wallet-runway';

/**
 * F-0099 regression gate (false-critical-runway-fallback, money-path).
 *
 * The load-bearing invariant: an UNKNOWN runway (null — dormant/funded wallet, pre-load, or a
 * 404'd brand-new workspace) is 'healthy', NOT 'critical'. The original bug coerced that case to
 * `0` and compared `< 14`; since `null < 14` / `0 < 14` are both true in JS, a healthy wallet
 * flashed a false red CRITICAL alarm. These tests fail if anyone reintroduces that coercion.
 */
describe('runwayTone (F-0099)', () => {
  it('treats a null (unknown / dormant / no-spend) runway as healthy — never critical', () => {
    expect(runwayTone(null)).toBe('healthy');
  });

  it('still raises a real alarm on a genuinely computed low numeric runway', () => {
    expect(runwayTone(0)).toBe('critical'); // real: 0 days of runway WITH spend is a true alarm
    expect(runwayTone(5)).toBe('critical');
    expect(runwayTone(13)).toBe('critical');
  });

  it('classifies the warning band (14–29 days)', () => {
    expect(runwayTone(14)).toBe('warning');
    expect(runwayTone(29)).toBe('warning');
  });

  it('classifies a comfortable runway as healthy', () => {
    expect(runwayTone(30)).toBe('healthy');
    expect(runwayTone(47)).toBe('healthy');
  });

  it('the null case is distinct from 0 — the exact distinction the bug collapsed', () => {
    expect(runwayTone(null)).not.toBe(runwayTone(0));
  });
});
