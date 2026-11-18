import { expect, test, type Page } from "@playwright/test";

/** C-18: process symbols driven by mock tags, and menus navigating between two session pages. */

const types = ["pump", "valve", "motor", "vessel", "sensor", "cylindrical-tank", "horizontal-menu", "menu-tree", "tree"];

async function openGallery(page: Page) {
  await page.goto("/hmi/_sample/symbols");
  await page.waitForFunction(() => window.__oipTest !== undefined);
  await page.evaluate(() => {
    window.__oipTest?.setTag("c18/pumpState", "stopped");
    window.__oipTest?.setTag("c18/valveState", "closed");
    window.__oipTest?.setTag("c18/level", 40);
    window.__oipTest?.setTag("c18/pressure", 4.2);
  });
}

function setTag(page: Page, path: string, value: string | number | boolean) {
  return page.evaluate(([name, next]) => window.__oipTest?.setTag(name as string, next as string | number | boolean), [path, value] as const);
}

test("the symbols page renders every C-18 component", async ({ page }) => {
  await openGallery(page);
  for (const type of types) {
    const node = page.locator(`[data-component='${type}']`).first();
    await expect(node, type).toBeVisible();
  }
  await expect(page.getByRole("img", { name: "XV-201: closed" })).toBeVisible();
  await expect(page.getByRole("img", { name: "PT-401: 4.20 bar" })).toBeVisible();
  await expect(page.getByRole("tree", { name: "Asset tree" })).toBeVisible();
});

test("a mock tag drives the pump state and the tank level", async ({ page }) => {
  await openGallery(page);
  const pump = page.locator("[data-component='pump'] .oip-symbol");
  await expect(pump).toHaveAttribute("data-state", "stopped");
  await expect(page.getByRole("img", { name: "P-101: stopped" })).toBeVisible();
  await setTag(page, "c18/pumpState", "running");
  await expect(pump).toHaveAttribute("data-state", "running");
  await setTag(page, "c18/pumpState", 3);
  await expect(page.getByRole("img", { name: "P-101: fault" })).toHaveAttribute("data-abnormal", "true");
  const faultStroke = await pump.locator(".oip-symbol-body").first().evaluate((element) => getComputedStyle(element).stroke);
  expect(faultStroke).toBe("rgb(255, 69, 58)");

  const tank = page.locator("[data-component='cylindrical-tank'] .oip-symbol");
  await expect(tank).toHaveAttribute("data-level", "40.0");
  await expect(page.getByRole("img", { name: "T-501: level 40%" })).toBeVisible();
  await setTag(page, "c18/level", 95);
  await expect(page.getByRole("img", { name: "T-501: level 95%, high level" })).toHaveAttribute("data-alarm", "high");
  await setTag(page, "c18/level", 250);
  await expect(tank).toHaveAttribute("data-level", "100.0");
  await setTag(page, "c18/level", -5);
  await expect(tank).toHaveAttribute("data-level", "0.0");
});

test("menu items navigate to their own pages through the page runtime", async ({ page }) => {
  await openGallery(page);
  const menu = page.getByRole("menubar", { name: "Symbols menu" });
  await expect(menu.getByRole("menuitem", { name: "Overview" })).toHaveAttribute("aria-current", "page");
  await menu.getByRole("menuitem", { name: "Areas" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/symbols$/);
  await page.getByRole("menu", { name: "Areas" }).getByRole("menuitem", { name: "Area 1" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/symbols-area$/);
  await expect(page.locator("[data-primary-view='c18-area']")).toBeVisible();
  await expect(page.getByRole("img", { name: /T-502: level/ })).toBeVisible();

  // A second item of the same menu goes to a different page.
  const areaMenu = page.getByRole("menubar", { name: "Symbols menu" });
  await areaMenu.getByRole("menuitem", { name: "Areas" }).click();
  await page.getByRole("menu", { name: "Areas" }).getByRole("menuitem", { name: "Area 2" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/pumps$/);
  await expect(page.locator("[data-page-url='/pumps']")).toBeVisible();

  // One history entry per click, so back returns to the previous page.
  await page.goBack();
  await expect(page).toHaveURL(/\/hmi\/_sample\/symbols-area$/);
  await page.getByRole("menubar", { name: "Symbols menu" }).getByRole("menuitem", { name: "Overview" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/symbols$/);
  await expect(page.locator("[data-primary-view='c18-gallery']")).toBeVisible();
});

test("the horizontal menu works from the keyboard and its hrefs carry the session base", async ({ page }) => {
  await openGallery(page);
  const menu = page.getByRole("menubar", { name: "Symbols menu" });
  await expect(menu.getByRole("menuitem", { name: "Overview" })).toHaveAttribute("href", "/hmi/_sample/symbols");
  await menu.getByRole("menuitem", { name: "Overview" }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(menu.getByRole("menuitem", { name: "Areas" })).toBeFocused();
  await page.keyboard.press("ArrowDown");
  const area1 = page.getByRole("menu", { name: "Areas" }).getByRole("menuitem", { name: "Area 1" });
  await expect(area1).toBeFocused();
  await expect(area1).toHaveAttribute("href", "/hmi/_sample/symbols-area");
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("menu", { name: "Areas" }).getByRole("menuitem", { name: "Area 2" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/hmi\/_sample\/pumps$/);
});

test("the menu tree drills down and navigates a leaf", async ({ page }) => {
  await openGallery(page);
  const site = page.getByRole("navigation", { name: "Site menu" });
  await site.getByRole("button", { name: "Areas, 2 items" }).click();
  await site.getByRole("link", { name: "Area 2" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/pumps$/);
});

test("the tree expands and selects by item path", async ({ page }) => {
  await openGallery(page);
  const tree = page.getByRole("tree", { name: "Asset tree" });
  const area1 = tree.getByRole("treeitem", { name: "Area 1" });
  await expect(area1).toHaveAttribute("aria-expanded", "false");
  await area1.locator(".oip-tree-twisty").click();
  await expect(area1).toHaveAttribute("aria-expanded", "true");
  await tree.getByRole("treeitem", { name: "T-501" }).click();
  await expect(tree.getByRole("treeitem", { name: "T-501" })).toHaveAttribute("aria-selected", "true");
  await expect(tree.getByRole("treeitem", { name: "T-501" })).toHaveAttribute("data-path", "0/0/1");
  await expect(page).toHaveURL(/\/hmi\/_sample\/symbols$/);
});
