'use client';

/**
 * DeliverableLifecyclePanel — verified-FIRST post-approval creator actions (verified-analytics-0804).
 * ----------------------------------------------------------------------------
 * Meta-verified metrics are the default and only primary source. Flow per deliverable:
 *   - APPROVED                 → "Mark as posted" (live post URL) → gives verification its target.
 *   - source PLATFORM_VERIFIED → show the real Instagram numbers, read-only, "Verified" badge.
 *   - posted, not verified     → "Verify with Instagram" (on-demand). Then, by outcome:
 *       · NO_TOKEN / not connected → "Connect Instagram" (never manual).
 *       · manualFallbackAllowed    → manual form opens, clearly labelled self-reported.
 *       · otherwise                → "pending / try again shortly".
 * The manual form is a failure-only escape hatch — it appears ONLY when the server says Meta
 * genuinely failed for a connected account (`verification.manualFallbackAllowed`).
 */

import * as React from 'react';
import { BadgeCheck, BarChart3, Instagram, Loader2, RefreshCw, Send, Upload } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { useToast } from '@/hooks/use-toast';
import { MetricSourceBadge } from '@/components/analytics/metric-source-badge';
import { useCreatorDeliverableLifecycle } from '@/hooks/creator/useCreatorDeliverableLifecycle';
import { ApiError, api } from '@/lib/api';
import type { CreatorDeliverableMetricsPayload, CreatorDeliverableRowStatus } from '@/lib/api';

export interface DeliverableLifecyclePanelProps {
  deliverableId: string;
  title: string;
  status: CreatorDeliverableRowStatus;
  /** Called after any successful lifecycle mutation so the parent can refresh. */
  onChanged?: () => void;
}

const METRIC_FIELDS: { key: keyof CreatorDeliverableMetricsPayload; label: string }[] = [
  { key: 'likes', label: 'Likes' },
  { key: 'comments', label: 'Comments' },
  { key: 'shares', label: 'Shares' },
  { key: 'views', label: 'Views' },
  { key: 'reach', label: 'Reach' },
  { key: 'impressions', label: 'Impressions' },
  { key: 'saves', label: 'Saves' },
];

function formatNum(n: number | null | undefined): string {
  return typeof n === 'number' ? n.toLocaleString('en-IN') : '—';
}

