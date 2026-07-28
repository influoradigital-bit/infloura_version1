/**
 * ErrorBoundary — CR-11 crash-report wiring
 * ----------------------------------------------------------------------------
 * CR-11 (wiki/tech/cr-11-client-error-contract.md) was BLOCKED for four passes waiting
 * for a human to catch `[ErrorBoundary] Uncaught render error: …` in a console at the
 * moment of blanking. The actual defect was that the app could not report its own
 * crashes. These tests cover the reporting path this ticket adds:
 *
 *   1. A crash triggers exactly one `api.clientErrors.report` call, with the field the
 *      contract calls out as "the field that names the throw site" — `componentStack` —
 *      actually present.
 *   2. The same crash twice (re-render after "Try again") reports once, not twice —
 *      proving the per-session dedupe on `message + pathname`.
 *   3. A rejecting AND a throwing `report` call do not break the fallback render —
 *      proving the never-throw guard around the fire-and-forget call.
 *
 * `@/lib/api` is mocked the same way as FundEscrowButton.test.tsx: spread the real
 * module, override just `clientErrors.report`. Every other export (the `api` object's
 * other resources, `ApiError`, etc.) stays real.
 *
 * Run: npx vitest run src/components/ErrorBoundary.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// `vi.hoisted` because `vi.mock`'s factory below is hoisted to the top of the file by
// vitest — a plain `const reportMock = ...` above it would still be a temporal-dead-zone
// reference at the time the factory actually runs.
const { reportMock } = vi.hoisted(() => ({ reportMock: vi.fn().mockResolvedValue(undefined) }));

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    default: {
      ...actual.default,
      clientErrors: {
        ...actual.default.clientErrors,
        report: reportMock,
      },
    },
  };
});

import ErrorBoundary from './ErrorBoundary';

/** Throws unconditionally on render, with a caller-chosen message so each test can
 *  control the `message + pathname` dedupe key independently. */
function Bomb({ message = 'kaboom' }: { message?: string }): never {
  throw new Error(message);
}

function setPathname(pathname: string): void {
  window.history.pushState({}, '', pathname);
}

describe('ErrorBoundary — CR-11 crash reporting', () => {
  beforeEach(() => {
    reportMock.mockClear();
    reportMock.mockResolvedValue(undefined);
    setPathname('/campaigns');
  });

  it('reports exactly one crash, carrying componentStack (the throw-site field)', () => {
    render(
      <ErrorBoundary>
        <Bomb message="render exploded" />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(reportMock).toHaveBeenCalledTimes(1);
    const payload = reportMock.mock.calls[0][0];
    expect(payload.message).toBe('render exploded');
    expect(payload.pathname).toBe('/campaigns');
    expect(typeof payload.componentStack).toBe('string');
    expect(payload.componentStack).toContain('Bomb');
  });

  it('dedupes the same crash (same message + pathname) to a single report', () => {
    const { unmount } = render(
      <ErrorBoundary>
        <Bomb message="same crash" />
      </ErrorBoundary>,
    );
    expect(reportMock).toHaveBeenCalledTimes(1);
    unmount();

    // A fresh boundary instance, same route, same message — e.g. the user hit "Try
    // again" and the same deterministic bug threw again. The dedupe set is
    // module-level (per session), so a brand-new component instance still sees it.
    render(
      <ErrorBoundary>
        <Bomb message="same crash" />
      </ErrorBoundary>,
    );
    expect(reportMock).toHaveBeenCalledTimes(1);
  });

  it('does NOT dedupe a different message on the same pathname', () => {
    render(
      <ErrorBoundary>
        <Bomb message="crash A" />
      </ErrorBoundary>,
    );
    render(
      <ErrorBoundary>
        <Bomb message="crash B" />
      </ErrorBoundary>,
    );
    expect(reportMock).toHaveBeenCalledTimes(2);
  });

  it('does NOT dedupe the same message on a different pathname', () => {
    setPathname('/deals/42');
    render(
      <ErrorBoundary>
        <Bomb message="cross-route crash" />
      </ErrorBoundary>,
    );
    setPathname('/settings');
    render(
      <ErrorBoundary>
        <Bomb message="cross-route crash" />
      </ErrorBoundary>,
    );
    expect(reportMock).toHaveBeenCalledTimes(2);
  });

  it('still renders the fallback when the report call REJECTS', () => {
    reportMock.mockRejectedValueOnce(new Error('network down'));

    render(
      <ErrorBoundary>
        <Bomb message="reject case" />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(reportMock).toHaveBeenCalledTimes(1);
  });

  it('still renders the fallback when the report call THROWS synchronously', () => {
    reportMock.mockImplementationOnce(() => {
      throw new Error('synchronous boom');
    });

    render(
      <ErrorBoundary>
        <Bomb message="throw case" />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
  });

  it('"Try again" still works after a report failure', async () => {
    const user = userEvent.setup();
    reportMock.mockRejectedValueOnce(new Error('network down'));

    render(
      <ErrorBoundary>
        <Bomb message="retry after failure" />
      </ErrorBoundary>,
    );

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    // The bomb still throws on retry (deterministic bug) — the fallback must still be
    // the thing on screen, not a dead app, and clicking must not have thrown out of
    // React's event handler.
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
  });
});
