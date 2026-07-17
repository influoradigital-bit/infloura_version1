import React, { useState } from 'react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  FileText,
  Download,
  Eye,
  CheckCircle2,
  Clock,
  AlertCircle,
  ArrowRight,
  Filter,
  Search,
  PenTool,
  Shield,
  Calendar,
  User,
} from 'lucide-react';

interface Contract {
  id: string;
  campaignName: string;
  creatorName: string;
  creatorImage: string;
  status: 'draft' | 'pending_signature' | 'signed' | 'expired';
  signedDate?: string;
  expiryDate: string;
  value: number;
  deliverables: number;
}

interface Deliverable {
  id: string;
  contractId: string;
  title: string;
  platform: string;
  type: string;
  dueDate: string;
  status: 'pending' | 'submitted' | 'approved' | 'revisions_needed';
  submittedDate?: string;
  approvalDate?: string;
}

const mockContracts: Contract[] = [
  {
    id: '1',
    campaignName: 'Summer Collection Launch',
    creatorName: 'Alex Johnson',
    creatorImage: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400',
    status: 'signed',
    signedDate: '2024-01-15',
    expiryDate: '2024-12-31',
    value: 5000,
    deliverables: 3,
  },
  {
    id: '2',
    campaignName: 'Seasonal Campaign Q3',
    creatorName: 'Jordan Smith',
    creatorImage: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400',
    status: 'pending_signature',
    expiryDate: '2024-10-31',
    value: 8000,
    deliverables: 5,
  },
];

const mockDeliverables: Deliverable[] = [
  {
    id: '1',
    contractId: '1',
    title: 'Instagram Reels Series',
    platform: 'Instagram',
    type: 'Reels',
    dueDate: '2024-02-15',
    status: 'approved',
    submittedDate: '2024-02-10',
    approvalDate: '2024-02-12',
  },
  {
    id: '2',
    contractId: '1',
    title: 'TikTok Product Showcase',
    platform: 'TikTok',
    type: 'Video',
    dueDate: '2024-02-20',
    status: 'submitted',
    submittedDate: '2024-02-19',
  },
  {
    id: '3',
    contractId: '1',
    title: 'YouTube Short Review',
    platform: 'YouTube',
    type: 'Short',
    dueDate: '2024-03-10',
    status: 'pending',
  },
];

const statusConfig = {
  draft: { label: 'Draft', color: 'bg-slate-500/10 text-slate-400', icon: PenTool },
  pending_signature: {
    label: 'Pending Signature',
    color: 'bg-amber-500/10 text-amber-600',
    icon: Clock,
  },
  signed: { label: 'Signed', color: 'bg-green-500/10 text-green-600', icon: CheckCircle2 },
  expired: { label: 'Expired', color: 'bg-red-500/10 text-red-600', icon: AlertCircle },
  pending: { label: 'Pending', color: 'bg-slate-500/10 text-slate-400', icon: Clock },
  submitted: { label: 'Submitted', color: 'bg-blue-500/10 text-blue-600', icon: FileText },
  approved: { label: 'Approved', color: 'bg-green-500/10 text-green-600', icon: CheckCircle2 },
  revisions_needed: {
    label: 'Revisions Needed',
    color: 'bg-amber-500/10 text-amber-600',
    icon: AlertCircle,
  },
};

