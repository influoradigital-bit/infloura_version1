import * as React from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Search,
  Send,
  Paperclip,
  Image as ImageIcon,
  MoreVertical,
  Phone,
  Video,
  ChevronLeft,
  Check,
  CheckCheck,
  Clock,
  Star,
  Archive,
  Trash2,
  Pin,
  Bell,
  BellOff,
  Flag,
  FileText,
  DollarSign,
  Calendar,
  Smile,
  Mic,
  X,
  Loader2,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import {
  deals as dealsApi,
  messages as messagesApi,
  isApiLive,
  type Deal,
  type DealMessage,
} from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Textarea } from '@/components/ui/textarea';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { Separator } from '@/components/ui/separator';

// Types
interface Message {
  id: string;
  senderId: string;
  senderType: 'brand' | 'creator';
  content: string;
  type: 'text' | 'image' | 'file' | 'proposal' | 'contract' | 'payment';
  timestamp: Date;
  status: 'sending' | 'sent' | 'delivered' | 'read';
  attachments?: { name: string; url: string; type: string; size: string }[];
  metadata?: Record<string, unknown>;
}

interface Conversation {
  id: string;
  creator: {
    id: string;
    name: string;
    avatar: string;
    handle: string;
    isOnline: boolean;
    lastSeen?: Date;
  };
  campaign?: {
    id: string;
    name: string;
  };
  lastMessage: {
    content: string;
    timestamp: Date;
    isFromMe: boolean;
  };
  unreadCount: number;
  isPinned: boolean;
  isMuted: boolean;
  isArchived: boolean;
}

// Mock data
const mockConversations: Conversation[] = [
  {
    id: 'conv-1',
    creator: {
      id: 'c1',
      name: 'Sarah Johnson',
      avatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=100',
      handle: '@sarahjcreates',
      isOnline: true,
    },
    campaign: { id: 'camp-1', name: 'Summer Collection Launch' },
    lastMessage: {
      content: 'I have uploaded the final deliverables for review!',
      timestamp: new Date(Date.now() - 1000 * 60 * 5),
      isFromMe: false,
    },
    unreadCount: 2,
    isPinned: true,
    isMuted: false,
    isArchived: false,
  },
  {
    id: 'conv-2',
    creator: {
      id: 'c2',
      name: 'Alex Chen',
      avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100',
      handle: '@alextech',
      isOnline: false,
      lastSeen: new Date(Date.now() - 1000 * 60 * 30),
    },
    campaign: { id: 'camp-2', name: 'Tech Product Review' },
    lastMessage: {
      content: 'Thanks for the feedback! I will make those changes.',
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 2),
      isFromMe: false,
    },
    unreadCount: 0,
    isPinned: false,
    isMuted: false,
    isArchived: false,
  },
  {
    id: 'conv-3',
    creator: {
      id: 'c3',
      name: 'Maya Patel',
      avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100',
      handle: '@mayastyle',
      isOnline: true,
    },
    lastMessage: {
      content: 'Looking forward to our collaboration!',
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24),
      isFromMe: true,
    },
    unreadCount: 0,
    isPinned: false,
    isMuted: true,
    isArchived: false,
  },
  {
    id: 'conv-4',
    creator: {
      id: 'c4',
      name: 'James Wilson',
      avatar: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=100',
      handle: '@jamesfitness',
      isOnline: false,
      lastSeen: new Date(Date.now() - 1000 * 60 * 60 * 5),
    },
    campaign: { id: 'camp-3', name: 'Fitness App Promotion' },
    lastMessage: {
      content: 'The contract looks good. I will sign it today.',
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 48),
      isFromMe: false,
    },
    unreadCount: 1,
    isPinned: false,
    isMuted: false,
    isArchived: false,
  },
];

