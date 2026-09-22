import { defineConfig, devices } from '@playwright/test';

/**
 * Browser tests run against a deployed stack rather than a dev server, because what they are there to catch is
 * the whole chain behaving: nginx, the gateway, the services and the built front end.
 *
 * Point E2E_BASE_URL at whichever deployment you want to exercise. The tests create the content they need
 * through the API and remove it afterwards, so they can run against a shared environment without leaving
 * anything behind — but they do write, so do not aim them at production.
 */
export default defineConfig({
  testDir: './e2e',
  // A PDF rendering in a browser is slower than a DOM assertion; the default 30s is tight for the reader.
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8088',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
