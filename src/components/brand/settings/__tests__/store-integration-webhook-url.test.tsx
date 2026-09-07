/**
 * Guard on the WooCommerce Delivery URL that `StoreIntegrationSetup` tells brands to paste into
 * their own store admin.
 *
 * WHY THIS EXISTS: the string shipped as the literal `https://api.influora.com/webhooks/woocommerce`
 * and was wrong twice over — wrong TLD (the API is api.influora.in) and missing the `/api/v1`
 * context path that `WooCommerceWebhookController` maps beneath. It could not ever have worked.
 *
 * Nothing caught it, and nothing would have. `tsc` cannot help: a wrong URL is a perfectly valid
 * string. There was no test file for this component at all. And the runtime failure is silent on
 * both sides — WooCommerce accepts any Delivery URL, so the brand sees success, deliveries then
 * fail out of sight in their store's webhook log, and Influora simply records no orders while the
 * brand believes tracking is live.
 *
 * So the assertions below are deliberately about the RENDERED text, not about the constant: the
 * bug was in what a brand reads off the screen and types into WooCommerce.
 *
 * Run: npx vitest run src/components/brand/settings/__tests__/store-integration-webhook-url.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

// Not connected, not loading — the state in which the platform picker and the WooCommerce
// instructions are reachable. No network is involved.
vi.mock('@/hooks/brand/useStoreIntegration', () => ({
  useStoreIntegration: () => ({
    data: { connected: false },
    loading: false,
    error: null,
    refresh: vi.fn(),
  }),
  default: () => ({ data: { connected: false }, loading: false, error: null, refresh: vi.fn() }),
}));

/**
 * Renders the panel and walks to the WooCommerce instructions the way a brand does.
 *
 * Imports the component AFTER any `vi.stubEnv`, because the URL is computed once at module load —
 * a static import here would bake in the env of whichever test ran first.
 */
async function renderWooInstructions(): Promise<string> {
  const { StoreIntegrationSetup } = await import('../StoreIntegrationSetup');
  render(<StoreIntegrationSetup />);

  await userEvent.click(screen.getByRole('button', { name: /woocommerce/i }));

  const deliveryUrl = screen.getByText(/\/webhooks\/woocommerce$/);
  return deliveryUrl.textContent ?? '';
}

beforeEach(() => {
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('WooCommerce Delivery URL shown to brands', () => {
  it('is DERIVED from VITE_API_BASE_URL, not hardcoded', async () => {
    // The load-bearing assertion. A hardcoded literal ignores this entirely, so this test fails
    // against the old code no matter which host the literal named — it is the hardcoding itself
    // that is the defect, because the deployment's address then has two sources of truth.
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test/api/v1');

    const url = await renderWooInstructions();

    expect(url).toBe('https://api.example.test/api/v1/webhooks/woocommerce');
  });

  it('tracks the API base when the deployment moves', async () => {
    // Same component, different deployment — proves the first result was not a coincidence of one
    // particular string.
    vi.stubEnv('VITE_API_BASE_URL', 'https://staging-api.influora.in/api/v1');

    const url = await renderWooInstructions();

    expect(url).toBe('https://staging-api.influora.in/api/v1/webhooks/woocommerce');
  });

  it('never renders the dead .com URL again, and never drops the context path', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.influora.in/api/v1');

    const url = await renderWooInstructions();

    // The exact string that shipped broken.
    expect(url).not.toBe('https://api.influora.com/webhooks/woocommerce');
    // Bug half 1 — wrong TLD. `.com` is not ours.
    expect(url).not.toContain('influora.com');
    // Bug half 2 — the missing `server.servlet.context-path`. Without `/api/v1` the URL 404s even
    // on the right host, which is the half a domain-only fix would have missed.
    expect(url).toContain('/api/v1/');
    expect(url).toBe('https://api.influora.in/api/v1/webhooks/woocommerce');
  });
});
