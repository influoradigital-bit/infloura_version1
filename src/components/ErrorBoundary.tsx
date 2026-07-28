import React from 'react';
import api from '@/lib/api';

/**
 * CR-11 — per-session dedupe for crash reports, keyed on `message + pathname`
 * (wiki/tech/cr-11-client-error-contract.md, frontend rule 3: "a render loop must not
 * become a self-inflicted request flood").
 *
 * Module-level, not component state: it must survive CR-10's `resetKey`-driven
 * `hasError` reset (a fresh `componentDidCatch` on the same route after "Try again" is
 * still the same crash) and it must cover every `ErrorBoundary` instance mounted during
 * this session, not just one. It only clears on a real page reload, which is the correct
 * boundary for "session" here — this is an SPA, so there is no other natural reset point.
 */
const reportedCrashKeys = new Set<string>();

interface ErrorBoundaryProps {
  children: React.ReactNode;
  /**
   * CR-10 — when this value changes while the boundary is tripped, the error
   * state clears and `children` re-render. `RoutedErrorBoundary` in App.tsx
   * passes the current pathname, so navigating away from a broken page is
   * enough to recover.
   *
   * Deliberately a prop and not React's `key`: keying the boundary would
   * remount the entire subtree on every navigation, throwing away all
   * component state on healthy routes to fix a case that almost never fires.
   */
  resetKey?: string | number;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

/**
 * Render error boundary.
 *
 * Zero-dependency (no `react-error-boundary`) class component — React only
 * supports catching render/lifecycle errors via componentDidCatch /
 * getDerivedStateFromError on a class component, there is no hooks
 * equivalent. Catches any uncaught throw in the wrapped subtree and renders
 * a minimal branded fallback instead of leaving a white screen.
 *
 * CR-10 — this used to be mounted OUTSIDE `<BrowserRouter>` with no way to
 * clear `hasError`. One transient throw on one page therefore tore down the
 * whole Router permanently: every subsequent tab click rendered the same dead
 * fallback because the routing tree no longer existed. That is the mechanism
 * behind the reported "after that, every other tab goes white". It is now
 * mounted inside the Router and resets on navigation (`resetKey`).
 */
export default class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary] Uncaught render error:', error, errorInfo);
    this.reportCrash(error, errorInfo);
  }

  /**
   * CR-11 — fire-and-forget crash report to `POST /api/v1/client-errors`
   * (wiki/tech/cr-11-client-error-contract.md). Every one of the contract's five
   * "frontend rules (non-negotiable)" is implemented right here:
   *
   * 1. Never throws — the whole body is wrapped in try/catch. A throw inside
   *    `componentDidCatch` itself is unrecoverable: there is no boundary around the
   *    boundary, so this must not become the thing that finally takes the tab down.
   * 2. Never blocks the fallback render — this is called from `componentDidCatch`
   *    (a side effect), never awaited, and the render below does not depend on it.
   * 3. Deduplicated per session on `message + pathname` via the module-level
   *    `reportedCrashKeys` set, so a render loop that keeps re-throwing the same error
   *    on the same route reports exactly once instead of once per re-render.
   * 4. The existing `console.error` above is untouched.
   * 5. Failure is silent — both here and in `api.clientErrors.report` itself.
   *
   * `pathname` is read straight off `window.location.pathname` — never `search`/`hash`/
   * `href`, which carry `?deal=<id>` and OAuth callback params (contract, hard
   * requirement).
   */
  private reportCrash(error: Error, errorInfo: React.ErrorInfo): void {
    try {
      const pathname = typeof window !== 'undefined' ? window.location.pathname : '';
      const message = error.message || 'Unknown error';
      // JSON.stringify of the pair, not a plain template-literal join: a plain
      // `${message} ${pathname}` join is collidable (message "a b" @ "/c" produces the
      // same string as message "a" @ "/b c"), which would under-report two distinct
      // crashes as one. JSON-encoding each field keeps them unambiguous regardless of
      // what characters either one contains.
      const dedupeKey = JSON.stringify([message, pathname]);
      if (reportedCrashKeys.has(dedupeKey)) return;
      reportedCrashKeys.add(dedupeKey);

      void api.clientErrors
        .report({
          message,
          stack: error.stack ?? null,
          componentStack: errorInfo.componentStack ?? null,
          pathname,
        })
        .catch(() => {
          // Belt-and-braces: `api.clientErrors.report` already never rejects (contract
          // rule 5), but `componentDidCatch` must never throw under any circumstance,
          // including a future change to that guarantee elsewhere.
        });
    } catch {
      // Rule 1 — componentDidCatch (and everything it calls) must never throw.
    }
  }

  componentDidUpdate(prevProps: ErrorBoundaryProps): void {
    // Only clears on an actual change, and only while tripped — so this can
    // never loop (the setState makes hasError false, failing the guard on the
    // update it triggers).
    if (this.state.hasError && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false });
    }
  }

  handleReload = (): void => {
    window.location.reload();
  };

  /**
   * Re-renders the same route. If the throw was transient (a bad fetch result,
   * a race) this recovers without losing the SPA session; if the route throws
   * deterministically the fallback simply returns, which is honest — the user
   * still has "Reload page" and, because the boundary now sits inside the
   * Router, every other route stays reachable.
   */
  handleRetry = (): void => {
    this.setState({ hasError: false });
  };

  render(): React.ReactNode {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen w-full flex-col items-center justify-center gap-4 bg-background px-6 text-center text-foreground">
          <h1 className="text-2xl font-semibold">Something went wrong</h1>
          <p className="max-w-md text-sm text-muted-foreground">
            An unexpected error occurred on this page. The rest of the app is
            still working — go back and try again, or reload if it persists.
          </p>
          <div className="flex flex-wrap items-center justify-center gap-3">
            <button
              type="button"
              onClick={this.handleRetry}
              className="inline-flex h-10 items-center justify-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              Try again
            </button>
            <button
              type="button"
              onClick={this.handleReload}
              className="inline-flex h-10 items-center justify-center rounded-md border border-border bg-background px-6 text-sm font-medium text-foreground transition-colors hover:bg-muted"
            >
              Reload page
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
