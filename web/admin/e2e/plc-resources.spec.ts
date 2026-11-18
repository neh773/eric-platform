import { expect, test } from "@playwright/test";
import { login } from "./gateway";

test("previews a PLC and ERIC resource split without applying it", async ({ page }) => {
  await login(page);
  await page.goto("/admin/plc-resources");
  await expect(page.getByRole("heading", { name: "PLC / ERIC resource split" })).toBeVisible();
  const hardware = page.getByLabel("Detected hardware");
  await expect(hardware).toContainText("physical cores");
  const cores = Number((await hardware.innerText()).match(/(\d+) physical cores/)?.[1] ?? "0");
  await page.getByLabel("PLC cores").fill("1");
  await page.getByLabel("PLC memory GB").fill("1");
  await page.getByRole("button", { name: "Preview" }).click();
  const dry = page.getByLabel("Dry run");
  if (cores < 2) {
    await expect(dry).toContainText("at least one core");
    return;
  }
  if (process.platform === "win32") {
    await expect(dry).toContainText("cannot fully isolate a core");
    return;
  }
  await expect(dry).toContainText("isolcpus=managed_irq,domain,");
  await expect(page.getByRole("status").filter({ hasText: "Reboot required" })).toBeVisible();
  await page.getByRole("button", { name: "Apply" }).click();
  await expect(dry).toContainText("eric-plc-resources");
  await expect(dry).not.toContainText("The helper applied the files");
});
