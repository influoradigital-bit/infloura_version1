'use client';

import * as React from 'react';
import { AlertCircle, RotateCcw, AlertTriangle } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';

interface RevisionHandlerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  deliverableTitle: string;
  currentRevision: number;
  maxRevisions: number;
  brandFeedback: string;
  onStartRevision: (revisionNotes: string) => Promise<void>;
  isProcessing?: boolean;
}

export function RevisionHandler({
  open,
  onOpenChange,
  deliverableTitle,
  currentRevision,
  maxRevisions,
  brandFeedback,
  onStartRevision,
  isProcessing = false,
}: RevisionHandlerProps) {
  const [revisionNotes, setRevisionNotes] = React.useState('');
  const isLastRevision = currentRevision === maxRevisions - 1;

  const handleSubmit = async () => {
    if (revisionNotes.trim()) {
      try {
        await onStartRevision(revisionNotes);
        setRevisionNotes('');
        onOpenChange(false);
      } catch (error) {
        console.log('[v0] Error starting revision:', error);
      }
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
          <DialogDescription>
            {deliverableTitle} - Revision {currentRevision + 1} of {maxRevisions}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-4">
          {/* Revision Progress */}
          <div className="flex items-center justify-between p-3 bg-muted rounded-lg">
            <span className="text-sm font-medium">Revision Progress</span>
            <div className="flex gap-1">
              {Array.from({ length: maxRevisions }).map((_, i) => (
                <div
                  key={i}
                  className={`h-2 w-8 rounded-full transition-colors ${
                    i < currentRevision + 1 ? 'bg-blue-600' : 'bg-muted-foreground/30'
                  }`}
                />
              ))}
            </div>
          </div>

          {/* Brand Feedback */}
          <div className="space-y-2">
            <Label className="text-base font-semibold">Brand Feedback</Label>
            <div className="p-3 bg-orange-50 border border-orange-200 rounded-lg">
              <p className="text-sm text-foreground leading-relaxed">{brandFeedback}</p>
            </div>
          </div>

          {/* Last Revision Warning */}
          {isLastRevision && (
            <Alert className="border-red-200 bg-red-50">
              <AlertTriangle className="h-4 w-4 text-stage-disputed-fg" />
              <AlertDescription className="text-red-800">
                This is your final revision. After this, the brand must approve or you&apos;ll need to renegotiate terms for additional revisions.
              </AlertDescription>
            </Alert>
          )}

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
            />
            <p className="text-xs text-muted-foreground">{revisionNotes.length}/500 characters</p>
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
