import * as React from 'react';
import {
  CreditCard,
  Zap,
  Users,
  BarChart3,
  Download,
  FileText,
  Sparkles,
  TrendingUp,
  AlertCircle,
  Crown,
  Building2,
  Loader2,
  Receipt,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Separator } from '@/components/ui/separator';
import { TooltipProvider } from '@/components/ui/tooltip';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { api, ApiError, brandInvoicing, type BillingInvoice, type CampaignServiceInvoice, type PlatformCommissionInvoice } from '@/lib/api';
import { useBilling } from '@/hooks/brand/useBilling';
import { useBrandBillingAccess } from '@/hooks/brand/useBrandBillingAccess';
import { toast } from '@/hooks/use-toast';

interface UsageMeterRow {
  metric: string;
  used: number;
  limit: number | null; // null = unlimited
}

const formatCurrency = (amount: number): string => {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
};

// B44: the live FREE-tier plan response has come back without a usable
// feeBps, producing "NaN%" from a bare `feeBps / 100`. Guard it here so a
// missing/non-numeric value shows a neutral "—" instead of NaN.
const formatFeePercent = (feeBps: number | null | undefined): string => {
  if (feeBps == null || Number.isNaN(feeBps)) return '—';
  return `${feeBps / 100}%`;
};

const formatDate = (iso: string | null): string => {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
};

/** Shared "download PDF" trigger — used by subscription, Doc#2, and Doc#3a invoice rows alike. */
function PdfDownloadButton({
  onDownload,
  filename,
}: {
  onDownload: () => Promise<Blob>;
  filename: string;
}) {
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
    } catch {
      // Swallowed — the button just stops spinning; no destructive fallout from a failed download.
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Button
      variant="outline"
      size="sm"
      className="gap-2"
      onClick={handleDownload}
      disabled={downloading}
      aria-label={downloading ? `Downloading ${filename}` : `Download ${filename}`}
    >
      {downloading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" aria-hidden="true" />}
      PDF
    </Button>
  );
}

function InvoiceDownloadButton({ invoice }: { invoice: BillingInvoice }) {
  return (
    <PdfDownloadButton
      onDownload={() => api.billing.downloadInvoicePdf(invoice.id)}
      filename={`invoice-${invoice.id}.pdf`}
    />
  );
}

function CampaignInvoiceStatusBadge({ status }: { status: CampaignServiceInvoice['status'] }) {
  return (
    <Badge
      variant="secondary"
      className={cn(status === 'PAID' && 'bg-green-500/10 text-green-500', status === 'ISSUED' && 'bg-amber-500/10 text-amber-500')}
    >
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </Badge>
  );
}

