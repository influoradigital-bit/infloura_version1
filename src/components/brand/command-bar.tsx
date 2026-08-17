import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Megaphone,
  Users,
  FileText,
  Wallet,
  Settings,
  MessageSquare,
  Plus,
  LayoutDashboard,
  Target,
  KanbanSquare,
  BarChart3,
  Star,
  AlertTriangle,
  Inbox,
  Sparkles,
  HelpCircle,
  Bell,
} from 'lucide-react';

import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '@/components/ui/command';
import { isApiLive } from '@/lib/api';

interface CommandBarProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

// F-0261 — these used to advertise a ⌘<letter> hint via <CommandShortcut> with no keydown
// handler anywhere in the app implementing it. ⌘F is the browser's own Find, ⌘C is Copy, and
// ⌘W closes the tab — none of those are ours to silently hijack, so rather than wire up three
// new global chords this drops the false advertising. Selecting these from the open palette
// (click or Enter) still works exactly as before; only the unimplemented keyboard hint is gone.
const quickActions = [
  {
    id: 'new-campaign',
    title: 'Create New Campaign',
    icon: Plus,
    action: '/brand/campaigns/new',
  },
  {
    id: 'find-creators',
    title: 'Find Creators',
    icon: Users,
    action: '/brand/discover',
  },
  {
    id: 'recharge-wallet',
    title: 'Recharge Wallet',
    icon: Wallet,
    action: '/brand/wallet',
  },
];

// Navigation items — F-0261: this used to cover only 7 of the 15 real /brand/* destinations
// (BrandLayout's own sidebar nav, src/components/brand/brand-layout.tsx, is the source of
// truth this was cross-checked against). Every action below is a route that actually exists in
// src/App.tsx — a command that navigates to a 404 is worse than one that's simply missing.
const navItems = [
  { id: 'now', title: 'Now', description: 'Action items & priorities', icon: LayoutDashboard, action: '/brand/dashboard' },
  { id: 'campaigns', title: 'Campaigns', description: 'Manage campaigns', icon: Megaphone, action: '/brand/campaigns' },
  { id: 'discover', title: 'Discover', description: 'Find creators', icon: Users, action: '/brand/discover' },
  { id: 'deals', title: 'Deal Rooms', description: 'Negotiations', icon: MessageSquare, action: '/brand/chat' },
  { id: 'pipeline', title: 'Pipeline', description: 'Track collaborations across stages', icon: KanbanSquare, action: '/brand/pipeline' },
  { id: 'messages', title: 'Messages', description: 'Direct messages', icon: Inbox, action: '/brand/messages' },
  { id: 'contracts', title: 'Contracts', description: 'Agreements & deliverables', icon: FileText, action: '/brand/contracts' },
  { id: 'analytics', title: 'Analytics', description: 'Campaign & creator performance', icon: BarChart3, action: '/brand/analytics' },
  { id: 'reviews', title: 'Reviews', description: 'Creator reviews', icon: Star, action: '/brand/reviews' },
  { id: 'disputes', title: 'Disputes', description: 'Open disputes', icon: AlertTriangle, action: '/brand/disputes' },
  { id: 'meera', title: 'Meera', description: 'AI campaign assistant', icon: Sparkles, action: '/brand/meera' },
  { id: 'wallet', title: 'Wallet', description: 'Balance & transactions', icon: Wallet, action: '/brand/wallet' },
  { id: 'notifications', title: 'Notifications', description: 'Recent activity', icon: Bell, action: '/brand/notifications' },
  { id: 'help', title: 'Help', description: 'Support & documentation', icon: HelpCircle, action: '/brand/help' },
  { id: 'settings', title: 'Settings', description: 'Workspace settings', icon: Settings, action: '/brand/settings' },
];

// Demo-only "Recent" fixtures. These carry fabricated ids that navigate to
// non-existent /brand/creators/1 etc. routes, so they must NEVER render in a
// live build — they are shown only in mock mode (F-0072). A real recent-items
// feed would come from the campaigns/creators clients; until that exists, live
// mode simply omits these sections rather than showing fake data.
const demoRecentCreators = [
  { id: '1', name: 'Priya Sharma', handle: '@priyasharma', avatar: 'PS' },
  { id: '2', name: 'Arjun Kapoor', handle: '@arjunkapoor', avatar: 'AK' },
  { id: '3', name: 'Sneha Reddy', handle: '@snehareddy', avatar: 'SR' },
];

