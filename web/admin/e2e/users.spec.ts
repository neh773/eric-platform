import { expect, test } from "@playwright/test";
import { login, openSection, PASSWORD, stamp, withViewer } from "./gateway";

/**
 * SEC-011: create, edit, and delete users and roles from the admin UI.
 * The password hash is never rendered.
 */
const NEXT_PASSWORD = "Correct-horse-2";

test("creates a user, assigns a role, resets the password, and edits a role", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const username = stamp("ada");
  const roleName = stamp("floor");
  await login(page);
  await openSection(page, "Users & Roles");

  const create = page.getByRole("form", { name: "Create user" });
  await create.getByLabel("Username").fill(username);
  await create.getByLabel("Password").fill(PASSWORD);
  await create.getByLabel("Display name").fill("Ada Lovelace");
  await create.getByRole("checkbox", { name: "viewer" }).check();
  await create.getByRole("button", { name: "Create user" }).click();

  const row = page.getByRole("row", { name: new RegExp(username) });
  await expect(row).toContainText("Ada Lovelace");
  await expect(row).toContainText("viewer");
  await expect(page.locator("body")).not.toContainText("passwordHash");
  await expect(page.locator("body")).not.toContainText("passwordHistory");
  await expect(page.locator("body")).not.toContainText("$argon2");

  await page.getByRole("button", { name: `Edit ${username}` }).click();
  const edit = page.getByRole("form", { name: `Edit ${username}` });
  await edit.getByRole("checkbox", { name: "operator" }).check();
  await edit.getByRole("button", { name: "Save user" }).click();
  await expect(row).toContainText("operator");

  await edit.getByLabel("New password").fill(NEXT_PASSWORD);
  await edit.getByRole("button", { name: "Reset password" }).click();
  await expect(edit.getByRole("status")).toHaveText("Password updated.");
  await expect(edit.getByLabel("New password")).toHaveValue("");

  const context = await browser.newContext();
  const signedIn = await context.newPage();
  try {
    await login(signedIn, username, NEXT_PASSWORD);
  } finally {
    await context.close();
  }

  await page.getByRole("button", { name: `Delete ${username}` }).click();
  await expect(page.getByRole("button", { name: `Delete ${username}` })).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText("passwordHash");

  const roles = page.getByRole("form", { name: "Create role" });
  await expect(roles).toBeVisible();
  await roles.getByLabel("Role name").fill(roleName);
  await roles.getByLabel("Description").fill("Floor lead");
  await roles.getByRole("button", { name: "Add Permissions" }).click();
  await roles.getByRole("combobox", { name: "Permissions 1" }).selectOption("gateway.read");
  await roles.getByRole("button", { name: "Create role" }).click();
  const roleRow = page.getByRole("row", { name: new RegExp(roleName) });
  await expect(roleRow).toContainText("Floor lead");
  await expect(roleRow).toContainText("gateway.read");

  await page.getByRole("button", { name: `Edit role ${roleName}` }).click();
  const editRole = page.getByRole("form", { name: "Edit role" });
  await editRole.getByLabel("Description").fill("Floor updated");
  await editRole.getByRole("button", { name: "Save role" }).click();
  await expect(roleRow).toContainText("Floor updated");
  await page.getByRole("button", { name: `Delete role ${roleName}` }).click();
  await expect(page.getByRole("button", { name: `Delete role ${roleName}` })).toHaveCount(0);

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "Users & Roles");
    await expect(viewer.getByRole("alert").filter({ hasText: "users.admin denied" })).toBeVisible();
    await expect(viewer.getByRole("alert").filter({ hasText: "roles.admin denied" })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Create user" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: "Create role" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: /Delete / })).toHaveCount(0);
    await expect(viewer.locator("body")).not.toContainText("passwordHash");
  });
});
