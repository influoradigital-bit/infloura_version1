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

// jsdom ships no ResizeObserver, but several Radix primitives (@radix-ui/react-use-size, used by
// Checkbox/Select/Popover internals) construct one on mount — so any page test that renders one
// of those dies with "ResizeObserver is not defined" before a single assertion runs. A no-op stub
// is the right shape here: nothing under test asserts on observed sizes, it only needs the
// constructor to exist. Only defined when absent, so a real implementation (or a per-test spy)
// always wins.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// Same class of jsdom gap, needed one layer further in: Radix Select/Dropdown guard their open
// logic on the Pointer Capture API and scroll the highlighted option into view. jsdom implements
// none of these, so `userEvent.click(combobox)` silently fails to open the listbox and any test
// that picks an option can never find it. No-op stubs restore the interaction without changing
// behaviour under test — nothing here asserts on capture state or scroll position.
for (const method of ['hasPointerCapture', 'setPointerCapture', 'releasePointerCapture'] as const) {
  if (!(method in Element.prototype)) {
    Object.defineProperty(Element.prototype, method, {
      value: method === 'hasPointerCapture' ? () => false : () => {},
      writable: true,
      configurable: true,
    });
  }
}
if (!('scrollIntoView' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    value: () => {},
    writable: true,
    configurable: true,
  });
}

// Cleanup after each test (unmount React components)
afterEach(() => {
  cleanup();
});
