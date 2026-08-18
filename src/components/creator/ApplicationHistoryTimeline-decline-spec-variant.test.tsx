/**
 * ApplicationHistoryTimeline — decline-wording "spec" variant (F-0287/F-0303, Decision 1).
 *
 * CEO ruling 2026-08-18 (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md) UPHOLDS "Closed" as
 * the shipped default (see the main ApplicationHistoryTimeline.test.tsx's F-0303 test, which
 * pins that exact behaviour) but explicitly authorizes a customer override to the requirements
 * document's literal "Rejected" wording, and asks that BOTH variants be fully built —
 * src/lib/application-status.ts's DECLINE_WORDING map — so the switch is a one-line change to
 * DECLINE_WORDING_VARIANT (in decline-wording-variant.ts), not a hunt through label maps.
 *
 * What that one-line flip actually costs, so whoever eventually makes it knows up front: flipping
 * DECLINE_WORDING_VARIANT to 'spec' is correctly a source change, and every test in
 * ApplicationHistoryTimeline.test.tsx that pins the CURRENT default (the F-0303 test plus three
 * others asserting "Closed") will go red — that is expected, correct behaviour for a test that
 * pins a specific default, not a defect. Whoever makes that flip needs to update those tests'
 * expectations to "Rejected"/"Application Rejected" in the same change. THIS file's test does not
 * need updating either way — it forces 'spec' itself and asserts spec's wording regardless of
 * whatever the shipped default currently is.
 *
 * This file is the mirror of that default-variant test: it forces the "spec" variant (by
 * mocking application-status.ts's resolved wording, the same one-line change DECLINE_WORDING_VARIANT
 * itself would make) and asserts the component actually renders the explicit wording — proving
 * the wiring in ApplicationHistoryTimeline.tsx follows the shared constant rather than a second,
 * hardcoded copy of "Closed" that would silently ignore the switch.
 *
 * This file's own module registry is isolated from ApplicationHistoryTimeline.test.tsx's (each
 * test file gets a fresh one in Vitest), so mocking '@/lib/application-status' here cannot leak
 * into — or be affected by — that file's unmocked, real-module assertions.
 *
 * Run: npx vitest run src/components/creator/ApplicationHistoryTimeline-decline-spec-variant.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { CreatorApplicationHistoryEvent } from '@/lib/api';

const historyMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorApplications: {
        ...actual.api.creatorApplications,
        history: (...a: unknown[]) => historyMock(...a),
      },
    },
  };
});

// Forces the 'spec' variant for this file only — the real DECLINE_WORDING map is untouched
// (imported via importActual), so this is exactly the resolved value DECLINE_WORDING_VARIANT =
// 'spec' would produce, not an invented shape.
vi.mock('@/lib/application-status', async () => {
  const actual = await vi.importActual<typeof import('@/lib/application-status')>('@/lib/application-status');
  return {
    ...actual,
    getDeclineWording: () => actual.DECLINE_WORDING.spec,
    getApplicationStatusLabel: (status: string) =>
      status === 'CANCELLED' ? actual.DECLINE_WORDING.spec.statusLabel : actual.getApplicationStatusLabel(status),
  };
});

// Imported after the mocks above so the component picks up the mocked module on first load.
import { ApplicationHistoryTimeline } from './ApplicationHistoryTimeline';

function renderTimeline(dealId = 'deal_1', brandName = 'Glow Naturals') {
  return render(
    <MemoryRouter>
      <ApplicationHistoryTimeline dealId={dealId} brandName={brandName} />
    </MemoryRouter>,
  );
}

const EVENT_REJECTED: CreatorApplicationHistoryEvent = {
  historyId: 'h_10',
  campaignId: 'camp_1',
  applicationId: 'app_1',
  eventType: 'APPLICATION_REJECTED',
  eventStatus: 'CANCELLED',
  actorType: 'BRAND',
  actorId: 'brand_1',
  description: 'This application was closed by the brand: Went with another creator',
  createdAt: '2026-08-04T10:00:00.000Z',
  metadata: 'Went with another creator',
  dealPhase: null,
};

describe('ApplicationHistoryTimeline — "spec" decline-wording variant override', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the explicit "Application Rejected" event label and "Rejected" status badge, not "Closed"', async () => {
    historyMock.mockResolvedValue([EVENT_REJECTED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Application Rejected')).toBeInTheDocument());
    expect(screen.getByText('Rejected')).toBeInTheDocument();
    // The two surfaces must agree with each other under this variant too — no "Closed" anywhere.
    expect(screen.queryByText('Closed')).not.toBeInTheDocument();
  });
});
