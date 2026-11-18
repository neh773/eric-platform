import { expect, test } from "@playwright/test";
import { login, openSection, stamp } from "./gateway";

/**
 * A text endpoint is saved and served at /system/webdev/gateway/{path}.
 * Doc source: FEATURES WEB-001, WEB-003.
 */

test("serves a text endpoint created on the HTTP page", async ({ page }) => {
  const name = stamp("web");
  const body = `hello ${name}`;
  await login(page);
  await openSection(page, "HTTP Endpoints");
  const main = page.getByRole("main");
  const form = main.getByRole("form", { name: "New endpoint" });
  await form.getByLabel("Endpoint name").fill(name);
  await form.getByLabel("Path").fill(name);
  await form.getByRole("combobox", { name: "Type", exact: true }).selectOption("text");
  await form.getByLabel("Body").fill(body);
  await form.getByRole("button", { name: "Save endpoint" }).click();
  await expect(main.getByRole("status")).toContainText(`/system/webdev/gateway/${name}`);
  const served = await page.request.get(`/system/webdev/gateway/${name}`);
  expect(served.status()).toBe(200);
  expect(await served.text()).toBe(body);
  await form.getByRole("checkbox", { name: "Edit as JSON" }).check();
  await expect(form.getByLabel("Endpoint JSON")).toHaveValue(/"type": "text"/);
  await expect(main.getByRole("region", { name: "API listing" }).getByRole("cell", { name: "/api/v1/health", exact: true })).toBeVisible();
  await main.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(main.getByRole("status")).toContainText(`Deleted ${name}.`);
});