// Per-conversation message store keyed by conversation id
const mockMessagesByConversation: Record<string, Message[]> = {
  'conv-1': [
    {
      id: 'c1-m1', senderId: 'brand', senderType: 'brand',
      content: 'Hi Sarah! We loved your portfolio and would like to discuss a collaboration for our Summer Collection campaign.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24 * 2), status: 'read',
    },
    {
      id: 'c1-m2', senderId: 'c1', senderType: 'creator',
      content: 'Thank you so much! I would love to work with your brand. Could you share more details about the campaign requirements?',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24 * 2 + 1000 * 60 * 30), status: 'read',
    },
    {
      id: 'c1-m3', senderId: 'brand', senderType: 'brand', content: '', type: 'proposal',
      timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24), status: 'read',
      metadata: { proposalId: 'prop-1', amount: 2500, deliverables: 3, deadline: '2024-02-15', status: 'accepted' },
    },
    {
      id: 'c1-m4', senderId: 'c1', senderType: 'creator',
      content: 'I accept the proposal! The terms look great. When can we start?',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 20), status: 'read',
    },
    {
      id: 'c1-m5', senderId: 'brand', senderType: 'brand',
      content: 'Fantastic! I have sent over the contract for your review.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 18), status: 'read',
    },
    {
      id: 'c1-m6', senderId: 'c1', senderType: 'creator',
      content: 'I have uploaded the final deliverables for review!',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 5), status: 'delivered',
    },
  ],
  'conv-2': [
    {
      id: 'c2-m1', senderId: 'brand', senderType: 'brand',
      content: 'Hi Alex! We are interested in having you review our new tech product. Are you available?',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 5), status: 'read',
    },
    {
      id: 'c2-m2', senderId: 'c2', senderType: 'creator',
      content: 'Absolutely! I have been looking at your product and it looks very promising. Send me the details.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 4), status: 'read',
    },
    {
      id: 'c2-m3', senderId: 'brand', senderType: 'brand',
      content: 'Great, here is the brief. We are targeting a launch date of next month.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 3), status: 'read',
    },
    {
      id: 'c2-m4', senderId: 'c2', senderType: 'creator',
      content: 'Thanks for the feedback! I will make those changes.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 2), status: 'read',
    },
  ],
  'conv-3': [
    {
      id: 'c3-m1', senderId: 'brand', senderType: 'brand',
      content: 'Hi Maya! We think your aesthetic is a perfect fit for our upcoming lifestyle campaign.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 30), status: 'read',
    },
    {
      id: 'c3-m2', senderId: 'c3', senderType: 'creator',
      content: 'Looking forward to our collaboration!',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 24), status: 'read',
    },
  ],
  'conv-4': [
    {
      id: 'c4-m1', senderId: 'brand', senderType: 'brand',
      content: 'Hi James! We would love to promote our fitness app with you. Can you take a look at the attached contract?',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 50), status: 'read',
    },
    {
      id: 'c4-m2', senderId: 'c4', senderType: 'creator',
      content: 'The contract looks good. I will sign it today.',
      type: 'text', timestamp: new Date(Date.now() - 1000 * 60 * 60 * 48), status: 'read',
    },
  ],
};

// ---------------------------------------------------------------------------
// Live-mode mapping — Deal (src/lib/api.ts) -> Conversation (this file's
// rendered shape). Fields with no DTO source are left in their honest "off"
// default rather than fabricated:
//   - creator.isOnline / creator.lastSeen: GET /deals carries no presence data
//   - creator.handle: no DTO field (falls back to empty string)
//   - lastMessage.isFromMe: GET /deals doesn't say who sent the last message
//   - isPinned / isMuted / isArchived: no per-conversation preference endpoint yet
// ---------------------------------------------------------------------------
function mapDealToConversation(deal: Deal): Conversation {
  return {
    id: deal.id,
    creator: {
      id: deal.counterpartyId,
      name: deal.counterpartyName,
      avatar: deal.counterpartyAvatar || '',
      handle: deal.counterpartyHandle || '',
      isOnline: false,
    },
    campaign: { id: deal.campaignId, name: deal.campaignName },
    lastMessage: {
      content: deal.lastMessage || 'No messages yet',
      timestamp: deal.lastMessageAt ? new Date(deal.lastMessageAt) : new Date(0),
      isFromMe: false,
    },
    unreadCount: deal.unreadCount,
    isPinned: false,
    isMuted: false,
    isArchived: false,
  };
}

function formatTime(date: Date): string {
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));
  
  if (days === 0) {
    return date.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  } else if (days === 1) {
    return 'Yesterday';
  } else if (days < 7) {
    return date.toLocaleDateString('en-US', { weekday: 'short' });
  } else {
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }
}

function formatLastSeen(date: Date): string {
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const minutes = Math.floor(diff / (1000 * 60));
  
  if (minutes < 60) {
    return `${minutes}m ago`;
  } else if (minutes < 1440) {
    return `${Math.floor(minutes / 60)}h ago`;
  } else {
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }
}

