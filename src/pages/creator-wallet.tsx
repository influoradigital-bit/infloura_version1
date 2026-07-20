import * as React from 'react';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Wallet,
  TrendingUp,
  Clock,
  CheckCircle2,
  IndianRupee,
  ArrowUpRight,
  ArrowDownRight,
  Building,
  FileText,
  Download,
  Calendar,
  Shield,
  AlertCircle,
  CreditCard,
  Loader2,
  ChevronRight,
  Receipt,
  Banknote,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  api,
  ApiError,
  isApiLive,
  creatorInvoicing,
  type CampaignServiceInvoice,
  type PlatformCommissionInvoice,
  type WalletTransactionRow,
  type PayoutMethod,
} from '@/lib/api';
import { useServiceInvoices } from '@/hooks/creator/useServiceInvoices';
import { useToast } from '@/hooks/use-toast';

// ---------------------------------------------------------------------------
// Live-wiring notes (ported from claude/api-connection-workflow-b62285):
//   GET  /wallet              → api.wallet.get('creator')        (availableBalance, escrowLocked)
//   GET  /wallet/transactions → api.wallet.transactions('creator') (History tab)
//
// Withdraw (POST /wallet/withdraw) and payout methods (GET/POST /wallet/payout-methods,
// PUT /wallet/payout-methods/:id/primary) are now wired to the live facade — see
// handleWithdraw / loadPayoutMethods / handleAddMethod / handleSetPrimary below.
// Withdraw sends a client-generated Idempotency-Key (B10 — WalletService rejects a
// withdrawal with no key).
//
// Still no facade coverage — remain mock-only in both mock and live mode:
//   - totalEarned / thisMonth / lastMonth (growth %) on the earnings hero card
//   - detailed payout breakdown (mockPayouts: tds/platformFee/gst/netAmount/utr,
//     brandName, campaignTitle) — there is no GET /wallet/payouts endpoint
//   - tax documents list
// ---------------------------------------------------------------------------

// Payout states
type PayoutStatus = 'QUEUED' | 'INITIATED' | 'PROCESSING' | 'PAID' | 'FAILED';

const payoutStatusConfig: Record<PayoutStatus, { label: string; color: string; bgColor: string }> = {
  QUEUED: { label: 'Queued', color: 'text-stage-draft-fg', bgColor: 'bg-stage-draft' },
  INITIATED: { label: 'Initiated', color: 'text-stage-outreach-fg', bgColor: 'bg-stage-outreach' },
  PROCESSING: { label: 'Processing', color: 'text-stage-negotiating-fg', bgColor: 'bg-stage-negotiating' },
  PAID: { label: 'Paid', color: 'text-stage-approved-fg', bgColor: 'bg-stage-approved' },
  FAILED: { label: 'Failed', color: 'text-stage-disputed-fg', bgColor: 'bg-stage-disputed' },
};

const mockEarningsData = {
  totalEarned: 425000,
  pendingPayout: 120000,
  inEscrow: 155000,
  thisMonth: 85000,
  lastMonth: 120000,
};

const mockPayouts = [
  {
    id: 'p1',
    brandName: 'Nykaa Fashion',
    campaignTitle: 'Winter Collection',
    grossAmount: 50000,
    tds: 500, // 1%
    platformFee: 5000, // 10%
    gst: 900, // 18% on platform fee
    netAmount: 43600,
    status: 'PAID' as PayoutStatus,
    paidAt: '2026-05-28',
    payoutMethod: 'UPI',
    utr: 'UTR123456789',
  },
  {
    id: 'p2',
    brandName: 'BoAt Lifestyle',
    campaignTitle: 'Earbuds Launch',
    grossAmount: 75000,
    tds: 750,
    platformFee: 7500,
    gst: 1350,
    netAmount: 65400,
    status: 'PROCESSING' as PayoutStatus,
    initiatedAt: '2026-06-01',
    payoutMethod: 'Bank',
  },
  {
    id: 'p3',
    brandName: 'Mamaearth',
    campaignTitle: 'Skincare Routine',
    grossAmount: 35000,
    tds: 350,
    platformFee: 3500,
    gst: 630,
    netAmount: 30520,
    status: 'QUEUED' as PayoutStatus,
    expectedAt: '2026-06-05',
    payoutMethod: 'UPI',
  },
];

interface WalletTransaction {
  id: string;
  type: 'EARNING' | 'PAYOUT';
  description: string;
  amount: number;
  date: string;
}

/**
 * Map a `/wallet/transactions` row (WalletTransactionRow — direction:
 * DEBIT|CREDIT, createdAt) onto this page's display shape (signed amount,
 * `date`). The facade's row type doesn't line up 1:1 with the UI's
 * EARNING/PAYOUT model, so this maps rather than casts.
 */
