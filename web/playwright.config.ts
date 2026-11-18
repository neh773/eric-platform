import { defineConfig, devices } from "@playwright/test";

const chromium = { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } };
// Ports can be moved when another checkout already uses the defaults. start-gateway.mjs reads OIP_ADMIN_E2E_PORT too.
const designerPort = process.env.OIP_DESIGNER_PORT ?? "5173";
const gatewayPort = process.env.OIP_ADMIN_E2E_PORT ?? "8091";
const gatewayUrl = `http://127.0.0.1:${gatewayPort}`;
// Live component harness (components/e2e/vite.config.ts); it proxies /api to the gateway above.
const componentsPort = process.env.OIP_COMPONENTS_E2E_PORT ?? "5175";

export default defineConfig({
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  projects: [
    {
      name: "designer",
      testDir: "./designer/e2e",
      use: { ...chromium, baseURL: `http://127.0.0.1:${designerPort}` },
    },
    {
      name: "admin",
      testDir: "./admin/e2e",
      use: { ...chromium, baseURL: gatewayUrl },
    },
    {
      // User manual screenshots (manual/shots.spec.ts) against the gateway above. Writes docs/manual/img/*.png.
      name: "manual",
      testDir: "./manual",
      use: { ...chromium, baseURL: gatewayUrl },
    },
    {
      // PRF-011 session farm (perf/prf011.spec.ts) against the gateway above. OIP_PRF011_SIZE=full is the 60 s window.
      name: "prf011",
      testDir: "./perf",
      testMatch: "prf011.spec.ts",
      use: { ...chromium, baseURL: gatewayUrl },
    },
    {
      name: "components",
      testDir: "./components/e2e",
      use: { ...chromium, baseURL: `http://127.0.0.1:${componentsPort}` },
    },
  ],
  webServer: [
    {
      command: `corepack pnpm --filter @oip/designer dev --port ${designerPort}`,
      url: `http://127.0.0.1:${designerPort}`,
      // The Studio saves views to the same gateway the admin tests use.
      env: { OIP_GATEWAY_URL: gatewayUrl, OIP_DESIGNER_PORT: designerPort },
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: "node admin/e2e/start-gateway.mjs",
      url: `${gatewayUrl}/api/v1/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 300_000,
    },
    {
      command: "corepack pnpm exec vite --config components/e2e/vite.config.ts",
      url: `http://127.0.0.1:${componentsPort}`,
      env: { OIP_COMPONENTS_E2E_PORT: componentsPort, OIP_ADMIN_E2E_PORT: gatewayPort },
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});
