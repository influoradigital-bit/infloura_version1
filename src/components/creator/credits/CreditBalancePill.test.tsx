import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CreditBalancePill, deriveCreditBalancePillState } from './CreditBalancePill';
import type { CreatorCreditBalance } from '@/lib/api';

function balance(overrides: Partial<CreatorCreditBalance>): CreatorCreditBalance {
  return {
    enabled: true,
    total: 55,
    free: 40,
    paid: 15,
    dailyUsed: 3,
    dailyCap: 30,
    ...overrides,
  };
}

describe('CreditBalancePill (A46 — states and flag-off)', () => {
  it('renders nothing when balance is null (still loading / fetch failed)', () => {
    const { container } = render(<CreditBalancePill balance={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when enabled is false', () => {
    const { container } = render(<CreditBalancePill balance={{ enabled: false }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('normal state: "{n} credits" in English', () => {
    render(<CreditBalancePill balance={balance({ total: 55 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAttribute('data-state', 'normal');
    expect(screen.getByText('55 credits')).toBeInTheDocument();
  });

  it('normal state in Hindi', () => {
    render(<CreditBalancePill balance={balance({ total: 55 })} language="hi-IN" />);
    expect(screen.getByText('55 क्रेडिट्स')).toBeInTheDocument();
  });

  it('low state: total <= 5 shows "{n} left"', () => {
    render(<CreditBalancePill balance={balance({ total: 3 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAttribute('data-state', 'low');
    expect(screen.getByText('3 left')).toBeInTheDocument();
  });

  it('zero state: total 0 shows "0 credits" regardless of language key text', () => {
    render(<CreditBalancePill balance={balance({ total: 0, free: 0, paid: 0 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAttribute('data-state', 'zero');
    expect(screen.getByText('0 credits')).toBeInTheDocument();
  });

  it('cap state: dailyUsed >= dailyCap shows "30/30 today" even with a nonzero balance', () => {
    render(<CreditBalancePill balance={balance({ total: 55, dailyUsed: 30, dailyCap: 30 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAttribute('data-state', 'cap');
    expect(screen.getByText('30/30 today')).toBeInTheDocument();
  });

  it('cap takes priority over zero when both conditions hold', () => {
    const state = deriveCreditBalancePillState(balance({ total: 0, dailyUsed: 30, dailyCap: 30 }));
    expect(state).toBe('cap');
  });

  it('calls onClick when tapped', () => {
    let clicked = false;
    render(<CreditBalancePill balance={balance({})} onClick={() => (clicked = true)} />);
    screen.getByTestId('credit-balance-pill').click();
    expect(clicked).toBe(true);
  });
});

describe('CreditBalancePill — findable "buy more" affordance (F-buy-credits-findable)', () => {
  it('shows a "+" icon, hidden from assistive tech (the accessible name already says the action)', () => {
    render(<CreditBalancePill balance={balance({ total: 55 })} language="en" />);
    const plus = screen.getByTestId('credit-balance-pill-plus');
    expect(plus).toBeInTheDocument();
    expect(plus).toHaveAttribute('aria-hidden', 'true');
  });

  it('names the tap action in the accessible name — "{n} credits — buy more" (normal state)', () => {
    render(<CreditBalancePill balance={balance({ total: 36 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAccessibleName('36 credits — buy more');
  });

  it('bilingual: Hindi accessible name uses the Hindi label and "buy more" phrase', () => {
    render(<CreditBalancePill balance={balance({ total: 36 })} language="hi-IN" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAccessibleName('36 क्रेडिट्स — और खरीदें');
  });

  it('low state: accessible name is "{n} left — buy more"', () => {
    render(<CreditBalancePill balance={balance({ total: 3 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAccessibleName('3 left — buy more');
  });

  it('zero state: accessible name appends "— buy more" to the full explanatory sentence, not just "0 credits"', () => {
    render(<CreditBalancePill balance={balance({ total: 0, free: 0, paid: 0 })} language="en" />);
    const pill = screen.getByTestId('credit-balance-pill');
    expect(pill).toHaveAccessibleName(/out of credits.*keep chatting\. — buy more/);
  });

  it('cap state: accessible name appends "— buy more" to the cap sentence', () => {
    render(<CreditBalancePill balance={balance({ total: 55, dailyUsed: 30, dailyCap: 30 })} language="en" />);
    const pill = screen.getByTestId('credit-balance-pill');
    expect(pill).toHaveAccessibleName(/resets at midnight.*for tomorrow\. — buy more/);
  });

  it('keeps the h-8 header size', () => {
    render(<CreditBalancePill balance={balance({ total: 55 })} language="en" />);
    expect(screen.getByTestId('credit-balance-pill').className).toMatch(/\bh-8\b/);
  });
});
