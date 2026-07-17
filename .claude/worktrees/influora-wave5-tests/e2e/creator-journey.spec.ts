/**
 * Creator journey smoke — Kv-GA-2 (Kavya)
 *
 * Demo/mock mode: login shell → dashboard OR disputes via `?demo=true`.
 * Fail-closed when VITE_API_MODE=live (staging OTP/JWT required — not this suite).
 *
 * Run: npx playwright test e2e/creator-journey.spec.ts
 */

import { test, expect } from '@playwright/test';

test.describe('Creator journey smoke (demo / mock)', () => {
  test.beforeEach(() => {
    test.skip(
      process.env.VITE_API_MODE === 'live',
      'Fail-closed: live API E2E requires staging credentials — use mock/demo suite only',
    );
  });

  test('login page → demo dashboard loads without live API', async ({ page }) => {
    await page.goto('/creator/login');

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.locator('form').first()).toBeVisible();

    // Demo bypass (DEV-only) — no OTP / live auth
    await page.goto('/creator/dashboard?demo=true');

    await expect(page).not.toHaveURL(/\/creator\/login/);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByText('Available balance')).toBeVisible();
    await expect(page.getByText('Active deals')).toBeVisible();
  });

  test('demo disputes page loads list shell via creatorDisputes mock', async ({ page }) => {
    await page.goto('/creator/disputes?demo=true');

    await expect(page).not.toHaveURL(/\/creator\/login/);
    await expect(page.getByRole('heading', { name: 'Disputes', exact: true })).toBeVisible();
    await expect(
      page.getByText(/Open a dispute on a funded collaboration/i),
    ).toBeVisible();
    // Mock list has at least one row (Summer Fashion / Wellness) or empty state
    await expect(
      page.getByRole('heading', { name: 'Your disputes', exact: true }),
    ).toBeVisible();
  });
});
