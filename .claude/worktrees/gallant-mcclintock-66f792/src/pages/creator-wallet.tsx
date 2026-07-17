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

const mockTransactions = [
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

export default function CreatorWalletPage() {
  const [selectedPayout, setSelectedPayout] = React.useState<typeof mockPayouts[0] | null>(null);
  const [showPayoutSettings, setShowPayoutSettings] = React.useState(false);
  const [showWithdrawDialog, setShowWithdrawDialog] = React.useState(false);
  const [withdrawAmount, setWithdrawAmount] = React.useState('');
  const [isWithdrawing, setIsWithdrawing] = React.useState(false);
  const [selectedPeriod, setSelectedPeriod] = React.useState('this-month');

  const growthPercent = ((mockEarningsData.thisMonth - mockEarningsData.lastMonth) / mockEarningsData.lastMonth * 100).toFixed(1);
  const isPositiveGrowth = mockEarningsData.thisMonth > mockEarningsData.lastMonth;

  const handleWithdraw = async () => {
    setIsWithdrawing(true);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    setIsWithdrawing(false);
    setShowWithdrawDialog(false);
    setWithdrawAmount('');
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

        {/* Earnings Overview */}
        <Card className="bg-gradient-to-br from-primary to-accent text-white mb-6">
          <CardContent className="p-6">
            <div className="flex items-center justify-between mb-4">
              <p className="text-sm text-white/80">Total Earned</p>
              <div className="flex items-center gap-1 text-sm">
                {isPositiveGrowth ? (
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
                <p className="text-lg font-semibold">{formatINR(mockEarningsData.pendingPayout)}</p>
              </div>
              <div className="bg-white/10 rounded-lg p-3">
                <p className="text-xs text-white/80">In Escrow</p>
                <p className="text-lg font-semibold">{formatINR(mockEarningsData.inEscrow)}</p>
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
          <TabsList className="w-full grid grid-cols-3">
            <TabsTrigger value="payouts">Payouts</TabsTrigger>
            <TabsTrigger value="history">History</TabsTrigger>
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
              {mockTransactions.map((tx) => (
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
            {/* Primary Method */}
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

            {/* Secondary Method */}
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

            <Button variant="outline" className="w-full">
              <CreditCard className="h-4 w-4 mr-2" />
              Add New Method
            </Button>
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
              <p className="text-3xl font-bold text-stage-contracted-fg">{formatINR(mockEarningsData.pendingPayout)}</p>
              <p className="text-xs text-muted-foreground mt-1">
                {formatINR(mockEarningsData.inEscrow)} locked in escrow
              </p>
            </div>

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
                  max={mockEarningsData.pendingPayout}
                />
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">Min: ₹100</span>
                <Button 
                  variant="link" 
                  className="h-auto p-0 text-xs"
                  onClick={() => setWithdrawAmount(mockEarningsData.pendingPayout.toString())}
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
              disabled={isWithdrawing || !withdrawAmount || parseFloat(withdrawAmount) < 100 || parseFloat(withdrawAmount) > mockEarningsData.pendingPayout}
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
