import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Test-only page for the live components. /api goes to the e2e gateway (admin/e2e/start-gateway.mjs).
const here = path.dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.OIP_COMPONENTS_E2E_PORT ?? "5175");
const gateway = `http://127.0.0.1:${process.env.OIP_ADMIN_E2E_PORT ?? "8091"}`;

export default defineConfig({
  root: path.resolve(here, "harness"),
  plugins: [react()],
  resolve: {
    alias: {
      "@oip/hmi-runtime": path.resolve(here, "../../hmi-runtime/src/index.ts"),
      "@oip/components": path.resolve(here, "../src/index.ts"),
    },
  },
  server: {
    host: "127.0.0.1",
    port,
    strictPort: true,
    fs: { allow: [path.resolve(here, "../..")] },
    proxy: { "/api": { target: gateway, changeOrigin: false } },
  },
  optimizeDeps: {
    exclude: ["@oip/hmi-runtime", "@oip/components"],
  },
});
