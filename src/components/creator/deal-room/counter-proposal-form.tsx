'use client';

import * as React from 'react';
import { ChevronRight, X, Loader2 } from 'lucide-react';
import { cn, formatINR } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';

export interface CounterProposalFormData {
  proposedAmount: number;
  deadline: string;
  terms: string;
  message: string;
}

interface CounterProposalFormProps {
  brandName: string;
  originalAmount: number;
  deliverables: Array<{ title: string; quantity: number }>;
  onSubmit: (data: CounterProposalFormData) => void;
  onClose: () => void;
  isSubmitting?: boolean;
}

export function CounterProposalForm({
  brandName,
  originalAmount,
  deliverables,
  onSubmit,
  onClose,
  isSubmitting = false,
}: CounterProposalFormProps) {
  const [step, setStep] = React.useState(1);
  const [formData, setFormData] = React.useState<CounterProposalFormData>({
    proposedAmount: originalAmount,
    deadline: '',
    terms: '',
    message: '',
  });

  const totalSteps = 4;
  // Deadline must be today or later — a delivery date in the past is never valid.
  const todayStr = React.useMemo(() => new Date().toISOString().split('T')[0], []);
  const deadlineInPast = formData.deadline !== '' && formData.deadline < todayStr;
  const steps = [
    { num: 1, title: 'Review Proposal', subtitle: 'Check the details' },
    { num: 2, title: 'Your Rate', subtitle: 'Propose your amount' },
    { num: 3, title: 'Terms', subtitle: 'Any changes needed?' },
    { num: 4, title: 'Message', subtitle: 'Send your counter offer' },
  ];

  // Calculate earnings breakdown
  const calculateEarnings = (amount: number) => {
    const platformFee = amount * 0.1;
    const gstOnFee = platformFee * 0.18;
    const tds = amount * 0.1;
    const netEarnings = amount - platformFee - gstOnFee - tds;
    return { platformFee, gstOnFee, tds, netEarnings };
  };

  const earnings = calculateEarnings(formData.proposedAmount);
  const difference = formData.proposedAmount - originalAmount;
  const diffPercent = ((difference / originalAmount) * 100).toFixed(1);

  const handleNext = () => {
    if (step < totalSteps) {
      setStep(step + 1);
    }
  };

  const handleBack = () => {
    if (step > 1) {
      setStep(step - 1);
    }
  };

  const handleAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = parseInt(e.target.value) || 0;
    setFormData({ ...formData, proposedAmount: value });
  };

  const handleSubmit = () => {
    onSubmit(formData);
  };

  return (
    <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4">
      <Card className="w-full max-w-2xl max-h-[90vh] overflow-y-auto">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 border-b pb-4">
          <div>
            <CardTitle>Counter Proposal to {brandName}</CardTitle>
            <p className="text-sm text-muted-foreground mt-1">Step {step} of {totalSteps}</p>
          </div>
          <button
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="h-5 w-5" />
          </button>
        </CardHeader>

        <CardContent className="pt-6">
          <Progress value={(step / totalSteps) * 100} className="mb-6" />

          {/* Step 1: Review Proposal */}
          {step === 1 && (
            <div className="space-y-4">
              <h3 className="font-semibold">Original Proposal Details</h3>
              <div className="space-y-3 bg-muted p-4 rounded-lg">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Proposed Amount:</span>
                  <span className="font-semibold">{formatINR(originalAmount)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Deliverables:</span>
                  <span className="font-semibold">{deliverables.length} items</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Your Net Earnings:</span>
                  <span className="font-semibold text-stage-approved-fg">
                    {formatINR(calculateEarnings(originalAmount).netEarnings)}
                  </span>
                </div>
              </div>
              <p className="text-sm text-muted-foreground">
                Review the original proposal details and decide if you want to counter offer with a different rate.
              </p>
            </div>
          )}

          {/* Step 2: Your Rate */}
          {step === 2 && (
            <div className="space-y-4">
              <div>
                <Label htmlFor="amount">Proposed Amount (INR)</Label>
                <div className="relative mt-2">
                  <Input
                    id="amount"
                    type="number"
                    placeholder="50000"
                    value={formData.proposedAmount}
                    onChange={handleAmountChange}
                    className="pl-8"
                  />
                  <span className="absolute left-3 top-2.5 text-muted-foreground">₹</span>
                </div>
              </div>

              {/* Rate Comparison */}
              <div className="bg-blue-50 p-4 rounded-lg space-y-3">
                <h4 className="font-semibold text-blue-900">Original vs Your Counter</h4>
                <div className="flex justify-between items-center">
                  <div>
                    <p className="text-sm text-muted-foreground">Original Rate</p>
                    <p className="font-semibold">{formatINR(originalAmount)}</p>
                  </div>
                  <div className="text-center">
                    <p className="text-xs text-muted-foreground">Difference</p>
                    <p className={cn(
                      'font-semibold',
                      difference > 0 ? 'text-stage-approved-fg' : difference < 0 ? 'text-stage-disputed-fg' : 'text-muted-foreground'
                    )}>
                      {difference > 0 ? '+' : ''}{formatINR(difference)} ({diffPercent}%)
                    </p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">Your Counter</p>
                    <p className="font-semibold">{formatINR(formData.proposedAmount)}</p>
                  </div>
                </div>
              </div>

              {/* Earnings Breakdown */}
              <div className="space-y-2 bg-amber-50 p-4 rounded-lg border border-stage-negotiating-border">
                <h4 className="font-semibold text-amber-900">Your Earnings Breakdown</h4>
                <div className="space-y-1.5 text-sm">
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Proposed Amount:</span>
                    <span className="font-semibold">{formatINR(formData.proposedAmount)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Platform Fee (10%):</span>
                    <span className="text-stage-disputed-fg">-{formatINR(earnings.platformFee)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">GST on Fee (18%):</span>
                    <span className="text-stage-disputed-fg">-{formatINR(earnings.gstOnFee)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">TDS (10%):</span>
                    <span className="text-stage-disputed-fg">-{formatINR(earnings.tds)}</span>
                  </div>
                  <div className="flex justify-between border-t border-stage-negotiating-border pt-1.5 font-semibold text-amber-900">
                    <span>You Receive:</span>
                    <span className="text-lg">{formatINR(earnings.netEarnings)}</span>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Step 3: Terms */}
          {step === 3 && (
            <div className="space-y-4">
              <div>
                <Label htmlFor="deadline">Delivery Deadline</Label>
                <Input
                  id="deadline"
                  type="date"
                  min={todayStr}
                  value={formData.deadline}
                  onChange={(e) => setFormData({ ...formData, deadline: e.target.value })}
                  className="mt-2"
                  aria-invalid={deadlineInPast}
                />
                {deadlineInPast && (
                  <p className="text-xs text-stage-disputed-fg mt-1">
                    Delivery deadline can&apos;t be in the past — pick today or a later date.
                  </p>
                )}
              </div>

              <div>
                <Label htmlFor="terms">Any Changes to Terms?</Label>
                <Textarea
                  id="terms"
                  placeholder="E.g., max 2 revisions, 30-day exclusive usage, etc."
                  value={formData.terms}
                  onChange={(e) => setFormData({ ...formData, terms: e.target.value })}
                  className="mt-2 min-h-32"
                />
                <p className="text-xs text-muted-foreground mt-1">
                  Leave blank to accept the same terms
                </p>
              </div>
            </div>
          )}

          {/* Step 4: Message */}
          {step === 4 && (
            <div className="space-y-4">
              <div>
                <Label htmlFor="message">Your Message to {brandName}</Label>
                <Textarea
                  id="message"
                  placeholder="Explain why you're proposing this rate, highlight your strengths, or any special offers..."
                  value={formData.message}
                  onChange={(e) => setFormData({ ...formData, message: e.target.value })}
                  className="mt-2 min-h-40"
                />
              </div>

              {/* Final Summary */}
              <Card className="bg-green-50 border-green-200">
                <CardContent className="pt-6">
                  <h4 className="font-semibold text-green-900 mb-3">Counter Proposal Summary</h4>
                  <div className="space-y-2 text-sm">
                    <div className="flex justify-between">
                      <span>Proposed Amount:</span>
                      <span className="font-semibold">{formatINR(formData.proposedAmount)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span>Your Net Earnings:</span>
                      <span className="font-semibold text-stage-approved-fg">{formatINR(earnings.netEarnings)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span>Delivery by:</span>
                      <span className="font-semibold">{formData.deadline || 'Not specified'}</span>
                    </div>
                    {formData.terms && (
                      <div className="flex justify-between">
                        <span>Custom Terms:</span>
                        <span className="font-semibold">Added</span>
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>
            </div>
          )}

          {/* Navigation */}
          <div className="flex gap-3 mt-8">
            {step > 1 && (
              <Button variant="outline" onClick={handleBack} disabled={isSubmitting}>
                Back
              </Button>
            )}
            <div className="flex-1" />
            {step < totalSteps ? (
              <Button onClick={handleNext} disabled={isSubmitting || (step === 3 && deadlineInPast)}>
                Next
                <ChevronRight className="ml-2 h-4 w-4" />
              </Button>
            ) : (
              <Button
                onClick={handleSubmit}
                disabled={isSubmitting}
                className="bg-stage-approved-fg hover:opacity-90 text-white"
              >
                {isSubmitting ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Sending...
                  </>
                ) : (
                  <>
                    Send Counter Proposal
                    <ChevronRight className="ml-2 h-4 w-4" />
                  </>
                )}
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
