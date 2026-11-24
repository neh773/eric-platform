import { defineConfig, devices } from "@playwright/test";

/**
 * New-user UI audit (web/e2e-ui). Every spec drives the admin page and Control Canvas the way a person would:
 * forms, buttons and the canvas only, no REST calls or file edits to set anything up. The specs run in order on
 * one gateway that the harness starts on a fresh data directory, then restart, kill and restore it.
 *
 *   pnpm test:ui                         # installDist gateway + built bundles
 *   ERIC_UI_GATEWAY=/opt/eric/bin/ERIC-Gateway pnpm test:ui   # an extracted release build
 */
const port = process.env.ERIC_UI_PORT ?? "8291";

export default defineConfig({
  testDir: "./e2e-ui",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["json", { outputFile: "test-results/ui-audit.json" }]],
  globalSetup: "./e2e-ui/global-setup.mjs",
  globalTeardown: "./e2e-ui/global-teardown.mjs",
  use: {
    ...devices["Desktop Chrome"],
    viewport: { width: 1440, height: 900 },
    baseURL: `http://127.0.0.1:${port}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    actionTimeout: 20_000,
  },
});
