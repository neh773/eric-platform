import { expect, test } from "@playwright/test";
import { openStudio, uniqueProject } from "./studio";

/** DES-010: the Studio's panels dock left, right or bottom, collapse or hide, and the layout survives a reload. */
test.describe("dockable panels", () => {
  test("moves the property editor to the bottom dock, hides the outline, and keeps both after a reload", async ({ page }) => {
    const project = uniqueProject("dock");
    const csrf = await openStudio(page, project);
    await page.evaluate(() => window.localStorage.removeItem("oip.designer.dock-layout.v2"));
    await page.reload();
    await expect(page.getByRole("tree").first()).toBeVisible();

    const right = page.getByRole("group", { name: "Right dock", exact: true });
    await expect(right.getByRole("region", { name: "Property editor panel", exact: true })).toBeVisible();

    await page.getByRole("button", { name: "Dock Property editor bottom", exact: true }).click();
    const bottom = page.getByRole("group", { name: "Bottom dock", exact: true });
    await expect(bottom.getByTestId("inspector")).toBeVisible();
    await expect(right).toHaveCount(0);

    await page.getByRole("button", { name: "Collapse Outline", exact: true }).click();
    await expect(page.getByRole("button", { name: "Expand Outline", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Hide Outline", exact: true }).click();
    await expect(page.getByRole("region", { name: "Outline panel", exact: true })).toHaveCount(0);

    await page.reload();
    await openStudio(page, project, csrf);
    await expect(page.getByRole("group", { name: "Bottom dock", exact: true }).getByTestId("inspector")).toBeVisible();
    await expect(page.getByRole("region", { name: "Outline panel", exact: true })).toHaveCount(0);

    await page.getByRole("button", { name: "Show Outline", exact: true }).click();
    await expect(page.getByRole("region", { name: "Outline panel", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Reset layout", exact: true }).click();
    await expect(page.getByRole("group", { name: "Right dock", exact: true }).getByTestId("inspector")).toBeVisible();
  });
});
