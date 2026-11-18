/**
 * Build the admin and designer bundles and start a test gateway that serves them at /admin, /hmi and /studio.
 * Playwright waits until GET /api/v1/health succeeds.
 *
 * The admin password is the shared test fixture (gateway AuthSupport.TEST_PASSWORD).
 * It overrides any OIP_ADMIN_PASSWORD already in the environment.
 */
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const webRoot = path.resolve(here, "../..");
const repoRoot = path.resolve(webRoot, "..");
const dist = path.resolve(here, "../dist");
const webDist = path.resolve(webRoot, "designer/dist");
const port = process.env.OIP_ADMIN_E2E_PORT ?? "8091";
const password = "Correct-horse-1";

function run(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: "inherit",
      env: process.env,
      // pnpm and gradlew are .cmd shims on Windows. CreateProcess cannot start those directly.
      shell: process.platform === "win32",
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} exited ${code}`));
    });
  });
}

await run(
  "corepack",
  ["pnpm", "--filter", "@oip/admin", "exec", "vite", "build", "--mode", "production", "--logLevel", "error"],
  webRoot,
);

// C-4: the gateway hosts HMI sessions and the Studio from the designer bundle (OIP_WEB_DIST).
await run(
  "corepack",
  ["pnpm", "--filter", "@oip/designer", "exec", "vite", "build", "--mode", "production", "--logLevel", "error"],
  webRoot,
);

const gradlew = path.join(repoRoot, process.platform === "win32" ? "gradlew.bat" : "gradlew");
const bin = path.join(repoRoot, "gateway/server/build/install/server/bin", process.platform === "win32" ? "server.bat" : "server");
const gradleTasks = [":modules:hello-world:modulePackage"];
if (!existsSync(bin)) gradleTasks.unshift(":gateway:server:installDist");
await run(gradlew, [...gradleTasks, "--no-daemon"], repoRoot);

const dataDir = mkdtempSync(path.join(tmpdir(), "oip-admin-e2e-"));
// The admin and component suites sign in to this one gateway from 127.0.0.1 many times a minute
// (each role check is a fresh login). The default of 20 logins per minute per IP throttles them with
// 429; the limiter itself is covered by the gateway's AuthHardeningTest.
// OIP_E2E_GAN_PORT moves the gateway network listener off its default (8060) when another local gateway holds it.
const ganPort = process.env.OIP_E2E_GAN_PORT;
writeFileSync(
  path.join(dataDir, "gateway.yaml"),
  "http:\n  loginRatePerMinute: 600\n" + (ganPort ? `gatewayNetwork:\n  port: ${Number(ganPort)}\n` : ""),
);
const child = spawn(bin, ["--data-dir", dataDir, "--port", port], {
  stdio: "inherit",
  shell: process.platform === "win32",
  env: {
    ...process.env,
    OIP_ADMIN_PASSWORD: password,
    ERIC_ADMIN_PASSWORD: password,
    OIP_ADMIN_DIST: dist,
    OIP_WEB_DIST: webDist,
    OIP_DATA_DIR: dataDir,
    OIP_HTTP_HOST: "127.0.0.1",
    OIP_OPCUA_ENABLED: "false",
    ERIC_PLC_COMPILER: process.env.ERIC_PLC_COMPILER ?? path.join(repoRoot, "plc/eric-plc/scripts/logic-check.sh"),
  },
});

function stop() {
  if (!child.killed) child.kill("SIGTERM");
}

process.on("SIGTERM", stop);
process.on("SIGINT", stop);
child.on("exit", (code) => process.exit(code ?? 0));
