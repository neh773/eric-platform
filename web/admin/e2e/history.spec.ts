import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * A simulator history provider is created and removed from Historian.
 * Doc source: FEATURES HIS-009.
 */

test("creates and deletes a simulator history provider", async ({ page }) => {
  const name = stamp("hist");
  await login(page);
  await openSection(page, "Historian");
  const main = page.getByRole("main");
  const providers = main.getByRole("region", { name: "Providers" });
  await providers.getByRole("button", { name: "New provider" }).click();
  const form = providers.getByRole("form", { name: "New provider" });
  await form.getByLabel("Provider name").fill(name);
  await form.getByLabel("Provider type").selectOption("simulator");
  await form.getByRole("button", { name: "Save" }).click();
  await expect(providers.getByRole("status")).toContainText(`Saved ${name}.`);
  const saved = providers.getByRole("form", { name: `Providers ${name}` });
  await saved.getByRole("button", { name: "Edit as JSON" }).click();
  await expect(saved.getByLabel("History JSON")).toHaveValue(/simulator/);
  await saved.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(providers.getByRole("status")).toContainText(`Deleted ${name}.`);
  await expect(providers.getByRole("cell", { name, exact: true })).toHaveCount(0);
});
