/**
 * Shopify connect needs the deployment's Shopify app credentials. No environment had them, so
 * POST /shopify/oauth/authorize answered 503 to everyone — while this panel advertised
 * "Shopify · OAuth — one click". GET /integrations/store/status now reports `shopifyAvailable`.
 *
 * Run: npx vitest run src/components/brand/settings/__tests__/store-integration-shopify-availability.test.tsx
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { IntegrationStatus } from '@/lib/api';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

let status: IntegrationStatus;

vi.mock('@/hooks/brand/useStoreIntegration', () => ({
  useStoreIntegration: () => ({ data: status, loading: false, error: null, refresh: vi.fn() }),
  default: () => ({ data: status, loading: false, error: null, refresh: vi.fn() }),
}));

import { StoreIntegrationSetup } from '../StoreIntegrationSetup';

describe('StoreIntegrationSetup — Shopify availability', () => {
  beforeEach(() => {
    status = { connected: false };
  });

  it('not available: Shopify is not a control, says why, and the connect form cannot be reached', async () => {
    status = { connected: false, shopifyAvailable: false };
    render(<StoreIntegrationSetup />);

    expect(screen.queryByRole('button', { name: /shopify/i })).toBeNull();
    expect(screen.getByText(/Not available yet/i)).toBeInTheDocument();
    expect(screen.queryByText('OAuth — one click')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Connect Shopify' })).toBeNull();
    // WooCommerce is unaffected.
    expect(screen.getByRole('button', { name: /woocommerce/i })).toBeInTheDocument();
  });

  it('available: the tile is a control and leads to the connect form', async () => {
    status = { connected: false, shopifyAvailable: true };
    const user = userEvent.setup();
    render(<StoreIntegrationSetup />);

    await user.click(screen.getByRole('button', { name: /shopify/i }));

    expect(screen.getByRole('button', { name: 'Connect Shopify' })).toBeInTheDocument();
  });

  it('an API that does not send the flag still offers Shopify (only an explicit false hides it)', () => {
    status = { connected: false };
    render(<StoreIntegrationSetup />);

    expect(screen.getByRole('button', { name: /shopify/i })).toBeInTheDocument();
  });
});
