/**
 * ThemeProvenanceBadge — Ananya (Trend-Spark recovery-tagger transparency chip).
 *
 * Run: npx vitest run src/components/trendspark/ThemeProvenanceBadge.test.tsx
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvenanceBadge } from './ThemeProvenanceBadge';

describe('ThemeProvenanceBadge', () => {
  it('renders the AI-recovered chip only for AI_RECOVERED', () => {
    render(<ThemeProvenanceBadge source="AI_RECOVERED" />);
    expect(screen.getByText('Spotted by AI')).toBeInTheDocument();
    // Screen-reader description is present for accessibility.
    expect(
      screen.getByText(/recovered by AI after the keyword tagger found no match/i),
    ).toBeInTheDocument();
  });

  it('renders nothing for the ordinary keyword case', () => {
    const { container } = render(<ThemeProvenanceBadge source="KEYWORD" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when provenance is absent (older backend, backward-compatible)', () => {
    const { container } = render(<ThemeProvenanceBadge />);
    expect(container).toBeEmptyDOMElement();
  });
});
