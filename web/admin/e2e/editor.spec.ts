import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * P1-24: edit hello-world config from its JSON Schema.
 * Invalid input shows a schema error and is not saved. A stale version shows a reload prompt.
 */
const PASSWORD = "Correct-horse-1";
const MODULE = "org.oip.hello-world";
const RESOURCE = `/api/v1/config/${MODULE}/gateway/ModuleConfig/default`;
const zipPath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../modules/hello-world/build/module-package/hello-world.oipmodule.zip",
);

async function login(page: Page) {
  await page.goto("/admin/login");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
}

async function csrf(page: Page): Promise<string> {
  const session = await page.request.get("/api/v1/auth/session");
  expect(session.ok()).toBeTruthy();
  const body = (await session.json()) as { csrfToken?: string };
  if (!body.csrfToken) throw new Error("session did not return a CSRF token");
  return body.csrfToken;
}

test("edits hello-world config, blocks invalid input, and surfaces a version conflict", async ({ page }) => {
  await login(page);
  const token = await csrf(page);
  const installed = await page.request.post("/api/v1/modules", {
    data: readFileSync(zipPath),
    headers: { "Content-Type": "application/zip", "X-CSRF-Token": token },
  });
  expect([201, 409]).toContain(installed.status());

  await page.goto("/admin/config");
  await page.getByRole("link", { name: `ModuleConfig default ${MODULE}` }).click();
  await expect(page).toHaveURL(/kind=ModuleConfig/);
  await expect(page).toHaveURL(/name=default/);
  const greeting = page.getByRole("textbox", { name: "Greeting" });
  await expect(greeting).toBeVisible();

  const next = `Hello, plant ${Date.now()}`;
  await greeting.fill(next);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("textbox", { name: "Greeting" })).toHaveValue(next);

  await page.getByRole("textbox", { name: "Greeting" }).fill("");
  await expect(page.getByRole("alert").filter({ hasText: "At least 1 character" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
  const emptyCheck = await page.request.get(RESOURCE);
  expect(((await emptyCheck.json()) as { spec: { greeting: string } }).spec.greeting).toBe(next);

  await page.getByRole("textbox", { name: "Greeting" }).fill("x".repeat(201));
  await expect(page.getByRole("alert").filter({ hasText: "At most 200 characters" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
  const longCheck = await page.request.get(RESOURCE);
  expect(((await longCheck.json()) as { spec: { greeting: string } }).spec.greeting).toBe(next);

  await page.getByRole("textbox", { name: "Greeting" }).fill(`${next} local`);
  const current = await page.request.get(RESOURCE);
  expect(current.ok()).toBeTruthy();
  const doc = (await current.json()) as { metadata: { version: number }; spec: { greeting: string } };
  const elsewhere = `Elsewhere ${Date.now()}`;
  doc.spec.greeting = elsewhere;
  const bumped = await page.request.put(RESOURCE, {
    data: doc,
    headers: {
      "Content-Type": "application/json",
      "X-CSRF-Token": token,
      "If-Match": String(doc.metadata.version),
    },
  });
  expect(bumped.ok()).toBeTruthy();

  await page.getByRole("button", { name: "Save" }).click();
  const conflict = page.getByTestId("version-conflict");
  await expect(conflict).toBeVisible();
  await expect(conflict).toContainText("Reload");
  const stored = await page.request.get(RESOURCE);
  expect(((await stored.json()) as { spec: { greeting: string } }).spec.greeting).toBe(elsewhere);

  await conflict.getByRole("button", { name: "Reload" }).click();
  await expect(page.getByRole("textbox", { name: "Greeting" })).toHaveValue(elsewhere);
  await expect(page.locator("body")).not.toContainText("passwordHash");
});
