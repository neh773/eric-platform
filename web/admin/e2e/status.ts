import { expect, type Browser, type Page } from "@playwright/test";
import { PASSWORD, csrf, login, stamp } from "./gateway";

/**
 * Helpers for the C-12 status page specs (overview, logs, diagnostics, projects).
 *
 * The default viewer role has no gateway.read or gateway.write. The default operator role has
 * gateway.read only, so it shows the read-only layout of these pages.
 */
export async function withRole(browser: Browser, admin: Page, role: "viewer" | "operator", run: (page: Page) => Promise<void>) {
  const username = stamp(role === "viewer" ? "view" : "oper");
  const token = await csrf(admin);
  const res = await admin.request.post("/api/v1/security/users", {
    data: { username, password: PASSWORD, roles: [role] },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await login(page, username, PASSWORD);
    await run(page);
  } finally {
    await context.close();
  }
}

/** Text that must never reach a status page. */
export async function expectNoSecrets(page: Page) {
  const body = page.locator("body");
  await expect(body).not.toContainText(PASSWORD);
  await expect(body).not.toContainText("passwordHash");
  await expect(body).not.toContainText("keyHash");
  await expect(body).not.toContainText("csrfToken");
  await expect(body).not.toContainText("PRIVATE KEY");
}
