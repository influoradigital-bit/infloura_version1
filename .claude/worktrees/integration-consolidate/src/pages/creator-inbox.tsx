import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { Textarea } from '@/components/ui/textarea';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { CollaborationTimeline } from '@/components/brand/timeline/collaboration-timeline';
import {
  Building2,
  Clock,
  IndianRupee,
  Calendar,
  CheckCircle2,
  X,
  MessageCircle,
  ChevronRight,
  Sparkles,
  Shield,
  TrendingUp,
  FileText,
  Star,
  AlertCircle,
  Loader2,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { addPersistedMessage, toDealRoomId } from '@/lib/creator-deal-messages';
import { setContractStatus } from '@/lib/creator-contract-store';
import type { Collaboration } from '@/lib/types';
import { toast } from '@/hooks/use-toast';

// Mock proposals data
const mockProposals = [
  {
    id: '1',
    brandName: 'Nykaa Fashion',
    brandLogo: '',
    brandVerified: true,
    brandRating: 4.8,
    brandPaymentSpeed: '24h avg',
    campaignTitle: 'Summer Collection Launch',
    deliverables: [
      { type: 'REEL', count: 2 },
      { type: 'STORY', count: 3 },
    ],
    budget: 45000,
    deadline: '2026-06-15',
    usageRights: '3 months',
    exclusivity: false,
    message: 'Hi Priya! We loved your recent fashion content and think you would be perfect for our summer collection launch. Looking forward to collaborating!',
    receivedAt: new Date(Date.now() - 1000 * 60 * 25), // 25 mins ago
    expiresAt: new Date(Date.now() + 1000 * 60 * 60 * 24 * 7 - 1000 * 60 * 25), // 7 days from received
    status: 'PENDING',
    escrowFunded: true,
  },
  {
    id: '2',
    brandName: 'BoAt Lifestyle',
    brandLogo: '',
    brandVerified: true,
    brandRating: 4.5,
    brandPaymentSpeed: '48h avg',
    campaignTitle: 'New Earbuds Launch',
    deliverables: [
      { type: 'VIDEO', count: 1 },
      { type: 'POST', count: 2 },
    ],
    budget: 75000,
    deadline: '2026-06-20',
    usageRights: '6 months',
    exclusivity: true,
    message: 'We are launching our new premium earbuds and would love to have you review them for your audience.',
    receivedAt: new Date(Date.now() - 1000 * 60 * 60 * 2), // 2 hours ago
    expiresAt: new Date(Date.now() + 1000 * 60 * 60 * 24 * 7 - 1000 * 60 * 60 * 2),
    status: 'PENDING',
    escrowFunded: true,
  },
  {
    id: '3',
    brandName: 'Mamaearth',
    brandLogo: '',
    brandVerified: true,
    brandRating: 4.6,
    brandPaymentSpeed: '24h avg',
    campaignTitle: 'Skincare Routine Series',
    deliverables: [
      { type: 'REEL', count: 3 },
    ],
    budget: 35000,
    deadline: '2026-06-10',
    usageRights: '3 months',
    exclusivity: false,
    message: 'Would love to feature you in our skincare routine series!',
    receivedAt: new Date(Date.now() - 1000 * 60 * 60 * 24), // 1 day ago
    expiresAt: new Date(Date.now() + 1000 * 60 * 60 * 24 * 6),
    status: 'PENDING',
    escrowFunded: true,
  },
];

const mockOpportunities = [
  {
    id: 'opp-1',
    brandName: 'Sugar Cosmetics',
    campaignTitle: 'Festival Makeup Challenge',
    budget: '₹30K - ₹50K',
    deliverables: '2 Reels',
    deadline: '2026-06-25',
    applicants: 45,
  },
  {
    id: 'opp-2',
    brandName: 'Plum Goodness',
    campaignTitle: 'Clean Beauty Campaign',
    budget: '₹25K - ₹40K',
    deliverables: '1 Video + 3 Stories',
    deadline: '2026-06-30',
    applicants: 32,
  },
];

function formatINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatTimeAgo(date: Date): string {
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const minutes = Math.floor(diff / (1000 * 60));
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));

  if (minutes < 60) return `${minutes}m ago`;
  if (hours < 24) return `${hours}h ago`;
  return `${days}d ago`;
}

function formatExpiresIn(expiresAt: Date): { label: string; urgent: boolean } {
  const now = new Date();
  const diff = expiresAt.getTime() - now.getTime();
  if (diff <= 0) return { label: 'Expired', urgent: true };
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));
  if (hours < 24) return { label: `Expires in ${hours}h`, urgent: true };
  return { label: `Expires in ${days}d`, urgent: days <= 2 };
}

