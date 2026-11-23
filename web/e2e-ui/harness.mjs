/**
 * New-user UI audit harness. Starts one ERIC gateway on a fresh data directory and controls its lifecycle
 * (graceful stop, hard kill, start on the same data directory, fresh install) so specs can check that what a
 * user configured through the admin page and Control Canvas survives restarts, crashes and restores.
 *
 * Which gateway runs:
 *  - ERIC_UI_GATEWAY=<launcher> runs an installed or extracted build (for example the Linux tarball's
 *    bin/ERIC-Gateway). Nothing else is set: the build must find its own web bundles.
 *  - otherwise gateway/server/build/install/server/bin/server with the repo's built admin and Canvas bundles.
 *
 * State (pid, port, data dir) lives in test-results/ui-gateway.json so every spec process shares one gateway.
 */
import { spawn, execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, openSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const webRoot = path.resolve(here, "..");
export const repoRoot = path.resolve(webRoot, "..");
const stateFile = path.join(webRoot, "test-results", "ui-gateway.json");

export const port = Number(process.env.ERIC_UI_PORT ?? "8291");
export const baseURL = `http://127.0.0.1:${port}`;
/** Test-only first administrator. Not a deployed secret. */
export const ADMIN_PASSWORD = process.env.ERIC_UI_ADMIN_PASSWORD ?? "Wet-well-pass-7";

function launcher(override) {
  if (override) return { bin: override, env: {} };
  if (process.env.ERIC_UI_GATEWAY) return { bin: process.env.ERIC_UI_GATEWAY, env: {} };
  const bin = path.join(repoRoot, "gateway/server/build/install/server/bin", process.platform === "win32" ? "server.bat" : "server");
  return {
    bin,
    env: { OIP_ADMIN_DIST: path.join(webRoot, "admin/dist"), OIP_WEB_DIST: path.join(webRoot, "designer/dist") },
  };
}

export function readState() {
  return existsSync(stateFile) ? JSON.parse(readFileSync(stateFile, "utf8")) : null;
}

function writeState(state) {
  mkdirSync(path.dirname(stateFile), { recursive: true });
  writeFileSync(stateFile, JSON.stringify(state, null, 2));
}

async function healthy(timeoutMs, pid) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (pid && !alive(pid)) return false;
    try {
      const res = await fetch(`${baseURL}/api/v1/health`);
      if (res.ok) return true;
    } catch {
      /* not listening yet */
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/**
 * A new, empty data directory. Only machine-local test plumbing is written: free OPC UA and gateway-network
 * ports (another gateway may hold 4840/8060 on a shared machine) and a higher login rate for the test browser.
 */
export function newDataDir() {
  const dir = mkdtempSync(path.join(process.env.ERIC_UI_TMP ?? tmpdir(), "eric-ui-"));
  writeFileSync(path.join(dir, "gateway.yaml"), "opcua:\n  port: 0\ngatewayNetwork:\n  port: 0\nhttp:\n  loginRatePerMinute: 600\n");
  return dir;
}

/** Start the gateway on [dataDir] (a fresh one when omitted) and wait until it answers. */
export async function start(dataDir = readState()?.dataDir ?? newDataDir(), launcherOverride = undefined) {
  const current = readState();
  if (current?.pid && alive(current.pid)) throw new Error(`gateway already running (pid ${current.pid})`);
  const { bin, env } = launcher(launcherOverride);
  const log = openSync(path.join(dataDir, "..", `${path.basename(dataDir)}.log`), "a");
  const child = spawn(bin, ["--data-dir", dataDir, "--port", String(port)], {
    detached: true,
    stdio: ["ignore", log, log],
    shell: process.platform === "win32",
    env: {
      ...process.env,
      ...env,
      // First administrator for a fresh data dir until first-run setup is driven through the UI (see ui-audit.md).
      ...(process.env.ERIC_UI_FIRST_RUN === "ui" ? {} : { ERIC_ADMIN_PASSWORD: ADMIN_PASSWORD }),
      OIP_HTTP_HOST: "127.0.0.1",
    },
  });
  child.unref();
  writeState({ ...(current ?? {}), pid: child.pid, dataDir, port });
  if (!(await healthy(180_000, child.pid))) throw new Error(`gateway did not become healthy; see ${dataDir}.log`);
  return dataDir;
}

/** Graceful stop (SIGTERM, like a service stop) and wait for the process to exit. */
export async function stop() {
  const state = readState();
  if (!state?.pid || !alive(state.pid)) return;
  signalTree(state.pid, "SIGTERM");
  const until = Date.now() + 60_000;
  while (alive(state.pid) && Date.now() < until) await new Promise((r) => setTimeout(r, 250));
  if (alive(state.pid)) throw new Error("gateway did not stop within 60 s of SIGTERM");
  // The JVM child of a shell launcher can outlive its parent for a moment.
  await new Promise((r) => setTimeout(r, 1000));
}

/** Power-loss style: SIGKILL with no shutdown hooks. */
export async function kill() {
  const state = readState();
  if (!state?.pid) return;
  signalTree(state.pid, "SIGKILL");
  await new Promise((r) => setTimeout(r, 1500));
}

function signalTree(pid, signal) {
  if (process.platform === "win32") {
    execFileSync("taskkill", ["/PID", String(pid), "/T", ...(signal === "SIGKILL" ? ["/F"] : [])]);
    return;
  }
  // detached: the launcher leads its own process group, so the JVM it starts gets the signal too.
  try {
    process.kill(-pid, signal);
  } catch {
    if (alive(pid)) process.kill(pid, signal);
  }
}

export async function restart({ hard = false } = {}) {
  if (hard) await kill();
  else await stop();
  return start(readState().dataDir);
}

/** Stop and delete the current data directory, then start a brand-new install. */
export async function freshInstall(launcherOverride = undefined) {
  const old = readState();
  await stop();
  writeState({ previousDataDir: old?.dataDir });
  return start(newDataDir(), launcherOverride);
}

/** Back to the data directory that was live before the last [freshInstall] (the fresh one is left on disk). */
export async function backToPrevious() {
  const prev = readState()?.previousDataDir;
  await stop();
  writeState({});
  return start(prev);
}

/**
 * Uninstall and reinstall the program, keeping the data directory. With ERIC_UI_TARBALL and ERIC_UI_INSTALL_DIR the
 * install directory is deleted and the tarball extracted again; otherwise the same build is simply started again.
 */
export async function reinstallKeepingData() {
  const state = readState();
  await stop();
  const tarball = process.env.ERIC_UI_TARBALL;
  const dir = process.env.ERIC_UI_INSTALL_DIR;
  if (tarball && dir) {
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    execFileSync("tar", ["-xzf", tarball, "-C", dir, "--strip-components=1"]);
  }
  return start(state.dataDir);
}
