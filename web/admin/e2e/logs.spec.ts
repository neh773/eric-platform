import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { csrf, login, openSection } from "./gateway";
import { expectNoSecrets, withRole } from "./status";

/**
 * GAT-016/GAT-019: log tail with filters, a per-logger level change without restart, and download.
 */
const LOGGER = "org.oip.http";

async function levels(page: Page): Promise<Record<string, string>> {
  const res = await page.request.get("/api/v1/logs/levels");
  expect(res.ok()).toBeTruthy();
  return ((await res.json()) as { levels: Record<string, string> }).levels;
}

async function resetLevel(page: Page) {
  const token = await csrf(page);
  await page.request.put("/api/v1/logs/levels", {
    data: { logger: LOGGER, level: "INFO" },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
}

test("filters the tail, changes a level at runtime, and downloads the log", async ({ page }) => {
  await login(page);
  try {
    await openSection(page, "Logs");
    const table = page.getByTestId("log-records");
    await expect(table).toContainText("gateway started");

    // Per-logger level change. The new level is visible through the API without a restart.
    await page.getByLabel("Logger name").fill(LOGGER);
    await page.getByLabel("New level").selectOption("DEBUG");
    await page.getByRole("button", { name: "Set level" }).click();
    await expect(page.getByRole("status").filter({ hasText: `${LOGGER} now logs at DEBUG.` })).toBeVisible();
    expect((await levels(page))[LOGGER]).toBe("DEBUG");
    await expect(page.getByTestId("log-levels").getByRole("row", { name: `${LOGGER} DEBUG` })).toBeVisible();

    // DEBUG request records now reach the ring. Filter the tail down to them.
    await page.request.get("/api/v1/health");
    await page.getByLabel("Logger", { exact: true }).fill(LOGGER);
    await page.getByLabel("Lowest level").selectOption("DEBUG");
    await page.getByLabel("Text").fill("request");
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(table.locator('tbody tr[data-level="DEBUG"]').first()).toContainText(LOGGER);
    await expect(table).not.toContainText("gateway started");

    // A text filter that matches nothing empties the table.
    await page.getByLabel("Text").fill("no-record-has-this-text");
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(table).toContainText("No records match these filters.");

    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("link", { name: "Download log file" }).click(),
    ]);
    const file = await download.path();
    if (!file) throw new Error("download was not saved");
    const lines = readFileSync(file, "utf8").trim().split("\n");
    expect(lines.some((line) => line.includes("gateway started"))).toBeTruthy();
    expect(() => JSON.parse(lines[0]!)).not.toThrow();
    await expectNoSecrets(page);
  } finally {
    await resetLevel(page);
  }
  expect((await levels(page))[LOGGER]).toBe("INFO");
});

test("read-only roles get no level control and the API refuses their change", async ({ page, browser }) => {
  await login(page);
  await withRole(browser, page, "operator", async (reader) => {
    await openSection(reader, "Logs");
    await expect(reader.getByTestId("log-records")).toBeVisible();
    await expect(reader.getByRole("button", { name: "Set level" })).toHaveCount(0);
    const token = await csrf(reader);
    const res = await reader.request.put("/api/v1/logs/levels", {
      data: { logger: LOGGER, level: "TRACE" },
      headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
    });
    expect(res.status()).toBe(403);
  });
  await withRole(browser, page, "viewer", async (viewer) => {
    await openSection(viewer, "Logs");
    await expect(viewer.getByRole("alert").first()).toContainText("denied");
    await expect(viewer.getByRole("button", { name: "Set level" })).toHaveCount(0);
    await expect(viewer.getByTestId("log-records")).toHaveCount(0);
    const token = await csrf(viewer);
    const res = await viewer.request.put("/api/v1/logs/levels", {
      data: { logger: LOGGER, level: "TRACE" },
      headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
    });
    expect(res.status()).toBe(403);
  });
  expect((await levels(page))[LOGGER]).not.toBe("TRACE");
});
