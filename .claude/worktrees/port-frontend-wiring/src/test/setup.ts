/**
 * Vitest Test Setup
 * Owner: Kavya (QA Lead)
 *
 * Global test setup for vitest. Extends expect with @testing-library/jest-dom
 * matchers (toBeInTheDocument, toHaveClass, etc.) and provides cleanup after
 * each test.
 */

import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
// The /vitest subpath both registers the jest-dom matchers on vitest's `expect`
// AND augments vitest's `Assertion` types (toBeInTheDocument, toHaveClass, …) so
// `tsc --noEmit` type-checks .test.tsx files instead of erroring on every matcher.
import '@testing-library/jest-dom/vitest';

// Cleanup after each test (unmount React components)
afterEach(() => {
  cleanup();
});
