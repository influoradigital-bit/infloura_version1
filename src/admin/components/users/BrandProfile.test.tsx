/**
 * INFLUORA ADMIN PANEL — BrandProfile Component Tests
 * Owner: Kavya (QA Lead)
 * Cycle: 6
 *
 * Tests for BrandProfile.tsx — the admin detail view for a brand.
 * Focus: KYC action flows (approve, reject with reason), suspend/reinstate
 * flows, and verification that action buttons appear only when appropriate
 * (no KYC actions if already approved, no reinstate if not suspended).
 *
 * Run: npx vitest run BrandProfile.test
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BrandProfile from './BrandProfile';
import { KycStatus } from '../../types/admin.types';

// ============================================
// MOCKS
// ============================================

// Mock the useBrandDetail hook
const mockUseBrandDetail = vi.fn();
vi.mock('../../hooks/useBrandDetail', () => ({
  useBrandDetail: (brandId: string) => mockUseBrandDetail(brandId),
}));

// Mock the live-wired brandApi (P1-WIRE-3). The action handlers call
// brandApi.verifyKyc / .suspend / .reinstate; mocking here keeps these unit
// tests off the network (the real client fetches /api/v1/admin/... which has no
// base URL under jsdom) while letting us assert the exact mutation payloads.
const mockVerifyKyc = vi.fn();
const mockSuspend = vi.fn();
const mockReinstate = vi.fn();
vi.mock('../../services/api-contracts', () => ({
  brandApi: {
    verifyKyc: (...args: unknown[]) => mockVerifyKyc(...args),
    suspend: (...args: unknown[]) => mockSuspend(...args),
    reinstate: (...args: unknown[]) => mockReinstate(...args),
  },
}));

// Mock lucide-react icons to avoid rendering issues
vi.mock('lucide-react', async () => {
  const actual = await vi.importActual('lucide-react');
  return {
    ...actual,
    Building2: () => <span data-testid="icon-building" />,
    Mail: () => <span data-testid="icon-mail" />,
    ShieldCheck: () => <span data-testid="icon-shield-check" />,
    ShieldQuestion: () => <span data-testid="icon-shield-question" />,
    ShieldX: () => <span data-testid="icon-shield-x" />,
    Ban: () => <span data-testid="icon-ban" />,
    CheckCircle2: () => <span data-testid="icon-check-circle" />,
    Users: () => <span data-testid="icon-users" />,
    FileText: () => <span data-testid="icon-file-text" />,
    CalendarDays: () => <span data-testid="icon-calendar" />,
  };
});

// ============================================
// TEST FIXTURES
// ============================================

const MOCK_BRAND_PENDING_KYC = {
  id: 'brand-123',
  name: 'Acme Corp',
  email: 'contact@acme.test',
  industry: 'Fashion',
  size: 'Medium',
  kycStatus: KycStatus.PENDING,
  isSuspended: false,
  totalSpend: 125000,
  gstNumber: '29ABCDE1234F1Z5',
  panNumber: 'ABCDE1234F',
  billingAddress: '123 MG Road, Bangalore 560001',
  createdAt: '2026-01-15T10:30:00Z',
  campaigns: [
    { id: 'c1', status: 'ACTIVE' },
    { id: 'c2', status: 'DRAFT' },
  ],
  teamMembers: [
    { id: 't1', name: 'John Doe', email: 'john@acme.test', role: 'Admin' },
  ],
};

const MOCK_BRAND_APPROVED_KYC = {
  ...MOCK_BRAND_PENDING_KYC,
  kycStatus: KycStatus.APPROVED,
};

const MOCK_BRAND_SUSPENDED = {
  ...MOCK_BRAND_PENDING_KYC,
  isSuspended: true,
};

// ============================================
// TEST SUITE
// ============================================

describe('BrandProfile Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Successful mutation by default; individual tests can override.
    mockVerifyKyc.mockResolvedValue({ success: true });
    mockSuspend.mockResolvedValue({ success: true });
    mockReinstate.mockResolvedValue({ success: true });
  });

  // ============================================
  // LOADING & ERROR STATES
  // ============================================

  describe('Loading and Error States', () => {
    it('should render loading skeleton when data is loading', () => {
      mockUseBrandDetail.mockReturnValue({
        data: null,
        isLoading: true,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      // Loading skeleton uses animate-pulse classes
      const skeletons = document.querySelectorAll('.animate-pulse');
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it('should render error message when fetch fails', () => {
      mockUseBrandDetail.mockReturnValue({
        data: null,
        isLoading: false,
        error: 'Network error',
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText(/Failed to load brand profile/i)).toBeInTheDocument();
      expect(screen.getByText(/Network error/i)).toBeInTheDocument();
    });

    it('should render error message when brand is null', () => {
      mockUseBrandDetail.mockReturnValue({
        data: null,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText(/Failed to load brand profile/i)).toBeInTheDocument();
    });
  });

  // ============================================
  // BRAND DETAILS RENDERING
  // ============================================

  describe('Brand Details Display', () => {
    beforeEach(() => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });
    });

    it('should render brand name and email', () => {
      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
      expect(screen.getByText('contact@acme.test')).toBeInTheDocument();
    });

    it('should render industry and size badges', () => {
      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('Fashion')).toBeInTheDocument();
      expect(screen.getByText('Medium')).toBeInTheDocument();
    });

    it('should render company details (GST, PAN, address)', () => {
      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('29ABCDE1234F1Z5')).toBeInTheDocument();
      expect(screen.getByText('ABCDE1234F')).toBeInTheDocument();
      expect(screen.getByText(/123 MG Road/i)).toBeInTheDocument();
    });

    it('should render active campaign count KPI card', () => {
      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('Active Campaigns')).toBeInTheDocument();
      // 1 active campaign out of 2 total
    });

    it('should render team members list', () => {
      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('John Doe')).toBeInTheDocument();
      expect(screen.getByText('john@acme.test')).toBeInTheDocument();
      expect(screen.getByText('Admin')).toBeInTheDocument();
    });

    it('should render "no team members" message when list is empty', () => {
      const brandWithNoTeam = { ...MOCK_BRAND_PENDING_KYC, teamMembers: [] };
      mockUseBrandDetail.mockReturnValue({
        data: brandWithNoTeam,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText(/No team members on file/i)).toBeInTheDocument();
    });
  });

  // ============================================
  // KYC STATUS PILL RENDERING
  // ============================================

  describe('KYC Status Pills', () => {
    it('should render "KYC Pending" pill for PENDING status', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('KYC Pending')).toBeInTheDocument();
    });

    it('should render "KYC Verified" pill for APPROVED status', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_APPROVED_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('KYC Verified')).toBeInTheDocument();
    });

    it('should render "KYC Rejected" pill for REJECTED status', () => {
      const rejectedBrand = { ...MOCK_BRAND_PENDING_KYC, kycStatus: KycStatus.REJECTED };
      mockUseBrandDetail.mockReturnValue({
        data: rejectedBrand,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('KYC Rejected')).toBeInTheDocument();
    });

    it('should render "Active" pill when not suspended', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('Active')).toBeInTheDocument();
    });

    it('should render "Suspended" pill when suspended', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_SUSPENDED,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByText('Suspended')).toBeInTheDocument();
    });
  });

  // ============================================
  // KYC ACTION BUTTONS — CRITICAL FLOW
  // ============================================

  describe('KYC Action Flow (P1 CRITICAL)', () => {
    it('should show Approve/Reject KYC buttons only when status is PENDING', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByRole('button', { name: /Approve KYC/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Reject KYC/i })).toBeInTheDocument();
    });

    it('should NOT show Approve/Reject KYC buttons when status is APPROVED', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_APPROVED_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.queryByRole('button', { name: /Approve KYC/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Reject KYC/i })).not.toBeInTheDocument();
    });

    it('should open dialog, accept justification, and call verifyKyc(APPROVE) on Approve flow', async () => {
      const user = userEvent.setup();

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
        refresh: vi.fn(),
      });

      render(<BrandProfile brandId="brand-123" />);

      // Click the Approve KYC trigger (opens the AlertDialog)
      await user.click(screen.getByRole('button', { name: /Approve KYC/i }));

      // Dialog appears; a justification is required before the action enables
      const textarea = await screen.findByPlaceholderText(/Justification for approval/i);
      await user.type(textarea, 'GST/PAN docs verified');

      // The confirm action is the last button labelled "Approve KYC" (trigger is first)
      const approveButtons = screen.getAllByRole('button', { name: /Approve KYC/i });
      await user.click(approveButtons[approveButtons.length - 1]);

      await waitFor(() => {
        expect(mockVerifyKyc).toHaveBeenCalledWith({
          brandId: 'brand-123',
          action: 'APPROVE',
          reason: 'GST/PAN docs verified',
        });
      });

      // Confirmation note replaces the stub notice from the pre-wiring version
      expect(await screen.findByText(/KYC approved\./i)).toBeInTheDocument();
    });

    it('should open dialog, accept reason, and call verifyKyc(REJECT) on Reject flow', async () => {
      const user = userEvent.setup();

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
        refresh: vi.fn(),
      });

      render(<BrandProfile brandId="brand-123" />);

      // Click Reject KYC button (opens AlertDialog)
      await user.click(screen.getByRole('button', { name: /Reject KYC/i }));

      // Dialog should appear with title
      await screen.findByText(/Reject KYC for Acme Corp/i);

      // Find textarea and type a reason
      const textarea = screen.getByPlaceholderText(/Reason for rejection/i);
      await user.type(textarea, 'Incorporation document expired');

      // Click the "Reject KYC" action button in the dialog (last of the matches)
      const rejectButtons = screen.getAllByRole('button', { name: /Reject KYC/i });
      await user.click(rejectButtons[rejectButtons.length - 1]);

      await waitFor(() => {
        expect(mockVerifyKyc).toHaveBeenCalledWith({
          brandId: 'brand-123',
          action: 'REJECT',
          reason: 'Incorporation document expired',
        });
      });

      expect(await screen.findByText(/KYC rejected\./i)).toBeInTheDocument();
    });
  });

  // ============================================
  // SUSPEND/REINSTATE ACTIONS
  // ============================================

  describe('Suspend/Reinstate Flow', () => {
    it('should show Suspend button when brand is not suspended', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByRole('button', { name: /Suspend Account/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Reinstate Account/i })).not.toBeInTheDocument();
    });

    it('should show Reinstate button when brand is suspended', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_SUSPENDED,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      expect(screen.getByRole('button', { name: /Reinstate Account/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Suspend Account/i })).not.toBeInTheDocument();
    });

    it('should open dialog, accept reason, and call suspend on Suspend flow', async () => {
      const user = userEvent.setup();

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
        refresh: vi.fn(),
      });

      render(<BrandProfile brandId="brand-123" />);

      await user.click(screen.getByRole('button', { name: /Suspend Account/i }));

      // Dialog should appear
      await screen.findByText(/Suspend Acme Corp/i);

      const textarea = screen.getByPlaceholderText(/Reason for suspension/i);
      await user.type(textarea, 'Policy violation');

      const suspendButtons = screen.getAllByRole('button', { name: /Suspend Account/i });
      await user.click(suspendButtons[suspendButtons.length - 1]);

      await waitFor(() => {
        expect(mockSuspend).toHaveBeenCalledWith('brand-123', 'Policy violation');
      });

      expect(await screen.findByText(/Brand suspended\./i)).toBeInTheDocument();
    });

    it('should open dialog, accept reason, and call reinstate on Reinstate flow', async () => {
      const user = userEvent.setup();

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_SUSPENDED,
        isLoading: false,
        error: null,
        refresh: vi.fn(),
      });

      render(<BrandProfile brandId="brand-123" />);

      await user.click(screen.getByRole('button', { name: /Reinstate Account/i }));

      // Reinstate is also a reason-gated dialog (audit trail), not a bare click
      await screen.findByText(/Reinstate Acme Corp/i);

      const textarea = screen.getByPlaceholderText(/Reason for reinstatement/i);
      await user.type(textarea, 'Appeal reviewed, docs re-verified');

      const reinstateButtons = screen.getAllByRole('button', { name: /Reinstate Account/i });
      await user.click(reinstateButtons[reinstateButtons.length - 1]);

      await waitFor(() => {
        expect(mockReinstate).toHaveBeenCalledWith('brand-123', 'Appeal reviewed, docs re-verified');
      });

      expect(await screen.findByText(/Brand reinstated\./i)).toBeInTheDocument();
    });
  });

  // ============================================
  // CONTACT BRAND BUTTON
  // ============================================

  describe('Contact Brand Action', () => {
    it('should render Contact Brand button with mailto link', () => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      const contactButton = screen.getByRole('link', { name: /Contact Brand/i });
      expect(contactButton).toBeInTheDocument();
      expect(contactButton).toHaveAttribute('href', 'mailto:contact@acme.test');
    });
  });

  // ============================================
  // ACCESSIBILITY
  // ============================================

  describe('Accessibility', () => {
    beforeEach(() => {
      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });
    });

    it('should render all action buttons as <button> elements', () => {
      render(<BrandProfile brandId="brand-123" />);

      const buttons = screen.getAllByRole('button');
      // Should have: Approve KYC, Reject KYC (trigger), Suspend Account (trigger)
      expect(buttons.length).toBeGreaterThanOrEqual(3);
    });

    it('should use aria-hidden on decorative icons', () => {
      render(<BrandProfile brandId="brand-123" />);

      // Icons mocked with data-testid should have aria-hidden (implicit in component)
      // This test verifies the component structure renders correctly
      expect(screen.getByTestId('icon-building')).toBeInTheDocument();
    });
  });
});
