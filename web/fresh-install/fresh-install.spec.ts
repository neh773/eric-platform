/**
 * A brand-new install, used the way a new person would: open /admin, create the administrator account in the
 * first-run form, sign out, sign in again, then load the admin pages, Control Canvas and the manual.
 * The gateway under test must have an empty data directory and no ERIC_ADMIN_PASSWORD.
 */
import { expect, test } from "@playwright/test";
import { randomBytes } from "node:crypto";

const username = "firstadmin";
const password = `Fresh-${randomBytes(9).toString("base64url")}`;

test("a fresh install can create its administrator and serve every web app", async ({ page }) => {
  const setup = await (await page.request.get("/api/v1/setup")).json();
  expect(setup).toEqual({ needsAdmin: true, fromThisMachine: true });

  await page.goto("/admin");
  const form = page.getByRole("form", { name: "Create the administrator" });
  await expect(form.getByRole("heading", { name: "Create administrator account" })).toBeVisible();
  await form.getByLabel("Administrator username").fill(username);
  await form.getByLabel("New password").fill(password);
  await form.getByLabel("Confirm password").fill(password);
  await form.getByRole("button", { name: "Create administrator" }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();

  await page.getByRole("button", { name: "Log out" }).click();
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("form", { name: "Create the administrator" })).toHaveCount(0);
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();

  const again = await page.request.post("/api/v1/setup/admin", { headers: { "Content-Type": "application/json" }, data: { username: "second", password } });
  expect(again.status()).toBe(409);

  await page.goto("/designer");
  await expect(page).toHaveTitle(/Control Canvas/);
  await expect(page.locator("#root")).not.toBeEmpty();

  await page.goto("/docs/");
  await expect(page).toHaveTitle(/ERIC manual/);
  await expect(page.getByRole("heading").first()).toBeVisible();
});
