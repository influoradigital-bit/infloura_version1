import * as React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/store';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
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
  Briefcase,
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
} from 'lucide-react';
import { InfluoraLogo } from '@/components/shared/influora-logo';

interface CreatorLayoutProps {
  children: React.ReactNode;
}

/**
 * 3-item navigation. Inbox + Active + Deal Room collapsed into one Deals
 * page (filtered by status). Profile + Settings live in the avatar menu.
 */
const navItems = [
  { label: 'Deals', href: '/creator/deals', icon: Briefcase },
  { label: 'Wallet', href: '/creator/wallet', icon: Wallet },
];

export function CreatorLayout({ children }: CreatorLayoutProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [showLogoutDialog, setShowLogoutDialog] = React.useState(false);

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
  const [mobileMenuOpen, setMobileMenuOpen] = React.useState(false);
  const [unreadCount] = React.useState(3);

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

  const handleLogout = () => {
    logout();
    localStorage.removeItem('creator_token');
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
          {/* Logo */}
          <div className="flex h-14 items-center gap-2.5 border-b border-sidebar-border px-5">
            <button
              onClick={() => navigate('/creator/deals')}
              className="flex items-center gap-2.5 hover:opacity-80 transition-opacity"
            >
              <InfluoraLogo size="md" showName={true} />
            </button>
          </div>

          {/* Nav items */}
          <nav className="flex-1 px-3 py-4">
            <div className="flex flex-col gap-0.5">
              {navItems.map((item) => {
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
          </nav>

          {/* Sidebar bottom: user + logout */}
          <div className="border-t border-sidebar-border p-3">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 hover:bg-sidebar-accent transition-colors">
                  <Avatar className="h-7 w-7">
                    <AvatarImage src={user?.avatarUrl} />
                    <AvatarFallback className="text-xs bg-primary/10 text-primary">
                      {getInitials(user?.displayName)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 text-left min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">
                      {user?.displayName || 'Creator Account'}
                    </p>
                  </div>
                  <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="top" className="w-52">
                <DropdownMenuLabel className="font-medium text-xs text-muted-foreground">
                  {user?.email || '@priya_sharma'}
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
                onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
                className="p-1.5 hover:bg-accent rounded-lg transition-colors"
              >
                {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
              </button>
              <button
                onClick={() => navigate('/creator/deals')}
                className="flex items-center gap-2"
              >
                <InfluoraLogo size="sm" showName={true} />
              </button>
            </div>

            {/* Desktop: search placeholder */}
            <div className="hidden lg:block">
              <div className="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground bg-muted/60 rounded-lg min-w-[240px]">
                <Search className="h-4 w-4" />
                <span className="flex-1 text-left">Search collaborations...</span>
              </div>
            </div>

            {/* Right: notifications */}
            <div className="flex items-center gap-2">
              <button className="lg:hidden p-1.5 hover:bg-accent rounded-lg transition-colors">
                <Search className="h-5 w-5 text-muted-foreground" />
              </button>
              <button className="relative p-1.5 hover:bg-accent rounded-lg transition-colors">
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
                  <button className="lg:hidden p-0.5 rounded-full hover:ring-2 hover:ring-ring transition-all">
                    <Avatar className="h-7 w-7">
                      <AvatarFallback className="text-xs bg-primary/10 text-primary">
                        {getInitials(user?.displayName)}
                      </AvatarFallback>
                    </Avatar>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48">
                  <DropdownMenuLabel className="text-xs text-muted-foreground">
                    {user?.email || '@priya_sharma'}
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
              <nav className="px-3 py-3">
                <div className="flex flex-col gap-0.5">
                  {navItems.map((item) => {
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
                        <Icon className="h-[18px] w-[18px]" />
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
