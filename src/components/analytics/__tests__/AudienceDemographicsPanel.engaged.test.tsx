/**
 * Audience panel: the gender summary line and the creator-only "Engaged this month" section
 * (2026-09-26, owner decisions E and G).
 *
 * - Gender line: each gender's share of the WHOLE age/gender breakdown, summed over every age
 *   band, as plain text ("Women 64% · Men 33% · Unknown 3%").
 * - Engaged section: only when the page opts in with `showEngaged` (the creator's own page). A brand
 *   page never shows it, even if a response carried the fields. When Meta returned nothing (under
 *   100 engagements this month) the section says so instead of showing empty bars.
 *
 * Run: npx vitest run src/components/analytics/__tests__/AudienceDemographicsPanel.engaged.test.tsx
 */
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { CreatorDemographics } from '@/lib/types';
import {
  AudienceDemographicsPanel,
  ENGAGED_EMPTY_TEXT,
  ENGAGED_FETCH_FAILED_TEXT,
  ENGAGED_NOT_YET_TEXT,
  genderSummary,
} from '../AudienceDemographicsPanel';

// 1,000 followers over two age bands: women 640, men 330, unknown 30.
const FOLLOWERS: CreatorDemographics = {
  hasData: true,
  ageGenderBreakdown: {
    '18-24_female': 400,
    '25-34_female': 240,
    '18-24_male': 200,
    '25-34_male': 130,
    '18-24_unknown': 30,
  },
  countryBreakdown: { IN: 900, AE: 100 },
  cityBreakdown: { 'Mumbai, Maharashtra': 500, 'Pune, Maharashtra': 500 },
  localeBreakdown: null,
  fetchedAt: '2026-09-24T03:30:00Z',
};

// 200 engaged accounts: women 150, men 50. Delhi only appears in the engaged audience.
const WITH_ENGAGED: CreatorDemographics = {
  ...FOLLOWERS,
  engagedAgeGenderBreakdown: { '18-24_female': 150, '25-34_male': 50 },
  engagedCountryBreakdown: { IN: 200 },
  engagedCityBreakdown: { 'Delhi, Delhi': 120, 'Mumbai, Maharashtra': 80 },
  engagedFetchedAt: '2026-09-25T03:30:00Z',
};

function section(name: string): HTMLElement {
  return screen.getByRole('region', { name });
}

describe('genderSummary', () => {
  it('sums every age band and divides by the whole breakdown', () => {
    expect(genderSummary(FOLLOWERS.ageGenderBreakdown)).toBe('Women 64% · Men 33% · Unknown 3%');
  });

  it('leaves out a gender with no one in it, and counts an unrecognised key as Unknown', () => {
    expect(genderSummary({ '18-24_female': 3, '25-34_female': 1 })).toBe('Women 100%');
    expect(genderSummary({ '18-24_male': 1, '35+_other': 1 })).toBe('Men 50% · Unknown 50%');
  });

  it('is null for an empty or missing breakdown', () => {
    expect(genderSummary(null)).toBeNull();
    expect(genderSummary(undefined)).toBeNull();
    expect(genderSummary({ '18-24_female': 0 })).toBeNull();
  });
});

describe('AudienceDemographicsPanel gender line', () => {
  it('shows the followers gender line as plain text', () => {
    render(<AudienceDemographicsPanel data={FOLLOWERS} />);
    expect(within(section('Followers')).getByText('Women 64% · Men 33% · Unknown 3%')).toBeInTheDocument();
  });
});

