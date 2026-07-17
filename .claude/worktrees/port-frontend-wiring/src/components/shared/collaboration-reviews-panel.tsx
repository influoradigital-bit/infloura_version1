import * as React from 'react';
import { AlertTriangle, CheckCircle2, Loader2, MessageSquareQuote, Star } from 'lucide-react';

import { ReviewCard } from '@/components/shared/review-card';
import { StarRatingInput } from '@/components/shared/star-rating-input';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import {
  api,
  ApiError,
  isApiLive,
  type Deal,
  type ReviewDisplayRecord,
  type Role,
} from '@/lib/api';

const MAX_REVIEW_TEXT = 1000;

interface RateableDeal {
  id: string;
  counterpartyName: string;
  campaignName: string;
}

interface CollaborationReviewsPanelProps {
  role: Role;
  counterpartyLabel: string;
  pageTitle: string;
  pageDescription: string;
}

async function loadRateableDeals(role: Role): Promise<RateableDeal[]> {
  if (!isApiLive()) {
    return [
      {
        id: 'deal-done-1',
        counterpartyName: role === 'creator' ? 'Nykaa Fashion' : 'Priya Sharma',
        campaignName: 'Winter Collection',
      },
    ];
  }

  const rows = await api.deals.list(role, 'completed');
  return rows
    .filter((deal) => deal.status === 'COMPLETED')
    .map((deal: Deal) => ({
      id: deal.id,
      counterpartyName: deal.counterpartyName,
      campaignName: deal.campaignName,
    }));
}

