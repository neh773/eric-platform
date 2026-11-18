import { expect, type Browser, type Page } from "@playwright/test";

/** Shared test fixture. Matches gateway AuthSupport.TEST_PASSWORD. Not a deployed secret. */
export const PASSWORD = "Correct-horse-1";

export async function login(page: Page, username = "admin", password = PASSWORD) {
  await page.goto("/admin/login");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("navigation", { name: "Gateway sections" })).toBeVisible();
}

export async function csrf(page: Page): Promise<string> {
  const session = await page.request.get("/api/v1/auth/session");
  expect(session.ok()).toBeTruthy();
  const body = (await session.json()) as { csrfToken?: string };
  if (!body.csrfToken) throw new Error("session did not return a CSRF token");
  return body.csrfToken;
}

export function stamp(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}`;
}

export async function openSection(page: Page, name: string) {
  await page.getByRole("navigation", { name: "Gateway sections" }).getByRole("link", { name, exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name, exact: true })).toBeVisible();
}

export async function createViewer(page: Page): Promise<string> {
  const username = stamp("view");
  const token = await csrf(page);
  const res = await page.request.post("/api/v1/security/users", {
    data: { username, password: PASSWORD, roles: ["viewer"] },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": token },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  return username;
}

/** Sign in as a new viewer without replacing the admin page's cookie. */
export async function withViewer(browser: Browser, admin: Page, run: (page: Page) => Promise<void>) {
  const username = await createViewer(admin);
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await login(page, username, PASSWORD);
    await run(page);
  } finally {
    await context.close();
  }
}
