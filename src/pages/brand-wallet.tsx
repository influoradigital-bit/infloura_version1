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
  Clock,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Filter,
  Search,
  RefreshCw,
  Lock,
  Unlock,
  Eye,
  EyeOff,
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
import { Skeleton } from '@/components/ui/skeleton';
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
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import {
  api,
  isApiLive,
  ApiError,
  type WalletSummaryResponse,
  type WalletTransactionRow,
  type WalletTopUpResponse,
  type EscrowHoldRow,
} from '@/lib/api';
import type { Campaign } from '@/lib/types';
import { openRazorpayCheckout } from '@/lib/razorpay';
import { runwayTone as computeRunwayTone } from '@/lib/wallet-runway';
// P-2 (BrandF.md §48): the only production caller of escrow funding was
// MeeraWorkspace.tsx — a brand who wanted to fund a campaign outside the AI chat
// had no path at all. FundEscrowButton is the real, security-reviewed control
// (P11 commit-tier: human-click-only, server-authoritative amount, real Razorpay
// Checkout, inline top-up-if-short) — it already existed, fully built, imported
// by nothing but its own test file. This mounts it on the one other money surface
// a brand actually visits.
import { FundEscrowButton } from '@/components/feature/meera/FundEscrowButton';
import { FundEscrowStatus } from '@/components/brand/wallet/FundEscrowStatus';
// F-0324 (wallet-has-no-loading-affordance) — the same loading/error/retry shell
// dashboard-page.tsx uses (F-0245), extracted to src/components/shared/ so both pages share
// one implementation instead of two.
import { DashboardCardError } from '@/components/shared/DashboardCardError';

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

