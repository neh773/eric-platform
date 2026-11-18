import { expect, test, type Page } from "@playwright/test";

/** C-11 batch 1: all twelve components on one session page, writing to the in-memory (mock) tag server. */

const types = [
  "simple-gauge",
  "progress-bar",
  "icon",
  "sparkline",
  "datetime-input",
  "datetime-picker",
  "password-field",
  "radio-group",
  "text-area",
  "one-shot-button",
  "multi-state-button",
  "link",
];

async function openGallery(page: Page) {
  await page.goto("/hmi/_sample/components");
  await page.waitForFunction(() => window.__oipTest !== undefined);
  await page.evaluate(() => {
    window.__oipTest?.setTag("c11/level", 40);
    window.__oipTest?.setTag("c11/reset", 0);
    window.__oipTest?.setTag("c11/mode", 0);
    window.__oipTest?.setTag("c11/recipe", "r1");
    window.__oipTest?.setTag("c11/notes", "");
    window.__oipTest?.setTag("c11/pin", "");
  });
}

function tag(page: Page, path: string) {
  return page.evaluate((name) => window.__oipTest?.getTag(name), path);
}

test("a session page renders all twelve C-11 components", async ({ page }) => {
  await openGallery(page);
  for (const type of types) {
    const node = page.locator(`[data-component='${type}']`);
    await expect(node, type).toHaveCount(1);
    await expect(node, type).toBeVisible();
  }
  await expect(page.getByRole("meter", { name: "Tank level" })).toHaveAttribute("aria-valuenow", "40");
  await expect(page.getByRole("progressbar", { name: "Batch progress" })).toHaveAttribute("aria-valuenow", "40");
  await expect(page.getByRole("img", { name: "Pump icon" })).toHaveAttribute("data-icon", "pump");
  await expect(page.getByRole("img", { name: /Flow history, 6 points/ })).toBeVisible();
  await expect(page.getByLabel("Batch start")).toHaveAttribute("type", "datetime-local");
  await expect(page.getByRole("group", { name: "Due date" })).toBeVisible();
  await expect(page.getByLabel("Operator PIN")).toHaveAttribute("type", "password");
  await expect(page.getByRole("radiogroup", { name: "Recipe" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Shift notes" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reset counter" })).toBeEnabled();
  await expect(page.getByRole("group", { name: "Pump mode" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Go to pumps" })).toBeVisible();
  const ink = await page
    .locator(".oip-session")
    .evaluate((element) => getComputedStyle(element).getPropertyValue("--oip-color-ink").trim());
  expect(ink).toBe("#f2f4f7");
});

test("one-shot, radio and multi-state writes reach the mock tags", async ({ page }) => {
  await openGallery(page);
  const mode = page.getByRole("group", { name: "Pump mode" });
  await expect(mode.getByRole("button", { name: "Off" })).toHaveAttribute("aria-pressed", "true");
  await mode.getByRole("button", { name: "Auto" }).click();
  await expect.poll(() => tag(page, "c11/mode")).toBe(2);
  await expect(mode.getByRole("button", { pressed: true })).toHaveText(["Auto"]);

  await expect(page.getByRole("radio", { name: "Recipe 1" })).toBeChecked();
  await page.getByRole("radio", { name: "Recipe 3" }).check();
  await expect.poll(() => tag(page, "c11/recipe")).toBe("r3");
  await expect(page.getByRole("radio", { name: "Recipe 3" })).toBeChecked();

  const reset = page.getByRole("button", { name: "Reset counter" });
  await reset.click();
  await expect.poll(() => tag(page, "c11/reset")).toBe(1);
  await expect(reset).toBeEnabled();
});

test("the password field writes without exposing the value, and the link navigates through the page runtime", async ({ page }) => {
  await openGallery(page);
  const pin = page.getByLabel("Operator PIN");
  await pin.fill("7351-secret");
  await pin.press("Enter");
  await expect.poll(() => tag(page, "c11/pin")).toBe("7351-secret");
  expect(await page.content()).not.toContain("7351-secret");
  expect(await page.locator("body").innerText()).not.toContain("7351-secret");

  await page.getByRole("link", { name: "Go to pumps" }).click();
  await expect(page).toHaveURL(/\/hmi\/_sample\/pumps$/);
  await expect(page.getByText("Pump list")).toBeVisible();
});
