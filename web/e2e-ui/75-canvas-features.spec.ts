import { expect, test } from "@playwright/test";
import { login, openStudio, PROJECT, studioSave } from "./ui";

/**
 * Every palette item, placed by clicking it in the Add panel, saves and renders in a published session without a
 * script error. Plus the project-level editors a user reaches from the Studio side tabs.
 */
test.describe("Control Canvas features", () => {
  test.beforeEach(async ({ page }) => login(page));

  test("every palette component can be added, saved and shown at /hmi", async ({ page }) => {
    test.setTimeout(600_000);
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await openStudio(page);
    const tree = page.getByRole("tree").first();
    if (!(await tree.getByText("palette", { exact: true }).count())) {
      await page.getByPlaceholder("New view name").fill("palette");
      await page.getByRole("button", { name: "Add view", exact: true }).click();
    } else {
      await tree.getByText("palette", { exact: true }).first().click();
    }
    await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: "Add", exact: true }).click();
    await page.getByLabel("Search components").fill("");
    const items = page.locator("[data-palette-item]");
    const names = await items.evaluateAll((els) => els.map((e) => (e.textContent ?? "").trim()));
    expect(names.length).toBeGreaterThan(20);
    const failed: string[] = [];
    for (const name of names) {
      if (/^Add view( canvas)?$/.test(name)) continue;
      try {
        await page.locator("[data-palette-item]", { hasText: name }).first().click({ timeout: 5_000 });
      } catch {
        failed.push(name);
      }
    }
    test.info().annotations.push({ type: "palette", description: `${names.length} items; could not add: ${failed.join(", ") || "none"}` });
    expect(failed, "palette items that could not be added").toEqual([]);
    await studioSave(page);
    expect(errors, "script errors while adding components").toEqual([]);
    await page.goto(`/hmi/${PROJECT}/palette`);
    await page.waitForTimeout(5_000);
    expect(errors, "script errors in the published session").toEqual([]);
  });

  for (const tab of ["Tags", "Styles", "Scripts", "Pages", "Project", "Tools", "Find", "Diagnostics", "Console"]) {
    test(`Studio panel ${tab} opens`, async ({ page }) => {
      const errors: string[] = [];
      page.on("pageerror", (e) => errors.push(e.message));
      await openStudio(page);
      await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: tab, exact: true }).click();
      await page.waitForTimeout(800);
      expect(errors).toEqual([]);
    });
  }

  test("tag browser in Control Canvas lists gateway tags", async ({ page }) => {
    await openStudio(page);
    await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: "Tags", exact: true }).click();
    const tree = page.getByRole("tree", { name: "Tags" });
    await expect(tree.getByRole("treeitem", { name: "default" })).toBeVisible();
    await tree.getByRole("treeitem", { name: "default" }).click();
    await expect(tree.getByRole("treeitem", { name: "LiftStation", exact: true })).toBeVisible();
  });

  test("Pages: customize pages so / opens the station screen, and the session follows it", async ({ page }) => {
    await openStudio(page);
    await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name: "Pages", exact: true }).click();
    await page.getByRole("button", { name: "Customize pages" }).click();
    const pages = page.getByRole("region", { name: "Page configuration" });
    await expect.poll(() => pages.getByRole("textbox", { name: /^Page \d+ URL$/ }).evaluateAll((inputs) => inputs.map((input) => (input as HTMLInputElement).value))).toContain("/station");
    await pages.getByRole("combobox", { name: "Home view" }).selectOption("station");
    await studioSave(page);
    await page.goto(`/hmi/${PROJECT}`);
    await expect(page.getByText("Wet well", { exact: true })).toBeVisible();
  });
});