function mapWalletTransactionRow(row: WalletTransactionRow): WalletTransaction {
  const isCredit = row.direction === 'CREDIT';
  return {
    id: row.id,
    type: isCredit ? 'EARNING' : 'PAYOUT',
    description: row.description,
    amount: isCredit ? Math.abs(row.amount) : -Math.abs(row.amount),
    date: row.createdAt,
  };
}

const mockTransactions: WalletTransaction[] = [
  { id: 't1', type: 'EARNING', description: 'Winter Collection - Nykaa Fashion', amount: 50000, date: '2026-05-28' },
  { id: 't2', type: 'PAYOUT', description: 'Payout to UPI', amount: -43600, date: '2026-05-28' },
  { id: 't3', type: 'EARNING', description: 'Product Review - Samsung', amount: 35000, date: '2026-05-20' },
  { id: 't4', type: 'PAYOUT', description: 'Payout to Bank Account', amount: -30520, date: '2026-05-20' },
  { id: 't5', type: 'EARNING', description: 'Brand Collab - Zomato', amount: 45000, date: '2026-05-15' },
];

const mockTaxDocs = [
  { id: 'td1', title: 'Form 16A - Q4 FY25-26', period: 'Jan-Mar 2026', status: 'AVAILABLE' },
  { id: 'td2', title: 'Form 16A - Q3 FY25-26', period: 'Oct-Dec 2025', status: 'AVAILABLE' },
  { id: 'td3', title: 'Annual Statement FY25-26', period: 'Apr 2025 - Mar 2026', status: 'PENDING' },
];

function formatINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatInvoiceDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** D14 — PDF download trigger shared by both invoice lists on this page. */
function InvoicePdfButton({ onDownload, filename }: { onDownload: () => Promise<Blob>; filename: string }) {
  const { toast } = useToast();
  const [downloading, setDownloading] = React.useState(false);

  const handleDownload = async () => {
    setDownloading(true);
    try {
      const blob = await onDownload();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast({
        title: 'Couldn’t download invoice',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Button
      variant="outline"
      size="sm"
      onClick={handleDownload}
      disabled={downloading}
      aria-label={downloading ? `Downloading ${filename}` : `Download ${filename}`}
    >
      {downloading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" aria-hidden="true" />}
    </Button>
  );
}

function MarketplaceInvoiceStatusBadge({ status }: { status: 'ISSUED' | 'PAID' }) {
  return (
    <Badge
      className={cn(
        status === 'PAID' ? 'bg-stage-approved text-stage-approved-fg' : 'bg-stage-outreach text-stage-outreach-fg',
        'hover:' + (status === 'PAID' ? 'bg-stage-approved' : 'bg-stage-outreach'),
      )}
    >
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </Badge>
  );
}

/** D14 Doc#2 + Doc#3b — the creator's own service invoices and Influora's commission invoices to them. */
function InvoicesTabContent() {
  const { campaignInvoices, commissionInvoices, loading, error, refresh } = useServiceInvoices();

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
        <AlertCircle className="h-8 w-8 text-stage-disputed-fg" />
        <p className="font-medium">Couldn't load invoices</p>
        <p className="text-sm text-muted-foreground">{error}</p>
        <Button variant="outline" size="sm" onClick={() => void refresh()}>
          Retry
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <h3 className="font-medium text-sm text-muted-foreground">Your Service Invoices</h3>
        {campaignInvoices.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center justify-center gap-2 p-6 text-center">
              <FileText className="h-8 w-8 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                Your invoices appear here after a collaboration's escrow is released.
              </p>
            </CardContent>
          </Card>
        ) : (
          campaignInvoices.map((invoice: CampaignServiceInvoice) => (
            <Card key={invoice.id}>
              <CardContent className="p-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-medium truncate">{invoice.invoiceNumber}</p>
                    <p className="text-xs text-muted-foreground">{formatInvoiceDate(invoice.issuedAt)}</p>
                  </div>
                  <div className="flex items-center gap-3 flex-shrink-0">
                    <div className="text-right">
                      <p className="font-semibold">{formatINR(invoice.grossAmount)}</p>
                      <MarketplaceInvoiceStatusBadge status={invoice.status} />
                    </div>
                    <InvoicePdfButton
                      onDownload={() => creatorInvoicing.downloadCampaignInvoicePdf(invoice.id)}
                      filename={`${invoice.invoiceNumber || invoice.id}.pdf`}
                    />
                  </div>
                </div>
              </CardContent>
            </Card>
          ))
        )}
      </div>

      <div className="space-y-3">
        <h3 className="font-medium text-sm text-muted-foreground">Platform Commission Invoices</h3>
        {commissionInvoices.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center justify-center gap-2 p-6 text-center">
              <Receipt className="h-8 w-8 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                Influora's commission invoice to you appears here after a payout.
              </p>
            </CardContent>
          </Card>
        ) : (
          commissionInvoices.map((invoice: PlatformCommissionInvoice) => (
            <Card key={invoice.id}>
              <CardContent className="p-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-medium truncate">{invoice.invoiceNumber}</p>
                    <p className="text-xs text-muted-foreground">
                      {formatInvoiceDate(invoice.issuedAt)} &middot; {(invoice.feeBpsApplied / 100).toFixed(2)}% fee
                    </p>
                  </div>
                  <div className="flex items-center gap-3 flex-shrink-0">
                    <div className="text-right">
                      <p className="font-semibold">{formatINR(invoice.commissionAmount)}</p>
                      <MarketplaceInvoiceStatusBadge status={invoice.status} />
                    </div>
                    <InvoicePdfButton
                      onDownload={() => creatorInvoicing.downloadCommissionInvoicePdf(invoice.id)}
                      filename={`${invoice.invoiceNumber || invoice.id}.pdf`}
                    />
                  </div>
                </div>
              </CardContent>
            </Card>
          ))
        )}
      </div>
    </div>
  );
}

export default function CreatorWalletPage() {
  const liveApi = isApiLive();
  const { toast } = useToast();

  const [selectedPayout, setSelectedPayout] = React.useState<typeof mockPayouts[0] | null>(null);
  const [showPayoutSettings, setShowPayoutSettings] = React.useState(false);
  const [showWithdrawDialog, setShowWithdrawDialog] = React.useState(false);
  const [withdrawAmount, setWithdrawAmount] = React.useState('');
  const [isWithdrawing, setIsWithdrawing] = React.useState(false);
  const [withdrawError, setWithdrawError] = React.useState<string | null>(null);
  const [selectedPeriod, setSelectedPeriod] = React.useState('this-month');

  // GET /wallet/payout-methods (creator) — UPI/bank instruments for the Payout Settings dialog.
  const [payoutMethods, setPayoutMethods] = React.useState<PayoutMethod[]>([]);
  const [payoutMethodsUnavailable, setPayoutMethodsUnavailable] = React.useState(false);
  const [showAddMethod, setShowAddMethod] = React.useState(false);
  const [newMethodType, setNewMethodType] = React.useState<'UPI' | 'BANK'>('UPI');
  const [newMethodValue, setNewMethodValue] = React.useState('');
  const [newMethodIfsc, setNewMethodIfsc] = React.useState('');
  const [addingMethod, setAddingMethod] = React.useState(false);
  const [addMethodError, setAddMethodError] = React.useState<string | null>(null);
  const [settingPrimaryId, setSettingPrimaryId] = React.useState<string | null>(null);

  // Wallet balance + transactions — DISPLAY-only live data behind isApiLive(),
  // mock as fallback. Does not touch the withdraw mutation (see notes above).
  const [earnings, setEarnings] = React.useState(mockEarningsData);
  const [walletLoading, setWalletLoading] = React.useState(false);
  const [walletError, setWalletError] = React.useState<string | null>(null);
  const [transactions, setTransactions] = React.useState<WalletTransaction[]>(mockTransactions);

  // GET /creator/platform-fee — global fee shown for transparency (wallet.platformFee).
  // Unlike the balance/transaction effects, this runs in BOTH mock and live mode: the
  // mock facade returns the GLOBAL_DEFAULT (15%), so a creator always sees the fee that
  // will be deducted at escrow release, even in the demo build.
  const [platformFeePercent, setPlatformFeePercent] = React.useState<number | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const fee = await api.wallet.platformFee();
        if (!cancelled && fee) setPlatformFeePercent(fee.feePercent);
      } catch (err) {
        // Non-blocking transparency label — a fetch failure just hides it.
        if (!cancelled) console.error('Failed to load platform fee', err);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // GET /wallet — availableBalance -> pendingPayout (available to withdraw), escrowLocked -> inEscrow.
  // totalEarned/thisMonth/lastMonth have no facade field yet, so they stay mock-derived.
  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    (async () => {
      setWalletLoading(true);
      setWalletError(null);
      try {
        const remote = await api.wallet.get('creator');
        if (!cancelled && remote) {
          setEarnings((prev) => ({
            ...prev,
            pendingPayout: remote.availableBalance ?? prev.pendingPayout,
            inEscrow: remote.escrowLocked ?? prev.inEscrow,
          }));
        }
      } catch (err) {
        if (!cancelled) {
          console.error('Failed to load wallet balance', err);
          setWalletError('Could not refresh wallet balance. Showing last known data.');
        }
      } finally {
        if (!cancelled) setWalletLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  // GET /wallet/transactions — History tab.
  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    (async () => {
      try {
        const remote = await api.wallet.transactions('creator');
        // Array.isArray alone, deliberately no `.length > 0` -- a creator with genuinely zero
        // transactions gets back a real empty array; requiring non-empty treated that correct
        // empty response the same as "API call didn't return usable data," so it silently kept
        // the mock transactions forever instead (same bug fixed in dashboard-page.tsx and
        // creator-deals.tsx).
        if (!cancelled && Array.isArray(remote)) {
          setTransactions(remote.map(mapWalletTransactionRow));
        }
      } catch (err) {
        if (!cancelled) {
          toast({
            title: 'Couldn’t refresh transaction history',
            description: err instanceof ApiError ? err.message : 'Showing your last known activity.',
            variant: 'destructive',
          });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  // GET /wallet/payout-methods — Payout Settings dialog.
  const loadPayoutMethods = React.useCallback(async () => {
    if (!liveApi) return;
    setPayoutMethodsUnavailable(false);
    try {
      const methods = await api.wallet.getPayoutMethods('creator');
      setPayoutMethods(Array.isArray(methods) ? methods : []);
    } catch (err) {
      setPayoutMethods([]);
      setPayoutMethodsUnavailable(true);
      console.error('[creator-wallet] failed to load payout methods', err);
    }
  }, [liveApi]);

  React.useEffect(() => {
    loadPayoutMethods();
  }, [loadPayoutMethods]);

  const growthPercent = ((mockEarningsData.thisMonth - mockEarningsData.lastMonth) / mockEarningsData.lastMonth * 100).toFixed(1);
  const isPositiveGrowth = mockEarningsData.thisMonth > mockEarningsData.lastMonth;

  const handleWithdraw = async () => {
    const amount = parseFloat(withdrawAmount);
    if (!amount || amount < 100) return;
    setIsWithdrawing(true);
    setWithdrawError(null);
    if (!liveApi) {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      setIsWithdrawing(false);
      setShowWithdrawDialog(false);
      setWithdrawAmount('');
      return;
    }
    try {
      // Client-generated Idempotency-Key (B10 — WalletService rejects a withdrawal with
      // no key); matches the `${id}-${Date.now()}` convention used elsewhere in api.ts callers.
      const idempotencyKey = `withdraw-${Date.now()}`;
      await api.wallet.withdraw(amount, idempotencyKey);
      setShowWithdrawDialog(false);
      setWithdrawAmount('');
    } catch (err) {
      setWithdrawError(err instanceof ApiError ? err.message : 'Withdrawal failed. Please try again.');
    } finally {
      setIsWithdrawing(false);
    }
  };

  const handleAddMethod = async () => {
    if (!newMethodValue.trim()) return;
    setAddingMethod(true);
    setAddMethodError(null);
    try {
      await api.wallet.addPayoutMethod('creator', {
        type: newMethodType,
        accountOrVpa: newMethodValue.trim(),
        ifsc: newMethodType === 'BANK' ? newMethodIfsc.trim() : undefined,
      });
      setNewMethodValue('');
      setNewMethodIfsc('');
      setShowAddMethod(false);
      await loadPayoutMethods();
    } catch (err) {
      setAddMethodError(err instanceof ApiError ? err.message : 'Could not add payout method.');
    } finally {
      setAddingMethod(false);
    }
  };

  const handleSetPrimary = async (id: string) => {
    setSettingPrimaryId(id);
    try {
      await api.wallet.setPrimaryPayoutMethod('creator', id);
      await loadPayoutMethods();
    } catch (err) {
      toast({
        title: 'Couldn’t set primary payout method',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setSettingPrimaryId(null);
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-2xl">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold">Wallet</h1>
            <p className="text-muted-foreground">Track your earnings and payouts</p>
          </div>
          <div className="flex items-center gap-2">
            <Button onClick={() => setShowWithdrawDialog(true)}>
              <ArrowUpRight className="h-4 w-4 mr-2" />
              Withdraw
            </Button>
            <Button variant="outline" onClick={() => setShowPayoutSettings(true)}>
              <CreditCard className="h-4 w-4 mr-2" />
              Settings
            </Button>
          </div>
        </div>

        {walletError && (
          <div className="flex items-center gap-2 rounded-lg border border-stage-disputed-border bg-red-50 px-3 py-2 text-sm text-stage-disputed-fg mb-4">
            <AlertCircle className="h-4 w-4 flex-shrink-0" />
            <span>{walletError}</span>
          </div>
        )}

        {platformFeePercent !== null && (
          <div className="flex items-start gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm mb-4">
            <Shield className="h-4 w-4 mt-0.5 flex-shrink-0 text-muted-foreground" />
            <div>
              <p className="font-medium">Platform fee: {platformFeePercent}%</p>
              <p className="text-muted-foreground">
                Deducted when campaign earnings are released from escrow.
              </p>
            </div>
          </div>
        )}

        {/* Earnings Overview */}
        <Card className="bg-gradient-to-br from-primary to-accent text-white mb-6">
          <CardContent className="p-6">
            <div className="flex items-center justify-between mb-4">
              <p className="text-sm text-white/80">Total Earned</p>
              <div className="flex items-center gap-1 text-sm">
                {walletLoading ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : isPositiveGrowth ? (
                  <ArrowUpRight className="h-4 w-4" />
                ) : (
                  <ArrowDownRight className="h-4 w-4" />
                )}
                <span>{Math.abs(parseFloat(growthPercent))}% vs last month</span>
              </div>
            </div>
            <p className="text-4xl font-bold mb-6">{formatINR(mockEarningsData.totalEarned)}</p>

            <div className="grid grid-cols-3 gap-4">
              <div className="bg-white/10 rounded-lg p-3">
                <p className="text-xs text-white/80">Pending</p>
                <p className="text-lg font-semibold">{formatINR(earnings.pendingPayout)}</p>
              </div>
              <div className="bg-white/10 rounded-lg p-3">
                <p className="text-xs text-white/80">In Escrow</p>
                <p className="text-lg font-semibold">{formatINR(earnings.inEscrow)}</p>
              </div>
              <div className="bg-white/10 rounded-lg p-3">
                <p className="text-xs text-white/80">This Month</p>
                <p className="text-lg font-semibold">{formatINR(mockEarningsData.thisMonth)}</p>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Tabs */}
        <Tabs defaultValue="payouts" className="space-y-4">
          <TabsList className="w-full grid grid-cols-4">
            <TabsTrigger value="payouts">Payouts</TabsTrigger>
            <TabsTrigger value="history">History</TabsTrigger>
            <TabsTrigger value="invoices">Invoices</TabsTrigger>
            <TabsTrigger value="tax">Tax Docs</TabsTrigger>
          </TabsList>

          {/* Payouts Tab */}
          <TabsContent value="payouts" className="space-y-4">
            {/* Pending Payouts */}
            {mockPayouts.filter(p => p.status !== 'PAID').length > 0 && (
              <div className="space-y-3">
                <h3 className="font-medium text-sm text-muted-foreground">Pending Payouts</h3>
                {mockPayouts
                  .filter((p) => p.status !== 'PAID')
                  .map((payout) => {
                    const statusInfo = payoutStatusConfig[payout.status];
                    return (
                      <Card
                        key={payout.id}
                        className="cursor-pointer hover:shadow-md transition-all"
                        onClick={() => setSelectedPayout(payout)}
                      >
                        <CardContent className="p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div className="flex items-start gap-3 min-w-0">
                              <Avatar className="h-10 w-10 flex-shrink-0">
                                <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold text-sm">
                                  {payout.brandName.charAt(0)}
                                </AvatarFallback>
                              </Avatar>
                              <div className="min-w-0">
                                <p className="font-semibold truncate">{payout.brandName}</p>
                                <p className="text-sm text-muted-foreground truncate">
                                  {payout.campaignTitle}
                                </p>
                              </div>
                            </div>
                            <div className="text-right flex-shrink-0">
                              <p className="font-semibold text-stage-approved-fg">
                                {formatINR(payout.netAmount)}
                              </p>
                              <Badge className={cn(statusInfo.bgColor, statusInfo.color, 'hover:' + statusInfo.bgColor, 'mt-1')}>
                                {statusInfo.label}
                              </Badge>
                            </div>
                          </div>
                          
                          {/* Progress indicator for processing */}
                          {payout.status === 'PROCESSING' && (
                            <div className="mt-3 flex items-center gap-2">
                              <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden">
                                <div className="h-full w-2/3 bg-amber-500 rounded-full animate-pulse" />
                              </div>
                              <span className="text-xs text-muted-foreground">Processing</span>
                            </div>
                          )}
                        </CardContent>
                      </Card>
                    );
                  })}
              </div>
            )}

            {/* Completed Payouts */}
            <div className="space-y-3">
              <h3 className="font-medium text-sm text-muted-foreground">Completed</h3>
              {mockPayouts
                .filter((p) => p.status === 'PAID')
                .map((payout) => (
                  <Card
                    key={payout.id}
                    className="cursor-pointer hover:shadow-md transition-all"
                    onClick={() => setSelectedPayout(payout)}
                  >
                    <CardContent className="p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex items-start gap-3 min-w-0">
                          <div className="h-10 w-10 rounded-full bg-stage-approved flex items-center justify-center flex-shrink-0">
                            <CheckCircle2 className="h-5 w-5 text-stage-approved-fg" />
                          </div>
                          <div className="min-w-0">
                            <p className="font-semibold truncate">{payout.brandName}</p>
                            <p className="text-sm text-muted-foreground">
                              Paid on {new Date(payout.paidAt!).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                            </p>
                          </div>
                        </div>
                        <div className="text-right flex-shrink-0">
                          <p className="font-semibold">{formatINR(payout.netAmount)}</p>
                          <p className="text-xs text-muted-foreground">{payout.payoutMethod}</p>
                        </div>
                      </div>
                    </CardContent>
                  </Card>
                ))}
            </div>
          </TabsContent>

          {/* History Tab */}
          <TabsContent value="history" className="space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-medium">Transaction History</h3>
              <Select value={selectedPeriod} onValueChange={setSelectedPeriod}>
                <SelectTrigger className="w-32">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="this-month">This Month</SelectItem>
                  <SelectItem value="last-month">Last Month</SelectItem>
                  <SelectItem value="3-months">3 Months</SelectItem>
                  <SelectItem value="all">All Time</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              {transactions.length === 0 && (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  No transactions yet.
                </p>
              )}
              {transactions.map((tx) => (
                <Card key={tx.id}>
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className={cn(
                          'h-10 w-10 rounded-full flex items-center justify-center',
                          tx.type === 'EARNING' ? 'bg-stage-approved' : 'bg-stage-outreach'
                        )}>
                          {tx.type === 'EARNING' ? (
                            <ArrowDownRight className="h-5 w-5 text-stage-approved-fg" />
                          ) : (
                            <ArrowUpRight className="h-5 w-5 text-stage-outreach-fg" />
                          )}
                        </div>
                        <div>
                          <p className="font-medium text-sm">{tx.description}</p>
                          <p className="text-xs text-muted-foreground">
                            {new Date(tx.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}
                          </p>
                        </div>
                      </div>
                      <p className={cn(
                        'font-semibold',
                        tx.amount > 0 ? 'text-stage-approved-fg' : 'text-foreground'
                      )}>
                        {tx.amount > 0 ? '+' : ''}{formatINR(Math.abs(tx.amount))}
                      </p>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </TabsContent>

          {/* Invoices Tab (D14) */}
          <TabsContent value="invoices">
            <InvoicesTabContent />
          </TabsContent>

          {/* Tax Documents Tab */}
          <TabsContent value="tax" className="space-y-4">
            <div className="bg-blue-50 border border-stage-outreach-border rounded-lg p-4 mb-4">
              <div className="flex items-start gap-3">
                <FileText className="h-5 w-5 text-stage-outreach-fg flex-shrink-0 mt-0.5" />
                <div>
                  <p className="font-medium text-blue-800">Tax Compliance</p>
                  <p className="text-sm text-blue-700 mt-1">
                    TDS (1%) is deducted at source as per IT Act. Download Form 16A quarterly for filing.
                  </p>
                </div>
              </div>
            </div>

            <div className="space-y-3">
              {mockTaxDocs.map((doc) => (
                <Card key={doc.id}>
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className="h-10 w-10 rounded-lg bg-muted flex items-center justify-center">
                          <Receipt className="h-5 w-5 text-muted-foreground" />
                        </div>
                        <div>
                          <p className="font-medium">{doc.title}</p>
                          <p className="text-sm text-muted-foreground">{doc.period}</p>
                        </div>
                      </div>
                      {doc.status === 'AVAILABLE' ? (
                        <Button variant="outline" size="sm">
                          <Download className="h-4 w-4 mr-2" />
                          Download
                        </Button>
                      ) : (
                        <Badge variant="secondary">Coming Soon</Badge>
                      )}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </TabsContent>
        </Tabs>
      </div>

      {/* Payout Detail Dialog */}
      <Dialog open={!!selectedPayout} onOpenChange={() => setSelectedPayout(null)}>
        <DialogContent className="max-w-sm">
          {selectedPayout && (
            <>
              <DialogHeader>
                <DialogTitle>Payout Details</DialogTitle>
                <DialogDescription>
                  {selectedPayout.brandName} - {selectedPayout.campaignTitle}
                </DialogDescription>
              </DialogHeader>

              <div className="space-y-4">
                {/* Status */}
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Status</span>
                  <Badge className={cn(
                    payoutStatusConfig[selectedPayout.status].bgColor,
                    payoutStatusConfig[selectedPayout.status].color
                  )}>
                    {payoutStatusConfig[selectedPayout.status].label}
                  </Badge>
                </div>

                <Separator />

                {/* Breakdown */}
                <div className="space-y-2">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Gross Amount</span>
                    <span>{formatINR(selectedPayout.grossAmount)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Platform Fee (10%)</span>
                    <span className="text-stage-disputed-fg">-{formatINR(selectedPayout.platformFee)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">GST on Platform Fee</span>
                    <span className="text-stage-disputed-fg">-{formatINR(selectedPayout.gst)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">TDS (1%)</span>
                    <span className="text-stage-disputed-fg">-{formatINR(selectedPayout.tds)}</span>
                  </div>
                  <Separator />
                  <div className="flex items-center justify-between font-semibold">
                    <span>Net Payout</span>
                    <span className="text-stage-approved-fg">{formatINR(selectedPayout.netAmount)}</span>
                  </div>
                </div>

                <Separator />

                {/* Payout Method */}
                <div className="space-y-2">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Method</span>
                    <span>{selectedPayout.payoutMethod}</span>
                  </div>
                  {selectedPayout.utr && (
                    <div className="flex items-center justify-between text-sm">
                      <span className="text-muted-foreground">UTR/Reference</span>
                      <span className="font-mono text-xs">{selectedPayout.utr}</span>
                    </div>
                  )}
                  {selectedPayout.paidAt && (
                    <div className="flex items-center justify-between text-sm">
                      <span className="text-muted-foreground">Paid On</span>
                      <span>{new Date(selectedPayout.paidAt).toLocaleDateString('en-IN')}</span>
                    </div>
                  )}
                </div>
              </div>

              <DialogFooter>
                <Button variant="outline" className="w-full">
                  <Download className="h-4 w-4 mr-2" />
                  Download Receipt
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* Payout Settings Dialog */}
      <Dialog open={showPayoutSettings} onOpenChange={setShowPayoutSettings}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Payout Settings</DialogTitle>
            <DialogDescription>
              Manage your payout methods
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            {!liveApi ? (
              <>
                {/* Mock mode — static demo cards, no facade call. */}
                <Card className="border-violet-200 bg-violet-50">
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className="h-10 w-10 rounded-full bg-stage-approved flex items-center justify-center">
                          <span className="text-stage-approved-fg font-bold">₹</span>
                        </div>
                        <div>
                          <p className="font-medium">UPI</p>
                          <p className="text-sm text-muted-foreground">priya@okaxis</p>
                        </div>
                      </div>
                      <Badge>Primary</Badge>
                    </div>
                  </CardContent>
                </Card>
                <Card>
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className="h-10 w-10 rounded-full bg-stage-outreach flex items-center justify-center">
                          <Building className="h-5 w-5 text-blue-700" />
                        </div>
                        <div>
                          <p className="font-medium">Bank Account</p>
                          <p className="text-sm text-muted-foreground">HDFC ****4532</p>
                        </div>
                      </div>
                      <Button variant="outline" size="sm">Set Primary</Button>
                    </div>
                  </CardContent>
                </Card>
              </>
            ) : payoutMethodsUnavailable ? (
              <div className="flex items-center gap-2 rounded-lg border border-stage-disputed-border bg-red-50 px-3 py-2 text-sm text-stage-disputed-fg">
                <AlertCircle className="h-4 w-4 flex-shrink-0" />
                <span>Could not load payout methods. Try again shortly.</span>
              </div>
            ) : payoutMethods.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No payout methods yet. Add a UPI ID or bank account to withdraw funds.
              </p>
            ) : (
              payoutMethods.map((method) => (
                <Card key={method.id} className={method.isPrimary ? 'border-violet-200 bg-violet-50' : undefined}>
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div
                          className={cn(
                            'h-10 w-10 rounded-full flex items-center justify-center',
                            method.type === 'UPI' ? 'bg-stage-approved' : 'bg-stage-outreach',
                          )}
                        >
                          {method.type === 'UPI' ? (
                            <span className="text-stage-approved-fg font-bold">₹</span>
                          ) : (
                            <Building className="h-5 w-5 text-blue-700" />
                          )}
                        </div>
                        <div>
                          <p className="font-medium">{method.type === 'UPI' ? 'UPI' : 'Bank Account'}</p>
                          <p className="text-sm text-muted-foreground">{method.displayMask}</p>
                        </div>
                      </div>
                      {method.isPrimary ? (
                        <Badge>Primary</Badge>
                      ) : (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={settingPrimaryId === method.id}
                          onClick={() => handleSetPrimary(method.id)}
                        >
                          {settingPrimaryId === method.id ? (
                            <Loader2 className="h-4 w-4 animate-spin" />
                          ) : (
                            'Set Primary'
                          )}
                        </Button>
                      )}
                    </div>
                  </CardContent>
                </Card>
              ))
            )}

            {liveApi && showAddMethod && (
              <Card>
                <CardContent className="p-4 space-y-3">
                  <div className="space-y-2">
                    <Label htmlFor="new-method-type">Type</Label>
                    <Select value={newMethodType} onValueChange={(v) => setNewMethodType(v as 'UPI' | 'BANK')}>
                      <SelectTrigger id="new-method-type">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="UPI">UPI</SelectItem>
                        <SelectItem value="BANK">Bank Account</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="new-method-value">{newMethodType === 'UPI' ? 'UPI ID' : 'Account Number'}</Label>
                    <Input
                      id="new-method-value"
                      value={newMethodValue}
                      onChange={(e) => setNewMethodValue(e.target.value)}
                      placeholder={newMethodType === 'UPI' ? 'name@bank' : 'Account number'}
                    />
                  </div>
                  {newMethodType === 'BANK' && (
                    <div className="space-y-2">
                      <Label htmlFor="new-method-ifsc">IFSC Code</Label>
                      <Input
                        id="new-method-ifsc"
                        value={newMethodIfsc}
                        onChange={(e) => setNewMethodIfsc(e.target.value)}
                        placeholder="IFSC code"
                      />
                    </div>
                  )}
                  {addMethodError && <p className="text-sm text-stage-disputed-fg">{addMethodError}</p>}
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      onClick={handleAddMethod}
                      disabled={addingMethod || !newMethodValue.trim()}
                    >
                      {addingMethod ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Save'}
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setShowAddMethod(false)}>
                      Cancel
                    </Button>
                  </div>
                </CardContent>
              </Card>
            )}

            {(!liveApi || !showAddMethod) && (
              <Button
                variant="outline"
                className="w-full"
                onClick={liveApi ? () => setShowAddMethod(true) : undefined}
              >
                <CreditCard className="h-4 w-4 mr-2" />
                Add New Method
              </Button>
            )}
          </div>

          <DialogFooter>
            <Button onClick={() => setShowPayoutSettings(false)}>Done</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Withdraw Dialog */}
      <Dialog open={showWithdrawDialog} onOpenChange={setShowWithdrawDialog}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Withdraw Funds</DialogTitle>
            <DialogDescription>
              Transfer your available balance to your payout account
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-4">
            {/* Available Balance */}
            <div className="bg-gradient-to-br from-violet-50 to-purple-50 border border-violet-200 rounded-lg p-4 text-center">
              <p className="text-sm text-muted-foreground">Available to Withdraw</p>
              <p className="text-3xl font-bold text-stage-contracted-fg">{formatINR(earnings.pendingPayout)}</p>
              <p className="text-xs text-muted-foreground mt-1">
                {formatINR(earnings.inEscrow)} locked in escrow
              </p>
            </div>

            {withdrawError && (
              <div className="flex items-center gap-2 rounded-lg border border-stage-disputed-border bg-red-50 px-3 py-2 text-sm text-stage-disputed-fg">
                <AlertCircle className="h-4 w-4 flex-shrink-0" />
                <span>{withdrawError}</span>
              </div>
            )}

            {/* Amount Input */}
            <div className="space-y-2">
              <Label htmlFor="withdraw-amount">Amount to Withdraw</Label>
              <div className="relative">
                <IndianRupee className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  id="withdraw-amount"
                  type="number"
                  placeholder="Enter amount"
                  value={withdrawAmount}
                  onChange={(e) => setWithdrawAmount(e.target.value)}
                  className="pl-9"
                  max={earnings.pendingPayout}
                />
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">Min: ₹100</span>
                <Button 
                  variant="link" 
                  className="h-auto p-0 text-xs"
                  onClick={() => setWithdrawAmount(earnings.pendingPayout.toString())}
                >
                  Withdraw All
                </Button>
              </div>
            </div>

            {/* Payout Method */}
            <div className="space-y-2">
              <Label>Payout Method</Label>
              <Card className="border-violet-200 bg-violet-50">
                <CardContent className="p-3">
                  <div className="flex items-center gap-3">
                    <div className="h-8 w-8 rounded-full bg-stage-approved flex items-center justify-center">
                      <span className="text-stage-approved-fg font-bold text-sm">₹</span>
                    </div>
                    <div>
                      <p className="font-medium text-sm">UPI</p>
                      <p className="text-xs text-muted-foreground">priya@okaxis</p>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </div>

            {/* Fees */}
            {withdrawAmount && parseFloat(withdrawAmount) > 0 && (
              <div className="bg-muted/50 rounded-lg p-3 space-y-1 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Amount</span>
                  <span>{formatINR(parseFloat(withdrawAmount))}</span>
                </div>
                <div className="flex justify-between text-muted-foreground">
                  <span>Processing fee</span>
                  <span>-₹0</span>
                </div>
                <div className="border-t pt-1 mt-1 flex justify-between font-medium">
                  <span>You&apos;ll receive</span>
                  <span className="text-stage-approved-fg">{formatINR(parseFloat(withdrawAmount))}</span>
                </div>
              </div>
            )}

            {/* Info */}
            <div className="flex items-start gap-2 text-xs text-muted-foreground">
              <Clock className="h-3 w-3 mt-0.5 flex-shrink-0" />
              <span>Payouts are processed within 24-48 hours on business days</span>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setShowWithdrawDialog(false)}>
              Cancel
            </Button>
            <Button 
              onClick={handleWithdraw}
              disabled={isWithdrawing || !withdrawAmount || parseFloat(withdrawAmount) < 100 || parseFloat(withdrawAmount) > earnings.pendingPayout}
              className="bg-primary hover:bg-primary/90"
            >
              {isWithdrawing ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <>
                  <ArrowUpRight className="h-4 w-4 mr-2" />
                  Withdraw
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </CreatorLayout>
  );
}
