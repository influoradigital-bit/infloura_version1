import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

/**
 * Live-mode companion to `vitest.config.ts`, for `*.live.test.ts` only.
 *
 * The base config pins `VITE_API_MODE=mock` via BOTH `test.env` and a `define`, so unit tests can
 * never hit localhost:8080. That guard is correct and stays — but `define` is a transform-time
 * substitution, so `vi.stubEnv` cannot override it, which left the client's live-mode branches
 * (auth headers, refresh-and-retry, envelope error handling) with no way to be tested at all.
 *
 * That gap hid a real production bug: expired access tokens were never refreshed, forcing users
 * to re-login every time the token aged out. Nothing could have caught it, because every test ran
 * in mock mode where `isLive()` short-circuits before any of that code runs.
 *
 * Tests under this config MUST stub `fetch` — nothing here may touch a real network. The base
 * config excludes `*.live.test.ts` so the two suites never run each other's files.
 *
 * Run: npx vitest run --config vitest.live.config.ts
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.live.test.ts', 'src/**/*.live.test.tsx'],
    exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**', '**/.claude/**'],
    testTimeout: 15_000,
    env: {
      VITE_API_MODE: 'live',
    },
  },
  define: {
    'import.meta.env.VITE_API_MODE': JSON.stringify('live'),
    // Keep requests pointed at a host that does not exist; every test stubs fetch, so a leaked
    // real request fails loudly instead of silently reaching a dev server on :8080.
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify('http://api.test.invalid/api/v1'),
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
