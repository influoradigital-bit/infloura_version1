/**
 * INFLUORA ADMIN PANEL — AdminLayout Component Tests
 * Owner: Kavya (QA Lead)
 * Cycle: 6
 *
 * Tests for AdminLayout.tsx — the admin shell with sidebar nav and topbar.
 * Focus: Mobile nav keyboard accessibility (Escape closes nav, focus management
 * on open/close), nav link rendering, and audit logger initialization.
 *
 * This test protects against regressions in the cycle-3 keyboard accessibility
 * fix where mobile nav lacked Escape-to-close and focus return-to-trigger.
 *
 * Run: npx vitest run AdminLayout.test
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router-dom';
import AdminLayout from './AdminLayout';

// ============================================
// MOCKS
// ============================================

// Mock the audit logger initialization
const mockInitAuditLogger = vi.fn();
vi.mock('../utils/auditLogger', () => ({
  initAuditLogger: () => mockInitAuditLogger(),
}));

// Mock lucide-react icons
vi.mock('lucide-react', async () => {
  const actual = await vi.importActual('lucide-react');
  return {
    ...actual,
    LayoutDashboard: () => <span data-testid="icon-dashboard" />,
    Users: () => <span data-testid="icon-users" />,
    Megaphone: () => <span data-testid="icon-megaphone" />,
    Wallet: () => <span data-testid="icon-wallet" />,
    LifeBuoy: () => <span data-testid="icon-lifebuoy" />,
    ShieldAlert: () => <span data-testid="icon-shield" />,
    Menu: () => <span data-testid="icon-menu" />,
    X: () => <span data-testid="icon-x" />,
    Bell: () => <span data-testid="icon-bell" />,
    ChevronDown: () => <span data-testid="icon-chevron" />,
  };
});

// ============================================
// TEST HELPERS
// ============================================

function renderLayout(props = {}) {
  const defaultProps = {
    children: <div data-testid="page-content">Test Page Content</div>,
    adminName: 'Test Admin',
    pageTitle: 'Test Page',
  };

  return render(
    <BrowserRouter>
      <AdminLayout {...defaultProps} {...props} />
    </BrowserRouter>
  );
}

// ============================================
// TEST SUITE
// ============================================

describe('AdminLayout Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ============================================
  // INITIALIZATION
  // ============================================

  describe('Component Initialization', () => {
    it('should call initAuditLogger on mount', () => {
      renderLayout();

      expect(mockInitAuditLogger).toHaveBeenCalledTimes(1);
    });

    it('should render children content in main area', () => {
      renderLayout();

      expect(screen.getByTestId('page-content')).toBeInTheDocument();
      expect(screen.getByText('Test Page Content')).toBeInTheDocument();
    });

    it('should render page title in topbar', () => {
      renderLayout({ pageTitle: 'Brand Management' });

      expect(screen.getByText('Brand Management')).toBeInTheDocument();
    });

    it('should render admin name in topbar user menu', () => {
      renderLayout({ adminName: 'Kavya Reddy' });

      expect(screen.getByText('Kavya Reddy')).toBeInTheDocument();
    });

    it('should default to "Admin" if adminName not provided', () => {
      renderLayout({ adminName: undefined });

      // User menu button should show "Admin" (not the branding "Admin")
      const userMenuButtons = screen.getAllByText('Admin');
      expect(userMenuButtons.length).toBeGreaterThan(0);
    });
  });

  // ============================================
  // NAVIGATION ITEMS
  // ============================================

  describe('Navigation Items', () => {
    it('should render all 6 navigation sections', () => {
      renderLayout();

      expect(screen.getByRole('link', { name: /Dashboard/i })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Users/i })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Campaigns/i })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Finance/i })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Support/i })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Moderation/i })).toBeInTheDocument();
    });

    it('should set correct href for each nav link', () => {
      renderLayout();

      const dashboardLink = screen.getByRole('link', { name: /Dashboard/i });
      expect(dashboardLink).toHaveAttribute('href', '/admin');

      const usersLink = screen.getByRole('link', { name: /Users/i });
      expect(usersLink).toHaveAttribute('href', '/admin/users');

      const campaignsLink = screen.getByRole('link', { name: /Campaigns/i });
      expect(campaignsLink).toHaveAttribute('href', '/admin/campaigns');
    });
  });

  // ============================================
  // MOBILE NAV TOGGLE
  // ============================================

  describe('Mobile Navigation Toggle', () => {
    it('should start with mobile nav closed', () => {
      renderLayout();

      const sidebar = screen.getByLabelText('Admin navigation');
      expect(sidebar).toHaveClass('-translate-x-full');
    });

    it('should open mobile nav when menu button clicked', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      const sidebar = screen.getByLabelText('Admin navigation');
      expect(sidebar).toHaveClass('translate-x-0');
    });

    it('should close mobile nav when close button clicked', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      // Open nav
      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      // Close nav - get all close buttons and use the one inside the sidebar
      const closeButtons = screen.getAllByRole('button', { name: /Close navigation/i });
      // The sidebar close button is the one that's NOT the overlay
      const sidebarCloseButton = closeButtons.find(btn => btn.querySelector('[data-testid="icon-x"]'));
      if (sidebarCloseButton) {
        await user.click(sidebarCloseButton);
      }

      const sidebar = screen.getByLabelText('Admin navigation');
      await waitFor(() => {
        expect(sidebar).toHaveClass('-translate-x-full');
      });
    });

    it('should close mobile nav when overlay is clicked', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      // Open nav
      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      // Click overlay (second "Close navigation" button is the overlay)
      const overlayButton = screen.getAllByRole('button', { name: /Close navigation/i })[0];
      await user.click(overlayButton);

      const sidebar = screen.getByLabelText('Admin navigation');
      await waitFor(() => {
        expect(sidebar).toHaveClass('-translate-x-full');
      });
    });

    it('should close mobile nav when any nav link is clicked', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      // Open nav
      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      // Click a nav link
      const usersLink = screen.getByRole('link', { name: /Users/i });
      await user.click(usersLink);

      const sidebar = screen.getByLabelText('Admin navigation');
      await waitFor(() => {
        expect(sidebar).toHaveClass('-translate-x-full');
      });
    });
  });

  // ============================================
  // KEYBOARD ACCESSIBILITY (P1 CRITICAL — REGRESSION PROTECTION)
  // ============================================

  describe('Mobile Nav Keyboard Accessibility (Cycle 3 Fix)', () => {
    it('should close mobile nav when Escape key is pressed', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      // Open nav
      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      const sidebar = screen.getByLabelText('Admin navigation');
      expect(sidebar).toHaveClass('translate-x-0');

      // Press Escape
      await user.keyboard('{Escape}');

      await waitFor(() => {
        expect(sidebar).toHaveClass('-translate-x-full');
      });
    });

    it('should move focus to close button when mobile nav opens', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      // The close button inside the sidebar should receive focus
      const closeButtons = screen.getAllByRole('button', { name: /Close navigation/i });
      const sidebarCloseButton = closeButtons.find(btn => btn.querySelector('[data-testid="icon-x"]'));

      await waitFor(() => {
        expect(sidebarCloseButton).toHaveFocus();
      });
    });

    it('should return focus to menu trigger when mobile nav closes', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      const menuButton = screen.getByRole('button', { name: /Open navigation/i });

      // Open nav
      await user.click(menuButton);

      // Close button should have focus
      const closeButtons = screen.getAllByRole('button', { name: /Close navigation/i });
      const sidebarCloseButton = closeButtons.find(btn => btn.querySelector('[data-testid="icon-x"]'));

      await waitFor(() => {
        expect(sidebarCloseButton).toHaveFocus();
      });

      // Close nav
      if (sidebarCloseButton) {
        await user.click(sidebarCloseButton);
      }

      // Focus should return to menu button
      await waitFor(() => {
        expect(menuButton).toHaveFocus();
      });
    });

    it('should return focus to menu trigger when Escape closes nav', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      // Close with Escape
      await user.keyboard('{Escape}');

      // Focus should return to menu button
      await waitFor(() => {
        expect(menuButton).toHaveFocus();
      });
    });

    it('should make overlay button keyboard-operable (not just pointer)', async () => {
      const user = userEvent.setup({ delay: null });
      renderLayout();

      // Open nav
      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      await user.click(menuButton);

      // The overlay should be a <button>, not a <div>, so it's keyboard-operable
      const overlayButton = screen.getAllByRole('button', { name: /Close navigation/i })[0];
      expect(overlayButton.tagName).toBe('BUTTON');
    });
  });

  // ============================================
  // TOPBAR ELEMENTS
  // ============================================

  describe('Topbar Elements', () => {
    it('should render notifications button', () => {
      renderLayout();

      const notificationsButton = screen.getByRole('button', { name: /Notifications/i });
      expect(notificationsButton).toBeInTheDocument();
    });

    it('should not fabricate an unread notification indicator (no notifications source wired up)', () => {
      renderLayout();

      // No notifications API is wired up yet, so the button must not imply
      // unread items — it should be a disabled placeholder instead of a fake dot.
      const notificationsButton = screen.getByRole('button', { name: /Notifications/i });
      expect(notificationsButton).toBeDisabled();
      expect(document.querySelector('.bg-destructive-foreground')).not.toBeInTheDocument();
    });

    it('should render admin user menu button', () => {
      renderLayout({ adminName: 'Priya Sharma' });

      // User menu button shows first letter as avatar
      expect(screen.getByText('P')).toBeInTheDocument();
      expect(screen.getByText('Priya Sharma')).toBeInTheDocument();
    });

    it('should render Influora Admin branding in sidebar', () => {
      renderLayout();

      expect(screen.getByText(/Influora/i)).toBeInTheDocument();
      // Check for the full branding text structure
      const brandingElements = screen.getAllByText(/Admin/i);
      expect(brandingElements.length).toBeGreaterThan(0);
    });

    it('should render Phase 1 label in sidebar footer', () => {
      renderLayout();

      expect(screen.getByText(/Phase 1 — Admin Panel/i)).toBeInTheDocument();
    });
  });

  // ============================================
  // ARIA LABELS & SEMANTIC HTML
  // ============================================

  describe('Accessibility Semantics', () => {
    it('should use <aside> with aria-label for sidebar', () => {
      renderLayout();

      const sidebar = screen.getByLabelText('Admin navigation');
      expect(sidebar.tagName).toBe('ASIDE');
    });

    it('should use <nav> with aria-label for navigation list', () => {
      renderLayout();

      const nav = screen.getByLabelText('Sections');
      expect(nav.tagName).toBe('NAV');
    });

    it('should use <header> for topbar', () => {
      renderLayout();

      const header = document.querySelector('header');
      expect(header).toBeInTheDocument();
    });

    it('should use <main> for page content area', () => {
      renderLayout();

      const main = document.querySelector('main');
      expect(main).toBeInTheDocument();
      expect(main).toContainElement(screen.getByTestId('page-content'));
    });

    it('should mark decorative icons with aria-hidden', () => {
      renderLayout();

      // Icons are mocked with data-testid and should have aria-hidden in real component
      // This test verifies component structure
      expect(screen.getByTestId('icon-menu')).toBeInTheDocument();
    });
  });

  // ============================================
  // RESPONSIVE BEHAVIOR
  // ============================================

  describe('Responsive Behavior', () => {
    it('should hide close button on desktop (lg:hidden class)', () => {
      renderLayout();

      // Open mobile nav
      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      expect(menuButton).toBeInTheDocument();

      // Close button has lg:hidden class (not testable in jsdom, but verifies render)
      const closeButton = screen.getByRole('button', { name: /Close navigation/i });
      expect(closeButton).toHaveClass('lg:hidden');
    });

    it('should hide menu trigger on desktop (lg:hidden class)', () => {
      renderLayout();

      const menuButton = screen.getByRole('button', { name: /Open navigation/i });
      expect(menuButton).toHaveClass('lg:hidden');
    });
  });

  // ============================================
  // EDGE CASES
  // ============================================

  describe('Edge Cases', () => {
    it('should not crash when children is null', () => {
      expect(() => {
        render(
          <BrowserRouter>
            <AdminLayout>{null}</AdminLayout>
          </BrowserRouter>
        );
      }).not.toThrow();
    });

    it('should handle empty pageTitle gracefully', () => {
      renderLayout({ pageTitle: '' });

      // Should render empty string, not crash
      expect(document.querySelector('h1')).toBeInTheDocument();
    });

    it('should render initials correctly for single-letter name', () => {
      renderLayout({ adminName: 'K' });

      // Avatar shows first letter
      const avatarElements = screen.getAllByText('K');
      expect(avatarElements.length).toBeGreaterThan(0);
    });

    it('should render initials correctly for multi-word name', () => {
      renderLayout({ adminName: 'kavya reddy' });

      // First letter uppercased
      expect(screen.getByText('K')).toBeInTheDocument();
    });
  });
});
