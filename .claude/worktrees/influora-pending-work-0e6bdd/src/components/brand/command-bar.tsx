import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Search,
  Megaphone,
  Users,
  FileText,
  Wallet,
  Settings,
  MessageSquare,
  Plus,
  ArrowRight,
  User,
  Building2,
  LayoutDashboard,
  Target,
} from 'lucide-react';

import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
  CommandShortcut,
} from '@/components/ui/command';

interface CommandBarProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

// Quick actions
const quickActions = [
  {
    id: 'new-campaign',
    title: 'Create New Campaign',
    icon: Plus,
    shortcut: 'C',
    action: '/brand/campaigns/new',
  },
  {
    id: 'find-creators',
    title: 'Find Creators',
    icon: Users,
    shortcut: 'F',
    action: '/brand/discover',
  },
  {
    id: 'recharge-wallet',
    title: 'Recharge Wallet',
    icon: Wallet,
    shortcut: 'W',
    action: '/brand/wallet',
  },
];

// Navigation items
const navItems = [
  { id: 'now', title: 'Now', description: 'Action items & priorities', icon: LayoutDashboard, action: '/brand/dashboard' },
  { id: 'campaigns', title: 'Campaigns', description: 'Manage campaigns', icon: Megaphone, action: '/brand/campaigns' },
  { id: 'discover', title: 'Discover', description: 'Find creators', icon: Users, action: '/brand/discover' },
  { id: 'deals', title: 'Deal Rooms', description: 'Negotiations', icon: MessageSquare, action: '/brand/chat' },
  { id: 'contracts', title: 'Contracts', description: 'Agreements & deliverables', icon: FileText, action: '/brand/contracts' },
  { id: 'wallet', title: 'Wallet', description: 'Balance & transactions', icon: Wallet, action: '/brand/wallet' },
  { id: 'settings', title: 'Settings', description: 'Workspace settings', icon: Settings, action: '/brand/settings' },
];

// Recent creators (mock)
const recentCreators = [
  { id: '1', name: 'Priya Sharma', handle: '@priyasharma', avatar: 'PS' },
  { id: '2', name: 'Arjun Kapoor', handle: '@arjunkapoor', avatar: 'AK' },
  { id: '3', name: 'Sneha Reddy', handle: '@snehareddy', avatar: 'SR' },
];

// Recent campaigns (mock)
const recentCampaigns = [
  { id: '1', name: 'Summer Fashion', status: 'Active' },
  { id: '2', name: 'Tech Review Series', status: 'Active' },
  { id: '3', name: 'Wellness Campaign', status: 'Draft' },
];

export function CommandBar({ open, onOpenChange }: CommandBarProps) {
  const navigate = useNavigate();
  const [search, setSearch] = React.useState('');

  React.useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
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
              <CommandShortcut>⌘{action.shortcut}</CommandShortcut>
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
