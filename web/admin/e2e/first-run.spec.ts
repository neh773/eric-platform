/**
 * 0.1.1 first-run setup: a new gateway with no users shows "Create administrator account" on /admin
 * instead of the sign-in form. This spec starts its own empty gateway (no ERIC_ADMIN_PASSWORD) so the
 * shared e2e gateway, which is seeded with a password, is not touched.
 */
import { expect, test } from "@playwright/test";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../../..");
const bin = path.join(repoRoot, "gateway/server/build/install/server/bin", process.platform === "win32" ? "server.bat" : "server");
const port = Number(process.env.OIP_FIRSTRUN_E2E_PORT ?? 8194);
const base = `http://127.0.0.1:${port}`;
const password = "First-run-Pass-42";

let gateway: ChildProcess | null = null;
let dataDir = "";

test.describe.configure({ mode: "serial" });

test.beforeAll(async () => {
  test.setTimeout(120_000);
  dataDir = mkdtempSync(path.join(tmpdir(), "eric-first-run-"));
  writeFileSync(path.join(dataDir, "gateway.yaml"), "gatewayNetwork:\n  port: 0\n");
  const env: NodeJS.ProcessEnv = { ...process.env };
  for (const key of ["OIP_ADMIN_PASSWORD", "ERIC_ADMIN_PASSWORD", "OIP_ADMIN_USERNAME", "ERIC_ADMIN_USERNAME"]) delete env[key];
  gateway = spawn(bin, ["--data-dir", dataDir, "--port", String(port)], {
    stdio: "inherit",
    shell: process.platform === "win32",
    env: {
      ...env,
      OIP_ADMIN_DIST: path.join(repoRoot, "web/admin/dist"),
      OIP_WEB_DIST: path.join(repoRoot, "web/designer/dist"),
      OIP_HTTP_HOST: "127.0.0.1",
      OIP_OPCUA_ENABLED: "false",
    },
  });
  const deadline = Date.now() + 90_000;
  for (;;) {
    try {
      const res = await fetch(`${base}/api/v1/health`);
      if (res.ok) break;
    } catch {
      // not up yet
    }
    if (Date.now() > deadline) throw new Error("first-run gateway did not start");
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
});

test.afterAll(() => {
  gateway?.kill("SIGTERM");
  if (dataDir) rmSync(dataDir, { recursive: true, force: true });
});

test("a proxied request cannot create the first administrator", async ({ request }) => {
  const res = await request.post(`${base}/api/v1/setup/admin`, {
    headers: { "Content-Type": "application/json", "X-Forwarded-For": "203.0.113.9" },
    data: { username: "intruder", password },
  });
  expect(res.status()).toBe(403);
  const status = await (await request.get(`${base}/api/v1/setup`)).json();
  expect(status.needsAdmin).toBe(true);
});

test("the first visit creates the administrator through the form", async ({ page }) => {
  await page.goto(`${base}/admin`);
  const form = page.getByRole("form", { name: "Create the administrator" });
  await expect(form.getByRole("heading", { name: "Create administrator account" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in", exact: true })).toHaveCount(0);

  await form.getByLabel("Administrator username").fill("nick");
  await form.getByLabel("New password").fill(password);
  await form.getByLabel("Confirm password").fill("not-the-same-1");
  await form.getByRole("button", { name: "Create administrator" }).click();
  await expect(form.getByRole("alert")).toContainText("do not match");

  await form.getByLabel("New password").fill(password);
  await form.getByLabel("Confirm password").fill(password);
  await form.getByRole("button", { name: "Create administrator" }).click();
  await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
});

test("after the first account exists the page is a normal sign-in", async ({ page, request }) => {
  const again = await request.post(`${base}/api/v1/setup/admin`, {
    headers: { "Content-Type": "application/json" },
    data: { username: "second", password },
  });
  expect(again.status()).toBe(409);

  await page.goto(`${base}/admin`);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("form", { name: "Create the administrator" })).toHaveCount(0);
  await page.getByLabel("Username").fill("nick");
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
});
