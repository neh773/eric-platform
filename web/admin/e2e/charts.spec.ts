import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * The chart form saves a begin step, an action, a transition, and an end step.
 * Doc source: FEATURES SFC-002, SFC-007, SFC-009.
 */

test("creates and deletes a chart", async ({ page }) => {
  const name = stamp("cht");
  await login(page);
  await openSection(page, "Charts");
  const main = page.getByRole("main");
  const form = main.getByRole("form", { name: "New chart" });
  await form.getByLabel("Chart name").fill(name);
  await form.getByLabel("Run mode").selectOption("callable");
  await form.getByLabel("Action script").fill("count = 1");
  await form.getByLabel("Transition").fill("true");
  await form.getByRole("button", { name: "Save chart" }).click();
  await expect(main.getByRole("status")).toContainText(`Saved ${name}.`);
  await expect(main.getByRole("row", { name: new RegExp(name) })).toHaveAttribute("data-valid", "yes");
  await form.getByRole("checkbox", { name: "Edit as JSON" }).check();
  await expect(form.getByLabel("Chart JSON")).toHaveValue(/"type": "begin"/);
  await main.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
});
