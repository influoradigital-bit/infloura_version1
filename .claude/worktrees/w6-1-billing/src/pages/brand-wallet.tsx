import * as React from 'react';
import {
  Wallet,
  ArrowUpRight,
  ArrowDownLeft,
  Plus,
  Download,
  CreditCard,
  Building2,
  TrendingUp,
  TrendingDown,
  Clock,
  CheckCircle2,
  XCircle,
  AlertCircle,
  ChevronRight,
  Filter,
  Search,
  Calendar,
  RefreshCw,
  Lock,
  Unlock,
  Eye,
  EyeOff,
  Copy,
  ExternalLink,
  Receipt,
  FileText,
  Banknote,
  IndianRupee,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Progress } from '@/components/ui/progress';
import { Separator } from '@/components/ui/separator';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { api, isApiLive, ApiError, type WalletSummaryResponse, type WalletTransactionRow } from '@/lib/api';

// Types
interface Transaction {
  id: string;
  type: 'credit' | 'debit' | 'escrow_lock' | 'escrow_release' | 'refund';
  amount: number;
  currency: string;
  description: string;
  status: 'completed' | 'pending' | 'failed' | 'processing';
  createdAt: Date;
  completedAt?: Date;
  reference?: string;
  campaign?: { id: string; name: string };
  creator?: { id: string; name: string; avatar: string };
  paymentMethod?: string;
  breakdown?: {
    base: number;
    platformFee: number;
    gst: number;
    tds?: number;
  };
}

interface EscrowItem {
  id: string;
  campaignId: string;
  campaignName: string;
  creatorName: string;
  creatorAvatar: string;
  amount: number;
  lockedAt: Date;
  releaseDate?: Date;
  status: 'locked' | 'releasing' | 'released' | 'disputed';
}

// Mock data
const mockWalletData = {
  balance: 285000,
  currency: 'INR',
  escrowLocked: 450000,
  pendingSettlement: 75000,
  totalSpent: 1485000,
  monthlySpend: 180000,
  lastRecharge: new Date(Date.now() - 1000 * 60 * 60 * 24 * 5),
  lastRechargeAmount: 100000,
  // Runway projections (per PDF section 5.4)
  projectedBurn30Days: 180000,
  runwayDays: 47,
  suggestedRecharge: 200000,
  pipelineCommitments: 320000, // Total committed but not yet locked
  // Tax summary
  totalTDSDeducted: 148500,
  totalGSTPaid: 267300,
};

// Recharge preset chips (per PDF section 5.4)
const RECHARGE_PRESETS = [
  { amount: 25000, label: '₹25K' },
  { amount: 50000, label: '₹50K' },
  { amount: 100000, label: '₹1L' },
  { amount: 500000, label: '₹5L' },
];

const mockTransactions: Transaction[] = [
  {
    id: 't1',
    type: 'credit',
    amount: 50000,
    currency: 'INR',
    description: 'Wallet recharge via UPI',
    status: 'completed',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 5),
    completedAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 5),
    reference: 'PAY-2024-001234',
    paymentMethod: 'UPI',
  },
  {
    id: 't2',
    type: 'escrow_lock',
    amount: 25000,
    currency: 'INR',
    description: 'Escrow locked for campaign',
    status: 'completed',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 4),
    campaign: { id: 'c1', name: 'Summer Collection Launch' },
    creator: { id: 'cr1', name: 'Sarah Johnson', avatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=100' },
  },
  {
    id: 't3',
    type: 'escrow_release',
    amount: 22500,
    currency: 'INR',
    description: 'Payment released to creator',
    status: 'completed',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 2),
    completedAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 2),
    campaign: { id: 'c2', name: 'Tech Product Review' },
    creator: { id: 'cr2', name: 'Alex Chen', avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100' },
    breakdown: {
      base: 20000,
      platformFee: 2000,  // 10% of base
      gst: 360,           // 18% of platform fee
      tds: 200,           // 1% of base (Section 194-O)
    },
  },
  {
    id: 't4',
    type: 'escrow_lock',
    amount: 20000,
    currency: 'INR',
    description: 'Escrow locked for campaign',
    status: 'completed',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 1),
    campaign: { id: 'c3', name: 'Holiday Season Promo' },
    creator: { id: 'cr3', name: 'Maya Patel', avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100' },
  },
  {
    id: 't5',
    type: 'debit',
    amount: 5000,
    currency: 'INR',
    description: 'Platform subscription fee',
    status: 'completed',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 12),
    reference: 'SUB-2024-000089',
  },
  {
    id: 't6',
    type: 'credit',
    amount: 25000,
    currency: 'INR',
    description: 'Wallet recharge via Card',
    status: 'processing',
    createdAt: new Date(Date.now() - 1000 * 60 * 30),
    reference: 'PAY-2024-001289',
    paymentMethod: 'Credit Card',
  },
];

