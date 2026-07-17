import { defineConfig, devices } from '@playwright/test';

/**
 * Influora Playwright — Vite + React Router (Kv3b / Kavya).
 *
 * Fail-closed for live API: webServer forces mock mode. Specs that need a
 * real backend must set VITE_API_MODE=live explicitly and supply staging
 * credentials — otherwise they must skip, never hit production.
 *
 * Demo auth: CreatorProtectedRoute honors `?demo=true` only when
 * `import.meta.env.DEV` is true (vite `npm run dev` / webServer below).
 */
const isLiveApi = process.env.VITE_API_MODE === 'live';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  timeout: 120_000,
  expect: { timeout: 30_000 },
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    navigationTimeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 3000',
    url: 'http://127.0.0.1:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    env: {
      ...process.env,
      // Fail-closed default: never start the smoke harness against live API
      // unless an operator deliberately overrides for a staging suite.
      VITE_API_MODE: isLiveApi ? 'live' : 'mock',
    },
  },
});
