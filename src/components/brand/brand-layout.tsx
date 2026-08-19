import * as React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Home,
  Megaphone,
  Users2,
  Wallet,
  Settings,
  Bell,
  Search,
  ChevronDown,
  LogOut,
  Menu,
  X,
  HelpCircle,
  Plus,
  MessageCircle,
  MessageSquare,
  KanbanSquare,
  FileText,
  BarChart3,
  Star,
  AlertTriangle,
  Sparkles,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { api } from '@/lib/api';
import { useAuthStore, useUIStore } from '@/lib/store';
import { useNotifications } from '@/hooks/useNotifications';
import { Button } from '@/components/ui/button';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
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
import { CommandBar } from './command-bar';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ScrollArea } from '@/components/ui/scroll-area';
import { IconBadge } from '@/components/shared/icon-badge';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { WorkspaceVerificationBanner } from '@/components/brand/WorkspaceVerificationBanner';
import { getBrandNavIconVariant } from '@/lib/icon-theme';

interface BrandNavItem {
  label: string;
  href: string;
  icon: typeof Home;
}

interface BrandNavGroup {
  label: string;
  items: BrandNavItem[];
}

/**
 * 12-item grouped navigation (was a 6-item flat list that left 6 fully-built,
 * confirmed-working pages orphaned — Messages/Pipeline/Contracts/Analytics/
 * Reviews/Disputes were only reachable by direct URL). Settings stays in the
 * avatar menu, not here.
 *
 * MAIN mirrors the day-to-day brand workflow; MANAGE holds the ops/oversight
 * surfaces.
 *
 * D-8 (BrandF.md §24): "Deals" points at `/brand/chat` (`BrandChatPage`), not
 * `/brand/deals` (`DealRoomDashboard`) — this comment previously claimed the
 * opposite. `DealRoomDashboard` only has overview/messages/history tabs;
 * `BrandChatPage` is the one with the shipment control, contract tab,
 * deliverables tab, and payments tab — sending brands to the thinner page
 * meant every deal's shipment control was one click further away than it
 * needed to be, reachable only via ⌘K or a deep link. `/brand/messages`
 * still covers pure messaging with no deal-room chrome.
 */
const navGroups: BrandNavGroup[] = [
  {
    label: 'Main',
    items: [
      { label: 'Home', href: '/brand/dashboard', icon: Home },
      { label: 'Meera', href: '/brand/meera', icon: Sparkles },
      { label: 'Campaigns', href: '/brand/campaigns', icon: Megaphone },
      { label: 'Creators', href: '/brand/discover', icon: Users2 },
      { label: 'Deals', href: '/brand/chat', icon: MessageCircle },
      { label: 'Messages', href: '/brand/messages', icon: MessageSquare },
      { label: 'Wallet', href: '/brand/wallet', icon: Wallet },
    ],
  },
  {
    label: 'Manage',
    items: [
      { label: 'Pipeline', href: '/brand/pipeline', icon: KanbanSquare },
      { label: 'Contracts', href: '/brand/contracts', icon: FileText },
      { label: 'Analytics', href: '/brand/analytics', icon: BarChart3 },
      { label: 'Reviews', href: '/brand/reviews', icon: Star },
      { label: 'Disputes', href: '/brand/disputes', icon: AlertTriangle },
      // Persistent, not just a first-run affordance: a brand who dismissed the checklist, or who
      // comes back confused three weeks later, needs a standing way back to the flow.
      { label: 'How it works', href: '/brand/how-it-works', icon: HelpCircle },
    ],
  },
];

interface BrandLayoutProps {
  children: React.ReactNode;
}

/**
 * Format relative time (mirrors NotificationBell's helper — no shared util
 * module exists yet, so this stays a small local copy).
 */
function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;
  return date.toLocaleDateString();
}

