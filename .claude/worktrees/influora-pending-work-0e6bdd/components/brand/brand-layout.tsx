'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  LayoutDashboard,
  Megaphone,
  Users,
  MessageSquare,
  FileText,
  Wallet,
  Settings,
  Bell,
  Search,
  ChevronDown,
  LogOut,
  Building2,
  Menu,
  X,
  Plus,
  HelpCircle,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { useAuthStore, useNotificationStore, useUIStore } from '@/lib/store';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
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
  SheetTrigger,
} from '@/components/ui/sheet';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';

const navigation = [
  { name: 'Dashboard', href: '/brand', icon: LayoutDashboard },
  { name: 'Campaigns', href: '/brand/campaigns', icon: Megaphone },
  { name: 'Discover', href: '/brand/discover', icon: Users },
  { name: 'Deal Room', href: '/brand/deals', icon: MessageSquare },
  { name: 'Contracts', href: '/brand/contracts', icon: FileText },
  { name: 'Payments', href: '/brand/payments', icon: Wallet },
];

const secondaryNavigation = [
  { name: 'Settings', href: '/brand/settings', icon: Settings },
  { name: 'Help', href: '/brand/help', icon: HelpCircle },
];

interface BrandLayoutProps {
  children: React.ReactNode;
}

export function BrandLayout({ children }: BrandLayoutProps) {
  const pathname = usePathname();
  const { user, workspace, logout } = useAuthStore();
  const { unreadCount } = useNotificationStore();
  const { sidebarOpen, mobileMenuOpen, toggleSidebar, toggleMobileMenu } = useUIStore();

  const isActive = (href: string) => {
    if (href === '/brand') return pathname === '/brand';
    return pathname.startsWith(href);
  };

  return (
    <TooltipProvider delayDuration={0}>
      <div className="flex min-h-screen bg-background">
        {/* Desktop Sidebar */}
        <aside
          className={cn(
            'fixed inset-y-0 left-0 z-50 hidden flex-col border-r border-border bg-sidebar transition-all duration-300 lg:flex',
            sidebarOpen ? 'w-64' : 'w-16'
          )}
        >
          {/* Logo */}
          <div className="flex h-16 items-center justify-between border-b border-sidebar-border px-4">
            <Link href="/brand" className="flex items-center gap-3">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
                <Building2 className="h-5 w-5 text-primary-foreground" />
              </div>
              {sidebarOpen && (
                <span className="text-lg font-semibold text-sidebar-foreground">
                  CollabOS
                </span>
              )}
            </Link>
            {sidebarOpen && (
              <Button
                variant="ghost"
                size="icon"
                onClick={toggleSidebar}
                className="h-8 w-8 text-sidebar-foreground/70 hover:text-sidebar-foreground"
              >
                <ChevronDown className="h-4 w-4 -rotate-90" />
              </Button>
            )}
          </div>

          {/* Navigation */}
          <ScrollArea className="flex-1 px-3 py-4">
            <nav className="flex flex-col gap-1">
              {navigation.map((item) => {
                const active = isActive(item.href);
                return (
                  <Tooltip key={item.name}>
                    <TooltipTrigger asChild>
                      <Link
                        href={item.href}
                        className={cn(
                          'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                          active
                            ? 'bg-sidebar-primary text-sidebar-primary-foreground'
                            : 'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
                        )}
                      >
                        <item.icon className="h-5 w-5 shrink-0" />
                        {sidebarOpen && <span>{item.name}</span>}
                      </Link>
                    </TooltipTrigger>
                    {!sidebarOpen && (
                      <TooltipContent side="right">{item.name}</TooltipContent>
                    )}
                  </Tooltip>
                );
              })}
            </nav>

            <div className="mt-6 border-t border-sidebar-border pt-4">
              <nav className="flex flex-col gap-1">
                {secondaryNavigation.map((item) => {
                  const active = isActive(item.href);
                  return (
                    <Tooltip key={item.name}>
                      <TooltipTrigger asChild>
                        <Link
                          href={item.href}
                          className={cn(
                            'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                            active
                              ? 'bg-sidebar-primary text-sidebar-primary-foreground'
                              : 'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
                          )}
                        >
                          <item.icon className="h-5 w-5 shrink-0" />
                          {sidebarOpen && <span>{item.name}</span>}
                        </Link>
                      </TooltipTrigger>
                      {!sidebarOpen && (
                        <TooltipContent side="right">{item.name}</TooltipContent>
                      )}
                    </Tooltip>
                  );
                })}
              </nav>
            </div>
          </ScrollArea>

          {/* Workspace Selector */}
          <div className="border-t border-sidebar-border p-3">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  className={cn(
                    'w-full justify-start gap-3 px-3 py-6',
                    !sidebarOpen && 'justify-center px-0'
                  )}
                >
                  <Avatar className="h-8 w-8">
                    <AvatarImage src={workspace?.logoUrl} />
                    <AvatarFallback className="bg-primary/10 text-primary">
                      {workspace?.name?.charAt(0) || 'W'}
                    </AvatarFallback>
                  </Avatar>
                  {sidebarOpen && (
                    <div className="flex flex-1 flex-col items-start text-left">
                      <span className="text-sm font-medium text-sidebar-foreground">
                        {workspace?.name || 'My Workspace'}
                      </span>
                      <span className="text-xs text-sidebar-foreground/60">
                        {user?.email}
                      </span>
                    </div>
                  )}
                  {sidebarOpen && (
                    <ChevronDown className="h-4 w-4 text-sidebar-foreground/60" />
                  )}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>My Account</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                  <Link href="/brand/settings">
                    <Settings className="mr-2 h-4 w-4" />
                    Settings
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => logout()} className="text-destructive">
                  <LogOut className="mr-2 h-4 w-4" />
                  Log out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </aside>

        {/* Main Content */}
        <div
          className={cn(
            'flex flex-1 flex-col transition-all duration-300',
            sidebarOpen ? 'lg:pl-64' : 'lg:pl-16'
          )}
        >
          {/* Top Header */}
          <header className="sticky top-0 z-40 flex h-16 items-center justify-between gap-4 border-b border-border bg-background/95 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/60 lg:px-6">
            {/* Mobile Menu Trigger */}
            <Sheet open={mobileMenuOpen} onOpenChange={toggleMobileMenu}>
              <SheetTrigger asChild>
                <Button variant="ghost" size="icon" className="lg:hidden">
                  <Menu className="h-5 w-5" />
                  <span className="sr-only">Toggle menu</span>
                </Button>
              </SheetTrigger>
              <SheetContent side="left" className="w-72 p-0">
                <SheetHeader className="border-b border-border p-4">
                  <SheetTitle className="flex items-center gap-3">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
                      <Building2 className="h-5 w-5 text-primary-foreground" />
                    </div>
                    CollabOS
                  </SheetTitle>
                </SheetHeader>
                <ScrollArea className="h-[calc(100vh-5rem)]">
                  <nav className="flex flex-col gap-1 p-4">
                    {navigation.map((item) => {
                      const active = isActive(item.href);
                      return (
                        <Link
                          key={item.name}
                          href={item.href}
                          onClick={() => toggleMobileMenu()}
                          className={cn(
                            'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                            active
                              ? 'bg-primary text-primary-foreground'
                              : 'text-foreground/70 hover:bg-accent hover:text-accent-foreground'
                          )}
                        >
                          <item.icon className="h-5 w-5" />
                          {item.name}
                        </Link>
                      );
                    })}
                    <div className="my-4 border-t border-border" />
                    {secondaryNavigation.map((item) => {
                      const active = isActive(item.href);
                      return (
                        <Link
                          key={item.name}
                          href={item.href}
                          onClick={() => toggleMobileMenu()}
                          className={cn(
                            'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                            active
                              ? 'bg-primary text-primary-foreground'
                              : 'text-foreground/70 hover:bg-accent hover:text-accent-foreground'
                          )}
                        >
                          <item.icon className="h-5 w-5" />
                          {item.name}
                        </Link>
                      );
                    })}
                  </nav>
                </ScrollArea>
              </SheetContent>
            </Sheet>

            {/* Collapsed Sidebar Toggle - Desktop */}
            {!sidebarOpen && (
              <Button
                variant="ghost"
                size="icon"
                onClick={toggleSidebar}
                className="hidden lg:flex"
              >
                <ChevronDown className="h-4 w-4 rotate-90" />
              </Button>
            )}

            {/* Search */}
            <div className="flex flex-1 items-center gap-4 lg:gap-6">
              <div className="relative hidden w-full max-w-md md:block">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  type="search"
                  placeholder="Search campaigns, creators..."
                  className="h-9 w-full pl-9 bg-muted/50"
                />
              </div>
            </div>

            {/* Actions */}
            <div className="flex items-center gap-2">
              {/* Quick Create */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="sm" className="gap-2">
                    <Plus className="h-4 w-4" />
                    <span className="hidden sm:inline">Create</span>
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem asChild>
                    <Link href="/brand/campaigns/new">
                      <Megaphone className="mr-2 h-4 w-4" />
                      New Campaign
                    </Link>
                  </DropdownMenuItem>
                  <DropdownMenuItem asChild>
                    <Link href="/brand/discover">
                      <Users className="mr-2 h-4 w-4" />
                      Find Creators
                    </Link>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>

              {/* Notifications */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="icon" className="relative">
                    <Bell className="h-5 w-5" />
                    {unreadCount > 0 && (
                      <Badge
                        variant="destructive"
                        className="absolute -right-1 -top-1 h-5 w-5 rounded-full p-0 text-xs"
                      >
                        {unreadCount > 9 ? '9+' : unreadCount}
                      </Badge>
                    )}
                    <span className="sr-only">Notifications</span>
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-80">
                  <DropdownMenuLabel>Notifications</DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <div className="py-4 text-center text-sm text-muted-foreground">
                    No new notifications
                  </div>
                </DropdownMenuContent>
              </DropdownMenu>

              {/* User Menu - Mobile */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="icon" className="lg:hidden">
                    <Avatar className="h-8 w-8">
                      <AvatarImage src={user?.avatarUrl} />
                      <AvatarFallback>
                        {user?.displayName?.charAt(0) || 'U'}
                      </AvatarFallback>
                    </Avatar>
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuLabel>{user?.displayName}</DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem asChild>
                    <Link href="/brand/settings">Settings</Link>
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => logout()} className="text-destructive">
                    Log out
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </header>

          {/* Page Content */}
          <main className="flex-1">{children}</main>
        </div>
      </div>
    </TooltipProvider>
  );
}
