/**
 * INFLUORA ADMIN PANEL — Creator Agent rate calibration table
 * Reference: T-MEERA-CREATOR-PHASE-B (SPEC.md §14.1.g, B0-35)
 *
 * Focus: the two things about this table that no type-checker, linter or screenshot can see.
 *
 * 1. A SUPPRESSED realised median must read as unknown, in words. The backend returns `null` for
 *    any tier whose sample is under the k-anonymity floor, and that null carries a specific
 *    meaning: "we are not telling you, because a median over four deals is a statement about four
 *    identifiable brands". Rendered as `₹0` it becomes "this tier closes at nothing"; rendered as
 *    a bare dash it becomes indistinguishable from every other empty cell on the admin console.
 *    Either way an admin reading this table to set a pricing override (§14.1.h) reads a
 *    suppression as a measurement. `tsc` is happy with all three renderings — only this test
 *    separates them.
 *
 * 2. The tiers must come out in SIZE order. The page already had a `TIER_ORDER` constant holding
 *    LOYALTY tiers (BRONZE..PLATINUM) when this table was added. Reusing it sorts every rate tier
 *    into the -1 bucket and falls through to `localeCompare`, producing MACRO, MEGA, MICRO, MID,
 *    NANO — an alphabetical accident that still looks like an ordering, on a table whose whole
 *    job is to be read down the size axis. Priya flagged it as SPEC.md §14.6 W7 before the code
 *    existed; this is the regression test for it.
 *
 * Day one is the all-suppressed case: with no priced deals closed, every realised cell reads
 * unknown. That is the correct output of an uncalibrated system, so it is tested as deliberately
 * as the populated one.
 *
 * Run: npx vitest run src/admin/pages/CreatorAgentBaselinesPage.calibration.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { CreatorAgentRateCalibration, CreatorAgentRateCalibrationTier } from '../types/admin.types';
import CreatorAgentBaselinesPage from './CreatorAgentBaselinesPage';

const getBaselinesMock = vi.fn();
const getRateCalibrationMock = vi.fn();

vi.mock('../services/api-contracts', async () => {
  const actual = await vi.importActual<typeof import('../services/api-contracts')>(
    '../services/api-contracts',
  );
  return {
    ...actual,
    creatorAgentApi: {
      getBaselines: (...args: unknown[]) => getBaselinesMock(...args),
      getRateCalibration: (...args: unknown[]) => getRateCalibrationMock(...args),
    },
  };
});

/** The literal the page renders for a suppressed figure. */
const UNKNOWN = 'Not enough data';

/** Compiled defaults, as `RateTierProperties.DEFAULT_TIER_BASE_RATES` has them (INR, per post). */
const COMPILED: Record<string, [number, number]> = {
  NANO: [1000, 5000],
  MICRO: [5000, 25000],
  MID: [25000, 100000],
  MACRO: [100000, 500000],
  MEGA: [500000, 2500000],
};

function tierRow(
  tier: string,
  overrides: Partial<CreatorAgentRateCalibrationTier> = {},
): CreatorAgentRateCalibrationTier {
  const [min, max] = COMPILED[tier];
  return {
    tier,
    benchmark_min: min,
    benchmark_max: max,
    benchmark_unit: min + (max - min) / 2,
    benchmark_source: 'compiled default',
    realised_median: null,
    realised_n: 0,
    distinct_workspaces: 0,
    meera_anchored_share: null,
    quoted_median_90d: null,
    quoted_n_90d: 0,
    ...overrides,
  };
}

/**
 * The backend emits tiers in size order. This helper deliberately hands the page the WRONG order
 * (alphabetical) so the assertion on rendered order is testing the page's own sort, not an order
 * it was handed for free.
 */
function calibration(tiers: CreatorAgentRateCalibrationTier[]): CreatorAgentRateCalibration {
  return {
    tiers,
    sample_floor: 5,
    window_days: 90,
    computed_at: '2026-09-12T06:00:00Z',
  };
}

function allTiersSuppressed(): CreatorAgentRateCalibrationTier[] {
  return ['MACRO', 'MEGA', 'MICRO', 'MID', 'NANO'].map((t) => tierRow(t));
}

