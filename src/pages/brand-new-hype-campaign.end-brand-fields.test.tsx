/**
 * T-MEERA-CREATOR-PHASE-A (A2, gate fix round 1 item 2/4) regression pin.
 *
 * Priya's audit (SHARED_CONTEXT.md Q2/Q9): `CampaignService.create` hard-400s any new campaign
 * without `endBrandName`/`endBrandCategory` (END_BRAND_NAME_REQUIRED), but this page's launch
 * payload sent neither — every HYPE campaign create, including resuming a Meera draft, was
 * dead. This pins: (1) launching without the two fields is blocked client-side with a specific
 * error, not silently sent and 400'd server-side; (2) filling them in sends both fields verbatim
 * on `api.campaigns.create`.
 *
 * Run: npx vitest run src/pages/brand-new-hype-campaign.end-brand-fields.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BrandNewHypeCampaignPage from './brand-new-hype-campaign';
import * as workspaceVerificationHook from '@/hooks/brand/useWorkspaceVerification';

vi.mock('@/hooks/brand/useWorkspaceVerification');

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

const { createMock } = vi.hoisted(() => ({ createMock: vi.fn().mockResolvedValue({ id: 'camp_1' }) }));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { ...actual.api, campaigns: { ...actual.api.campaigns, get: vi.fn(), create: createMock } },
  };
});

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function renderHypePage() {
  vi.mocked(workspaceVerificationHook.useWorkspaceVerification).mockReturnValue({
    status: null,
    isLoading: false,
    isVerified: true,
    roleResolved: true,
    roleError: false,
    canVerify: true,
    retryRole: vi.fn(),
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/campaigns/new/hype']}>
        <BrandNewHypeCampaignPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function fillRequiredFieldsExceptEndBrand() {
  fireEvent.change(screen.getByLabelText(/campaign title/i), { target: { value: 'Glow Drop Challenge' } });
  fireEvent.change(screen.getByLabelText(/source reel url/i), {
    target: { value: 'https://instagram.com/reel/abc123' },
  });
  fireEvent.change(screen.getByLabelText(/campaign hashtag/i), { target: { value: 'GlowDrop' } });
  fireEvent.change(screen.getByLabelText(/per-reel rate/i), { target: { value: '3500' } });
  fireEvent.change(screen.getByLabelText(/slot cap/i), { target: { value: '10' } });
}

describe('BrandNewHypeCampaignPage — end-brand fields (A2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders End Brand Name and End Brand Category inputs', () => {
    renderHypePage();
    expect(screen.getByLabelText(/end brand name/i)).toBeInTheDocument();
    expect(screen.getByText(/end brand category/i)).toBeInTheDocument();
  });

  it('blocks launch with a client-side error when End Brand Name is empty, and never calls create', async () => {
    renderHypePage();
    fillRequiredFieldsExceptEndBrand();

    fireEvent.click(screen.getByRole('button', { name: /launch hype campaign/i }));

    await waitFor(() => {
      expect(screen.getByText(/end brand name is required/i)).toBeInTheDocument();
    });
    expect(createMock).not.toHaveBeenCalled();
  });

  it('sends endBrandName/endBrandCategory on create once both are filled in', async () => {
    const user = userEvent.setup();
    renderHypePage();
    fillRequiredFieldsExceptEndBrand();
    fireEvent.change(screen.getByLabelText(/end brand name/i), { target: { value: 'Kavala Skincare' } });

    // Radix Select — open the trigger (a combobox button, not the placeholder text span
    // itself, which is `pointer-events: none`), pick an option from the listbox. Needs
    // userEvent (full pointerdown/up + click sequence), not a bare fireEvent.click, for
    // Radix to open in jsdom.
    await user.click(screen.getByRole('combobox', { name: /end brand category/i }));
    await user.click(await screen.findByRole('option', { name: 'Beauty' }));

    fireEvent.click(screen.getByRole('button', { name: /launch hype campaign/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1));
    const payload = createMock.mock.calls[0][0];
    expect(payload.endBrandName).toBe('Kavala Skincare');
    expect(payload.endBrandCategory).toBe('Beauty');
  });
});
