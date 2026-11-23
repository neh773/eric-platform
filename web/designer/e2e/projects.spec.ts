import { expect, test, type Page } from "@playwright/test";
import { PASSWORD, canvas, signIn, uniqueProject } from "./studio";

/**
 * The Designer opens and creates projects, saves a view, and saves the project under a new name.
 * A running session picks up the saved view. A user without the designer role cannot open a project.
 * doc source: FEATURES DES-011
 * doc source: FEATURES DES-009
 */

const status = (page: Page) => page.getByRole("status").filter({ hasText: /unsaved|saved|Saving|conflict/i });

test("creates a project, saves a view, saves a copy, and a session sees the change", async ({ page }) => {
  const parent = uniqueProject("parent");
  const child = uniqueProject("child");
  const copy = uniqueProject("copy");
  const csrf = await signIn(page.request);
  const created = await page.request.post("/api/v1/projects", {
    headers: { "X-CSRF-Token": csrf },
    data: { name: parent, title: "Parent plant", description: "Shared resources", inheritable: true },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  await page.goto(`/?project=${parent}`);
  await page.getByRole("button", { name: "File", exact: true }).click();
  await page.getByRole("menuitem", { name: "New project…" }).click();
  const dialog = page.getByRole("dialog", { name: "New project" });
  await dialog.getByLabel("Project name").fill(child);
  await dialog.getByLabel("Title").fill("Child line");
  await dialog.getByLabel("Description").fill("Packaging hall");
  await expect(dialog.getByLabel("Parent project").locator(`option[value="${parent}"]`)).toHaveCount(1);
  await dialog.getByLabel("Parent project").selectOption(parent);
  await dialog.getByRole("button", { name: "Create project" }).click();
  // A blank project has nothing unsaved, so the new project opens without the unsaved-changes prompt.
  await expect(page).toHaveURL(new RegExp(`[?&]project=${child}`));
  await expect(page.getByRole("alertdialog", { name: "Unsaved changes" })).toHaveCount(0);
  await expect(page.locator(".oip-project-name")).toHaveText(child);

  const storedParent = await page.request.get(`/api/v1/projects/${child}`);
  expect(storedParent.ok(), await storedParent.text()).toBeTruthy();
  expect(await storedParent.json()).toMatchObject({ title: "Child line", description: "Packaging hall", parent });

  await page.getByRole("textbox", { name: "New view name" }).fill("Line 1");
  await page.getByRole("button", { name: "Add view", exact: true }).click();
  await page.getByRole("button", { name: "Add label" }).click();
  await page.getByTestId("inspector").getByRole("textbox", { name: "Text" }).fill("Saved on the gateway");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(status(page)).toHaveText("All changes saved");

  const session = await page.context().newPage();
  await session.goto(`/hmi/${child}/line-1`);
  await expect(session.getByText("Saved on the gateway")).toBeVisible();

  await page.getByTestId("inspector").getByRole("textbox", { name: "Text" }).fill("Updated live");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(status(page)).toHaveText("All changes saved");
  await expect(session.getByText("Updated live")).toBeVisible({ timeout: 15_000 });
  await session.close();

  const before = await page.request.get(`/api/v1/config/designer/project/${child}/View/line-1`);
  const originalVersion = ((await before.json()) as { metadata: { version: number } }).metadata.version;

  await page.getByRole("button", { name: "File", exact: true }).click();
  await page.getByRole("menuitem", { name: "Save as…" }).click();
  const saveAs = page.getByRole("dialog", { name: "Save as" });
  await expect(saveAs.getByLabel("New project name")).toHaveValue(`copy-of-${child}`);
  await saveAs.getByLabel("New project name").fill(copy);
  await saveAs.getByRole("button", { name: "Save copy" }).click();
  await expect(page).toHaveURL(new RegExp(`[?&]project=${copy}`));
  await expect(page.getByRole("treeitem", { name: "Line 1" })).toBeVisible();

  const after = await page.request.get(`/api/v1/config/designer/project/${child}/View/line-1`);
  expect(((await after.json()) as { metadata: { version: number } }).metadata.version).toBe(originalVersion);
  const copied = await page.request.get(`/api/v1/config/designer/project/${copy}/View/line-1`);
  expect(copied.ok(), await copied.text()).toBeTruthy();
  expect(JSON.stringify(await copied.json())).toContain("Updated live");

  await page.reload();
  await expect(page.getByRole("treeitem", { name: "Line 1" })).toBeVisible();
  await page.getByRole("treeitem", { name: "Line 1" }).click();
  await expect(canvas(page).getByText("Updated live")).toBeVisible();

  await page.getByRole("button", { name: "File", exact: true }).click();
  await page.getByRole("menuitem", { name: "Open…" }).click();
  const open = page.getByRole("dialog", { name: "Open project" });
  const recent = open.getByRole("list", { name: "Recent projects" });
  await expect(recent).toContainText(child);
  await recent.getByRole("button", { name: child, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`[?&]project=${child}`));
  await page.reload();
  await expect(page.getByRole("treeitem", { name: "Line 1" })).toBeVisible();
  await page.getByRole("treeitem", { name: "Line 1" }).click();
  await expect(canvas(page).getByText("Updated live")).toBeVisible();
});

test("refuses a user who may not edit projects", async ({ page, browser }) => {
  const csrf = await signIn(page.request);
  const username = `view-${Date.now().toString(36)}`;
  const res = await page.request.post("/api/v1/security/users", {
    headers: { "X-CSRF-Token": csrf },
    data: { username, password: PASSWORD, roles: ["viewer"] },
  });
  expect(res.ok(), await res.text()).toBeTruthy();

  const context = await browser.newContext();
  const viewer = await context.newPage();
  try {
    await viewer.goto("/");
    await viewer.getByLabel("Username").fill(username);
    await viewer.getByLabel("Password").fill(PASSWORD);
    await viewer.getByRole("button", { name: "Sign in" }).click();
    await expect(viewer.getByRole("alert")).toContainText("not allowed to use Control Canvas");
    await expect(viewer.getByRole("tree")).toHaveCount(0);
  } finally {
    await context.close();
  }
});
