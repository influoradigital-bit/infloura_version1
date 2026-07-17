import React, { useState } from 'react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  BarChart3,
  MessageCircle,
  FileText,
  Zap,
  ChevronRight,
  Plus,
  Search,
  Filter,
  Calendar,
  DollarSign,
  CheckCircle2,
  Clock,
  AlertCircle,
} from 'lucide-react';

interface DealRoom {
  id: string;
  campaignName: string;
  creatorName: string;
  creatorImage: string;
  status: 'negotiating' | 'proposed' | 'accepted' | 'rejected';
  budget: number;
  deliverables: number;
  timeline: string;
  lastMessage: string;
  lastMessageTime: string;
  messages: number;
  proposalVersion: number;
}

const mockDealRooms: DealRoom[] = [
  {
    id: '1',
    campaignName: 'Summer Collection Launch',
    creatorName: 'Alex Johnson',
    creatorImage: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400',
    status: 'negotiating',
    budget: 5000,
    deliverables: 3,
    timeline: '30 days',
    lastMessage: 'Looks good, let me review the deliverables...',
    lastMessageTime: '2 hours ago',
    messages: 12,
    proposalVersion: 2,
  },
  {
    id: '2',
    campaignName: 'Seasonal Campaign Q3',
    creatorName: 'Jordan Smith',
    creatorImage: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400',
    status: 'proposed',
    budget: 8000,
    deliverables: 5,
    timeline: '45 days',
    lastMessage: 'Sent initial proposal - awaiting review',
    lastMessageTime: '1 day ago',
    messages: 3,
    proposalVersion: 1,
  },
  {
    id: '3',
    campaignName: 'Holiday Promo 2024',
    creatorName: 'Sam Chen',
    creatorImage: 'https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=400',
    status: 'accepted',
    budget: 12000,
    deliverables: 8,
    timeline: '60 days',
    lastMessage: 'Contract signed - moving to deliverables',
    lastMessageTime: '3 days ago',
    messages: 28,
    proposalVersion: 3,
  },
];

const statusConfig = {
  negotiating: {
    label: 'Negotiating',
    color: 'bg-amber-500/10 text-amber-600 border border-amber-200',
    icon: Clock,
  },
  proposed: {
    label: 'Proposed',
    color: 'bg-blue-500/10 text-blue-600 border border-blue-200',
    icon: FileText,
  },
  accepted: {
    label: 'Accepted',
    color: 'bg-green-500/10 text-green-600 border border-green-200',
    icon: CheckCircle2,
  },
  rejected: {
    label: 'Rejected',
    color: 'bg-red-500/10 text-red-600 border border-red-200',
    icon: AlertCircle,
  },
};

