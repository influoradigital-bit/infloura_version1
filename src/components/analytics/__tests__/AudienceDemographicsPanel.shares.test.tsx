/**
 * Audience panel shows each row's share of ALL followers Meta reported (2026-09-24).
 *
 * Dividing by only the rows on screen made the top 6 cities always add up to 100%, whatever the
 * real spread. Also: country codes read as names, and the Locales list (Meta no longer reports
 * follower languages) is hidden rather than shown as an empty "No data" box.
 *
 * Run: npx vitest run src/components/analytics/__tests__/AudienceDemographicsPanel.shares.test.tsx
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { AudienceDemographicsPanel } from '../AudienceDemographicsPanel';

// 8 cities, 1,000 followers; the 6 shown hold 880. Mumbai is 200 of 1,000 = 20%.
const DATA = {
  hasData: true,
  ageGenderBreakdown: { '18-24_female': 300, '18-24_male': 100 },
  countryBreakdown: { IN: 900, AE: 100 },
  cityBreakdown: {
    'Mumbai, Maharashtra': 200,
    'Pune, Maharashtra': 100,
    'Delhi, Delhi': 100,
    'Jaipur, Rajasthan': 80,
    'Indore, Madhya Pradesh': 60,
    'Surat, Gujarat': 60,
    'Nagpur, Maharashtra': 200,
    'Patna, Bihar': 200,
  },
  localeBreakdown: null,
  fetchedAt: '2026-09-24T03:30:00Z',
};

function rowText(label: string): string {
  const row = screen.getByText(label).closest('[role="listitem"]');
  return row?.textContent ?? '';
}

describe('AudienceDemographicsPanel shares', () => {
  it('a city share is of all reported followers, not of the six rows shown', () => {
    render(<AudienceDemographicsPanel data={DATA} />);
    // 200 / 1,000 = 20%. Dividing by the visible rows would say 200 / 880 = 23%.
    expect(rowText('Mumbai, Maharashtra')).toContain('(20%)');
  });

  it('age and gender read "18-24 · Female" with its share of the whole breakdown', () => {
    render(<AudienceDemographicsPanel data={DATA} />);
    expect(rowText('18-24 · Female')).toContain('(75%)');
  });

  it('countries show their names', () => {
    render(<AudienceDemographicsPanel data={DATA} />);
    expect(rowText('India')).toContain('(90%)');
  });

  it('no Locales box when Meta sent no language breakdown', () => {
    render(<AudienceDemographicsPanel data={DATA} />);
    expect(screen.queryByText('Locales')).toBeNull();
  });
});
