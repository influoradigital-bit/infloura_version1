import * as React from 'react';
import {
  AlertTriangle,
  ArrowDownToLine,
  BadgeCheck,
  Building2,
  Clock,
  Gavel,
  IndianRupee,
  ShieldCheck,
  Users,
  Zap,
} from 'lucide-react';

import { cn, formatINR } from '@/lib/utils';
import {
  demoAdminDisputes,
  demoAdminKycQueue,
  demoAdminStats,
  demoAdminTransactions,
  demoAdminUsers,
  type AdminDisputeRow,
  type AdminTransactionRow,
  type AdminUserRow,
} from '@/lib/demo-data';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';

/**
 * Admin operations console — demo build driven entirely by lib/demo-data.ts.
 * Covers the four admin jobs: user/KYC oversight, dispute resolution,
 * transaction monitoring, and platform health. Live endpoints land in M2.
 */

const relativeTime = (date: Date) => {
  const diff = Date.now() - date.getTime();
  const mins = Math.round(diff / 60000);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
};

function StatCard({
  label,
  value,
  icon: Icon,
  tone,
}: {
  label: string;
  value: string;
  icon: typeof Users;
  tone?: 'default' | 'warning' | 'hype';
}) {
  return (
    <Card>
      <CardContent className="flex items-center justify-between p-4">
        <div>
          <p className="text-sm text-muted-foreground">{label}</p>
          <p className="mt-1 text-2xl font-bold">{value}</p>
        </div>
        <span
          className={cn(
            'flex h-10 w-10 items-center justify-center rounded-full',
            tone === 'warning' && 'bg-warning text-warning-foreground',
            tone === 'hype' && 'bg-hype text-hype-foreground',
            (!tone || tone === 'default') && 'bg-accent text-accent-foreground',
          )}
        >
          <Icon className="h-5 w-5" aria-hidden="true" />
        </span>
      </CardContent>
    </Card>
  );
}

const userStatusBadge = (user: AdminUserRow) => {
  switch (user.status) {
    case 'ACTIVE':
      return <Badge className="border-stage-approved-border bg-stage-approved text-stage-approved-fg hover:bg-stage-approved">Active</Badge>;
    case 'PENDING_VERIFICATION':
      return <Badge className="border-stage-negotiating-border bg-stage-negotiating text-stage-negotiating-fg hover:bg-stage-negotiating">Pending</Badge>;
    case 'SUSPENDED':
      return <Badge className="border-stage-disputed-border bg-stage-disputed text-stage-disputed-fg hover:bg-stage-disputed">Suspended</Badge>;
    default:
      return <Badge variant="secondary">Deactivated</Badge>;
  }
};

const disputeStatusBadge = (dispute: AdminDisputeRow) => {
  switch (dispute.status) {
    case 'OPEN':
      return <Badge className="border-stage-review-border bg-stage-review text-stage-review-fg hover:bg-stage-review">Open</Badge>;
    case 'UNDER_REVIEW':
      return <Badge className="border-stage-negotiating-border bg-stage-negotiating text-stage-negotiating-fg hover:bg-stage-negotiating">Under review</Badge>;
    case 'MEDIATION':
      return <Badge className="border-stage-contracted-border bg-stage-contracted text-stage-contracted-fg hover:bg-stage-contracted">Mediation</Badge>;
    default:
      return <Badge className="border-stage-approved-border bg-stage-approved text-stage-approved-fg hover:bg-stage-approved">Resolved</Badge>;
  }
};

const txStatusBadge = (tx: AdminTransactionRow) => {
  switch (tx.status) {
    case 'COMPLETED':
      return <Badge className="border-stage-approved-border bg-stage-approved text-stage-approved-fg hover:bg-stage-approved">Completed</Badge>;
    case 'PENDING':
      return <Badge className="border-stage-negotiating-border bg-stage-negotiating text-stage-negotiating-fg hover:bg-stage-negotiating">Pending</Badge>;
    case 'FAILED':
      return <Badge className="border-stage-disputed-border bg-stage-disputed text-stage-disputed-fg hover:bg-stage-disputed">Failed</Badge>;
    default:
      return <Badge variant="secondary">Cancelled</Badge>;
  }
};

