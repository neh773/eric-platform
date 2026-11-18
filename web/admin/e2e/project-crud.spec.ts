import { expect, test, type Page } from "@playwright/test";
import { csrf, login, openSection, stamp } from "./gateway";
import { withRole } from "./status";

/**
 * PRO-004 on the admin Projects page: create, edit settings, disable and enable, rename, copy, and delete, each
 * through the page and checked through REST, and each audited once. PRO-002: a parent in use is not deleted, and a
 * disabled project is not served at /hmi.
 */

async function project(page: Page, name: string): Promise<Record<string, unknown> | null> {
  const res = await page.request.get(`/api/v1/projects/${name}`);
  if (res.status() === 404) return null;
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await res.json()) as Record<string, unknown>;
}

async function auditTotal(page: Page, action: string): Promise<number> {
  const res = await page.request.get(`/api/v1/audit?action=${encodeURIComponent(action)}&limit=1`);
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { total: number }).total;
}

test("creates, edits, disables, renames, copies and deletes a project from the Projects page", async ({ page }) => {
  const base = stamp("base");
  const plant = stamp("plant");
  const renamed = `${plant}-r`;
  const copy = `${plant}-c`;
  await login(page);
  const created0 = await auditTotal(page, "project created");
  await openSection(page, "Projects");

  const create = page.getByRole("form", { name: "Create project" });
  await create.getByLabel("New project name").fill(base);
  await create.getByLabel("Other projects may inherit from it (inheritable)").check();
  await create.getByRole("button", { name: "Create project" }).click();
  await expect(page.getByRole("link", { name: `Export ${base}`, exact: true })).toBeVisible();

  await create.getByLabel("New project name").fill(plant);
  await create.getByLabel("New project title").fill("Plant floor");
  await create.getByLabel("New project description").fill("Line one");
  await create.getByLabel("New project parent").selectOption(base);
  await create.getByRole("button", { name: "Create project" }).click();
  await expect(page.getByRole("link", { name: `Export ${plant}`, exact: true })).toBeVisible();
  expect(await project(page, plant)).toMatchObject({ title: "Plant floor", description: "Line one", parent: base, enabled: true });
  expect((await auditTotal(page, "project created")) - created0).toBe(2);

  // Disable: not served at /hmi; enable again.
  await page.getByRole("button", { name: `Disable ${plant}`, exact: true }).click();
  await expect(page.getByTestId(`state-${plant}`)).toHaveText("Disabled");
  expect((await page.request.get(`/hmi/${plant}/`)).status()).toBe(404);
  await page.getByRole("button", { name: `Enable ${plant}`, exact: true }).click();
  await expect(page.getByTestId(`state-${plant}`)).toHaveText("Enabled");
  expect((await page.request.get(`/hmi/${plant}/`)).status()).toBe(200);

  // Settings.
  await page.getByRole("button", { name: `Manage ${plant}`, exact: true }).click();
  const settings = page.getByRole("form", { name: `Settings of ${plant}` });
  await settings.getByLabel("Title").fill("Plant floor A");
  await settings.getByRole("button", { name: "Save settings" }).click();
  await expect(page.getByTestId("project-notice")).toHaveText(`Saved ${plant}.`);
  expect((await project(page, plant))?.title).toBe("Plant floor A");

  // A parent in use is not deleted.
  await page.getByRole("button", { name: `Manage ${base}`, exact: true }).click();
  await page.getByRole("button", { name: `Delete ${base}`, exact: true }).click();
  await page.getByRole("button", { name: "Confirm delete" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "parent of" })).toContainText(plant);
  expect(await project(page, base)).not.toBeNull();

  // Rename, copy, delete.
  await page.getByRole("button", { name: `Manage ${plant}`, exact: true }).click();
  const rename = page.getByRole("form", { name: `Rename ${plant}` });
  await rename.getByLabel("Rename to").fill(renamed);
  await rename.getByRole("button", { name: "Rename" }).click();
  await expect(page.getByRole("link", { name: `Export ${renamed}`, exact: true })).toBeVisible();
  expect(await project(page, plant)).toBeNull();
  expect(await project(page, renamed)).toMatchObject({ title: "Plant floor A", parent: base });

  const copyForm = page.getByRole("form", { name: `Copy ${renamed}` });
  await copyForm.getByLabel("Copy to").fill(copy);
  await copyForm.getByRole("button", { name: "Copy" }).click();
  await expect(page.getByRole("link", { name: `Export ${copy}`, exact: true })).toBeVisible();
  expect(await project(page, copy)).toMatchObject({ title: "Plant floor A", parent: base });

  const deleted0 = await auditTotal(page, "project deleted");
  await page.getByRole("button", { name: `Delete ${renamed}`, exact: true }).click();
  await page.getByRole("button", { name: "Confirm delete" }).click();
  await expect(page.getByRole("link", { name: `Export ${renamed}`, exact: true })).toHaveCount(0);
  expect(await project(page, renamed)).toBeNull();
  expect(await project(page, copy)).not.toBeNull();
  expect((await auditTotal(page, "project deleted")) - deleted0).toBe(1);
  expect(await auditTotal(page, "project renamed")).toBeGreaterThanOrEqual(1);
  expect(await auditTotal(page, "project copied")).toBeGreaterThanOrEqual(1);
});

test("a read-only role sees projects without CRUD controls, and the API refuses its writes", async ({ page, browser }) => {
  const name = stamp("ro-crud");
  await login(page);
  const token = await csrf(page);
  const res = await page.request.post("/api/v1/projects", { data: { name }, headers: { "X-CSRF-Token": token } });
  expect(res.status(), await res.text()).toBe(201);
  await withRole(browser, page, "operator", async (reader) => {
    await openSection(reader, "Projects");
    await expect(reader.getByRole("link", { name: `Export ${name}`, exact: true })).toBeVisible();
    await expect(reader.getByRole("button", { name: `Disable ${name}`, exact: true })).toHaveCount(0);
    await expect(reader.getByRole("form", { name: "Create project" })).toHaveCount(0);
    const t = await csrf(reader);
    const patch = await reader.request.patch(`/api/v1/projects/${name}`, { data: { enabled: false }, headers: { "X-CSRF-Token": t } });
    expect(patch.status()).toBe(403);
    const del = await reader.request.delete(`/api/v1/projects/${name}`, { headers: { "X-CSRF-Token": t } });
    expect(del.status()).toBe(403);
  });
});
