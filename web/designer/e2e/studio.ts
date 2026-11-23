import { expect, type APIRequestContext, type Page } from "@playwright/test";

/** Shared test fixture. Matches gateway AuthSupport.TEST_PASSWORD and admin/e2e/gateway.ts. Not a deployed secret. */
export const PASSWORD = "Correct-horse-1";

/** A project name no other test uses, so each test starts from an empty gateway project. */
export function uniqueProject(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/** Sign in through the API; the browser context keeps the session cookie. Returns the CSRF token. */
export async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = (await res.json()) as { csrfToken?: string };
  if (!body.csrfToken) throw new Error("login did not return a CSRF token");
  return body.csrfToken;
}

/** Signed-in Studio on [project]. Pass [csrf] when this page already signed in; a second login replaces the session. */
export async function openStudio(page: Page, project: string, existingCsrf?: string): Promise<string> {
  const csrf = existingCsrf ?? (await signIn(page.request));
  // New gateway projects open blank; the specs build on the sample views, offered as unsaved drafts.
  await page.goto(`/?project=${project}&starter=sample`);
  await expect(page.getByRole("tree").first()).toBeVisible();
  return csrf;
}

/** The live design-mode canvas (D-1): the rendered view plus its editing overlay. */
export function canvas(page: Page) {
  return page.getByTestId("canvas");
}

/** A rendered node on the canvas, by view node id. */
export function canvasNode(page: Page, id: string) {
  return canvas(page).locator(`.oip-canvas-render [data-node-id='${id}']`);
}

/** Selects node [id] by clicking the canvas overlay near the node's top-left corner (inside any child that starts later). */
export async function selectOnCanvas(page: Page, id: string): Promise<void> {
  const box = await canvasNode(page, id).first().boundingBox();
  if (!box) throw new Error(`node ${id} is not on the canvas`);
  await page.mouse.click(box.x + Math.min(6, box.width / 2), box.y + Math.min(6, box.height / 2));
  await expect(page.getByRole("application", { name: "View canvas" })).toHaveAttribute("data-selected-node", id);
}

/** Opens one of the Studio's right-column tabs: "Add", "Tags", "Styles" or "Scripts". */
export async function openPanel(page: Page, name: "Add" | "Tags" | "Styles" | "Scripts"): Promise<void> {
  await page.getByRole("tablist", { name: "Studio panels" }).getByRole("tab", { name, exact: true }).click();
}

/**
 * Waits until the dev server's test hook is installed. The app imports it lazily after the first render, so on a
 * cold dev server it can arrive after the page looks ready, and a call through `window.__oipTest?.` would do nothing.
 */
export async function waitForTestHook(page: Page): Promise<void> {
  await page.waitForFunction(() => window.__oipTest !== undefined);
}

/** Sets a mock tag value through the dev test hook, waiting for the hook first. */
export async function setTag(page: Page, path: string, value: string | number | boolean): Promise<void> {
  await waitForTestHook(page);
  await page.evaluate(
    ([name, next]) => {
      const hook = window.__oipTest;
      if (!hook) throw new Error("test hook is not installed");
      hook.setTag(name as string, next as string | number | boolean);
    },
    [path, value] as const,
  );
}