export default function AdminDashboardPage() {
  const stats = demoAdminStats;

  return (
    <div className="min-h-screen bg-background">
      <header className="sticky top-0 z-40 border-b border-border bg-background/80 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <InfluoraLogo size="sm" showName={false} />
            <div>
              <p className="text-sm font-semibold leading-none">Admin Console</p>
              <p className="text-[11px] text-muted-foreground">Operations · demo data</p>
            </div>
          </div>
          <Badge variant="outline" className="gap-1">
            <ShieldCheck className="h-3 w-3" aria-hidden="true" /> ADMIN
          </Badge>
        </div>
      </header>

      <main className="mx-auto max-w-6xl space-y-6 p-6">
        {/* Platform health */}
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="Total users" value={stats.totalUsers.toLocaleString('en-IN')} icon={Users} />
          <StatCard label="Escrow under management" value={`₹${(stats.escrowUnderManagement / 10000000).toFixed(1)}Cr`} icon={IndianRupee} />
          <StatCard label="Open disputes" value={String(stats.openDisputes)} icon={Gavel} tone="warning" />
          <StatCard label="Live Hype campaigns" value={String(stats.liveHypeCampaigns)} icon={Zap} tone="hype" />
        </div>

        <Tabs defaultValue="users">
          <TabsList>
            <TabsTrigger value="users">Users</TabsTrigger>
            <TabsTrigger value="disputes">
              Disputes
              <Badge variant="secondary" className="ml-1.5 h-4 min-w-4 rounded-full px-1 text-[10px]">
                {stats.openDisputes}
              </Badge>
            </TabsTrigger>
            <TabsTrigger value="transactions">Transactions</TabsTrigger>
            <TabsTrigger value="kyc">
              KYC
              <Badge variant="secondary" className="ml-1.5 h-4 min-w-4 rounded-full px-1 text-[10px]">
                {stats.pendingKyc}
              </Badge>
            </TabsTrigger>
          </TabsList>

          {/* Users */}
          <TabsContent value="users">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">User management</CardTitle>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>User</TableHead>
                      <TableHead>Type</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead className="text-right">Deals</TableHead>
                      <TableHead className="text-right">GMV</TableHead>
                      <TableHead className="text-right">Last active</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {demoAdminUsers.map((user) => (
                      <TableRow key={user.id}>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-accent text-xs font-semibold text-accent-foreground">
                              {user.displayName.split(' ').map((w) => w[0]).join('').slice(0, 2)}
                            </span>
                            <div>
                              <p className="flex items-center gap-1 font-medium">
                                {user.displayName}
                                {user.verificationStatus === 'VERIFIED' && (
                                  <BadgeCheck className="h-3.5 w-3.5 text-info-foreground" aria-label="Verified" />
                                )}
                              </p>
                              <p className="text-xs text-muted-foreground">{user.email}</p>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <span className="inline-flex items-center gap-1 text-sm">
                            {user.userType === 'BRAND' ? (
                              <Building2 className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
                            ) : (
                              <Users className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
                            )}
                            {user.userType === 'BRAND' ? 'Brand' : 'Creator'}
                          </span>
                        </TableCell>
                        <TableCell>{userStatusBadge(user)}</TableCell>
                        <TableCell className="text-right font-mono text-sm">{user.totalDeals}</TableCell>
                        <TableCell className="text-right font-mono text-sm">{formatINR(user.gmv)}</TableCell>
                        <TableCell className="text-right text-sm text-muted-foreground">
                          {relativeTime(user.lastActiveAt)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          </TabsContent>

          {/* Disputes */}
          <TabsContent value="disputes">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Dispute queue</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {demoAdminDisputes.map((dispute) => (
                  <div
                    key={dispute.id}
                    className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        {disputeStatusBadge(dispute)}
                        <Badge variant="outline" className="text-[10px]">
                          {dispute.type.replace(/_/g, ' ').toLowerCase()}
                        </Badge>
                        {dispute.slaHoursLeft > 0 && dispute.slaHoursLeft <= 12 && (
                          <span className="inline-flex items-center gap-1 text-xs font-medium text-stage-review-fg">
                            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
                            SLA {dispute.slaHoursLeft}h
                          </span>
                        )}
                      </div>
                      <p className="mt-1.5 font-medium">{dispute.title}</p>
                      <p className="text-sm text-muted-foreground">
                        {dispute.brandName} vs {dispute.creatorName} · {formatINR(dispute.amountAtStake)} at stake ·
                        opened {relativeTime(dispute.openedAt)}
                      </p>
                    </div>
                    <Button size="sm" variant="outline" className="shrink-0">
                      Review case
                    </Button>
                  </div>
                ))}
              </CardContent>
            </Card>
          </TabsContent>

          {/* Transactions */}
          <TabsContent value="transactions">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Transaction oversight</CardTitle>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Reference</TableHead>
                      <TableHead>Type</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead className="text-right">Amount</TableHead>
                      <TableHead className="text-right">TDS</TableHead>
                      <TableHead className="text-right">When</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {demoAdminTransactions.map((tx) => (
                      <TableRow key={tx.id}>
                        <TableCell>
                          <p className="max-w-[280px] truncate font-medium">{tx.reference}</p>
                          <p className="text-xs text-muted-foreground">
                            {[tx.brandName, tx.creatorName].filter(Boolean).join(' → ')}
                          </p>
                        </TableCell>
                        <TableCell className="text-sm">{tx.type.replace(/_/g, ' ').toLowerCase()}</TableCell>
                        <TableCell>{txStatusBadge(tx)}</TableCell>
                        <TableCell className="text-right font-mono text-sm">{formatINR(tx.amount)}</TableCell>
                        <TableCell className="text-right font-mono text-sm text-muted-foreground">
                          {tx.tds ? formatINR(tx.tds) : '—'}
                        </TableCell>
                        <TableCell className="text-right text-sm text-muted-foreground">
                          {relativeTime(tx.createdAt)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          </TabsContent>

          {/* KYC */}
          <TabsContent value="kyc">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">KYC verification queue</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {demoAdminKycQueue.map((kyc) => (
                  <div
                    key={kyc.id}
                    className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div>
                      <p className="font-medium">{kyc.name}</p>
                      <p className="text-sm text-muted-foreground">
                        {kyc.userType === 'BRAND' ? 'Brand' : 'Creator'} · {kyc.document} ·
                        {' '}
                        <span className="inline-flex items-center gap-1">
                          <Clock className="h-3 w-3" aria-hidden="true" />
                          submitted {relativeTime(kyc.submittedAt)}
                        </span>
                      </p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <Button size="sm" variant="outline" className="gap-1">
                        <ArrowDownToLine className="h-3.5 w-3.5" aria-hidden="true" /> Documents
                      </Button>
                      <Button size="sm">Approve</Button>
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </main>
    </div>
  );
}
