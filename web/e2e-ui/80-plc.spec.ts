import { expect, test } from "@playwright/test";
import { login, openStudio } from "./ui";

/** Structured Text program: create, check, compile and deploy, start, from the Logic workspace. */
test("PLC: new ST program, check, compile and deploy, run", async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await openStudio(page);
  await page.getByRole("button", { name: "Logic", exact: true }).click();
  await page.getByLabel("Program name").fill("lead_lag");
  await page.getByRole("button", { name: "New program" }).click();
  const editor = page.locator(".cm-content, textarea").first();
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("PROGRAM lead_lag\nVAR\n  count AT %QD0 : DINT;\nEND_VAR\ncount := count + 1;\nEND_PROGRAM\n");
  await page.getByRole("button", { name: "Check program" }).click();
  await expect(page.getByText(/no problems|check passed|OK/i).first()).toBeVisible({ timeout: 60_000 });
  // Deploy needs at least one variable mapped to a tag: map the counter through the Tag mapping form.
  // The tag map belongs to the deployed program: clear rows left from another program, then map this one.
  await expect(page.getByRole("region", { name: "Tag mapping and runtime" })).toHaveAttribute("data-mapping-loaded", "yes");
  while (await page.getByRole("button", { name: "Remove mapping 0", exact: true }).count()) {
    await page.getByRole("button", { name: "Remove mapping 0", exact: true }).click();
  }
  await page.getByRole("button", { name: "Add mapping" }).click();
  await page.getByLabel("Variable 0", { exact: true }).fill("count");
  await page.getByLabel("Address 0", { exact: true }).fill("%QD0");
  await page.getByLabel("Tag 0", { exact: true }).fill("[default]LiftStation/LeadLagCount");
  await page.getByLabel("Type 0", { exact: true }).selectOption("DINT");
  await page.getByLabel("Direction 0", { exact: true }).selectOption("out");
  await page.getByLabel("Safe 0", { exact: true }).fill("0");
  await page.getByRole("button", { name: "Compile and deploy" }).click();
  await expect(page.getByRole("status").filter({ hasText: /Deployed lead_lag/ })).toBeVisible({ timeout: 100_000 });
  const start = page.getByRole("button", { name: "Start", exact: true });
  if ((await start.count()) && (await start.isEnabled())) await start.click();
  await expect(page.locator("[aria-label='Logic status']")).toHaveText("running", { timeout: 30_000 });
});
