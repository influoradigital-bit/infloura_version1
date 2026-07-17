import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { api, isApiLive, ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Progress } from '@/components/ui/progress';
import { Textarea } from '@/components/ui/textarea';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  FileText,
  Download,
  Eye,
  CheckCircle2,
  Clock,
  AlertCircle,
  PenTool,
  Calendar,
  User,
  MessageSquare,
  Shield,
  Lock,
  Unlock,
  Send,
  Image,
  Video,
  Play,
  ExternalLink,
  ThumbsUp,
  ThumbsDown,
  RotateCcw,
  Hash,
  DollarSign,
  ChevronRight,
  Sparkles,
  AlertTriangle,
  CheckCheck,
  XCircle,
  FileSignature,
  Pen,
  Loader2,
} from 'lucide-react';

// Types
interface ContractClause {
  id: string;
  title: string;
  content: string;
  comments: { id: string; user: string; text: string; resolved: boolean }[];
}

interface Contract {
  id: string;
  campaignId: string;
  campaignName: string;
  creatorId: string;
  creatorName: string;
  creatorHandle: string;
  creatorImage: string;
  status: 'draft' | 'pending_review' | 'pending_signature' | 'signed' | 'expired' | 'disputed';
  brandSigned: boolean;
  creatorSigned: boolean;
  signedDate?: string;
  expiryDate: string;
  value: number;
  currency: string;
  deliverables: ContractDeliverable[];
  clauses: ContractClause[];
  escrowLocked: boolean;
  escrowAmount: number;
  createdAt: string;
  hash?: string;
}

interface ContractDeliverable {
  id: string;
  title: string;
  platform: 'instagram' | 'youtube' | 'tiktok';
  type: string;
  dueDate: string;
  status: 'pending' | 'submitted' | 'in_review' | 'approved' | 'revision_requested' | 'rejected';
  submittedDate?: string;
  submittedUrl?: string;
  thumbnailUrl?: string;
  feedback?: string;
  revisionCount: number;
  maxRevisions: number;
}

/** Demo mapping: Contracts inbox → Deal Room deep link */
const CONTRACT_DEAL_ROOM: Record<string, string> = {
  'contract-1': 'deal-1',
  'contract-2': 'deal-3',
  'contract-3': 'deal-4',
};

// Mock Data
const mockContracts: Contract[] = [
  {
    id: 'contract-1',
    campaignId: 'active-1',
    campaignName: 'Summer Collection Launch',
    creatorId: 'creator-1',
    creatorName: 'Sarah Johnson',
    creatorHandle: '@sarahjcreates',
    creatorImage: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400',
    status: 'signed',
    brandSigned: true,
    creatorSigned: true,
    signedDate: '2024-01-15',
    expiryDate: '2024-06-30',
    value: 45000,
    currency: 'INR',
    escrowLocked: true,
    escrowAmount: 45000,
    createdAt: '2024-01-10',
    hash: 'a1b2c3d4e5f6g7h8i9j0',
    clauses: [
      {
        id: 'c1',
        title: 'Scope of Work',
        content: 'Creator agrees to produce 3 Instagram Reels showcasing the Summer Collection products in lifestyle settings. Each reel should be 30-60 seconds in length with original audio or trending sounds.',
        comments: [],
      },
      {
        id: 'c2',
        title: 'Compensation',
        content: 'Brand shall pay Creator a total of INR 45,000 for the services rendered. Payment schedule: 50% upon contract signing (INR 22,500), 50% upon approval of all deliverables (INR 22,500).',
        comments: [],
      },
      {
        id: 'c3',
        title: 'Timeline',
        content: 'All deliverables must be submitted within 14 days of contract signing. Brand shall review and provide feedback within 48 hours of submission.',
        comments: [],
      },
      {
        id: 'c4',
        title: 'Usage Rights',
        content: 'Brand receives non-exclusive usage rights for 90 days across owned social media channels. Extended usage or paid advertising requires additional compensation as mutually agreed.',
        comments: [
          { id: 'cm1', user: 'Brand', text: 'Can we extend to paid ads?', resolved: true },
          { id: 'cm2', user: 'Creator', text: 'Yes, with additional INR 10,000 fee', resolved: true },
        ],
      },
      {
        id: 'c5',
        title: 'Revisions',
        content: 'Up to 2 rounds of revisions are included. Additional revisions will be charged at INR 2,500 per round.',
        comments: [],
      },
    ],
    deliverables: [
      {
        id: 'd1',
        title: 'Summer Outfit Styling Reel',
        platform: 'instagram',
        type: 'Reel',
        dueDate: '2024-01-25',
        status: 'approved',
        submittedDate: '2024-01-22',
        submittedUrl: 'https://instagram.com/reel/abc123',
        thumbnailUrl: 'https://images.unsplash.com/photo-1515886657613-9f3515b0c78f?w=400',
        revisionCount: 1,
        maxRevisions: 2,
      },
      {
        id: 'd2',
        title: 'Beach Day Collection Showcase',
        platform: 'instagram',
        type: 'Reel',
        dueDate: '2024-01-28',
        status: 'in_review',
        submittedDate: '2024-01-27',
        submittedUrl: 'https://instagram.com/reel/def456',
        thumbnailUrl: 'https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=400',
        revisionCount: 0,
        maxRevisions: 2,
      },
      {
        id: 'd3',
        title: 'Evening Wear Transition',
        platform: 'instagram',
        type: 'Reel',
        dueDate: '2024-01-30',
        status: 'pending',
        revisionCount: 0,
        maxRevisions: 2,
      },
    ],
  },
  {
    id: 'contract-2',
    campaignId: 'active-1',
    campaignName: 'Summer Collection Launch',
    creatorId: 'creator-2',
    creatorName: 'Mike Chen',
    creatorHandle: '@mikechenlifestyle',
    creatorImage: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400',
    status: 'pending_signature',
    brandSigned: true,
    creatorSigned: false,
    expiryDate: '2024-06-30',
    value: 35000,
    currency: 'INR',
    escrowLocked: true,
    escrowAmount: 35000,
    createdAt: '2024-01-18',
    clauses: [
      {
        id: 'c1',
        title: 'Scope of Work',
        content: 'Creator agrees to produce 2 YouTube Shorts showcasing the Summer Collection in a "day in my life" format.',
        comments: [],
      },
      {
        id: 'c2',
        title: 'Compensation',
        content: 'Brand shall pay Creator a total of INR 35,000. Payment: 50% upfront, 50% on completion.',
        comments: [],
      },
    ],
    deliverables: [
      {
        id: 'd1',
        title: 'Morning Routine with Summer Fits',
        platform: 'youtube',
        type: 'Short',
        dueDate: '2024-02-10',
        status: 'pending',
        revisionCount: 0,
        maxRevisions: 2,
      },
      {
        id: 'd2',
        title: 'Weekend Vibes Lookbook',
        platform: 'youtube',
        type: 'Short',
        dueDate: '2024-02-15',
        status: 'pending',
        revisionCount: 0,
        maxRevisions: 2,
      },
    ],
  },
  {
    id: 'contract-3',
    campaignId: 'campaign-2',
    campaignName: 'Tech Product Review',
    creatorId: 'creator-3',
    creatorName: 'Priya Sharma',
    creatorHandle: '@priyatechreview',
    creatorImage: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400',
    status: 'pending_review',
    brandSigned: false,
    creatorSigned: false,
    expiryDate: '2024-07-31',
    value: 28000,
    currency: 'INR',
    escrowLocked: false,
    escrowAmount: 0,
    createdAt: '2024-01-20',
    clauses: [
      {
        id: 'c1',
        title: 'Scope of Work',
        content: 'Creator agrees to produce 1 TikTok video reviewing the new product launch.',
        comments: [
          { id: 'cm1', user: 'Creator', text: 'Can I include competitor comparison?', resolved: false },
        ],
      },
    ],
    deliverables: [
      {
        id: 'd1',
        title: 'Product Unboxing & Review',
        platform: 'tiktok',
        type: 'Video',
        dueDate: '2024-02-20',
        status: 'pending',
        revisionCount: 0,
        maxRevisions: 2,
      },
    ],
  },
];

