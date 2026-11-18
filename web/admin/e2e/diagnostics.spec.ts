import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { login, openSection } from "./gateway";
import { expectNoSecrets, withRole } from "./status";

/**
 * GAT-017/GAT-020: live performance panel, threads with dump download, scheduled tasks, memory, modules.
 */
test("performance refreshes, threads download, and tasks, memory, and modules render", async ({ page }) => {
  await login(page);
  await openSection(page, "Diagnostics");
  const panel = page.getByTestId("performance-panel");
  await expect(panel).toContainText("System CPU");
  await expect(panel).toContainText("Clock drift");
  const first = await panel.getAttribute("data-updated");
  // The panel refreshes every 3 s; allow for a slow CI runner.
  await expect.poll(async () => panel.getAttribute("data-updated"), { timeout: 10_000 }).not.toBe(first);

  await page.getByRole("tab", { name: "Threads" }).click();
  await expect(page.getByTestId("thread-summary")).toContainText("threads:");
  expect(await page.getByRole("table", { name: "Threads list" }).locator("tbody tr").count()).toBeGreaterThan(3);
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: "Download thread dump" }).click(),
  ]);
  const file = await download.path();
  if (!file) throw new Error("download was not saved");
  const dump = readFileSync(file, "utf8");
  expect(dump).toMatch(/^"[^"]+" #\d+ /);
  expect(dump).toContain("\tat ");

  await page.getByRole("tab", { name: "Scheduled tasks" }).click();
  await expect(page.getByRole("table", { name: "Scheduled tasks list" })).toContainText("diagnostics-sampler");

  await page.getByRole("tab", { name: "Memory" }).click();
  await expect(page.getByTestId("memory-panel")).toContainText("Processors");

  await page.getByRole("tab", { name: "Modules" }).click();
  await expect(page.getByRole("table", { name: "Module states" })).toContainText("RUNNING");
  await expectNoSecrets(page);
});

test("a viewer gets the API denial and no diagnostics", async ({ page, browser }) => {
  await login(page);
  await withRole(browser, page, "viewer", async (viewer) => {
    await openSection(viewer, "Diagnostics");
    await expect(viewer.getByTestId("diagnostics-no-read")).toBeVisible();
    await expect(viewer.getByRole("alert").first()).toContainText("denied");
    await expect(viewer.getByTestId("performance-panel")).toHaveCount(0);
    expect((await viewer.request.get("/api/v1/diagnostics/threads/dump")).status()).toBe(403);
  });
});

/**
 * GAT-021: Diagnostics groups architecture, connection, performance and environment status.
 * Doc source: FEATURES GAT-021.
 */
test("diagnostics groups the four status views", async ({ page }) => {
  await login(page);
  await openSection(page, "Diagnostics");
  await expect(page.getByTestId("performance-panel")).toBeVisible();

  await page.getByRole("button", { name: "Architecture", exact: true }).click();
  await expect(page.getByTestId("architecture-node")).toBeVisible();
  await expect(page.getByTestId("diagnostics-architecture")).toContainText("Redundancy storage");

  await page.getByRole("button", { name: "Connections", exact: true }).click();
  await expect(page.getByTestId("diagnostics-connections").getByTestId("connections-summary")).toContainText("connections:");
  await page.getByRole("link", { name: "Open the Connections section", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Connections", exact: true })).toBeVisible();

  await openSection(page, "Diagnostics");
  await page.getByRole("button", { name: "Environment", exact: true }).click();
  const env = page.getByTestId("environment-panel");
  await expect(env).toContainText("Java runtime");
  await expect(env).toContainText("Data directory");
  await expectNoSecrets(page);
});
