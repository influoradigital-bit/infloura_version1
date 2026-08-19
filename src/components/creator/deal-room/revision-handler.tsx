'use client';

import * as React from 'react';
import { AlertCircle, RotateCcw } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';

/** The notes field's own limit — enforced on the input, not merely announced by the counter. */
const NOTES_MAX_LENGTH = 500;

interface RevisionHandlerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  deliverableTitle: string;
  /**
   * The revision number the brand just asked for — `Deliverable.revisionCount` straight off the
   * wire. There is deliberately no `maxRevisions` companion (F-0360): nothing server-side caps
   * revisions, so this dialog states the count and claims no ceiling.
   */
  currentRevision: number;
  brandFeedback: string;
  onStartRevision: (revisionNotes: string) => Promise<void>;
  isProcessing?: boolean;
}

export function RevisionHandler({
  open,
  onOpenChange,
  deliverableTitle,
  currentRevision,
  brandFeedback,
  onStartRevision,
  isProcessing = false,
}: RevisionHandlerProps) {
  const { toast } = useToast();
  const [revisionNotes, setRevisionNotes] = React.useState('');

  const handleSubmit = async () => {
    // F-0359 — the notes really are optional (the label says so, and SubmitRequest.notes is
    // optional server-side). The old `if (revisionNotes.trim())` guard made an always-enabled
    // button do nothing at all when the field was left blank, so the revision could never start.
    try {
      await onStartRevision(revisionNotes);
      setRevisionNotes('');
      onOpenChange(false);
    } catch (error) {
      toast({
        title: 'Could not start revision',
        description: error instanceof ApiError ? error.message : 'Please try again.',
        variant: 'destructive',
      });
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <RotateCcw className="h-5 w-5" />
            Revise Deliverable
          </DialogTitle>
          {/* F-0360 — `currentRevision` is rendered raw, exactly as the card that opens this
              dialog renders it, so one row can never show two different numbers. The old
              `Math.min(..., maxRevisions)` clamp went with the fictional maximum it clamped to:
              no server code caps revisionCount, so there is no ceiling to clamp against and no
              "final revision" this dialog could honestly announce. */}
          <DialogDescription>
            {deliverableTitle} - Revision {currentRevision}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-4">
          {/* Revisions requested so far. A progress bar needs a denominator; this product has
              none (F-0360), so the honest render is the count itself. */}
          <div className="flex items-center justify-between p-3 bg-muted rounded-lg">
            <span className="text-sm font-medium">Revisions requested</span>
            <span className="text-sm font-semibold tabular-nums">{currentRevision}</span>
          </div>

          {/* Brand Feedback */}
          <div className="space-y-2">
            <Label className="text-base font-semibold">Brand Feedback</Label>
            <div className="p-3 bg-orange-50 border border-orange-200 rounded-lg">
              <p className="text-sm text-foreground leading-relaxed">{brandFeedback}</p>
            </div>
          </div>

          {/* Your Notes */}
          <div className="space-y-2">
            <Label htmlFor="revision-notes">Your Revision Notes (Optional)</Label>
            <Textarea
              id="revision-notes"
              placeholder="Explain what you've changed or improved based on the feedback..."
              rows={4}
              value={revisionNotes}
              onChange={(e) => setRevisionNotes(e.target.value)}
              disabled={isProcessing}
              // F-0360 — the counter promised a 500-character limit the field never imposed, so
              // it happily displayed "700/500" with nothing blocked and no error. The limit is
              // now real, which is what makes the counter true.
              maxLength={NOTES_MAX_LENGTH}
            />
            <p className="text-xs text-muted-foreground">
              {revisionNotes.length}/{NOTES_MAX_LENGTH} characters
            </p>
          </div>

          {/* Info Box */}
          <Alert>
            <AlertCircle className="h-4 w-4" />
            <AlertDescription>
              You&apos;ll be able to upload the revised file in the next step. Make sure your changes address all of the brand&apos;s feedback.
            </AlertDescription>
          </Alert>
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isProcessing}
          >
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={isProcessing}
          >
            {isProcessing ? 'Starting...' : 'Proceed to Upload'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
