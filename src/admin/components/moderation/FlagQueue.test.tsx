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

import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import { render as rtlRender, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import FlagQueue from './FlagQueue';
import { ContentFlagStatus } from '../../types/admin.types';
import type { ContentFlag } from '../../types/admin.types';

// FlagQueue's FlagDetailDrawer calls useQueryClient()/useMutation(), so the tree
// needs a QueryClientProvider — in the app that comes from App.tsx. Without it
// every render throws "No QueryClient set". Shadow RTL's `render` so the call
// sites below stay plain `render(<FlagQueue />)`. Retries off: a failing
// mutation should surface immediately rather than stall the test.
function render(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return rtlRender(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

// ============================================
// MOCKS
// ============================================

// Mock the useFlagQueue hook
const mockUseFlagQueue = vi.fn();
vi.mock('../../hooks/useFlagQueue', () => ({
  useFlagQueue: () => mockUseFlagQueue(),
}));

// Mock moderationApi.actionFlag — FlagDetailDrawer is live-wired to the real
// POST /moderation/flags/:id/action endpoint (Vikram, 2026-07-12), not a
// stub. Same vi.importActual + spy-per-method pattern as the other
// live-wired-API test in this repo (src/pages/creator-disputes.test.tsx
// mocking '@/lib/api').
const actionFlagMock = vi.fn();
vi.mock('../../services/api-contracts', async () => {
  const actual = await vi.importActual<typeof import('../../services/api-contracts')>(
    '../../services/api-contracts'
  );
  return {
    ...actual,
    moderationApi: {
      ...actual.moderationApi,
      actionFlag: (...args: unknown[]) => actionFlagMock(...args),
    },
  };
});

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

const MOCK_FLAG_ESCALATED: ContentFlag = {
  id: 'flag-004',
  contentType: 'MESSAGE',
  contentId: 'message-999',
  contentPreview: 'Escalated message preview...',
  flagReason: 'Possible legal/compliance risk',
  flaggedBy: 'ADMIN',
  status: ContentFlagStatus.ESCALATED,
  createdAt: '2026-07-10T11:00:00Z',
  reviewedAt: undefined,
  reviewedBy: undefined,
  actionTaken: undefined,
};

const MOCK_EMPTY_STATE = {
  flags: [],
  totalCount: 0,
  unresolvedCount: 0,
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
  // Only PENDING counts here — ESCALATED coverage lives in
  // MOCK_LOADED_STATE_WITH_ESCALATED below so this fixture stays untouched
  // for the unrelated table/filter/sort/a11y tests that assert against it.
  unresolvedCount: 1,
  isLoading: false,
  error: null,
  filters: {},
  setFilters: vi.fn(),
  sort: { field: 'createdAt', direction: 'desc' as const },
  setSort: vi.fn(),
};

// unresolvedCount = PENDING (flag-001) + ESCALATED (flag-004) = 2 — this is
// the regression fixture for the ESCALATED-counts-as-unresolved ruling.
const MOCK_LOADED_STATE_WITH_ESCALATED = {
  flags: [MOCK_FLAG_PENDING, MOCK_FLAG_REVIEWED, MOCK_FLAG_ACTIONED, MOCK_FLAG_ESCALATED],
  totalCount: 4,
  unresolvedCount: 2,
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
    actionFlagMock.mockResolvedValue({ ...MOCK_FLAG_PENDING, status: ContentFlagStatus.ACTIONED });
  });

  // Radix Select requires PointerEvent capture APIs that jsdom lacks — same
  // polyfill as src/pages/creator-disputes.test.tsx, needed only for the
  // "appears in the status filter" test below, which actually opens the menu.
  beforeAll(() => {
    Object.defineProperty(HTMLElement.prototype, 'hasPointerCapture', {
      configurable: true,
      value: () => false,
    });
    Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
      configurable: true,
      value: () => undefined,
    });
    Object.defineProperty(HTMLElement.prototype, 'releasePointerCapture', {
      configurable: true,
      value: () => undefined,
    });
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: () => undefined,
    });
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

    it('should render total count and unresolved count', () => {
      render(<FlagQueue />);

      expect(screen.getByText(/3 of 3 flags/i)).toBeInTheDocument();
      expect(screen.getByText(/1 unresolved/i)).toBeInTheDocument();
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

    it('should list ESCALATED as a selectable status filter option', async () => {
      const user = userEvent.setup();
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE);

      render(<FlagQueue />);

      const statusFilter = screen.getByLabelText(/Filter by status/i);
      await user.click(statusFilter);

      expect(await screen.findByRole('option', { name: 'Escalated' })).toBeInTheDocument();
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

    it('should open reason dialog, accept reason, and call moderationApi.actionFlag for REMOVE', async () => {
      const user = userEvent.setup();
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

      // Verify the real mutation fired against POST /moderation/flags/:id/action
      // with the correct flag id, action, and reason payload.
      await waitFor(() => {
        expect(actionFlagMock).toHaveBeenCalledWith('flag-001', {
          entityId: 'flag-001',
          entityType: 'CONTENT_FLAG',
          action: 'REMOVE',
          reason: 'Confirmed policy violation',
        });
      });

      // Verify the real success notice appears (not a stub notice — the stub
      // path is gone now that the component is live-wired).
      await waitFor(() => {
        expect(screen.getByText(/Action applied successfully/i)).toBeInTheDocument();
      });
    });

    it('should open reason dialog and call moderationApi.actionFlag for REJECT (dismiss)', async () => {
      const user = userEvent.setup();
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

      // Verify the real mutation fired with the REJECT action + reason.
      await waitFor(() => {
        expect(actionFlagMock).toHaveBeenCalledWith('flag-001', {
          entityId: 'flag-001',
          entityType: 'CONTENT_FLAG',
          action: 'REJECT',
          reason: 'False positive — content is compliant',
        });
      });
    });

    it('should open reason dialog and call moderationApi.actionFlag for ESCALATE, and never allow submission without a reason', async () => {
      const user = userEvent.setup();
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

      // Backend enforces REASON_REQUIRED/400 on ESCALATE before any mutation
      // (Vikram) — confirm the confirm button is disabled with an empty
      // reason, i.e. the UI cannot submit ESCALATE without a reason.
      const dialogEscalateButtonEmpty = screen
        .getAllByRole('button', { name: /Escalate/i })
        .at(-1);
      expect(dialogEscalateButtonEmpty).toBeDisabled();

      // Type reason
      const textarea = screen.getByPlaceholderText(
        /Reason for escalation \(e.g. possible legal\/compliance risk\)/i
      );
      await user.type(textarea, 'Possible legal risk, needs senior review');

      // Click action button
      const dialogEscalateButtons = screen.getAllByRole('button', { name: /Escalate/i });
      const dialogEscalateButton = dialogEscalateButtons[dialogEscalateButtons.length - 1];
      expect(dialogEscalateButton).toBeEnabled();
      await user.click(dialogEscalateButton);

      // Verify the real mutation fired with the ESCALATE action + reason.
      await waitFor(() => {
        expect(actionFlagMock).toHaveBeenCalledWith('flag-001', {
          entityId: 'flag-001',
          entityType: 'CONTENT_FLAG',
          action: 'ESCALATE',
          reason: 'Possible legal risk, needs senior review',
        });
      });
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
  // ESCALATED STATUS — REGRESSION GUARD (P2)
  //
  // Priya ruling 2026-07-15: an ESCALATED flag is UNRESOLVED work. It must
  // never render with the same green/checkmark treatment as a resolved
  // (ACTIONED) flag, and it must count toward the queue's unresolved badge
  // the same way PENDING does. This block pins down the exact defect Ananya
  // found: ESCALATED falling through `default:` in statusPillTone/statusIcon
  // renders a success pill — these tests fail if that regression returns.
  // ============================================

  describe('Escalated Status (unresolved-work regression guard)', () => {
    it('should render the escalated pill for an ESCALATED flag, never the success/checkmark treatment', () => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE_WITH_ESCALATED);

      render(<FlagQueue />);

      // The pill's own text is "Escalated" (getByText resolves to the pill
      // <span> itself, since its icon sibling contributes no text content).
      const pill = screen.getByText('Escalated');

      // Must use the escalated tone (bg-accent-foreground), not success
      // (bg-success-foreground) — this is the exact bug: ESCALATED falling
      // through `default:` in statusPillTone reads as resolved/green.
      expect(pill.className).toContain('bg-accent-foreground');
      expect(pill.className).not.toContain('bg-success-foreground');

      // Must render the TriangleAlert icon, not the CheckCircle2 success icon
      // — same regression, caught in statusIcon's `default:` fallthrough.
      expect(pill.querySelector('[data-testid="icon-triangle-alert"]')).toBeInTheDocument();
      expect(pill.querySelector('[data-testid="icon-check-circle"]')).not.toBeInTheDocument();
    });

    it('should count the ESCALATED flag toward the unresolved badge alongside PENDING', () => {
      mockUseFlagQueue.mockReturnValue(MOCK_LOADED_STATE_WITH_ESCALATED);

      render(<FlagQueue />);

      // 1 PENDING (flag-001) + 1 ESCALATED (flag-004) = 2 unresolved.
      expect(screen.getByText(/2 unresolved/i)).toBeInTheDocument();
      // The old "pending"-only wording must be gone now that the count
      // covers more than PENDING.
      expect(screen.queryByText(/2 pending/i)).not.toBeInTheDocument();
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