/** D14 Doc#2 — creator service invoices this workspace was billed for a collaboration. */
function CampaignInvoicesCard({ invoices }: { invoices: CampaignServiceInvoice[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FileText className="h-5 w-5" aria-hidden="true" />
          Creator Service Invoices
        </CardTitle>
        <CardDescription>Invoices creators have issued you for completed collaborations</CardDescription>
      </CardHeader>
      <CardContent>
        {invoices.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-muted">
              <FileText className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
            </div>
            <p className="mt-4 font-medium">No creator invoices yet</p>
            <p className="text-sm text-muted-foreground">
              These appear once a collaboration's escrow is released to the creator.
            </p>
          </div>
        ) : (
          <div className="divide-y">
            {invoices.map((invoice) => (
              <div key={invoice.id} className="flex items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <p className="truncate font-medium">{invoice.invoiceNumber}</p>
                  <p className="text-sm text-muted-foreground">{formatDate(invoice.issuedAt)}</p>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <p className="font-semibold">{formatCurrency(invoice.grossAmount)}</p>
                    <CampaignInvoiceStatusBadge status={invoice.status} />
                  </div>
                  <PdfDownloadButton
                    onDownload={() => brandInvoicing.downloadCampaignInvoicePdf(invoice.id)}
                    filename={`${invoice.invoiceNumber || invoice.id}.pdf`}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** D14 Doc#3a — Influora's commission invoice to this workspace (leg BRAND). */
function CommissionInvoicesCard({ invoices }: { invoices: PlatformCommissionInvoice[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Receipt className="h-5 w-5" aria-hidden="true" />
          Platform Commission Invoices
        </CardTitle>
        <CardDescription>Influora's commission invoice for campaigns you've published</CardDescription>
      </CardHeader>
      <CardContent>
        {invoices.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-muted">
              <Receipt className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
            </div>
            <p className="mt-4 font-medium">No commission invoices yet</p>
            <p className="text-sm text-muted-foreground">These appear once a campaign is published.</p>
          </div>
        ) : (
          <div className="divide-y">
            {invoices.map((invoice) => (
              <div key={invoice.id} className="flex items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <p className="truncate font-medium">{invoice.invoiceNumber}</p>
                  <p className="text-sm text-muted-foreground">
                    {formatDate(invoice.issuedAt)} &middot; {(invoice.feeBpsApplied / 100).toFixed(2)}% fee
                  </p>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <p className="font-semibold">{formatCurrency(invoice.commissionAmount)}</p>
                    <CampaignInvoiceStatusBadge status={invoice.status} />
                  </div>
                  <PdfDownloadButton
                    onDownload={() => brandInvoicing.downloadCommissionInvoicePdf(invoice.id)}
                    filename={`${invoice.invoiceNumber || invoice.id}.pdf`}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default function BrandBillingSettingsPage() {
  const {
    plan: planStatus,
    usage,
    invoices,
    campaignInvoices,
    commissionInvoices,
    isLoading,
    error,
    refetch,
  } = useBilling();

  const [checkoutBusy, setCheckoutBusy] = React.useState(false);
  const [cancelBusy, setCancelBusy] = React.useState(false);

  // F-0132: mirror BillingController's requireRole(OWNER, ADMIN) on /checkout and /cancel so a
  // MANAGER/MEMBER/VIEWER sees a disabled control with a reason, not a fully-enabled button that
  // only reveals the 403 as an error toast after clicking.
  const { canManage: canManageBilling } = useBrandBillingAccess();
  const billingRoleHint = 'Only workspace owners and admins can change the plan.';

  // F-0070: initiate a real Razorpay checkout (POST /billing/checkout) and hand
  // off to the hosted checkout URL. Live behaviour is gated on provisioned
  // Razorpay keys — a placeholder key surfaces as an API error here, same as the
  // escrow-fund path, rather than a silently dead button.
  const handleUpgrade = async () => {
    setCheckoutBusy(true);
    try {
      const { checkoutUrl } = await api.billing.initiateCheckout('PRO');
      window.location.assign(checkoutUrl);
    } catch (err) {
      toast({
        title: 'Could not start checkout',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
      setCheckoutBusy(false);
    }
  };

  // F-0073: cancel an active subscription (POST /billing/cancel). Backend marks
  // cancelAtPeriodEnd; access continues until the current period ends.
  const handleCancel = async () => {
    setCancelBusy(true);
    try {
      await api.billing.cancelSubscription();
      toast({
        title: 'Subscription cancelled',
        description: 'Your plan stays active until the end of the current billing period.',
      });
      refetch();
    } catch (err) {
      toast({
        title: 'Could not cancel subscription',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setCancelBusy(false);
    }
  };

  if (isLoading && !planStatus) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (error || !planStatus || !usage) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-24 text-center">
        <AlertCircle className="h-8 w-8 text-red-500" />
        <div>
          <p className="font-medium">Couldn't load billing details</p>
          <p className="text-sm text-muted-foreground">{error ?? 'Please try again.'}</p>
        </div>
        <Button variant="outline" onClick={() => refetch()}>
          Retry
        </Button>
      </div>
    );
  }

  const currentPlan = planStatus.plan;
  const subscription = planStatus.subscription;
  const isFreeTier = currentPlan.code === 'FREE';

  const usageCounters: UsageMeterRow[] = [
    {
      metric: 'Tracked/Saved Creators',
      used: usage.trackedCreatorsUsed,
      limit: usage.trackedCreatorLimit,
    },
    {
      metric: 'Creator Analytics Views',
      used: usage.analyticsViewsUsed,
      limit: usage.analyticsViewsLimit,
    },
    {
      metric: 'AI Credits Used',
      used: Math.max(usage.aiCreditsMonthlyAllotment - usage.aiCreditsRemaining, 0),
      limit: usage.aiCreditsMonthlyAllotment,
    },
    {
      metric: 'Active Seats',
      used: usage.activeSeatsUsed,
      limit: currentPlan.seatLimit,
    },
  ];

  return (
    <TooltipProvider>
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div>
            <h1 className="text-2xl font-bold">Billing & Subscription</h1>
            <p className="text-muted-foreground">
              Manage your plan, view usage, and track invoices
            </p>
          </div>
        </div>

        {/* Current Plan Card */}
        <Card className={cn(
          'relative overflow-hidden',
          !isFreeTier && 'border-primary/30 bg-gradient-to-br from-primary/5 via-transparent to-transparent'
        )}>
          <CardHeader>
            <div className="flex items-start justify-between">
              <div className="space-y-1">
                <CardTitle className="flex items-center gap-2">
                  {currentPlan.name} Plan
                  {!isFreeTier && <Crown className="h-5 w-5 text-amber-500" />}
                </CardTitle>
                <CardDescription>
                  {isFreeTier ? 'Get started with Influora at no cost' : 'Full access to all Pro features'}
                </CardDescription>
              </div>
              <div className="text-right">
                <p className="text-3xl font-bold">
                  {currentPlan.priceInr === 0 ? 'Free' : formatCurrency(currentPlan.priceInr)}
                </p>
                {currentPlan.priceInr > 0 && (
                  <p className="text-sm text-muted-foreground">per month</p>
                )}
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* Plan Features */}
            <div className="grid gap-3 md:grid-cols-2">
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10">
                  <TrendingUp className="h-4 w-4 text-primary" />
                </div>
                <div>
                  <p className="text-sm font-medium">Brand Fee</p>
                  <p className="text-xs text-muted-foreground">
                    {formatFeePercent(currentPlan.feeBps)} per campaign
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-500/10">
                  <Zap className="h-4 w-4 text-blue-500" />
                </div>
                <div>
                  <p className="text-sm font-medium">AI Credits</p>
                  <p className="text-xs text-muted-foreground">
                    {usage.aiCreditsMonthlyAllotment} per month
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-green-500/10">
                  <Users className="h-4 w-4 text-green-500" />
                </div>
                <div>
                  <p className="text-sm font-medium">Team Seats</p>
                  <p className="text-xs text-muted-foreground">
                    {currentPlan.seatLimit}
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-amber-500/10">
                  <BarChart3 className="h-4 w-4 text-amber-500" />
                </div>
                <div>
                  <p className="text-sm font-medium">Creator Analytics</p>
                  <p className="text-xs text-muted-foreground">
                    {currentPlan.creatorAnalyticsMonthlyLimit === null
                      ? 'Unlimited views'
                      : `${currentPlan.creatorAnalyticsMonthlyLimit} view/month`}
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-purple-500/10">
                  <Sparkles className="h-4 w-4 text-purple-500" />
                </div>
                <div>
                  <p className="text-sm font-medium">Tracked Creators</p>
                  <p className="text-xs text-muted-foreground">
                    {currentPlan.trackedCreatorLimit === null
                      ? 'Unlimited'
                      : `Up to ${currentPlan.trackedCreatorLimit}`}
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-red-500/10">
                  <Download className="h-4 w-4 text-red-500" />
                </div>
                <div>
                  <p className="text-sm font-medium">Export Reports</p>
                  <p className="text-xs text-muted-foreground">
                    {currentPlan.exportEnabled ? 'CSV & PDF' : 'Not available'}
                  </p>
                </div>
              </div>
            </div>

            {!isFreeTier && (
              <>
                <Separator />
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">
                    {subscription.cancelAtPeriodEnd ? 'Access until' : 'Next billing date'}
                  </span>
                  <span className="font-medium">{formatDate(subscription.currentPeriodEnd)}</span>
                </div>
                {!subscription.cancelAtPeriodEnd && (
                  <div className="flex items-center justify-end gap-3">
                    {!canManageBilling && (
                      <span className="text-xs text-muted-foreground">{billingRoleHint}</span>
                    )}
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-muted-foreground"
                          disabled={cancelBusy || !canManageBilling}
                          title={!canManageBilling ? billingRoleHint : undefined}
                        >
                          {cancelBusy && <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />}
                          Cancel subscription
                        </Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>Cancel your Pro subscription?</AlertDialogTitle>
                          <AlertDialogDescription>
                            You'll keep Pro features until{' '}
                            {formatDate(subscription.currentPeriodEnd)}. After that your workspace
                            returns to the Free plan. You can re-subscribe at any time.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>Keep Pro</AlertDialogCancel>
                          <AlertDialogAction onClick={handleCancel}>
                            Cancel subscription
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  </div>
                )}
              </>
            )}
          </CardContent>
        </Card>

        {/* Plan Comparison (if on Free tier) */}
        {isFreeTier && (
          <Card>
            <CardHeader>
              <CardTitle>Upgrade to Pro</CardTitle>
              <CardDescription>
                Unlock unlimited creators, analytics, and lower campaign fees
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-6">
                {/* Feature Comparison Table */}
                <div className="rounded-lg border">
                  <div className="grid grid-cols-3 border-b bg-muted/30 p-3 text-sm font-medium">
                    <div>Feature</div>
                    <div className="text-center">Free</div>
                    <div className="text-center">Pro</div>
                  </div>
                  {[
                    { label: 'Brand Fee', free: '10%', pro: '7%' },
                    { label: 'AI Credits/mo', free: '150', pro: '400' },
                    { label: 'Seats', free: '1', pro: '5' },
                    { label: 'Tracked Creators', free: '5', pro: 'Unlimited' },
                    { label: 'Analytics Views/mo', free: '1', pro: 'Unlimited' },
                    { label: 'Export Reports', free: '—', pro: 'CSV & PDF' },
                    { label: 'Campaign Templates', free: '—', pro: 'Included' },
                  ].map((row, idx) => (
                    <div
                      key={idx}
                      className="grid grid-cols-3 border-b p-3 text-sm last:border-0"
                    >
                      <div className="font-medium">{row.label}</div>
                      <div className="text-center text-muted-foreground">{row.free}</div>
                      <div className="text-center font-medium text-primary">{row.pro}</div>
                    </div>
                  ))}
                </div>

                {/* Upgrade CTA */}
                <div className="flex items-center justify-between rounded-lg border border-primary/20 bg-primary/5 p-4">
                  <div>
                    <p className="font-semibold">Ready to upgrade?</p>
                    <p className="text-sm text-muted-foreground">
                      Start growing with Pro — {formatCurrency(4999)}/month
                    </p>
                    {!canManageBilling && (
                      <p className="mt-1 text-xs text-muted-foreground">{billingRoleHint}</p>
                    )}
                  </div>
                  <Button
                    className="gap-2"
                    onClick={handleUpgrade}
                    disabled={checkoutBusy || !canManageBilling}
                    title={!canManageBilling ? billingRoleHint : undefined}
                  >
                    {checkoutBusy ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <Crown className="h-4 w-4" />
                    )}
                    Upgrade to Pro
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Usage Meters */}
        <Card>
          <CardHeader>
            <CardTitle>Usage This Month</CardTitle>
            <CardDescription>
              Track your current cycle usage against plan limits
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {usageCounters.map((counter, idx) => {
                const percentage = counter.limit === null
                  ? 0
                  : Math.min((counter.used / counter.limit) * 100, 100);
                const isNearLimit = counter.limit !== null && counter.used >= counter.limit * 0.8;
                const isAtLimit = counter.limit !== null && counter.used >= counter.limit;

                return (
                  <div key={idx} className="space-y-2">
                    <div className="flex items-center justify-between text-sm">
                      <span className="font-medium">{counter.metric}</span>
                      <span className={cn(
                        'font-semibold',
                        isAtLimit && 'text-red-500',
                        isNearLimit && !isAtLimit && 'text-amber-500'
                      )}>
                        {counter.used}
                        {counter.limit !== null && ` / ${counter.limit}`}
                        {counter.limit === null && ' (Unlimited)'}
                      </span>
                    </div>
                    {counter.limit !== null && (
                      <Progress
                        value={percentage}
                        className={cn(
                          'h-2',
                          isAtLimit && '[&>div]:bg-red-500',
                          isNearLimit && !isAtLimit && '[&>div]:bg-amber-500'
                        )}
                      />
                    )}
                    {isAtLimit && (
                      <p className="text-xs text-red-500 flex items-center gap-1">
                        <AlertCircle className="h-3 w-3" />
                        Limit reached — upgrade to Pro for unlimited access
                      </p>
                    )}
                  </div>
                );
              })}
            </div>

            {isFreeTier && (
              <>
                <Separator className="my-4" />
                <div className="rounded-lg border border-primary/20 bg-primary/5 p-3">
                  <p className="text-sm">
                    Need more? <strong>Upgrade to Pro</strong> for unlimited tracked creators, analytics views, and 400 AI credits per month.
                  </p>
                </div>
              </>
            )}
          </CardContent>
        </Card>

        {/* Invoices */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FileText className="h-5 w-5" />
              Invoices
            </CardTitle>
            <CardDescription>
              Download past invoices and payment receipts
            </CardDescription>
          </CardHeader>
          <CardContent>
            {invoices.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-8 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-muted">
                  <FileText className="h-8 w-8 text-muted-foreground" />
                </div>
                <p className="mt-4 font-medium">No invoices yet</p>
                <p className="text-sm text-muted-foreground">
                  {isFreeTier
                    ? 'Upgrade to Pro to see your subscription invoices here'
                    : 'Your invoices will appear here after your first billing cycle'}
                </p>
              </div>
            ) : (
              <div className="divide-y">
                {invoices.map((invoice) => (
                  <div
                    key={invoice.id}
                    className="flex items-center justify-between py-3"
                  >
                    <div>
                      <p className="font-medium">Invoice #{invoice.id.slice(0, 8)}</p>
                      <p className="text-sm text-muted-foreground">
                        {formatDate(invoice.issuedAt)}
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="text-right">
                        <p className="font-semibold">{formatCurrency(invoice.amount)}</p>
                        <Badge
                          variant="secondary"
                          className={cn(
                            invoice.status === 'PAID' && 'bg-green-500/10 text-green-500',
                            invoice.status === 'PENDING' && 'bg-amber-500/10 text-amber-500',
                            invoice.status === 'FAILED' && 'bg-red-500/10 text-red-500'
                          )}
                        >
                          {invoice.status.charAt(0).toUpperCase() + invoice.status.slice(1).toLowerCase()}
                        </Badge>
                      </div>
                      <InvoiceDownloadButton invoice={invoice} />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        {/* D14 — Creator service invoices (Doc#2) */}
        <CampaignInvoicesCard invoices={campaignInvoices} />

        {/* D14 — Platform commission invoices (Doc#3a) */}
        <CommissionInvoicesCard invoices={commissionInvoices} />

        {/* Payment Method Placeholder */}
        {!isFreeTier && (
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <CreditCard className="h-5 w-5" />
                Payment Method
              </CardTitle>
              <CardDescription>
                Manage your payment details and billing preferences
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="flex flex-col items-center justify-center py-6 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-muted">
                  <Building2 className="h-8 w-8 text-muted-foreground" />
                </div>
                <p className="mt-4 font-medium">Payment method management coming soon</p>
                <p className="text-sm text-muted-foreground">
                  You'll be able to update your card and billing details here
                </p>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </TooltipProvider>
  );
}
