import { expect, test } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { PASSWORD, login, openSection, withViewer } from "./gateway";

/**
 * EAM-001, EAM-005, EAM-008, EAM-014: designate the test gateway as a controller on the Enterprise Admin page, start a
 * second gateway process that enrolls itself from first-boot files, approve its certificate and the agent on the page,
 * and run a task on it.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const bin = path.resolve(here, "../../../gateway/server/build/install/server/bin", process.platform === "win32" ? "server.bat" : "server");

interface EamStatus {
  role: string;
  controller: { listenPort: number | null; fingerprint: string | null } | null;
}

test("designates a controller, approves a first-boot agent, and runs a task on it", async ({ page, browser }) => {
  test.setTimeout(150_000);
  test.skip(!existsSync(bin), "gateway distribution is not built");
  await login(page);
  await openSection(page, "Enterprise Admin");

  const form = page.getByTestId("eam-role-form");
  await form.getByLabel("Role").selectOption("controller");
  await form.getByRole("button", { name: "Save role" }).click();
  await expect(page.getByTestId("eam-role")).toContainText("controller");

  const status = await page.evaluate(async () => {
    const res = await fetch("/api/v1/eam", { credentials: "same-origin" });
    return (await res.json()) as EamStatus;
  });
  expect(status.controller?.listenPort).toBeTruthy();
  expect(status.controller?.fingerprint).toMatch(/^[0-9a-f]{64}$/);

  const agentName = `e2e-agent-${Date.now().toString(36)}`;
  const dataDir = mkdtempSync(path.join(tmpdir(), "oip-eam-agent-"));
  writeFileSync(path.join(dataDir, "gateway.yaml"), `profile: standard\ngatewayNetwork:\n  name: ${agentName}\n  port: 0\n`);
  writeFileSync(
    path.join(dataDir, "eam-agent.json"),
    JSON.stringify({
      controllerHost: "127.0.0.1",
      controllerPort: status.controller!.listenPort,
      controllerFingerprint: status.controller!.fingerprint,
      group: "e2e",
      checkInMillis: 500,
    }),
  );
  let agent: ChildProcess | null = spawn(bin, ["--data-dir", dataDir, "--port", "0"], {
    stdio: "ignore",
    shell: process.platform === "win32",
    env: { ...process.env, OIP_ADMIN_PASSWORD: PASSWORD, OIP_HTTP_HOST: "127.0.0.1", OIP_OPCUA_ENABLED: "false", OIP_DATA_DIR: dataDir },
  });
  try {
    const approveCertificate = page.getByRole("button", { name: "Approve certificate" });
    await expect(approveCertificate.first()).toBeVisible({ timeout: 90_000 });
    await approveCertificate.first().click();
    await expect(page.getByRole("status").filter({ hasText: "Certificate approved" })).toBeVisible();

    await expect(page.getByTestId(`eam-status-${agentName}`)).toHaveText("pending", { timeout: 30_000 });
    await page.getByRole("button", { name: `Approve ${agentName}` }).click();
    await expect(page.getByTestId(`eam-status-${agentName}`)).toHaveText("online", { timeout: 30_000 });
    await expect(page.getByTestId(`eam-agent-${agentName}`)).toContainText("e2e");

    const tasks = page.getByTestId("eam-task-form");
    await tasks.getByLabel("Task id").fill("e2e-tags");
    await tasks.getByLabel("Kind").selectOption("send-tags");
    await tasks.getByLabel("Agents").fill(agentName);
    await tasks.getByRole("button", { name: "Create task" }).click();
    await expect(page.getByTestId(`eam-run-e2e-tags-${agentName}`).getByTestId("eam-run-state")).toHaveText("succeeded", {
      timeout: 30_000,
    });

    const csv = await page.evaluate(async () => (await fetch("/api/v1/eam/inventory?format=csv", { credentials: "same-origin" })).text());
    expect(csv).toContain(agentName);

    await withViewer(browser, page, async (viewer) => {
      await openSection(viewer, "Enterprise Admin");
      await expect(viewer.getByText("Enterprise administration needs gateway.read.")).toBeVisible();
      await expect(viewer.getByRole("button", { name: `Approve ${agentName}` })).toHaveCount(0);
    });
  } finally {
    stopProcess(agent);
    agent = null;
    await page.evaluate(async () => {
      const session = await (await fetch("/api/v1/auth/session", { credentials: "same-origin" })).json();
      await fetch("/api/v1/eam/settings", {
        method: "PUT",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({ role: "none" }),
      });
    });
    for (let attempt = 0; attempt < 10; attempt++) {
      try {
        rmSync(dataDir, { recursive: true, force: true });
        break;
      } catch (error) {
        if (attempt === 9) throw error;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
      }
    }
  }
});

/** Windows `shell: true` makes the child cmd.exe; the gateway JVM is a grandchild and keeps the data dir open. */
function stopProcess(agent: ChildProcess | null) {
  if (!agent?.pid) return;
  if (process.platform === "win32") {
    try {
      execFileSync("taskkill", ["/pid", String(agent.pid), "/T", "/F"], { stdio: "ignore" });
    } catch {
      // The process had already exited.
    }
    return;
  }
  agent.kill("SIGTERM");
}
