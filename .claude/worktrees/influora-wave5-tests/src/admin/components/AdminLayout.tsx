/**
 * INFLUORA ADMIN PANEL — Admin Shell Layout
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P0)
 *
 * Sidebar nav (Dashboard, Users, Campaigns, Finance, Support, Moderation)
 * + topbar. Wraps route-level pages via <Outlet /> or children.
 *
 * Auth/RBAC gating is NOT this component's job — `useAdminAuth`
 * (src/admin/hooks/useAdminAuth.ts, live-wired to `authApi.getCurrentUser`)
 * wraps the routed pages elsewhere. This shell only renders nav chrome and
 * the topbar user menu (which does call `useAdminAuth().logout()` directly);
 * it does not itself gate route access.
 */

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard,
  Users,
  Megaphone,
  Wallet,
  LifeBuoy,
  ShieldAlert,
  Scale,
  CreditCard,
  Menu,
  X,
  Bell,
  ChevronDown,
  LogOut,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { initAuditLogger } from '../utils/auditLogger';
import { useAdminAuth } from '../hooks/useAdminAuth';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

// ============================================
// NAV CONFIG
// ============================================

interface AdminNavItem {
  label: string;
  to: string;
  icon: typeof LayoutDashboard;
}

const NAV_ITEMS: AdminNavItem[] = [
  { label: 'Dashboard', to: '/admin', icon: LayoutDashboard },
  { label: 'Users', to: '/admin/users', icon: Users },
  { label: 'Campaigns', to: '/admin/campaigns', icon: Megaphone },
  { label: 'Finance', to: '/admin/finance', icon: Wallet },
  { label: 'Support', to: '/admin/support', icon: LifeBuoy },
  { label: 'Moderation', to: '/admin/moderation', icon: ShieldAlert },
  { label: 'Disputes', to: '/admin/disputes', icon: Scale },
  { label: 'Billing', to: '/admin/billing', icon: CreditCard },
];

// ============================================
// TYPES
// ============================================

export interface AdminLayoutProps {
  /** Route content — page components render here. */
  children: ReactNode;
  /** Display name for the topbar user menu. Defaults to a placeholder
   *  until Priya's useAdminAuth hook is wired in by the page-level route. */
  adminName?: string;
  /** Page title shown in the topbar; falls back to "Admin". */
  pageTitle?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function AdminLayout({ children, adminName = 'Admin', pageTitle = 'Admin' }: AdminLayoutProps) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const menuTriggerRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  const { logout } = useAdminAuth();

  async function handleLogout() {
    setIsLoggingOut(true);
    try {
      await logout();
      navigate('/admin/login');
    } finally {
      setIsLoggingOut(false);
    }
  }

  // Flush any audit log entries queued while the backend was unreachable in a
  // previous session (Kavya, cycle 4 — src/admin/utils/auditLogger.ts).
  useEffect(() => {
    initAuditLogger();
  }, []);

  // Keyboard support: Escape closes the mobile nav from anywhere while open.
  useEffect(() => {
    if (!mobileNavOpen) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMobileNavOpen(false);
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [mobileNavOpen]);

  // Focus management: move focus into the nav on open, and return it to the
  // trigger button on close so keyboard users never lose their place.
  useEffect(() => {
    if (mobileNavOpen) {
      closeButtonRef.current?.focus();
    } else {
      menuTriggerRef.current?.focus();
    }
  }, [mobileNavOpen]);

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {/* Mobile overlay — a real button so it is focusable and operable via
          Enter/Space, not just pointer clicks (WCAG 2.1.1 Keyboard). */}
      {mobileNavOpen && (
        <button
          type="button"
          className="fixed inset-0 z-40 cursor-default bg-black/40 lg:hidden"
          onClick={() => setMobileNavOpen(false)}
          aria-label="Close navigation"
        />
      )}

      {/* Sidebar */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-transform duration-200 ease-in-out lg:static lg:translate-x-0',
          mobileNavOpen ? 'translate-x-0' : '-translate-x-full',
        )}
        aria-label="Admin navigation"
      >
        <div className="flex h-16 shrink-0 items-center justify-between gap-2 border-b border-sidebar-border px-5">
          <span className="text-lg font-semibold tracking-tight text-sidebar-foreground">
            Influora <span className="text-sidebar-primary">Admin</span>
          </span>
          <button
            ref={closeButtonRef}
            type="button"
            className="rounded-md p-1.5 text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground lg:hidden"
            onClick={() => setMobileNavOpen(false)}
            aria-label="Close navigation"
          >
            <X className="size-5" aria-hidden="true" />
          </button>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4" aria-label="Sections">
          {NAV_ITEMS.map(({ label, to, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/admin'}
              onClick={() => setMobileNavOpen(false)}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-sidebar-primary text-sidebar-primary-foreground shadow-sm'
                    : 'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                )
              }
            >
              <Icon className="size-4.5 shrink-0" aria-hidden="true" />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="border-t border-sidebar-border px-5 py-4 text-xs text-sidebar-foreground/60">
          Phase 1 — Admin Panel
        </div>
      </aside>

      {/* Main column */}
      <div className="flex min-h-screen flex-1 flex-col lg:pl-0">
        {/* Topbar */}
        <header className="sticky top-0 z-30 flex h-16 shrink-0 items-center justify-between gap-4 border-b border-border bg-card px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <button
              ref={menuTriggerRef}
              type="button"
              className="rounded-md p-2 text-foreground/70 hover:bg-accent hover:text-accent-foreground lg:hidden"
              onClick={() => setMobileNavOpen(true)}
              aria-label="Open navigation"
            >
              <Menu className="size-5" aria-hidden="true" />
            </button>
            <h1 className="text-base font-semibold text-foreground sm:text-lg">{pageTitle}</h1>
          </div>

          <div className="flex items-center gap-2 sm:gap-3">
            {/* No notifications source is wired up yet, so this stays a
                disabled placeholder rather than showing a fabricated unread
                indicator (TECH-STACK.md rule 7 — no fabricated contracts). */}
            <button
              type="button"
              disabled
              className="relative rounded-full p-2 text-foreground/40 disabled:cursor-not-allowed"
              aria-label="Notifications (not available yet)"
            >
              <Bell className="size-5" aria-hidden="true" />
            </button>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex items-center gap-2 rounded-full py-1 pl-1 pr-2.5 text-sm font-medium text-foreground hover:bg-accent"
                  aria-label={`Open user menu for ${adminName}`}
                >
                  <span className="flex size-7 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
                    {adminName.slice(0, 1).toUpperCase()}
                  </span>
                  <span className="hidden sm:inline">{adminName}</span>
                  <ChevronDown className="size-3.5 text-foreground/50" aria-hidden="true" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>{adminName}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  disabled={isLoggingOut}
                  onSelect={(event) => {
                    event.preventDefault();
                    void handleLogout();
                  }}
                >
                  <LogOut className="size-4" aria-hidden="true" />
                  {isLoggingOut ? 'Logging out…' : 'Log out'}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1 px-4 py-6 sm:px-6 lg:px-8">{children}</main>
      </div>
    </div>
  );
}
