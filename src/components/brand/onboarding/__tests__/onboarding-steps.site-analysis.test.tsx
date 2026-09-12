/**
 * Brand onboarding — site-analysis feedback (P1-13).
 *
 * THE DEFECT: submitting the company step hands `websiteUrl` to
 * `AnalyzeSiteTriggerService`, which fetches the brand's storefront — and we
 * never told the brand any of it. The field was a bare "Website (optional)"
 * input with no notice, no progress and no result, and when the brand-profile
 * poll exhausted its budget the give-up was silent: nothing on screen ever
 * changed again.
 *
 * WHY EACH TEST FAILS AGAINST THE PRE-FIX CODE:
 *   - `SiteAnalysisStatus` did not exist at all (onboarding-steps.tsx had no
 *     export of that name), so every state test below fails at import.
 *   - the URL field rendered `<Label>` + `<Input>` and nothing else — no
 *     disclosure text of any kind, so the two disclosure tests fail on a
 *     missing element.
 *
 * `useBrandProfile` is mocked so these tests pin the RENDERED STATES rather
 * than react-query's timers; the budget/timeout behaviour of the hook itself is
 * pinned separately by the StageSnapshot suite and the hook's own poll budget.
 *
 * Run: npx vitest run src/components/brand/onboarding/__tests__/onboarding-steps.site-analysis.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import * as React from 'react';

import type { MeeraBrandProfile } from '@/lib/meera-api';

// --- useBrandProfile stub -------------------------------------------------
// Mutable so each test can put the hook in one state before rendering.
const restartAnalysisPoll = vi.fn();
let hookState: {
  brandProfile: MeeraBrandProfile | null;
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
  analysisTimedOut: boolean;
  restartAnalysisPoll: () => void;
};

vi.mock('@/hooks/useBrandProfile', () => ({
  useBrandProfile: () => hookState,
  default: () => hookState,
}));

const checkSlug = vi.fn().mockResolvedValue({ slug: 'acme', available: true, suggestions: [] });

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    // false → CompanyDetailsStep does NOT auto-mount the live readout, so the
    // disclosure tests below exercise the field in isolation.
    isApiLive: () => false,
    api: {
      auth: {
        sendBrandEmailOtp: vi.fn().mockResolvedValue({ sent: true }),
        verifyBrandEmail: vi.fn().mockResolvedValue({ emailVerified: true }),
      },
      workspaces: { checkSlug: (...args: unknown[]) => checkSlug(...args) },
    },
    ApiError: actual.ApiError,
  };
});

vi.mock('@/hooks/use-toast', () => ({ toast: vi.fn() }));

import {
  CompanyDetailsStep,
  SiteAnalysisStatus,
  initialData,
  type OnboardingData,
} from '../onboarding-steps';

function profile(overrides: Partial<MeeraBrandProfile> = {}): MeeraBrandProfile {
  return {
    workspaceId: 'ws_1',
    websiteUrl: 'https://www.acme.in/shop',
    analysisStatus: 'PENDING',
    nicheTags: null,
    productCatalog: null,
    analysisError: null,
    ...overrides,
  };
}

function setHook(overrides: Partial<typeof hookState> = {}) {
  hookState = {
    brandProfile: null,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    analysisTimedOut: false,
    restartAnalysisPoll,
    ...overrides,
  };
}

/** CompanyDetailsStep is controlled — hold its data so typing is observable. */
function CompanyStepHarness({ initial }: { initial?: Partial<OnboardingData> }) {
  const [data, setData] = React.useState<OnboardingData>({ ...initialData, ...initial });
  return (
    <MemoryRouter>
      <CompanyDetailsStep
        data={data}
        onUpdate={(updates) => setData((prev) => ({ ...prev, ...updates }))}
        onNext={vi.fn()}
        onBack={vi.fn()}
      />
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  setHook();
});