/** The cells of one tier's row, in render order. */
function cellsFor(tier: string): HTMLTableCellElement[] {
  const header = screen.getByRole('rowheader', { name: tier });
  const row = header.closest('tr');
  expect(row).not.toBeNull();
  return Array.from((row as HTMLTableRowElement).querySelectorAll('td'));
}

/**
 * Column indexes into `cellsFor()`. The tier name is a `<th scope="row">`, NOT a td, so the
 * eight data cells are 0-7 — reading a 9th is how this test first failed with a TypeError
 * instead of an assertion.
 */
const COL = {
  band: 0,
  unit: 1,
  source: 2,
  realisedMedian: 3,
  sample: 4,
  workspaces: 5,
  anchored: 6,
  quoted: 7,
} as const;

function realisedMedianCell(tier: string): HTMLTableCellElement {
  return cellsFor(tier)[COL.realisedMedian];
}

function renderedTierOrder(): string[] {
  return screen.getAllByRole('rowheader').map((el) => el.textContent?.trim() ?? '');
}

async function renderPage() {
  render(<CreatorAgentBaselinesPage />);
  await waitFor(() => expect(getRateCalibrationMock).toHaveBeenCalled());
  await waitFor(() => expect(screen.getByText(/rate calibration by tier/i)).toBeInTheDocument());
}

