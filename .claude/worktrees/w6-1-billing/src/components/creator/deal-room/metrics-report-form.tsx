'use client';

import * as React from 'react';
import { AlertCircle, BarChart3 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogDescription,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Alert, AlertDescription } from '@/components/ui/alert';
import type { CreatorDeliverableMetricsPayload } from '@/lib/api';

interface MetricsDeliverableOption {
  id: string;
  title: string;
}

interface MetricsReportFormProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  deliverables: MetricsDeliverableOption[];
  onSubmit: (data: MetricsReportData) => Promise<void>;
  isSubmitting?: boolean;
  submitError?: string | null;
}

export interface MetricsReportData {
  deliverableId: string;
  metrics: CreatorDeliverableMetricsPayload;
  proofScreenshots?: string[];
  reportedDaysAfterPosting?: number;
}

type MetricField = keyof CreatorDeliverableMetricsPayload;

const METRIC_FIELDS: { key: MetricField; label: string }[] = [
  { key: 'likes', label: 'Likes' },
  { key: 'comments', label: 'Comments' },
  { key: 'shares', label: 'Shares' },
  { key: 'views', label: 'Views' },
  { key: 'reach', label: 'Reach' },
  { key: 'impressions', label: 'Impressions' },
  { key: 'saves', label: 'Saves' },
];

function parseMetricValue(raw: string): number | undefined {
  if (raw.trim() === '') return undefined;
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed) || parsed < 0) return undefined;
  return parsed;
}

function hasAnyMetric(metrics: CreatorDeliverableMetricsPayload): boolean {
  return METRIC_FIELDS.some((field) => metrics[field.key] != null && metrics[field.key]! > 0);
}

export function MetricsReportForm({
  open,
  onOpenChange,
  deliverables,
  onSubmit,
  isSubmitting = false,
  submitError = null,
}: MetricsReportFormProps) {
  const [selectedDeliverable, setSelectedDeliverable] = React.useState('');
  const [metricValues, setMetricValues] = React.useState<Record<MetricField, string>>({
    likes: '',
    comments: '',
    shares: '',
    views: '',
    reach: '',
    impressions: '',
    saves: '',
  });
  const [daysAfterPosting, setDaysAfterPosting] = React.useState('');

  React.useEffect(() => {
    if (deliverables.length > 0 && !selectedDeliverable) {
      setSelectedDeliverable(deliverables[0].id);
    }
  }, [deliverables, selectedDeliverable]);

  const buildMetrics = (): CreatorDeliverableMetricsPayload => {
    const metrics: CreatorDeliverableMetricsPayload = {};
    for (const field of METRIC_FIELDS) {
      const value = parseMetricValue(metricValues[field.key]);
      if (value != null) {
        metrics[field.key] = value;
      }
    }
    return metrics;
  };

  const metrics = buildMetrics();
  const canSubmit = Boolean(selectedDeliverable) && hasAnyMetric(metrics);

  const handleSubmit = async () => {
    if (!canSubmit) return;

    const reportedDaysAfterPosting = parseMetricValue(daysAfterPosting);

    try {
      await onSubmit({
        deliverableId: selectedDeliverable,
        metrics,
        reportedDaysAfterPosting,
      });
      setMetricValues({
        likes: '',
        comments: '',
        shares: '',
        views: '',
        reach: '',
        impressions: '',
        saves: '',
      });
      setDaysAfterPosting('');
      setSelectedDeliverable(deliverables[0]?.id ?? '');
      onOpenChange(false);
    } catch {
      // Parent surfaces submitError; keep form state for retry.
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <BarChart3 className="h-5 w-5" />
            Report Performance Metrics
          </DialogTitle>
          <DialogDescription>
            Enter the metrics from your post&apos;s insights. At least one metric is required.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-5 py-2">
          <div className="space-y-2">
            <Label htmlFor="metrics-deliverable">Deliverable</Label>
            <Select value={selectedDeliverable} onValueChange={setSelectedDeliverable}>
              <SelectTrigger id="metrics-deliverable">
                <SelectValue placeholder="Choose a deliverable..." />
              </SelectTrigger>
              <SelectContent>
                {deliverables.map((d) => (
                  <SelectItem key={d.id} value={d.id}>
                    {d.title}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            {METRIC_FIELDS.map((field) => (
              <div key={field.key} className="space-y-1.5">
                <Label htmlFor={`metric-${field.key}`}>{field.label}</Label>
                <Input
                  id={`metric-${field.key}`}
                  type="number"
                  min={0}
                  inputMode="numeric"
                  placeholder="0"
                  value={metricValues[field.key]}
                  onChange={(e) =>
                    setMetricValues((prev) => ({ ...prev, [field.key]: e.target.value }))
                  }
                  disabled={isSubmitting}
                />
              </div>
            ))}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="days-after-posting">Days after posting (optional)</Label>
            <Input
              id="days-after-posting"
              type="number"
              min={0}
              inputMode="numeric"
              placeholder="e.g. 7"
              value={daysAfterPosting}
              onChange={(e) => setDaysAfterPosting(e.target.value)}
              disabled={isSubmitting}
            />
          </div>

          {submitError && (
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>{submitError}</AlertDescription>
            </Alert>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit || isSubmitting}>
            {isSubmitting ? 'Submitting...' : 'Submit metrics'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
