import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { CheckCircle2, AlertTriangle, XCircle, ChevronDown, ShieldCheck } from 'lucide-react';

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { cn } from '@/lib/utils';
import type { DeliverableSafetyCheck, DeliverableSafetyReview, DeliverableSafetyVerdict } from '@/lib/api';

interface DeliverableSafetyReviewCardProps {
  review: DeliverableSafetyReview | null;
  loading?: boolean;
  className?: string;
}

const VERDICT_CONFIG: Record<
  DeliverableSafetyVerdict,
  { label: string; className: string; Icon: typeof CheckCircle2 }
> = {
  PASS: { label: 'No concerns found', className: 'bg-success/15 text-success', Icon: CheckCircle2 },
  REVIEW: { label: 'Review recommended', className: 'bg-warning/15 text-warning', Icon: AlertTriangle },
  FAIL: { label: 'Issues found', className: 'bg-destructive/15 text-destructive-foreground', Icon: XCircle },
};

const CHECK_STATUS_CONFIG: Record<
  DeliverableSafetyCheck['status'],
  { className: string; Icon: typeof CheckCircle2 }
> = {
  PASS: { className: 'bg-success/15 text-success', Icon: CheckCircle2 },
  WARNING: { className: 'bg-warning/15 text-warning', Icon: AlertTriangle },
  FAIL: { className: 'bg-destructive/15 text-destructive-foreground', Icon: XCircle },
};

/**
 * Advisory brand-safety review for a submitted deliverable — Brand Surface
 * Audit fix #3 (wiki/reports/brand-feature-audit.md item 3: "no code path
 * scores submitted deliverable content"). Renders pass/fail/warning chips
 * plus an overall verdict from Vikram's (in-progress) deliverable-content
 * safety check.
 *
 * ADVISORY ONLY — this card never disables or gates DeliverableViewer's
 * Approve / Request Changes buttons; it renders alongside them as pure
 * information. Same idiom as the creator-side pre-submit check described in
 * wiki/ai-review/meera-label-to-moat-build-plan.md section 3.1
 * (`CompliancePreCheck.tsx`): collapsible card, chips are text+icon (not
 * color-only) so the signal survives grayscale/colorblind viewing, and this
 * uses the shadcn `text-destructive-foreground` token family — matching this
 * component's mount site, DeliverableViewer.tsx (bg-success/15 text-success,
 * bg-warning/15 text-warning, text-destructive-foreground), NOT the
 * `meera-*` tokens, which are scoped to the Meera chat surface only.
 *
 * Renders nothing when `review` is null (not yet available, not computed for
 * this deliverable version, or the backend route isn't live yet) — see
 * useDeliverableSafetyReview's doc comment for why that degrades silently
 * instead of showing an error state.
 */
export function DeliverableSafetyReviewCard({
  review,
  loading = false,
  className,
}: DeliverableSafetyReviewCardProps) {
  const shouldReduceMotion = useReducedMotion();
  const [open, setOpen] = React.useState(false);

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <div className="h-4 w-40 animate-pulse rounded bg-muted" aria-hidden="true" />
        </CardHeader>
      </Card>
    );
  }

  if (!review) return null;

  const verdict = VERDICT_CONFIG[review.overallVerdict];

  return (
    <Card className={className}>
      <Collapsible open={open} onOpenChange={setOpen}>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between gap-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              <ShieldCheck className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
              Brand Safety Review
              <span className="text-xs font-normal text-muted-foreground">(advisory)</span>
            </CardTitle>
            <CollapsibleTrigger asChild>
              <button
                type="button"
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium',
                  verdict.className,
                )}
                aria-expanded={open}
                aria-label={`Brand safety review: ${verdict.label}. ${open ? 'Collapse' : 'Expand'} details.`}
              >
                <verdict.Icon className="h-3.5 w-3.5" aria-hidden="true" />
                {verdict.label}
                <ChevronDown
                  className={cn(
                    'h-3 w-3 transition-transform',
                    open && 'rotate-180',
                    shouldReduceMotion && 'transition-none',
                  )}
                  aria-hidden="true"
                />
              </button>
            </CollapsibleTrigger>
          </div>
        </CardHeader>
        <CollapsibleContent>
          <CardContent className="space-y-2 pt-0">
            <div className="flex flex-wrap gap-1.5" role="list" aria-label="Safety checks">
              {review.checks.map((check) => {
                const config = CHECK_STATUS_CONFIG[check.status];
                return (
                  // `check.detail` is model-generated free text about this deliverable's own
                  // caption (Kabir F2, wiki/build/brand-fixes-kabir-review.md): it is NOT run
                  // back through SensitiveTextRedactor on the response path, so a hostile
                  // caption could in principle steer its wording. Passed only as the `title`
                  // attribute (plain string -> native browser tooltip) — React sets this as a
                  // literal DOM attribute, never parsed/executed as HTML. Never render `detail`
                  // via dangerouslySetInnerHTML anywhere on this path.
                  <Badge
                    key={check.id}
                    variant="outline"
                    role="listitem"
                    className={cn('gap-1 font-normal', config.className)}
                    title={check.detail}
                  >
                    <config.Icon className="h-3 w-3" aria-hidden="true" />
                    {check.label}
                  </Badge>
                );
              })}
            </div>
            <p className="text-xs text-muted-foreground">
              Advisory only — this does not affect your ability to approve or request changes.
            </p>
          </CardContent>
        </CollapsibleContent>
      </Collapsible>
    </Card>
  );
}

export default DeliverableSafetyReviewCard;
