import { expect, test } from "@playwright/test";
import { login, openSection, stamp, withViewer } from "./gateway";

/**
 * SEC-005: create, rotate, and revoke an API key.
 * The secret is shown once, with a copy control, and is not shown again.
 */
test.use({ permissions: ["clipboard-read", "clipboard-write"] });

test("shows an API key secret only on create and rotate", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const name = stamp("reader");
  await login(page);
  await openSection(page, "API Keys");

  const form = page.getByRole("form", { name: "Create API key" });
  await form.getByLabel("Name").fill(name);
  await form.getByRole("checkbox", { name: "gateway.read" }).check();
  await form.getByRole("button", { name: "Create API key" }).click();

  const panel = page.getByTestId("key-secret");
  await expect(panel).toBeVisible();
  const secret = (await page.getByTestId("key-secret-value").innerText()).trim();
  expect(secret.length).toBeGreaterThan(16);
  await expect(page.getByRole("table", { name: "API keys" })).not.toContainText(secret);
  await page.getByRole("button", { name: "Copy secret" }).click();
  await expect(panel.getByRole("status")).toHaveText("Copied.");
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(secret);

  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByTestId("key-secret")).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(secret);
  await expect(page.locator("body")).not.toContainText("keyHash");

  await page.reload();
  await expect(page.getByRole("row", { name: new RegExp(name) })).toContainText("Enabled");
  await expect(page.locator("body")).not.toContainText(secret);

  await page.getByRole("button", { name: `Rotate ${name}` }).click();
  await expect(page.getByTestId("key-secret")).toBeVisible();
  const rotated = (await page.getByTestId("key-secret-value").innerText()).trim();
  expect(rotated).not.toBe(secret);
  expect(rotated.length).toBeGreaterThan(16);
  await expect(page.locator("body")).not.toContainText(secret);
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.locator("body")).not.toContainText(rotated);
  await expect(page.locator("body")).not.toContainText(secret);

  await page.getByRole("button", { name: `Revoke ${name}` }).click();
  await expect(page.getByRole("row", { name: new RegExp(name) })).toContainText("Disabled");
  await page.reload();
  await expect(page.locator("body")).not.toContainText(secret);
  await expect(page.locator("body")).not.toContainText(rotated);
  await expect(page.locator("body")).not.toContainText("keyHash");

  await withViewer(browser, page, async (viewer) => {
    await openSection(viewer, "API Keys");
    await expect(viewer.getByRole("alert")).toContainText("apikeys.admin denied");
    await expect(viewer.getByRole("button", { name: "Create API key" })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: /Rotate / })).toHaveCount(0);
    await expect(viewer.getByRole("button", { name: /Revoke / })).toHaveCount(0);
    await expect(viewer.locator("body")).not.toContainText(secret);
  });
});
