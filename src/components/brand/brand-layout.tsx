import * as React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
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
  User,
  Sparkles,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { useAuthStore, useNotificationStore, useUIStore } from '@/lib/store';
import { Button } from '@/components/ui/button';
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
 * surfaces. "Deals" now points at `/brand/deals` (`DealRoomDashboard`) —
 * the actively-maintained Deal Room — not the older `/brand/chat` page,
 * which `/brand/messages` now covers for pure messaging.
 */
const navGroups: BrandNavGroup[] = [
  {
    label: 'Main',
    items: [
      { label: 'Home', href: '/brand/dashboard', icon: Home },
      { label: 'Meera', href: '/brand/meera', icon: Sparkles },
      { label: 'Campaigns', href: '/brand/campaigns', icon: Megaphone },
      { label: 'Creators', href: '/brand/discover', icon: Users2 },
      { label: 'Deals', href: '/brand/deals', icon: MessageCircle },
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
    ],
  },
];

interface BrandLayoutProps {
  children: React.ReactNode;
}

export function BrandLayout({ children }: BrandLayoutProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const { unreadCount } = useNotificationStore();
  const { mobileMenuOpen, toggleMobileMenu, setMobileMenuOpen, closeMobileMenu } = useUIStore();
  const [showLogoutDialog, setShowLogoutDialog] = React.useState(false);
  const [commandBarOpen, setCommandBarOpen] = React.useState(false);
  const [notificationsOpen, setNotificationsOpen] = React.useState(false);

  const mockNotifications = [
    { id: '1', type: 'deal', title: 'New proposal received', body: 'Priya Sharma sent a counter-offer on Summer Fashion Campaign', time: '5 min ago', read: false },
    { id: '2', type: 'payment', title: 'Payment released', body: 'Milestone 1 payment of ₹25,000 was released to Sneha Reddy', time: '1 hr ago', read: false },
    { id: '3', type: 'contract', title: 'Contract signed', body: 'Rahul Verma signed the Product Launch contract', time: '3 hrs ago', read: true },
    { id: '4', type: 'system', title: 'Campaign approved', body: 'Your Tech Review campaign has been approved and is now live', time: '1 day ago', read: true },
  ];

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
    // Deals (/brand/deals) covers its own deep-link variant (/brand/deals/:id)
    // only — Contracts and Messages are now separate nav items, each with
    // their own real page, not sub-surfaces of Deals.
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
                      {getInitials(user?.displayName)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 text-left min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">
                      {user?.displayName || 'Brand Account'}
                    </p>
                  </div>
                  <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="top" className="w-52">
                <DropdownMenuLabel className="font-medium text-xs text-muted-foreground">
                  {user?.email || 'brand@company.com'}
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => handleNavigate('/brand/settings')}>
                  <Settings className="mr-2 h-4 w-4" />
                  Settings
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => window.open('https://help.influora.com', '_blank')}>
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
                    <Button variant="ghost" size="sm" className="h-auto px-2 text-xs text-muted-foreground">
                      Mark all read
                    </Button>
                  </div>
                  <ScrollArea className="h-80">
                    <div className="p-1">
                      {mockNotifications.map((notification) => (
                        <div
                          key={notification.id}
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
                            <p className="text-xs text-muted-foreground line-clamp-2">{notification.body}</p>
                            <p className="text-xs text-muted-foreground mt-1">{notification.time}</p>
                          </div>
                        </div>
                      ))}
                    </div>
                  </ScrollArea>
                  <div className="border-t border-border p-2">
                    <Button
                      variant="ghost"
                      size="sm"
                      className="w-full text-xs"
                      onClick={() => setNotificationsOpen(false)}
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
                      {getInitials(user?.displayName)}
                    </AvatarFallback>
                    </Avatar>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48">
                  <DropdownMenuLabel className="text-xs text-muted-foreground">
                    {user?.email || 'brand@company.com'}
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => handleNavigate('/brand/settings')}>
                    <Settings className="mr-2 h-4 w-4" /> Settings
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
          <main className="flex-1">{children}</main>
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
