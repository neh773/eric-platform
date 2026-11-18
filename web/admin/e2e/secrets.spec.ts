import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * A secret is saved and listed by reference. The value is not shown again.
 * Doc source: FEATURES SEC-023.
 */

test("saves a secret and shows only its reference", async ({ page }) => {
  const name = stamp("sec");
  const value = `hidden-${name}`;
  await login(page);
  await openSection(page, "Secrets");
  const main = page.getByRole("main");
  const form = main.getByRole("form", { name: "New secret" });
  await form.getByLabel("Secret name").fill(name);
  await form.getByLabel("Description").fill("database");
  await form.getByLabel("Secret value").fill(value);
  await form.getByRole("button", { name: "Save secret" }).click();
  await expect(main.getByRole("status")).toContainText(`secret://${name}`);
  await expect(main.getByRole("cell", { name: `secret://${name}`, exact: true })).toBeVisible();
  await expect(main).not.toContainText(value);
  await main.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
});