describe('AudienceDemographicsPanel engaged audience', () => {
  it('creator page: an "Engaged this month" section with its own gender line and rows', () => {
    render(<AudienceDemographicsPanel data={WITH_ENGAGED} showEngaged />);

    const engaged = section('Engaged this month');
    expect(within(engaged).getByText('Women 75% · Men 25%')).toBeInTheDocument();
    // Delhi is 120 of 200 engaged accounts = 60%; it is not in the followers breakdown at all.
    const delhi = within(engaged).getByText('Delhi, Delhi').closest('[role="listitem"]');
    expect(delhi?.textContent).toContain('(60%)');
    expect(within(section('Followers')).queryByText('Delhi, Delhi')).toBeNull();
    expect(within(engaged).queryByText(ENGAGED_EMPTY_TEXT)).toBeNull();
  });

  // Fix round 2026-09-26 (checker + Priya): a snapshot written before the engaged fetch existed has
  // engaged_status NULL until the next weekly job. That is "not fetched yet" (what Meera says,
  // CreatorAudienceShares.ENGAGED_NOT_YET), never the under-100-engagements claim.
  it('creator page, older response without the engaged fields or a status: not fetched yet, no bars', () => {
    render(<AudienceDemographicsPanel data={FOLLOWERS} showEngaged />);

    const engaged = section('Engaged this month');
    expect(within(engaged).getByText(ENGAGED_NOT_YET_TEXT)).toBeInTheDocument();
    expect(within(engaged).queryByText(ENGAGED_EMPTY_TEXT)).toBeNull();
    expect(within(engaged).queryByRole('listitem')).toBeNull();
  });

  it('creator page, engagedStatus null (row written before the engaged fetch): not fetched yet', () => {
    render(
      <AudienceDemographicsPanel
        data={{
          ...FOLLOWERS,
          engagedAgeGenderBreakdown: null,
          engagedCountryBreakdown: null,
          engagedCityBreakdown: null,
          engagedFetchedAt: null,
          engagedStatus: null,
        }}
        showEngaged
      />,
    );
    const engaged = section('Engaged this month');
    expect(within(engaged).getByText(ENGAGED_NOT_YET_TEXT)).toBeInTheDocument();
    expect(within(engaged).queryByText(ENGAGED_EMPTY_TEXT)).toBeNull();
    expect(ENGAGED_NOT_YET_TEXT).toBe("Not fetched yet. It arrives with the next weekly Instagram update.");
  });

  it('creator page, engagedStatus BELOW_THRESHOLD: the under-100-engagements text', () => {
    render(
      <AudienceDemographicsPanel
        data={{
          ...FOLLOWERS,
          engagedAgeGenderBreakdown: {},
          engagedCountryBreakdown: null,
          engagedCityBreakdown: null,
          engagedFetchedAt: '2026-09-25T03:30:00Z',
          engagedStatus: 'BELOW_THRESHOLD',
        }}
        showEngaged
      />,
    );
    const engaged = section('Engaged this month');
    expect(within(engaged).getByText(ENGAGED_EMPTY_TEXT)).toBeInTheDocument();
    expect(within(engaged).queryByText(ENGAGED_NOT_YET_TEXT)).toBeNull();
    // Meta's threshold is 100 engagements, not 100 people (the Java copy and the persona agree).
    expect(ENGAGED_EMPTY_TEXT).toBe('Shown once your posts get at least 100 engagements in a month');
  });

  it('creator page, engagedStatus FETCH_FAILED: says the check failed, never the 100-engagement reason', () => {
    render(
      <AudienceDemographicsPanel
        data={{
          ...FOLLOWERS,
          engagedAgeGenderBreakdown: null,
          engagedCountryBreakdown: null,
          engagedCityBreakdown: null,
          engagedFetchedAt: null,
          engagedStatus: 'FETCH_FAILED',
        }}
        showEngaged
      />,
    );
    const engaged = section('Engaged this month');
    expect(within(engaged).getByText(ENGAGED_FETCH_FAILED_TEXT)).toBeInTheDocument();
    expect(within(engaged).queryByText(ENGAGED_EMPTY_TEXT)).toBeNull();
  });

  it('brand page (no showEngaged): nothing about the engaged audience, even if the response carried it', () => {
    render(<AudienceDemographicsPanel data={WITH_ENGAGED} />);

    expect(screen.queryByText('Engaged this month')).toBeNull();
    expect(screen.queryByText(ENGAGED_EMPTY_TEXT)).toBeNull();
    expect(screen.queryByText('Delhi, Delhi')).toBeNull();
    expect(screen.queryByText('Women 75% · Men 25%')).toBeNull();
  });

  it('no follower snapshot yet: the brand page keeps the no-snapshot empty state', () => {
    render(<AudienceDemographicsPanel data={{ ...WITH_ENGAGED, hasData: false }} />);
    expect(screen.getByText('No demographics snapshot yet')).toBeInTheDocument();
  });
});
