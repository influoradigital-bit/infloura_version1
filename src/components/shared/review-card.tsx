import { Flag, Star } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import type { ReviewDisplayRecord } from '@/lib/api';
import { cn } from '@/lib/utils';

interface ReviewCardProps {
  review: ReviewDisplayRecord;
  className?: string;
  /** When provided, renders a "Flag" affordance (brand moderation — F: review flag). */
  onFlag?: () => void;
  /** True once this review has been flagged, to disable the control and show state. */
  flagged?: boolean;
}

function formatReviewDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

export function ReviewCard({ review, className, onFlag, flagged }: ReviewCardProps) {
  const title =
    review.reviewerName ??
    (review.reviewerType === 'BRAND' ? 'Brand' : 'Creator');

  return (
    <Card className={cn('border-border/80', className)}>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="font-medium truncate">{title}</p>
            {review.campaignName && (
              <p className="text-sm text-muted-foreground truncate">{review.campaignName}</p>
            )}
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <time className="text-xs text-muted-foreground" dateTime={review.createdAt}>
              {formatReviewDate(review.createdAt)}
            </time>
            {onFlag && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-7 gap-1 px-2 text-xs text-muted-foreground hover:text-destructive-foreground"
                onClick={onFlag}
                disabled={flagged}
                aria-label={flagged ? 'Review flagged' : 'Flag this review'}
              >
                <Flag className={cn('h-3.5 w-3.5', flagged && 'fill-destructive-foreground')} />
                {flagged ? 'Flagged' : 'Flag'}
              </Button>
            )}
          </div>
        </div>

        <div className="mt-2 flex items-center gap-0.5" aria-label={`${review.stars} out of 5 stars`}>
          {[1, 2, 3, 4, 5].map((star) => (
            <Star
              key={star}
              className={cn(
                'h-4 w-4',
                star <= review.stars
                  ? 'fill-amber-400 text-amber-400'
                  : 'text-muted-foreground/30',
              )}
            />
          ))}
        </div>

        {review.text && (
          <p className="mt-3 text-sm text-foreground/90 leading-relaxed">{review.text}</p>
        )}
      </CardContent>
    </Card>
  );
}