export function BrandLayout({ children }: BrandLayoutProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  // F-0246 — nothing in the brand login flow writes `useAuthStore().user` (only the creator
  // flow calls `login()`/`setUser()`), so `user` above is always `null` in a live brand
  // session. Rather than fall back to a fabricated placeholder ("Brand Account" /
  // "brand@company.com" — a plausible-looking identity belonging to nobody), fall back to the
  // real workspace record. Same queryKey/staleTime as useWorkspaceVerification.ts's `me` query
  // (mounted app-wide via WorkspaceVerificationBanner below) so this shares that cache entry
  // instead of firing a second `GET /workspaces/me`.
  const workspaceMe = useQuery({
    queryKey: ['workspace', 'me'],
    queryFn: () => api.workspaces.getMe(),
    staleTime: 5 * 60 * 1000,
  });
  const accountDisplayName = user?.displayName || workspaceMe.data?.name;
  const accountDisplayEmail = user?.email || workspaceMe.data?.email || undefined;
  const { notifications, unreadCount, loading, error, refresh: refreshNotifications, markRead, markAllRead } = useNotifications('brand');
  const { mobileMenuOpen, toggleMobileMenu, setMobileMenuOpen, closeMobileMenu } = useUIStore();
  const [showLogoutDialog, setShowLogoutDialog] = React.useState(false);
  const [commandBarOpen, setCommandBarOpen] = React.useState(false);
  const [notificationsOpen, setNotificationsOpen] = React.useState(false);

  // M-B (BrandF.md §78): refetch when the bell popover opens, not just once
  // at page-load — the hook's own interval poll is the floor, this is the
  // "I just clicked the bell, show me what's current" path.
  React.useEffect(() => {
    if (notificationsOpen) refreshNotifications();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notificationsOpen]);

  const getInitials = (name?: string | null, fallback = 'IN') => {
    if (!name?.trim()) return fallback;
    return name
      .trim()
      .split(/\s+/)
      .map((word) => word[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  };

  const pathname = location.pathname;

  const isActive = (href: string) => {
    if (href === '/brand/dashboard') return pathname === '/brand/dashboard';
    // Creator profile pages (/brand/creators/:id) belong to the Creators nav item
    if (href === '/brand/discover') {
      return pathname.startsWith('/brand/discover') || pathname.startsWith('/brand/creators');
    }
    // D-8 follow-up (Priya review): "Deals" now routes to /brand/chat, but
    // /brand/chat, /brand/deals AND /brand/deals/:id are all still live entry
    // points into a deal room (Pipeline still navigates straight into
    // /brand/deals/:id — see brand-pipeline.tsx). Without this, opening a deal
    // from Pipeline landed on a page where no sidebar item lit up at all.
    if (href === '/brand/chat') {
      return pathname.startsWith('/brand/chat') || pathname.startsWith('/brand/deals');
    }
    // Contracts and Messages are separate nav items, each with their own real
    // page, not sub-surfaces of Deals.
    return pathname.startsWith(href);
  };

  const handleLogout = () => {
    logout();
    localStorage.removeItem('brand_token');
    navigate('/brand/login');
  };

  const handleNavigate = (href: string) => {
    navigate(href);
    closeMobileMenu();
  };

  // Keyboard shortcut for command bar
  React.useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setCommandBarOpen(true);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex min-h-screen bg-background">
        {/* Desktop Sidebar -- always expanded, clean flat list */}
        <aside className="fixed inset-y-0 left-0 z-50 hidden w-60 flex-col border-r border-border bg-sidebar lg:flex">
          {/* Logo */}
          <div className="flex h-14 items-center gap-2.5 border-b border-sidebar-border px-5">
            <button
              onClick={() => navigate('/brand/dashboard')}
              className="hover:opacity-80 transition-opacity"
            >
              <InfluoraLogo size="md" />
            </button>
          </div>

          {/* Quick create */}
          <div className="px-3 pt-4 pb-2">
            <Button
              onClick={() => navigate('/brand/campaigns/new')}
              className="w-full justify-start gap-2 h-9 text-sm"
              size="sm"
            >
              <Plus className="h-4 w-4 shrink-0" strokeWidth={2.35} />
              New Campaign
            </Button>
          </div>

          {/* Nav items — grouped (Main / Manage), scrollable so 12 items never
              get clipped on shorter viewports */}
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
                              variant={active ? 'primary' : getBrandNavIconVariant(item.href)}
                              size="sm"
                              active={active}
                              rounded="lg"
                            />
                            <span>{item.label}</span>
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
                <button className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 hover:bg-sidebar-accent transition-colors">
                  <Avatar className="h-7 w-7">
                    <AvatarImage src="" />
                    <AvatarFallback className="text-xs bg-primary/10 text-primary">
                      {getInitials(accountDisplayName)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 text-left min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">
                      {accountDisplayName || 'Workspace'}
                    </p>
                  </div>
                  <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="top" className="w-52">
                <DropdownMenuLabel className="font-medium text-xs text-muted-foreground">
                  {accountDisplayEmail || 'No email on file'}
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => handleNavigate('/brand/settings')}>
                  <Settings className="mr-2 h-4 w-4" />
                  Settings
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => handleNavigate('/brand/help')}>
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
          <header className="sticky top-0 z-40 flex h-14 items-center justify-between border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 px-4 sm:px-6">
            {/* Mobile: hamburger + logo */}
            <div className="flex items-center gap-3 lg:hidden">
              <button
                onClick={toggleMobileMenu}
                aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
                className="p-1.5 hover:bg-accent rounded-lg transition-colors"
              >
                {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
              </button>
              <button
                onClick={() => navigate('/brand/dashboard')}
                className="flex items-center gap-2"
              >
                <div className="flex h-7 w-7 items-center justify-center rounded-md bg-primary">
                  <span className="text-[10px] font-bold text-primary-foreground">IN</span>
                </div>
                <span className="font-semibold text-sm text-foreground">Influora</span>
              </button>
            </div>

            {/* Desktop: search bar */}
            <div className="hidden lg:block">
              <button
                onClick={() => setCommandBarOpen(true)}
                className="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground bg-muted/60 rounded-lg hover:bg-muted transition-colors min-w-[240px]"
              >
                <Search className="h-4 w-4" />
                <span className="flex-1 text-left">Search campaigns, creators...</span>
                <kbd className="hidden md:inline-flex h-5 items-center gap-0.5 rounded border border-border bg-background px-1.5 text-[10px] font-medium text-muted-foreground">
                  <span className="text-xs">Cmd</span>K
                </kbd>
              </button>
            </div>

            {/* Right: notifications + mobile search */}
            <div className="flex items-center gap-2">
              <button
                onClick={() => setCommandBarOpen(true)}
                aria-label="Search"
                className="lg:hidden p-1.5 hover:bg-accent rounded-lg transition-colors"
              >
                <Search className="h-5 w-5 text-muted-foreground" />
              </button>
              <Popover open={notificationsOpen} onOpenChange={setNotificationsOpen}>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    aria-label={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
                    className="relative p-1.5 hover:bg-accent rounded-lg transition-colors"
                  >
                    <Bell className="h-5 w-5 text-muted-foreground" />
                    {unreadCount > 0 && (
                      <span className="absolute -top-0.5 -right-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-destructive text-[10px] font-bold text-destructive-foreground">
                        {unreadCount > 9 ? '9+' : unreadCount}
                      </span>
                    )}
                  </button>
                </PopoverTrigger>
                <PopoverContent align="end" className="w-80 p-0">
                  <div className="flex items-center justify-between border-b border-border px-4 py-3">
                    <p className="text-sm font-semibold">Notifications</p>
                    {unreadCount > 0 && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-auto px-2 text-xs text-muted-foreground"
                        onClick={() => markAllRead()}
                      >
                        Mark all read
                      </Button>
                    )}
                  </div>
                  <ScrollArea className="h-80">
                    {loading ? (
                      <div className="flex items-center justify-center py-8">
                        <div className="h-5 w-5 animate-spin rounded-full border-2 border-border border-t-primary" />
                      </div>
                    ) : error && notifications.length === 0 ? (
                      <div className="flex flex-col items-center justify-center py-8 text-center px-4">
                        <Bell className="mb-2 h-8 w-8 text-muted-foreground/50" />
                        <p className="text-sm text-muted-foreground">Couldn’t load notifications</p>
                        <p className="mt-0.5 text-xs text-muted-foreground/80">Please try again shortly.</p>
                      </div>
                    ) : notifications.length === 0 ? (
                      <div className="flex flex-col items-center justify-center py-8 text-center px-4">
                        <Bell className="mb-2 h-8 w-8 text-muted-foreground/50" />
                        <p className="text-sm text-muted-foreground">No notifications yet</p>
                      </div>
                    ) : (
                      <div className="p-1">
                        {notifications.map((notification) => (
                          <div
                            key={notification.id}
                            onClick={() => {
                              // Priya review finding (N-1/M-B follow-up): a full `window.location.href`
                              // reload here would cancel the markRead POST in flight and blow away all
                              // SPA state — use router navigation instead, same as every other in-app link.
                              if (!notification.read) markRead(notification.id);
                              setNotificationsOpen(false);
                              if (notification.link) navigate(notification.link);
                            }}
                            className={cn(
                              'flex gap-3 p-3 hover:bg-accent cursor-pointer rounded-lg transition-colors',
                              !notification.read && 'bg-primary/5',
                            )}
                          >
                            {!notification.read && (
                              <span className="bg-primary h-2 w-2 rounded-full mt-1.5 shrink-0" />
                            )}
                            <div className={cn('flex-1 min-w-0', notification.read && 'ml-5')}>
                              <p className="text-sm font-medium">{notification.title}</p>
                              {notification.body && (
                                <p className="text-xs text-muted-foreground line-clamp-2">{notification.body}</p>
                              )}
                              <p className="text-xs text-muted-foreground mt-1">
                                {formatRelativeTime(notification.createdAt)}
                              </p>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </ScrollArea>
                  <div className="border-t border-border p-2">
                    <Button
                      variant="ghost"
                      size="sm"
                      className="w-full text-xs"
                      onClick={() => {
                        setNotificationsOpen(false);
                        navigate('/brand/notifications');
                      }}
                    >
                      View all notifications
                    </Button>
                  </div>
                </PopoverContent>
              </Popover>
              {/* Mobile user menu */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    aria-label="User menu"
                    className="lg:hidden p-0.5 rounded-full hover:ring-2 hover:ring-ring transition-all"
                  >
                    <Avatar className="h-7 w-7">
                      <AvatarFallback className="text-xs bg-primary/10 text-primary">
                      {getInitials(accountDisplayName)}
                    </AvatarFallback>
                    </Avatar>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48">
                  <DropdownMenuLabel className="text-xs text-muted-foreground">
                    {accountDisplayEmail || 'No email on file'}
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => handleNavigate('/brand/settings')}>
                    <Settings className="mr-2 h-4 w-4" /> Settings
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => handleNavigate('/brand/help')}>
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
              <div className="px-3 pt-3 pb-2">
                <Button
                  onClick={() => { handleNavigate('/brand/campaigns/new'); }}
                  className="w-full justify-start gap-2 h-9 text-sm"
                  size="sm"
                >
                  <Plus className="h-4 w-4" />
                  New Campaign
                </Button>
              </div>
              <nav className="max-h-[calc(100vh-9rem)] overflow-y-auto px-3 py-1">
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
                              variant={active ? 'primary' : getBrandNavIconVariant(item.href)}
                              size="sm"
                              active={active}
                              rounded="lg"
                            />
                            <span>{item.label}</span>
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
          <main className="flex-1">
            <WorkspaceVerificationBanner />
            {children}
          </main>
        </div>

        {/* Command bar */}
        <CommandBar open={commandBarOpen} onOpenChange={setCommandBarOpen} />

        {/* Logout confirmation */}
        <AlertDialog open={showLogoutDialog} onOpenChange={setShowLogoutDialog}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Log out?</AlertDialogTitle>
              <AlertDialogDescription>
                You will need to sign in again to access your workspace.
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
