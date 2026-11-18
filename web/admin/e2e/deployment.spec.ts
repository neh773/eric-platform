import { expect, test } from "@playwright/test";
import { login, openSection } from "./gateway";

/**
 * Deployment shows the install values and the proxy note, and does not write them.
 * Doc source: FEATURES GAT-012, HMP-088.
 */

test("shows install values that can be copied", async ({ page }) => {
  await login(page);
  await openSection(page, "Deployment");
  const main = page.getByRole("main");
  await expect(main.getByLabel("Install values")).toHaveValue(/ERIC_HTTP_PORT=/);
  await expect(main.getByLabel("Helm values")).toHaveValue(/httpPort: 8088/);
  await expect(main.getByRole("heading", { name: "Reverse proxy" })).toBeVisible();
  await main.getByRole("button", { name: "Copy environment names" }).click();
});
