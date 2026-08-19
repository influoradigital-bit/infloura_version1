import * as React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/store';
import { clearCreatorSession } from '@/lib/auth-session';
import { api, isApiLive } from '@/lib/api';
import { useCreatorIdentity } from '@/hooks/use-creator-identity';
import { useCreatorUnreadCount } from '@/hooks/use-creator-unread-count';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { IconBadge } from '@/components/shared/icon-badge';
import { getCreatorNavIconVariant } from '@/lib/icon-theme';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  Home,
  Briefcase,
  Megaphone,
  Sparkles,
  BarChart3,
  Wallet,
  User,
  Bell,
  Settings,
  LogOut,
  Menu,
  X,
  Search,
  HelpCircle,
  ChevronDown,
  Globe,
  Star,
  AlertTriangle,
  Ticket,
  TrendingUp,
  ClipboardList,
} from 'lucide-react';
import { InfluoraLogo } from '@/components/shared/influora-logo';

interface CreatorLayoutProps {
  children: React.ReactNode;
}

interface CreatorNavItem {
  label: string;
  href: string;
  icon: typeof Home;
}

interface CreatorNavGroup {
  label: string;
  items: CreatorNavItem[];
}

/**
 * Grouped navigation (mirrors brand-layout's Main/Manage split) — was a flat
 * 6-item list that left 4 fully-built creator pages orphaned — Reviews,
 * Disputes, Coupons, and Affiliate were only reachable by direct URL.
 * Inbox + Active + Deal Room stay collapsed into one Deals page (filtered by
 * status). Profile, Public Page, and Settings live in the avatar menu, not
 * here.
 */
const navGroups: CreatorNavGroup[] = [
  {
    label: 'Main',
    items: [
      { label: 'Home', href: '/creator/dashboard', icon: Home },
      { label: 'Deals', href: '/creator/deals', icon: Briefcase },
      { label: 'Campaigns', href: '/creator/campaigns', icon: Megaphone },
      { label: 'Applications', href: '/creator/applications', icon: ClipboardList },
      { label: 'Co-pilot', href: '/creator/copilot', icon: Sparkles },
      { label: 'Analytics', href: '/creator/analytics', icon: BarChart3 },
      { label: 'Wallet', href: '/creator/wallet', icon: Wallet },
    ],
  },
  {
    label: 'Manage',
    items: [
      { label: 'Reviews', href: '/creator/reviews', icon: Star },
      { label: 'Disputes', href: '/creator/disputes', icon: AlertTriangle },
      { label: 'Coupons', href: '/creator/coupons', icon: Ticket },
      { label: 'Affiliate', href: '/creator/affiliate', icon: TrendingUp },
    ],
  },
];