/**
 * Shape assumed for rows returned by `contracts.list` / `contracts.get`
 * (src/lib/api.ts:756). The facade doesn't type this response yet (returns
 * `unknown`), and the backend `Contract` model in `src/lib/types.ts` is
 * collaboration-scoped (no creator name/handle/image, no UI clause text) —
 * so every field here is optional and merged per-field against the mock
 * fixture below rather than assumed present.
 */
interface ApiContractRow {
  id: string;
  campaignId?: string;
  campaignName?: string;
  creatorId?: string;
  creatorName?: string;
  creatorHandle?: string;
  creatorImage?: string;
  status?: Contract['status'];
  brandSigned?: boolean;
  creatorSigned?: boolean;
  signedDate?: string;
  expiryDate?: string;
  value?: number;
  currency?: string;
  escrowLocked?: boolean;
  escrowAmount?: number;
  createdAt?: string;
  hash?: string;
}

/**
 * Merge a live contract row with its mock fixture (matched by id, when one
 * exists). `deliverables` and `clauses` are UI-only detail the `/contracts`
 * endpoint doesn't return yet, so they always come from the mock fallback
 * (or an empty array for contracts with no local fixture).
 */
function mergeContractRow(row: ApiContractRow, fallback?: Contract): Contract {
  return {
    id: row.id,
    campaignId: row.campaignId ?? fallback?.campaignId ?? '',
    campaignName: row.campaignName ?? fallback?.campaignName ?? 'Untitled Campaign',
    creatorId: row.creatorId ?? fallback?.creatorId ?? '',
    creatorName: row.creatorName ?? fallback?.creatorName ?? 'Creator',
    creatorHandle: row.creatorHandle ?? fallback?.creatorHandle ?? '',
    creatorImage: row.creatorImage ?? fallback?.creatorImage ?? '',
    status: row.status ?? fallback?.status ?? 'draft',
    brandSigned: row.brandSigned ?? fallback?.brandSigned ?? false,
    creatorSigned: row.creatorSigned ?? fallback?.creatorSigned ?? false,
    signedDate: row.signedDate ?? fallback?.signedDate,
    expiryDate: row.expiryDate ?? fallback?.expiryDate ?? '',
    value: row.value ?? fallback?.value ?? 0,
    currency: row.currency ?? fallback?.currency ?? 'INR',
    deliverables: fallback?.deliverables ?? [],
    clauses: fallback?.clauses ?? [],
    escrowLocked: row.escrowLocked ?? fallback?.escrowLocked ?? false,
    escrowAmount: row.escrowAmount ?? fallback?.escrowAmount ?? 0,
    createdAt: row.createdAt ?? fallback?.createdAt ?? '',
    hash: row.hash ?? fallback?.hash,
  };
}

/** Update one deliverable's status/feedback within a contracts array. */
function withDeliverableStatus(
  list: Contract[],
  contractId: string | undefined,
  deliverableId: string,
  status: ContractDeliverable['status'],
  feedback?: string,
): Contract[] {
  if (!contractId) return list;
  return list.map((c) =>
    c.id === contractId
      ? {
          ...c,
          deliverables: c.deliverables.map((d) =>
            d.id === deliverableId ? { ...d, status, feedback: feedback || d.feedback } : d,
          ),
        }
      : c,
  );
}

