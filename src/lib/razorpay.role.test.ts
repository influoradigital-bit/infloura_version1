/**
 * The Razorpay key comes from an AUTHENTICATED route (`GET /config/razorpay`,
 * PublicConfigController:83), and the API client defaults every call to the BRAND token. A
 * creator browser never holds one, so a creator-side payment that does not say whose session is
 * paying dies at the key fetch with a 401 and Checkout never opens.
 *
 * These pin the role all the way through the launcher, in both directions: the creator surface
 * must reach the API as a creator, and the brand surfaces must keep reaching it as a brand.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { razorpayConfigMock } = vi.hoisted(() => ({
  razorpayConfigMock: vi.fn(async () => ({ keyId: 'rzp_test_key' })),
}));

vi.mock('./api', () => ({
  api: { config: { razorpay: razorpayConfigMock } },
}));

describe('Razorpay key fetch carries the paying session', () => {
  beforeEach(() => {
    vi.resetModules();
    razorpayConfigMock.mockClear();
  });

  it("asks as the creator when the creator is paying", async () => {
    const { getRazorpayKeyId } = await import('./razorpay');

    await getRazorpayKeyId('creator');

    expect(razorpayConfigMock).toHaveBeenCalledWith('creator');
  });

  it('still asks as the brand when no role is given, so brand wallet top-ups are unchanged', async () => {
    const { getRazorpayKeyId } = await import('./razorpay');

    await getRazorpayKeyId();

    expect(razorpayConfigMock).toHaveBeenCalledWith('brand');
  });
});
