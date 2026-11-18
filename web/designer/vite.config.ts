import path from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The Designer loads and saves views through the gateway REST API (M4.4). In dev, /api goes to a gateway
// at OIP_GATEWAY_URL; Playwright points it at the end-to-end gateway. Without a gateway the Studio opens
// its sample project in the tab and says edits are not saved.
const gatewayUrl = process.env.OIP_GATEWAY_URL ?? "http://127.0.0.1:8090";
const port = Number(process.env.OIP_DESIGNER_PORT ?? 5173);

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@oip/hmi-runtime": path.resolve(__dirname, "../hmi-runtime/src/index.ts"),
      "@oip/components": path.resolve(__dirname, "../components/src/index.ts"),
    },
  },
  server: {
    host: "127.0.0.1",
    port,
    strictPort: true,
    proxy: {
      // ws: the HMI session socket /api/v1/ws (M4.1) goes to the same gateway.
      "/api": { target: gatewayUrl, changeOrigin: false, ws: true },
    },
    fs: { allow: [path.resolve(__dirname, "..")] },
  },
  optimizeDeps: {
    exclude: ["@oip/hmi-runtime", "@oip/components"],
  },
});
