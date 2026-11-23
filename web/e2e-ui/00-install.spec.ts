import { expect, test } from "@playwright/test";
import { login, main, section } from "./ui";

/** A fresh install serves the admin page, Control Canvas and the manual without any environment variables. */
test.describe("fresh install", () => {
  test("admin sign-in page, Control Canvas and the manual load", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/admin/);
    await login(page);
    await section(page, "Home");
    await expect(main(page).getByText("Finish setting up this gateway")).toBeVisible();

    await page.goto("/studio/");
    await expect(page.getByText("Control Canvas", { exact: false }).first()).toBeVisible();

    await page.goto("/docs");
    await expect(page.locator("body")).toContainText(/ERIC/);
    await expect(page.locator("body")).not.toContainText(/not found|404/i);
  });

  test("home page links to Control Canvas and the manual", async ({ page }) => {
    await login(page);
    await section(page, "Home");
    await expect(main(page).getByRole("link", { name: /Control Canvas/i }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: /manual/i }).first()).toBeVisible();
  });

  test("first-run steps can be completed from the home page", async ({ page }) => {
    await login(page);
    await section(page, "Gateway Settings");
    await main(page).getByLabel("System name").fill("audit-gateway");
    await main(page).getByRole("button", { name: "Save settings" }).click();
    await expect(main(page).getByText(/saved/i).first()).toBeVisible();
    await section(page, "Home");
    await expect(main(page).getByText("Name this gateway").locator("..")).toContainText("done");
  });
});
