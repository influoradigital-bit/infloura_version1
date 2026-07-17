import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { CheckCircle2, Pen, X, AlertCircle, Video as VideoIcon, Image as ImageIcon } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { useDeliverableDetail } from '@/hooks/brand/useDeliverableDetail';
import { api } from '@/lib/api';
import type { DeliverableFile, DeliverableStatus } from '@/lib/api';

interface DeliverableViewerProps {
  deliverableId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onActionComplete?: () => void;
}

const statusConfig: Record<DeliverableStatus, { label: string; className: string }> = {
  PENDING: { label: 'Pending', className: 'bg-muted text-muted-foreground' },
  DRAFT: { label: 'Draft', className: 'bg-muted text-muted-foreground' },
  SUBMITTED: { label: 'Submitted', className: 'bg-warning/15 text-warning' },
  REVISION_REQUESTED: { label: 'Revision Requested', className: 'bg-orange-100 text-orange-700' },
  RESUBMITTED: { label: 'Resubmitted', className: 'bg-warning/15 text-warning' },
  APPROVED: { label: 'Approved', className: 'bg-success/15 text-success' },
  REJECTED: { label: 'Rejected', className: 'bg-destructive/15 text-destructive' },
  POSTED: { label: 'Posted', className: 'bg-blue-100 text-blue-700' },
  METRICS_REPORTED: { label: 'Metrics Reported', className: 'bg-blue-100 text-blue-700' },
  VERIFIED: { label: 'Verified', className: 'bg-success/15 text-success' },
};

function MediaPlayer({ file, onLoadError }: { file: DeliverableFile; onLoadError: () => void }) {
  const [hasError, setHasError] = React.useState(false);
  const [retryNonce, setRetryNonce] = React.useState(0);
  const shouldReduceMotion = useReducedMotion();

  // Reset the error state whenever a fresh URL actually arrives — either because
  // refetch() resolved with a new presigned link, or because the brand navigated
  // to a different file. Without this, hasError stays true forever after the
  // first 403: the fresh URL from refetch() is fetched but never attempted.
  React.useEffect(() => {
    setHasError(false);
  }, [file.url]);

  const handleError = () => {
    setHasError(true);
    // Bump the nonce so the <video>/<img> remounts (new key) once the fresh
    // presigned URL comes back — React won't reload a live <video>/<img> just
    // because `src` changed, so a key change is required to force reload.
    setRetryNonce((n) => n + 1);
    onLoadError();
  };

  if (file.fileType === 'VIDEO') {
    return (
      <div className="relative bg-black rounded-lg overflow-hidden">
        {hasError ? (
          <div className="flex flex-col items-center justify-center h-64 text-muted-foreground gap-2">
            <VideoIcon className={cn('h-12 w-12', !shouldReduceMotion && 'animate-pulse')} />
            <p className="text-sm">Refreshing video link...</p>
          </div>
        ) : (
          <video
            key={`${file.id}-${retryNonce}`}
            controls
            className="w-full max-h-[500px]"
            poster={file.thumbnailUrl || undefined}
            onError={handleError}
          >
            <source src={file.url} type="video/mp4" />
            Your browser does not support video playback.
          </video>
        )}
      </div>
    );
  }

  // IMAGE
  return (
    <div className="relative bg-muted rounded-lg overflow-hidden flex items-center justify-center">
      {hasError ? (
        <div className="flex flex-col items-center justify-center h-64 text-muted-foreground gap-2">
          <ImageIcon className={cn('h-12 w-12', !shouldReduceMotion && 'animate-pulse')} />
          <p className="text-sm">Refreshing image link...</p>
        </div>
      ) : (
        <img
          key={`${file.id}-${retryNonce}`}
          src={file.url}
          alt={file.fileName}
          className="max-w-full max-h-[500px] object-contain"
          onError={handleError}
        />
      )}
    </div>
  );
}

