/**
 * INFLUORA ADMIN PANEL — FlagQueue Component Tests
 * Owner: Kavya (QA Lead)
 * Cycle: 7
 *
 * Tests for FlagQueue.tsx — the content moderation flag queue with reason-required action dialogs.
 * Focus: Loading/error states, status filter, action flows (approve-remove/dismiss/escalate), and
 * verification that actions appear only when flag status is not ACTIONED.
 *
 * Run: npx vitest run FlagQueue.test
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FlagQueue from './FlagQueue';
import { ContentFlagStatus } from '../../types/admin.types';
import type { ContentFlag } from '../../types/admin.types';

// ============================================
// MOCKS
// ============================================

// Mock the useFlagQueue hook
const mockUseFlagQueue = vi.fn();
vi.mock('../../hooks/useFlagQueue', () => ({
  useFlagQueue: () => mockUseFlagQueue(),
}));

// Mock lucide-react icons to avoid rendering issues
vi.mock('lucide-react', async () => {
  const actual = await vi.importActual('lucide-react');
  return {
    ...actual,
    ArrowDown: () => <span data-testid="icon-arrow-down" />,
    ArrowUp: () => <span data-testid="icon-arrow-up" />,
    ArrowUpDown: () => <span data-testid="icon-arrow-up-down" />,
    Ban: () => <span data-testid="icon-ban" />,
    Bot: () => <span data-testid="icon-bot" />,
    CheckCircle2: () => <span data-testid="icon-check-circle" />,
    Clock: () => <span data-testid="icon-clock" />,
    Eye: () => <span data-testid="icon-eye" />,
    Flag: () => <span data-testid="icon-flag" />,
    FileWarning: () => <span data-testid="icon-file-warning" />,
    MessageSquareWarning: () => <span data-testid="icon-message-warning" />,
    ShieldAlert: () => <span data-testid="icon-shield-alert" />,
    TriangleAlert: () => <span data-testid="icon-triangle-alert" />,
    User: () => <span data-testid="icon-user" />,
    UserRound: () => <span data-testid="icon-user-round" />,
  };
});

// ============================================
// TEST FIXTURES
// ============================================

const MOCK_FLAG_PENDING: ContentFlag = {
  id: 'flag-001',
  contentType: 'DELIVERABLE',
  contentId: 'deliverable-abc',
  contentPreview: 'This is a flagged deliverable caption with inappropriate content.',
  flagReason: 'Potential brand safety violation detected by AI',
  flaggedBy: 'AI',
  status: ContentFlagStatus.PENDING,
  createdAt: '2026-07-09T10:00:00Z',
  reviewedAt: undefined,
  reviewedBy: undefined,
  actionTaken: undefined,
};

const MOCK_FLAG_REVIEWED: ContentFlag = {
  id: 'flag-002',
  contentType: 'PROFILE',
  contentId: 'creator-xyz',
  contentPreview: 'Creator bio excerpt...',
  flagReason: 'User report: misleading follower count',
  flaggedBy: 'USER',
  status: ContentFlagStatus.REVIEWED,
  createdAt: '2026-07-08T15:30:00Z',
  reviewedAt: '2026-07-09T09:00:00Z',
  reviewedBy: 'admin-123',
  actionTaken: undefined,
};

const MOCK_FLAG_ACTIONED: ContentFlag = {
  id: 'flag-003',
  contentType: 'MESSAGE',
  contentId: 'message-123',
  contentPreview: 'Message preview...',
  flagReason: 'Spam detected',
  flaggedBy: 'ADMIN',
  status: ContentFlagStatus.ACTIONED,
  createdAt: '2026-07-07T12:00:00Z',
  reviewedAt: '2026-07-08T10:00:00Z',
  reviewedBy: 'admin-456',
  actionTaken: 'Content removed',
};

const MOCK_EMPTY_STATE = {
  flags: [],
  totalCount: 0,
  pendingCount: 0,
  isLoading: false,
  error: null,
  filters: {},
  setFilters: vi.fn(),
  sort: { field: 'createdAt', direction: 'desc' as const },
  setSort: vi.fn(),
};

const MOCK_LOADED_STATE = {
  flags: [MOCK_FLAG_PENDING, MOCK_FLAG_REVIEWED, MOCK_FLAG_ACTIONED],
  totalCount: 3,
  pendingCount: 1,
  isLoading: false,
  error: null,
  filters: {},
  setFilters: vi.fn(),
  sort: { field: 'createdAt', direction: 'desc' as const },
  setSort: vi.fn(),
};

// ============================================
// TEST SUITE
// ============================================

describe('FlagQueue Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ============================================
  // LOADING & ERROR STATES
  // ============================================

  describe('Loading and Error States', () => {
    it('should render loading skeleton when data is loading', () => {
      mockUseFlagQueue.mockReturnValue({
        ...MOCK_EMPTY_STATE,
        isLoading: true,
      });

      render(<FlagQueue />);

      // Loading skeleton uses animate-pulse classes
      const skeletons = document.querySelectorAll('.animate-pulse');
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it('should render error message when fetch fails', () => {
      mockUseFlagQueue.mockReturnValue({
        ...MOCK_EMPTY_STATE,
        error: 'Network timeout',
      });

      render(<FlagQueue />);

      expect(screen.getByText(/Failed to load content flags/i)).toBeInTheDocument();
      expect(screen.getByText(/Network timeout/i)).toBeInTheDocument();
    });

    it('should render empty state when no flags match filters', () => {
      mockUseFlagQueue.mockReturnValue(MOCK_EMPTY_STATE);

      render(<FlagQueue />);

      expect(screen.getByText(/No content flags match the current filters/i)).toBeInTheDocument();
    });
  });

  // ============================================
  // FLAG TABLE RENDERING
  // ============================================

  describe('Flag Table Display', () => {
    beforeEach(() => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);
    });

    it('should render all flags in the table', () => {
      render(<FlagQueue />);

      expect(screen.getByText('deliverable-abc')).toBeInTheDocument();
      expect(screen.getByText('creator-xyz')).toBeInTheDocument();
      expect(screen.getByText('message-123')).toBeInTheDocument();
    });

    it('should render flag reason for each flag', () => {
      render(<FlagQueue />);

      expect(
        screen.getByText(/Potential brand safety violation detected by AI/i)
      ).toBeInTheDocument();
      expect(screen.getByText(/User report: misleading follower count/i)).toBeInTheDocument();
      expect(screen.getByText(/Spam detected/i)).toBeInTheDocument();
    });

    it('should render content type badges', () => {
      render(<FlagQueue />);

      expect(screen.getByText('DELIVERABLE')).toBeInTheDocument();
      expect(screen.getByText('PROFILE')).toBeInTheDocument();
      expect(screen.getByText('MESSAGE')).toBeInTheDocument();
    });

    it('should render flaggedBy indicators', () => {
      render(<FlagQueue />);

      expect(screen.getByText('AI')).toBeInTheDocument();
      expect(screen.getByText('USER')).toBeInTheDocument();
      expect(screen.getByText('ADMIN')).toBeInTheDocument();
    });

    it('should render total count and pending count', () => {
      render(<FlagQueue />);

      expect(screen.getByText(/3 of 3 flags/i)).toBeInTheDocument();
      expect(screen.getByText(/1 pending/i)).toBeInTheDocument();
    });
  });

  // ============================================
  // STATUS FILTERS
  // ============================================

  describe('Status Filter', () => {
    it('should render status filter dropdown', () => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      const statusFilter = screen.getByLabelText(/Filter by status/i);
      expect(statusFilter).toBeInTheDocument();
      expect(statusFilter).toHaveAttribute('role', 'combobox');
    });
  });

  // ============================================
  // CONTENT TYPE FILTER
  // ============================================

  describe('Content Type Filter', () => {
    it('should render content type filter dropdown', () => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      const contentTypeFilter = screen.getByLabelText(/Filter by content type/i);
      expect(contentTypeFilter).toBeInTheDocument();
      expect(contentTypeFilter).toHaveAttribute('role', 'combobox');
    });
  });

  // ============================================
  // SEARCH FILTER
  // ============================================

  describe('Search Filter', () => {
    it('should render search input', () => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      const searchInput = screen.getByPlaceholderText(/Search flag reason or content id/i);
      expect(searchInput).toBeInTheDocument();
    });

    it('should call setFilters when search input changes', async () => {
      const user = userEvent.setup();
      const mockSetFilters = vi.fn();
      mockUseFlagQueue.mockReturnValue({
        ...MOCK_LOADED_STATE,
        setFilters: mockSetFilters,
      });

      render(<FlagQueue />);

      const searchInput = screen.getByPlaceholderText(/Search flag reason or content id/i);
      await user.type(searchInput, 'brand safety');

      await waitFor(() => {
        expect(mockSetFilters).toHaveBeenCalled();
      });
    });
  });

  // ============================================
  // SORTING
  // ============================================

  describe('Sortable Columns', () => {
    it('should call setSort when clicking sortable column header', async () => {
      const user = userEvent.setup();
      const mockSetSort = vi.fn();
      mockUseFlagQueue.mockReturnValue({
        ...MOCK_LOADED_STATE,
        setSort: mockSetSort,
      });

      render(<FlagQueue />);

      // Find the "Status" sortable header
      const statusHeader = screen.getByRole('button', { name: /Status/i });
      await user.click(statusHeader);

      await waitFor(() => {
        expect(mockSetSort).toHaveBeenCalledWith({
          field: 'status',
          direction: 'asc',
        });
      });
    });

    it('should toggle sort direction when clicking same column twice', async () => {
      const user = userEvent.setup();
      const mockSetSort = vi.fn();
      mockUseFlagQueue.mockReturnValue({
        ...MOCK_LOADED_STATE,
        sort: { field: 'createdAt', direction: 'asc' as const },
        setSort: mockSetSort,
      });

      render(<FlagQueue />);

      const createdAtHeader = screen.getByRole('button', { name: /Flagged At/i });
      await user.click(createdAtHeader);

      await waitFor(() => {
        expect(mockSetSort).toHaveBeenCalledWith({
          field: 'createdAt',
          direction: 'desc',
        });
      });
    });
  });

  // ============================================
  // FLAG DETAIL DRAWER
  // ============================================

  describe('Flag Detail Drawer', () => {
    beforeEach(() => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);
    });

    it('should open detail drawer when clicking a flag row', async () => {
      const user = userEvent.setup();

      render(<FlagQueue />);

      const flagRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(flagRow);

      // Drawer should show flag content type and ID in title
      await waitFor(() => {
        expect(screen.getByText('DELIVERABLE · deliverable-abc')).toBeInTheDocument();
      }, { timeout: 3000 });
    });

    it('should show content preview in drawer', async () => {
      const user = userEvent.setup();

      render(<FlagQueue />);

      const flagRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(flagRow);

      await waitFor(() => {
        expect(
          screen.getByText(/This is a flagged deliverable caption with inappropriate content/i)
        ).toBeInTheDocument();
      });
    });

    it('should show reviewed metadata when available', async () => {
      const user = userEvent.setup();

      render(<FlagQueue />);

      const reviewedFlagRow = screen.getByRole('button', {
        name: /Review flag: User report: misleading follower count/i,
      });
      await user.click(reviewedFlagRow);

      await waitFor(() => {
        expect(screen.getByText(/Reviewed by/i)).toBeInTheDocument();
        expect(screen.getByText('admin-123')).toBeInTheDocument();
      });
    });
  });

  // ============================================
  // MODERATION ACTIONS — CRITICAL FLOW
  // ============================================

  describe('Moderation Action Flow (P2 CRITICAL)', () => {
    it('should show all action buttons for PENDING or REVIEWED flags', async () => {
      const user = userEvent.setup();
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      // Open drawer for PENDING flag
      const pendingRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(pendingRow);

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /Approve & Remove Content/i })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /Dismiss Flag/i })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /Escalate/i })).toBeInTheDocument();
      });
    });

    it('should NOT show action buttons for ACTIONED flags', async () => {
      const user = userEvent.setup();
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      // Open drawer for ACTIONED flag
      const actionedRow = screen.getByRole('button', { name: /Review flag: Spam detected/i });
      await user.click(actionedRow);

      await waitFor(() => {
        expect(
          screen.getByText(/This flag has already been actioned — no further action is available/i)
        ).toBeInTheDocument();
      });

      expect(screen.queryByRole('button', { name: /Approve & Remove Content/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Dismiss Flag/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Escalate/i })).not.toBeInTheDocument();
    });

    it('should open reason dialog, accept reason, and call handleAction for REMOVE', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      // Open drawer
      const pendingRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(pendingRow);

      // Click "Approve & Remove Content" (opens AlertDialog)
      const removeButton = await screen.findByRole('button', {
        name: /Approve & Remove Content/i,
      });
      await user.click(removeButton);

      // Dialog should appear
      await waitFor(() => {
        expect(screen.getByText(/Remove this content/i)).toBeInTheDocument();
      });

      // Type reason
      const textarea = screen.getByPlaceholderText(
        /Reason for removal \(e.g. confirmed brand-safety violation\)/i
      );
      await user.type(textarea, 'Confirmed policy violation');

      // Click action button in dialog
      const dialogRemoveButtons = screen.getAllByRole('button', {
        name: /Approve & Remove Content/i,
      });
      const dialogRemoveButton = dialogRemoveButtons[dialogRemoveButtons.length - 1];
      await user.click(dialogRemoveButton);

      // Verify console log was called (stub behavior)
      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalledWith(
          '[FlagQueue] stub: moderation action',
          expect.objectContaining({
            flagId: 'flag-001',
            action: 'REMOVE',
            reason: 'Confirmed policy violation',
          })
        );
      });

      // Verify stub notice appears
      await waitFor(() => {
        expect(
          screen.getByText(/This action is stubbed — no moderation controller is live yet/i)
        ).toBeInTheDocument();
      });

      consoleInfo.mockRestore();
    });

    it('should open reason dialog and call handleAction for REJECT (dismiss)', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      // Open drawer
      const pendingRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(pendingRow);

      // Click "Dismiss Flag"
      const dismissButton = await screen.findByRole('button', { name: /Dismiss Flag/i });
      await user.click(dismissButton);

      // Dialog should appear
      await waitFor(() => {
        expect(screen.getByText(/Dismiss this flag/i)).toBeInTheDocument();
      });

      // Type reason
      const textarea = screen.getByPlaceholderText(
        /Reason for dismissal \(e.g. false positive, content is compliant\)/i
      );
      await user.type(textarea, 'False positive — content is compliant');

      // Click action button
      const dialogDismissButtons = screen.getAllByRole('button', { name: /Dismiss Flag/i });
      const dialogDismissButton = dialogDismissButtons[dialogDismissButtons.length - 1];
      await user.click(dialogDismissButton);

      // Verify console log
      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalledWith(
          '[FlagQueue] stub: moderation action',
          expect.objectContaining({
            flagId: 'flag-001',
            action: 'REJECT',
          })
        );
      });

      consoleInfo.mockRestore();
    });

    it('should open reason dialog and call handleAction for ESCALATE', async () => {
      const user = userEvent.setup();
      const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      // Open drawer
      const pendingRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(pendingRow);

      // Click "Escalate"
      const escalateButton = await screen.findByRole('button', { name: /Escalate/i });
      await user.click(escalateButton);

      // Dialog should appear
      await waitFor(() => {
        expect(screen.getByText(/Escalate this flag/i)).toBeInTheDocument();
      });

      // Type reason
      const textarea = screen.getByPlaceholderText(
        /Reason for escalation \(e.g. possible legal\/compliance risk\)/i
      );
      await user.type(textarea, 'Possible legal risk, needs senior review');

      // Click action button
      const dialogEscalateButtons = screen.getAllByRole('button', { name: /Escalate/i });
      const dialogEscalateButton = dialogEscalateButtons[dialogEscalateButtons.length - 1];
      await user.click(dialogEscalateButton);

      // Verify console log
      await waitFor(() => {
        expect(consoleInfo).toHaveBeenCalledWith(
          '[FlagQueue] stub: moderation action',
          expect.objectContaining({
            flagId: 'flag-001',
            action: 'ESCALATE',
          })
        );
      });

      consoleInfo.mockRestore();
    });

    it('should disable action button when reason is empty', async () => {
      const user = userEvent.setup();
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      // Open drawer
      const pendingRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });
      await user.click(pendingRow);

      // Click "Approve & Remove Content"
      const removeButton = await screen.findByRole('button', {
        name: /Approve & Remove Content/i,
      });
      await user.click(removeButton);

      // Dialog should appear with disabled action button
      await waitFor(() => {
        const dialogRemoveButtons = screen.getAllByRole('button', {
          name: /Approve & Remove Content/i,
        });
        const dialogRemoveButton = dialogRemoveButtons[dialogRemoveButtons.length - 1];
        expect(dialogRemoveButton).toBeDisabled();
      });
    });
  });

  // ============================================
  // KEYBOARD ACCESSIBILITY
  // ============================================

  describe('Accessibility', () => {
    beforeEach(() => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);
    });

    it('should make flag rows keyboard-navigable with Enter key', async () => {
      const user = userEvent.setup();

      render(<FlagQueue />);

      const flagRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });

      // Press Enter to open drawer
      flagRow.focus();
      await user.keyboard('{Enter}');

      await waitFor(() => {
        expect(
          screen.getByText(/This is a flagged deliverable caption with inappropriate content/i)
        ).toBeInTheDocument();
      });
    });

    it('should make flag rows keyboard-navigable with Space key', async () => {
      const user = userEvent.setup();

      render(<FlagQueue />);

      const flagRow = screen.getByRole('button', {
        name: /Review flag: Potential brand safety violation/i,
      });

      // Press Space to open drawer
      flagRow.focus();
      await user.keyboard(' ');

      await waitFor(() => {
        expect(
          screen.getByText(/This is a flagged deliverable caption with inappropriate content/i)
        ).toBeInTheDocument();
      });
    });

    it('should use aria-label for search input', () => {
      render(<FlagQueue />);

      const searchInput = screen.getByLabelText(/Search flags/i);
      expect(searchInput).toBeInTheDocument();
    });

    it('should use aria-label for filter dropdowns', () => {
      render(<FlagQueue />);

      expect(screen.getByLabelText(/Filter by status/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/Filter by content type/i)).toBeInTheDocument();
    });
  });
});