export function ContractsAndDeliverables() {
  const [searchTerm, setSearchTerm] = useState('');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [selectedContract, setSelectedContract] = useState<Contract | null>(
    mockContracts[0],
  );
  const [activeTab, setActiveTab] = useState('contracts');

  const filteredContracts = mockContracts.filter((contract) => {
    const matchesSearch =
      contract.campaignName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      contract.creatorName.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesStatus = filterStatus === 'all' || contract.status === filterStatus;
    return matchesSearch && matchesStatus;
  });

  const contractDeliverables = mockDeliverables.filter(
    (d) => d.contractId === selectedContract?.id,
  );

  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-900 via-slate-800 to-slate-900 pb-20">
      {/* Header */}
      <div className="sticky top-0 z-40 border-b border-slate-700/50 bg-slate-900/80 backdrop-blur supports-[backdrop-filter]:bg-slate-900/60 px-4 py-6 sm:px-6">
        <div className="mb-6">
          <h1 className="text-2xl sm:text-3xl font-bold text-white">Contracts & Deliverables</h1>
          <p className="text-sm text-slate-400 mt-1">Manage contracts and track deliverable submissions</p>
        </div>

        {/* Search and Filter */}
        <div className="flex flex-col gap-3 sm:flex-row sm:gap-4">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500" />
            <input
              type="text"
              placeholder="Search contracts..."
              className="w-full pl-10 pr-4 py-2 bg-slate-800 border border-slate-700 rounded-lg text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
          </div>
          <select
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value)}
            className="px-4 py-2 bg-slate-800 border border-slate-700 rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
          >
            <option value="all">All Status</option>
            <option value="signed">Signed</option>
            <option value="pending_signature">Pending Signature</option>
            <option value="draft">Draft</option>
          </select>
        </div>
      </div>

      <div className="px-4 sm:px-6 py-6 grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Contracts List */}
        <div className="lg:col-span-1">
          <div className="space-y-3 max-h-[calc(100vh-200px)] overflow-y-auto">
            {filteredContracts.map((contract) => {
              const StatusIcon =
                statusConfig[contract.status as keyof typeof statusConfig].icon;
              return (
                <button
                  key={contract.id}
                  onClick={() => setSelectedContract(contract)}
                  className={`w-full text-left p-4 rounded-lg border transition-all ${
                    selectedContract?.id === contract.id
                      ? 'bg-slate-700 border-blue-500'
                      : 'bg-slate-800/50 border-slate-700 hover:bg-slate-800'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <img
                      src={contract.creatorImage}
                      alt={contract.creatorName}
                      className="w-10 h-10 rounded-full object-cover"
                    />
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-white text-sm truncate">
                        {contract.creatorName}
                      </h3>
                      <p className="text-xs text-slate-400 truncate">{contract.campaignName}</p>
                      <div className="mt-2">
                        <StatusIcon className="w-3 h-3 inline mr-1" />
                        <span
                          className={`text-xs px-2 py-0.5 rounded ${
                            statusConfig[contract.status as keyof typeof statusConfig].color
                          }`}
                        >
                          {statusConfig[contract.status as keyof typeof statusConfig].label}
                        </span>
                      </div>
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        {/* Contract Details */}
        <div className="lg:col-span-2">
          {selectedContract ? (
            <Card className="bg-slate-800/50 border-slate-700">
              {/* Header */}
              <div className="p-6 border-b border-slate-700">
                <div className="flex items-start justify-between gap-4">
                  <div className="flex items-start gap-4">
                    <img
                      src={selectedContract.creatorImage}
                      alt={selectedContract.creatorName}
                      className="w-16 h-16 rounded-full object-cover"
                    />
                    <div className="flex-1">
                      <h2 className="text-2xl font-bold text-white">
                        {selectedContract.campaignName}
                      </h2>
                      <p className="text-slate-400 mt-1">{selectedContract.creatorName}</p>
                      <div className="mt-3 flex flex-wrap gap-2">
                        <span
                          className={`px-3 py-1 rounded-full text-sm font-medium ${
                            statusConfig[
                              selectedContract.status as keyof typeof statusConfig
                            ].color
                          }`}
                        >
                          {statusConfig[selectedContract.status as keyof typeof statusConfig].label}
                        </span>
                      </div>
                    </div>
                  </div>
                  <div className="flex flex-col gap-2">
                    <Button className="bg-blue-600 hover:bg-blue-700 text-white gap-2">
                      <Eye className="w-4 h-4" />
                      <span className="hidden sm:inline">View Contract</span>
                    </Button>
                    <Button className="bg-slate-700 hover:bg-slate-600 text-white gap-2">
                      <Download className="w-4 h-4" />
                      <span className="hidden sm:inline">Download</span>
                    </Button>
                  </div>
                </div>
              </div>

              {/* Stats */}
              <div className="px-6 py-4 grid grid-cols-2 sm:grid-cols-4 gap-4 border-b border-slate-700">
                <div>
                  <p className="text-slate-400 text-xs">Contract Value</p>
                  <p className="text-lg sm:text-xl font-bold text-white mt-1">
                    ${(selectedContract.value / 1000).toFixed(1)}k
                  </p>
                </div>
                <div>
                  <p className="text-slate-400 text-xs">Deliverables</p>
                  <p className="text-lg sm:text-xl font-bold text-white mt-1">
                    {selectedContract.deliverables}
                  </p>
                </div>
                <div>
                  <p className="text-slate-400 text-xs">Signed Date</p>
                  <p className="text-sm sm:text-base font-semibold text-white mt-1">
                    {selectedContract.signedDate ? (
                      new Date(selectedContract.signedDate).toLocaleDateString()
                    ) : (
                      <span className="text-slate-500">Pending</span>
                    )}
                  </p>
                </div>
                <div>
                  <p className="text-slate-400 text-xs">Expires</p>
                  <p className="text-sm sm:text-base font-semibold text-white mt-1">
                    {new Date(selectedContract.expiryDate).toLocaleDateString()}
                  </p>
                </div>
              </div>

              {/* Tabs */}
              <Tabs defaultValue="deliverables" className="w-full">
                <TabsList className="w-full grid grid-cols-2 gap-0 rounded-none bg-slate-700/50 p-0 border-b border-slate-700">
                  <TabsTrigger
                    value="deliverables"
                    className="rounded-none data-[state=active]:bg-slate-700 data-[state=active]:text-blue-400"
                  >
                    Deliverables
                  </TabsTrigger>
                  <TabsTrigger
                    value="details"
                    className="rounded-none data-[state=active]:bg-slate-700 data-[state=active]:text-blue-400"
                  >
                    Details
                  </TabsTrigger>
                </TabsList>

                <TabsContent value="deliverables" className="p-6 space-y-4">
                  {contractDeliverables.length === 0 ? (
                    <p className="text-center text-slate-400 py-8">No deliverables yet</p>
                  ) : (
                    contractDeliverables.map((deliverable) => {
                      const DelivStatusIcon =
                        statusConfig[deliverable.status as keyof typeof statusConfig].icon;
                      return (
                        <div
                          key={deliverable.id}
                          className="bg-slate-700/30 rounded-lg p-4 border border-slate-700"
                        >
                          <div className="flex items-start justify-between gap-4 mb-3">
                            <div className="flex-1">
                              <h4 className="font-semibold text-white">{deliverable.title}</h4>
                              <div className="flex flex-wrap gap-2 mt-2">
                                <span className="text-xs px-2 py-1 rounded bg-slate-600 text-slate-200">
                                  {deliverable.platform}
                                </span>
                                <span className="text-xs px-2 py-1 rounded bg-slate-600 text-slate-200">
                                  {deliverable.type}
                                </span>
                              </div>
                            </div>
                            <span
                              className={`text-xs px-2 py-1 rounded whitespace-nowrap ${
                                statusConfig[deliverable.status as keyof typeof statusConfig]
                                  .color
                              }`}
                            >
                              {statusConfig[deliverable.status as keyof typeof statusConfig].label}
                            </span>
                          </div>
                          <div className="flex flex-col sm:flex-row sm:items-center gap-3 text-sm text-slate-400 pt-3 border-t border-slate-600">
                            <span className="flex items-center gap-2">
                              <Calendar className="w-4 h-4" />
                              Due: {new Date(deliverable.dueDate).toLocaleDateString()}
                            </span>
                            {deliverable.submittedDate && (
                              <span className="flex items-center gap-2">
                                <CheckCircle2 className="w-4 h-4 text-green-500" />
                                Submitted: {new Date(deliverable.submittedDate).toLocaleDateString()}
                              </span>
                            )}
                          </div>
                        </div>
                      );
                    })
                  )}
                </TabsContent>

                <TabsContent value="details" className="p-6">
                  <div className="space-y-4">
                    <div>
                      <p className="text-slate-400 text-sm">Contract Terms</p>
                      <p className="text-white mt-2">
                        This contract covers {selectedContract.deliverables} deliverables across
                        multiple platforms with a total value of ${selectedContract.value}.
                      </p>
                    </div>
                    <div className="pt-4 border-t border-slate-700">
                      <p className="text-slate-400 text-sm">Payment Schedule</p>
                      <div className="mt-3 space-y-2">
                        <div className="flex justify-between items-center text-sm">
                          <span className="text-white">50% Upon Signing</span>
                          <span className="font-semibold text-green-400">
                            ${selectedContract.value / 2}
                          </span>
                        </div>
                        <div className="flex justify-between items-center text-sm">
                          <span className="text-white">50% Upon Completion</span>
                          <span className="font-semibold text-slate-400">
                            ${selectedContract.value / 2}
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>
                </TabsContent>
              </Tabs>
            </Card>
          ) : (
            <Card className="bg-slate-800/50 border-slate-700 flex items-center justify-center h-full">
              <div className="text-center">
                <FileText className="w-12 h-12 text-slate-600 mx-auto mb-3" />
                <p className="text-slate-400">No contracts available</p>
              </div>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
