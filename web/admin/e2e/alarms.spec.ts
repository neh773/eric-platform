import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * A roster is created, changed, and removed from Alarming.
 * Doc source: FEATURES ALA-019.
 */

test("creates, edits, and deletes an on-call roster", async ({ page }) => {
  const name = stamp("roster");
  await login(page);
  await openSection(page, "Alarming");
  const main = page.getByRole("main");
  await main.getByRole("button", { name: "New roster" }).click();
  const form = main.getByRole("form", { name: "New roster" });
  await form.getByLabel("Alarm name").fill(name);
  await form.getByLabel("Roster users").fill("ada");
  await form.getByRole("button", { name: "Save" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  const saved = main.getByRole("form", { name: `Roster ${name}` });
  await saved.getByLabel("Roster users").fill("ada, bo");
  await saved.getByRole("button", { name: "Save" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  await saved.getByRole("button", { name: "Edit as JSON" }).click();
  await expect(saved.getByLabel("Alarm JSON")).toHaveValue(/bo/);
  await saved.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
  await expect(main.getByRole("cell", { name, exact: true })).toHaveCount(0);
});
