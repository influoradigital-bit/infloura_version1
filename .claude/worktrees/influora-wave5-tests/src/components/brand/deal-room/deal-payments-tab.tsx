import { Link } from 'react-router-dom';
import { IndianRupee, Lock, Shield, Unlock } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { formatINR } from '@/lib/utils';
import type { DealContractStatus } from './deal-contract-tab';

interface PaymentMilestone {
  id: string;
  label: string;
  amount: number;
  status: 'pending' | 'locked' | 'released';
  date?: string;
}

interface DealPaymentsTabProps {
  dealValue: number;
  contractStatus: DealContractStatus | null;
  deliverablesDone: number;
  deliverablesTotal: number;
}

export function DealPaymentsTab({
  dealValue,
  contractStatus,
  deliverablesDone,
  deliverablesTotal,
}: DealPaymentsTabProps) {
  const escrowLocked =
    contractStatus === 'active' || contractStatus === 'creator_signed';
  const perDeliverable =
    deliverablesTotal > 0 ? Math.round(dealValue / deliverablesTotal) : dealValue;

  const milestones: PaymentMilestone[] = [
    {
      id: 'escrow',
      label: 'Escrow funded',
      amount: dealValue,
      status: escrowLocked ? 'locked' : 'pending',
    },
    ...Array.from({ length: deliverablesTotal }, (_, i) => ({
      id: `del-${i + 1}`,
      label: `Deliverable ${i + 1} payout`,
      amount: perDeliverable,
      status: (i < deliverablesDone ? 'released' : escrowLocked ? 'locked' : 'pending') as
        | 'pending'
        | 'locked'
        | 'released',
      date: i < deliverablesDone ? new Date().toLocaleDateString('en-IN') : undefined,
    })),
  ];

  const releasedTotal = milestones
    .filter((m) => m.status === 'released')
    .reduce((s, m) => s + m.amount, 0);

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        <Card className={escrowLocked ? 'border-success/30 bg-success/5' : 'border-muted'}>
          <CardContent className="pt-4 flex items-start gap-3">
            {escrowLocked ? (
              <Lock className="h-5 w-5 text-success shrink-0 mt-0.5" />
            ) : (
              <Shield className="h-5 w-5 text-muted-foreground shrink-0 mt-0.5" />
            )}
            <div>
              <p className="font-medium text-sm">
                {escrowLocked ? 'Escrow active' : 'Escrow not funded yet'}
              </p>
              <p className="text-sm text-muted-foreground mt-1">
                {escrowLocked
                  ? `${formatINR(dealValue)} is secured until deliverables are approved.`
                  : 'Fund escrow after both parties sign the contract.'}
              </p>
            </div>
          </CardContent>
        </Card>

        <div className="grid grid-cols-2 gap-4">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                In escrow
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold">
                {formatINR(escrowLocked ? dealValue - releasedTotal : 0)}
              </p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">Released</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold text-success">{formatINR(releasedTotal)}</p>
            </CardContent>
          </Card>
        </div>

        <Separator />

        <div>
          <h3 className="font-semibold text-sm mb-3">Payment milestones</h3>
          <div className="space-y-2">
            {milestones.map((m) => (
              <div
                key={m.id}
                className="flex items-center justify-between p-3 rounded-lg border bg-card"
              >
                <div className="flex items-center gap-2">
                  {m.status === 'released' ? (
                    <Unlock className="h-4 w-4 text-success" />
                  ) : m.status === 'locked' ? (
                    <Lock className="h-4 w-4 text-warning" />
                  ) : (
                    <IndianRupee className="h-4 w-4 text-muted-foreground" />
                  )}
                  <span className="text-sm">{m.label}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{formatINR(m.amount)}</span>
                  <Badge
                    variant="outline"
                    className={
                      m.status === 'released'
                        ? 'text-success border-success/30'
                        : m.status === 'locked'
                          ? 'text-warning border-warning/30'
                          : ''
                    }
                  >
                    {m.status}
                  </Badge>
                </div>
              </div>
            ))}
          </div>
        </div>

        <Button variant="outline" className="w-full" asChild>
          <Link to="/brand/wallet">View wallet & transactions</Link>
        </Button>
      </div>
    </ScrollArea>
  );
}
