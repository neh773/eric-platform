import { expect, test } from "@playwright/test";
import { login, openStudio } from "./ui";

/** Structured Text program: create, check, compile and deploy, start, from the Logic workspace. */
test("PLC: new ST program, check, compile and deploy, run", async ({ page }) => {
  await login(page);
  await openStudio(page);
  await page.getByRole("button", { name: "Logic", exact: true }).click();
  await page.getByRole("button", { name: "New program" }).click();
  const name = page.getByRole("textbox", { name: /Program name/i });
  if (await name.count()) {
    await name.fill("lead_lag");
    await page.getByRole("button", { name: /^(Create|OK)$/ }).click();
  }
  const editor = page.locator(".cm-content, textarea").first();
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("PROGRAM lead_lag\nVAR\n  count : DINT;\nEND_VAR\ncount := count + 1;\nEND_PROGRAM\n");
  await page.getByRole("button", { name: "Check program" }).click();
  await expect(page.getByText(/no problems|check passed|OK/i).first()).toBeVisible({ timeout: 60_000 });
  await page.getByRole("button", { name: "Compile and deploy" }).click();
  await expect(page.getByText(/deployed/i).first()).toBeVisible({ timeout: 180_000 });
  await page.getByRole("button", { name: "Start", exact: true }).click();
  await expect(page.getByText(/running/i).first()).toBeVisible({ timeout: 60_000 });
});
