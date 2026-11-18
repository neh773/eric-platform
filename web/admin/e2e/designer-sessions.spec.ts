import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { csrf, login, openSection, stamp, withViewer } from "./gateway";

/**
 * GAT-028: a Designer opened on the gateway's Studio appears on the Canvas sessions page, and the page offers the
 * Designer launch link and the shortcut download.
 * Doc source: FEATURES GAT-028.
 */
test("an open Studio is listed with its user and project, and the launcher downloads", async ({ page, context }) => {
  test.setTimeout(60_000);
  const project = stamp("dsn");
  await login(page);
  const token = await csrf(page);
  const created = await page.request.post("/api/v1/projects", {
    headers: { "X-CSRF-Token": token, "Content-Type": "application/json" },
    data: { name: project },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  const studio = await context.newPage();
  await studio.goto(`/studio?project=${project}`);

  await openSection(page, "Canvas sessions");
  const table = page.getByTestId("designer-sessions");
  await expect(table).toContainText(project, { timeout: 15_000 });
  await expect(table).toContainText("admin");
  await expect(page.getByTestId("designer-launch")).toHaveAttribute("href", "/studio");

  const [download] = await Promise.all([page.waitForEvent("download"), page.getByTestId("designer-download").click()]);
  const file = await download.path();
  if (!file) throw new Error("download was not saved");
  const shortcut = readFileSync(file, "utf8");
  expect(shortcut).toContain("[InternetShortcut]");
  expect(shortcut).toContain("/studio");
  expect(download.suggestedFilename()).toBe("control-canvas.url");
});

test("a viewer cannot list Designer sessions", async ({ page, browser }) => {
  await login(page);
  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Canvas sessions");
    await expect(viewer.getByTestId("designer-sessions-no-read")).toBeVisible();
    expect((await viewer.request.get("/api/v1/designer/sessions")).status()).toBe(403);
  });
});
