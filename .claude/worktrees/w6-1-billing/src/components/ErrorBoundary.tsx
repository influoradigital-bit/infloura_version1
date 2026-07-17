import React from 'react';

interface ErrorBoundaryProps {
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

/**
 * Top-level render error boundary.
 *
 * Zero-dependency (no `react-error-boundary`) class component — React only
 * supports catching render/lifecycle errors via componentDidCatch /
 * getDerivedStateFromError on a class component, there is no hooks
 * equivalent. Catches any uncaught throw in the wrapped subtree and renders
 * a minimal branded fallback instead of leaving a white screen.
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
  }

  handleReload = (): void => {
    window.location.reload();
  };

  render(): React.ReactNode {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen w-full flex-col items-center justify-center gap-4 bg-background px-6 text-center text-foreground">
          <h1 className="text-2xl font-semibold">Something went wrong</h1>
          <p className="max-w-md text-sm text-muted-foreground">
            An unexpected error occurred. Please reload the page to continue.
          </p>
          <button
            type="button"
            onClick={this.handleReload}
            className="inline-flex h-10 items-center justify-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Reload page
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
