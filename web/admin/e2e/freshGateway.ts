/**
 * Starts an extra, empty gateway for one spec: no users, no ERIC_ADMIN_PASSWORD, its own data folder and port.
 * The shared e2e gateway (start-gateway.mjs) is seeded with a password, so first-run and "restore on a new
 * gateway" checks use one of these.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../../..");
export const REPO_ROOT = repoRoot;
const bin = path.join(repoRoot, "gateway/server/build/install/server/bin", process.platform === "win32" ? "server.bat" : "server");

export interface FreshGateway {
  base: string;
  dataDir: string;
  /** Stops the gateway and deletes its data folder, or keeps the folder for a restart with `keepData`. */
  stop: (keepData?: boolean) => Promise<void>;
}

/** `reuseDataDir` starts the gateway again on a folder a previous one left (a restart). */
export async function startFreshGateway(port: number, extraEnv: NodeJS.ProcessEnv = {}, reuseDataDir?: string): Promise<FreshGateway> {
  const dataDir = reuseDataDir ?? mkdtempSync(path.join(tmpdir(), "eric-fresh-e2e-"));
  // gatewayNetwork port 0: never clash with 8060 held by another local gateway.
  if (!reuseDataDir) writeFileSync(path.join(dataDir, "gateway.yaml"), "gatewayNetwork:\n  port: 0\n");
  const env: NodeJS.ProcessEnv = { ...process.env };
  for (const key of ["OIP_ADMIN_PASSWORD", "ERIC_ADMIN_PASSWORD", "OIP_ADMIN_USERNAME", "ERIC_ADMIN_USERNAME"]) delete env[key];
  const child: ChildProcess = spawn(bin, ["--data-dir", dataDir, "--port", String(port)], {
    stdio: "inherit",
    shell: process.platform === "win32",
    // Own process group, so stop() reaches the JVM the start script launches, not only the script.
    detached: process.platform !== "win32",
    env: {
      ...env,
      OIP_ADMIN_DIST: path.join(repoRoot, "web/admin/dist"),
      OIP_WEB_DIST: path.join(repoRoot, "web/designer/dist"),
      OIP_HTTP_HOST: "127.0.0.1",
      OIP_OPCUA_ENABLED: "false",
      ...extraEnv,
    },
  });
  const base = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 90_000;
  for (;;) {
    try {
      if ((await fetch(`${base}/api/v1/health`)).ok) break;
    } catch {
      // not up yet
    }
    if (Date.now() > deadline) {
      if (process.platform !== "win32" && child.pid) { try { process.kill(-child.pid, "SIGTERM"); } catch { /* gone */ } } else child.kill("SIGTERM");
      throw new Error("fresh gateway did not start");
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  return {
    base,
    dataDir,
    stop: async (keepData = false) => {
      const exited = child.exitCode !== null ? Promise.resolve() : new Promise<void>((resolve) => child.once("exit", () => resolve()));
      if (process.platform !== "win32" && child.pid) {
        try { process.kill(-child.pid, "SIGTERM"); } catch { child.kill("SIGTERM"); }
      } else child.kill("SIGTERM");
      await Promise.race([exited, new Promise((resolve) => setTimeout(resolve, 15_000))]);
      if (!keepData) rmSync(dataDir, { recursive: true, force: true });
    },
  };
}