function ReviseModal({
  open,
  onOpenChange,
  onSubmit,
  isSubmitting,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (feedback: string) => Promise<void>;
  isSubmitting: boolean;
}) {
  const [feedback, setFeedback] = React.useState('');

  const handleSubmit = async () => {
    if (!feedback.trim()) return;
    await onSubmit(feedback);
    setFeedback('');
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Request Revision</DialogTitle>
        </DialogHeader>
        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <Label htmlFor="feedback">Feedback for Creator</Label>
            <Textarea
              id="feedback"
              placeholder="Explain what changes you'd like to see..."
              rows={5}
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              disabled={isSubmitting}
            />
            <p className="text-xs text-muted-foreground">
              Be specific about what needs to change so the creator can deliver exactly what you need.
            </p>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!feedback.trim() || isSubmitting}>
            {isSubmitting ? 'Sending...' : 'Send Feedback'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function DeliverableViewer({
  deliverableId,
  open,
  onOpenChange,
  onActionComplete,
}: DeliverableViewerProps) {
  const { deliverable, isLoading, error, refetch } = useDeliverableDetail(deliverableId);
  const [reviseModalOpen, setReviseModalOpen] = React.useState(false);
  const [isApproving, setIsApproving] = React.useState(false);
  const [isRevising, setIsRevising] = React.useState(false);
  const [currentFileIndex, setCurrentFileIndex] = React.useState(0);
  const shouldReduceMotion = useReducedMotion();

  const handleApprove = async () => {
    if (!deliverable) return;
    setIsApproving(true);
    try {
      await api.deliverables.approve(deliverable.id);
      onActionComplete?.();
      onOpenChange(false);
    } catch (err) {
      console.error('Failed to approve deliverable:', err);
    } finally {
      setIsApproving(false);
    }
  };

  const handleRequestRevision = async (feedback: string) => {
    if (!deliverable) return;
    setIsRevising(true);
    try {
      await api.deliverables.requestRevision(deliverable.id, feedback);
      setReviseModalOpen(false);
      onActionComplete?.();
      onOpenChange(false);
    } catch (err) {
      console.error('Failed to request revision:', err);
    } finally {
      setIsRevising(false);
    }
  };

  const handleMediaError = () => {
    // Presigned link expired (15 min) — fetch fresh link
    refetch();
  };

  if (isLoading) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-4xl max-h-[90vh] overflow-y-auto">
          <div className="flex items-center justify-center py-12">
            <div className="space-y-2 text-center">
              <div
                className={cn(
                  'h-8 w-8 border-2 border-primary rounded-full mx-auto',
                  shouldReduceMotion ? 'border-t-primary/40' : 'border-t-transparent animate-spin',
                )}
                role="status"
                aria-label="Loading"
              />
              <p className="text-sm text-muted-foreground">Loading deliverable...</p>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    );
  }

  if (error || !deliverable) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-md">
          <div className="flex flex-col items-center justify-center py-8 gap-4">
            <AlertCircle className="h-12 w-12 text-destructive" />
            <div className="text-center space-y-2">
              <p className="font-medium">Could not load deliverable</p>
              <p className="text-sm text-muted-foreground">{error || 'Unknown error'}</p>
            </div>
            <Button onClick={() => refetch()}>Try Again</Button>
          </div>
        </DialogContent>
      </Dialog>
    );
  }

  const currentFile = deliverable.files[currentFileIndex];
  const hasMultipleFiles = deliverable.files.length > 1;

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-4xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <div className="flex items-start justify-between gap-4">
              <div className="flex-1 space-y-1">
                <DialogTitle className="text-xl">{deliverable.title}</DialogTitle>
                <div className="flex items-center gap-2">
                  <Badge className={cn('text-xs', statusConfig[deliverable.status]?.className)}>
                    {statusConfig[deliverable.status]?.label || deliverable.status}
                  </Badge>
                  <span className="text-xs text-muted-foreground">Version {deliverable.versionNumber}</span>
                </div>
              </div>
            </div>
          </DialogHeader>

          <div className="space-y-6 py-4">
            {/* Media Player */}
            <div className="space-y-2">
              {currentFile && <MediaPlayer file={currentFile} onLoadError={handleMediaError} />}

              {/* File navigation for multiple files */}
              {hasMultipleFiles && (
                <div className="flex items-center justify-center gap-2">
                  {deliverable.files.map((file, idx) => (
                    <button
                      key={file.id}
                      onClick={() => setCurrentFileIndex(idx)}
                      className={cn(
                        'h-2 w-2 rounded-full transition-colors',
                        idx === currentFileIndex ? 'bg-primary' : 'bg-muted-foreground/30',
                      )}
                      aria-label={`View file ${idx + 1}`}
                    />
                  ))}
                </div>
              )}
            </div>

            {/* Caption & Hashtags */}
            {(deliverable.caption || deliverable.hashtags.length > 0) && (
              <Card>
                <CardHeader>
                  <CardTitle className="text-sm">Caption</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  {deliverable.caption && (
                    <p className="text-sm whitespace-pre-wrap">{deliverable.caption}</p>
                  )}
                  {deliverable.hashtags.length > 0 && (
                    <div className="flex flex-wrap gap-2">
                      {deliverable.hashtags.map((tag) => (
                        <Badge key={tag} variant="secondary" className="text-xs">
                          {tag}
                        </Badge>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>
            )}

            {/* Creator Notes */}
            {deliverable.creatorNotes && (
              <Card>
                <CardHeader>
                  <CardTitle className="text-sm">Creator Notes</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground">{deliverable.creatorNotes}</p>
                </CardContent>
              </Card>
            )}

            {/* Review Notes (if revision was previously requested) */}
            {deliverable.reviewNotes && (
              <Card className="border-orange-200 bg-orange-50/50">
                <CardHeader>
                  <CardTitle className="text-sm text-orange-900">Previous Feedback</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-orange-800">{deliverable.reviewNotes}</p>
                </CardContent>
              </Card>
            )}

            {/* Action Buttons */}
            {(deliverable.canApprove || deliverable.canRequestRevision) && (
              <div className="flex items-center justify-end gap-3 pt-4 border-t">
                {deliverable.canRequestRevision && (
                  <Button
                    variant="outline"
                    onClick={() => setReviseModalOpen(true)}
                    disabled={isApproving}
                  >
                    <Pen className="h-4 w-4 mr-2" />
                    Request Changes
                  </Button>
                )}
                {deliverable.canApprove && (
                  <Button
                    onClick={handleApprove}
                    disabled={isApproving}
                    className="bg-accent-foreground text-white hover:bg-accent-foreground/90"
                  >
                    {isApproving ? (
                      <>
                        <div
                          className={cn(
                            'h-4 w-4 border-2 border-white rounded-full mr-2',
                            shouldReduceMotion ? 'border-t-white/40' : 'border-t-transparent animate-spin',
                          )}
                          role="status"
                          aria-label="Approving"
                        />
                        Approving...
                      </>
                    ) : (
                      <>
                        <CheckCircle2 className="h-4 w-4 mr-2" />
                        Approve Deliverable
                      </>
                    )}
                  </Button>
                )}
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>

      <ReviseModal
        open={reviseModalOpen}
        onOpenChange={setReviseModalOpen}
        onSubmit={handleRequestRevision}
        isSubmitting={isRevising}
      />
    </>
  );
}

export default DeliverableViewer;
