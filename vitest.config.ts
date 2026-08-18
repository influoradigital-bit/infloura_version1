import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

// Minimal vitest config to run Kavya's RBAC contract-style tests
// (src/admin/__tests__/rbac-permission-matrix.test.ts). Mirrors vite.config.ts's
// '@' alias so hook/type imports resolve the same way as the app build.
//
// Kv3b: force mock API for unit/RTL — local `.env.local` often sets
// VITE_API_MODE=live for manual dev; tests must not hit localhost:8080.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // e2e/ holds Playwright specs — run via `playwright test`, never vitest.
    // .claude/worktrees/* are sibling git worktrees with their own node_modules;
    // vitest's default include would run those duplicate test copies too (they
    // fail with dup-React "Invalid hook call"), so exclude the whole .claude tree.
    // `*.live.test.ts` runs under vitest.live.config.ts instead — those tests need
    // VITE_API_MODE=live, which the `define` below makes impossible to override per-file.
    exclude: [
      '**/node_modules/**',
      '**/dist/**',
      '**/e2e/**',
      '**/.claude/**',
      '**/*.live.test.ts',
      '**/*.live.test.tsx',
      // F-0334: .proof-os/ is the trust layer, not the product. Gates that prove a behaviour
      // instead of grepping for a token keep fixture specs in .proof-os/gates/ (gate work owns
      // that directory and nothing else), and several gates write TEMPORARY *.test.tsx there
      // under an EXIT trap. Without this line the default include swept all of them into the
      // product suite and into gates/build.sh's `npm test` leg — so a gate fixture failing read
      // as a product regression, and build.sh partly graded the trust layer. Gates run their own
      // specs under .proof-os/gates/vitest.gates.config.ts, which derives from this file and
      // drops exactly this one entry (vitest applies `exclude` even to an explicitly-passed
      // path, so they cannot simply pass the filename).
      '**/.proof-os/**',
    ],
    // F-0217: raised from 15s. A page test that awaits several elements in sequence now has up
    // to 5s per wait (src/test/setup.ts), so 15s could be exhausted by three slow waits on a
    // loaded machine and reported as a timeout rather than the contention it was. The ceiling
    // only costs time on tests that are already failing; it never turns a red test green.
    testTimeout: 30_000,
    // Default is 10s. `beforeEach` in the heavier page suites does the initial render + fetch
    // stubbing, and a hook timeout under load aborts the file rather than one test.
    hookTimeout: 30_000,
    env: {
      VITE_API_MODE: 'mock',
    },
  },
  // Override Vite env resolution so import.meta.env.VITE_API_MODE is mock
  // even when .env.local says live (define wins over dotenv for this key).
  define: {
    'import.meta.env.VITE_API_MODE': JSON.stringify('mock'),
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
