import { useReducedMotion, motion } from 'framer-motion';
import { ShieldCheck, ShieldAlert, ShieldQuestion, ShieldX, Info } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import { cn } from '@/lib/utils';

interface BrandSafetyBadgeProps {
  /** 0-100 (100 = no concern). Null = not yet computed for this creator. */
  brandSafetyScore: number | null;
  /**
   * CreatorScoresResponse.garmFlags — wire contract is `List<String>`
   * (AnalyticsDtos.java CreatorScoresResponse.garmFlags, mirrored by
   * CreatorScores.garmFlags in src/lib/types.ts). BrandSafetyScoreService
   * writes a flat above-floor-category JSON string array
   * (BrandSafetyScoreService.writeGarmFlagsJson), matching what
   * JsonLists.stringListFromJson reads back — see the component's doc
   * comment below for the (now-fixed) history here.
   */
  garmFlags?: string[] | null;
  loading?: boolean;
  className?: string;
}

/**
 * Letter-grade bands for brand_safety_score (0-100). No existing grade scale
 * was found anywhere in code or wiki/ (checked VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md,
 * ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md, wiki/decisions/, wiki/tech/) — this is a
 * new convention proposed for C4, deliberately mirroring the existing
 * QualityScoreDisplay/FakeFollowerIndicator threshold shape (80/60/40 cut points)
 * already used elsewhere in this file's siblings so scores read consistently
 * across the dashboard:
 *   90-100 = A  (no/negligible concern)
 *   80-89  = B  (minor concern)
 *   70-79  = C  (moderate concern, review recommended)
 *   60-69  = D  (significant concern)
 *   0-59   = F  (severe concern — brand_safety_score is worst-post-driven per
 *                BrandSafetyScoreService, so an F reflects at least one high-risk post)
 * Priya/Vikram should override this if a different scale gets ratified later.
 */
function getGrade(score: number): {
  letter: string;
  label: string;
  color: string;
  bgColor: string;
  Icon: typeof ShieldCheck;
} {
  if (score >= 90)
    return { letter: 'A', label: 'No Concern', color: 'text-success-foreground', bgColor: 'bg-success', Icon: ShieldCheck };
  if (score >= 80)
    return { letter: 'B', label: 'Minor Concern', color: 'text-primary', bgColor: 'bg-primary/10', Icon: ShieldCheck };
  if (score >= 70)
    return { letter: 'C', label: 'Moderate Concern', color: 'text-amber-600', bgColor: 'bg-amber-500/10', Icon: ShieldQuestion };
  if (score >= 60)
    return { letter: 'D', label: 'Significant Concern', color: 'text-amber-600', bgColor: 'bg-amber-500/10', Icon: ShieldAlert };
  return { letter: 'F', label: 'Severe Concern', color: 'text-destructive-foreground', bgColor: 'bg-destructive', Icon: ShieldX };
}

/**
 * Brand-safety letter-grade badge — Wave C task C4. Wired to
 * GET /analytics/creators/{creatorId}/scores (CreatorScoresResponse.brandSafetyScore),
 * now populated by ScoreCalculationJob -> BrandSafetyScoreService (Wave C task C3,
 * Kabir-signed-off wiki/errors/wave-c-task-c3-security-review.md). Replaces the
 * inert placeholder that previously lived only as a comment block in
 * brand-creator-analytics.tsx (BrandSafetyBadge was "intentionally not built"
 * there because the score was always null) — this component now renders the
 * real score, same first-class-empty-state discipline as B5's
 * AudienceDemographicsPanel: `brandSafetyScore === null` (score not computed
 * yet for this creator, e.g. ScoreCalculationJob hasn't run or influora-ai was
 * unreachable on every run so far — see BrandSafetyScoreService's
 * graceful-degradation contract) is an explicit informational empty state,
 * never a fabricated grade.
 *
 * Backend gap CLOSED (was flagged here per B5 precedent; re-verified during
 * the Brand Surface Audit fix pass, wiki/reports/brand-feature-audit.md):
 * this comment used to describe a real mismatch — an earlier
 * BrandSafetyScoreService.writeGarmFlagsJson (influora-api/src/main/java/com/influora/
 * service/scoring/BrandSafetyScoreService.java) serialized the FULL
 * classified-item list (each with a nested 10-category {category,risk,rationale}
 * breakdown) into `creator_scores.garm_flags`, which AnalyticsService.getCreatorScores'
 * JsonLists.stringListFromJson (a List<String> parser) couldn't parse — it
 * would silently degrade to an empty list. That has since been fixed:
 * writeGarmFlagsJson now writes the distinct set of above-floor GARM category
 * names as a flat JSON string array, exactly what stringListFromJson expects
 * and what AnalyticsDtos.CreatorScoresResponse.garmFlags (List<String>)
 * declares. The prop is still rendered defensively below (plain tags, no
 * assumption of a fixed category list), which is also just the correct way
 * to render a flat string array — no frontend change was needed for this fix.
 */
