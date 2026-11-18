import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/** Project defaults (DES-004, HMP-083): a saved session theme is what a new HMI session opens with. */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

test("a project session theme is what the HMI session opens with", async ({ page }) => {
  const run = stamp("ps");
  const project = `${run}-plant`;
  const csrf = await signIn(page.request);
  const view = await page.request.put(`/api/v1/config/designer/project/${project}/View/home`, {
    headers: { "X-CSRF-Token": csrf },
    data: {
      apiVersion: "oip/v1",
      kind: "View",
      metadata: { name: "home", module: "designer", version: 0 },
      spec: {
        path: "home",
        title: "Home",
        document: { params: {}, width: 200, height: 80, root: { id: "root", kind: "container", name: "Home", layout: "flex", props: {}, children: [] } },
      },
    },
  });
  expect(view.ok(), await view.text()).toBeTruthy();
  const patched = await page.request.patch(`/api/v1/projects/${project}`, {
    headers: { "X-CSRF-Token": csrf },
    data: { defaultTagProvider: "default", defaultDatabase: "plant", sessionTheme: "nightwatch", sessionLocale: "en-GB" },
  });
  expect(patched.ok(), await patched.text()).toBeTruthy();
  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-session")).toHaveAttribute("data-theme", "nightwatch");
  await expect(page.locator(".oip-session")).toHaveAttribute("data-locale", "en-GB");
});
