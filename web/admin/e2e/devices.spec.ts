import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * A simulator device is created, changed, and removed from Connections.
 * Doc source: FEATURES DEV-028.
 */

test("creates, edits, and deletes a simulator device", async ({ page }) => {
  const name = stamp("sim");
  await login(page);
  await openSection(page, "Connections");
  const main = page.getByRole("main");
  await main.getByRole("button", { name: "New device" }).click();
  const form = main.getByRole("form", { name: "New device" });
  await form.getByLabel("Device name").fill(name);
  await form.getByLabel("Device type").selectOption("simulator");
  await form.getByLabel("Program").selectOption("generic");
  await form.getByRole("button", { name: "Save device" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  const saved = main.getByRole("form", { name: `Device ${name}` });
  await saved.getByLabel("Program").selectOption("dairy");
  await saved.getByRole("button", { name: "Save device" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  await saved.getByRole("button", { name: "Edit as JSON" }).click();
  await expect(saved.getByLabel("Device JSON")).toHaveValue(/dairy/);
  await saved.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
  await expect(main.getByRole("cell", { name, exact: true })).toHaveCount(0);
});
