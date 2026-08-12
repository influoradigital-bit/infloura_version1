import * as React from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft,
  Calendar,
  CheckCircle2,
  Hash,
  IndianRupee,
  Loader2,
  Megaphone,
  Users,
} from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { FadeUp } from '@/components/motion/FadeUp';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import type { CreatorCampaignDetail } from '@/lib/api';
import { api, ApiError } from '@/lib/api';
import { formatINR } from '@/lib/utils';
import { useToast } from '@/hooks/use-toast';
import { getApplicationStatusLabel } from '@/lib/application-status';

function formatDate(value: string | null): string {
  if (!value) return 'TBD';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function daysUntilDeadline(deadline: string | null): number | null {
  if (!deadline) return null;
  const end = new Date(deadline);
  if (Number.isNaN(end.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  end.setHours(0, 0, 0, 0);
  return Math.ceil((end.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
}

export default function CreatorCampaignDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { toast } = useToast();

  const [campaign, setCampaign] = React.useState<CreatorCampaignDetail | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);

  const [applyOpen, setApplyOpen] = React.useState(false);
  const [applyMessage, setApplyMessage] = React.useState('');
  const [applying, setApplying] = React.useState(false);
  const [applySuccess, setApplySuccess] = React.useState(false);

  const loadCampaign = React.useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const data = await api.creatorCampaigns.get(id);
      if (!data) {
        setError('Campaign not found.');
        setCampaign(null);
      } else {
        setCampaign(data);
      }
    } catch (e) {
      const message =
        e instanceof ApiError ? e.message : 'Could not load campaign details.';
      setError(message);
      setCampaign(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  React.useEffect(() => {
    void loadCampaign();
  }, [loadCampaign]);

  React.useEffect(() => {
    if (searchParams.get('apply') === '1' && campaign && !campaign.applicationStatus) {
      setApplyOpen(true);
    }
  }, [searchParams, campaign]);

  const handleApply = async () => {
    if (!id || !campaign) return;
    setApplying(true);
    try {
      await api.creatorCampaigns.apply(id, {
        message: applyMessage.trim() || undefined,
      });
      setApplySuccess(true);
      setCampaign((prev) => (prev ? { ...prev, applicationStatus: 'APPLIED' } : prev));
      toast({
        title: 'Application submitted',
        description: 'The brand will review your application and get back to you.',
      });
    } catch (e) {
      const message =
        e instanceof ApiError ? e.message : 'Could not submit application. Try again.';
      toast({ title: 'Application failed', description: message, variant: 'destructive' });
    } finally {
      setApplying(false);
    }
  };

  const closeApplyDialog = () => {
    setApplyOpen(false);
    if (applySuccess) {
      navigate(`/creator/campaigns/${id}`, { replace: true });
    }
  };

  if (loading) {
    return (
      <CreatorLayout>
        <div className="container mx-auto max-w-4xl px-4 py-6 space-y-6">
          <Skeleton className="h-8 w-48" />
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      </CreatorLayout>
    );
  }

  if (error || !campaign) {
    return (
      <CreatorLayout>
        <div className="container mx-auto max-w-4xl px-4 py-6">
          <Button variant="ghost" size="sm" className="mb-4 gap-2" asChild>
            <Link to="/creator/campaigns">
              <ArrowLeft className="h-4 w-4" />
              Back to campaigns
            </Link>
          </Button>
          <Alert variant="destructive">
            <AlertTitle>{error ?? 'Campaign not found'}</AlertTitle>
            <AlertDescription>
              <Button size="sm" variant="outline" className="mt-2" onClick={() => void loadCampaign()}>
                Try again
              </Button>
            </AlertDescription>
          </Alert>
        </div>
      </CreatorLayout>
    );
  }

  const brandName = campaign.brand?.name ?? 'Brand';
  const brandInitial = brandName.charAt(0).toUpperCase();
  const daysLeft = daysUntilDeadline(campaign.applicationDeadline);
  const hasApplied = Boolean(campaign.applicationStatus);
  const deadlinePassed = daysLeft != null && daysLeft < 0;

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-4xl px-4 py-6">
        <FadeUp>
          <Button variant="ghost" size="sm" className="mb-4 gap-2" asChild>
            <Link to="/creator/campaigns">
              <ArrowLeft className="h-4 w-4" />
              Back to campaigns
            </Link>
          </Button>
        </FadeUp>

        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-2">
            <FadeUp delay={0.05}>
              <div className="flex items-start gap-4">
                <Avatar className="h-16 w-16 rounded-lg">
                  {campaign.brand?.logoUrl ? (
                    <AvatarImage src={campaign.brand.logoUrl} alt={brandName} />
                  ) : null}
                  <AvatarFallback className="rounded-lg text-xl">{brandInitial}</AvatarFallback>
                </Avatar>
                <div className="min-w-0 flex-1">
                  <h1 className="text-2xl font-bold">{campaign.title}</h1>
                  <p className="text-muted-foreground">{brandName}</p>
                </div>
                {daysLeft != null && (
                  <Badge variant={daysLeft <= 3 ? 'destructive' : 'secondary'} className="shrink-0">
                    {deadlinePassed ? 'Deadline passed' : `${daysLeft} days left`}
                  </Badge>
                )}
              </div>
            </FadeUp>

            <FadeUp delay={0.1}>
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">About this campaign</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="whitespace-pre-wrap text-sm leading-relaxed">{campaign.description}</p>
                </CardContent>
              </Card>
            </FadeUp>

            {campaign.objectives.length > 0 && (
              <FadeUp delay={0.12}>
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Objectives</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <ul className="list-disc space-y-1 pl-5 text-sm">
                      {campaign.objectives.map((obj) => (
                        <li key={obj}>{obj}</li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              </FadeUp>
            )}

            {campaign.requirements.length > 0 && (
              <FadeUp delay={0.14}>
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Requirements</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <ul className="space-y-2 text-sm">
                      {campaign.requirements.map((req) => (
                        <li key={req} className="flex items-start gap-2">
                          <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success-foreground" />
                          {req}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              </FadeUp>
            )}

            {campaign.brandGuidelines && (
              <FadeUp delay={0.16}>
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Brand guidelines</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <p className="whitespace-pre-wrap text-sm text-muted-foreground">
                      {campaign.brandGuidelines}
                    </p>
                  </CardContent>
                </Card>
              </FadeUp>
            )}

            {campaign.hashtags.length > 0 && (
              <FadeUp delay={0.18}>
                <div className="flex flex-wrap items-center gap-2">
                  <Hash className="h-4 w-4 text-muted-foreground" />
                  {campaign.hashtags.map((tag) => (
                    <Badge key={tag} variant="outline">
                      {tag}
                    </Badge>
                  ))}
                </div>
              </FadeUp>
            )}
          </div>

          <div className="space-y-4">
            <FadeUp delay={0.08}>
              <Card className="sticky top-4">
                <CardContent className="space-y-4 p-5">
                  {campaign.budget && (
                    <div>
                      <p className="text-sm text-muted-foreground">Budget</p>
                      <p className="text-xl font-bold">
                        {formatINR(campaign.budget.min)} – {formatINR(campaign.budget.max)}
                      </p>
                    </div>
                  )}

                  <Separator />

                  <div className="space-y-2 text-sm">
                    {campaign.platforms.length > 0 && (
                      <div className="flex flex-wrap gap-1">
                        {campaign.platforms.map((p) => (
                          <Badge key={p} variant="outline">
                            {p.charAt(0) + p.slice(1).toLowerCase()}
                          </Badge>
                        ))}
                      </div>
                    )}
                    {campaign.maxCollaborators != null && (
                      <div className="flex items-center justify-between">
                        <span className="flex items-center gap-1 text-muted-foreground">
                          <Users className="h-4 w-4" />
                          Creators needed
                        </span>
                        <span>{campaign.maxCollaborators}</span>
                      </div>
                    )}
                    {campaign.applicationDeadline && (
                      <div className="flex items-center justify-between">
                        <span className="flex items-center gap-1 text-muted-foreground">
                          <Calendar className="h-4 w-4" />
                          Apply by
                        </span>
                        <span>{formatDate(campaign.applicationDeadline)}</span>
                      </div>
                    )}
                    {campaign.startDate && campaign.endDate && (
                      <div className="flex items-center justify-between">
                        <span className="text-muted-foreground">Campaign period</span>
                        <span className="text-right text-xs">
                          {formatDate(campaign.startDate)} – {formatDate(campaign.endDate)}
                        </span>
                      </div>
                    )}
                  </div>

                  <Separator />

                  {hasApplied ? (
                    <div className="rounded-lg border border-border bg-muted/50 p-4 text-center">
                      <CheckCircle2 className="mx-auto mb-2 h-8 w-8 text-success-foreground" />
                      <p className="font-medium">
                        {/* CR-55/F-0105: this used to keep its own drifted copy of the status
                            label map and fall back to the raw backend enum string when a
                            status wasn't in it — so a cancelled application literally
                            rendered "CANCELLED", the exact thing the platform-wide
                            never-show-Rejected/Cancelled rule (src/lib/application-status.ts)
                            exists to prevent. Uses the canonical, always-labeled map now. */}
                        {getApplicationStatusLabel(campaign.applicationStatus!)}
                      </p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        You&apos;ve already applied to this campaign.
                      </p>
                    </div>
                  ) : (
                    <Button
                      className="w-full"
                      size="lg"
                      disabled={deadlinePassed}
                      onClick={() => setApplyOpen(true)}
                    >
                      <Megaphone className="mr-2 h-4 w-4" />
                      {deadlinePassed ? 'Applications closed' : 'Apply now'}
                    </Button>
                  )}
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </div>

        <Dialog open={applyOpen} onOpenChange={(open) => !open && closeApplyDialog()}>
          <DialogContent className="sm:max-w-md">
            {applySuccess ? (
              <>
                <DialogHeader>
                  <DialogTitle>Application submitted</DialogTitle>
                  <DialogDescription>
                    Your application to {campaign.title} has been sent to {brandName}.
                  </DialogDescription>
                </DialogHeader>
                <div className="flex flex-col items-center py-6 text-center">
                  <CheckCircle2 className="mb-3 h-12 w-12 text-success-foreground" />
                  <p className="text-sm text-muted-foreground">
                    You can track this application under My Applications, and it will also
                    appear in your Deals inbox once the brand responds.
                  </p>
                </div>
                <DialogFooter className="flex-col gap-2 sm:flex-col">
                  <Button className="w-full" onClick={closeApplyDialog}>
                    Done
                  </Button>
                  <Button variant="outline" className="w-full" asChild>
                    <Link to="/creator/applications">View my applications</Link>
                  </Button>
                  <Button variant="outline" className="w-full" asChild>
                    <Link to="/creator/deals">Go to deals</Link>
                  </Button>
                </DialogFooter>
              </>
            ) : (
              <>
                <DialogHeader>
                  <DialogTitle>Apply to {campaign.title}</DialogTitle>
                  <DialogDescription>
                    Tell {brandName} why you&apos;re a great fit. A short note helps your application stand out.
                  </DialogDescription>
                </DialogHeader>

                <div className="space-y-2 py-2">
                  <Label htmlFor="apply-message">Message (optional)</Label>
                  <Textarea
                    id="apply-message"
                    placeholder="Share why you're excited about this campaign and what you'd bring..."
                    value={applyMessage}
                    onChange={(e) => setApplyMessage(e.target.value)}
                    rows={5}
                    maxLength={2000}
                  />
                  <p className="text-xs text-muted-foreground">{applyMessage.length}/2000 characters</p>
                </div>

                {campaign.budget && (
                  <div className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-sm">
                    <IndianRupee className="h-4 w-4 text-muted-foreground" />
                    <span>
                      Budget: {formatINR(campaign.budget.min)} – {formatINR(campaign.budget.max)}
                    </span>
                  </div>
                )}

                <DialogFooter>
                  <Button variant="outline" onClick={closeApplyDialog} disabled={applying}>
                    Cancel
                  </Button>
                  <Button onClick={() => void handleApply()} disabled={applying}>
                    {applying ? (
                      <>
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        Submitting...
                      </>
                    ) : (
                      'Submit application'
                    )}
                  </Button>
                </DialogFooter>
              </>
            )}
          </DialogContent>
        </Dialog>
      </div>
    </CreatorLayout>
  );
}
