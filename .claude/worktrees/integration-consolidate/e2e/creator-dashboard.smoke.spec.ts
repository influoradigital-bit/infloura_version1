/**
 * Creator journey smoke — Kv3b (Kavya)
 *
 * Auth: injects sessionStorage access token (ProtectedRoute) — works without live login.
 * Demo query `?demo=true` kept as secondary DEV bypass signal.
 * Fail-closed: skips when VITE_API_MODE=live (needs staging OTP/JWT).
 */

import { test, expect } from '@playwright/test';

test.describe('Creator dashboard smoke (demo / mock)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(
      process.env.VITE_API_MODE === 'live',
      'Fail-closed: live API E2E requires staging credentials — use mock/demo suite only',
    );

    // Bypass CreatorProtectedRoute without hitting live auth (A-GA-2: access JWT in sessionStorage)
    await page.addInitScript(() => {
      sessionStorage.setItem('influora_creator_access', 'mock_creator_token_e2e');
    });
  });

  test('loads creator dashboard in demo mode without live API', async ({ page }) => {
    await page.goto('/creator/dashboard?demo=true', {
      waitUntil: 'domcontentloaded',
      timeout: 120_000,
    });

    await expect(page).not.toHaveURL(/\/creator\/login/);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('Available balance')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Active deals')).toBeVisible();
  });

  test('creator login page renders without calling live auth', async ({ page }) => {
    await page.goto('/creator/login', {
      waitUntil: 'domcontentloaded',
      timeout: 120_000,
    });

    await expect(page.getByRole('heading', { name: /Welcome back/i })).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.locator('form').first()).toBeVisible();
  });
});