export function CreatorLayout({ children }: CreatorLayoutProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { logout } = useAuthStore();
  const [showLogoutDialog, setShowLogoutDialog] = React.useState(false);

  // CR-06 — real identity or nothing. See `useCreatorIdentity` for why the
  // 'Creator Account' / '@priya_sharma' / 'IN' fallbacks were removed rather
  // than repointed.
  const { identity } = useCreatorIdentity();

  /**
   * CR-06 — initials, or `null` when the name is unknown.
   *
   * The old signature was `getInitials(name, fallback = 'IN')`, which is why
   * the live sidebar showed "IN" for every creator. A caller that doesn't know
   * the name gets nothing back and renders a skeleton instead.
   */
  const getInitials = (name?: string | null): string | null => {
    if (!name?.trim()) return null;
    return name
      .trim()
      .split(/\s+/)
      .map((word) => word[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  };
  const initials = getInitials(identity.displayName);
  const [mobileMenuOpen, setMobileMenuOpen] = React.useState(false);
  // CR-16 — was `React.useState(3)`: a hardcoded literal with no setter and no
  // data source, rendering "Deals 3" on an account with 2 deals and 0 unread.
  // Keyed on the pathname so the badge settles once a deal room marks its
  // messages read.
  const unreadCount = useCreatorUnreadCount(location.pathname);

  const pathname = location.pathname;

  const isActive = (href: string) => {
    // Deals nav covers /deals, the legacy /inbox + /active + /chat surfaces
    if (href === '/creator/deals') {
      return (
        pathname.startsWith('/creator/deals') ||
        pathname.startsWith('/creator/inbox') ||
        pathname.startsWith('/creator/active') ||
        pathname.startsWith('/creator/chat')
      );
    }
    return pathname.startsWith(href);
  };

  const handleLogout = async () => {
    // CR-90: this sidebar/mobile-dropdown path is the primary, easiest-to-reach way a
    // creator logs out, and it used to only clear client-side state — the server never
    // saw the logout, so the HttpOnly refresh cookie / refresh token stayed live and a
    // new access token could be silently re-issued via /auth/refresh. Mirrors the
    // Settings-page logout's already-reviewed pattern (see api.ts's `auth.logout` doc,
    // CR-91): call the server first so it can still resolve the principal from the
    // still-present token, then always clear locally in the finally-equivalent below
    // even if the server call fails — a failed request must never strand the creator
    // in a logged-in-looking UI.
    try {
      if (isApiLive()) {
        await api.auth.logout('creator');
      }
    } catch (err) {
      console.error('Logout request failed', err);
    }
    logout();
    // CR-06 — clears the identity keys login now writes, not just the token.
    // Leaving creator_email / creator_display_name behind would let the next
    // person to open this browser see the previous creator's name in the shell
    // before /me/creator-profile resolves.
    clearCreatorSession();
    navigate('/creator/login');
  };

  const handleNavigate = (href: string) => {
    navigate(href);
    setMobileMenuOpen(false);
  };

  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex min-h-screen bg-background">
        {/* Desktop Sidebar */}
        <aside className="fixed inset-y-0 left-0 z-50 hidden w-60 flex-col border-r border-border bg-sidebar lg:flex">
          {/* Logo — F-0275: a logo is the universal "take me home" affordance, and the nav's
              own "Home" item (above) already points at /creator/dashboard. Was
              /creator/deals, disagreeing with the nav it sits right next to. */}
          <div className="flex h-14 items-center gap-2.5 border-b border-sidebar-border px-5">
            <button
              onClick={() => navigate('/creator/dashboard')}
              className="flex items-center gap-2.5 hover:opacity-80 transition-opacity"
            >
              <InfluoraLogo size="md" showName={true} />
            </button>
          </div>

          {/* Nav items — grouped (Main / Manage), mirrors brand-layout */}
          <nav role="navigation" className="flex-1 overflow-y-auto px-3 py-2">
            {navGroups.map((group) => (
              <div key={group.label} className="mb-3 last:mb-0">
                <p className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground/70">
                  {group.label}
                </p>
                <div className="flex flex-col gap-0.5">
                  {group.items.map((item) => {
                    const Icon = item.icon;
                    const active = isActive(item.href);
                    return (
                      <Tooltip key={item.href}>
                        <TooltipTrigger asChild>
                          <button
                            onClick={() => handleNavigate(item.href)}
                            className={cn(
                              'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                              active
                                ? 'bg-primary/10 text-primary'
                                : 'text-muted-foreground hover:bg-sidebar-accent hover:text-foreground',
                            )}
                          >
                            <IconBadge
                              icon={Icon}
                              variant={active ? 'primary' : getCreatorNavIconVariant(item.href)}
                              size="sm"
                              active={active}
                              rounded="lg"
                            />
                            <span>{item.label}</span>
                            {item.label === 'Deals' && unreadCount > 0 && (
                              <Badge variant="secondary" className="ml-auto h-5 px-1.5 text-xs">
                                {unreadCount}
                              </Badge>
                            )}
                          </button>
                        </TooltipTrigger>
                        <TooltipContent side="right" className="lg:hidden">
                          {item.label}
                        </TooltipContent>
                      </Tooltip>
                    );
                  })}
                </div>
              </div>
            ))}
          </nav>

          {/* Sidebar bottom: user + logout */}
          <div className="border-t border-sidebar-border p-3">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                {/* F-0170 — this button's accessible name was empty while identity.displayName
                    was still null (the visible content was a bare skeleton span, no text) —
                    WCAG 2.1 AA 4.1.2. Unlike F-0169's mobile avatar trigger, this one already has
                    real visible label text once resolved (the displayName paragraph below), so a
                    plain aria-label would override it and violate WCAG 2.1 AA 2.5.3 Label in Name
                    (a voice-control user saying the name they see wouldn't match the accessible
                    name). An unconditional sr-only span composes into the accessible name instead
                    of replacing it — always non-empty, and the visible name stays included once
                    resolved. Wording deliberately differs from the mobile avatar trigger's
                    "Account menu" (F-0169) — jsdom renders both hidden lg:flex / lg:hidden
                    siblings at once with no CSS breakpoint evaluation, so identical accessible
                    names would collide in test tooling even though a real browser only ever
                    shows one at a time. */}
                <button
                  type="button"
                  data-testid="creator-sidebar-account-trigger"
                  className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 hover:bg-sidebar-accent transition-colors"
                >
                  <span className="sr-only">Open account menu</span>
                  {/* F-0179 — the initials text ('PS') and avatar image previously leaked into
                      the composed accessible name alongside the sr-only label and the real
                      display name (e.g. "Open account menu PS Priya Sharma"), announcing
                      redundant letters. Purely decorative once the sr-only label + visible
                      name already convey who/what this is — aria-hidden removes it from the
                      accessible-name computation without touching the visible avatar. */}
                  <Avatar className="h-7 w-7" aria-hidden="true">
                    <AvatarImage src={identity.avatarUrl ?? undefined} />
                    {/* CR-06 — no 'IN' fallback. An unknown creator gets a blank
                        tinted circle, not another user's initials. */}
                    <AvatarFallback className="text-xs bg-primary/10 text-primary">
                      {initials}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 text-left min-w-0">
                    {/* CR-06 — a skeleton bar while the name is unknown. The old
                        `|| 'Creator Account'` asserted an identity the app did
                        not have. */}
                    {identity.displayName ? (
                      <p className="text-sm font-medium text-foreground truncate">
                        {identity.displayName}
                      </p>
                    ) : (
                      <span className="block h-3.5 w-24 rounded bg-muted animate-pulse" />
                    )}
                  </div>
                  <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="top" className="w-52">
                {/* CR-06 — the '@priya_sharma' literal is deleted, not repointed.
                    Shipping one creator's handle as another creator's fallback is
                    an identity-leak pattern. Prefers the real @handle from
                    /me/creator-profile, falls back to the session email, and
                    renders a skeleton when it knows neither. */}
                <DropdownMenuLabel className="font-medium text-xs text-muted-foreground">
                  {identity.handle ?? identity.email ?? (
                    <span className="block h-3 w-28 rounded bg-muted animate-pulse" />
                  )}
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => handleNavigate('/creator/profile')}>
                  <User className="mr-2 h-4 w-4" />
                  Profile
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => handleNavigate('/creator/portfolio')}>
                  <Globe className="mr-2 h-4 w-4" />
                  Public Page
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => handleNavigate('/creator/settings')}>
                  <Settings className="mr-2 h-4 w-4" />
                  Settings
                </DropdownMenuItem>
                {/* CR-88/89 red-team fix (Priya) — 'https://help.influora.com' does not
                    resolve to anything real; the brand side already migrated off this exact
                    dead external host (see wiki/build/ananya-nav-alignment-2026-07-23.md).
                    Routed to the real in-app /support page instead. */}
                <DropdownMenuItem onClick={() => handleNavigate('/support')}>
                  <HelpCircle className="mr-2 h-4 w-4" />
                  Help & Support
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => setShowLogoutDialog(true)} className="text-destructive-foreground focus:text-destructive-foreground">
                  <LogOut className="mr-2 h-4 w-4" />
                  Log out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </aside>

        {/* Main area */}
        <div className="flex flex-1 flex-col lg:ml-60">
          {/* Top header */}
          {/* CR-17 — height comes from the shared --app-header-h token (globals.css),
              which the deal room's `h-[calc(100vh-var(--app-header-h))]` also reads.
              Visually identical to the previous `h-14`; the point is that the two
              can no longer disagree. */}
          <header className="sticky top-0 z-40 flex h-[var(--app-header-h)] items-center justify-between border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 px-4 sm:px-6">
            {/* Mobile: hamburger + logo */}
            <div className="flex items-center gap-3 lg:hidden">
              {/* F-0167 — this button had no accessible name (no aria-label, no text child,
                  icon rendered aria-hidden) so a screen reader announced it as an unnamed
                  "button" — WCAG 2.1 AA 4.1.2. aria-label + aria-expanded now reflect the
                  actual open/closed state, matching the icon that's already state-driven. */}
              <button
                type="button"
                onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
                className="p-1.5 hover:bg-accent rounded-lg transition-colors"
                aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
                aria-expanded={mobileMenuOpen}
              >
                {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
              </button>
              {/* F-0275 — same "take me home" fix as the desktop logo above. */}
              <button
                onClick={() => navigate('/creator/dashboard')}
                className="flex items-center gap-2"
              >
                <InfluoraLogo size="sm" showName={true} />
              </button>
            </div>

            {/* Desktop: search — CR-122. Was a plain non-interactive <div> styled to look like a
                real input (no onClick, no onKeyDown, no routing, no backend search endpoint) —
                a real search UI is CR-70/71-scale work this ticket didn't ask for, so the same
                minimal fix as CR-124's mobile icon: route to the one page with a genuinely
                working "Search brand or campaign…" box (creator-deals.tsx). A real button, not
                a div, so it's keyboard-reachable. */}
            <button
              type="button"
              onClick={() => handleNavigate('/creator/deals')}
              className="hidden lg:flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground bg-muted/60 hover:bg-muted rounded-lg min-w-[240px] transition-colors"
            >
              <Search className="h-4 w-4" />
              <span className="flex-1 text-left">Search collaborations...</span>
            </button>

            {/* Right: notifications */}
            <div className="flex items-center gap-2">
              {/* CR-124 — was a dead click (no onClick at all). The honest minimal fix is
                  sending the tap to the one page that already has a working "Search brand or
                  campaign…" box (creator-deals.tsx), matching this icon's own "Search
                  collaborations" label. */}
              <button
                className="lg:hidden p-1.5 hover:bg-accent rounded-lg transition-colors"
                onClick={() => handleNavigate('/creator/deals')}
                aria-label="Search collaborations"
              >
                <Search className="h-5 w-5 text-muted-foreground" />
              </button>
              {/* CR-123 — the unread badge was already real (useCreatorUnreadCount); the button
                  itself had no onClick. Routes to /creator/notifications, a real page backed by
                  the same GET/POST /notifications endpoints the badge's sibling deals-unread
                  count already proves work — mirrors brand-notifications.tsx, no new backend. */}
              <button
                className="relative p-1.5 hover:bg-accent rounded-lg transition-colors"
                onClick={() => handleNavigate('/creator/notifications')}
                aria-label="Notifications"
              >
                <Bell className="h-5 w-5 text-muted-foreground" />
                {unreadCount > 0 && (
                  <span className="absolute -top-0.5 -right-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-destructive text-[10px] font-bold text-destructive-foreground">
                    {unreadCount > 9 ? '9+' : unreadCount}
                  </span>
                )}
              </button>
              {/* Mobile user menu */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  {/* F-0169 — icon/avatar-only, no text child; without an explicit label the
                      accessible name was empty (or, worse, a bare initials string like "PS"
                      that reads as a name, not a control) — WCAG 2.1 AA 4.1.2. aria-label is a
                      fixed string, not derived from identity, so it's present immediately, not
                      only once the avatar/initials resolve (contrast F-0170, which is exactly
                      that dependency on the desktop trigger). */}
                  <button
                    type="button"
                    className="lg:hidden p-0.5 rounded-full hover:ring-2 hover:ring-ring transition-all"
                    aria-label="Account menu"
                  >
                    <Avatar className="h-7 w-7">
                      <AvatarImage src={identity.avatarUrl ?? undefined} />
                      {/* CR-06 — see the desktop trigger above; no 'IN' fallback. */}
                      <AvatarFallback className="text-xs bg-primary/10 text-primary">
                        {initials}
                      </AvatarFallback>
                    </Avatar>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48">
                  {/* CR-06 — second copy of the same leak; same fix. */}
                  <DropdownMenuLabel className="text-xs text-muted-foreground">
                    {identity.handle ?? identity.email ?? (
                      <span className="block h-3 w-24 rounded bg-muted animate-pulse" />
                    )}
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => handleNavigate('/creator/profile')}>
                    <User className="mr-2 h-4 w-4" /> Profile
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => handleNavigate('/creator/portfolio')}>
                    <Globe className="mr-2 h-4 w-4" /> Public Page
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => handleNavigate('/creator/settings')}>
                    <Settings className="mr-2 h-4 w-4" /> Settings
                  </DropdownMenuItem>
                  {/* CR-89 — the desktop sidebar has this entry (above); the mobile dropdown
                      omitted it entirely, so mobile creators had no in-app path to help at all.
                      Routed to /support, not the dead 'help.influora.com' host — see the
                      CR-88/89 red-team note on the desktop item above. */}
                  <DropdownMenuItem onClick={() => handleNavigate('/support')}>
                    <HelpCircle className="mr-2 h-4 w-4" /> Help & Support
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setShowLogoutDialog(true)} className="text-destructive-foreground focus:text-destructive-foreground">
                    <LogOut className="mr-2 h-4 w-4" /> Log out
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </header>

          {/* Mobile nav sheet */}
          <Sheet open={mobileMenuOpen} onOpenChange={setMobileMenuOpen}>
            <SheetContent side="left" className="w-64 p-0">
              <SheetHeader className="px-5 pt-5 pb-3 border-b border-border">
                <SheetTitle className="text-left text-base">Navigation</SheetTitle>
              </SheetHeader>
              <nav className="max-h-[calc(100vh-6rem)] overflow-y-auto px-3 py-1">
                {navGroups.map((group) => (
                  <div key={group.label} className="mb-3 last:mb-0">
                    <p className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground/70">
                      {group.label}
                    </p>
                    <div className="flex flex-col gap-0.5">
                      {group.items.map((item) => {
                        const Icon = item.icon;
                        const active = isActive(item.href);
                        return (
                          <button
                            key={item.href}
                            onClick={() => handleNavigate(item.href)}
                            className={cn(
                              'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                              active
                                ? 'bg-primary/10 text-primary'
                                : 'text-muted-foreground hover:bg-accent hover:text-foreground',
                            )}
                          >
                            <IconBadge
                              icon={Icon}
                              variant={active ? 'primary' : getCreatorNavIconVariant(item.href)}
                              size="sm"
                              active={active}
                              rounded="lg"
                            />
                            <span>{item.label}</span>
                            {item.label === 'Deals' && unreadCount > 0 && (
                              <Badge variant="secondary" className="ml-auto h-5 px-1.5 text-xs">
                                {unreadCount}
                              </Badge>
                            )}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </nav>
            </SheetContent>
          </Sheet>

          {/* Page content */}
          <main className="flex-1">{children}</main>
        </div>

        {/* Logout confirmation */}
        <AlertDialog open={showLogoutDialog} onOpenChange={setShowLogoutDialog}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Log out?</AlertDialogTitle>
              <AlertDialogDescription>
                You will need to sign in again to access your account.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={handleLogout} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
                Log out
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </TooltipProvider>
  );
}
