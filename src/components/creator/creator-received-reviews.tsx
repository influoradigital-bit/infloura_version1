'use client';

/**
 * CreatorReceivedReviews — the reviews a creator has received, with a Flag action.
 * ----------------------------------------------------------------------------
 * creator-missing-0804: builds the previously-missing creator received-reviews surface
 * (GET /creator/reviews/received) and wires the previously-orphan flag route
 * (POST /creator/reviews/:id/flag) so a creator can report an unfair/inaccurate review
 * for moderator attention.
 */

import * as React from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Flag, Loader2, Star } from 'lucide-react';

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { ApiError, creatorReviews, type ReviewDisplayRecord } from '@/lib/api';

const MAX_REASON = 255;

function Stars({ count }: { count: number }) {
  return (
    <span className="inline-flex" aria-label={`${count} out of 5 stars`}>
      {Array.from({ length: 5 }).map((_, i) => (
        <Star
          key={i}
          className={i < count ? 'h-4 w-4 fill-amber-400 text-amber-400' : 'h-4 w-4 text-muted-foreground/40'}
          aria-hidden="true"
        />
      ))}
    </span>
  );
}

export function CreatorReceivedReviews() {
  const { toast } = useToast();
  const [reviews, setReviews] = React.useState<ReviewDisplayRecord[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [flagged, setFlagged] = React.useState<Record<string, boolean>>({});

  const [flagTarget, setFlagTarget] = React.useState<ReviewDisplayRecord | null>(null);
  const [reason, setReason] = React.useState('');
  const [submitting, setSubmitting] = React.useState(false);

  const load = React.useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setReviews(await creatorReviews.listReceived());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load your reviews');
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void load();
  }, [load]);

  const submitFlag = async () => {
    if (!flagTarget || reason.trim() === '') return;
    setSubmitting(true);
    try {
      await creatorReviews.flag(flagTarget.id, reason.trim());
      setFlagged((prev) => ({ ...prev, [flagTarget.id]: true }));
      toast({ title: 'Review flagged', description: 'A moderator will review it shortly.' });
      setFlagTarget(null);
      setReason('');
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Could not flag review',
        description: err instanceof ApiError ? err.message : 'Please try again.',
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base">Reviews about you</CardTitle>
          {/* CR-79: cross-link to the other "reviews about you" surface (Reviews page) */}
          <Link
            to="/creator/reviews"
            className="shrink-0 text-xs font-medium text-muted-foreground underline underline-offset-2 hover:text-foreground"
          >
            View &amp; manage all reviews
          </Link>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading && <Skeleton className="h-20 w-full" />}

        {error && !loading && (
          <Alert variant="destructive">
            <AlertTriangle className="h-4 w-4" aria-hidden="true" />
            <AlertTitle>Couldn&apos;t load reviews</AlertTitle>
            <AlertDescription>
              {error}
              <div className="mt-2">
                <Button type="button" size="sm" variant="outline" onClick={() => void load()}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {!loading && !error && reviews.length === 0 && (
          <p className="py-6 text-center text-sm text-muted-foreground">
            No reviews yet — they’ll appear here once brands review your completed collaborations.
          </p>
        )}

        {!loading &&
          !error &&
          reviews.map((r) => (
            <div key={r.id} className="rounded-lg border p-3">
              <div className="flex items-start justify-between gap-3">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <Stars count={r.stars} />
                    {r.reviewerName && <span className="text-sm font-medium">{r.reviewerName}</span>}
                    {r.campaignName && (
                      <Badge variant="outline" className="text-xs">
                        {r.campaignName}
                      </Badge>
                    )}
                  </div>
                  {r.text && <p className="text-sm text-muted-foreground">{r.text}</p>}
                </div>
                {flagged[r.id] ? (
                  <Badge variant="outline" className="shrink-0 gap-1 text-amber-700">
                    <Flag className="h-3 w-3" aria-hidden="true" />
                    Flagged
                  </Badge>
                ) : (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="shrink-0 gap-1.5 text-muted-foreground"
                    onClick={() => {
                      setFlagTarget(r);
                      setReason('');
                    }}
                  >
                    <Flag className="h-3.5 w-3.5" aria-hidden="true" />
                    Flag
                  </Button>
                )}
              </div>
            </div>
          ))}
      </CardContent>

      <Dialog open={flagTarget !== null} onOpenChange={(open) => !open && setFlagTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Flag this review</DialogTitle>
            <DialogDescription>
              Tell us why this review is inappropriate or inaccurate. A moderator will look into it.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="flag-reason">Reason</Label>
            <Textarea
              id="flag-reason"
              rows={4}
              maxLength={MAX_REASON}
              placeholder="e.g. This review is about a collaboration that never happened."
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
            <p className="text-right text-xs text-muted-foreground">
              {reason.length}/{MAX_REASON}
            </p>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setFlagTarget(null)} disabled={submitting}>
              Cancel
            </Button>
            <Button type="button" onClick={submitFlag} disabled={submitting || reason.trim() === ''}>
              {submitting && <Loader2 className="animate-spin" aria-hidden="true" />}
              Submit flag
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

export default CreatorReceivedReviews;