const statusConfig = {
  draft: { label: 'Draft', color: 'border bg-stage-draft text-stage-draft-fg border-stage-draft-border', icon: PenTool },
  pending_review: { label: 'Pending Review', color: 'border bg-stage-review text-stage-review-fg border-stage-review-border', icon: Eye },
  pending_signature: { label: 'Awaiting Signature', color: 'border bg-stage-contracted text-stage-contracted-fg border-stage-contracted-border', icon: FileSignature },
  signed: { label: 'Active', color: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border', icon: CheckCircle2 },
  expired: { label: 'Expired', color: 'border bg-stage-expired text-stage-expired-fg border-stage-expired-border', icon: AlertCircle },
  disputed: { label: 'Disputed', color: 'border bg-stage-disputed text-stage-disputed-fg border-stage-disputed-border', icon: AlertTriangle },
};

const deliverableStatusConfig = {
  pending: { label: 'Pending', color: 'border bg-stage-draft text-stage-draft-fg border-stage-draft-border', icon: Clock },
  submitted: { label: 'Submitted', color: 'border bg-stage-outreach text-stage-outreach-fg border-stage-outreach-border', icon: Send },
  in_review: { label: 'In Review', color: 'border bg-stage-review text-stage-review-fg border-stage-review-border', icon: Eye },
  approved: { label: 'Approved', color: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border', icon: CheckCircle2 },
  revision_requested: { label: 'Revisions Needed', color: 'border bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border', icon: RotateCcw },
  rejected: { label: 'Rejected', color: 'border bg-stage-disputed text-stage-disputed-fg border-stage-disputed-border', icon: XCircle },
};

const platformIcons = {
  instagram: 'bg-gradient-to-br from-stage-contracted to-stage-review',
  youtube: 'bg-stage-disputed',
  tiktok: 'bg-stage-progress',
};

export function ContractsAndDeliverables() {
  const liveApi = isApiLive();
  const { toast } = useToast();

  const [contracts, setContracts] = useState<Contract[]>(mockContracts);
  const [contractsLoading, setContractsLoading] = useState(false);
  const [contractsError, setContractsError] = useState<string | null>(null);
  const [selectedContractId, setSelectedContractId] = useState<string | null>(mockContracts[0]?.id ?? null);
  const [activeTab, setActiveTab] = useState('overview');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [searchTerm, setSearchTerm] = useState('');

  // Dialogs
  const [showSignDialog, setShowSignDialog] = useState(false);
  const [showReviewDialog, setShowReviewDialog] = useState(false);
  const [selectedDeliverable, setSelectedDeliverable] = useState<ContractDeliverable | null>(null);
  const [reviewFeedback, setReviewFeedback] = useState('');
  const [signatureText, setSignatureText] = useState('');
  const [isApproving, setIsApproving] = useState(false);
  const [isRequestingRevision, setIsRequestingRevision] = useState(false);
  const [isSigning, setIsSigning] = useState(false);

  // Canvas for signature
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isDrawing, setIsDrawing] = useState(false);

  const selectedContract = contracts.find((c) => c.id === selectedContractId) ?? null;

  // GET /contracts (no dealId → all contracts for this brand). Falls back to
  // the mock fixtures below whenever mock mode is on, the request fails, or
  // live mode returns an empty list.
  const fetchContracts = useCallback(async () => {
    if (!liveApi) return;
    setContractsLoading(true);
    setContractsError(null);
    try {
      const rows = (await api.contracts.list('brand')) as ApiContractRow[];
      if (Array.isArray(rows) && rows.length > 0) {
        setContracts(
          rows.map((row) => mergeContractRow(row, mockContracts.find((m) => m.id === row.id))),
        );
      }
    } catch (err) {
      setContractsError(err instanceof ApiError ? err.message : 'Could not load contracts.');
    } finally {
      setContractsLoading(false);
    }
  }, [liveApi]);

  useEffect(() => {
    fetchContracts();
  }, [fetchContracts]);

  // Keep selection valid whenever the contracts list changes underneath it.
  useEffect(() => {
    if (contracts.length === 0) return;
    if (!contracts.some((c) => c.id === selectedContractId)) {
      setSelectedContractId(contracts[0].id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contracts]);

  // GET /contracts/:id — best-effort detail refresh for the selected contract.
  useEffect(() => {
    if (!liveApi || !selectedContractId) return;
    let cancelled = false;
    (async () => {
      try {
        const row = (await api.contracts.get('brand', selectedContractId)) as ApiContractRow | null;
        if (!cancelled && row) {
          setContracts((prev) =>
            prev.map((c) =>
              c.id === selectedContractId
                ? mergeContractRow(row, mockContracts.find((m) => m.id === selectedContractId) ?? c)
                : c,
            ),
          );
        }
      } catch {
        // Detail refresh is best-effort — the list row already rendered.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi, selectedContractId]);

  const filteredContracts = contracts.filter((contract) => {
    const matchesSearch =
      contract.campaignName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      contract.creatorName.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesStatus = filterStatus === 'all' || contract.status === filterStatus;
    return matchesSearch && matchesStatus;
  });

  const completedDeliverables = selectedContract?.deliverables.filter(d => d.status === 'approved').length || 0;
  const totalDeliverables = selectedContract?.deliverables.length || 0;
  const progress = totalDeliverables > 0 ? (completedDeliverables / totalDeliverables) * 100 : 0;

  const handleReviewDeliverable = (deliverable: ContractDeliverable) => {
    setSelectedDeliverable(deliverable);
    setShowReviewDialog(true);
  };

  // POST /deliverables/:id/approve (src/lib/api.ts:806)
  const handleApproveDeliverable = async () => {
    if (!selectedDeliverable) return;
    if (liveApi) {
      setIsApproving(true);
      try {
        await api.deliverables.approve(selectedDeliverable.id);
      } catch (err) {
        toast({
          title: 'Could not approve deliverable',
          description: err instanceof ApiError ? err.message : 'Try again.',
          variant: 'destructive',
        });
        setIsApproving(false);
        return;
      }
      setIsApproving(false);
    }
    setContracts((prev) =>
      withDeliverableStatus(prev, selectedContract?.id, selectedDeliverable.id, 'approved'),
    );
    setShowReviewDialog(false);
    setSelectedDeliverable(null);
    setReviewFeedback('');
  };

  // POST /deliverables/:id/revise (src/lib/api.ts:812)
  const handleRequestRevision = async () => {
    if (!selectedDeliverable) return;
    if (liveApi) {
      setIsRequestingRevision(true);
      try {
        await api.deliverables.requestRevision(selectedDeliverable.id, reviewFeedback);
      } catch (err) {
        toast({
          title: 'Could not request revision',
          description: err instanceof ApiError ? err.message : 'Try again.',
          variant: 'destructive',
        });
        setIsRequestingRevision(false);
        return;
      }
      setIsRequestingRevision(false);
    }
    setContracts((prev) =>
      withDeliverableStatus(
        prev,
        selectedContract?.id,
        selectedDeliverable.id,
        'revision_requested',
        reviewFeedback,
      ),
    );
    setShowReviewDialog(false);
    setSelectedDeliverable(null);
    setReviewFeedback('');
  };

  // POST /contracts/:id/sign (src/lib/api.ts:776)
  const handleSignContract = async () => {
    if (!selectedContract || !signatureText) return;
    if (liveApi) {
      setIsSigning(true);
      try {
        await api.contracts.sign('brand', selectedContract.id, {
          name: signatureText,
          agreedAt: new Date().toISOString(),
        });
      } catch (err) {
        toast({
          title: 'Could not sign contract',
          description: err instanceof ApiError ? err.message : 'Try again.',
          variant: 'destructive',
        });
        setIsSigning(false);
        return;
      }
      setIsSigning(false);
    }
    setContracts((prev) =>
      prev.map((c) =>
        c.id === selectedContract.id
          ? { ...c, brandSigned: true, status: c.creatorSigned ? 'signed' : 'pending_signature' }
          : c,
      ),
    );
    setShowSignDialog(false);
    setSignatureText('');
  };

  // Canvas drawing functions
  const startDrawing = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    setIsDrawing(true);
    const rect = canvas.getBoundingClientRect();
    ctx.beginPath();
    ctx.moveTo(e.clientX - rect.left, e.clientY - rect.top);
  };

  const draw = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDrawing) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const rect = canvas.getBoundingClientRect();
    ctx.lineTo(e.clientX - rect.left, e.clientY - rect.top);
    ctx.strokeStyle = '#3b82f6';
    ctx.lineWidth = 2;
    ctx.stroke();
  };

  const stopDrawing = () => {
    setIsDrawing(false);
  };

  const clearSignature = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    setSignatureText('');
  };

  const formatCurrency = (amount: number, currency: string = 'INR') => {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: currency,
      maximumFractionDigits: 0,
    }).format(amount);
  };

  return (
    <div className="min-h-screen bg-background pb-20">
      {/* Header */}
      <div className="sticky top-0 z-40 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 px-4 py-4 sm:px-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold">Contracts & Deliverables</h1>
            <p className="text-sm text-muted-foreground mt-1">Manage contracts, track deliverables, and review submissions</p>
          </div>
          <div className="flex gap-2">
            <select
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              className="px-3 py-2 bg-muted border border-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            >
              <option value="all">All Status</option>
              <option value="signed">Active</option>
              <option value="pending_signature">Awaiting Signature</option>
              <option value="pending_review">Pending Review</option>
              <option value="draft">Draft</option>
            </select>
          </div>
        </div>
      </div>

      {contractsError && (
        <div className="mx-4 sm:mx-6 mt-4 flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <div className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{contractsError}</span>
          </div>
          <Button variant="outline" size="sm" onClick={fetchContracts}>Retry</Button>
        </div>
      )}

      <div className="px-4 sm:px-6 py-6">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Contracts List - Sidebar */}
          <div className="lg:col-span-4 xl:col-span-3">
            <Card className="border-border">
              <div className="p-4 border-b border-border">
                <input
                  type="text"
                  placeholder="Search contracts..."
                  className="w-full px-3 py-2 bg-muted border border-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                />
              </div>
              {contractsLoading && (
                <div className="p-4 flex items-center justify-center gap-2 text-sm text-muted-foreground">
                  <Loader2 className="w-4 h-4 animate-spin" />
                  Loading contracts…
                </div>
              )}
              <ScrollArea className="h-[calc(100vh-280px)]">
                <div className="p-2 space-y-2">
                  {filteredContracts.map((contract) => {
                    const StatusIcon = statusConfig[contract.status].icon;
                    const completed = contract.deliverables.filter(d => d.status === 'approved').length;
                    const total = contract.deliverables.length;
                    
                    return (
                      <button
                        key={contract.id}
                        onClick={() => setSelectedContractId(contract.id)}
                        className={`w-full text-left p-3 rounded-lg border transition-all ${
                          selectedContract?.id === contract.id
                            ? 'bg-primary/10 border-primary'
                            : 'bg-card border-border hover:bg-muted'
                        }`}
                      >
                        <div className="flex items-start gap-3">
                          <Avatar className="h-10 w-10">
                            <AvatarImage src={contract.creatorImage} />
                            <AvatarFallback>{contract.creatorName[0]}</AvatarFallback>
                          </Avatar>
                          <div className="flex-1 min-w-0">
                            <h3 className="font-medium text-sm truncate">{contract.creatorName}</h3>
                            <p className="text-xs text-muted-foreground truncate">{contract.campaignName}</p>
                            <div className="flex items-center gap-2 mt-2">
                              <Badge variant="outline" className={`text-[10px] px-1.5 py-0 ${statusConfig[contract.status].color}`}>
                                <StatusIcon className="w-3 h-3 mr-1" />
                                {statusConfig[contract.status].label}
                              </Badge>
                              <span className="text-[10px] text-muted-foreground">{completed}/{total}</span>
                            </div>
                          </div>
                          <div className="text-right">
                            <p className="text-sm font-semibold">{formatCurrency(contract.value, contract.currency)}</p>
                          </div>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </ScrollArea>
            </Card>
          </div>

          {/* Contract Details */}
          <div className="lg:col-span-8 xl:col-span-9">
            {selectedContract ? (
              <Card className="border-border overflow-hidden">
                {/* Contract Header */}
                <div className="p-6 border-b border-border bg-muted/30">
                  <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
                    <div className="flex items-start gap-4">
                      <Avatar className="h-14 w-14">
                        <AvatarImage src={selectedContract.creatorImage} />
                        <AvatarFallback>{selectedContract.creatorName[0]}</AvatarFallback>
                      </Avatar>
                      <div>
                        <div className="flex items-center gap-2">
                          <h2 className="text-xl font-bold">{selectedContract.creatorName}</h2>
                          <Badge variant="outline" className={statusConfig[selectedContract.status].color}>
                            {statusConfig[selectedContract.status].label}
                          </Badge>
                        </div>
                        <p className="text-sm text-muted-foreground">{selectedContract.creatorHandle}</p>
                        <p className="text-sm text-muted-foreground mt-1">{selectedContract.campaignName}</p>
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {selectedContract.status === 'pending_signature' && !selectedContract.creatorSigned && (
                        <Badge variant="outline" className="bg-amber-500/10 text-amber-400 border-amber-500/30">
                          <Clock className="w-3 h-3 mr-1" />
                          Awaiting Creator Signature
                        </Badge>
                      )}
                      {selectedContract.status === 'signed' && (
                        // No PDF-export endpoint exists in src/lib/api.ts (contracts facade
                        // only has list/get/generate/sign) — left as a non-functional stub
                        // rather than faking a download.
                        <Button variant="outline" size="sm" className="gap-2" disabled>
                          <Download className="w-4 h-4" />
                          Download PDF
                        </Button>
                      )}
                      <Button variant="outline" size="sm" className="gap-2" asChild>
                        <Link
                          to={`/brand/chat?deal=${CONTRACT_DEAL_ROOM[selectedContract.id] ?? 'deal-1'}&tab=messages`}
                        >
                          <MessageSquare className="w-4 h-4" />
                          Message
                        </Link>
                      </Button>
                      <Button size="sm" className="gap-2" asChild>
                        <Link
                          to={`/brand/chat?deal=${CONTRACT_DEAL_ROOM[selectedContract.id] ?? 'deal-1'}&tab=contract`}
                        >
                          <ExternalLink className="w-4 h-4" />
                          Open in Deal Room
                        </Link>
                      </Button>
                    </div>
                  </div>

                  {/* Progress & Stats */}
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-6">
                    <div className="bg-card rounded-lg p-3 border border-border">
                      <p className="text-xs text-muted-foreground">Contract Value</p>
                      <p className="text-lg font-bold mt-1">{formatCurrency(selectedContract.value, selectedContract.currency)}</p>
                    </div>
                    <div className="bg-card rounded-lg p-3 border border-border">
                      <p className="text-xs text-muted-foreground">Escrow Status</p>
                      <div className="flex items-center gap-2 mt-1">
                        {selectedContract.escrowLocked ? (
                          <>
                            <Lock className="w-4 h-4 text-green-500" />
                            <span className="text-sm font-semibold text-green-500">Locked</span>
                          </>
                        ) : (
                          <>
                            <Unlock className="w-4 h-4 text-amber-500" />
                            <span className="text-sm font-semibold text-amber-500">Not Locked</span>
                          </>
                        )}
                      </div>
                    </div>
                    <div className="bg-card rounded-lg p-3 border border-border">
                      <p className="text-xs text-muted-foreground">Deliverables</p>
                      <p className="text-lg font-bold mt-1">{completedDeliverables}/{totalDeliverables}</p>
                      <Progress value={progress} className="h-1 mt-2" />
                    </div>
                    <div className="bg-card rounded-lg p-3 border border-border">
                      <p className="text-xs text-muted-foreground">Expires</p>
                      <p className="text-sm font-semibold mt-1">{new Date(selectedContract.expiryDate).toLocaleDateString()}</p>
                    </div>
                  </div>

                  {/* Signature Status */}
                  {selectedContract.status === 'signed' && (
                    <div className="mt-4 p-3 bg-green-500/10 border border-green-500/20 rounded-lg">
                      <div className="flex items-center gap-3">
                        <Shield className="w-5 h-5 text-green-500" />
                        <div className="flex-1">
                          <p className="text-sm font-medium text-green-400">Contract Fully Executed</p>
                          <p className="text-xs text-muted-foreground">Signed on {new Date(selectedContract.signedDate!).toLocaleDateString()} | Hash: {selectedContract.hash}</p>
                        </div>
                        <div className="flex gap-2">
                          <div className="flex items-center gap-1 text-xs">
                            <CheckCheck className="w-4 h-4 text-green-500" />
                            <span>Brand</span>
                          </div>
                          <div className="flex items-center gap-1 text-xs">
                            <CheckCheck className="w-4 h-4 text-green-500" />
                            <span>Creator</span>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>

                {/* Tabs */}
                <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
                  <TabsList className="w-full justify-start rounded-none border-b border-border bg-transparent p-0">
                    <TabsTrigger value="overview" className="rounded-none border-b-2 border-transparent data-[state=active]:border-primary data-[state=active]:bg-transparent">
                      Overview
                    </TabsTrigger>
                    <TabsTrigger value="contract" className="rounded-none border-b-2 border-transparent data-[state=active]:border-primary data-[state=active]:bg-transparent">
                      Contract
                    </TabsTrigger>
                    <TabsTrigger value="deliverables" className="rounded-none border-b-2 border-transparent data-[state=active]:border-primary data-[state=active]:bg-transparent">
                      Deliverables
                    </TabsTrigger>
                    <TabsTrigger value="payments" className="rounded-none border-b-2 border-transparent data-[state=active]:border-primary data-[state=active]:bg-transparent">
                      Payments
                    </TabsTrigger>
                  </TabsList>

                  {/* Overview Tab */}
                  <TabsContent value="overview" className="p-6 space-y-6">
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                      {/* Contract Summary - Plain English */}
                      <Card className="p-4 border-border">
                        <h3 className="font-semibold flex items-center gap-2 mb-3">
                          <FileText className="w-4 h-4 text-primary" />
                          Contract Summary
                        </h3>
                        <div className="text-sm text-muted-foreground space-y-2">
                          <p><strong>{selectedContract.creatorName}</strong> agrees to create <strong>{selectedContract.deliverables.length} deliverable(s)</strong> for <strong>{selectedContract.campaignName}</strong>.</p>
                          <p>Total compensation: <strong>{formatCurrency(selectedContract.value, selectedContract.currency)}</strong></p>
                          <p>Timeline: Content due by <strong>{new Date(selectedContract.expiryDate).toLocaleDateString()}</strong></p>
                          <p>Revisions: Up to <strong>2 rounds</strong> included per deliverable</p>
                        </div>
                      </Card>

                      {/* Structured Summary */}
                      <Card className="p-4 border-border">
                        <h3 className="font-semibold flex items-center gap-2 mb-3">
                          <Sparkles className="w-4 h-4 text-primary" />
                          Quick Reference
                        </h3>
                        <div className="space-y-3 text-sm">
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">WHO</span>
                            <span className="font-medium">Brand ↔ {selectedContract.creatorName}</span>
                          </div>
                          <Separator />
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">WHAT</span>
                            <span className="font-medium">{selectedContract.deliverables.length} {selectedContract.deliverables[0]?.platform} {selectedContract.deliverables[0]?.type}(s)</span>
                          </div>
                          <Separator />
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">WHEN</span>
                            <span className="font-medium">Due {new Date(selectedContract.expiryDate).toLocaleDateString()}</span>
                          </div>
                          <Separator />
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">HOW MUCH</span>
                            <span className="font-medium">{formatCurrency(selectedContract.value, selectedContract.currency)}</span>
                          </div>
                          <Separator />
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">IF DISPUTE</span>
                            <span className="font-medium">Platform mediates, escrow held</span>
                          </div>
                        </div>
                      </Card>
                    </div>

                    {/* Deliverables Preview */}
                    <div>
                      <h3 className="font-semibold mb-3">Deliverables Status</h3>
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                        {selectedContract.deliverables.map((deliverable) => {
                          const StatusIcon = deliverableStatusConfig[deliverable.status].icon;
                          return (
                            <Card key={deliverable.id} className="p-3 border-border">
                              <div className="flex items-start gap-3">
                                <div className={`w-10 h-10 rounded-lg ${platformIcons[deliverable.platform]} flex items-center justify-center`}>
                                  {deliverable.platform === 'instagram' && <Image className="w-5 h-5 text-white" />}
                                  {deliverable.platform === 'youtube' && <Play className="w-5 h-5 text-white" />}
                                  {deliverable.platform === 'tiktok' && <Video className="w-5 h-5 text-white" />}
                                </div>
                                <div className="flex-1 min-w-0">
                                  <p className="text-sm font-medium truncate">{deliverable.title}</p>
                                  <p className="text-xs text-muted-foreground">Due {new Date(deliverable.dueDate).toLocaleDateString()}</p>
                                  <Badge variant="outline" className={`text-[10px] mt-1 ${deliverableStatusConfig[deliverable.status].color}`}>
                                    <StatusIcon className="w-3 h-3 mr-1" />
                                    {deliverableStatusConfig[deliverable.status].label}
                                  </Badge>
                                </div>
                              </div>
                            </Card>
                          );
                        })}
                      </div>
                    </div>
                  </TabsContent>

                  {/* Contract Tab - Full Document */}
                  <TabsContent value="contract" className="p-6">
                    <div className="space-y-4">
                      <div className="flex items-center justify-between">
                        <h3 className="font-semibold">Contract Terms</h3>
                        {selectedContract.status === 'pending_review' && (
                          <Badge variant="outline" className="bg-amber-500/10 text-amber-400">
                            Comments need resolution before signing
                          </Badge>
                        )}
                      </div>
                      
                      {selectedContract.clauses.map((clause, index) => (
                        <Card key={clause.id} className="p-4 border-border">
                          <div className="flex items-start justify-between gap-4">
                            <div className="flex-1">
                              <h4 className="font-medium text-sm">
                                {index + 1}. {clause.title}
                              </h4>
                              <p className="text-sm text-muted-foreground mt-2">{clause.content}</p>
                            </div>
                          </div>
                          
                          {/* Comments */}
                          {clause.comments.length > 0 && (
                            <div className="mt-4 pt-4 border-t border-border space-y-2">
                              {clause.comments.map((comment) => (
                                <div key={comment.id} className="flex items-start gap-2 text-sm">
                                  <MessageSquare className="w-4 h-4 text-muted-foreground mt-0.5" />
                                  <div className="flex-1">
                                    <span className="font-medium">{comment.user}:</span>{' '}
                                    <span className="text-muted-foreground">{comment.text}</span>
                                  </div>
                                  {comment.resolved && (
                                    <Badge variant="outline" className="text-[10px] bg-green-500/10 text-green-400">
                                      <CheckCircle2 className="w-3 h-3 mr-1" />
                                      Resolved
                                    </Badge>
                                  )}
                                </div>
                              ))}
                            </div>
                          )}
                        </Card>
                      ))}

                      {/* Signing Section */}
                      {selectedContract.status === 'pending_signature' && selectedContract.brandSigned && !selectedContract.creatorSigned && (
                        <Card className="p-4 border-primary/30 bg-primary/5">
                          <div className="flex items-center gap-3">
                            <FileSignature className="w-5 h-5 text-primary" />
                            <div className="flex-1">
                              <p className="font-medium">Awaiting Creator Signature</p>
                              <p className="text-sm text-muted-foreground">You have signed this contract. Waiting for {selectedContract.creatorName} to countersign.</p>
                            </div>
                          </div>
                        </Card>
                      )}

                      {selectedContract.status === 'pending_review' && (
                        <Button className="w-full" onClick={() => setShowSignDialog(true)}>
                          <Pen className="w-4 h-4 mr-2" />
                          Sign Contract
                        </Button>
                      )}
                    </div>
                  </TabsContent>

                  {/* Deliverables Tab */}
                  <TabsContent value="deliverables" className="p-6">
                    <div className="space-y-4">
                      {selectedContract.deliverables.map((deliverable) => {
                        const StatusIcon = deliverableStatusConfig[deliverable.status].icon;
                        return (
                          <Card key={deliverable.id} className="overflow-hidden border-border">
                            <div className="p-4">
                              <div className="flex flex-col sm:flex-row sm:items-start gap-4">
                                {/* Thumbnail */}
                                {deliverable.thumbnailUrl ? (
                                  <div className="relative w-full sm:w-32 h-40 sm:h-24 rounded-lg overflow-hidden bg-muted flex-shrink-0">
                                    <img src={deliverable.thumbnailUrl} alt={deliverable.title} className="w-full h-full object-cover" />
                                    <div className="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 hover:opacity-100 transition-opacity">
                                      <Button size="sm" variant="secondary" className="gap-1">
                                        <Play className="w-3 h-3" />
                                        Preview
                                      </Button>
                                    </div>
                                  </div>
                                ) : (
                                  <div className={`w-full sm:w-32 h-40 sm:h-24 rounded-lg ${platformIcons[deliverable.platform]} flex items-center justify-center flex-shrink-0`}>
                                    <Clock className="w-8 h-8 text-white/50" />
                                  </div>
                                )}

                                {/* Details */}
                                <div className="flex-1 min-w-0">
                                  <div className="flex items-start justify-between gap-2">
                                    <div>
                                      <h4 className="font-medium">{deliverable.title}</h4>
                                      <div className="flex flex-wrap items-center gap-2 mt-1">
                                        <Badge variant="outline" className="text-xs capitalize">{deliverable.platform}</Badge>
                                        <Badge variant="outline" className="text-xs">{deliverable.type}</Badge>
                                        <Badge variant="outline" className={`text-xs ${deliverableStatusConfig[deliverable.status].color}`}>
                                          <StatusIcon className="w-3 h-3 mr-1" />
                                          {deliverableStatusConfig[deliverable.status].label}
                                        </Badge>
                                      </div>
                                    </div>
                                  </div>

                                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-3 text-sm">
                                    <div>
                                      <p className="text-xs text-muted-foreground">Due Date</p>
                                      <p className="font-medium">{new Date(deliverable.dueDate).toLocaleDateString()}</p>
                                    </div>
                                    {deliverable.submittedDate && (
                                      <div>
                                        <p className="text-xs text-muted-foreground">Submitted</p>
                                        <p className="font-medium">{new Date(deliverable.submittedDate).toLocaleDateString()}</p>
                                      </div>
                                    )}
                                    <div>
                                      <p className="text-xs text-muted-foreground">Revisions</p>
                                      <p className="font-medium">{deliverable.revisionCount}/{deliverable.maxRevisions} used</p>
                                    </div>
                                  </div>

                                  {/* Actions */}
                                  {(deliverable.status === 'submitted' || deliverable.status === 'in_review') && (
                                    <div className="flex flex-wrap gap-2 mt-4">
                                      {deliverable.submittedUrl && (
                                        <Button variant="outline" size="sm" className="gap-1" asChild>
                                          <a href={deliverable.submittedUrl} target="_blank" rel="noopener noreferrer">
                                            <ExternalLink className="w-3 h-3" />
                                            View Post
                                          </a>
                                        </Button>
                                      )}
                                      <Button size="sm" className="gap-1 bg-stage-approved-fg hover:opacity-90 text-white" onClick={() => handleReviewDeliverable(deliverable)}>
                                        <ThumbsUp className="w-3 h-3" />
                                        Approve
                                      </Button>
                                      <Button variant="outline" size="sm" className="gap-1" onClick={() => handleReviewDeliverable(deliverable)}>
                                        <RotateCcw className="w-3 h-3" />
                                        Request Revision
                                      </Button>
                                    </div>
                                  )}
                                </div>
                              </div>
                            </div>
                          </Card>
                        );
                      })}
                    </div>
                  </TabsContent>

                  {/* Payments Tab */}
                  <TabsContent value="payments" className="p-6">
                    <div className="space-y-4">
                      <Card className="p-4 border-border">
                        <h3 className="font-semibold mb-4">Payment Schedule</h3>
                        <div className="space-y-3">
                          <div className="flex items-center justify-between p-3 bg-muted rounded-lg">
                            <div className="flex items-center gap-3">
                              <div className="w-8 h-8 rounded-full bg-green-500/20 flex items-center justify-center">
                                <CheckCircle2 className="w-4 h-4 text-green-500" />
                              </div>
                              <div>
                                <p className="font-medium text-sm">50% Upon Signing</p>
                                <p className="text-xs text-muted-foreground">Released when contract is signed</p>
                              </div>
                            </div>
                            <div className="text-right">
                              <p className="font-semibold text-green-500">{formatCurrency(selectedContract.value / 2, selectedContract.currency)}</p>
                              <p className="text-xs text-green-500">Paid</p>
                            </div>
                          </div>

                          <div className="flex items-center justify-between p-3 bg-muted rounded-lg">
                            <div className="flex items-center gap-3">
                              <div className="w-8 h-8 rounded-full bg-amber-500/20 flex items-center justify-center">
                                <Lock className="w-4 h-4 text-amber-500" />
                              </div>
                              <div>
                                <p className="font-medium text-sm">50% Upon Completion</p>
                                <p className="text-xs text-muted-foreground">Released when all deliverables approved</p>
                              </div>
                            </div>
                            <div className="text-right">
                              <p className="font-semibold">{formatCurrency(selectedContract.value / 2, selectedContract.currency)}</p>
                              <p className="text-xs text-amber-500">In Escrow</p>
                            </div>
                          </div>
                        </div>
                      </Card>

                      <Card className="p-4 border-border">
                        <h3 className="font-semibold mb-4">Transaction History</h3>
                        <div className="space-y-2">
                          <div className="flex items-center justify-between py-2 border-b border-border">
                            <div className="flex items-center gap-3">
                              <DollarSign className="w-4 h-4 text-muted-foreground" />
                              <div>
                                <p className="text-sm">Escrow Funded</p>
                                <p className="text-xs text-muted-foreground">Jan 10, 2024</p>
                              </div>
                            </div>
                            <p className="font-medium">{formatCurrency(selectedContract.value, selectedContract.currency)}</p>
                          </div>
                          <div className="flex items-center justify-between py-2 border-b border-border">
                            <div className="flex items-center gap-3">
                              <Send className="w-4 h-4 text-green-500" />
                              <div>
                                <p className="text-sm">First Payment Released</p>
                                <p className="text-xs text-muted-foreground">Jan 15, 2024</p>
                              </div>
                            </div>
                            <p className="font-medium text-green-500">-{formatCurrency(selectedContract.value / 2, selectedContract.currency)}</p>
                          </div>
                        </div>
                      </Card>
                    </div>
                  </TabsContent>
                </Tabs>
              </Card>
            ) : (
              <Card className="border-border flex items-center justify-center h-96">
                <div className="text-center">
                  <FileText className="w-12 h-12 text-muted-foreground mx-auto mb-3" />
                  <p className="text-muted-foreground">Select a contract to view details</p>
                </div>
              </Card>
            )}
          </div>
        </div>
      </div>

      {/* Sign Contract Dialog */}
      <Dialog open={showSignDialog} onOpenChange={setShowSignDialog}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <FileSignature className="w-5 h-5" />
              Sign Contract
            </DialogTitle>
            <DialogDescription>
              Review the terms and sign the contract to proceed.
            </DialogDescription>
          </DialogHeader>
          
          <div className="space-y-4 py-4">
            {/* Term Summary */}
            <Card className="p-3 bg-muted/50 border-border">
              <p className="text-xs text-muted-foreground mb-2">Contract Summary</p>
              <p className="text-sm">{selectedContract?.deliverables.length} deliverables | {selectedContract && formatCurrency(selectedContract.value, selectedContract.currency)} | Due {selectedContract && new Date(selectedContract.expiryDate).toLocaleDateString()}</p>
            </Card>

            {/* Signature Pad */}
            <div className="space-y-2">
              <label className="text-sm font-medium">Draw Your Signature</label>
              <div className="border border-border rounded-lg p-2 bg-white">
                <canvas
                  ref={canvasRef}
                  width={400}
                  height={150}
                  className="w-full cursor-crosshair"
                  onMouseDown={startDrawing}
                  onMouseMove={draw}
                  onMouseUp={stopDrawing}
                  onMouseLeave={stopDrawing}
                />
              </div>
              <div className="flex justify-between">
                <Button variant="ghost" size="sm" onClick={clearSignature}>Clear</Button>
                <p className="text-xs text-muted-foreground">Or type your name below</p>
              </div>
            </div>

            {/* Type Name */}
            <div className="space-y-2">
              <label className="text-sm font-medium">Type Full Name</label>
              <input
                type="text"
                placeholder="Your legal name"
                className="w-full px-3 py-2 bg-muted border border-border rounded-lg"
                value={signatureText}
                onChange={(e) => setSignatureText(e.target.value)}
              />
            </div>

            <p className="text-xs text-muted-foreground">
              By signing, you agree to be legally bound by the terms of this contract under the IT Act 2000.
            </p>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setShowSignDialog(false)} disabled={isSigning}>Cancel</Button>
            <Button disabled={!signatureText || isSigning} onClick={handleSignContract}>
              {isSigning ? (
                <Loader2 className="w-4 h-4 mr-2 animate-spin" />
              ) : (
                <Pen className="w-4 h-4 mr-2" />
              )}
              Sign Contract
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Review Deliverable Dialog */}
      <Dialog open={showReviewDialog} onOpenChange={setShowReviewDialog}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Review Deliverable</DialogTitle>
            <DialogDescription>
              {selectedDeliverable?.title}
            </DialogDescription>
          </DialogHeader>
          
          <div className="space-y-4 py-4">
            {selectedDeliverable?.thumbnailUrl && (
              <div className="aspect-video rounded-lg overflow-hidden bg-muted">
                <img src={selectedDeliverable.thumbnailUrl} alt="" className="w-full h-full object-cover" />
              </div>
            )}

            <div className="space-y-2">
              <label className="text-sm font-medium">Feedback (Optional)</label>
              <Textarea
                placeholder="Add feedback for the creator..."
                value={reviewFeedback}
                onChange={(e) => setReviewFeedback(e.target.value)}
                rows={3}
              />
            </div>
          </div>

          <DialogFooter className="flex-col sm:flex-row gap-2">
            <Button
              variant="outline"
              onClick={() => setShowReviewDialog(false)}
              className="w-full sm:w-auto"
              disabled={isApproving || isRequestingRevision}
            >
              Cancel
            </Button>
            <Button
              variant="outline"
              onClick={handleRequestRevision}
              className="w-full sm:w-auto gap-1"
              disabled={isApproving || isRequestingRevision}
            >
              {isRequestingRevision ? <Loader2 className="w-4 h-4 animate-spin" /> : <RotateCcw className="w-4 h-4" />}
              Request Revision
            </Button>
            <Button
              onClick={handleApproveDeliverable}
              className="w-full sm:w-auto gap-1 bg-stage-approved-fg hover:opacity-90 text-white"
              disabled={isApproving || isRequestingRevision}
            >
              {isApproving ? <Loader2 className="w-4 h-4 animate-spin" /> : <ThumbsUp className="w-4 h-4" />}
              Approve
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
