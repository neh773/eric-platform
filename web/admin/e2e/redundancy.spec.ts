import { expect, test } from "@playwright/test";
import { login, openSection } from "./gateway";

/**
 * The redundancy pair form writes the master address through PUT /api/v1/redundancy.
 * Doc source: FEATURES RED-002, RED-003.
 */

test("saves a backup pair on the network page", async ({ page }) => {
  await login(page);
  await openSection(page, "Network");
  const pair = page.getByRole("main").getByRole("region", { name: "Redundancy pair" });
  const form = pair.getByRole("form", { name: "Redundancy settings" });
  await form.getByLabel("Role").selectOption("backup");
  await form.getByLabel("Master host").fill("10.1.2.3");
  await form.getByLabel("Master port").fill("8060");
  await form.getByRole("button", { name: "Save pair" }).click();
  await expect(pair.getByRole("status")).toContainText("Redundancy pair saved");
  await form.getByRole("checkbox", { name: "Edit as JSON" }).check();
  await expect(form.getByLabel("Redundancy JSON")).toHaveValue(/10\.1\.2\.3/);
});
