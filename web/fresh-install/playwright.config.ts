import { defineConfig, devices } from "@playwright/test";

/** Fresh-install check (packaging/linux/fresh-install-test.sh starts the gateway and sets FRESH_URL). */
export default defineConfig({
  testDir: ".",
  timeout: 120_000,
  workers: 1,
  reporter: [["line"]],
  use: { ...devices["Desktop Chrome"], baseURL: process.env.FRESH_URL ?? "http://127.0.0.1:8090", screenshot: "only-on-failure" },
  outputDir: process.env.FRESH_OUT ?? "test-results/fresh-install",
});
