import * as React from 'react';
import { FileSignature, Loader2, Plus, Send, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { cn, formatINR } from '@/lib/utils';

export interface MilestoneDraft {
  description: string;
  amount: number;
  dueDate: string;
}

interface DealContractGenerateProps {
  creatorName: string;
  campaignName: string;
  dealValue: number;
  isGenerating: boolean;
  onGenerate: (milestones: MilestoneDraft[]) => void;
}

/**
 * FE-4 — the "Review & send contract" trigger. Shown once terms are agreed
 * and no contract exists yet (`deal.contractId == null`). Pre-fills a single
 * milestone at the full `deal.dealValue` ("Release on final approval",
 * architecture doc §2) the brand can edit or split into several; submits
 * `POST /contracts { collaborationId, milestones }`. The server re-sums
 * `totalAmount` from these milestones — the client total shown here is
 * cosmetic, never trusted.
 */
export function DealContractGenerate({
  creatorName,
  campaignName,
  dealValue,
  isGenerating,
  onGenerate,
}: DealContractGenerateProps) {
  const [milestones, setMilestones] = React.useState<MilestoneDraft[]>([
    { description: 'Release on final approval', amount: dealValue, dueDate: '' },
  ]);

  const total = milestones.reduce((sum, m) => sum + (Number.isFinite(m.amount) ? m.amount : 0), 0);
  const totalMismatch = total !== dealValue;
  const hasEmptyDescription = milestones.some((m) => !m.description.trim());
  const hasInvalidAmount = milestones.some((m) => !(m.amount > 0));
  const canSubmit = milestones.length > 0 && !hasEmptyDescription && !hasInvalidAmount && !isGenerating;

  const updateMilestone = (index: number, patch: Partial<MilestoneDraft>) => {
    setMilestones((prev) => prev.map((m, i) => (i === index ? { ...m, ...patch } : m)));
  };

  const addMilestone = () => {
    setMilestones((prev) => [...prev, { description: '', amount: 0, dueDate: '' }]);
  };

  const removeMilestone = (index: number) => {
    setMilestones((prev) => (prev.length > 1 ? prev.filter((_, i) => i !== index) : prev));
  };

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        <Card className="border-primary/30 bg-primary/5">
          <CardContent className="pt-4 flex items-start gap-3">
            <FileSignature className="h-5 w-5 text-primary shrink-0 mt-0.5" />
            <div>
              <p className="font-medium text-sm">Terms agreed — send the contract</p>
              <p className="text-sm text-muted-foreground mt-1">
                {creatorName} has agreed to the terms for {campaignName}. Review the payment
                schedule below, then send the contract for signature.
              </p>
            </div>
          </CardContent>
        </Card>

        <Card className="border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Payment schedule</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {milestones.map((milestone, index) => (
              <div key={index} className="space-y-2 rounded-md border border-border p-3">
                <div className="flex items-center justify-between gap-2">
                  <Label htmlFor={`milestone-desc-${index}`} className="text-xs text-muted-foreground">
                    Milestone {index + 1}
                  </Label>
                  {milestones.length > 1 && (
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-6 w-6 text-muted-foreground hover:text-destructive-foreground"
                      onClick={() => removeMilestone(index)}
                      disabled={isGenerating}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </div>
                <Input
                  id={`milestone-desc-${index}`}
                  placeholder="Description (e.g. Release on final approval)"
                  value={milestone.description}
                  onChange={(e) => updateMilestone(index, { description: e.target.value })}
                  disabled={isGenerating}
                />
                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <Label htmlFor={`milestone-amount-${index}`} className="text-xs text-muted-foreground">
                      Amount (INR)
                    </Label>
                    <Input
                      id={`milestone-amount-${index}`}
                      type="number"
                      min={1}
                      value={milestone.amount}
                      onChange={(e) => updateMilestone(index, { amount: Number(e.target.value) || 0 })}
                      disabled={isGenerating}
                    />
                  </div>
                  <div>
                    <Label htmlFor={`milestone-due-${index}`} className="text-xs text-muted-foreground">
                      Due date (optional)
                    </Label>
                    <Input
                      id={`milestone-due-${index}`}
                      type="date"
                      value={milestone.dueDate}
                      onChange={(e) => updateMilestone(index, { dueDate: e.target.value })}
                      disabled={isGenerating}
                    />
                  </div>
                </div>
              </div>
            ))}

            <Button
              variant="outline"
              size="sm"
              className="gap-1.5"
              onClick={addMilestone}
              disabled={isGenerating}
            >
              <Plus className="h-3.5 w-3.5" />
              Split into another milestone
            </Button>

            <Separator />

            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Deal value</span>
              <span className="font-medium">{formatINR(dealValue)}</span>
            </div>
            <div className={cn('flex justify-between text-sm', totalMismatch && 'text-warning')}>
              <span className="text-muted-foreground">Milestone total</span>
              <span className="font-semibold">{formatINR(total)}</span>
            </div>
            {totalMismatch && (
              <p className="text-xs text-warning">
                Milestone total doesn&apos;t match the agreed deal value — the server will use the
                milestone total as the contract amount.
              </p>
            )}
          </CardContent>
        </Card>

        <Button
          className="w-full gap-2"
          disabled={!canSubmit}
          onClick={() => onGenerate(milestones)}
        >
          {isGenerating ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Sending contract...
            </>
          ) : (
            <>
              <Send className="h-4 w-4" />
              Send contract for signature
            </>
          )}
        </Button>
      </div>
    </ScrollArea>
  );
}
