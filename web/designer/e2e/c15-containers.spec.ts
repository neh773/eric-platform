import { expect, test, type Page } from "@playwright/test";

/** C-15: a session resize flips the breakpoint child, and a flex repeater bound to a mock tag list grows and shrinks. */

async function openGallery(page: Page) {
  await page.goto("/hmi/_sample/containers");
  await page.waitForFunction(() => window.__oipTest !== undefined);
  await expect(page.locator("[data-primary-view='c15-gallery']")).toBeVisible();
}

function setInstances(page: Page, names: string[]) {
  const value = JSON.stringify(names.map((name) => ({ name })));
  return page.evaluate((next) => window.__oipTest?.setTag("c15/instances", next), value);
}

test("a session resize flips the breakpoint child", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openGallery(page);
  const container = page.locator("[data-layout='breakpoint']");
  await expect(container).toHaveAttribute("data-current-breakpoint", "large");
  await expect(container.getByText("Full layout")).toBeVisible();
  await expect(container.getByText("Compact layout")).toHaveCount(0);

  await page.setViewportSize({ width: 820, height: 900 });
  await expect(container).toHaveAttribute("data-current-breakpoint", "small");
  await expect(container.getByText("Compact layout")).toBeVisible();
  await expect(container.getByText("Full layout")).toHaveCount(0);

  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(container).toHaveAttribute("data-current-breakpoint", "large");
  await expect(container.getByText("Full layout")).toBeVisible();
});

test("a flex repeater bound to a mock tag list grows and shrinks", async ({ page }) => {
  await openGallery(page);
  const list = page.getByRole("list", { name: "Pump cards" });
  await setInstances(page, ["P-101", "P-102"]);
  await expect(list.getByRole("listitem")).toHaveCount(2);
  await expect(list.getByRole("listitem").nth(1)).toContainText("P-102");

  await setInstances(page, ["P-101", "P-102", "P-103", "P-104", "P-105"]);
  await expect(list.getByRole("listitem")).toHaveCount(5);
  await expect(list).toHaveAttribute("data-instance-count", "5");
  await expect(list.getByRole("listitem").nth(4)).toContainText("P-105");

  await setInstances(page, ["T-201"]);
  await expect(list.getByRole("listitem")).toHaveCount(1);
  await expect(list.getByRole("listitem").first()).toContainText("T-201");

  await setInstances(page, []);
  await expect(list.getByRole("listitem")).toHaveCount(0);
});

test("the split divider drags within its min sizes, and the carousel and accordion embed views", async ({ page }) => {
  await openGallery(page);
  const divider = page.getByRole("separator", { name: "Resize Detail panes" });
  await expect(divider).toHaveAttribute("aria-valuenow", "240");
  const box = await divider.boundingBox();
  if (!box) throw new Error("no divider box");
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x - 400, box.y + box.height / 2, { steps: 4 });
  await page.mouse.up();
  await expect(divider).toHaveAttribute("aria-valuenow", "120");
  await divider.focus();
  await page.keyboard.press("End");
  const max = Number(await divider.getAttribute("aria-valuemax"));
  expect(max).toBeGreaterThan(120);
  await expect(divider).toHaveAttribute("aria-valuenow", String(max));

  const carousel = page.getByRole("region", { name: "Line carousel" });
  await expect(carousel).toContainText("Line 1");
  await carousel.getByRole("button", { name: "Next view" }).click();
  await expect(carousel).toContainText("Line 2");

  await expect(page.getByText("North pumps")).toBeVisible();
  await page.getByRole("button", { name: "South area" }).click();
  await expect(page.getByText("South pumps")).toBeVisible();
  await expect(page.getByText("North pumps")).toHaveCount(0);
});