export default function CreatorInboxPage() {
  const navigate = useNavigate();
  const [proposals, setProposals] = React.useState(mockProposals);
  const [selectedProposal, setSelectedProposal] = React.useState<typeof mockProposals[0] | null>(null);
  const [showDeclineDialog, setShowDeclineDialog] = React.useState(false);
  const [showCounterDialog, setShowCounterDialog] = React.useState(false);
  const [counterAmount, setCounterAmount] = React.useState('');
  const [counterAmountError, setCounterAmountError] = React.useState('');
  const [counterMessage, setCounterMessage] = React.useState('');
  const [declineReason, setDeclineReason] = React.useState('');
  const [isLoading, setIsLoading] = React.useState(false);
  const [selectedOpportunity, setSelectedOpportunity] = React.useState<typeof mockOpportunities[0] | null>(null);
  const [applyMessage, setApplyMessage] = React.useState('');
  const [applyRate, setApplyRate] = React.useState('');
  const [applyRateError, setApplyRateError] = React.useState('');
  const [applyMessageError, setApplyMessageError] = React.useState('');
  const [applyAvailableFrom, setApplyAvailableFrom] = React.useState('');
  const [applyDeliverables, setApplyDeliverables] = React.useState<{ type: string; qty: string }[]>([{ type: 'REEL', qty: '1' }]);
  const [applyPortfolioLinks, setApplyPortfolioLinks] = React.useState<string[]>(['']);
  const [showMessageDialog, setShowMessageDialog] = React.useState(false);
  const [messageProposal, setMessageProposal] = React.useState<typeof mockProposals[0] | null>(null);
  const [brandMessage, setBrandMessage] = React.useState('');
  const [showTimeline, setShowTimeline] = React.useState(false);
  const [timelineProposal, setTimelineProposal] = React.useState<typeof mockProposals[0] | null>(null);
  const [showContractSheet, setShowContractSheet] = React.useState(false);
  const [contractProposal, setContractProposal] = React.useState<typeof mockProposals[0] | null>(null);

  // Accept → open the Deal Room for this specific deal
  const handleAccept = async () => {
    if (!selectedProposal) return;
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    const dealId = toDealRoomId(selectedProposal.id);
    setIsLoading(false);
    setSelectedProposal(null);
    navigate(`/creator/chat?deal=${dealId}`);
  };

  // Decline → remove from list, stay on Inbox, show confirmation toast
  const handleDecline = async () => {
    if (!selectedProposal) return;
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 800));
    setIsLoading(false);
    setProposals((prev) => prev.filter((p) => p.id !== selectedProposal.id));
    setShowDeclineDialog(false);
    setSelectedProposal(null);
    setDeclineReason('');
    toast({
      title: 'Proposal declined',
      description: `You have declined the proposal from ${selectedProposal.brandName}.`,
    });
  };

  // Apply to open opportunity
  const handleApply = async () => {
    let hasError = false;
    if (!applyRate.trim()) { setApplyRateError('Please enter your rate'); hasError = true; }
    else if (isNaN(Number(applyRate)) || Number(applyRate) <= 0) { setApplyRateError('Please enter a valid amount'); hasError = true; }
    else setApplyRateError('');
    if (!applyMessage.trim()) { setApplyMessageError('Please write a short cover letter'); hasError = true; }
    else setApplyMessageError('');
    if (hasError) return;
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    setIsLoading(false);
    setSelectedOpportunity(null);
    setApplyMessage('');
    setApplyRate('');
    setApplyAvailableFrom('');
    setApplyDeliverables([{ type: 'REEL', qty: '1' }]);
    setApplyPortfolioLinks(['']);
    toast({
      title: 'Application submitted',
      description: 'Your application has been sent to the brand.',
    });
  };

  const resetApplyDialog = () => {
    setSelectedOpportunity(null);
    setApplyMessage('');
    setApplyRate('');
    setApplyRateError('');
    setApplyMessageError('');
    setApplyAvailableFrom('');
    setApplyDeliverables([{ type: 'REEL', qty: '1' }]);
    setApplyPortfolioLinks(['']);
  };

  const handleSendMessage = async () => {
    if (!brandMessage.trim() || !messageProposal) return;
    setIsLoading(true);
    const dealId = toDealRoomId(messageProposal.id);
    const brandName = messageProposal.brandName;
    addPersistedMessage(dealId, brandMessage, 'creator');
    await new Promise((resolve) => setTimeout(resolve, 400));
    setIsLoading(false);
    setShowMessageDialog(false);
    setSelectedProposal(null);
    setMessageProposal(null);
    setBrandMessage('');
    toast({
      title: 'Message sent',
      description: `Your message to ${brandName} is in the Deal Room.`,
    });
    navigate(`/creator/chat?deal=${dealId}`);
  };

  // Counter → validate amount then navigate to Deal Room
  const handleCounter = async () => {
    if (!counterAmount.trim()) {
      setCounterAmountError('Please enter your counter amount');
      return;
    }
    const parsed = Number(counterAmount);
    if (isNaN(parsed) || parsed <= 0) {
      setCounterAmountError('Please enter a valid amount');
      return;
    }
    if (selectedProposal && parsed < selectedProposal.budget * 0.5) {
      setCounterAmountError('Counter amount seems too low compared to the original offer');
      return;
    }
    setCounterAmountError('');
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 800));
    setIsLoading(false);
    const dealId = selectedProposal ? toDealRoomId(selectedProposal.id) : '';
    setShowCounterDialog(false);
    setSelectedProposal(null);
    setCounterAmount('');
    setCounterMessage('');
    navigate(`/creator/chat?deal=${dealId}`);
  };

  const freshProposals = proposals.filter(
    (p) => new Date().getTime() - p.receivedAt.getTime() < 1000 * 60 * 30
  );

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-2xl">
        {/* Welcome Section */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Good morning, Priya!</h1>
          <p className="text-muted-foreground">
            You have {mockProposals.length} new proposals waiting
          </p>
        </div>

        {/* Quick Stats */}
        <div className="grid grid-cols-3 gap-3 mb-6">
          <Card className="bg-gradient-to-br from-primary to-accent text-white">
            <CardContent className="p-4 text-center">
              <p className="text-2xl font-bold">{mockProposals.length}</p>
              <p className="text-xs text-white/80">New Proposals</p>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4 text-center">
              <p className="text-2xl font-bold">2</p>
              <p className="text-xs text-muted-foreground">Active Collabs</p>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4 text-center">
              <p className="text-2xl font-bold">₹1.2L</p>
              <p className="text-xs text-muted-foreground">Pending Payout</p>
            </CardContent>
          </Card>
        </div>

        {/* Tabs */}
        <Tabs defaultValue="proposals" className="space-y-4">
          <TabsList className="w-full grid grid-cols-2">
            <TabsTrigger value="proposals" className="relative">
              Proposals
              {mockProposals.length > 0 && (
                <Badge className="ml-2 h-5 px-1.5 bg-violet-600">{mockProposals.length}</Badge>
              )}
            </TabsTrigger>
            <TabsTrigger value="opportunities">
              Opportunities
            </TabsTrigger>
          </TabsList>

          {/* Proposals Tab */}
          <TabsContent value="proposals" className="space-y-4">
            {/* Fresh Badge */}
            {freshProposals.length > 0 && (
              <div className="flex items-center gap-2 text-sm">
                <Sparkles className="h-4 w-4 text-amber-500" />
                <span className="text-amber-700 font-medium">
                  {freshProposals.length} fresh proposal{freshProposals.length > 1 ? 's' : ''} (under 30 min)
                </span>
              </div>
            )}

            {/* Proposal Cards */}
            <div className="space-y-3">
              {proposals.map((proposal) => {
                const isFresh = new Date().getTime() - proposal.receivedAt.getTime() < 1000 * 60 * 30;
                return (
                  <Card
                    key={proposal.id}
                    className={cn(
                      'cursor-pointer transition-all hover:shadow-md',
                      isFresh && 'border-stage-negotiating-border bg-stage-negotiating'
                    )}
                    onClick={() => setSelectedProposal(proposal)}
                  >
                    <CardContent className="p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex items-start gap-3 flex-1 min-w-0">
                          <Avatar className="h-12 w-12 flex-shrink-0">
                            <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold">
                              {proposal.brandName.charAt(0)}
                            </AvatarFallback>
                          </Avatar>
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="font-semibold truncate">{proposal.brandName}</span>
                              {proposal.brandVerified && (
                                <Badge variant="secondary" className="text-xs h-5 px-1.5">
                                  <CheckCircle2 className="h-3 w-3 mr-0.5 text-blue-500" />
                                  Verified
                                </Badge>
                              )}
                              {isFresh && (
                                <Badge className="bg-amber-500 text-white text-xs h-5 px-1.5">
                                  Fresh
                                </Badge>
                              )}
                            </div>
                            <p className="text-sm text-muted-foreground truncate">
                              {proposal.campaignTitle}
                            </p>
                            <div className="flex items-center gap-3 mt-2 text-sm">
                              <span className="font-semibold text-stage-approved-fg">
                                {formatINR(proposal.budget)}
                              </span>
                              <span className="text-muted-foreground">
                                {proposal.deliverables.map((d) => `${d.count} ${d.type}`).join(' + ')}
                              </span>
                            </div>
                          </div>
                        </div>
                        <div className="flex flex-col items-end gap-1 flex-shrink-0">
                          <span className="text-xs text-muted-foreground">
                            {formatTimeAgo(proposal.receivedAt)}
                          </span>
                          {(() => {
                            const exp = formatExpiresIn(proposal.expiresAt);
                            return (
                              <span className={`text-xs font-medium ${exp.urgent ? 'text-red-500' : 'text-muted-foreground'}`}>
                                {exp.label}
                              </span>
                            );
                          })()}
                          <ChevronRight className="h-5 w-5 text-muted-foreground" />
                        </div>
                      </div>
                      
                      {/* Escrow indicator */}
                      {proposal.escrowFunded && (
                        <div className="mt-3 flex items-center gap-1.5 text-xs text-stage-approved-fg bg-green-50 rounded-full px-2.5 py-1 w-fit">
                          <Shield className="h-3 w-3" />
                          <span>Payment secured in escrow</span>
                        </div>
                      )}
                    </CardContent>
                  </Card>
                );
              })}
            </div>
          </TabsContent>

          {/* Opportunities Tab */}
          <TabsContent value="opportunities" className="space-y-4">
            <p className="text-sm text-muted-foreground">
              Open campaigns you can apply to
            </p>
            <div className="space-y-3">
              {mockOpportunities.map((opp) => (
                <Card key={opp.id} className="cursor-pointer hover:shadow-md transition-all">
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="font-semibold">{opp.brandName}</p>
                        <p className="text-sm text-muted-foreground">{opp.campaignTitle}</p>
                        <div className="flex items-center gap-3 mt-2 text-sm">
                          <span className="font-medium text-stage-approved-fg">{opp.budget}</span>
                          <span className="text-muted-foreground">{opp.deliverables}</span>
                        </div>
                      </div>
                      <div className="text-right">
                        <p className="text-xs text-muted-foreground">{opp.applicants} applied</p>
                        <Button 
                          size="sm" 
                          className="mt-2"
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedOpportunity(opp);
                            setApplyRate(opp.budget.split(' - ')[0].replace('₹', '').replace('K', '000'));
                          }}
                        >
                          Apply
                        </Button>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </TabsContent>
        </Tabs>
      </div>

      {/* Proposal Detail Dialog */}
      <Dialog 
        open={!!selectedProposal} 
        onOpenChange={(open) => {
          if (!open) {
            setSelectedProposal(null);
            setBrandMessage('');
          }
        }}
      >
        <DialogContent className="max-w-md max-h-[90vh] overflow-y-auto">
          {selectedProposal && (
            <>
              <DialogHeader>
                <div className="flex items-center gap-3">
                  <Avatar className="h-12 w-12">
                    <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold">
                      {selectedProposal.brandName.charAt(0)}
                    </AvatarFallback>
                  </Avatar>
                  <div>
                    <DialogTitle className="flex items-center gap-2">
                      {selectedProposal.brandName}
                      {selectedProposal.brandVerified && (
                        <CheckCircle2 className="h-4 w-4 text-blue-500" />
                      )}
                    </DialogTitle>
                    <DialogDescription>
                      {selectedProposal.campaignTitle}
                    </DialogDescription>
                  </div>
                </div>
              </DialogHeader>

              <div className="space-y-4">
                {/* Brand Trust Info */}
                <div className="flex items-center gap-4 text-sm bg-muted/50 rounded-lg p-3">
                  <div className="flex items-center gap-1">
                    <Star className="h-4 w-4 text-amber-500" />
                    <span>{selectedProposal.brandRating}</span>
                  </div>
                  <div className="flex items-center gap-1">
                    <TrendingUp className="h-4 w-4 text-green-500" />
                    <span>{selectedProposal.brandPaymentSpeed}</span>
                  </div>
                  {selectedProposal.escrowFunded && (
                    <div className="flex items-center gap-1 text-stage-approved-fg">
                      <Shield className="h-4 w-4" />
                      <span>Escrow funded</span>
                    </div>
                  )}
                </div>

                {/* Budget */}
                <div className="bg-stage-approved border border-stage-approved-border rounded-lg p-4 text-center">
                  <p className="text-sm text-muted-foreground">Your Earnings</p>
                  <p className="text-3xl font-bold text-stage-approved-fg">
                    {formatINR(selectedProposal.budget)}
                  </p>
                  <p className="text-xs text-muted-foreground mt-1">
                    After platform fee (10%) + TDS (1%)
                  </p>
                </div>

                {/* Expiry */}
                {(() => {
                  const exp = formatExpiresIn(selectedProposal.expiresAt);
                  return (
                    <div className={`flex items-center gap-2 rounded-lg p-3 text-sm ${exp.urgent ? 'bg-red-50 border border-red-200' : 'bg-muted/50'}`}>
                      <Clock className={`h-4 w-4 flex-shrink-0 ${exp.urgent ? 'text-red-500' : 'text-muted-foreground'}`} />
                      <span className={exp.urgent ? 'text-red-700 font-medium' : 'text-muted-foreground'}>
                        {exp.label} — respond before{' '}
                        {selectedProposal.expiresAt.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                      </span>
                    </div>
                  );
                })()}

                {/* Deliverables */}
                <div>
                  <h4 className="font-medium mb-2">Deliverables</h4>
                  <div className="flex flex-wrap gap-2">
                    {selectedProposal.deliverables.map((d, idx) => (
                      <Badge key={idx} variant="outline" className="px-3 py-1">
                        {d.count}x {d.type}
                      </Badge>
                    ))}
                  </div>
                </div>

                {/* Terms */}
                <div className="grid grid-cols-2 gap-3 text-sm">
                  <div className="bg-muted/50 rounded-lg p-3">
                    <p className="text-muted-foreground text-xs">Deadline</p>
                    <p className="font-medium flex items-center gap-1">
                      <Calendar className="h-4 w-4" />
                      {new Date(selectedProposal.deadline).toLocaleDateString('en-IN', {
                        day: 'numeric',
                        month: 'short',
                        year: 'numeric',
                      })}
                    </p>
                  </div>
                  <div className="bg-muted/50 rounded-lg p-3">
                    <p className="text-muted-foreground text-xs">Usage Rights</p>
                    <p className="font-medium">{selectedProposal.usageRights}</p>
                  </div>
                </div>

                {selectedProposal.exclusivity && (
                  <div className="flex items-start gap-2 bg-amber-50 border border-stage-negotiating-border rounded-lg p-3">
                    <AlertCircle className="h-4 w-4 text-stage-negotiating-fg flex-shrink-0 mt-0.5" />
                    <div>
                      <p className="text-sm font-medium text-amber-800">Exclusivity Required</p>
                      <p className="text-xs text-amber-700">
                        No competitor collaborations during campaign period
                      </p>
                    </div>
                  </div>
                )}

                {/* Message */}
                {selectedProposal.message && (
                  <div>
                    <h4 className="font-medium mb-2">Message from Brand</h4>
                    <p className="text-sm text-muted-foreground bg-muted/50 rounded-lg p-3">
                      {selectedProposal.message}
                    </p>
                  </div>
                )}
              </div>

              <DialogFooter className="flex-col gap-2 sm:flex-col">
                <div className="flex items-center gap-2 w-full mb-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setContractProposal(selectedProposal);
                      setShowContractSheet(true);
                    }}
                    className="flex-1"
                  >
                    <FileText className="h-4 w-4 mr-2" />
                    View Contract
                  </Button>
                </div>
                <Button
                  className="w-full bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700"
                  onClick={handleAccept}
                  disabled={isLoading}
                >
                  {isLoading ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <>
                      <CheckCircle2 className="h-4 w-4 mr-2" />
                      Accept Proposal
                    </>
                  )}
                </Button>
                <div className="grid grid-cols-3 gap-2 w-full">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => {
                      setMessageProposal(selectedProposal);
                      setBrandMessage('');
                      setShowMessageDialog(true);
                    }}
                  >
                    <MessageCircle className="h-4 w-4 mr-2" />
                    Message
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setShowCounterDialog(true)}
                  >
                    <IndianRupee className="h-4 w-4 mr-2" />
                    Counter
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    className="text-stage-disputed-fg hover:text-red-700 hover:bg-red-50"
                    onClick={() => setShowDeclineDialog(true)}
                  >
                    <X className="h-4 w-4 mr-2" />
                    Decline
                  </Button>
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="w-full text-muted-foreground"
                  onClick={() => {
                    setTimelineProposal(selectedProposal);
                    setShowTimeline(true);
                  }}
                >
                  View chat timeline
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* Message Dialog */}
      <Dialog open={showMessageDialog} onOpenChange={setShowMessageDialog}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Message Brand</DialogTitle>
            <DialogDescription>
              Ask a question or request more details from {messageProposal?.brandName}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            {/* Brand Info */}
            <div className="flex items-center gap-3 bg-muted/50 rounded-lg p-3">
              <Avatar className="h-10 w-10">
                <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold">
                  {messageProposal?.brandName.charAt(0)}
                </AvatarFallback>
              </Avatar>
              <div>
                <p className="font-medium">{messageProposal?.brandName}</p>
                <p className="text-sm text-muted-foreground">{messageProposal?.campaignTitle}</p>
              </div>
            </div>

            {/* Message Input */}
            <div className="space-y-2">
              <Label htmlFor="brand-message">Your Message</Label>
              <Textarea
                id="brand-message"
                placeholder="e.g., Could you share more details about the timeline? What's the expected turnaround for revisions?"
                value={brandMessage}
                onChange={(e) => setBrandMessage(e.target.value)}
                rows={4}
              />
            </div>

            {/* Quick Questions */}
            <div className="space-y-2">
              <p className="text-sm text-muted-foreground">Quick questions:</p>
              <div className="flex flex-wrap gap-2">
                {[
                  'Timeline details?',
                  'Revision policy?',
                  'Content guidelines?',
                  'Payment terms?',
                ].map((q) => (
                  <Button
                    key={q}
                    variant="outline"
                    size="sm"
                    className="text-xs"
                    onClick={() => setBrandMessage((prev) => prev ? `${prev}\n${q}` : q)}
                  >
                    {q}
                  </Button>
                ))}
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowMessageDialog(false)}>
              Cancel
            </Button>
            <Button 
              onClick={handleSendMessage}
              disabled={isLoading || !brandMessage.trim()}
              className="bg-primary hover:bg-primary/90"
            >
              {isLoading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <>
                  <MessageCircle className="h-4 w-4 mr-2" />
                  Send Message
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Counter Dialog */}
      <Dialog open={showCounterDialog} onOpenChange={(open) => { setShowCounterDialog(open); if (!open) { setCounterAmount(''); setCounterAmountError(''); setCounterMessage(''); } }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Send Counter Offer</DialogTitle>
            <DialogDescription>
              Propose different terms to {selectedProposal?.brandName}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>
                Your Counter Amount <span className="text-red-400">*</span>
              </Label>
              <div className="relative">
                <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  type="number"
                  placeholder={selectedProposal?.budget.toString()}
                  value={counterAmount}
                  onChange={(e) => {
                    setCounterAmount(e.target.value);
                    if (counterAmountError) setCounterAmountError('');
                  }}
                  className={`pl-9 ${counterAmountError ? 'border-red-500' : ''}`}
                />
              </div>
              <p className="text-xs text-muted-foreground">
                Original offer: {selectedProposal && formatINR(selectedProposal.budget)}
              </p>
              {counterAmountError && (
                <p className="text-red-400 text-xs">{counterAmountError}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label>Message (Optional)</Label>
              <Textarea
                placeholder="Explain why you're countering..."
                value={counterMessage}
                onChange={(e) => setCounterMessage(e.target.value)}
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setShowCounterDialog(false); setCounterAmountError(''); }}>
              Cancel
            </Button>
            <Button onClick={handleCounter} disabled={isLoading}>
              {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Send Counter'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Decline Dialog */}
      <Dialog open={showDeclineDialog} onOpenChange={setShowDeclineDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Decline Proposal</DialogTitle>
            <DialogDescription>
              Let {selectedProposal?.brandName} know why you&apos;re declining
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>Reason (Optional)</Label>
              <Textarea
                placeholder="e.g., Budget too low, timeline doesn't work..."
                value={declineReason}
                onChange={(e) => setDeclineReason(e.target.value)}
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDeclineDialog(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDecline}
              disabled={isLoading}
            >
              {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Decline'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Apply to Opportunity Dialog */}
      <Dialog open={!!selectedOpportunity} onOpenChange={(open) => { if (!open) resetApplyDialog(); }}>
        <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
          {selectedOpportunity && (
            <>
              <DialogHeader>
                <DialogTitle>Apply to Campaign</DialogTitle>
                <DialogDescription>
                  {selectedOpportunity.brandName} — {selectedOpportunity.campaignTitle}
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-5 py-2">
                {/* Campaign Info */}
                <div className="bg-muted/50 rounded-lg p-3 flex items-center justify-between text-sm">
                  <div>
                    <p className="font-semibold">{selectedOpportunity.brandName}</p>
                    <p className="text-muted-foreground">{selectedOpportunity.campaignTitle}</p>
                  </div>
                  <div className="text-right">
                    <p className="font-medium text-stage-approved-fg">{selectedOpportunity.budget}</p>
                    <p className="text-xs text-muted-foreground">{selectedOpportunity.deliverables}</p>
                  </div>
                </div>

                {/* Your Rate */}
                <div className="space-y-1.5">
                  <Label htmlFor="apply-rate">
                    Your Rate <span className="text-red-400">*</span>
                  </Label>
                  <div className="relative">
                    <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="apply-rate"
                      type="number"
                      placeholder="Enter your rate"
                      value={applyRate}
                      onChange={(e) => { setApplyRate(e.target.value); if (applyRateError) setApplyRateError(''); }}
                      className={`pl-9 ${applyRateError ? 'border-red-500' : ''}`}
                    />
                  </div>
                  <p className="text-xs text-muted-foreground">Campaign budget: {selectedOpportunity.budget}</p>
                  {applyRateError && <p className="text-red-400 text-xs flex items-center gap-1"><AlertCircle className="h-3 w-3" />{applyRateError}</p>}
                </div>

                {/* Proposed Deliverables */}
                <div className="space-y-1.5">
                  <Label>Proposed Deliverables</Label>
                  <div className="space-y-2">
                    {applyDeliverables.map((d, idx) => (
                      <div key={idx} className="flex items-center gap-2">
                        <select
                          value={d.type}
                          onChange={(e) => setApplyDeliverables(prev => prev.map((item, i) => i === idx ? { ...item, type: e.target.value } : item))}
                          className="flex-1 h-9 rounded-md border border-input bg-background px-3 text-sm"
                        >
                          {['REEL', 'POST', 'STORY', 'VIDEO', 'LIVE_STREAM', 'ARTICLE'].map(t => (
                            <option key={t} value={t}>{t}</option>
                          ))}
                        </select>
                        <Input
                          type="number"
                          min="1"
                          value={d.qty}
                          onChange={(e) => setApplyDeliverables(prev => prev.map((item, i) => i === idx ? { ...item, qty: e.target.value } : item))}
                          className="w-20"
                          placeholder="Qty"
                        />
                        {applyDeliverables.length > 1 && (
                          <Button variant="ghost" size="icon" className="h-9 w-9" onClick={() => setApplyDeliverables(prev => prev.filter((_, i) => i !== idx))}>
                            <X className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    ))}
                    <Button variant="outline" size="sm" onClick={() => setApplyDeliverables(prev => [...prev, { type: 'POST', qty: '1' }])}>
                      + Add Deliverable
                    </Button>
                  </div>
                </div>

                {/* Available From */}
                <div className="space-y-1.5">
                  <Label htmlFor="available-from">Available From (Optional)</Label>
                  <Input
                    id="available-from"
                    type="date"
                    value={applyAvailableFrom}
                    onChange={(e) => setApplyAvailableFrom(e.target.value)}
                    min={new Date().toISOString().split('T')[0]}
                  />
                </div>

                {/* Cover Letter */}
                <div className="space-y-1.5">
                  <Label htmlFor="apply-message">
                    Cover Letter <span className="text-red-400">*</span>
                  </Label>
                  <Textarea
                    id="apply-message"
                    placeholder="Tell the brand why you're a great fit — mention relevant past work, your audience alignment, and your creative angle for this campaign..."
                    value={applyMessage}
                    onChange={(e) => { setApplyMessage(e.target.value); if (applyMessageError) setApplyMessageError(''); }}
                    rows={4}
                    className={applyMessageError ? 'border-red-500' : ''}
                  />
                  {applyMessageError && <p className="text-red-400 text-xs flex items-center gap-1"><AlertCircle className="h-3 w-3" />{applyMessageError}</p>}
                </div>

                {/* Portfolio Links */}
                <div className="space-y-1.5">
                  <Label>Portfolio Links (Optional)</Label>
                  <div className="space-y-2">
                    {applyPortfolioLinks.map((link, idx) => (
                      <div key={idx} className="flex items-center gap-2">
                        <Input
                          placeholder="https://instagram.com/p/example..."
                          value={link}
                          onChange={(e) => setApplyPortfolioLinks(prev => prev.map((l, i) => i === idx ? e.target.value : l))}
                          className="flex-1"
                        />
                        {applyPortfolioLinks.length > 1 && (
                          <Button variant="ghost" size="icon" className="h-9 w-9" onClick={() => setApplyPortfolioLinks(prev => prev.filter((_, i) => i !== idx))}>
                            <X className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    ))}
                    {applyPortfolioLinks.length < 3 && (
                      <Button variant="outline" size="sm" onClick={() => setApplyPortfolioLinks(prev => [...prev, ''])}>
                        + Add Link
                      </Button>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground">Share links to relevant past work (max 3)</p>
                </div>

                <div className="text-xs text-muted-foreground flex items-center gap-1">
                  <Clock className="h-3 w-3" />
                  <span>{selectedOpportunity.applicants} creators have already applied</span>
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={resetApplyDialog}>Cancel</Button>
                <Button onClick={handleApply} disabled={isLoading} className="bg-primary hover:bg-primary/90">
                  {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Submit Application'}
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>


      {/* Collaboration Timeline Sheet */}
      <Sheet open={showTimeline} onOpenChange={setShowTimeline}>
        <SheetContent side="right" className="w-full sm:w-[540px] sm:max-w-[540px] p-0 flex flex-col">
          <SheetHeader className="px-6 py-4 border-b">
            <SheetTitle className="flex items-center gap-3">
              <Avatar className="h-8 w-8">
                <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold text-sm">
                  {timelineProposal?.brandName.charAt(0)}
                </AvatarFallback>
              </Avatar>
              {timelineProposal?.brandName} - Chat & Timeline
            </SheetTitle>
          </SheetHeader>
          {timelineProposal && (
            <div className="flex-1 overflow-hidden px-6 py-4">
              <CollaborationTimeline
                collaboration={{
                  id: timelineProposal.id,
                  creatorId: 'creator-1',
                  creatorName: 'Priya Sharma',
                } as Collaboration}
                currentUserType="creator"
                peerName={timelineProposal.brandName}
              />
            </div>
          )}
        </SheetContent>
      </Sheet>

      {/* Contract Detail Sheet */}
      <Sheet open={showContractSheet} onOpenChange={setShowContractSheet}>
        <SheetContent side="right" className="w-full sm:w-[480px] sm:max-w-[480px]">
          <SheetHeader>
            <SheetTitle>Contract Details</SheetTitle>
          </SheetHeader>
          {contractProposal && (
            <div className="mt-6 space-y-6">
              {/* Parties */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Parties</h4>
                <div className="space-y-2">
                  <div className="flex items-center justify-between p-3 bg-muted/50 rounded-lg">
                    <span className="text-sm text-muted-foreground">Brand</span>
                    <span className="text-sm font-medium">{contractProposal.brandName}</span>
                  </div>
                  <div className="flex items-center justify-between p-3 bg-muted/50 rounded-lg">
                    <span className="text-sm text-muted-foreground">Creator</span>
                    <span className="text-sm font-medium">Priya Sharma</span>
                  </div>
                </div>
              </div>

              {/* Campaign Details */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Campaign</h4>
                <div className="p-3 bg-muted/50 rounded-lg space-y-2">
                  <p className="font-medium">{contractProposal.campaignTitle}</p>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Compensation</span>
                    <span className="font-semibold text-stage-approved-fg">{formatINR(contractProposal.budget)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Deadline</span>
                    <span>{new Date(contractProposal.deadline).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Usage Rights</span>
                    <span>{contractProposal.usageRights}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Exclusivity</span>
                    <span>{contractProposal.exclusivity ? 'Yes' : 'No'}</span>
                  </div>
                </div>
              </div>

              {/* Deliverables */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Deliverables</h4>
                <div className="space-y-2">
                  {contractProposal.deliverables.map((d, idx) => (
                    <div key={idx} className="flex items-center justify-between p-3 bg-muted/50 rounded-lg">
                      <span className="text-sm">{d.count}x {d.type}</span>
                      <Badge variant="outline">Required</Badge>
                    </div>
                  ))}
                </div>
              </div>

              {/* Payment Terms */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Payment Terms</h4>
                <div className="p-3 bg-stage-approved border border-stage-approved-border rounded-lg space-y-2">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Total Amount</span>
                    <span className="font-bold text-stage-approved-fg">{formatINR(contractProposal.budget)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Platform Fee (10%)</span>
                    <span>{formatINR(contractProposal.budget * 0.1)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">TDS (1%)</span>
                    <span>{formatINR(contractProposal.budget * 0.01)}</span>
                  </div>
                  <div className="border-t border-green-300 pt-2 flex items-center justify-between text-sm">
                    <span className="font-medium">Your Payout</span>
                    <span className="font-bold text-stage-approved-fg">{formatINR(contractProposal.budget * 0.89)}</span>
                  </div>
                  <div className="flex items-center gap-1.5 text-xs text-stage-approved-fg">
                    <Shield className="h-3 w-3" />
                    <span>Escrow funded - payment secured</span>
                  </div>
                </div>
              </div>

              {/* Actions */}
              <div className="flex flex-col gap-2">
                <Button
                  className="w-full bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700"
                  onClick={async () => {
                    setShowContractSheet(false);
                    const dealId = toDealRoomId(contractProposal.id);
                    setContractStatus(dealId, 'brand_signed');
                    await handleAccept();
                  }}
                  disabled={isLoading}
                >
                  {isLoading ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <>
                      <CheckCircle2 className="h-4 w-4 mr-2" />
                      Accept proposal
                    </>
                  )}
                </Button>
                <Button
                  variant="outline"
                  className="w-full"
                  onClick={() => {
                    setShowContractSheet(false);
                    navigate(`/creator/chat?deal=${toDealRoomId(contractProposal.id)}&tab=contract`);
                  }}
                >
                  <FileText className="h-4 w-4 mr-2" />
                  Review & sign in Deal Room
                </Button>
                <Button variant="ghost" onClick={() => setShowContractSheet(false)}>
                  Close
                </Button>
              </div>
            </div>
          )}
        </SheetContent>
      </Sheet>


    </CreatorLayout>
  );
}
