import { expect, test } from "@playwright/test";

test("session pages, the side dock, and a popup", async ({ page }) => {
  await page.goto("/hmi/_sample/");
  await expect(page.getByText("Home", { exact: true })).toBeVisible();
  await expect(page.getByText("Navigation")).toBeVisible();
  await expect(page.getByTestId("numeric-value")).toContainText("62.4");
  await page.getByRole("button", { name: "Pumps" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/pumps$/);
  await expect(page.getByText("Pump list")).toBeVisible();
  await page.goto("/hmi/_sample/");
  await page.getByRole("button", { name: "Detail" }).click();
  await expect(page.getByRole("dialog", { name: "Area" })).toBeVisible();
  await expect(page.getByText("North")).toBeVisible();
  await page.getByRole("button", { name: "Close Area" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