export function DeliverableLifecyclePanel({
  deliverableId,
  title,
  status,
  onChanged,
}: DeliverableLifecyclePanelProps) {
  const { toast } = useToast();
  const { status: liveStatus, verification, busy, verifying, error, verify, markPosted, reportMetrics, uploadProof } =
    useCreatorDeliverableLifecycle(deliverableId, status);

  const currentStatus = liveStatus?.status ?? status;
  const source = verification?.metricSource ?? liveStatus?.metricSource ?? null;
  const metaConnected = verification?.metaConnected ?? liveStatus?.metaConnected ?? false;
  const manualAllowed = verification?.manualFallbackAllowed ?? false;
  const isVerified = source === 'PLATFORM_VERIFIED';

  const [livePostUrl, setLivePostUrl] = React.useState('');
  const [metrics, setMetrics] = React.useState<CreatorDeliverableMetricsPayload>({});
  const [connecting, setConnecting] = React.useState(false);
  const proofInputRef = React.useRef<HTMLInputElement>(null);

  const canMarkPosted = currentStatus === 'APPROVED';
  const posted =
    currentStatus === 'POSTED' ||
    currentStatus === 'METRICS_REPORTED' ||
    currentStatus === 'VERIFIED';

  const setMetric = (key: keyof CreatorDeliverableMetricsPayload, raw: string) => {
    const n = raw === '' ? undefined : Number(raw);
    setMetrics((prev) => ({ ...prev, [key]: Number.isFinite(n) ? n : undefined }));
  };

  const handleMarkPosted = async () => {
    const result = await markPosted(livePostUrl.trim());
    if (result) {
      toast({ title: 'Marked as posted', description: 'We’ll verify your metrics with Instagram.' });
      setLivePostUrl('');
      onChanged?.();
      void verify();
    }
  };

  const handleConnect = async () => {
    setConnecting(true);
    try {
      const { authorizationUrl } = await api.metaOAuth.authorize();
      window.location.href = authorizationUrl;
    } catch (err) {
      setConnecting(false);
      toast({
        variant: 'destructive',
        title: 'Could not start Instagram connect',
        description: err instanceof ApiError ? err.message : 'Please try again in a moment.',
      });
    }
  };

  const handleReportMetrics = async () => {
    const result = await reportMetrics({ metrics });
    if (result) {
      toast({ title: 'Metrics submitted', description: result.message || 'Recorded as self-reported.' });
      onChanged?.();
    }
  };

  const handleProof = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    const result = await uploadProof(file);
    if (proofInputRef.current) proofInputRef.current.value = '';
    if (result) toast({ title: 'Proof uploaded', description: 'Your proof screenshot was saved.' });
  };

  const hasAnyMetric = METRIC_FIELDS.some((f) => typeof metrics[f.key] === 'number');

  return (
    <Card className="border">
      <CardContent className="space-y-4 p-4">
        <div className="flex items-center justify-between gap-2">
          <h4 className="text-sm font-semibold">{title}</h4>
          <MetricSourceBadge source={source} className="shrink-0" />
        </div>

        {/* 1 — APPROVED: mark posted so verification has a target */}
        {canMarkPosted && (
          <div className="space-y-2">
            <Label htmlFor={`post-url-${deliverableId}`}>Live post URL</Label>
            <div className="flex gap-2">
              <Input
                id={`post-url-${deliverableId}`}
                placeholder="https://instagram.com/p/…"
                inputMode="url"
                value={livePostUrl}
                onChange={(e) => setLivePostUrl(e.target.value)}
              />
              <Button type="button" onClick={handleMarkPosted} disabled={busy || livePostUrl.trim() === ''}>
                {busy ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Send aria-hidden="true" />}
                Mark posted
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Once your content is live, paste its URL. We pull your reach & engagement straight from
              Instagram — no manual entry.
            </p>
          </div>
        )}

        {/* 2 — VERIFIED: real Instagram numbers, read-only */}
        {posted && isVerified && (
          <div className="space-y-2">
            <div className="grid grid-cols-3 gap-2 text-center">
              {(['reach', 'impressions', 'engagements'] as const).map((k) => (
                <div key={k} className="rounded-md border p-2">
                  <div className="text-sm font-semibold">{formatNum(verification?.[k])}</div>
                  <div className="text-xs capitalize text-muted-foreground">{k}</div>
                </div>
              ))}
            </div>
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <BadgeCheck className="h-3.5 w-3.5 text-stage-approved-fg" aria-hidden="true" />
              Pulled directly from Instagram — this is what the brand sees.
            </p>
            <Button type="button" variant="ghost" size="sm" onClick={() => void verify()} disabled={verifying}>
              {verifying ? <Loader2 className="animate-spin" aria-hidden="true" /> : <RefreshCw aria-hidden="true" />}
              Refresh
            </Button>
          </div>
        )}

        {/* 3 — posted, not verified: verify-first, then branch by outcome */}
        {posted && !isVerified && (
          <div className="space-y-3">
            {!verification && (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">
                  Your metrics are verified straight from Instagram. Run it now, or the daily sync will
                  pick it up.
                </p>
                <Button type="button" onClick={() => void verify()} disabled={verifying}>
                  {verifying ? <Loader2 className="animate-spin" aria-hidden="true" /> : <BadgeCheck aria-hidden="true" />}
                  Verify with Instagram
                </Button>
              </div>
            )}

            {verification && !metaConnected && (
              <Alert>
                <Instagram className="h-4 w-4" aria-hidden="true" />
                <AlertTitle>Connect Instagram</AlertTitle>
                <AlertDescription className="space-y-2">
                  <p>Connect your account so brands see your verified reach and engagement.</p>
                  <Button type="button" size="sm" onClick={handleConnect} disabled={connecting}>
                    {connecting ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Instagram aria-hidden="true" />}
                    Connect Instagram
                  </Button>
                </AlertDescription>
              </Alert>
            )}

            {verification && metaConnected && !manualAllowed && (
              <Alert>
                <Loader2 className="h-4 w-4" aria-hidden="true" />
                <AlertTitle>Verifying…</AlertTitle>
                <AlertDescription className="space-y-2">
                  <p>
                    We couldn’t read this post from Instagram yet ({verification.outcome}). This often
                    resolves on its own — try again shortly.
                  </p>
                  <Button type="button" variant="outline" size="sm" onClick={() => void verify()} disabled={verifying}>
                    {verifying ? <Loader2 className="animate-spin" aria-hidden="true" /> : <RefreshCw aria-hidden="true" />}
                    Try again
                  </Button>
                </AlertDescription>
              </Alert>
            )}

            {/* Manual fallback — ONLY when Meta genuinely failed for a connected account */}
            {verification && manualAllowed && (
              <div className="space-y-3">
                <Alert variant="destructive">
                  <BarChart3 className="h-4 w-4" aria-hidden="true" />
                  <AlertTitle>Instagram couldn’t verify this post</AlertTitle>
                  <AlertDescription>
                    Enter your numbers manually to keep things moving. They’ll be shown to the brand as
                    <strong> self-reported</strong>, not verified.
                  </AlertDescription>
                </Alert>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  {METRIC_FIELDS.map((f) => (
                    <div key={f.key} className="space-y-1">
                      <Label htmlFor={`m-${f.key}-${deliverableId}`} className="text-xs">
                        {f.label}
                      </Label>
                      <Input
                        id={`m-${f.key}-${deliverableId}`}
                        type="number"
                        min={0}
                        inputMode="numeric"
                        value={metrics[f.key] ?? ''}
                        onChange={(e) => setMetric(f.key, e.target.value)}
                      />
                    </div>
                  ))}
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button type="button" onClick={handleReportMetrics} disabled={busy || !hasAnyMetric}>
                    {busy ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Send aria-hidden="true" />}
                    Submit self-reported
                  </Button>
                  <Button type="button" variant="outline" disabled={busy} onClick={() => proofInputRef.current?.click()}>
                    <Upload aria-hidden="true" />
                    Upload proof
                  </Button>
                  <input
                    ref={proofInputRef}
                    type="file"
                    accept="image/*"
                    className="sr-only"
                    onChange={handleProof}
                  />
                </div>
              </div>
            )}
          </div>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
      </CardContent>
    </Card>
  );
}

export default DeliverableLifecyclePanel;