const mockEscrowItems: EscrowItem[] = [
  {
    id: 'e1',
    campaignId: 'c1',
    campaignName: 'Summer Collection Launch',
    creatorName: 'Sarah Johnson',
    creatorAvatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=100',
    amount: 25000,
    lockedAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 4),
    releaseDate: new Date(Date.now() + 1000 * 60 * 60 * 24 * 3),
    status: 'locked',
  },
  {
    id: 'e2',
    campaignId: 'c3',
    campaignName: 'Holiday Season Promo',
    creatorName: 'Maya Patel',
    creatorAvatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100',
    amount: 20000,
    lockedAt: new Date(Date.now() - 1000 * 60 * 60 * 24),
    status: 'locked',
  },
];

const formatCurrency = (amount: number, currency = 'INR'): string => {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
};

const formatDate = (date: Date): string => {
  return date.toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
};

const formatTime = (date: Date): string => {
  return date.toLocaleTimeString('en-IN', {
    hour: 'numeric',
    minute: '2-digit',
  });
};

function mapTxType(type: WalletTransactionRow['type']): Transaction['type'] {
  switch (type) {
    case 'DEPOSIT':
      return 'credit';
    case 'WITHDRAWAL':
    case 'PAYOUT':
    case 'PLATFORM_FEE':
      return 'debit';
    case 'ESCROW_HOLD':
      return 'escrow_lock';
    case 'ESCROW_RELEASE':
      return 'escrow_release';
    case 'ESCROW_REFUND':
      return 'refund';
    default:
      return 'debit';
  }
}

function mapTxStatus(status: string): Transaction['status'] {
  const s = status.toUpperCase();
  if (s === 'COMPLETED' || s === 'SUCCESS') return 'completed';
  if (s === 'PENDING') return 'pending';
  if (s === 'FAILED') return 'failed';
  return 'processing';
}

function mapWalletTransaction(row: WalletTransactionRow): Transaction {
  return {
    id: row.id,
    type: mapTxType(row.type),
    amount: row.amount,
    currency: row.currency || 'INR',
    description: row.description,
    status: mapTxStatus(row.status),
    createdAt: new Date(row.createdAt),
  };
}

const emptyWalletSummary: WalletSummaryResponse = {
  availableBalance: 0,
  escrowLocked: 0,
  pendingPayouts: 0,
  runwayDays: null,
};

