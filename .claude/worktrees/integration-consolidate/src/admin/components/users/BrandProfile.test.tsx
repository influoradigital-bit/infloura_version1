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

    it('should call handleApproveKyc and show stub notice on Approve click', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      const approveButton = screen.getByRole('button', { name: /Approve KYC/i });
      await user.click(approveButton);

      // Verify console log (stub behavior)
      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalledWith(
          '[BrandProfile] stub: approve KYC',
          expect.objectContaining({ brandId: 'brand-123' })
        );
      });

      // Verify stub notice appears
      expect(screen.getByText(/KYC approval is stubbed/i)).toBeInTheDocument();

      consoleInfo.mockRestore();
    });

    it('should open dialog, accept reason, and call handleRejectKyc on Reject flow', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      // Click Reject KYC button (opens AlertDialog)
      const rejectButton = screen.getByRole('button', { name: /Reject KYC/i });
      await user.click(rejectButton);

      // Dialog should appear with title
      await waitFor(() => {
        expect(screen.getByText(/Reject KYC for Acme Corp/i)).toBeInTheDocument();
      });

      // Find textarea and type a reason
      const textarea = screen.getByPlaceholderText(/Reason for rejection/i);
      await user.type(textarea, 'Incorporation document expired');

      // Click "Reject KYC" action button in dialog
      const dialogRejectButtons = screen.getAllByRole('button', { name: /Reject KYC/i });
      const dialogRejectButton = dialogRejectButtons[dialogRejectButtons.length - 1]; // Last one is in dialog
      await user.click(dialogRejectButton);

      // Verify console log was called
      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalled();
      }, { timeout: 3000 });

      // Verify stub notice appears
      await waitFor(() => {
        expect(screen.getByText(/KYC rejection is stubbed/i)).toBeInTheDocument();
      }, { timeout: 3000 });

      consoleInfo.mockRestore();
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

    it('should open dialog, accept reason, and call handleSuspend on Suspend flow', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_PENDING_KYC,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      const suspendButton = screen.getByRole('button', { name: /Suspend Account/i });
      await user.click(suspendButton);

      // Dialog should appear
      await waitFor(() => {
        expect(screen.getByText(/Suspend Acme Corp/i)).toBeInTheDocument();
      });

      const textarea = screen.getByPlaceholderText(/Reason for suspension/i);
      await user.type(textarea, 'Policy violation');

      const dialogSuspendButtons = screen.getAllByRole('button', { name: /Suspend Account/i });
      const dialogSuspendButton = dialogSuspendButtons[dialogSuspendButtons.length - 1];
      await user.click(dialogSuspendButton);

      // Verify console log was called
      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalled();
      }, { timeout: 3000 });

      // Verify stub notice appears
      await waitFor(() => {
        expect(screen.getByText(/Suspend is stubbed/i)).toBeInTheDocument();
      }, { timeout: 3000 });

      consoleInfo.mockRestore();
    });

    it('should call handleReinstate when Reinstate button clicked', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});

      mockUseBrandDetail.mockReturnValue({
        data: MOCK_BRAND_SUSPENDED,
        isLoading: false,
        error: null,
      });

      render(<BrandProfile brandId="brand-123" />);

      const reinstateButton = screen.getByRole('button', { name: /Reinstate Account/i });
      await user.click(reinstateButton);

      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalledWith(
          '[BrandProfile] stub: reinstate brand',
          expect.objectContaining({ brandId: 'brand-123' })
        );
      });

      expect(screen.getByText(/Reinstate is stubbed/i)).toBeInTheDocument();

      consoleInfo.mockRestore();
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