describe('CreatorAgentBaselinesPage — rate calibration table (B0-35)', () => {
  beforeEach(() => {
    getBaselinesMock.mockReset();
    getRateCalibrationMock.mockReset();
    getBaselinesMock.mockResolvedValue({
      success: true,
      data: {
        creators_by_tier: {},
        briefs_per_creator_per_month: { median: 0, p75: 0, p90: 0 },
        meta_connect_rate: 0,
        median_creator_reply_hours: null,
        sample_label_compliance: { sample_size: 0, labelled_count: 0, compliance_rate: 0 },
        computed_at: '2026-09-12T06:00:00Z',
      },
    });
    getRateCalibrationMock.mockResolvedValue({
      success: true,
      data: calibration(allTiersSuppressed()),
    });
  });

  // ===========================================================================================
  // The suppressed path — which on day one is every tier
  // ===========================================================================================

  it('renders a suppressed realised median as words, never as a zero and never as a bare dash', async () => {
    await renderPage();

    const cell = realisedMedianCell('NANO');
    const text = cell.textContent?.trim() ?? '';

    expect(text).toBe(UNKNOWN);
    // The three renderings tsc cannot tell apart. A zero says the tier closes at nothing; a dash
    // says nothing at all and looks like every other empty admin cell.
    expect(text).not.toBe('₹0');
    expect(text).not.toBe('0');
    expect(text).not.toMatch(/^[—–-]$/);
  });

  it('day one — no priced deals anywhere — shows every tier as unknown, not as zeros', async () => {
    await renderPage();

    for (const tier of Object.keys(COMPILED)) {
      expect(realisedMedianCell(tier).textContent?.trim()).toBe(UNKNOWN);
    }
    // And the caption explains WHY it is empty, so the panel does not read as broken.
    expect(screen.getByText(/does not mean zero/i)).toBeInTheDocument();
  });

  it('still shows the sample size behind a suppressed median, flagged as under the floor', async () => {
    getRateCalibrationMock.mockResolvedValue({
      success: true,
      data: calibration([
        tierRow('NANO', { realised_median: null, realised_n: 4, distinct_workspaces: 4 }),
        tierRow('MICRO'),
        tierRow('MID'),
        tierRow('MACRO'),
        tierRow('MEGA'),
      ]),
    });
    await renderPage();

    expect(realisedMedianCell('NANO').textContent?.trim()).toBe(UNKNOWN);
    // n survives suppression: without it a reader cannot tell "4 deals, withheld" from "0 deals".
    const sampleCell = cellsFor('NANO')[COL.sample];
    expect(sampleCell.textContent).toContain('4');
    expect(sampleCell.textContent).toMatch(/under floor/i);
  });

  it('suppresses the Meera-anchored share under the same floor, in the same words', async () => {
    await renderPage();
    expect(cellsFor('NANO')[COL.anchored].textContent?.trim()).toBe(UNKNOWN);
  });

  // ===========================================================================================
  // The populated path
  // ===========================================================================================

  it('renders a realised median once the tier clears the floor', async () => {
    getRateCalibrationMock.mockResolvedValue({
      success: true,
      data: calibration([
        tierRow('NANO', {
          realised_median: 2500,
          realised_n: 6,
          distinct_workspaces: 4,
          meera_anchored_share: 0.5,
          quoted_median_90d: 4125,
          quoted_n_90d: 12,
        }),
        tierRow('MICRO'),
        tierRow('MID'),
        tierRow('MACRO'),
        tierRow('MEGA'),
      ]),
    });
    await renderPage();

    const cells = cellsFor('NANO');
    expect(cells[COL.realisedMedian].textContent).toContain('2,500');
    expect(cells[COL.realisedMedian].textContent).not.toContain(UNKNOWN);
    expect(cells[COL.anchored].textContent).toContain('50.0%');
    expect(cells[COL.quoted].textContent).toContain('4,125');
    // n is rendered beside the quoted median, which carries no k-anonymity floor of its own.
    expect(cells[COL.quoted].textContent).toContain('12');
  });

  it('says so plainly when a tier has had no quotes issued at all', async () => {
    await renderPage();
    expect(cellsFor('NANO')[COL.quoted].textContent).toMatch(/no quotes issued/i);
  });

  // ===========================================================================================
  // Ordering — the W7 regression
  // ===========================================================================================

  it('orders tiers by SIZE (NANO..MEGA), not alphabetically as the loyalty TIER_ORDER would', async () => {
    // Handed to the page alphabetically on purpose.
    await renderPage();

    expect(renderedTierOrder()).toEqual(['NANO', 'MICRO', 'MID', 'MACRO', 'MEGA']);
    // What reusing the page's loyalty-tier TIER_ORDER produces: every rate tier misses the list,
    // both indexes are -1, and localeCompare decides. Pinned so the wrong constant cannot come
    // back looking correct.
    expect(renderedTierOrder()).not.toEqual(['MACRO', 'MEGA', 'MICRO', 'MID', 'NANO']);
  });

  it('does not drop a tier name it has never seen — an unknown tier sorts last, it is not hidden', async () => {
    getRateCalibrationMock.mockResolvedValue({
      success: true,
      data: calibration([...allTiersSuppressed(), { ...tierRow('NANO'), tier: 'GIGA' }]),
    });
    await renderPage();

    expect(renderedTierOrder()).toEqual(['NANO', 'MICRO', 'MID', 'MACRO', 'MEGA', 'GIGA']);
  });

  // ===========================================================================================
  // The yml override — B0-36 has to be able to confirm its change took effect
  // ===========================================================================================

  it('reflects a yml tier override, and labels which tiers are still on the compiled default', async () => {
    getRateCalibrationMock.mockResolvedValue({
      success: true,
      data: calibration([
        tierRow('NANO', {
          benchmark_min: 1200,
          benchmark_max: 4800,
          benchmark_unit: 3000,
          benchmark_source: 'yml override',
        }),
        tierRow('MICRO'),
        tierRow('MID'),
        tierRow('MACRO'),
        tierRow('MEGA'),
      ]),
    });
    await renderPage();

    const nano = cellsFor('NANO');
    // The overridden band, not the compiled 1,000-5,000 one.
    expect(nano[COL.band].textContent).toContain('1,200');
    expect(nano[COL.band].textContent).toContain('4,800');
    expect(nano[COL.band].textContent).not.toContain('5,000');
    expect(nano[COL.source].textContent).toContain('yml override');

    // An un-overridden tier says which figures it is showing, so the report cannot be mistaken
    // for describing a configuration that is not running.
    expect(cellsFor('MICRO')[COL.source].textContent).toContain('compiled default');
  });

  // ===========================================================================================
  // Error path
  // ===========================================================================================

  it('reports a failed calibration fetch without blanking the Phase A panels above it', async () => {
    getRateCalibrationMock.mockResolvedValue({ success: false, error: 'boom' });
    render(<CreatorAgentBaselinesPage />);

    await waitFor(() =>
      expect(screen.getByText(/failed to load rate calibration/i)).toBeInTheDocument(),
    );
    // The two panels are independent fetches; one failing must not take the other down.
    // getByRole, not getByText: "creators by tier" also appears in the page subtitle prose,
    // so a text query matches two nodes and throws before asserting anything.
    expect(screen.getByRole('heading', { name: /creators by tier/i })).toBeInTheDocument();
  });
});
