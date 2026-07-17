'use client';

import * as React from 'react';
import { CheckCircle2, AlertTriangle, PackageX } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Alert, AlertDescription } from '@/components/ui/alert';

export type ReceiptCondition = 'good' | 'damaged' | 'wrong_item';

export interface ReceiptData {
  condition: ReceiptCondition;
  notes: string;
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (data: ReceiptData) => Promise<void> | void;
  isSubmitting?: boolean;
}

export function ReceiptConfirmation({ open, onOpenChange, onConfirm, isSubmitting }: Props) {
  const [condition, setCondition] = React.useState<ReceiptCondition>('good');
  const [notes, setNotes] = React.useState('');

  const requiresNotes = condition !== 'good';
  const canSubmit = condition === 'good' || notes.trim().length > 5;

  const handleConfirm = async () => {
    if (!canSubmit) return;
    await onConfirm({ condition, notes });
    setNotes('');
    setCondition('good');
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <CheckCircle2 className="h-5 w-5 text-stage-approved-fg" />
            Confirm Receipt
          </DialogTitle>
          <DialogDescription>
            Confirm you have received the shipment so production can begin.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label>Condition of items</Label>
            <RadioGroup value={condition} onValueChange={(v) => setCondition(v as ReceiptCondition)}>
              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/40">
                <RadioGroupItem value="good" id="good" className="mt-0.5" />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <CheckCircle2 className="h-4 w-4 text-stage-approved-fg" />
                    <span className="font-medium text-sm">Good — everything as expected</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">All items received in proper condition.</p>
                </div>
              </label>

              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/40">
                <RadioGroupItem value="damaged" id="damaged" className="mt-0.5" />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <AlertTriangle className="h-4 w-4 text-stage-negotiating-fg" />
                    <span className="font-medium text-sm">Damaged on arrival</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">Items received but in damaged condition.</p>
                </div>
              </label>

              <label className="flex items-start gap-3 p-3 border rounded-lg cursor-pointer hover:bg-muted/40">
                <RadioGroupItem value="wrong_item" id="wrong" className="mt-0.5" />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <PackageX className="h-4 w-4 text-stage-disputed-fg" />
                    <span className="font-medium text-sm">Wrong item / missing</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">Did not receive the correct items.</p>
                </div>
              </label>
            </RadioGroup>
          </div>

          <div className="space-y-2">
            <Label htmlFor="notes">
              Notes {requiresNotes && <span className="text-stage-disputed-fg">*</span>}
            </Label>
            <Textarea
              id="notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              placeholder={requiresNotes ? 'Describe the issue in detail...' : 'Optional unboxing notes...'}
            />
          </div>

          {requiresNotes && (
            <Alert className="border-stage-negotiating-border bg-amber-50">
              <AlertTriangle className="h-4 w-4 text-stage-negotiating-fg" />
              <AlertDescription className="text-amber-900 text-xs">
                Reporting an issue will pause the deal and notify the brand. You can resume once resolved.
              </AlertDescription>
            </Alert>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSubmitting}>Cancel</Button>
          <Button onClick={handleConfirm} disabled={!canSubmit || isSubmitting}>
            {isSubmitting ? 'Confirming...' : condition === 'good' ? 'Confirm Receipt' : 'Report Issue'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