export function DealRoomDashboard() {
  const [searchTerm, setSearchTerm] = useState('');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [selectedDeal, setSelectedDeal] = useState<DealRoom | null>(null);
  const [activeTab, setActiveTab] = useState('overview');

  const filteredDeals = mockDealRooms.filter((deal) => {
    const matchesSearch =
      deal.campaignName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      deal.creatorName.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesStatus = filterStatus === 'all' || deal.status === filterStatus;
    return matchesSearch && matchesStatus;
  });

  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-900 via-slate-800 to-slate-900 pb-20">
      {/* Header */}
      <div className="sticky top-0 z-40 border-b border-slate-700/50 bg-slate-900/80 backdrop-blur supports-[backdrop-filter]:bg-slate-900/60 px-4 py-6 sm:px-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl sm:text-3xl font-bold text-white">Deal Rooms</h1>
            <p className="text-sm text-slate-400 mt-1">Negotiate and finalize creator partnerships</p>
          </div>
          <Button className="w-full sm:w-auto bg-blue-600 hover:bg-blue-700 text-white gap-2">
            <Plus className="w-4 h-4" />
            New Deal Room
          </Button>
        </div>

        {/* Search and Filter */}
        <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:gap-4">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500" />
            <input
              type="text"
              placeholder="Search campaigns or creators..."
              className="w-full pl-10 pr-4 py-2 bg-slate-800 border border-slate-700 rounded-lg text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
          </div>
          <div className="flex gap-2">
            <Filter className="w-4 h-4 text-slate-400 hidden sm:block" />
            <select
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              className="px-4 py-2 bg-slate-800 border border-slate-700 rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
            >
              <option value="all">All Status</option>
              <option value="negotiating">Negotiating</option>
              <option value="proposed">Proposed</option>
              <option value="accepted">Accepted</option>
              <option value="rejected">Rejected</option>
            </select>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="px-4 sm:px-6 py-6 grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Deal List */}
        <div className="lg:col-span-1">
          <div className="space-y-3 max-h-[calc(100vh-200px)] overflow-y-auto">
            {filteredDeals.length === 0 ? (
              <Card className="bg-slate-800/50 border-slate-700 p-8 text-center">
                <BarChart3 className="w-12 h-12 text-slate-500 mx-auto mb-3" />
                <p className="text-slate-400">No deal rooms found</p>
              </Card>
            ) : (
              filteredDeals.map((deal) => {
                const StatusIcon =
                  statusConfig[deal.status as keyof typeof statusConfig].icon;
                return (
                  <button
                    key={deal.id}
                    onClick={() => setSelectedDeal(deal)}
                    className={`w-full text-left p-4 rounded-lg border transition-all ${
                      selectedDeal?.id === deal.id
                        ? 'bg-slate-700 border-blue-500'
                        : 'bg-slate-800/50 border-slate-700 hover:bg-slate-800 hover:border-slate-600'
                    }`}
                  >
                    <div className="flex items-start gap-3">
                      <img
                        src={deal.creatorImage}
                        alt={deal.creatorName}
                        className="w-10 h-10 rounded-full object-cover"
                      />
                      <div className="flex-1 min-w-0">
                        <h3 className="font-semibold text-white text-sm truncate">
                          {deal.creatorName}
                        </h3>
                        <p className="text-xs text-slate-400 truncate">
                          {deal.campaignName}
                        </p>
                        <div className="mt-2 flex items-center gap-2">
                          <StatusIcon className="w-3 h-3" />
                          <span
                            className={`text-xs px-2 py-1 rounded ${
                              statusConfig[deal.status as keyof typeof statusConfig].color
                            }`}
                          >
                            {statusConfig[deal.status as keyof typeof statusConfig].label}
                          </span>
                        </div>
                      </div>
                      {deal.messages > 0 && (
                        <div className="flex-shrink-0 bg-blue-600 rounded-full w-5 h-5 flex items-center justify-center">
                          <span className="text-xs font-bold text-white">
                            {deal.messages}
                          </span>
                        </div>
                      )}
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>

        {/* Deal Details */}
        <div className="lg:col-span-2">
          {selectedDeal ? (
            <Card className="bg-slate-800/50 border-slate-700">
              <div className="p-6 border-b border-slate-700">
                <div className="flex items-start justify-between gap-4 mb-4">
                  <div className="flex items-start gap-4">
                    <img
                      src={selectedDeal.creatorImage}
                      alt={selectedDeal.creatorName}
                      className="w-16 h-16 rounded-full object-cover"
                    />
                    <div>
                      <h2 className="text-2xl font-bold text-white">
                        {selectedDeal.creatorName}
                      </h2>
                      <p className="text-slate-400">{selectedDeal.campaignName}</p>
                      <div className="mt-2 flex items-center gap-2">
                        <span
                          className={`px-3 py-1 rounded-full text-sm font-medium ${
                            statusConfig[selectedDeal.status as keyof typeof statusConfig]
                              .color
                          }`}
                        >
                          {statusConfig[selectedDeal.status as keyof typeof statusConfig].label}
                        </span>
                      </div>
                    </div>
                  </div>
                  <Button className="bg-slate-700 hover:bg-slate-600 text-white">
                    More Options
                  </Button>
                </div>
              </div>

              {/* Tabs */}
              <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
                <TabsList className="w-full grid grid-cols-4 gap-0 rounded-none bg-slate-700/50 p-0 border-b border-slate-700">
                  <TabsTrigger
                    value="overview"
                    className="rounded-none data-[state=active]:bg-slate-700 data-[state=active]:text-blue-400"
                  >
                    <span className="hidden sm:inline">Overview</span>
                    <span className="sm:hidden">Info</span>
                  </TabsTrigger>
                  <TabsTrigger
                    value="proposal"
                    className="rounded-none data-[state=active]:bg-slate-700 data-[state=active]:text-blue-400"
                  >
                    <span className="hidden sm:inline">Proposal</span>
                    <span className="sm:hidden">Prop</span>
                  </TabsTrigger>
                  <TabsTrigger
                    value="messages"
                    className="rounded-none data-[state=active]:bg-slate-700 data-[state=active]:text-blue-400"
                  >
                    <span className="hidden sm:inline">Messages</span>
                    <span className="sm:hidden">Chat</span>
                  </TabsTrigger>
                  <TabsTrigger
                    value="history"
                    className="rounded-none data-[state=active]:bg-slate-700 data-[state=active]:text-blue-400"
                  >
                    <span className="hidden sm:inline">History</span>
                    <span className="sm:hidden">Log</span>
                  </TabsTrigger>
                </TabsList>

                <div className="p-6">
                  {/* Overview Tab */}
                  <TabsContent value="overview" className="space-y-6 mt-0">
                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
                      <div className="bg-slate-700/30 rounded-lg p-4">
                        <p className="text-slate-400 text-xs mb-1">Budget</p>
                        <p className="text-xl sm:text-2xl font-bold text-white flex items-center gap-1">
                          <DollarSign className="w-5 h-5 text-green-400" />
                          {(selectedDeal.budget / 1000).toFixed(1)}k
                        </p>
                      </div>
                      <div className="bg-slate-700/30 rounded-lg p-4">
                        <p className="text-slate-400 text-xs mb-1">Deliverables</p>
                        <p className="text-xl sm:text-2xl font-bold text-white">
                          {selectedDeal.deliverables}
                        </p>
                      </div>
                      <div className="bg-slate-700/30 rounded-lg p-4">
                        <p className="text-slate-400 text-xs mb-1">Timeline</p>
                        <p className="text-sm sm:text-base font-semibold text-white">
                          {selectedDeal.timeline}
                        </p>
                      </div>
                    </div>

                    <div>
                      <h4 className="font-semibold text-white mb-3">Proposal Details</h4>
                      <div className="space-y-2 text-sm text-slate-300">
                        <p>
                          <span className="text-slate-400">Proposal Version:</span> v
                          {selectedDeal.proposalVersion}
                        </p>
                        <p>
                          <span className="text-slate-400">Sent:</span>{' '}
                          {selectedDeal.lastMessageTime}
                        </p>
                        <p>
                          <span className="text-slate-400">Messages:</span>{' '}
                          {selectedDeal.messages}
                        </p>
                      </div>
                    </div>

                    <div className="flex flex-col sm:flex-row gap-3">
                      <Button className="flex-1 bg-blue-600 hover:bg-blue-700 text-white gap-2">
                        <FileText className="w-4 h-4" />
                        View Proposal
                      </Button>
                      <Button className="flex-1 bg-slate-700 hover:bg-slate-600 text-white gap-2">
                        <MessageCircle className="w-4 h-4" />
                        Send Message
                      </Button>
                    </div>
                  </TabsContent>

                  {/* Proposal Tab */}
                  <TabsContent value="proposal" className="mt-0">
                    <div className="bg-slate-700/20 rounded-lg p-4 mb-4">
                      <h4 className="font-semibold text-white mb-3">Current Proposal (v{selectedDeal.proposalVersion})</h4>
                      <div className="space-y-3 text-sm text-slate-300">
                        <div className="flex justify-between items-center py-2 border-b border-slate-700">
                          <span>Platform Coverage</span>
                          <span className="font-semibold text-white">Instagram, TikTok, YouTube</span>
                        </div>
                        <div className="flex justify-between items-center py-2 border-b border-slate-700">
                          <span>Post Types</span>
                          <span className="font-semibold text-white">Reels, Stories, Feed</span>
                        </div>
                        <div className="flex justify-between items-center py-2">
                          <span>Usage Rights</span>
                          <span className="font-semibold text-white">3 months</span>
                        </div>
                      </div>
                    </div>
                    <Button className="w-full bg-blue-600 hover:bg-blue-700 text-white">
                      Accept Proposal
                    </Button>
                  </TabsContent>

                  {/* Messages Tab */}
                  <TabsContent value="messages" className="mt-0">
                    <div className="space-y-3 max-h-96 overflow-y-auto mb-4">
                      <div className="flex gap-3">
                        <img
                          src={selectedDeal.creatorImage}
                          alt="Creator"
                          className="w-8 h-8 rounded-full"
                        />
                        <div className="bg-slate-700/50 rounded-lg p-3 max-w-xs">
                          <p className="text-sm text-white">
                            I&apos;m interested in this opportunity!
                          </p>
                          <p className="text-xs text-slate-500 mt-1">2 hours ago</p>
                        </div>
                      </div>
                      <div className="flex gap-3 justify-end">
                        <div className="bg-blue-600/30 rounded-lg p-3 max-w-xs">
                          <p className="text-sm text-white">Great! Let&apos;s discuss details.</p>
                          <p className="text-xs text-slate-400 mt-1">1 hour ago</p>
                        </div>
                      </div>
                    </div>
                    <div className="flex gap-2">
                      <input
                        type="text"
                        placeholder="Type your message..."
                        className="flex-1 px-4 py-2 bg-slate-700 border border-slate-600 rounded-lg text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                      />
                      <Button className="bg-blue-600 hover:bg-blue-700 text-white">
                        Send
                      </Button>
                    </div>
                  </TabsContent>

                  {/* History Tab */}
                  <TabsContent value="history" className="mt-0 space-y-3">
                    <div className="flex gap-3 text-sm">
                      <div className="text-blue-400 font-semibold">v3</div>
                      <div className="flex-1">
                        <p className="text-white font-medium">Proposal Updated</p>
                        <p className="text-slate-400">3 days ago</p>
                      </div>
                    </div>
                    <div className="flex gap-3 text-sm">
                      <div className="text-blue-400 font-semibold">v2</div>
                      <div className="flex-1">
                        <p className="text-white font-medium">Proposal Revised</p>
                        <p className="text-slate-400">5 days ago</p>
                      </div>
                    </div>
                    <div className="flex gap-3 text-sm">
                      <div className="text-blue-400 font-semibold">v1</div>
                      <div className="flex-1">
                        <p className="text-white font-medium">Proposal Created</p>
                        <p className="text-slate-400">1 week ago</p>
                      </div>
                    </div>
                  </TabsContent>
                </div>
              </Tabs>
            </Card>
          ) : (
            <Card className="bg-slate-800/50 border-slate-700 flex items-center justify-center h-full min-h-96">
              <div className="text-center">
                <Zap className="w-12 h-12 text-slate-600 mx-auto mb-3" />
                <p className="text-slate-400">Select a deal room to view details</p>
              </div>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