export function BrandSafetyBadge({
  brandSafetyScore,
  garmFlags,
  loading = false,
  className,
}: BrandSafetyBadgeProps) {
  const shouldReduceMotion = useReducedMotion();

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-32" />
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-4">
            <Skeleton className="h-12 w-12 rounded-full" />
            <Skeleton className="h-6 w-28" />
          </div>
        </CardContent>
      </Card>
    );
  }

  if (brandSafetyScore === null) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">Brand Safety</CardTitle>
        </CardHeader>
        <CardContent>
          <Empty className="border-0 p-0">
            <EmptyHeader>
              <ShieldQuestion className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
              <EmptyTitle>Not yet available</EmptyTitle>
              <EmptyDescription>
                This creator hasn't been scored for brand safety yet.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </CardContent>
      </Card>
    );
  }

  const grade = getGrade(brandSafetyScore);
  const flags = garmFlags ?? [];

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-sm font-medium text-muted-foreground">Brand Safety</CardTitle>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  className="text-muted-foreground hover:text-foreground"
                  aria-label="Learn more about brand safety scoring"
                >
                  <Info className="h-4 w-4" />
                </button>
              </TooltipTrigger>
              <TooltipContent className="max-w-xs">
                <p>
                  Brand safety is scored against the GARM (Global Alliance for Responsible
                  Media) framework across 10 risk categories. The grade reflects the most
                  severe finding across this creator's recently classified content, not an
                  average — a single high-risk post pulls the grade down sharply.
                </p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>
      </CardHeader>
      <CardContent>
        <div
          className="flex items-center gap-4"
          role="img"
          aria-label={`Brand safety grade: ${grade.letter}, ${grade.label}, score ${brandSafetyScore} out of 100`}
        >
          <div className={cn('flex h-12 w-12 items-center justify-center rounded-full', grade.bgColor)}>
            <span className={cn('text-xl font-bold', grade.color)}>{grade.letter}</span>
          </div>
          <div className="flex-1">
            <div className="flex items-baseline gap-2">
              <motion.span
                className={cn('text-2xl font-bold', grade.color)}
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ duration: shouldReduceMotion ? 0 : 0.3 }}
              >
                {brandSafetyScore}
              </motion.span>
              <span className="text-sm text-muted-foreground">/ 100</span>
            </div>
            <p className={cn('flex items-center gap-1 text-sm', grade.color)}>
              <grade.Icon className="h-3.5 w-3.5" aria-hidden="true" />
              {grade.label}
            </p>
          </div>
        </div>

        {flags.length > 0 ? (
          <div className="mt-4 space-y-2 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground">GARM flags</p>
            <div className="flex flex-wrap gap-1.5" role="list" aria-label="GARM risk flags">
              {flags.map((flag) => (
                <Badge key={flag} variant="outline" role="listitem">
                  {flag}
                </Badge>
              ))}
            </div>
          </div>
        ) : (
          <p className="mt-4 border-t border-border pt-4 text-xs text-muted-foreground">
            No elevated-risk GARM categories reported.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

export default BrandSafetyBadge;