export function CollaborationReviewsPanel({
  role,
  counterpartyLabel,
  pageTitle,
  pageDescription,
}: CollaborationReviewsPanelProps) {
  const [activeTab, setActiveTab] = React.useState('rate');
  const [rateableDeals, setRateableDeals] = React.useState<RateableDeal[]>([]);
  const [receivedReviews, setReceivedReviews] = React.useState<ReviewDisplayRecord[]>([]);
  const [reviewedIds, setReviewedIds] = React.useState<Set<string>>(new Set());
  const [loadingDeals, setLoadingDeals] = React.useState(true);
  const [loadingReceived, setLoadingReceived] = React.useState(true);
  const [dealsError, setDealsError] = React.useState<string | null>(null);
  const [receivedError, setReceivedError] = React.useState<string | null>(null);
  const [receivedNotImplemented, setReceivedNotImplemented] = React.useState(false);
  const [submittingId, setSubmittingId] = React.useState<string | null>(null);
  const [submitSuccessId, setSubmitSuccessId] = React.useState<string | null>(null);
  const [submitError, setSubmitError] = React.useState<string | null>(null);
  const [drafts, setDrafts] = React.useState<
    Record<string, { stars: number; text: string }>
  >({});

  const reviewsClient = role === 'creator' ? api.creatorReviews : api.brandReviews;

  const refreshDeals = React.useCallback(async () => {
    setLoadingDeals(true);
    setDealsError(null);
    try {
      const deals = await loadRateableDeals(role);
      setRateableDeals(deals);
    } catch (err) {
      setRateableDeals([]);
      setDealsError(err instanceof ApiError ? err.message : 'Could not load completed deals.');
    } finally {
      setLoadingDeals(false);
    }
  }, [role]);

  const refreshReceived = React.useCallback(async () => {
    setLoadingReceived(true);
    setReceivedError(null);
    setReceivedNotImplemented(false);
    try {
      const rows = await reviewsClient.listReceived();
      setReceivedReviews(rows);
    } catch (err) {
      setReceivedReviews([]);
      if (err instanceof ApiError && err.code === 'NOT_IMPLEMENTED') {
        setReceivedNotImplemented(true);
      } else {
        setReceivedError(
          err instanceof ApiError ? err.message : 'Could not load reviews about you.',
        );
      }
    } finally {
      setLoadingReceived(false);
    }
  }, [reviewsClient]);

  React.useEffect(() => {
    void refreshDeals();
    void refreshReceived();
  }, [refreshDeals, refreshReceived]);

  // Keep the just-submitted deal's card mounted so its success confirmation renders;
  // other already-reviewed deals drop out of the pending list.
  const pendingDeals = rateableDeals.filter(
    (deal) => !reviewedIds.has(deal.id) || submitSuccessId === deal.id,
  );

  const updateDraft = (dealId: string, patch: Partial<{ stars: number; text: string }>) => {
    setDrafts((prev) => ({
      ...prev,
      [dealId]: {
        stars: patch.stars ?? prev[dealId]?.stars ?? 0,
        text: patch.text ?? prev[dealId]?.text ?? '',
      },
    }));
  };

  const handleSubmit = async (deal: RateableDeal) => {
    const draft = drafts[deal.id] ?? { stars: 0, text: '' };
    if (draft.stars < 1) {
      setSubmitError('Please select a star rating before submitting.');
      return;
    }

    setSubmittingId(deal.id);
    setSubmitError(null);
    setSubmitSuccessId(null);

    try {
      await reviewsClient.create({
        collaborationId: deal.id,
        stars: draft.stars,
        text: draft.text.trim() || undefined,
      });
      setReviewedIds((prev) => new Set(prev).add(deal.id));
      setSubmitSuccessId(deal.id);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'ALREADY_REVIEWED') {
        setReviewedIds((prev) => new Set(prev).add(deal.id));
        setSubmitError('You have already reviewed this collaboration.');
      } else if (err instanceof ApiError && err.code === 'COLLABORATION_NOT_COMPLETED') {
        setSubmitError('Reviews are only allowed after the collaboration is completed.');
      } else {
        setSubmitError(err instanceof ApiError ? err.message : 'Could not submit review.');
      }
    } finally {
      setSubmittingId(null);
    }
  };

  return (
    <div className="container mx-auto max-w-3xl px-4 py-6">
      <div className="mb-6">
        <h1 className="text-2xl font-bold">{pageTitle}</h1>
        <p className="text-muted-foreground">{pageDescription}</p>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList className="mb-5 grid w-full grid-cols-2">
          <TabsTrigger value="rate">Rate {counterpartyLabel}</TabsTrigger>
          <TabsTrigger value="received">Reviews about you</TabsTrigger>
        </TabsList>

        <TabsContent value="rate" className="space-y-4">
          {dealsError && (
            <Alert variant="destructive">
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>Could not load completed deals</AlertTitle>
              <AlertDescription>
                {dealsError}
                <div className="mt-2">
                  <Button size="sm" variant="outline" onClick={() => void refreshDeals()}>
                    Try again
                  </Button>
                </div>
              </AlertDescription>
            </Alert>
          )}

          {submitError && (
            <Alert variant="destructive">
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>Review not submitted</AlertTitle>
              <AlertDescription>{submitError}</AlertDescription>
            </Alert>
          )}

          {loadingDeals ? (
            <div className="flex items-center justify-center py-16">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : pendingDeals.length === 0 && !dealsError ? (
            <EmptyRateState counterpartyLabel={counterpartyLabel} />
          ) : (
            pendingDeals.map((deal) => {
              const draft = drafts[deal.id] ?? { stars: 0, text: '' };
              const isSubmitting = submittingId === deal.id;
              const justSubmitted = submitSuccessId === deal.id;

              return (
                <Card key={deal.id}>
                  <CardHeader className="pb-3">
                    <CardTitle className="text-base">{deal.counterpartyName}</CardTitle>
                    <CardDescription>{deal.campaignName}</CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {justSubmitted ? (
                      <div className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
                        <CheckCircle2 className="h-4 w-4 shrink-0" />
                        Thanks — your review was submitted.
                      </div>
                    ) : (
                      <>
                        <div className="space-y-2">
                          <Label>Your rating</Label>
                          <StarRatingInput
                            value={draft.stars}
                            onChange={(stars) => updateDraft(deal.id, { stars })}
                            disabled={isSubmitting}
                            label={`Rate ${deal.counterpartyName}`}
                          />
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor={`review-text-${deal.id}`}>
                            Comments <span className="text-muted-foreground">(optional)</span>
                          </Label>
                          <Textarea
                            id={`review-text-${deal.id}`}
                            value={draft.text}
                            disabled={isSubmitting}
                            maxLength={MAX_REVIEW_TEXT}
                            rows={4}
                            placeholder={`Share your experience working with ${deal.counterpartyName}…`}
                            onChange={(e) => updateDraft(deal.id, { text: e.target.value })}
                          />
                          <p className="text-xs text-muted-foreground text-right">
                            {draft.text.length}/{MAX_REVIEW_TEXT}
                          </p>
                        </div>

                        <Button
                          disabled={isSubmitting || draft.stars < 1}
                          onClick={() => void handleSubmit(deal)}
                        >
                          {isSubmitting ? (
                            <>
                              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                              Submitting…
                            </>
                          ) : (
                            'Submit review'
                          )}
                        </Button>
                      </>
                    )}
                  </CardContent>
                </Card>
              );
            })
          )}
        </TabsContent>

        <TabsContent value="received" className="space-y-4">
          {receivedNotImplemented && (
            <Alert className="border-amber-300 bg-amber-50">
              <AlertTriangle className="h-4 w-4 text-amber-700" />
              <AlertTitle className="text-amber-900">Read API not yet available</AlertTitle>
              <AlertDescription className="text-amber-800">
                Listing reviews left about you requires{' '}
                <code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-xs">
                  GET /{role}/reviews/received
                </code>
                {role === 'creator'
                  ? ', which is not reachable from this environment.'
                  : ' — not built yet for brands. This tab shows illustrative data in demo mode only.'}
              </AlertDescription>
            </Alert>
          )}

          {receivedError && (
            <Alert variant="destructive">
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>Could not load reviews</AlertTitle>
              <AlertDescription>
                {receivedError}
                <div className="mt-2">
                  <Button size="sm" variant="outline" onClick={() => void refreshReceived()}>
                    Try again
                  </Button>
                </div>
              </AlertDescription>
            </Alert>
          )}

          {loadingReceived ? (
            <div className="flex items-center justify-center py-16">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : receivedReviews.length === 0 && !receivedNotImplemented && !receivedError ? (
            <EmptyReceivedState />
          ) : (
            <div className="space-y-3">
              {receivedReviews.map((review) => (
                <ReviewCard key={review.id} review={review} />
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

function EmptyRateState({ counterpartyLabel }: { counterpartyLabel: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <Star className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No collaborations to review</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">
        Completed collaborations you have not reviewed yet will appear here so you can rate your{' '}
        {counterpartyLabel.toLowerCase()}.
      </p>
    </div>
  );
}

function EmptyReceivedState() {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <MessageSquareQuote className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No reviews yet</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">
        When collaborators leave feedback after a completed deal, it will show up here.
      </p>
    </div>
  );
}