describe('CompanyDetailsStep — website field tells the brand we will read the site', () => {
  it('promises nothing until a link is given', () => {
    render(<CompanyStepHarness />);
    expect(screen.getByText(/read your site to pre-fill your products/i)).toBeInTheDocument();
  });

  it('names the site and the no-JavaScript limitation once a link is entered', async () => {
    const user = userEvent.setup();
    render(<CompanyStepHarness />);

    await user.type(screen.getByLabelText(/website/i), 'https://www.acme.in/shop');

    // Honest about WHAT we are about to do, to WHICH host, and how long.
    expect(screen.getByText(/read acme\.in to pull in your products/i)).toBeInTheDocument();
    // Honest about the known limitation (plain GET, no JS rendering) rather
    // than letting a client-rendered storefront look broken later.
    expect(screen.getByText(/without running its scripts/i)).toBeInTheDocument();
  });
});

describe('SiteAnalysisStatus — the analysing state', () => {
  it('says we are reading the site, naming the host', () => {
    setHook({ brandProfile: profile({ analysisStatus: 'ANALYZING' }) });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.getByText(/Reading acme\.in now/i)).toBeInTheDocument();
  });

  it('treats PENDING-with-a-URL as reading too (the trigger has fired, the job has not started)', () => {
    setHook({ brandProfile: profile({ analysisStatus: 'PENDING' }) });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    expect(screen.getByText(/Reading acme\.in now/i)).toBeInTheDocument();
  });

  it('renders nothing at all when there is no profile to report on', () => {
    setHook({ brandProfile: null });
    const { container } = render(<SiteAnalysisStatus enteredUrl="" />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('SiteAnalysisStatus — the success state', () => {
  it('reports how many products we actually found', () => {
    setHook({
      brandProfile: profile({
        analysisStatus: 'READY',
        productCatalog: [
          { name: 'Neem Face Wash', price: 349 },
          { name: 'Ubtan Scrub', price: 499 },
          { name: 'Kumkumadi Oil', price: 1299 },
        ],
      }),
    });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    expect(screen.getByText(/We read acme\.in and found 3 products\./i)).toBeInTheDocument();
  });

  it('says plainly when it read the page and found nothing, and why that can happen', () => {
    setHook({ brandProfile: profile({ analysisStatus: 'READY', productCatalog: [] }) });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    expect(screen.getByText(/couldn't find any products on the page/i)).toBeInTheDocument();
    expect(screen.getByText(/without running its scripts/i)).toBeInTheDocument();
  });
});

describe('SiteAnalysisStatus — the poll-exhausted terminal state', () => {
  it('says we stopped waiting, instead of leaving the brand in silence', () => {
    setHook({
      brandProfile: profile({ analysisStatus: 'PENDING' }),
      analysisTimedOut: true,
    });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    // Terminal wording — not "still reading", which is what a silent give-up
    // left on screen forever.
    expect(screen.getByText(/We stopped waiting on acme\.in/i)).toBeInTheDocument();
    expect(screen.queryByText(/Reading acme\.in now/i)).not.toBeInTheDocument();
  });

  it('offers a way forward: a re-check control and the manual route', async () => {
    const user = userEvent.setup();
    setHook({
      brandProfile: profile({ analysisStatus: 'PENDING' }),
      analysisTimedOut: true,
    });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    const recheck = screen.getByRole('button', { name: /check again/i });
    await user.click(recheck);
    expect(restartAnalysisPoll).toHaveBeenCalledTimes(1);

    // ...and tells them they can just enter the details themselves.
    expect(screen.getByText(/add your products yourself at any time/i)).toBeInTheDocument();
  });

  it('gives a failed analysis the same honest treatment rather than a bare error code', () => {
    setHook({
      brandProfile: profile({
        analysisStatus: 'ERROR',
        analysisError: 'no readable content found',
      }),
    });
    render(<SiteAnalysisStatus enteredUrl="https://www.acme.in/shop" />);

    expect(screen.getByText(/We couldn't read acme\.in/i)).toBeInTheDocument();
    expect(screen.getByText(/no readable content found/i)).toBeInTheDocument();
    expect(screen.getByText(/without running its scripts/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /check again/i })).toBeInTheDocument();
  });
});
