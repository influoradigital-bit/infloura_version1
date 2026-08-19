/**
 * The three-up strip under the hero on / and /about.
 *
 * WHAT THIS REPLACED, AND WHY (F-0342)
 * ------------------------------------
 * Both pages used to render this block:
 *
 *   { label: 'Creators on platform', value: 8915,     ... }
 *   { label: 'Paid out to creators', value: 42600000, ... }   // "₹4.3Cr+"
 *   { label: 'Avg. payout time',     value: 24,       ... }   // "24h"
 *
 * animated with <CountUp>, which reads to a visitor as live telemetry ticking
 * up. Every one of those numbers was a hardcoded literal. There is no source
 * behind them: `PlatformStat` is per-creator Instagram data (followers and
 * engagement for one profile), not site-wide totals, and `PublicConfigController`
 * exposes only `requireEmailOtp` and a Razorpay key ID. Nothing in the API can
 * produce a platform-wide creator count or lifetime payout figure today.
 *
 * Publishing invented traction figures is a problem on three fronts at once:
 *   - they are a factual claim to every visitor, and in India specifically an
 *     unsubstantiated performance claim is ASCI/consumer-law exposure;
 *   - AI answer engines now quote marketing pages directly, so a fabricated
 *     figure propagates into answers we do not control and cannot retract;
 *   - the same three numbers were duplicated by hand in two files, so they were
 *     already drifting out of any single intent.
 *
 * THE REPLACEMENT RULE
 * --------------------
 * Everything below is true *by construction* — it describes how the product
 * behaves, not how much it has been used. None of it needs a data source, and
 * none of it goes stale or becomes false as traffic changes. That is the test
 * any future entry here has to pass.
 *
 * If real, measured traction numbers are wanted in this slot later, they must
 * arrive from an endpoint — not from a literal typed into this file. That is
 * what `.proof-os/gates/F-SEO-marketing-surface.sh` now enforces: a numeric
 * literal in a stat-shaped constant on a marketing page fails the build.
 */
export interface ProofPoint {
  /** The large, bold line. Keep it to a few characters — it is scanned, not read. */
  value: string;
  /** The explanatory line under it. */
  label: string;
}

export const PROOF_POINTS: ProofPoint[] = [
  {
    value: '₹0',
    label: 'To start — no subscription on the Free tier',
  },
  {
    value: 'On approval',
    label: 'Payment releases only after you approve the work',
  },
  {
    value: 'Nano → macro',
    label: 'No minimum follower count to join as a creator',
  },
];
