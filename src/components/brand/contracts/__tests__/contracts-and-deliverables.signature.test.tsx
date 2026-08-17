/**
 * F-0253 — decorative-legal-control.
 * ----------------------------------------------------------------------------
 * The Sign Contract dialog used to also offer a "Draw Your Signature" <canvas> pad.
 * Its strokes were painted to a 2d context nothing ever read (no toDataURL, no export) —
 * only the typed `signatureText` reached `api.contracts.sign('brand', id, { name, agreedAt })`
 * (src/lib/api.ts:2410), which accepts only `{ name: string; agreedAt: string }` and has no
 * image/bitmap field at all. Meanwhile the dialog told the brand "you agree to be legally
 * bound ... under the IT Act 2000" right next to a signature mark that was silently discarded.
 *
 * The fix removes the decorative canvas (the backend genuinely has no field for it) and makes
 * the legal copy describe only what is actually submitted: the typed name.
 *
 * Two guarantees:
 *   A. No <canvas> renders anywhere in the Sign Contract dialog.
 *   B. Behavioral — signing a contract calls api.contracts.sign with exactly the typed name and
 *      an agreedAt timestamp; nothing else. What the brand types is what gets submitted.
 *
 * Run: npx vitest run src/components/brand/contracts/__tests__/contracts-and-deliverables.signature.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const contractsList = vi.fn();
const contractsGet = vi.fn();
const contractsSign = vi.fn();
const dealsList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      contracts: {
        ...actual.api.contracts,
        list: (...a: unknown[]) => contractsList(...a),
        get: (...a: unknown[]) => contractsGet(...a),
        sign: (...a: unknown[]) => contractsSign(...a),
      },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
    },
  };
});

// Imported after the mock so the component picks up the mocked `@/lib/api`.
const { ContractsAndDeliverables } = await import('../contracts-and-deliverables');

function liveRecord(over: Record<string, unknown> = {}) {
  return {
    id: 'ctr-1',
    collaborationId: 'collab-1',
    status: 'PENDING_SIGNATURES',
    totalAmount: 50000,
    currency: 'INR',
    brandSignedAt: null,
    creatorSignedAt: null,
    milestones: [],
    expirationDate: '2026-12-31',
    createdAt: '2026-08-01',
    ...over,
  };
}

async function openSignDialog(user: ReturnType<typeof userEvent.setup>) {
  const contractTab = await screen.findByRole('tab', { name: 'Contract' });
  await user.click(contractTab);
  const openButton = await screen.findByRole('button', { name: /^Sign Contract$/i });
  await user.click(openButton);
  return screen.findByRole('dialog');
}

describe('F-0253 — signature dialog submits exactly what it promises', () => {
  it('renders no <canvas> signature pad anywhere in the Sign Contract dialog', async () => {
    contractsList.mockResolvedValue([liveRecord()]);
    contractsGet.mockResolvedValue(liveRecord());
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    const dialog = await openSignDialog(user);
    expect(dialog.querySelector('canvas')).toBeNull();
  });

  it('submits only the typed name and an agreedAt timestamp — no image/bitmap field', async () => {
    contractsList.mockResolvedValue([liveRecord()]);
    contractsGet.mockResolvedValue(liveRecord());
    dealsList.mockResolvedValue([]);
    contractsSign.mockResolvedValue(liveRecord({ brandSignedAt: '2026-08-17T00:00:00Z' }));
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    const dialog = await openSignDialog(user);
    const nameInput = within(dialog).getByPlaceholderText('Your legal name');
    await user.type(nameInput, 'Priya Sharma');

    const submitButton = within(dialog).getByRole('button', { name: /^Sign Contract$/i });
    await user.click(submitButton);

    expect(contractsSign).toHaveBeenCalledTimes(1);
    const [role, id, signature] = contractsSign.mock.calls[0];
    expect(role).toBe('brand');
    expect(id).toBe('ctr-1');
    // Exactly { name, agreedAt } — matches api.contracts.sign's real accepted shape
    // (src/lib/api.ts:2410). Any extra key here (e.g. a signature image) would be silently
    // dropped by the real backend, which is exactly the dishonesty this record fixes.
    expect(Object.keys(signature as object).sort()).toEqual(['agreedAt', 'name']);
    expect((signature as { name: string }).name).toBe('Priya Sharma');
    expect(typeof (signature as { agreedAt: string }).agreedAt).toBe('string');
    expect(Number.isNaN(Date.parse((signature as { agreedAt: string }).agreedAt))).toBe(false);
  });

  it('the legal notice describes the typed name, not a drawn mark', async () => {
    contractsList.mockResolvedValue([liveRecord()]);
    contractsGet.mockResolvedValue(liveRecord());
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    const dialog = await openSignDialog(user);
    expect(within(dialog).getByText(/legally bound/i)).toBeInTheDocument();
    expect(within(dialog).queryByText(/draw (your|a) signature/i)).toBeNull();
  });
});
