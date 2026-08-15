import * as React from 'react';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
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
  Clock,
  IndianRupee,
  ArrowUpRight,
  ArrowDownRight,
  Building,
  FileText,
  Download,
  Shield,
  AlertCircle,
  CreditCard,
  Loader2,
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
  type CreatorPayoutRow,
  type PayoutMethod,
} from '@/lib/api';
import { useServiceInvoices } from '@/hooks/creator/useServiceInvoices';
import { useToast } from '@/hooks/use-toast';

/**
 * `crypto.randomUUID` only exists in secure contexts (https / localhost) — an http:// staging
 * host would throw. Idempotency keys just need per-submission uniqueness, not crypto strength,
 * so fall back to a timestamp+random id (mirrors the guarded pattern in brand-wallet.tsx /
 * src/lib/meera-api.ts).
 */
function safeRandomUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `withdraw-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

// ---------------------------------------------------------------------------
// Live-wiring notes (ported from claude/api-connection-workflow-b62285):
//   GET  /wallet              → api.wallet.get('creator')        (availableBalance, escrowLocked, pendingPayouts)
//   GET  /wallet/transactions → api.wallet.transactions('creator') (History tab)
//   GET  /wallet/payouts      → api.wallet.payouts()               (Payouts tab — CR-77)
//
// Withdraw (POST /wallet/withdraw) and payout methods (GET/POST /wallet/payout-methods,
// PUT /wallet/payout-methods/:id/primary) are wired to the live facade — see
// handleWithdraw / loadPayoutMethods / handleAddMethod / handleSetPrimary below.
// Withdraw sends a client-generated Idempotency-Key (B10 — WalletService rejects a
// withdrawal with no key).
//
// No facade coverage yet (render a proper empty state, never fabricated figures):
//   - detailed payout breakdown (TDS/platform-fee/GST split, brand/campaign name, UTR).
//     CR-77 built the real GET /wallet/payouts, so the Payouts tab now reads the `payouts`
//     table and shows amount, gateway status, settlement time and the payout reference. The
//     BREAKDOWN specifically is still absent because the data genuinely isn't there: TDS/GST
//     are unimplemented platform-wide, the platform fee is a separate ledger row rather than a
//     payout attribute, and a lump-sum wallet withdrawal has no linked milestone, so there is no
//     campaign to name. The tab states that gap in words rather than shipping em-dash columns.
//   - tax documents (Form-16A etc.) — no backend endpoint
// ---------------------------------------------------------------------------

/** Real wallet zero-state — used until `/wallet` resolves, and again if it 404s/errors. */
const EMPTY_EARNINGS = {
  availableBalance: 0,
  escrowLocked: 0,
  pendingPayouts: 0,
};

/**
 * Minimum withdrawal in INR. MUST mirror `WalletService.MIN_CREATOR_WITHDRAWAL`
 * (currently ₹500.00) on the backend — a lower client floor lets a withdraw pass
 * the UI only to be rejected server-side with `MINIMUM_WITHDRAWAL`.
 */
const MIN_WITHDRAWAL_INR = 500;

interface WalletTransaction {
  id: string;
  type: 'EARNING' | 'PAYOUT';
  /**
   * CR-75 — the real backend transaction type (WalletTransactionRow.type), kept alongside
   * the collapsed EARNING/PAYOUT direction above. `type` is still fine for the History
   * tab's credit/debit icon and color — that binary grouping is exactly what it's for.
   * `rawType` is what anything needing to distinguish an actual bank withdrawal from a
   * platform-fee or escrow-hold debit must use instead.
   */
  rawType: WalletTransactionRow['type'];
  description: string;
  amount: number;
  date: string;
  status: string;
  balanceAfter: number;
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
    rawType: row.type,
    description: row.description,
    amount: isCredit ? Math.abs(row.amount) : -Math.abs(row.amount),
    date: row.createdAt,
    status: row.status,
    balanceAfter: row.balanceAfter,
  };
}

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

  const [showPayoutSettings, setShowPayoutSettings] = React.useState(false);
  const [showWithdrawDialog, setShowWithdrawDialog] = React.useState(false);
  const [withdrawAmount, setWithdrawAmount] = React.useState('');
  const [isWithdrawing, setIsWithdrawing] = React.useState(false);
  const [withdrawError, setWithdrawError] = React.useState<string | null>(null);
  // Minted once per logical withdrawal submission, reused across retries of that SAME
  // submission (network failure) so the server's idempotency dedupe isn't bypassed by a fresh
  // key each click. Reset to null on success, on dialog close, and whenever the amount changes
  // (a changed amount is a new submission, not a retry) — mirrors topUpIdempotencyKey in
  // brand-wallet.tsx.
  const [withdrawIdempotencyKey, setWithdrawIdempotencyKey] = React.useState<string | null>(null);
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
  const [earnings, setEarnings] = React.useState(EMPTY_EARNINGS);
  const [walletLoading, setWalletLoading] = React.useState(false);
  const [walletError, setWalletError] = React.useState<string | null>(null);
  const [transactions, setTransactions] = React.useState<WalletTransaction[]>([]);
  // CR-77 — the Payouts tab's own data, from the real GET /wallet/payouts (the `payouts` table),
  // no longer derived by filtering ledger WITHDRAWAL debits.
  const [payouts, setPayouts] = React.useState<CreatorPayoutRow[]>([]);
  const [payoutsError, setPayoutsError] = React.useState(false);

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

  // GET /wallet — availableBalance, escrowLocked, pendingPayouts are all real fields on
  // WalletSummaryResponse; rendered as-is in the hero card below. No client-side derivation.
  // Extracted to a callback (not just an effect) so a successful withdraw (see handleWithdraw)
  // can re-trigger the same fetch instead of leaving the hero balance stale until reload.
  const loadWalletBalance = React.useCallback(async () => {
    if (!liveApi) return;
    setWalletLoading(true);
    setWalletError(null);
    try {
      const remote = await api.wallet.get('creator');
      if (remote) {
        setEarnings({
          availableBalance: remote.availableBalance ?? 0,
          escrowLocked: remote.escrowLocked ?? 0,
          pendingPayouts: remote.pendingPayouts ?? 0,
        });
      }
    } catch (err) {
      console.error('Failed to load wallet balance', err);
      setWalletError('Could not refresh wallet balance. Showing last known data.');
      // Never leave a fabricated balance on screen on error — zero it out.
      setEarnings(EMPTY_EARNINGS);
    } finally {
      setWalletLoading(false);
    }
  }, [liveApi]);

  React.useEffect(() => {
    loadWalletBalance();
  }, [loadWalletBalance]);

  // GET /wallet/transactions — History tab only. CR-77 — the Payouts tab no longer derives
  // itself from these debit rows; it reads the real GET /wallet/payouts (see loadPayouts).
  // Extracted to a callback for the same reason as loadWalletBalance above — reused by
  // handleWithdraw to refresh history after a successful withdrawal (CR-77 — the Payouts tab
  // has its own loadPayouts() now; this one no longer feeds it).
  // CR-72 — takes `selectedPeriod` as a param (rather than closing over state) so a caller like
  // handleWithdraw can be explicit about which window it's refreshing; the effect below re-runs
  // it whenever the dropdown changes.
  const loadTransactions = React.useCallback(async (period: string) => {
    if (!liveApi) return;
    try {
      const remote = await api.wallet.transactions('creator', 1, 20, period);
      // Array.isArray alone, deliberately no `.length > 0` -- a creator with genuinely zero
      // transactions gets back a real empty array; requiring non-empty treated that correct
      // empty response the same as "API call didn't return usable data," so it silently kept
      // the mock transactions forever instead (same bug fixed in dashboard-page.tsx and
      // creator-deals.tsx).
      if (Array.isArray(remote)) {
        setTransactions(remote.map(mapWalletTransactionRow));
      }
    } catch (err) {
      toast({
        title: 'Couldn’t refresh transaction history',
        description: err instanceof ApiError ? err.message : 'Showing your last known activity.',
        variant: 'destructive',
      });
      // Never leave fabricated/stale rows on screen on error.
      setTransactions([]);
    }
  }, [liveApi, toast]);

  // CR-72 — selectedPeriod (the History tab's this-month/last-month/3-months/all dropdown) was
  // rendered but never consumed: not passed to the fetch, not in this effect's dependency array,
  // so changing it never re-fetched and the creator always saw all-time transactions regardless
  // of selection. Re-fetch whenever the dropdown value changes.
  React.useEffect(() => {
    loadTransactions(selectedPeriod);
  }, [loadTransactions, selectedPeriod]);

  /**
   * CR-77 — real payout history. Deliberately a separate fetch from the ledger: a WITHDRAWAL debit
   * says money left the Influora wallet, not that it reached the bank. A reversed/rejected payout
   * is re-credited by a SEPARATE compensating entry while the debit stays, so the old derived tab
   * showed bounced payouts as money paid out. This reads the gateway's terminal state instead.
   */
  const loadPayouts = React.useCallback(async () => {
    if (!liveApi) return;
    try {
      const remote = await api.wallet.payouts(1, 20);
      if (Array.isArray(remote)) {
        setPayouts(remote);
        setPayoutsError(false);
      }
    } catch (err) {
      // Never leave stale rows on a money surface — show the honest error state instead.
      setPayouts([]);
      setPayoutsError(true);
      toast({
        title: 'Couldn’t load your payouts',
        description: err instanceof ApiError ? err.message : 'Please try again in a moment.',
        variant: 'destructive',
      });
    }
  }, [liveApi, toast]);

  React.useEffect(() => {
    void loadPayouts();
  }, [loadPayouts]);

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

  // Changing the amount starts a new logical submission, not a retry of the last one — drop
  // any held idempotency key so the next confirm mints a fresh one.
  const changeWithdrawAmount = (value: string) => {
    setWithdrawAmount(value);
    setWithdrawIdempotencyKey(null);
  };

  const handleWithdrawDialogChange = (open: boolean) => {
    setShowWithdrawDialog(open);
    if (!open) {
      setWithdrawAmount('');
      setWithdrawError(null);
      setWithdrawIdempotencyKey(null);
    }
  };

  const handleWithdraw = async () => {
    const amount = parseFloat(withdrawAmount);
    if (!amount || amount < MIN_WITHDRAWAL_INR) return;
    setIsWithdrawing(true);
    setWithdrawError(null);
    if (!liveApi) {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      setIsWithdrawing(false);
      setShowWithdrawDialog(false);
      setWithdrawAmount('');
      return;
    }
    // Client-generated Idempotency-Key (B10 — WalletService rejects a withdrawal with no key).
    // Minted once per logical submission (kept in withdrawIdempotencyKey) and reused across
    // retries of that same submission after a network failure — a fresh key per retry would let
    // a client retry double-spend past the server's idempotency dedupe.
    const idempotencyKey = withdrawIdempotencyKey ?? safeRandomUUID();
    if (!withdrawIdempotencyKey) setWithdrawIdempotencyKey(idempotencyKey);
    try {
      await api.wallet.withdraw(amount, idempotencyKey);
      // Success closes out this submission — the key must not be reused for a future one.
      setWithdrawIdempotencyKey(null);
      setShowWithdrawDialog(false);
      setWithdrawAmount('');
      // CR-73 — a successful withdrawal debits the balance and adds a transaction row
      // server-side; without this the hero balance and History/Payouts tabs kept showing
      // pre-withdrawal figures until a manual reload. Reuse the same loaders the mount
      // effects use rather than duplicating the fetch logic. Refresh under the
      // currently-selected period (CR-72) so History/Payouts stay consistent with what's
      // on screen.
      loadWalletBalance();
      loadTransactions(selectedPeriod);
      // CR-77 — the Payouts tab used to refresh for free because it was derived from
      // `transactions`. It has its own source now, so it needs its own refresh: without this the
      // payout the creator just queued is invisible until a remount, at exactly the moment
      // they're most likely to open that tab to check on it.
      void loadPayouts();
    } catch (err) {
      // Keep the same idempotency key held so a user-initiated retry of this submission reuses it.
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

  // Destination shown in the Withdraw dialog: the creator's real primary payout
  // method (falls back to the first on file). Never hardcode a demo VPA here — the
  // creator must see exactly where their money is going.
  const primaryPayoutMethod =
    payoutMethods.find((m) => m.isPrimary) ?? payoutMethods[0] ?? null;

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

        {/* Earnings Overview — every figure here is a real field off WalletSummaryResponse
            (GET /wallet); there is no lifetime-earnings or month-over-month endpoint yet,
            so we no longer fabricate a "Total Earned" / growth number. */}
        <Card className="bg-gradient-to-br from-primary to-accent text-white mb-6">
          <CardContent className="p-6">
            <div className="flex items-center justify-between mb-4">
              <p className="text-sm text-white/80">Available Balance</p>
              {walletLoading && <Loader2 className="h-4 w-4 animate-spin" />}
            </div>
            <p className="text-4xl font-bold mb-6">{formatINR(earnings.availableBalance)}</p>

            <div className="grid grid-cols-2 gap-4">
              <div className="bg-white/10 rounded-lg p-3">
                <p className="text-xs text-white/80">In Escrow</p>
                <p className="text-lg font-semibold">{formatINR(earnings.escrowLocked)}</p>
              </div>
              <div className="bg-white/10 rounded-lg p-3">
                <p className="text-xs text-white/80">Pending Payouts</p>
                <p className="text-lg font-semibold">{formatINR(earnings.pendingPayouts)}</p>
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

          {/* Payouts Tab — reads the real GET /wallet/payouts (CR-77). */}
          <TabsContent value="payouts" className="space-y-4">
            {/* CR-77 — was `transactions.filter(tx => tx.rawType === 'WITHDRAWAL')`, i.e. the
                Payouts tab derived itself from ledger debits. A debit records that money left the
                creator's Influora wallet; it does NOT record that it arrived at their bank. When
                RazorpayX reverses/rejects/cancels a payout the backend posts a SEPARATE
                compensating credit and correctly leaves the original debit standing — so a
                bounced payout kept showing here as money paid out, and the offsetting credit was
                invisible to this tab because a credit is not a WITHDRAWAL. Now reads the real
                `payouts` table via GET /wallet/payouts, which carries the gateway's own terminal
                status. */}
            {payoutsError ? (
              <Card>
                <CardContent className="flex flex-col items-center justify-center gap-2 p-8 text-center">
                  <Banknote className="h-8 w-8 text-muted-foreground" />
                  <p className="font-medium">Couldn’t load your payouts</p>
                  <p className="text-sm text-muted-foreground">
                    We’d rather show nothing than a stale list on a money screen.
                  </p>
                  <Button variant="outline" size="sm" className="mt-2" onClick={() => void loadPayouts()}>
                    Try again
                  </Button>
                </CardContent>
              </Card>
            ) : payouts.length === 0 ? (
              <Card>
                <CardContent className="flex flex-col items-center justify-center gap-2 p-8 text-center">
                  <Banknote className="h-8 w-8 text-muted-foreground" />
                  <p className="font-medium">No payouts yet</p>
                  <p className="text-sm text-muted-foreground">
                    Your payouts will appear here once a withdrawal is processed.
                  </p>
                </CardContent>
              </Card>
            ) : (
              <div className="space-y-3">
                {payouts.map((payout) => (
                  <Card key={payout.id}>
                    <CardContent className="p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex items-start gap-3 min-w-0">
                          <div
                            className={cn(
                              'h-10 w-10 rounded-full flex items-center justify-center flex-shrink-0',
                              payout.failed ? 'bg-muted' : 'bg-stage-outreach',
                            )}
                          >
                            <Banknote
                              className={cn(
                                'h-5 w-5',
                                payout.failed ? 'text-muted-foreground' : 'text-stage-outreach-fg',
                              )}
                            />
                          </div>
                          <div className="min-w-0">
                            <p className="font-semibold truncate">
                              {payout.failed ? 'Payout failed — amount returned to your balance' : 'Bank payout'}
                            </p>
                            <p className="text-sm text-muted-foreground truncate">
                              Requested{' '}
                              {new Date(payout.requestedAt).toLocaleDateString('en-IN', {
                                day: 'numeric',
                                month: 'short',
                                year: 'numeric',
                              })}
                              {!payout.failed && payout.settledAt
                                ? ` · Settled ${new Date(payout.settledAt).toLocaleDateString('en-IN', {
                                    day: 'numeric',
                                    month: 'short',
                                    year: 'numeric',
                                  })}`
                                : ''}
                            </p>
                            {/* The reference a creator can quote to support when chasing a payment.
                                Suppressed for a row still in the pre-gateway PENDING state:
                                Payout.createPending seeds razorpayPayoutId with an internal
                                "pending:<ulid>" placeholder until markGatewayConfirmed replaces it,
                                and showing that to a creator as a quotable reference would send
                                them to support with a string support cannot look up. */}
                            {!payout.reference.startsWith('pending:') && (
                              <p className="text-xs text-muted-foreground truncate">Ref: {payout.reference}</p>
                            )}
                          </div>
                        </div>
                        <div className="text-right flex-shrink-0">
                          <p className={cn('font-semibold', payout.failed && 'text-muted-foreground line-through')}>
                            {formatINR(Math.abs(payout.amount))}
                          </p>
                          {/* CR-77 — deliberately NOT statusBadgeClass(payout.status). That helper
                              substring-matches, and RazorpayX's vocabulary inverts under it:
                              'reversed'/'rejected'/'cancelled' match none of its FAIL/PROCESS/PEND
                              patterns and fall through to the GREEN success style, while
                              'processed' — the terminal success — matches 'PROCESS' and renders
                              amber in-progress. The failure flag is already computed server-side
                              from the shared FAILURE_STATUSES set; drive the badge off that. */}
                          <Badge
                            className={cn(
                              'mt-1',
                              payout.failed
                                ? 'bg-destructive/10 text-destructive-foreground'
                                : payout.settledAt
                                  ? 'bg-stage-approved text-stage-approved-fg'
                                  : 'bg-stage-negotiating text-stage-negotiating-fg',
                            )}
                          >
                            {payout.failed ? 'Failed' : payout.settledAt ? 'Paid' : 'In progress'}
                          </Badge>
                        </div>
                      </div>
                    </CardContent>
                  </Card>
                ))}
                {/* CR-77 — states the gap rather than shipping em-dash placeholder fields. TDS/GST
                    are unimplemented platform-wide, the platform fee is a separate ledger entry,
                    and a wallet withdrawal has no linked milestone so there is no campaign to name. */}
                <p className="px-1 text-xs text-muted-foreground">
                  A detailed breakdown (TDS, platform fee, GST, and the linked campaign) isn’t available yet.
                  Each payout above shows the amount actually sent to your bank and its current status.
                </p>
              </div>
            )}
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

            {/* No tax-document endpoint exists yet — show a real empty state instead of
                fabricated Form-16A rows. */}
            <Card>
              <CardContent className="flex flex-col items-center justify-center gap-2 p-8 text-center">
                <Receipt className="h-8 w-8 text-muted-foreground" />
                <p className="font-medium">No tax documents yet</p>
                <p className="text-sm text-muted-foreground">
                  Form 16A and annual statements will appear here once available.
                </p>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>


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
      <Dialog open={showWithdrawDialog} onOpenChange={handleWithdrawDialogChange}>
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
              <p className="text-3xl font-bold text-stage-contracted-fg">{formatINR(earnings.availableBalance)}</p>
              <p className="text-xs text-muted-foreground mt-1">
                {formatINR(earnings.escrowLocked)} locked in escrow
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
                  onChange={(e) => changeWithdrawAmount(e.target.value)}
                  className="pl-9"
                  max={earnings.availableBalance}
                />
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">Min: {formatINR(MIN_WITHDRAWAL_INR)}</span>
                <Button
                  variant="link"
                  className="h-auto p-0 text-xs"
                  onClick={() => changeWithdrawAmount(earnings.availableBalance.toString())}
                >
                  Withdraw All
                </Button>
              </div>
            </div>

            {/* Payout Method — the creator's real primary method, never a hardcoded VPA */}
            <div className="space-y-2">
              <Label>Payout Method</Label>
              {primaryPayoutMethod ? (
                <Card
                  className={
                    primaryPayoutMethod.usable
                      ? 'border-violet-200 bg-violet-50'
                      : 'border-amber-200 bg-amber-50'
                  }
                >
                  <CardContent className="p-3">
                    <div className="flex items-center gap-3">
                      <div className="h-8 w-8 rounded-full bg-stage-approved flex items-center justify-center">
                        {primaryPayoutMethod.type === 'UPI' ? (
                          <span className="text-stage-approved-fg font-bold text-sm">₹</span>
                        ) : (
                          <Building className="h-4 w-4 text-blue-700" />
                        )}
                      </div>
                      <div>
                        <p className="font-medium text-sm">
                          {primaryPayoutMethod.type === 'UPI' ? 'UPI' : 'Bank Account'}
                        </p>
                        <p className="text-xs text-muted-foreground">{primaryPayoutMethod.displayMask}</p>
                      </div>
                    </div>
                    {/* CR-74 — mirrors the server's 24h new-bank-account cool-down
                        (RazorpayFundAccountService#resolveFundAccountId / CreatorBankAccount#isUsableAt)
                        so the Withdraw button reflects it before the POST ever reaches the server. */}
                    {!primaryPayoutMethod.usable && (
                      <p className="mt-2 flex items-start gap-1.5 text-xs text-amber-800">
                        <Clock className="h-3 w-3 mt-0.5 flex-shrink-0" />
                        This payout method was added recently and is in its 24-hour security
                        verification window. You&apos;ll be able to withdraw to it once that
                        window has passed.
                      </p>
                    )}
                  </CardContent>
                </Card>
              ) : (
                <Card className="border-dashed">
                  <CardContent className="p-3">
                    <p className="text-sm text-muted-foreground">
                      No payout method on file. Add a bank account or UPI in Settings before withdrawing.
                    </p>
                  </CardContent>
                </Card>
              )}
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
            <Button variant="outline" onClick={() => handleWithdrawDialogChange(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleWithdraw}
              disabled={isWithdrawing || !withdrawAmount || parseFloat(withdrawAmount) < MIN_WITHDRAWAL_INR || parseFloat(withdrawAmount) > earnings.availableBalance || (liveApi && (!primaryPayoutMethod || !primaryPayoutMethod.usable))}
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
