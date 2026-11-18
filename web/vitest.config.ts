import { defineConfig } from "vitest/config";
import path from "node:path";

export default defineConfig({
  test: {
    environment: "happy-dom",
    include: [
      "hmi-runtime/src/**/*.test.ts",
      "hmi-runtime/src/**/*.test.tsx",
      "components/src/**/*.test.ts",
      "components/src/**/*.test.tsx",
      "designer/src/**/*.test.ts",
      "designer/src/**/*.test.tsx",
      "admin/src/**/*.test.ts",
      "admin/src/**/*.test.tsx",
      "perf/**/*.test.ts",
    ],
    setupFiles: ["./vitest.setup.ts"],
    testTimeout: 15_000,
  },
  resolve: {
    alias: {
      "@oip/hmi-runtime": path.resolve("./hmi-runtime/src/index.ts"),
      "@oip/components": path.resolve("./components/src/index.ts"),
    },
  },
});
