/**
 * FirstRunChecklist — the two honesty rules the component exists to hold.
 *
 *   1. A step whose completion could not be determined (`done: null`) is NEVER rendered as done,
 *      and is excluded from the "N of M" denominator. Both failure directions are defects: a
 *      `null` treated as `true` congratulates the user for work they may not have done; folded
 *      into the denominator as `false`, it reports a progress fraction the data does not support.
 *   2. The ladder retires itself only when every step is PROVED done. One undeterminable step is
 *      enough to keep it up — "probably finished" is not finished.
 *
 * Run: npx vitest run src/components/shared/__tests__/FirstRunChecklist.test.tsx
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { FirstRunChecklist, type FirstRunStep } from '../FirstRunChecklist';

const STORAGE_KEY = 'test_first_run_dismissed';

function step(over: Partial<FirstRunStep> & Pick<FirstRunStep, 'id' | 'done'>): FirstRunStep {
  return {
    title: `Step ${over.id}`,
    subtitle: `Do the ${over.id} thing`,
    href: `/${over.id}`,
    cta: 'Go',
    ...over,
  };
}

function renderChecklist(steps: FirstRunStep[]) {
  return render(
    <MemoryRouter>
      <FirstRunChecklist
        title="Get started"
        subtitle="A few steps"
        steps={steps}
        storageKey={STORAGE_KEY}
      />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
});

describe('FirstRunChecklist — undeterminable steps', () => {
  it('leaves a null step out of the denominator and names the omission', () => {
    renderChecklist([
      step({ id: 'a', done: true }),
      step({ id: 'b', done: false }),
      step({ id: 'c', done: null }),
    ]);
    // 1 of 2 — not "1 of 3", which would report progress against a step nobody checked.
    expect(screen.getByText(/1 of 2/)).toBeInTheDocument();
    expect(screen.getByText(/couldn’t be checked/)).toBeInTheDocument();
  });

  it('does not mark a null step as done', () => {
    renderChecklist([step({ id: 'a', done: null })]);
    // The done affordance is the strikethrough on the title; a null step must not carry it.
    const title = screen.getByText('Step a');
    expect(title.className).not.toContain('line-through');
    expect(screen.getByText(/0 of 0/)).toBeInTheDocument();
  });

  it('keeps a null step actionable — it is the active step when everything before it is done', () => {
    renderChecklist([step({ id: 'a', done: true }), step({ id: 'b', done: null, cta: 'Connect' })]);
    expect(screen.getByText('Connect')).toBeInTheDocument();
  });
});

describe('FirstRunChecklist — retirement', () => {
  it('renders nothing once every step is proved done', () => {
    const { container } = renderChecklist([
      step({ id: 'a', done: true }),
      step({ id: 'b', done: true }),
    ]);
    expect(container).toBeEmptyDOMElement();
  });

  it('stays up when a step is undeterminable, even if every other step is done', () => {
    renderChecklist([step({ id: 'a', done: true }), step({ id: 'b', done: null })]);
    expect(screen.getByText('Get started')).toBeInTheDocument();
  });

  it('renders nothing when the user has already dismissed it', () => {
    localStorage.setItem(STORAGE_KEY, 'true');
    const { container } = renderChecklist([step({ id: 'a', done: false })]);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for an empty step list rather than an empty card', () => {
    const { container } = renderChecklist([]);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('FirstRunChecklist — the active step', () => {
  it('puts the CTA on the first step that is not done, not on the first step overall', () => {
    renderChecklist([
      step({ id: 'a', done: true, cta: 'First' }),
      step({ id: 'b', done: false, cta: 'Second' }),
      step({ id: 'c', done: false, cta: 'Third' }),
    ]);
    expect(screen.getByText('Second')).toBeInTheDocument();
    expect(screen.queryByText('First')).not.toBeInTheDocument();
    expect(screen.queryByText('Third')).not.toBeInTheDocument();
  });

  it('shows the subtitle only for the active step, so the card is not a wall of text', () => {
    renderChecklist([step({ id: 'a', done: false }), step({ id: 'b', done: false })]);
    expect(screen.getByText('Do the a thing')).toBeInTheDocument();
    expect(screen.queryByText('Do the b thing')).not.toBeInTheDocument();
  });

  it('every step is reachable — no row is a dead control (F-0341)', () => {
    renderChecklist([
      step({ id: 'a', done: true }),
      step({ id: 'b', done: false }),
      step({ id: 'c', done: null }),
    ]);
    for (const id of ['a', 'b', 'c']) {
      const row = screen.getByText(`Step ${id}`).closest('button');
      expect(row).not.toBeNull();
      expect(row).toBeEnabled();
    }
  });
});
