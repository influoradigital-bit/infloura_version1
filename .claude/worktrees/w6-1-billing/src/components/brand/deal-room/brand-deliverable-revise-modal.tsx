'use client';

import * as React from 'react';
import { AlertCircle, MessageSquare } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';

interface BrandDeliverableReviseModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  deliverableTitle: string;
  onSubmit: (feedback: string) => Promise<void>;
  isSubmitting?: boolean;
  error?: string | null;
}

export function BrandDeliverableReviseModal({
  open,
  onOpenChange,
  deliverableTitle,
  onSubmit,
  isSubmitting = false,
  error = null,
}: BrandDeliverableReviseModalProps) {
  const [feedback, setFeedback] = React.useState('');
  const [validationError, setValidationError] = React.useState('');

  React.useEffect(() => {
    if (!open) {
      setFeedback('');
      setValidationError('');
    }
  }, [open]);

  const handleSubmit = async () => {
    const trimmed = feedback.trim();
    if (!trimmed) {
      setValidationError('Please provide feedback so the creator knows what to change');
      return;
    }
    setValidationError('');
    try {
      await onSubmit(trimmed);
      setFeedback('');
      onOpenChange(false);
    } catch {
      // Parent surfaces API errors via `error` prop
    }
  };

  const displayError = validationError || error;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <MessageSquare className="h-5 w-5" />
            Request Changes
          </DialogTitle>
          <DialogDescription>
            Send revision notes for <span className="font-medium text-foreground">{deliverableTitle}</span>.
            Feedback is required.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-2 py-2">
          <Label htmlFor="brand-revise-feedback">
            Feedback <span className="text-destructive-foreground">*</span>
          </Label>
          <Textarea
            id="brand-revise-feedback"
            placeholder="E.g., Please adjust the product focus in the first 3 seconds..."
            rows={4}
            value={feedback}
            onChange={(e) => {
              setFeedback(e.target.value);
              if (validationError) setValidationError('');
            }}
            disabled={isSubmitting}
            className={displayError ? 'border-destructive-foreground' : undefined}
            maxLength={2000}
          />
          {displayError && (
            <div className="flex items-center gap-1.5 text-destructive-foreground text-xs">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" />
              {displayError}
            </div>
          )}
          <p className="text-xs text-muted-foreground">{feedback.length}/2000 characters</p>
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
            {isSubmitting ? 'Sending...' : 'Send Revision Request'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