const demoRecentCampaigns = [
  { id: '1', name: 'Summer Fashion', status: 'Active' },
  { id: '2', name: 'Tech Review Series', status: 'Active' },
  { id: '3', name: 'Wellness Campaign', status: 'Draft' },
];

export function CommandBar({ open, onOpenChange }: CommandBarProps) {
  const navigate = useNavigate();
  const [search, setSearch] = React.useState('');

  // In live mode we have no recent-items feed yet, so we show nothing rather
  // than fabricated demo rows that link to non-existent ids (F-0072).
  const recentCreators = isApiLive() ? [] : demoRecentCreators;
  const recentCampaigns = isApiLive() ? [] : demoRecentCampaigns;

  // F-0261 — BrandLayout (src/components/brand/brand-layout.tsx, out of scope for this file)
  // also listens for Cmd/Ctrl+K on `window` and unconditionally opens the bar, never toggling
  // it closed. `document` is hit before `window` during the bubble phase, so on the CLOSING
  // press this handler used to compute `false` first and then BrandLayout's listener ran right
  // after and forced it back to `true` — the bar opened but the same chord could never close
  // it. stopPropagation() here keeps this handler authoritative: the event never reaches
  // `window`, so BrandLayout's listener does not fire for this chord at all. Escape closing the
  // dialog is handled by the underlying Radix Dialog (CommandDialog -> Dialog), not here.
  React.useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        e.stopPropagation();
        onOpenChange(!open);
      }
    };

    document.addEventListener('keydown', down);
    return () => document.removeEventListener('keydown', down);
  }, [open, onOpenChange]);

  const handleSelect = (action: string) => {
    onOpenChange(false);
    setSearch('');
    navigate(action);
  };

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange}>
      <CommandInput 
        placeholder="Search or type a command..." 
        value={search}
        onValueChange={setSearch}
      />
      <CommandList>
        <CommandEmpty>
          <div className="py-6 text-center">
            <p className="text-sm text-muted-foreground">No results found</p>
            <p className="text-xs text-muted-foreground mt-1">
              Try searching for creators, campaigns, or commands
            </p>
          </div>
        </CommandEmpty>

        {/* Quick Actions */}
        <CommandGroup heading="Quick Actions">
          {quickActions.map((action) => (
            <CommandItem
              key={action.id}
              onSelect={() => handleSelect(action.action)}
              className="flex items-center gap-3"
            >
              <div className="h-8 w-8 rounded-lg bg-primary/10 flex items-center justify-center">
                <action.icon className="h-4 w-4 text-primary" />
              </div>
              <span>{action.title}</span>
            </CommandItem>
          ))}
        </CommandGroup>

        <CommandSeparator />

        {/* Navigation */}
        <CommandGroup heading="Go To">
          {navItems.map((item) => (
            <CommandItem
              key={item.id}
              onSelect={() => handleSelect(item.action)}
              className="flex items-center gap-3"
            >
              <item.icon className="h-4 w-4 text-muted-foreground" />
              <div className="flex-1">
                <span>{item.title}</span>
                <span className="ml-2 text-xs text-muted-foreground">{item.description}</span>
              </div>
            </CommandItem>
          ))}
        </CommandGroup>

        <CommandSeparator />

        {/* Recent Creators */}
        {recentCreators.length > 0 && (
          <CommandGroup heading="Recent Creators">
            {recentCreators.map((creator) => (
              <CommandItem
                key={creator.id}
                onSelect={() => handleSelect(`/brand/creators/${creator.id}`)}
                className="flex items-center gap-3"
              >
                <div className="h-8 w-8 rounded-full bg-muted flex items-center justify-center text-xs font-medium">
                  {creator.avatar}
                </div>
                <div>
                  <span>{creator.name}</span>
                  <span className="ml-2 text-xs text-muted-foreground">{creator.handle}</span>
                </div>
              </CommandItem>
            ))}
          </CommandGroup>
        )}

        {/* Recent Campaigns */}
        {recentCampaigns.length > 0 && (
          <CommandGroup heading="Recent Campaigns">
            {recentCampaigns.map((campaign) => (
              <CommandItem
                key={campaign.id}
                onSelect={() => handleSelect(`/brand/campaigns/${campaign.id}`)}
                className="flex items-center gap-3"
              >
                <Target className="h-4 w-4 text-muted-foreground" />
                <span>{campaign.name}</span>
                <span className={`ml-auto text-xs ${campaign.status === 'Active' ? 'text-stage-approved-fg' : 'text-muted-foreground'}`}>
                  {campaign.status}
                </span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}
      </CommandList>
    </CommandDialog>
  );
}