// Accepts null/undefined because several wallet figures (TDS/GST totals, burn
// rate, last recharge) have no backend source yet in live mode — render a
// neutral "—" rather than "₹NaN" or a fabricated number (B42).
const formatCurrency = (amount: number | null | undefined, currency = 'INR'): string => {
  if (amount == null || Number.isNaN(amount)) return '—';
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

/**
 * `crypto.randomUUID` only exists in secure contexts (https / localhost) — an http:// staging
 * host would throw. Idempotency keys just need per-submission uniqueness, not crypto strength,
 * so fall back to a timestamp+random id (mirrors the guarded pattern in src/lib/meera-api.ts).
 */
function safeRandomUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `topup-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

/**
 * Derive "last recharge" honestly from the real transaction list (GET
 * /wallet/transactions) rather than a hardcoded figure — no dedicated
 * "last recharge" field exists on WalletSummaryResponse. Returns null when
 * there's no completed credit yet (B42).
 */
function findLastRecharge(txs: Transaction[]): { amount: number; date: Date } | null {
  const credits = txs.filter((t) => t.type === 'credit' && t.status === 'completed');
  if (credits.length === 0) return null;
  const latest = credits.reduce((a, b) => (b.createdAt > a.createdAt ? b : a));
  return { amount: latest.amount, date: latest.createdAt };
}

/** Badge styling + label for a real GET /wallet/escrow row's status. */
function escrowStatusBadge(status: EscrowHoldRow['status']): { label: string; className: string } {
  switch (status) {
    case 'PENDING':
      return { label: 'Pending', className: 'bg-amber-500/10 text-amber-500' };
    case 'FUNDED':
      return { label: 'Funded', className: 'bg-blue-500/10 text-blue-500' };
    case 'RELEASED':
      return { label: 'Released', className: 'bg-green-500/10 text-green-500' };
    case 'CANCELLED':
      return { label: 'Cancelled', className: 'bg-red-500/10 text-red-500' };
    default:
      return { label: status, className: 'bg-muted text-muted-foreground' };
  }
}

const emptyWalletSummary: WalletSummaryResponse = {
  availableBalance: 0,
  escrowLocked: 0,
  pendingPayouts: 0,
  runwayDays: null,
};

/**
 * F-0324 (wallet-has-no-loading-affordance) — same three-way shape as dashboard-page.tsx's
 * `LoadStatus` (F-0245), tracked PER DATA REGION rather than one page-wide flag: this page loads
 * from three independent sources (loadWallet, loadEscrow, loadFundableCampaigns), and one
 * failing/slow fetch must not blank or spinner-lock a region that already loaded fine.
 *
 * The defect this closes: the file used to hold a `loading` boolean written on both edges of
 * `loadWallet` and read by nothing — a state variable that existed but changed nothing on
 * screen, indistinguishable from having no loading state at all. `walletStatus` below is that
 * same boolean's replacement, this time actually gating what renders.
 */
type LoadStatus = 'loading' | 'error' | 'ready';

export default function BrandWalletPage() {
  const [isBalanceVisible, setIsBalanceVisible] = React.useState(true);
  const [isAddFundsOpen, setIsAddFundsOpen] = React.useState(false);
  const [addAmount, setAddAmount] = React.useState('');
  const [paymentMethod, setPaymentMethod] = React.useState('upi');
  const [isAddingFunds, setIsAddingFunds] = React.useState(false);
  const [addFundsError, setAddFundsError] = React.useState<string | null>(null);
  // Minted once per logical top-up submission, reused across retries of that SAME submission
  // (network failure) so the server's idempotency dedupe isn't bypassed by a fresh key each
  // click. Reset to null on success, on dialog close, and whenever the amount changes (a changed
  // amount is a new submission, not a retry).
  const [topUpIdempotencyKey, setTopUpIdempotencyKey] = React.useState<string | null>(null);
  // The backend only returns a Razorpay ORDER — money is not credited until the webhook
  // confirms it. `topUpOrder` holds that order; `topUpStage` tracks what's happened with the
  // real Razorpay Checkout launcher (src/lib/razorpay.ts) since then. The client Checkout
  // success callback is NEVER trusted as proof of a credited wallet — only used to decide
  // when to start re-fetching the balance (see `handleTopUpCheckoutSuccess`).
  const [topUpOrder, setTopUpOrder] = React.useState<WalletTopUpResponse | null>(null);
  const [topUpStage, setTopUpStage] = React.useState<
    'awaiting_payment' | 'confirming' | 'confirmed' | 'dismissed'
  >('awaiting_payment');
  // Guards against opening the same Razorpay modal twice for one order (React StrictMode
  // double-invoke / re-render safe). Reset whenever a fresh order is minted or the human
  // explicitly asks to retry after dismissing the modal.
  const topUpCheckoutOpenRef = React.useRef(false);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [filterType, setFilterType] = React.useState('all');

  // H-20: live mode starts from an honest empty state and fetches real wallet
  // data; mock mode keeps the polished demo dataset above.
  const [walletSummary, setWalletSummary] = React.useState<WalletSummaryResponse>(emptyWalletSummary);
  const [transactions, setTransactions] = React.useState<Transaction[]>(
    isApiLive() ? [] : mockTransactions,
  );
  const [loadError, setLoadError] = React.useState<string | null>(null);
  // F-0324 — feeds the Balance Cards row, the Transactions tab and the Payouts tab (all three
  // read walletSummary/transactions, populated by the same loadWallet call). 'ready' immediately
  // in mock mode, where loadWallet never runs.
  const [walletStatus, setWalletStatus] = React.useState<LoadStatus>(isApiLive() ? 'loading' : 'ready');

  // H-20 applies equally here: live mode starts empty and fetches GET /wallet/escrow; mock mode
  // keeps rendering the polished mockEscrowItems demo dataset (handled where escrowItems is read).
  const [escrowRows, setEscrowRows] = React.useState<EscrowHoldRow[]>([]);
  const [escrowStatus, setEscrowStatus] = React.useState<LoadStatus>(isApiLive() ? 'loading' : 'ready');
  const [escrowError, setEscrowError] = React.useState<string | null>(null);

  // P-2: direct "Fund Escrow" — a brand's own ACTIVE campaigns to pick from.
  const [fundableCampaigns, setFundableCampaigns] = React.useState<Campaign[]>([]);
  const [fundableCampaignsStatus, setFundableCampaignsStatus] = React.useState<LoadStatus>(
    isApiLive() ? 'loading' : 'ready',
  );
  const [fundableCampaignsError, setFundableCampaignsError] = React.useState<string | null>(null);
  const [selectedFundCampaignId, setSelectedFundCampaignId] = React.useState<string>('');

  // F-0324 — `walletStatus` is written on every path (start / success / failure) and read by
  // the Balance Cards, Transactions and Payouts tabs below; this is the loading affordance the
  // original defect (a state written but read by nothing) removed.
  const loadWallet = React.useCallback(async () => {
    if (!isApiLive()) return;
    setWalletStatus('loading');
    setLoadError(null);
    try {
      const [summary, txRows] = await Promise.all([
        api.wallet.get('brand'),
        api.wallet.transactions('brand'),
      ]);
      setWalletSummary(summary ?? emptyWalletSummary);
      setTransactions(Array.isArray(txRows) ? txRows.map(mapWalletTransaction) : []);
      setWalletStatus('ready');
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : 'Could not load wallet data.');
      setWalletStatus('error');
    }
  }, []);

  const loadEscrow = React.useCallback(async () => {
    if (!isApiLive()) return;
    setEscrowStatus('loading');
    setEscrowError(null);
    try {
      const rows = await api.wallet.escrowList();
      setEscrowRows(Array.isArray(rows) ? rows : []);
      setEscrowStatus('ready');
    } catch (err) {
      setEscrowError(err instanceof ApiError ? err.message : 'Could not load escrow holdings.');
      setEscrowStatus('error');
    }
  }, []);

  // P-2: campaigns a brand could plausibly want to fund. ACTIVE only — a
  // DRAFT/PAUSED/COMPLETED/CANCELLED campaign isn't something you'd lock money
  // against from this control (funding still ultimately re-validates server-side
  // via EscrowService regardless of what this list shows).
  //
  // F-0324: a failed fetch here used to be swallowed into the same "no campaigns to fund"
  // empty copy a genuinely-empty account sees — indistinguishable from this page's core
  // defect. It now gets its own error state with a Retry (below), same as the other two
  // regions; the card stays scoped to itself so a failure here still can't blank the rest
  // of the page.
  const loadFundableCampaigns = React.useCallback(async () => {
    if (!isApiLive()) return;
    setFundableCampaignsStatus('loading');
    setFundableCampaignsError(null);
    try {
      const { campaigns: rows } = await api.campaigns.list({ status: 'ACTIVE', limit: 100 });
      setFundableCampaigns(rows);
      setFundableCampaignsStatus('ready');
    } catch (err) {
      setFundableCampaigns([]);
      setFundableCampaignsError(
        err instanceof ApiError ? err.message : 'Could not load your campaigns.',
      );
      setFundableCampaignsStatus('error');
    }
  }, []);

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      await Promise.all([loadWallet(), loadEscrow(), loadFundableCampaigns()]);
      if (cancelled) return;
    })();
    return () => {
      cancelled = true;
    };
  }, [loadWallet, loadEscrow, loadFundableCampaigns]);

  const handleEscrowFunded = React.useCallback(async () => {
    // Priya review (P-2): was clearing the selection synchronously here, which unmounted
    // FundEscrowButton (via the `key`) on the same tick the "Secured" state rendered — the
    // brand's only confirmation a real payment moved flashed for about one frame. Selection
    // now only clears from the explicit "Change campaign" action below, so the confirmed
    // state stays visible until the human deliberately moves on.
    await Promise.all([loadWallet(), loadEscrow()]);
  }, [loadWallet, loadEscrow]);

  // Priya review (P-2): campaigns that already have a PENDING or FUNDED hold are excluded —
  // without this, re-selecting an already-locked campaign mints a fresh idempotency key
  // (useEscrowFund.ts) and creates a SECOND hold + a second debit, since server-side dedupe
  // is keyed on idempotency key, not "does this campaign already have money locked."
  const campaignIdsWithActiveHold = React.useMemo(
    () =>
      new Set(
        escrowRows
          .filter((r) => r.status === 'PENDING' || r.status === 'FUNDED')
          .map((r) => r.campaignId),
      ),
    [escrowRows],
  );
  const selectableFundCampaigns = React.useMemo(
    () => fundableCampaigns.filter((c) => !campaignIdsWithActiveHold.has(c.id)),
    [fundableCampaigns, campaignIdsWithActiveHold],
  );

  // Changing the amount starts a new logical submission, not a retry of the last one — drop any
  // held idempotency key so the next confirm mints a fresh one.
  const changeAddAmount = (value: string) => {
    setAddAmount(value);
    setTopUpIdempotencyKey(null);
  };

  const handleAddFundsDialogChange = (open: boolean) => {
    setIsAddFundsOpen(open);
    if (!open) {
      setAddAmount('');
      setAddFundsError(null);
      setTopUpIdempotencyKey(null);
      setTopUpOrder(null);
      setTopUpStage('awaiting_payment');
      topUpCheckoutOpenRef.current = false;
    }
  };

  // Called after the Razorpay Checkout success callback fires. SECURITY: this is NOT proof
  // the wallet was credited — the ledger credit is applied asynchronously off the Razorpay
  // webhook (WalletTopUpService#confirmCredited). This only re-fetches the wallet/escrow
  // summaries so the balance updates as soon as the webhook lands, and shows an honest
  // "confirming" state in the meantime rather than claiming success prematurely.
  const handleTopUpCheckoutSuccess = React.useCallback(async () => {
    topUpCheckoutOpenRef.current = false;
    setTopUpStage('confirming');
    await Promise.all([loadWallet(), loadEscrow()]);
    setTopUpStage('confirmed');
  }, [loadWallet, loadEscrow]);

  // No money moved on this leg — let the human retry the SAME order (it hasn't been paid)
  // instead of forcing them to start a brand-new top-up submission.
  const handleTopUpCheckoutDismiss = React.useCallback(() => {
    topUpCheckoutOpenRef.current = false;
    setTopUpStage('dismissed');
  }, []);

  const retryTopUpCheckout = () => {
    setTopUpStage('awaiting_payment');
  };

  // Opens the real Razorpay Checkout for the top-up order once it's ready. Real launcher
  // (src/lib/razorpay.ts) — replaces the old "order created, no launcher exists" honest gap.
  React.useEffect(() => {
    if (!topUpOrder || topUpStage !== 'awaiting_payment' || topUpCheckoutOpenRef.current) return;
    topUpCheckoutOpenRef.current = true;
    openRazorpayCheckout({
      orderId: topUpOrder.razorpayOrderId,
      amount: topUpOrder.amount,
      currency: topUpOrder.currency,
      name: 'Influora',
      description: 'Add funds to wallet',
      onSuccess: () => void handleTopUpCheckoutSuccess(),
      onDismiss: () => handleTopUpCheckoutDismiss(),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [topUpOrder, topUpStage]);

  // POST /wallet/topup, then refresh balances + escrow. The Idempotency-Key is minted once per
  // logical submission (kept in topUpIdempotencyKey) and reused across retries of that same
  // submission after a network failure — a fresh key per retry would let a client retry double-
  // spend past the server's idempotency dedupe (this repo's QA flag, see state comment above).
  // In mock mode api.wallet.topUp resolves to a stub.
  const handleAddFunds = async () => {
    const amount = parseInt(addAmount, 10);
    if (!amount || amount < 1000) return;
    setIsAddingFunds(true);
    setAddFundsError(null);
    const idempotencyKey = topUpIdempotencyKey ?? safeRandomUUID();
    if (!topUpIdempotencyKey) setTopUpIdempotencyKey(idempotencyKey);
    try {
      const order = await api.wallet.topUp({ amount }, idempotencyKey);
      // Success closes out this submission — the key must not be reused for a future one.
      setTopUpIdempotencyKey(null);
      topUpCheckoutOpenRef.current = false;
      setTopUpStage('awaiting_payment');
      setTopUpOrder(order);
    } catch (e) {
      // Keep the same idempotency key held so a user-initiated retry of this submission reuses it.
      setAddFundsError(e instanceof ApiError ? e.message : 'Could not add funds. Please try again.');
    } finally {
      setIsAddingFunds(false);
    }
  };

  // Wallet card data: real numbers in live mode, polished demo numbers in mock mode.
  // WalletSummaryResponse only carries availableBalance/escrowLocked/pendingPayouts/
  // runwayDays — TDS/GST totals, projected burn, and suggested recharge have no
  // backend source yet, so live mode renders an honest null (→ "—") instead of the
  // mock figures. Last recharge is derived from the real transaction list (B42).
  const liveLastRecharge = isApiLive() ? findLastRecharge(transactions) : null;
  const wallet = isApiLive()
    ? {
        balance: walletSummary.availableBalance,
        currency: mockWalletData.currency,
        escrowLocked: walletSummary.escrowLocked,
        pendingSettlement: walletSummary.pendingPayouts,
        // F-0099: keep the backend's `null` (dormant/funded wallet, no spend in the trailing
        // window → undefined/infinite runway). Coercing to `0` flashed a false red CRITICAL
        // alarm because `null < 14`/`0 < 14` both read as "critical" in the card below.
        runwayDays: walletSummary.runwayDays ?? null,
        lastRecharge: liveLastRecharge?.date ?? null,
        lastRechargeAmount: liveLastRecharge?.amount ?? null,
        projectedBurn30Days: null as number | null,
        suggestedRecharge: null as number | null,
        totalTDSDeducted: null as number | null,
        totalGSTPaid: null as number | null,
      }
    : {
        balance: mockWalletData.balance,
        currency: mockWalletData.currency,
        escrowLocked: mockWalletData.escrowLocked,
        pendingSettlement: mockWalletData.pendingSettlement,
        runwayDays: mockWalletData.runwayDays,
        lastRecharge: mockWalletData.lastRecharge as Date | null,
        lastRechargeAmount: mockWalletData.lastRechargeAmount as number | null,
        projectedBurn30Days: mockWalletData.projectedBurn30Days as number | null,
        suggestedRecharge: mockWalletData.suggestedRecharge as number | null,
        totalTDSDeducted: mockWalletData.totalTDSDeducted as number | null,
        totalGSTPaid: mockWalletData.totalGSTPaid as number | null,
      };

  // F-0099: resolve the runway health tone ONCE, null-safely, via the shared pinned helper
  // (src/lib/wallet-runway.ts + its regression test). `runwayDays === null` means a dormant/funded
  // wallet (undefined/effectively-infinite runway) that must read 'healthy', never 'critical' — in
  // JS `null < 14` coerces to `0 < 14` (true), so raw comparisons would flash red on a healthy wallet.
  const runwayDays = wallet.runwayDays;
  const runwayTone = computeRunwayTone(runwayDays);

  // Live mode renders real GET /wallet/escrow rows (escrowRows); mock mode keeps the polished
  // mockEscrowItems demo dataset. escrowActiveCount feeds the summary card above the tabs.
  const escrowActiveCount = isApiLive() ? escrowRows.length : mockEscrowItems.length;

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

  // F-0324 — same source (transactions, from loadWallet) the Payouts tab reads; extracted so
  // the tab's loading/error/empty gating below can check its length without re-filtering inline.
  const brandPayouts = transactions.filter((t) => t.type === 'escrow_release');

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
            {/* F-0264: no transaction-export endpoint exists anywhere in src/lib/api.ts,
                src/lib/meera-api.ts, or the backend WalletController — this used to render as
                a live, clickable button with no handler behind it. Disabled permanently (not
                just while `loading`) with a reason a screen reader / hover can reach, instead
                of pretending the feature exists. The Tooltip is attached to the wrapping
                `span`, not the `Button` itself, because `disabled` sets pointer-events:none on
                the button and would otherwise swallow the hover before Radix ever sees it. */}
            <Tooltip>
              <TooltipTrigger asChild>
                <span tabIndex={0} className="inline-flex">
                  <Button variant="outline" className="gap-2" disabled aria-disabled="true">
                    <Download className="h-4 w-4" />
                    Export
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>Transaction export isn&apos;t available yet — coming soon.</TooltipContent>
            </Tooltip>
            <Dialog open={isAddFundsOpen} onOpenChange={handleAddFundsDialogChange}>
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
                {topUpOrder ? (
                  // Honest post-submit state: the backend only created a Razorpay ORDER — the
                  // ledger is credited asynchronously off the payment webhook, never here.
                  // `topUpStage` reflects what the real Checkout launcher (src/lib/razorpay.ts)
                  // has reported, but the Checkout callback itself is never trusted as proof of
                  // a credit — only used to decide when to re-fetch the wallet balance.
                  <div className="space-y-4 py-4">
                    <div className="flex items-start gap-3 rounded-lg border border-primary/20 bg-primary/5 p-4">
                      {topUpStage === 'confirmed' ? (
                        <CheckCircle2 className="h-5 w-5 flex-shrink-0 text-green-500" />
                      ) : (
                        <AlertCircle className="h-5 w-5 flex-shrink-0 text-primary" />
                      )}
                      <div className="space-y-1">
                        <p className="font-medium">
                          {topUpStage === 'awaiting_payment' && 'Opening payment window…'}
                          {topUpStage === 'confirming' && 'Confirming your payment…'}
                          {topUpStage === 'confirmed' && 'Payment received'}
                          {topUpStage === 'dismissed' && 'Payment window closed'}
                        </p>
                        <p className="text-sm text-muted-foreground">
                          {topUpStage === 'awaiting_payment' &&
                            'Complete the payment in the Razorpay window to add funds to your wallet.'}
                          {topUpStage === 'confirming' &&
                            'Your balance updates automatically once the payment webhook confirms it — this can take a few seconds.'}
                          {topUpStage === 'confirmed' &&
                            'Your balance will reflect the top-up as soon as the payment is confirmed. If it doesn’t update within a minute, refresh this page.'}
                          {topUpStage === 'dismissed' &&
                            'No payment was made and no money moved. You can retry this same order or cancel.'}
                        </p>
                      </div>
                    </div>
                    <div className="space-y-2 rounded-lg border border-border/50 p-4 text-sm">
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Order ID</span>
                        <span className="font-mono">{topUpOrder.razorpayOrderId}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Amount</span>
                        <span>{formatCurrency(topUpOrder.amount, topUpOrder.currency)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Status</span>
                        <span>{topUpOrder.status}</span>
                      </div>
                    </div>
                    {topUpStage === 'dismissed' && (
                      <Button onClick={retryTopUpCheckout} className="w-full">
                        Retry payment
                      </Button>
                    )}
                  </div>
                ) : (
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
                            onClick={() => changeAddAmount(preset.amount.toString())}
                          >
                            {preset.label}
                          </Button>
                        ))}
                      </div>
                    </div>

                    {/* Suggested Recharge based on Pipeline Burn — no backend
                        source for a burn projection yet (B42), so this nudge
                        only renders in mock/demo mode, never with a fabricated
                        number in live mode. */}
                    {!isApiLive() && wallet.suggestedRecharge != null && runwayDays != null && runwayDays < 45 && (
                      <div className="rounded-lg border border-stage-negotiating-border bg-amber-50 p-3">
                        <div className="flex items-start gap-3">
                          <AlertCircle className="h-5 w-5 text-stage-negotiating-fg flex-shrink-0 mt-0.5" />
                          <div className="space-y-1">
                            <p className="text-sm font-medium text-amber-800">
                              Based on your pipeline, we recommend recharging:
                            </p>
                            <button
                              onClick={() => changeAddAmount(String(wallet.suggestedRecharge))}
                              className="text-lg font-bold text-amber-700 hover:underline"
                            >
                              {formatCurrency(wallet.suggestedRecharge)}
                            </button>
                            <p className="text-xs text-stage-negotiating-fg">
                              Current runway: {runwayDays != null ? `${runwayDays} days` : '—'} | Projected burn: {formatCurrency(wallet.projectedBurn30Days)}/month
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
                          onChange={(e) => changeAddAmount(e.target.value)}
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
                    {addFundsError && (
                      <p className="text-sm text-destructive-foreground">{addFundsError}</p>
                    )}
                  </div>
                )}
                <DialogFooter>
                  {topUpOrder ? (
                    <Button onClick={() => handleAddFundsDialogChange(false)}>Done</Button>
                  ) : (
                    <>
                      <Button
                        variant="outline"
                        onClick={() => handleAddFundsDialogChange(false)}
                        disabled={isAddingFunds}
                      >
                        Cancel
                      </Button>
                      <Button
                        onClick={handleAddFunds}
                        disabled={!addAmount || parseInt(addAmount) < 1000 || isAddingFunds}
                      >
                        {isAddingFunds
                          ? 'Adding…'
                          : `Add ${addAmount ? formatCurrency(parseInt(addAmount)) : 'Funds'}`}
                      </Button>
                    </>
                  )}
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
        </div>

        {/* Balance Cards */}
        {/* F-0324 — this grid renders real ledger figures (balance, escrow, pending
            settlement, runway). While loadWallet is in flight or has failed, walletSummary
            still holds its initial all-zero default — rendering the cards as-is here would
            show a confident "₹0" indistinguishable from a genuinely empty wallet. loading and
            error each get their own visually distinct state; only 'ready' renders real
            numbers. */}
        {/* GATE-F0324-CARDS-START */}
        {walletStatus === 'loading' && (
          <div
            className="grid gap-4 md:grid-cols-2 lg:grid-cols-4"
            role="status"
            aria-label="Loading wallet balances"
          >
            <Skeleton className="h-32 w-full rounded-xl" />
            <Skeleton className="h-32 w-full rounded-xl" />
            <Skeleton className="h-32 w-full rounded-xl" />
            <Skeleton className="h-32 w-full rounded-xl" />
          </div>
        )}
        {walletStatus === 'error' && (
          <Card>
            <CardContent className="pt-6">
              <DashboardCardError
                message={loadError ?? 'Could not load wallet data.'}
                onRetry={loadWallet}
              />
            </CardContent>
          </Card>
        )}
        {walletStatus === 'ready' && (
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
                {wallet.lastRechargeAmount != null && wallet.lastRecharge
                  ? <>Last recharge: {formatCurrency(wallet.lastRechargeAmount)} on{' '}
                    {formatDate(wallet.lastRecharge)}</>
                  : 'No recharge yet'}
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
                {/* F-0324 — escrowActiveCount comes from a SEPARATE fetch (loadEscrow) than the
                    amount above (loadWallet). walletStatus === 'ready' only vouches for the
                    amount; the count needs its own honest state so it never reads as a
                    confirmed zero while its own fetch is still in flight or has failed. */}
                {escrowStatus === 'loading'
                  ? 'Loading…'
                  : escrowStatus === 'error'
                    ? 'Count unavailable'
                    : `${escrowActiveCount} active campaigns`}
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

          {/* 30-Day Runway Projection — F-0099: null runway (dormant/funded) reads healthy, not red */}
          <Card className={cn(
            'border-2',
            runwayTone === 'critical' ? 'border-red-300 bg-red-50/30' :
            runwayTone === 'warning' ? 'border-amber-300 bg-amber-50/30' :
            'border-green-300 bg-green-50/30'
          )}>
            <CardHeader className="pb-2">
              <CardDescription className="flex items-center gap-1.5">
                <TrendingUp className="h-3.5 w-3.5" />
                Runway Projection
              </CardDescription>
              <CardTitle className={cn(
                'text-2xl',
                runwayTone === 'critical' ? 'text-stage-disputed-fg' :
                runwayTone === 'warning' ? 'text-stage-negotiating-fg' :
                'text-stage-approved-fg'
              )}>
                {runwayDays != null ? `${runwayDays} days` : 'Healthy'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <Progress
                value={runwayDays != null ? Math.min((runwayDays / 60) * 100, 100) : 100}
                className={cn(
                  'h-2 mb-2',
                  runwayTone === 'critical' && '[&>div]:bg-red-500',
                  runwayTone === 'warning' && '[&>div]:bg-amber-500',
                  runwayTone === 'healthy' && '[&>div]:bg-green-500'
                )}
              />
              <p className="text-xs text-muted-foreground">
                Burn rate: {wallet.projectedBurn30Days != null
                  ? `${formatCurrency(wallet.projectedBurn30Days)}/mo`
                  : 'Not enough data yet'}
              </p>
            </CardContent>
          </Card>
        </div>
        )}
        {/* GATE-F0324-CARDS-END */}

        {/* Tax Summary Cards */}
        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <CardHeader className="pb-2">
              <CardDescription>Total TDS Deducted (This FY)</CardDescription>
              <CardTitle className="text-xl flex items-center gap-2">
                {formatCurrency(wallet.totalTDSDeducted)}
                {/* F-0264: no Form 16A (TDS certificate) generation endpoint exists on the
                    backend — WalletController has no such route, and no InvoiceService/
                    GstSplitUtil path produces one either. Disabled + reason instead of a
                    silent no-op on a tax-compliance control; see the Export button above for
                    why the Tooltip wraps a `span` rather than sitting on the disabled Button
                    directly. */}
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span tabIndex={0} className="inline-flex">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        disabled
                        aria-disabled="true"
                        aria-label="Download Form 16A — not available yet"
                      >
                        <FileText className="h-4 w-4" />
                      </Button>
                    </span>
                  </TooltipTrigger>
                  <TooltipContent>Form 16A isn&apos;t available yet — coming soon.</TooltipContent>
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
                {/* F-0264: no GST-summary generation endpoint exists on the backend either —
                    same gap as Form 16A above. Disabled + reason, not a silent no-op. */}
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span tabIndex={0} className="inline-flex">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        disabled
                        aria-disabled="true"
                        aria-label="Download GST Summary — not available yet"
                      >
                        <Download className="h-4 w-4" />
                      </Button>
                    </span>
                  </TooltipTrigger>
                  <TooltipContent>GST summary isn&apos;t available yet — coming soon.</TooltipContent>
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
            {/* F-0324 — transactions comes from loadWallet, same as the Balance Cards above;
                loading/error/genuinely-empty need the same three visually distinct treatments
                here, not just on the balance figures. */}
            <Card>
              {walletStatus === 'loading' && (
                <div className="space-y-3 p-4" role="status" aria-label="Loading transactions">
                  <Skeleton className="h-16 w-full" />
                  <Skeleton className="h-16 w-full" />
                  <Skeleton className="h-16 w-full" />
                </div>
              )}
              {walletStatus === 'error' && (
                <div className="p-4">
                  <DashboardCardError
                    message={loadError ?? 'Could not load wallet data.'}
                    onRetry={loadWallet}
                  />
                </div>
              )}
              {walletStatus === 'ready' && filteredTransactions.length === 0 && (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  No transactions to show.
                </p>
              )}
              {walletStatus === 'ready' && filteredTransactions.length > 0 && (
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
              )}
            </Card>
          </TabsContent>

          {/* Escrow Tab */}
          <TabsContent value="escrow" className="space-y-4">
            {/* P-2 (BrandF.md §48): escrow funding used to be reachable only through the
                Meera AI chat (MeeraWorkspace.tsx's hardcoded MEERA_DEMO_CAMPAIGN_ID demo
                flow) — no direct control existed anywhere else. */}
            {isApiLive() && (
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <IndianRupee className="h-5 w-5 text-primary" />
                    Fund Campaign Escrow
                  </CardTitle>
                  <CardDescription>
                    Lock funds for one of your active campaigns directly from here — the same
                    server-authoritative flow Meera's chat uses, no chat detour required.
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  {/* F-0131: polite live region so screen readers hear this card's state changes —
                      the loading/empty text and, critically, the FundEscrowButton's appearance
                      after a campaign is picked (an otherwise-silent DOM insertion). Visually
                      hidden; the visible content below is unchanged. */}
                  <FundEscrowStatus
                    loading={fundableCampaignsStatus === 'loading'}
                    error={fundableCampaignsStatus === 'error'}
                    selectableCount={selectableFundCampaigns.length}
                    totalCount={fundableCampaigns.length}
                    selectedId={selectedFundCampaignId}
                    selectedTitle={
                      fundableCampaigns.find((c) => c.id === selectedFundCampaignId)?.title
                    }
                  />
                  {fundableCampaignsStatus === 'loading' ? (
                    <div className="space-y-2 py-1" role="status" aria-label="Loading your campaigns">
                      <Skeleton className="h-9 w-full" />
                      <Skeleton className="h-9 w-2/3" />
                    </div>
                  ) : fundableCampaignsStatus === 'error' ? (
                    <DashboardCardError
                      message={fundableCampaignsError ?? 'Could not load your campaigns.'}
                      onRetry={loadFundableCampaigns}
                    />
                  ) : selectableFundCampaigns.length === 0 ? (
                    <p className="py-2 text-sm text-muted-foreground">
                      {fundableCampaigns.length === 0
                        ? 'No active campaigns to fund right now.'
                        : 'Every active campaign already has funds locked.'}
                    </p>
                  ) : (
                    <>
                      <div className="space-y-2">
                        <Label htmlFor="fund-escrow-campaign">Campaign</Label>
                        <Select
                          value={selectedFundCampaignId}
                          onValueChange={setSelectedFundCampaignId}
                          // Priya review (P-2): locked once a campaign is chosen so a stray click
                          // can't swap the id out from under an in-flight Razorpay checkout —
                          // useEscrowFund has no unmount cleanup for its poll/retry timers, so a
                          // silent switch mid-flow would orphan them. "Change campaign" below is
                          // the only way out, a deliberate action instead of an accidental one.
                          disabled={!!selectedFundCampaignId}
                        >
                          <SelectTrigger id="fund-escrow-campaign">
                            <SelectValue placeholder="Choose a campaign to fund" />
                          </SelectTrigger>
                          <SelectContent>
                            {selectableFundCampaigns.map((c) => (
                              <SelectItem key={c.id} value={c.id}>
                                {c.title}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                      {selectedFundCampaignId && (
                        <div className="space-y-2">
                          <FundEscrowButton
                            key={selectedFundCampaignId}
                            campaignId={selectedFundCampaignId}
                            displayAmount={
                              fundableCampaigns.find((c) => c.id === selectedFundCampaignId)
                                ?.budget?.max
                            }
                            onFunded={handleEscrowFunded}
                          />
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="w-full text-xs text-muted-foreground"
                            onClick={() => setSelectedFundCampaignId('')}
                          >
                            Change campaign
                          </Button>
                        </div>
                      )}
                    </>
                  )}
                </CardContent>
              </Card>
            )}

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
                {isApiLive() ? (
                  <div className="space-y-4">
                    {/* F-0324 — loading, error (with retry) and genuinely-empty are three
                        visually distinct states; a still-loading or failed fetch must never
                        render the same "No escrow holdings yet" copy a real empty account sees. */}
                    {escrowStatus === 'loading' && (
                      <div className="space-y-3" role="status" aria-label="Loading escrow holdings">
                        <Skeleton className="h-20 w-full rounded-lg" />
                        <Skeleton className="h-20 w-full rounded-lg" />
                      </div>
                    )}
                    {escrowStatus === 'error' && (
                      <DashboardCardError
                        message={escrowError ?? 'Could not load escrow holdings.'}
                        onRetry={loadEscrow}
                      />
                    )}
                    {escrowStatus === 'ready' && escrowRows.length === 0 && (
                      <p className="py-6 text-sm text-muted-foreground">
                        No escrow holdings yet. Funds locked for a campaign will show up here.
                      </p>
                    )}
                    {escrowStatus === 'ready' && escrowRows.length > 0 &&
                      escrowRows.map((row) => {
                        const badge = escrowStatusBadge(row.status);
                        return (
                          <div
                            key={row.escrowHoldId}
                            className="flex items-center gap-4 rounded-lg border border-border/50 p-4"
                          >
                            <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-full bg-amber-500/10">
                              <Lock className="h-5 w-5 text-amber-500" />
                            </div>

                            <div className="min-w-0 flex-1">
                              <p className="font-medium">Campaign {row.campaignId}</p>
                              {row.milestoneId && (
                                <p className="text-sm text-muted-foreground">
                                  Milestone: {row.milestoneId}
                                </p>
                              )}
                              <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                                <Lock className="h-3 w-3" />
                                {row.fundedAt
                                  ? `Funded on ${formatDate(new Date(row.fundedAt))}`
                                  : 'Not yet funded'}
                              </div>
                            </div>

                            <div className="text-right">
                              <p className="text-lg font-semibold text-amber-500">
                                {formatCurrency(row.amount, row.currency)}
                              </p>
                              <Badge variant="secondary" className={badge.className}>
                                {badge.label}
                              </Badge>
                            </div>
                          </div>
                        );
                      })}
                  </div>
                ) : (
                  <div className="space-y-4">
                    {mockEscrowItems.map((item) => (
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
                )}

                {/* Escrow Summary */}
                <Separator className="my-6" />
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm text-muted-foreground">Total Locked in Escrow</p>
                    <p className="text-2xl font-bold text-amber-500">
                      {/* F-0324 — this figure reads wallet.escrowLocked (loadWallet's region)
                          but renders unconditionally regardless of the Escrow tab's own
                          escrowStatus above; without this it would show a stale/zero amount
                          while walletStatus is still 'loading' or has errored. */}
                      {walletStatus === 'ready'
                        ? formatCurrency(wallet.escrowLocked)
                        : walletStatus === 'loading'
                          ? 'Loading…'
                          : '—'}
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
                {/* F-0324 — same loadWallet region as the Transactions tab; a still-loading or
                    failed fetch must not render as "no payouts yet". */}
                {walletStatus === 'loading' && (
                  <div className="space-y-3" role="status" aria-label="Loading payouts">
                    <Skeleton className="h-16 w-full" />
                    <Skeleton className="h-16 w-full" />
                  </div>
                )}
                {walletStatus === 'error' && (
                  <DashboardCardError
                    message={loadError ?? 'Could not load wallet data.'}
                    onRetry={loadWallet}
                  />
                )}
                {walletStatus === 'ready' && brandPayouts.length === 0 && (
                  <p className="py-10 text-center text-sm text-muted-foreground">
                    No payouts released to creators yet.
                  </p>
                )}
                {walletStatus === 'ready' && brandPayouts.length > 0 && (
                <div className="space-y-4">
                  {brandPayouts.map((payout) => (
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
                )}
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </TooltipProvider>
  );
}