export default function BrandWalletPage() {
  const [isBalanceVisible, setIsBalanceVisible] = React.useState(true);
  const [isAddFundsOpen, setIsAddFundsOpen] = React.useState(false);
  const [addAmount, setAddAmount] = React.useState('');
  const [paymentMethod, setPaymentMethod] = React.useState('upi');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [filterType, setFilterType] = React.useState('all');

  // H-20: live mode starts from an honest empty state and fetches real wallet
  // data; mock mode keeps the polished demo dataset above.
  const [walletSummary, setWalletSummary] = React.useState<WalletSummaryResponse>(emptyWalletSummary);
  const [transactions, setTransactions] = React.useState<Transaction[]>(
    isApiLive() ? [] : mockTransactions,
  );
  const [loading, setLoading] = React.useState(isApiLive());
  const [loadError, setLoadError] = React.useState<string | null>(null);

  const loadWallet = React.useCallback(async () => {
    if (!isApiLive()) return;
    setLoading(true);
    setLoadError(null);
    try {
      const [summary, txRows] = await Promise.all([
        api.wallet.get('brand'),
        api.wallet.transactions('brand'),
      ]);
      setWalletSummary(summary ?? emptyWalletSummary);
      setTransactions(Array.isArray(txRows) ? txRows.map(mapWalletTransaction) : []);
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : 'Could not load wallet data.');
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      await loadWallet();
      if (cancelled) return;
    })();
    return () => {
      cancelled = true;
    };
  }, [loadWallet]);

  // Wallet card data: real numbers in live mode, polished demo numbers in mock mode.
  // Fields with no backend source yet (totalSpent, monthlySpend, projectedBurn30Days,
  // suggestedRecharge, TDS/GST totals, lastRecharge) stay mock-only.
  const wallet = isApiLive()
    ? {
        ...mockWalletData,
        balance: walletSummary.availableBalance,
        escrowLocked: walletSummary.escrowLocked,
        pendingSettlement: walletSummary.pendingPayouts,
        runwayDays: walletSummary.runwayDays ?? 0,
      }
    : mockWalletData;

  // No GET /wallet/escrow endpoint yet — stays empty (not fabricated) in live mode.
  const escrowItems = isApiLive() ? [] : mockEscrowItems;

  // Use preset chips from PDF section 5.4
  const quickAmounts = RECHARGE_PRESETS;

  const filteredTransactions = transactions.filter((t) => {
    if (filterType !== 'all' && t.type !== filterType) return false;
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      return (
        t.description.toLowerCase().includes(query) ||
        t.reference?.toLowerCase().includes(query) ||
        t.campaign?.name.toLowerCase().includes(query) ||
        t.creator?.name.toLowerCase().includes(query)
      );
    }
    return true;
  });

  const getTransactionIcon = (type: Transaction['type']) => {
    switch (type) {
      case 'credit':
        return <ArrowDownLeft className="h-4 w-4 text-green-500" />;
      case 'debit':
        return <ArrowUpRight className="h-4 w-4 text-red-500" />;
      case 'escrow_lock':
        return <Lock className="h-4 w-4 text-amber-500" />;
      case 'escrow_release':
        return <Unlock className="h-4 w-4 text-blue-500" />;
      case 'refund':
        return <RefreshCw className="h-4 w-4 text-purple-500" />;
      default:
        return <Wallet className="h-4 w-4" />;
    }
  };

  const getStatusBadge = (status: Transaction['status']) => {
    switch (status) {
      case 'completed':
        return (
          <Badge variant="secondary" className="gap-1 bg-green-500/10 text-green-500">
            <CheckCircle2 className="h-3 w-3" /> Completed
          </Badge>
        );
      case 'pending':
        return (
          <Badge variant="secondary" className="gap-1 bg-amber-500/10 text-amber-500">
            <Clock className="h-3 w-3" /> Pending
          </Badge>
        );
      case 'processing':
        return (
          <Badge variant="secondary" className="gap-1 bg-blue-500/10 text-blue-500">
            <RefreshCw className="h-3 w-3 animate-spin" /> Processing
          </Badge>
        );
      case 'failed':
        return (
          <Badge variant="secondary" className="gap-1 bg-red-500/10 text-red-500">
            <XCircle className="h-3 w-3" /> Failed
          </Badge>
        );
      default:
        return null;
    }
  };

  return (
    <TooltipProvider>
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div>
            <h1 className="text-2xl font-bold">Wallet</h1>
            <p className="text-muted-foreground">
              Manage your funds, view transactions, and track escrow
            </p>
            {loadError && (
              <p className="mt-1 flex items-center gap-1 text-sm text-stage-disputed-fg">
                <AlertCircle className="h-3.5 w-3.5" /> {loadError}
              </p>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" className="gap-2" disabled={loading}>
              <Download className="h-4 w-4" />
              Export
            </Button>
            <Dialog open={isAddFundsOpen} onOpenChange={setIsAddFundsOpen}>
              <DialogTrigger asChild>
                <Button className="gap-2">
                  <Plus className="h-4 w-4" />
                  Add Funds
                </Button>
              </DialogTrigger>
              <DialogContent className="sm:max-w-md">
                <DialogHeader>
                  <DialogTitle>Add Funds to Wallet</DialogTitle>
                  <DialogDescription>
                    Choose an amount and payment method to recharge your wallet.
                  </DialogDescription>
                </DialogHeader>
                <div className="space-y-6 py-4">
                  {/* Quick Amount Selection - Preset Chips */}
                  <div className="space-y-3">
                    <Label>Quick Select</Label>
                    <div className="grid grid-cols-4 gap-2">
                      {quickAmounts.map((preset) => (
                        <Button
                          key={preset.amount}
                          variant={addAmount === preset.amount.toString() ? 'default' : 'outline'}
                          size="sm"
                          onClick={() => setAddAmount(preset.amount.toString())}
                        >
                          {preset.label}
                        </Button>
                      ))}
                    </div>
                  </div>

                  {/* Suggested Recharge based on Pipeline Burn */}
                  {wallet.runwayDays < 45 && (
                    <div className="rounded-lg border border-stage-negotiating-border bg-amber-50 p-3">
                      <div className="flex items-start gap-3">
                        <AlertCircle className="h-5 w-5 text-stage-negotiating-fg flex-shrink-0 mt-0.5" />
                        <div className="space-y-1">
                          <p className="text-sm font-medium text-amber-800">
                            Based on your pipeline, we recommend recharging:
                          </p>
                          <button
                            onClick={() => setAddAmount(wallet.suggestedRecharge.toString())}
                            className="text-lg font-bold text-amber-700 hover:underline"
                          >
                            {formatCurrency(wallet.suggestedRecharge)}
                          </button>
                          <p className="text-xs text-stage-negotiating-fg">
                            Current runway: {wallet.runwayDays} days | Projected burn: {formatCurrency(wallet.projectedBurn30Days)}/month
                          </p>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Custom Amount */}
                  <div className="space-y-2">
                    <Label htmlFor="amount">Or Enter Amount</Label>
                    <div className="relative">
                      <IndianRupee className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                      <Input
                        id="amount"
                        type="number"
                        placeholder="Enter amount"
                        value={addAmount}
                        onChange={(e) => setAddAmount(e.target.value)}
                        className="pl-9"
                      />
                    </div>
                  </div>

                  {/* Payment Method */}
                  <div className="space-y-3">
                    <Label>Payment Method</Label>
                    <div className="grid gap-2">
                      <button
                        onClick={() => setPaymentMethod('upi')}
                        className={cn(
                          'flex items-center gap-3 rounded-lg border p-3 text-left transition-colors',
                          paymentMethod === 'upi' ? 'border-primary bg-primary/5' : 'hover:bg-muted/50'
                        )}
                      >
                        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-green-500/10">
                          <Banknote className="h-5 w-5 text-green-500" />
                        </div>
                        <div className="flex-1">
                          <p className="font-medium">UPI</p>
                          <p className="text-sm text-muted-foreground">Instant transfer, no fees</p>
                        </div>
                        {paymentMethod === 'upi' && (
                          <CheckCircle2 className="h-5 w-5 text-primary" />
                        )}
                      </button>
                      <button
                        onClick={() => setPaymentMethod('card')}
                        className={cn(
                          'flex items-center gap-3 rounded-lg border p-3 text-left transition-colors',
                          paymentMethod === 'card' ? 'border-primary bg-primary/5' : 'hover:bg-muted/50'
                        )}
                      >
                        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-500/10">
                          <CreditCard className="h-5 w-5 text-blue-500" />
                        </div>
                        <div className="flex-1">
                          <p className="font-medium">Credit / Debit Card</p>
                          <p className="text-sm text-muted-foreground">2% convenience fee</p>
                        </div>
                        {paymentMethod === 'card' && (
                          <CheckCircle2 className="h-5 w-5 text-primary" />
                        )}
                      </button>
                      <button
                        onClick={() => setPaymentMethod('netbanking')}
                        className={cn(
                          'flex items-center gap-3 rounded-lg border p-3 text-left transition-colors',
                          paymentMethod === 'netbanking' ? 'border-primary bg-primary/5' : 'hover:bg-muted/50'
                        )}
                      >
                        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-purple-500/10">
                          <Building2 className="h-5 w-5 text-purple-500" />
                        </div>
                        <div className="flex-1">
                          <p className="font-medium">Net Banking</p>
                          <p className="text-sm text-muted-foreground">Redirect to bank</p>
                        </div>
                        {paymentMethod === 'netbanking' && (
                          <CheckCircle2 className="h-5 w-5 text-primary" />
                        )}
                      </button>
                    </div>
                  </div>
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setIsAddFundsOpen(false)}>
                    Cancel
                  </Button>
                  <Button disabled={!addAmount || parseInt(addAmount) < 1000}>
                    Add {addAmount ? formatCurrency(parseInt(addAmount)) : 'Funds'}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
        </div>

        {/* Balance Cards */}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {/* Available Balance */}
          <Card className="relative overflow-hidden">
            <div className="absolute inset-0 bg-gradient-to-br from-primary/10 via-transparent to-transparent" />
            <CardHeader className="relative pb-2">
              <CardDescription className="flex items-center justify-between">
                <span>Available Balance</span>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6"
                  onClick={() => setIsBalanceVisible(!isBalanceVisible)}
                >
                  {isBalanceVisible ? (
                    <Eye className="h-4 w-4" />
                  ) : (
                    <EyeOff className="h-4 w-4" />
                  )}
                </Button>
              </CardDescription>
              <CardTitle className="text-3xl">
                {isBalanceVisible ? formatCurrency(wallet.balance) : '********'}
              </CardTitle>
            </CardHeader>
            <CardContent className="relative">
              <p className="text-sm text-muted-foreground">
                Last recharge: {formatCurrency(wallet.lastRechargeAmount)} on{' '}
                {formatDate(wallet.lastRecharge)}
              </p>
            </CardContent>
          </Card>

          {/* Escrow Locked */}
          <Card>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <Lock className="h-3.5 w-3.5" />
                Escrow Locked
              </CardDescription>
              <CardTitle className="text-2xl text-amber-500">
                {formatCurrency(wallet.escrowLocked)}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">
                {escrowItems.length} active campaigns
              </p>
            </CardContent>
          </Card>

          {/* Pending Settlement */}
          <Card>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <Clock className="h-3.5 w-3.5" />
                Pending Settlement
              </CardDescription>
              <CardTitle className="text-2xl text-blue-500">
                {formatCurrency(wallet.pendingSettlement)}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">Processing payouts</p>
            </CardContent>
          </Card>

          {/* 30-Day Runway Projection */}
          <Card className={cn(
            'border-2',
            wallet.runwayDays < 14 ? 'border-red-300 bg-red-50/30' :
            wallet.runwayDays < 30 ? 'border-amber-300 bg-amber-50/30' :
            'border-green-300 bg-green-50/30'
          )}>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <TrendingUp className="h-3.5 w-3.5" />
                Runway Projection
              </CardDescription>
              <CardTitle className={cn(
                'text-2xl',
                wallet.runwayDays < 14 ? 'text-stage-disputed-fg' :
                wallet.runwayDays < 30 ? 'text-stage-negotiating-fg' :
                'text-stage-approved-fg'
              )}>
                {wallet.runwayDays} days
              </CardTitle>
            </CardHeader>
            <CardContent>
              <Progress 
                value={Math.min((wallet.runwayDays / 60) * 100, 100)} 
                className={cn(
                  'h-2 mb-2',
                  wallet.runwayDays < 14 && '[&>div]:bg-red-500',
                  wallet.runwayDays >= 14 && wallet.runwayDays < 30 && '[&>div]:bg-amber-500',
                  wallet.runwayDays >= 30 && '[&>div]:bg-green-500'
                )}
              />
              <p className="text-xs text-muted-foreground">
                Burn rate: {formatCurrency(wallet.projectedBurn30Days)}/mo
              </p>
            </CardContent>
          </Card>
        </div>

        {/* Tax Summary Cards */}
        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <CardHeader className="pb-2">
              <CardDescription>Total TDS Deducted (This FY)</CardDescription>
              <CardTitle className="text-xl flex items-center gap-2">
                {formatCurrency(wallet.totalTDSDeducted)}
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-6 w-6">
                      <FileText className="h-4 w-4" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Download Form 16A</TooltipContent>
                </Tooltip>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xs text-muted-foreground">
                TDS @ 1% deducted on creator payments (Sec. 194-O)
              </p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardDescription>Total GST Paid (This FY)</CardDescription>
              <CardTitle className="text-xl flex items-center gap-2">
                {formatCurrency(wallet.totalGSTPaid)}
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-6 w-6">
                      <Download className="h-4 w-4" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Download GST Summary</TooltipContent>
                </Tooltip>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xs text-muted-foreground">
                GST @ 18% on platform fees
              </p>
            </CardContent>
          </Card>
        </div>

        {/* Main Content Tabs */}
        <Tabs defaultValue="transactions" className="space-y-4">
          <TabsList>
            <TabsTrigger value="transactions" className="gap-2">
              <Receipt className="h-4 w-4" />
              Transactions
            </TabsTrigger>
            <TabsTrigger value="escrow" className="gap-2">
              <Lock className="h-4 w-4" />
              Escrow
            </TabsTrigger>
            <TabsTrigger value="payouts" className="gap-2">
              <ArrowUpRight className="h-4 w-4" />
              Payouts
            </TabsTrigger>
          </TabsList>

          {/* Transactions Tab */}
          <TabsContent value="transactions" className="space-y-4">
            {/* Filters */}
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="relative flex-1 sm:max-w-xs">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  placeholder="Search transactions..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-9"
                />
              </div>
              <div className="flex gap-2">
                <Select value={filterType} onValueChange={setFilterType}>
                  <SelectTrigger className="w-40">
                    <Filter className="mr-2 h-4 w-4" />
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">All Types</SelectItem>
                    <SelectItem value="credit">Credits</SelectItem>
                    <SelectItem value="debit">Debits</SelectItem>
                    <SelectItem value="escrow_lock">Escrow Locks</SelectItem>
                    <SelectItem value="escrow_release">Releases</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* Transactions List */}
            <Card>
              <ScrollArea className="h-[500px]">
                <div className="divide-y">
                  {filteredTransactions.map((transaction) => (
                    <div
                      key={transaction.id}
                      className="flex items-center gap-4 p-4 transition-colors hover:bg-muted/50"
                    >
                      <div
                        className={cn(
                          'flex h-10 w-10 items-center justify-center rounded-full',
                          transaction.type === 'credit' && 'bg-green-500/10',
                          transaction.type === 'debit' && 'bg-red-500/10',
                          transaction.type === 'escrow_lock' && 'bg-amber-500/10',
                          transaction.type === 'escrow_release' && 'bg-blue-500/10',
                          transaction.type === 'refund' && 'bg-purple-500/10'
                        )}
                      >
                        {getTransactionIcon(transaction.type)}
                      </div>

                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <p className="font-medium">{transaction.description}</p>
                          {getStatusBadge(transaction.status)}
                        </div>
                        <div className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                          <span>{formatDate(transaction.createdAt)} at {formatTime(transaction.createdAt)}</span>
                          {transaction.reference && (
                            <span className="flex items-center gap-1">
                              <FileText className="h-3 w-3" />
                              {transaction.reference}
                            </span>
                          )}
                          {transaction.campaign && (
                            <span className="text-primary">{transaction.campaign.name}</span>
                          )}
                        </div>
                        {transaction.creator && (
                          <div className="mt-1.5 flex items-center gap-2">
                            <Avatar className="h-5 w-5">
                              <AvatarImage src={transaction.creator.avatar} />
                              <AvatarFallback>{transaction.creator.name[0]}</AvatarFallback>
                            </Avatar>
                            <span className="text-sm">{transaction.creator.name}</span>
                          </div>
                        )}
                      </div>

                      <div className="text-right">
                        <p
                          className={cn(
                            'text-lg font-semibold',
                            transaction.type === 'credit' || transaction.type === 'refund'
                              ? 'text-green-500'
                              : transaction.type === 'debit' || transaction.type === 'escrow_release'
                              ? 'text-red-500'
                              : 'text-amber-500'
                          )}
                        >
                          {transaction.type === 'credit' || transaction.type === 'refund' ? '+' : '-'}
                          {formatCurrency(transaction.amount)}
                        </p>
                        {transaction.breakdown && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <p className="cursor-help text-xs text-muted-foreground underline decoration-dashed">
                                View breakdown
                              </p>
                            </TooltipTrigger>
                            <TooltipContent side="left" className="w-48">
                              <div className="space-y-1 text-sm">
                                <div className="flex justify-between">
                                  <span>Base</span>
                                  <span>{formatCurrency(transaction.breakdown.base)}</span>
                                </div>
                                <div className="flex justify-between">
                                  <span>Platform Fee (10%)</span>
                                  <span>{formatCurrency(transaction.breakdown.platformFee)}</span>
                                </div>
                                <div className="flex justify-between">
                                  <span>GST (18% on fee)</span>
                                  <span>{formatCurrency(transaction.breakdown.gst)}</span>
                                </div>
                                {transaction.breakdown.tds && (
                                  <div className="flex justify-between">
                                    <span>TDS (1% Sec. 194-O)</span>
                                    <span>{formatCurrency(transaction.breakdown.tds)}</span>
                                  </div>
                                )}
                              </div>
                            </TooltipContent>
                          </Tooltip>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </ScrollArea>
            </Card>
          </TabsContent>

          {/* Escrow Tab */}
          <TabsContent value="escrow" className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Lock className="h-5 w-5 text-amber-500" />
                  Active Escrow Holdings
                </CardTitle>
                <CardDescription>
                  Funds secured for ongoing campaigns. Released upon deliverable approval.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  {escrowItems.map((item) => (
                    <div
                      key={item.id}
                      className="flex items-center gap-4 rounded-lg border border-border/50 p-4"
                    >
                      <Avatar className="h-12 w-12">
                        <AvatarImage src={item.creatorAvatar} />
                        <AvatarFallback>{item.creatorName[0]}</AvatarFallback>
                      </Avatar>

                      <div className="min-w-0 flex-1">
                        <p className="font-medium">{item.campaignName}</p>
                        <p className="text-sm text-muted-foreground">
                          Creator: {item.creatorName}
                        </p>
                        <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                          <Lock className="h-3 w-3" />
                          Locked on {formatDate(item.lockedAt)}
                          {item.releaseDate && (
                            <>
                              <span>•</span>
                              <span>Est. release: {formatDate(item.releaseDate)}</span>
                            </>
                          )}
                        </div>
                      </div>

                      <div className="text-right">
                        <p className="text-lg font-semibold text-amber-500">
                          {formatCurrency(item.amount)}
                        </p>
                        <Badge
                          variant="secondary"
                          className={cn(
                            item.status === 'locked' && 'bg-amber-500/10 text-amber-500',
                            item.status === 'releasing' && 'bg-blue-500/10 text-blue-500',
                            item.status === 'disputed' && 'bg-red-500/10 text-red-500'
                          )}
                        >
                          {item.status.charAt(0).toUpperCase() + item.status.slice(1)}
                        </Badge>
                      </div>
                    </div>
                  ))}
                </div>

                {/* Escrow Summary */}
                <Separator className="my-6" />
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm text-muted-foreground">Total Locked in Escrow</p>
                    <p className="text-2xl font-bold text-amber-500">
                      {formatCurrency(wallet.escrowLocked)}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="text-sm text-muted-foreground">Protected by</p>
                    <p className="flex items-center gap-1.5 font-medium text-green-500">
                      <CheckCircle2 className="h-4 w-4" />
                      Secure Escrow
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* Escrow Info */}
            <Card className="border-primary/20 bg-primary/5">
              <CardContent className="flex items-start gap-4 p-4">
                <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10">
                  <AlertCircle className="h-5 w-5 text-primary" />
                </div>
                <div>
                  <p className="font-medium">How Escrow Works</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    When you start a campaign, funds are locked in escrow to guarantee payment to creators.
                    Once you approve the deliverables, funds are automatically released to the creator.
                    If there is a dispute, our team will mediate and ensure fair resolution.
                  </p>
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          {/* Payouts Tab */}
          <TabsContent value="payouts" className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle>Recent Payouts to Creators</CardTitle>
                <CardDescription>
                  Track payments released to creators from your campaigns.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  {transactions
                    .filter((t) => t.type === 'escrow_release')
                    .map((payout) => (
                      <div
                        key={payout.id}
                        className="flex items-center gap-4 rounded-lg border border-border/50 p-4"
                      >
                        {payout.creator && (
                          <Avatar className="h-12 w-12">
                            <AvatarImage src={payout.creator.avatar} />
                            <AvatarFallback>{payout.creator.name[0]}</AvatarFallback>
                          </Avatar>
                        )}

                        <div className="min-w-0 flex-1">
                          <p className="font-medium">{payout.creator?.name}</p>
                          <p className="text-sm text-muted-foreground">
                            {payout.campaign?.name}
                          </p>
                          <p className="mt-1 text-xs text-muted-foreground">
                            {formatDate(payout.createdAt)} at {formatTime(payout.createdAt)}
                          </p>
                        </div>

                        <div className="text-right">
                          <p className="text-lg font-semibold">{formatCurrency(payout.amount)}</p>
                          {getStatusBadge(payout.status)}
                        </div>
                      </div>
                    ))}
                </div>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </TooltipProvider>
  );
}
