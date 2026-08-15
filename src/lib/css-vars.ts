/**
 * Ref-callback helper for setting CSS custom properties on an element without
 * using the banned `style={...}` JSX attribute (kavya: no inline styles —
 * Tailwind only). Pair with a Tailwind arbitrary-property utility that reads
 * the same var, e.g.:
 *
 *   <div ref={cssVars({ '--w': `${pct}%` })} className="w-[var(--w)]" />
 *
 * DO NOT wrap the returned callback in `useCallback` (or otherwise memoize it).
 * Updates propagate ONLY because the inline arrow gets a new identity each
 * render, which makes React detach and re-run the ref with the fresh values —
 * memoizing it freezes every CSS var at its first-render value. If an element
 * needs its own ref too, compose manually: run both callbacks inside one arrow.
 */
export const cssVars =
  (vars: Record<string, string | number>) => (el: HTMLElement | SVGElement | null) => {
    if (el) {
      for (const [k, v] of Object.entries(vars)) {
        el.style.setProperty(k, String(v));
      }
    }
  };
