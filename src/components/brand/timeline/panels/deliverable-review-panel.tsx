'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Label } from '@/components/ui/label';
import { Progress } from '@/components/ui/progress';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { TimelineEvent } from '@/lib/types';
import { deliverables as deliverablesApi, ApiError } from '@/lib/api';
import {
  Play, MessageSquare, CheckCircle2, AlertCircle, Upload,
  FileIcon, X, Download,
} from 'lucide-react';

/** Emitted after a brand approves or requests revision on a deliverable, so the
 *  parent timeline can optimistically update the deliverable's rendered status. */
export interface DeliverableReviewResult {
  deliverableId: string;
  status: string;
  feedback?: string;
}

interface DeliverableReviewPanelProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  event: TimelineEvent;
  currentUserType: 'brand' | 'creator';
  onApprove?: (eventId: string, feedback: string) => void;
  onRequestRevision?: (eventId: string, feedback: string) => void;
  onUploadRevision?: (eventId: string, file: File) => void;
  onReviewSuccess?: (result: DeliverableReviewResult) => void;
}

export function DeliverableReviewPanel({
  open,
  onOpenChange,
  event,
  currentUserType,
  onApprove,
  onRequestRevision,
  onUploadRevision,
  onReviewSuccess,
}: DeliverableReviewPanelProps) {
  const meta = event.metadata;
  const status = meta?.deliverableStatus || 'submitted';

  const [feedback, setFeedback] = React.useState('');
  const [feedbackError, setFeedbackError] = React.useState('');
  const [submitError, setSubmitError] = React.useState('');
  const [uploading, setUploading] = React.useState(false);
  const [uploadProgress, setUploadProgress] = React.useState(0);
  const [selectedFile, setSelectedFile] = React.useState<File | null>(null);
  const [isSubmitting, setIsSubmitting] = React.useState(false);

  const resetState = () => {
    setFeedback('');
    setFeedbackError('');
    setSubmitError('');
    setSelectedFile(null);
    setUploadProgress(0);
  };

  React.useEffect(() => {
    if (!open) resetState();
  }, [open]);

  // Real deliverable id used by the backend (POST /deliverables/:id/approve|revise) —
  // distinct from `event.id`, which is the timeline event's own id. See
  // deliverablesApi.approve/requestRevision (src/lib/api.ts:1434-1446), the
  // proven pattern already used by brand-chat.tsx's handleApproveLive/handleReviseLive.
  const deliverableId = meta?.deliverableId;

  const handleApprove = async () => {
    if (!deliverableId) {
      setSubmitError('Missing deliverable reference — cannot approve.');
      return;
    }
    setSubmitError('');
    setIsSubmitting(true);
    try {
      await deliverablesApi.approve(deliverableId);
      onApprove?.(event.id, feedback.trim());
      onReviewSuccess?.({ deliverableId, status: 'approved', feedback: feedback.trim() || undefined });
      onOpenChange(false);
    } catch (err) {
      setSubmitError(err instanceof ApiError ? err.message : 'Could not approve. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRequestRevision = async () => {
    if (!feedback.trim()) {
      setFeedbackError('Please provide feedback so the creator knows what to change');
      return;
    }
    if (!deliverableId) {
      setSubmitError('Missing deliverable reference — cannot request revision.');
      return;
    }
    setFeedbackError('');
    setSubmitError('');
    setIsSubmitting(true);
    try {
      await deliverablesApi.requestRevision(deliverableId, feedback.trim());
      onRequestRevision?.(event.id, feedback.trim());
      onReviewSuccess?.({ deliverableId, status: 'revision_requested', feedback: feedback.trim() });
      onOpenChange(false);
    } catch (err) {
      setSubmitError(err instanceof ApiError ? err.message : 'Could not request revision. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleUpload = async () => {
    if (!selectedFile) return;
    setUploading(true);
    for (let i = 0; i <= 100; i += 10) {
      await new Promise((resolve) => setTimeout(resolve, 150));
      setUploadProgress(i);
    }
    onUploadRevision?.(event.id, selectedFile);
    setUploading(false);
    setUploadProgress(0);
    setSelectedFile(null);
    onOpenChange(false);
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files?.[0]) {
      setSelectedFile(e.target.files[0]);
    }
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:w-[700px] overflow-y-auto">
        <SheetHeader className="mb-6">
          <SheetTitle className="flex items-center gap-2">
            Reel #{meta?.deliverableNumber || 1} — {meta?.platform?.toUpperCase() || 'INSTAGRAM'}
          </SheetTitle>
        </SheetHeader>

        <div className="space-y-6">
          {/* Video Player */}
          <div className="bg-muted aspect-video rounded-lg flex items-center justify-center relative overflow-hidden">
            {meta?.submittedUrl ? (
              <video
                src={meta.submittedUrl}
                controls
                className="w-full h-full rounded-lg object-contain"
              />
            ) : (
              <div className="absolute inset-0 bg-gradient-to-br from-muted to-muted-foreground/20 flex flex-col items-center justify-center gap-2">
                <Play className="h-16 w-16 text-muted-foreground opacity-50" />
                <p className="text-xs text-muted-foreground">Video preview not available</p>
              </div>
            )}
          </div>

          {/* Status */}
          <div className="flex items-center justify-between">
            <h3 className="font-semibold">Status</h3>
            <Badge
              className={
                status === 'approved'
                  ? 'bg-success text-white'
                  : status === 'revision_requested'
                    ? 'bg-orange-100 text-orange-900'
                    : 'bg-blue-100 text-blue-900'
              }
            >
              {status.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())}
            </Badge>
          </div>

          <Separator />

          {/* File Info */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Deliverable Info</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div>
                <p className="text-xs text-muted-foreground">File</p>
                <div className="flex items-center gap-2 mt-1">
                  <FileIcon className="h-4 w-4 text-muted-foreground" />
                  <span className="text-sm">{meta?.submittedFilename || 'reel-1.mp4'}</span>
                </div>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Revisions Used</p>
                <p className="text-sm font-semibold mt-1">
                  {meta?.revisionCount || 0}/{meta?.revisionLimit || 2}
                </p>
              </div>
              {meta?.revisionFeedback && (
                <div>
                  <p className="text-xs text-muted-foreground">Previous Feedback</p>
                  <div className="bg-orange-50/50 border border-orange-200/50 rounded p-2 mt-1">
                    <p className="text-sm text-muted-foreground">{meta.revisionFeedback}</p>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Brand: Feedback & Actions */}
          {currentUserType === 'brand' && (status === 'submitted' || status === 'under_review') && (
            <>
              <Separator />
              <div className="space-y-3">
                <Label htmlFor="feedback" className="text-sm font-semibold">
                  Feedback{' '}
                  <span className="text-muted-foreground font-normal">(required for revision request, optional for approval)</span>
                </Label>
                <Textarea
                  id="feedback"
                  placeholder="E.g., Colour grade looks off, please adjust saturation..."
                  value={feedback}
                  onChange={(e) => {
                    setFeedback(e.target.value);
                    if (feedbackError) setFeedbackError('');
                  }}
                  className={`resize-none ${feedbackError ? 'border-red-500' : ''}`}
                  rows={4}
                />
                {feedbackError && (
                  <div className="flex items-center gap-1.5 text-red-500 text-xs">
                    <AlertCircle className="h-3.5 w-3.5" />
                    {feedbackError}
                  </div>
                )}
              </div>
            </>
          )}

          {/* Brand: after revision was requested */}
          {currentUserType === 'brand' &&
            status === 'revision_requested' &&
            (meta?.revisionCount ?? 0) < (meta?.revisionLimit ?? 2) && (
            <>
              <Separator />
              <div className="space-y-3">
                <Label htmlFor="feedback-rev" className="text-sm font-semibold">
                  Additional Feedback (required)
                </Label>
                <Textarea
                  id="feedback-rev"
                  placeholder="What still needs to be changed?"
                  value={feedback}
                  onChange={(e) => {
                    setFeedback(e.target.value);
                    if (feedbackError) setFeedbackError('');
                  }}
                  className={`resize-none ${feedbackError ? 'border-red-500' : ''}`}
                  rows={4}
                />
                {feedbackError && (
                  <div className="flex items-center gap-1.5 text-red-500 text-xs">
                    <AlertCircle className="h-3.5 w-3.5" />
                    {feedbackError}
                  </div>
                )}
              </div>
            </>
          )}

          {/* Creator: Upload Revision */}
          {currentUserType === 'creator' &&
            status === 'revision_requested' &&
            (meta?.revisionCount ?? 0) < (meta?.revisionLimit ?? 2) && (
            <>
              <Separator />
              <div className="space-y-3">
                <Label className="text-sm font-semibold">
                  Upload Revised Version <span className="text-red-400">*</span>
                </Label>
                {!selectedFile ? (
                  <div className="border-2 border-dashed border-border rounded-lg p-6 text-center cursor-pointer hover:border-primary/50 transition-colors">
                    <input
                      type="file"
                      onChange={handleFileSelect}
                      accept="video/*,image/*"
                      className="hidden"
                      id="file-input"
                    />
                    <label htmlFor="file-input" className="cursor-pointer block">
                      <Upload className="h-8 w-8 text-muted-foreground mx-auto mb-2" />
                      <p className="text-sm font-medium">Click to upload or drag and drop</p>
                      <p className="text-xs text-muted-foreground">MP4, MOV, JPG — up to 500 MB</p>
                    </label>
                  </div>
                ) : (
                  <div className="flex items-center gap-3 p-3 bg-muted/50 rounded-lg">
                    <FileIcon className="h-5 w-5 text-primary" />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{selectedFile.name}</p>
                      <p className="text-xs text-muted-foreground">
                        {(selectedFile.size / 1024 / 1024).toFixed(1)} MB
                      </p>
                    </div>
                    <Button variant="ghost" size="sm" onClick={() => setSelectedFile(null)}>
                      <X className="h-4 w-4" />
                    </Button>
                  </div>
                )}
                {uploading && (
                  <div className="space-y-2">
                    <div className="flex justify-between items-center text-xs">
                      <span>Uploading...</span>
                      <span>{uploadProgress}%</span>
                    </div>
                    <Progress value={uploadProgress} className="h-2" />
                  </div>
                )}
              </div>
            </>
          )}

          <Separator />

          {submitError && (
            <div className="flex items-center gap-1.5 text-red-500 text-xs">
              <AlertCircle className="h-3.5 w-3.5" />
              {submitError}
            </div>
          )}

          {/* Action Buttons */}
          <div className="flex gap-2">
            {currentUserType === 'brand' && (status === 'submitted' || status === 'under_review') && (
              <>
                <Button
                  className="flex-1"
                  onClick={handleApprove}
                  disabled={isSubmitting}
                >
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  {isSubmitting ? 'Approving...' : 'Approve'}
                </Button>
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={handleRequestRevision}
                  disabled={isSubmitting}
                >
                  <MessageSquare className="h-4 w-4 mr-2" />
                  {isSubmitting ? 'Sending...' : 'Request Revision'}
                </Button>
              </>
            )}

            {currentUserType === 'brand' &&
              status === 'revision_requested' &&
              (meta?.revisionCount ?? 0) < (meta?.revisionLimit ?? 2) && (
              <>
                <Button
                  className="flex-1"
                  onClick={handleApprove}
                  disabled={isSubmitting}
                >
                  {isSubmitting ? 'Approving...' : 'Approve Revised'}
                </Button>
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={handleRequestRevision}
                  disabled={isSubmitting}
                >
                  {isSubmitting ? 'Sending...' : 'Request Another Revision'}
                </Button>
              </>
            )}

            {currentUserType === 'creator' &&
              status === 'revision_requested' &&
              (meta?.revisionCount ?? 0) < (meta?.revisionLimit ?? 2) && (
              <Button
                className="w-full"
                onClick={handleUpload}
                disabled={!selectedFile || uploading}
              >
                {uploading ? 'Uploading...' : 'Upload Revision'}
              </Button>
            )}

            {status === 'approved' && (
              meta?.submittedUrl ? (
                // F-0289: `submittedUrl` is the real R2 link to the approved file — the same
                // URL the video player above renders from. Opening it is the same pattern
                // deal-contract-tab.tsx uses for its own PDF download (window.open on a real
                // presigned URL rather than fabricating a client-side file).
                <Button
                  variant="outline"
                  className="w-full"
                  onClick={() => window.open(meta.submittedUrl, '_blank', 'noopener,noreferrer')}
                >
                  <Download className="h-4 w-4 mr-2" />
                  Download Approved Version
                </Button>
              ) : (
                // No file URL on this event — there is nothing to download. Disabled with a
                // stated reason instead of a button that opens nothing (mirrors the Export /
                // Form 16A pattern in src/pages/brand-wallet.tsx).
                <TooltipProvider>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <span tabIndex={0} className="inline-flex w-full">
                        <Button variant="outline" className="w-full" disabled aria-disabled="true">
                          <Download className="h-4 w-4 mr-2" />
                          Download Approved Version
                        </Button>
                      </span>
                    </TooltipTrigger>
                    <TooltipContent>No file is attached to this deliverable yet.</TooltipContent>
                  </Tooltip>
                </TooltipProvider>
              )
            )}
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}
