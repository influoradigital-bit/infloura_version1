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
    exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**'],
    testTimeout: 15_000,
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
