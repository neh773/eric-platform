import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * A transaction group is created, changed, and removed from Databases.
 * Doc source: FEATURES DAT-004, DAT-008, DAT-009.
 */

test("creates, edits, and deletes a transaction group", async ({ page }) => {
  const name = stamp("group");
  await login(page);
  await openSection(page, "Databases");
  const main = page.getByRole("main");
  await main.getByRole("button", { name: "New transaction group" }).click();
  const form = main.getByRole("form", { name: "New transaction group" });
  await form.getByLabel("Group name").fill(name);
  await form.getByRole("textbox", { name: "Table", exact: true }).fill("samples");
  await form.getByLabel("Tag path").fill("[default]tank/level");
  await form.getByRole("button", { name: "Save group" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  const saved = main.getByRole("form", { name: `Transaction group ${name}` });
  await saved.getByLabel("Rate").fill("5000");
  await saved.getByRole("button", { name: "Save group" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  await saved.getByRole("button", { name: "Edit as JSON" }).click();
  await expect(saved.getByLabel("Group JSON")).toHaveValue(/5000/);
  await saved.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
  await expect(main.getByRole("cell", { name, exact: true })).toHaveCount(0);
});
