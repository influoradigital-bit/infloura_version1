/**
 * Cumulative progress over the brand pipeline's stage buckets.
 *
 * Lives in `lib/` rather than beside the checklist component so the rule can be pinned by a test
 * without importing React, and so any other surface that needs "has this account got past stage
 * X yet?" derives it from one place instead of re-deriving the ordering.
 */

/**
 * The stage vocabulary `DashboardService#bucketFor` emits, in lifecycle order (PL-2, matching
 * `brand-pipeline-stage.ts`'s board vocabulary). `Completed` is the dead pre-PL-2 key — kept out
 * of the order deliberately, since `bucketFor` emits `Settled` and a duplicate terminal bucket
 * would double-count.
 */
export const PIPELINE_STAGE_ORDER = [
  'Outreach',
  'Negotiating',
  'Contracted',
  'In Progress',
  'Review',
  'Settled',
] as const;

export type PipelineStageLabel = (typeof PIPELINE_STAGE_ORDER)[number];

/**
 * How many deals have reached `stage` **or any stage after it**.
 *
 * Cumulative, not exact-bucket, on purpose: a deal sitting in `Contracted` has necessarily been
 * negotiated. Counting only the exact bucket would un-tick "Agree the terms" the moment the deal
 * advanced past it, so a first-run checklist would appear to go backwards as the user made
 * progress. An unrecognised stage label contributes nothing rather than throwing — the dashboard
 * must not blank out because the backend added a bucket.
 */
export function countAtOrBeyond(
  pipeline: Array<{ stage: string; count: number }>,
  stage: PipelineStageLabel,
): number {
  const from = PIPELINE_STAGE_ORDER.indexOf(stage);
  if (from < 0) return 0;
  const counted = new Set<string>(PIPELINE_STAGE_ORDER.slice(from));
  return pipeline.reduce((sum, p) => (counted.has(p.stage) ? sum + p.count : sum), 0);
}
