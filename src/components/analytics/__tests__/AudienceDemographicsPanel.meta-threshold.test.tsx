/**
 * F-0953 — a creator with no demographics snapshot saw only "after the first weekly sync", with no
 * hint that Instagram shares audience data only for accounts with 100+ followers, so a small
 * account looked broken forever (AudienceDemographicsJob skips accounts below that threshold).
 *
 * Run: npx vitest run src/components/analytics/__tests__/AudienceDemographicsPanel.meta-threshold.test.tsx
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { AudienceDemographicsPanel } from '../AudienceDemographicsPanel';

describe('AudienceDemographicsPanel — empty state (F-0953)', () => {
  it("names Instagram's 100-follower rule when there is no snapshot", () => {
    render(<AudienceDemographicsPanel data={null} />);

    expect(screen.getByText('No demographics snapshot yet')).toBeInTheDocument();
    expect(screen.getByText(/100 or more followers/i)).toBeInTheDocument();
  });

  it('shows the same explanation for the production empty shape ({ hasData: false })', () => {
    // AnalyticsService returns an empty snapshot object rather than a 404.
    render(
      <AudienceDemographicsPanel
        data={{ hasData: false } as unknown as Parameters<typeof AudienceDemographicsPanel>[0]['data']}
      />,
    );

    expect(screen.getByText(/100 or more followers/i)).toBeInTheDocument();
  });
});
