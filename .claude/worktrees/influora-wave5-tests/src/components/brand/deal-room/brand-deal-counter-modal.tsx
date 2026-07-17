'use client';

import * as React from 'react';
import { AlertCircle, HandCoins } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { formatINR } from '@/lib/utils';

export interface BrandDealCounterData {
  amount: number;
  message?: string;
}

interface BrandDealCounterModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  creatorName: string;
  currentAmount: number;
  onSubmit: (data: BrandDealCounterData) => Promise<void>;
  isSubmitting?: boolean;
  error?: string | null;
}

export function BrandDealCounterModal({
  open,
  onOpenChange,
  creatorName,
  currentAmount,
  onSubmit,
  isSubmitting = false,
  error = null,
}: BrandDealCounterModalProps) {
  const [amount, setAmount] = React.useState(String(currentAmount || ''));
  const [message, setMessage] = React.useState('');
  const [validationError, setValidationError] = React.useState('');

  React.useEffect(() => {
    if (open) {
      setAmount(String(currentAmount || ''));
      setMessage('');
      setValidationError('');
    }
  }, [open, currentAmount]);

  const handleSubmit = async () => {
    const parsed = Number(amount);
    if (!amount.trim() || !Number.isFinite(parsed) || parsed <= 0) {
      setValidationError('Enter a valid counter offer amount greater than 0');
      return;
    }
    setValidationError('');
    try {
      await onSubmit({ amount: parsed, message: message.trim() || undefined });
      setAmount('');
      setMessage('');
      onOpenChange(false);
    } catch {
      // Parent surfaces API errors via `error` prop; keep dialog open on failure.
    }
  };

  const displayError = validationError || error;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <HandCoins className="h-5 w-5" />
            Counter Offer to {creatorName}
          </DialogTitle>
          <DialogDescription>
            Propose a different amount for this deal. The creator will see your counter offer and
            can accept, counter back, or decline.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="brand-counter-amount">
              Counter Amount (INR) <span className="text-destructive-foreground">*</span>
            </Label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground text-sm">
                ₹
              </span>
              <Input
                id="brand-counter-amount"
                type="number"
                min={1}
                placeholder="50000"
                value={amount}
                onChange={(e) => {
                  setAmount(e.target.value);
                  if (validationError) setValidationError('');
                }}
                disabled={isSubmitting}
                className="pl-7"
              />
            </div>
            <p className="text-xs text-muted-foreground">
              Current amount: {formatINR(currentAmount)}
            </p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="brand-counter-message">Message (optional)</Label>
            <Textarea
              id="brand-counter-message"
              placeholder="Explain the revised terms or reasoning behind this offer..."
              rows={4}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              disabled={isSubmitting}
              maxLength={2000}
            />
            <p className="text-xs text-muted-foreground">{message.length}/2000 characters</p>
          </div>

          {displayError && (
            <div className="flex items-center gap-1.5 text-destructive-foreground text-xs">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" />
              {displayError}
            </div>
          )}
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
          <Button type="button" onClick={() => void handleSubmit()} disabled={isSubmitting}>
            {isSubmitting ? 'Sending...' : 'Send Counter Offer'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
