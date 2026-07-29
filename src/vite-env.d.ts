/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_USE_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/**
 * CR-11 — Vite `define` set in vite.config.ts (short git SHA at build time, timestamp
 * fallback outside a git checkout). A compile-time text substitution, not a real runtime
 * binding — see the `typeof __APP_BUILD_ID__ !== 'undefined'` guard in src/lib/api.ts,
 * which is what lets code referencing this run under vitest too: vitest's configs don't
 * carry this `define`, so referencing it as a bare identifier there would throw
 * ReferenceError, but `typeof` on an undeclared identifier never throws.
 */
declare const __APP_BUILD_ID__: string;