export default function BrandMessagesPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const creatorIdFromUrl = searchParams.get('creator');
  
  const initialConversation = creatorIdFromUrl
    ? mockConversations.find(c => c.creator.id === creatorIdFromUrl) || mockConversations[0]
    : mockConversations[0];

  // Live mode (isApiLive()) state — mock rendering above stays untouched.
  const [liveConversations, setLiveConversations] = React.useState<Conversation[]>([]);
  const [conversationsLoading, setConversationsLoading] = React.useState(false);
  const [conversationsError, setConversationsError] = React.useState<string | null>(null);
  const [liveMessages, setLiveMessages] = React.useState<DealMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = React.useState(false);
  const [messagesError, setMessagesError] = React.useState<string | null>(null);
  const [sendingMessage, setSendingMessage] = React.useState(false);

  const conversations = isApiLive() ? liveConversations : mockConversations;

  const [selectedConversation, setSelectedConversation] = React.useState<Conversation | null>(
    isApiLive() ? null : initialConversation
  );
  const [messages, setMessages] = React.useState<Message[]>(
    isApiLive() ? [] : (mockMessagesByConversation[initialConversation?.id ?? 'conv-1'] ?? [])
  );
  const [newMessage, setNewMessage] = React.useState('');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [isMobileConversationOpen, setIsMobileConversationOpen] = React.useState(!!creatorIdFromUrl);
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  React.useEffect(() => {
    scrollToBottom();
  }, [messages, liveMessages]);

  const loadConversations = React.useCallback(async () => {
    setConversationsLoading(true);
    setConversationsError(null);
    try {
      const remote = await dealsApi.list('brand');
      setLiveConversations(Array.isArray(remote) ? remote.map(mapDealToConversation) : []);
    } catch {
      setLiveConversations([]);
      setConversationsError('Could not load conversations. Check your connection and retry.');
    } finally {
      setConversationsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isApiLive()) void loadConversations();
  }, [loadConversations]);

  // Auto-select a conversation once the live list arrives (mock mode selects synchronously above).
  React.useEffect(() => {
    if (!isApiLive() || selectedConversation || liveConversations.length === 0) return;
    const match = creatorIdFromUrl
      ? liveConversations.find((c) => c.creator.id === creatorIdFromUrl)
      : undefined;
    setSelectedConversation(match || liveConversations[0]);
    if (creatorIdFromUrl) setIsMobileConversationOpen(true);
  }, [liveConversations, selectedConversation, creatorIdFromUrl]);

  const loadMessages = React.useCallback(async (dealId: string) => {
    setMessagesLoading(true);
    setMessagesError(null);
    try {
      const list = await messagesApi.list('brand', dealId);
      setLiveMessages(list);
      // fire-and-forget read receipt; failure here must not surface as a load error
      void messagesApi.markRead('brand', dealId).catch(() => {});
    } catch {
      setLiveMessages([]); // clear stale rows from a previously-selected conversation
      setMessagesError('Could not load messages. Check your connection and retry.');
    } finally {
      setMessagesLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isApiLive() && selectedConversation) {
      void loadMessages(selectedConversation.id);
    }
  }, [selectedConversation, loadMessages]);

  const handleSendMessage = async () => {
    if (!newMessage.trim() || !selectedConversation) return;
    const content = newMessage.trim();

    if (isApiLive()) {
      setNewMessage('');
      setMessagesError(null);
      setSendingMessage(true);
      try {
        const sent = await messagesApi.send('brand', selectedConversation.id, content);
        setLiveMessages((prev) => [...prev, sent]);
      } catch {
        setNewMessage(content); // restore so the user can retry — no silent loss
        setMessagesError('Could not send message. Try again.');
      } finally {
        setSendingMessage(false);
      }
      return;
    }

    const message: Message = {
      id: `m${Date.now()}`,
      senderId: 'brand',
      senderType: 'brand',
      content: newMessage,
      type: 'text',
      timestamp: new Date(),
      status: 'sending',
    };

    setMessages((prev) => [...prev, message]);
    setNewMessage('');

    // Simulate message sent
    setTimeout(() => {
      setMessages((prev) =>
        prev.map((m) => (m.id === message.id ? { ...m, status: 'sent' } : m))
      );
    }, 500);
  };

  const filteredConversations = conversations.filter(
    (conv) =>
      conv.creator.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      conv.creator.handle.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const pinnedConversations = filteredConversations.filter((c) => c.isPinned);
  const regularConversations = filteredConversations.filter((c) => !c.isPinned);

  const MessageStatusIcon = ({ status }: { status: Message['status'] }) => {
    switch (status) {
      case 'sending':
        return <Clock className="h-3 w-3 text-muted-foreground" />;
      case 'sent':
        return <Check className="h-3 w-3 text-muted-foreground" />;
      case 'delivered':
        return <CheckCheck className="h-3 w-3 text-muted-foreground" />;
      case 'read':
        return <CheckCheck className="h-3 w-3 text-primary" />;
      default:
        return null;
    }
  };

  return (
    <TooltipProvider>
      <div className="flex h-[calc(100vh-4rem)] overflow-hidden rounded-lg border border-border/50 bg-card">
        {/* Conversation List */}
        <div
          className={cn(
            'flex w-full flex-col border-r border-border/50 md:w-80 lg:w-96',
            isMobileConversationOpen && 'hidden md:flex'
          )}
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-border/50 p-4">
            <h2 className="text-lg font-semibold">Messages</h2>
            <Badge variant="secondary" className="font-medium">
              {conversations.reduce((acc, c) => acc + c.unreadCount, 0)} unread
            </Badge>
          </div>

          {/* Search */}
          <div className="p-3">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="Search conversations..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9"
              />
            </div>
          </div>

          {/* Conversations */}
          <ScrollArea className="flex-1">
            {isApiLive() && conversationsLoading && (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
              </div>
            )}
            {isApiLive() && !conversationsLoading && conversationsError && (
              <p className="px-3 py-8 text-center text-sm text-destructive">{conversationsError}</p>
            )}
            {isApiLive() && !conversationsLoading && !conversationsError && filteredConversations.length === 0 && (
              <p className="px-3 py-8 text-center text-sm text-muted-foreground">No conversations yet.</p>
            )}
            {(!isApiLive() || (!conversationsLoading && !conversationsError)) && (
              <>
                {pinnedConversations.length > 0 && (
                  <div className="px-3 pb-2">
                    <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                      <Pin className="h-3 w-3" /> Pinned
                    </p>
                    {pinnedConversations.map((conv) => (
                      <ConversationItem
                        key={conv.id}
                        conversation={conv}
                        isSelected={selectedConversation?.id === conv.id}
                        onClick={() => {
                          setSelectedConversation(conv);
                          if (!isApiLive()) setMessages(mockMessagesByConversation[conv.id] ?? []);
                          setIsMobileConversationOpen(true);
                        }}
                      />
                    ))}
                  </div>
                )}

                <div className="px-3 pb-3">
                  {pinnedConversations.length > 0 && (
                    <p className="mb-2 text-xs font-medium text-muted-foreground">All Messages</p>
                  )}
                  {regularConversations.map((conv) => (
                    <ConversationItem
                      key={conv.id}
                      conversation={conv}
                      isSelected={selectedConversation?.id === conv.id}
                      onClick={() => {
                        setSelectedConversation(conv);
                        if (!isApiLive()) setMessages(mockMessagesByConversation[conv.id] ?? []);
                        setIsMobileConversationOpen(true);
                      }}
                    />
                  ))}
                </div>
              </>
            )}
          </ScrollArea>
        </div>

        {/* Chat Area */}
        <div
          className={cn(
            'flex flex-1 flex-col',
            !isMobileConversationOpen && 'hidden md:flex'
          )}
        >
          {selectedConversation ? (
            <>
              {/* Chat Header */}
              <div className="flex items-center justify-between border-b border-border/50 p-4">
                <div className="flex items-center gap-3">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="md:hidden"
                    onClick={() => setIsMobileConversationOpen(false)}
                  >
                    <ChevronLeft className="h-5 w-5" />
                  </Button>
                  <div className="relative">
                    <Avatar className="h-10 w-10">
                      <AvatarImage src={selectedConversation.creator.avatar} />
                      <AvatarFallback>{selectedConversation.creator.name[0]}</AvatarFallback>
                    </Avatar>
                    {selectedConversation.creator.isOnline && (
                      <span className="absolute bottom-0 right-0 h-3 w-3 rounded-full border-2 border-card bg-green-500" />
                    )}
                  </div>
                  <div>
                    <h3 className="font-semibold">{selectedConversation.creator.name}</h3>
                    <p className="text-xs text-muted-foreground">
                      {selectedConversation.creator.isOnline
                        ? 'Online'
                        : selectedConversation.creator.lastSeen
                        ? `Last seen ${formatLastSeen(selectedConversation.creator.lastSeen)}`
                        : 'Offline'}
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-1">
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button variant="ghost" size="icon">
                        <Phone className="h-4 w-4" />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Voice Call</TooltipContent>
                  </Tooltip>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button variant="ghost" size="icon">
                        <Video className="h-4 w-4" />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Video Call</TooltipContent>
                  </Tooltip>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon">
                        <MoreVertical className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem>
                        <Star className="mr-2 h-4 w-4" /> Pin conversation
                      </DropdownMenuItem>
                      <DropdownMenuItem>
                        <BellOff className="mr-2 h-4 w-4" /> Mute notifications
                      </DropdownMenuItem>
                      <DropdownMenuItem>
                        <Archive className="mr-2 h-4 w-4" /> Archive
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem>
                        <Flag className="mr-2 h-4 w-4" /> Report
                      </DropdownMenuItem>
                      <DropdownMenuItem className="text-destructive">
                        <Trash2 className="mr-2 h-4 w-4" /> Delete conversation
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              </div>

              {/* Campaign Context Banner */}
              {selectedConversation.campaign && (
                <div className="flex items-center justify-between border-b border-border/50 bg-muted/30 px-4 py-2">
                  <div className="flex items-center gap-2 text-sm">
                    <FileText className="h-4 w-4 text-muted-foreground" />
                    <span className="text-muted-foreground">Campaign:</span>
                    <span className="font-medium">{selectedConversation.campaign.name}</span>
                  </div>
                  <Button variant="ghost" size="sm" onClick={() => navigate(`/brand/campaigns/${selectedConversation.campaign?.id}`)}>
                    View
                  </Button>
                </div>
              )}

              {/* Messages */}
              <ScrollArea className="flex-1 p-4">
                <div className="space-y-4">
                  {isApiLive() && (
                    <>
                      {messagesLoading && (
                        <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
                          <Loader2 className="h-4 w-4 mr-2 animate-spin" /> Loading messages…
                        </div>
                      )}
                      {!messagesLoading && messagesError && (
                        <p className="text-sm text-destructive text-center py-8">{messagesError}</p>
                      )}
                      {!messagesLoading && !messagesError && liveMessages.length === 0 && (
                        <p className="text-sm text-muted-foreground text-center py-8">No messages yet.</p>
                      )}
                      {!messagesLoading && !messagesError && liveMessages.map((m, index) => {
                        const isFromMe = m.senderType === 'brand';
                        const showAvatar =
                          index === 0 || liveMessages[index - 1].senderType !== m.senderType;
                        return (
                          <div key={m.id} className={cn('flex gap-2', isFromMe && 'flex-row-reverse')}>
                            {!isFromMe && showAvatar ? (
                              <Avatar className="h-8 w-8">
                                <AvatarImage src={selectedConversation.creator.avatar} />
                                <AvatarFallback>{selectedConversation.creator.name[0]}</AvatarFallback>
                              </Avatar>
                            ) : (
                              !isFromMe && <div className="w-8" />
                            )}
                            <div className={cn('flex max-w-[70%] flex-col', isFromMe && 'items-end')}>
                              <div
                                className={cn(
                                  'rounded-2xl px-4 py-2',
                                  isFromMe ? 'bg-primary text-primary-foreground' : 'bg-muted'
                                )}
                              >
                                {/* DealMessage.content is optional for non-text kinds; the deals
                                    API has no structured proposal/contract card payload yet, so
                                    non-text kinds fall back to an honest label instead of the
                                    fabricated card layouts used in demo mode below. */}
                                <p className="text-sm">{m.content || `[${m.kind} update]`}</p>
                              </div>
                              <div
                                className={cn(
                                  'mt-1 flex items-center gap-1 text-xs text-muted-foreground',
                                  isFromMe && 'flex-row-reverse'
                                )}
                              >
                                <span>{formatTime(new Date(m.createdAt))}</span>
                                {isFromMe && (
                                  m.readBy?.includes(selectedConversation.creator.id) ? (
                                    <CheckCheck className="h-3 w-3 text-primary" />
                                  ) : (
                                    <Check className="h-3 w-3 text-muted-foreground" />
                                  )
                                )}
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </>
                  )}
                  {!isApiLive() && messages.map((message, index) => {
                    const isFromMe = message.senderType === 'brand';
                    const showAvatar =
                      index === 0 ||
                      messages[index - 1].senderType !== message.senderType;
                    const showTimestamp =
                      index === messages.length - 1 ||
                      messages[index + 1].senderType !== message.senderType ||
                      new Date(messages[index + 1].timestamp).getTime() -
                        message.timestamp.getTime() >
                        1000 * 60 * 5;

                    return (
                      <div
                        key={message.id}
                        className={cn('flex gap-2', isFromMe && 'flex-row-reverse')}
                      >
                        {!isFromMe && showAvatar ? (
                          <Avatar className="h-8 w-8">
                            <AvatarImage src={selectedConversation.creator.avatar} />
                            <AvatarFallback>
                              {selectedConversation.creator.name[0]}
                            </AvatarFallback>
                          </Avatar>
                        ) : (
                          !isFromMe && <div className="w-8" />
                        )}

                        <div
                          className={cn(
                            'flex max-w-[70%] flex-col',
                            isFromMe && 'items-end'
                          )}
                        >
                          {/* Text Message */}
                          {message.type === 'text' && (
                            <div
                              className={cn(
                                'rounded-2xl px-4 py-2',
                                isFromMe
                                  ? 'bg-primary text-primary-foreground'
                                  : 'bg-muted'
                              )}
                            >
                              <p className="text-sm">{message.content}</p>
                            </div>
                          )}

                          {/* Proposal Message */}
                          {message.type === 'proposal' && (
                            <div className="w-72 rounded-xl border border-border bg-card p-4">
                              <div className="mb-3 flex items-center gap-2">
                                <div className="rounded-lg bg-primary/10 p-2">
                                  <DollarSign className="h-4 w-4 text-primary" />
                                </div>
                                <div>
                                  <p className="text-sm font-medium">Collaboration Proposal</p>
                                  <p className="text-xs text-muted-foreground">
                                    {message.metadata?.status === 'accepted' ? 'Accepted' : 'Pending'}
                                  </p>
                                </div>
                              </div>
                              <div className="space-y-2 text-sm">
                                <div className="flex justify-between">
                                  <span className="text-muted-foreground">Amount</span>
                                  <span className="font-medium">${String(message.metadata?.amount ?? '')}</span>
                                </div>
                                <div className="flex justify-between">
                                  <span className="text-muted-foreground">Deliverables</span>
                                  <span className="font-medium">{String(message.metadata?.deliverables ?? '')} pieces</span>
                                </div>
                                <div className="flex justify-between">
                                  <span className="text-muted-foreground">Deadline</span>
                                  <span className="font-medium">{message.metadata?.deadline as string}</span>
                                </div>
                              </div>
                              {message.metadata?.status === 'accepted' && (
                                <Badge className="mt-3 w-full justify-center" variant="secondary">
                                  <Check className="mr-1 h-3 w-3" /> Accepted
                                </Badge>
                              )}
                            </div>
                          )}

                          {/* Contract Message */}
                          {message.type === 'contract' && (
                            <div className="w-72 rounded-xl border border-border bg-card p-4">
                              <div className="mb-3 flex items-center gap-2">
                                <div className="rounded-lg bg-blue-500/10 p-2">
                                  <FileText className="h-4 w-4 text-blue-500" />
                                </div>
                                <div className="flex-1">
                                  <p className="text-sm font-medium">{message.metadata?.title as string}</p>
                                  <p className="text-xs text-muted-foreground">
                                    {message.metadata?.status === 'signed' ? 'Signed by both parties' : 'Awaiting signature'}
                                  </p>
                                </div>
                              </div>
                              <Button variant="outline" size="sm" className="w-full">
                                View Contract
                              </Button>
                            </div>
                          )}

                          {/* Image Message */}
                          {message.type === 'image' && message.attachments && (
                            <div className="grid gap-2">
                              {message.attachments.map((attachment, i) => (
                                <div
                                  key={i}
                                  className="relative overflow-hidden rounded-xl"
                                >
                                  <img
                                    src={attachment.url}
                                    alt={attachment.name}
                                    className="max-h-64 w-auto object-cover"
                                  />
                                </div>
                              ))}
                            </div>
                          )}

                          {/* Timestamp & Status */}
                          {showTimestamp && (
                            <div
                              className={cn(
                                'mt-1 flex items-center gap-1 text-xs text-muted-foreground',
                                isFromMe && 'flex-row-reverse'
                              )}
                            >
                              <span>{formatTime(message.timestamp)}</span>
                              {isFromMe && <MessageStatusIcon status={message.status} />}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                  <div ref={messagesEndRef} />
                </div>
              </ScrollArea>

              {/* Input Area */}
              <div className="border-t border-border/50 p-4">
                {isApiLive() && messagesError && (
                  <p className="text-xs text-destructive mb-2">{messagesError}</p>
                )}
                <div className="flex items-end gap-2">
                  <div className="flex gap-1">
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Button variant="ghost" size="icon" className="h-9 w-9">
                          <Paperclip className="h-4 w-4" />
                        </Button>
                      </TooltipTrigger>
                      <TooltipContent>Attach file</TooltipContent>
                    </Tooltip>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Button variant="ghost" size="icon" className="h-9 w-9">
                          <ImageIcon className="h-4 w-4" />
                        </Button>
                      </TooltipTrigger>
                      <TooltipContent>Send image</TooltipContent>
                    </Tooltip>
                  </div>

                  <div className="relative flex-1">
                    <Textarea
                      placeholder="Type a message..."
                      value={newMessage}
                      onChange={(e) => setNewMessage(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && !e.shiftKey) {
                          e.preventDefault();
                          handleSendMessage();
                        }
                      }}
                      className="min-h-[44px] max-h-32 resize-none pr-20"
                      rows={1}
                      disabled={sendingMessage}
                    />
                    <div className="absolute bottom-2 right-2 flex items-center gap-1">
                      <Button variant="ghost" size="icon" className="h-7 w-7">
                        <Smile className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>

                  <Button onClick={handleSendMessage} disabled={!newMessage.trim() || sendingMessage}>
                    {sendingMessage ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <Send className="h-4 w-4" />
                    )}
                  </Button>
                </div>
              </div>
            </>
          ) : (
            <div className="flex flex-1 items-center justify-center">
              <div className="text-center">
                <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
                  <Send className="h-8 w-8 text-muted-foreground" />
                </div>
                <h3 className="mb-1 text-lg font-semibold">Select a conversation</h3>
                <p className="text-sm text-muted-foreground">
                  Choose a creator to start messaging
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </TooltipProvider>
  );
}

function ConversationItem({
  conversation,
  isSelected,
  onClick,
}: {
  conversation: Conversation;
  isSelected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'mb-1 flex w-full items-center gap-3 rounded-lg p-3 text-left transition-colors',
        isSelected ? 'bg-primary/10' : 'hover:bg-muted/50'
      )}
    >
      <div className="relative">
        <Avatar className="h-12 w-12">
          <AvatarImage src={conversation.creator.avatar} />
          <AvatarFallback>{conversation.creator.name[0]}</AvatarFallback>
        </Avatar>
        {conversation.creator.isOnline && (
          <span className="absolute bottom-0 right-0 h-3 w-3 rounded-full border-2 border-card bg-green-500" />
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate font-medium">{conversation.creator.name}</span>
          <span className="flex-shrink-0 text-xs text-muted-foreground">
            {formatTime(conversation.lastMessage.timestamp)}
          </span>
        </div>
        <div className="flex items-center justify-between gap-2">
          <p className="truncate text-sm text-muted-foreground">
            {conversation.lastMessage.isFromMe && 'You: '}
            {conversation.lastMessage.content}
          </p>
          {conversation.unreadCount > 0 && (
            <Badge className="h-5 min-w-[20px] justify-center px-1.5">
              {conversation.unreadCount}
            </Badge>
          )}
        </div>
        {conversation.campaign && (
          <p className="mt-0.5 truncate text-xs text-muted-foreground">
            {conversation.campaign.name}
          </p>
        )}
      </div>

      {conversation.isMuted && (
        <BellOff className="h-4 w-4 flex-shrink-0 text-muted-foreground" />
      )}
    </button>
  );
}
