/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.4, §8.10, B0-37) — `DealRiskCard`.
 *
 * Pins the three things about this card that a type-checker cannot see:
 *   1. flags render worst-first, whatever order the server happened to send them in;
 *   2. a flag with `dismissible: false` has NO dismiss control — not a disabled one;
 *   3. an ABSENT `flags` key (the `@JsonInclude(NON_NULL)` shape, `undefined` not `null`) renders
 *      nothing instead of throwing inside `.map()`.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { RiskFlag } from '@/lib/api';
import { DealRiskCard } from './deal-risk-card';

function flag(overrides: Partial<RiskFlag> & Pick<RiskFlag, 'code' | 'severity'>): RiskFlag {
  return {
    title: `${overrides.code} title`,
    detail: `${overrides.code} detail`,
    action: `${overrides.code} action`,
    data: {},
    dismissible: true,
    ...overrides,
  };
}

describe('DealRiskCard', () => {
  it('orders rows CRITICAL, WARN, INFO regardless of the order they arrive in', () => {
    render(
      <DealRiskCard
        flags={[
          flag({ code: 'INFO_RULE', severity: 'INFO' }),
          flag({ code: 'CRITICAL_RULE', severity: 'CRITICAL' }),
          flag({ code: 'WARN_RULE', severity: 'WARN' }),
        ]}
      />,
    );

    const rows = screen.getAllByTestId('deal-risk-row');
    expect(rows.map((row) => row.getAttribute('data-severity'))).toEqual([
      'CRITICAL',
      'WARN',
      'INFO',
    ]);
  });

  it('gives a non-dismissible flag no dismiss control at all, not a disabled one', () => {
    const onDismiss = vi.fn();
    render(
      <DealRiskCard
        onDismiss={onDismiss}
        flags={[
          flag({ code: 'OFF_PLATFORM_PAYMENT', severity: 'CRITICAL', dismissible: false }),
          flag({ code: 'EXCLUSIVITY_LONG', severity: 'WARN', dismissible: true }),
        ]}
      />,
    );

    // Exactly one control, and it belongs to the dismissible flag.
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveAccessibleName('Dismiss EXCLUSIVITY_LONG title');

    // The non-dismissible flag's row renders — it just carries no control, disabled or otherwise.
    expect(screen.getByText('OFF_PLATFORM_PAYMENT title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /OFF_PLATFORM_PAYMENT/ })).not.toBeInTheDocument();
    expect(buttons.some((b) => b.hasAttribute('disabled'))).toBe(false);
  });

  it('renders no dismiss control at all when no onDismiss handler is supplied', () => {
    render(<DealRiskCard flags={[flag({ code: 'EXCLUSIVITY_LONG', severity: 'WARN' })]} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('puts critical text on the destructive surface as text-destructive-foreground', () => {
    // The theme is pale-surface / strong-foreground: --destructive is #ffe5e5, so
    // `text-destructive` on `bg-destructive` is effectively invisible.
    render(<DealRiskCard flags={[flag({ code: 'BELOW_FLOOR', severity: 'CRITICAL' })]} />);
    const severity = screen.getByTestId('deal-risk-severity');
    expect(severity).toHaveClass('bg-destructive');
    expect(severity).toHaveClass('text-destructive-foreground');
    expect(severity.className).not.toMatch(/(^|\s)text-destructive(\s|$)/);
  });

  it('renders the cost line only when the rule sent one', () => {
    render(
      <DealRiskCard
        flags={[
          flag({ code: 'WITH_COST', severity: 'WARN', cost: '≈ 6,000 of lost income' }),
          flag({ code: 'NO_COST', severity: 'INFO' }),
        ]}
      />,
    );
    expect(screen.getByText('≈ 6,000 of lost income')).toBeInTheDocument();
    expect(screen.getAllByTestId('deal-risk-row')).toHaveLength(2);
  });

  it('U-3: says how many flags are hidden for this session and offers them back', () => {
    const onRestoreHidden = vi.fn();
    // Every flag hidden: the card must still render, or the creator could never get them back.
    render(<DealRiskCard flags={[]} hiddenCount={2} onRestoreHidden={onRestoreHidden} />);

    expect(screen.getByTestId('deal-risk-hidden-note')).toHaveTextContent(
      '2 flags hidden for this session.',
    );
    screen.getByRole('button', { name: 'Show hidden flags' }).click();
    expect(onRestoreHidden).toHaveBeenCalledTimes(1);
  });

  it('renders nothing — and does not throw — when flags is absent or empty', () => {
    // `flags` arrives `undefined`, never `null`: CheckDealRisksResult is @JsonInclude(NON_NULL),
    // so an absent list is an absent KEY. A `.map()` here would throw at runtime and `tsc` would
    // not have caught it.
    const absent = render(<DealRiskCard />);
    expect(absent.container).toBeEmptyDOMElement();

    const empty = render(<DealRiskCard flags={[]} />);
    expect(empty.container.querySelector('[data-testid="deal-risk-card"]')).toBeNull();
  });
});
