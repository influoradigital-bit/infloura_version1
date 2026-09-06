/**
 * Falsification stand-in for src/components/ErrorBoundary.tsx — NOT a copy
 * of the real component and never used except via the W2_ERRORBOUNDARY_PATH
 * override (falsification harness only; real gate invocations always read
 * the real src/components/ErrorBoundary.tsx). Models a future rework where
 * the fallback drops its <h1> entirely (e.g. replaced with a <p> or an
 * icon-only illustration) — the gate must refuse to guess a heading and
 * exit 2 GATE UNAVAILABLE, never silently pass every route.
 */
import React from 'react';

export default class ErrorBoundary extends React.Component {
  render(): React.ReactNode {
    return (
      <div>
        <p>Something went wrong.</p>
        <button type="button">Try again</button>
        <button type="button">Reload page</button>
      </div>
    );
  }
}
