import { expect, test, type APIRequestContext, type Browser } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * Review-4 F3, F4, F5, F8 and F9 on the real gateway:
 * - a viewer runs a project at `/hmi` (tags.read) and still cannot read the gateway configuration;
 * - a Studio save is seen by an operator's separate `/hmi` session without a reload;
 * - a gateway.write user without a designer role is refused by the Studio and by the config API;
 * - a view with a `javascript:` navigation URL is refused on save;
 * - `/hmi` carries a Content-Security-Policy, and works on a phone-sized screen (HMP-081).
 */

async function signIn(request: APIRequestContext, username = "admin"): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username, password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const metadata = { ...(resource.metadata as Record<string, unknown>), version };
  return request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata } });
}

function view(path: string, children: unknown[]) {
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: path, module: "designer" },
    spec: {
      path,
      title: path,
      document: {
        params: {},
        width: 360,
        height: 240,
        root: { id: `${path}-root`, kind: "container", name: path, layout: "flex", props: { direction: "column", alignItems: "flex-start" }, children },
      },
    },
  };
}

async function user(request: APIRequestContext, csrf: string, username: string, roles: string[]) {
  const res = await request.post("/api/v1/security/users", {
    data: { username, password: PASSWORD, roles },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function contextFor(browser: Browser, baseURL: string, username: string, viewport = { width: 1280, height: 800 }) {
  const context = await browser.newContext({ baseURL, viewport });
  await signIn(context.request, username);
  return context;
}

test("a viewer runs a project, an operator sees a Studio save live, and design writes need a designer role", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("acc");
  const project = run;
  const folder = run;
  const csrf = await signIn(page.request);
  const tagName = `${run}-level`;
  expect(
    (
      await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${tagName}`, {
        apiVersion: "oip/v1",
        kind: "Tag",
        metadata: { name: tagName, module: "tags" },
        spec: { path: `${folder}/level`, type: "memory", dataType: "Double", value: 12.5 },
      })
    ).ok(),
  ).toBeTruthy();
  const level = {
    id: "level",
    kind: "component",
    name: "level",
    type: "label",
    props: { text: "" },
    bindings: { text: { kind: "tag", mode: "direct", path: `[default]${folder}/level`, transforms: [{ kind: "format", type: "numeric", mode: "number" }] } },
  };
  const saved = await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, view("home", [level]));
  expect(saved.ok(), await saved.text()).toBeTruthy();

  const viewerName = `${run}-viewer`;
  const operatorName = `${run}-op`;
  const editorName = `${run}-cfg`;
  await user(page.request, csrf, viewerName, ["viewer"]);
  await user(page.request, csrf, operatorName, ["operator"]);
  const role = `${run}-config`;
  const created = await page.request.post("/api/v1/security/roles", {
    data: { name: role, permissions: ["gateway.write", "tags.read"] },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  await user(page.request, csrf, editorName, [role]);

  // F5: a viewer (tags.read, no gateway.read) runs the project and sees the live value.
  const viewer = await contextFor(browser, baseURL, viewerName);
  const viewerPage = await viewer.newPage();
  const response = await viewerPage.goto(`/hmi/${project}/`);
  // F8: the page carries its security headers.
  const csp = response?.headers()["content-security-policy"] ?? "";
  expect(csp).toContain("script-src 'self'");
  expect(csp).toContain("frame-ancestors 'self'");
  expect(response?.headers()["x-content-type-options"]).toBe("nosniff");
  await expect(viewerPage.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  await expect(viewerPage.locator("[data-node-id='level']")).toContainText("12.5");
  expect((await viewer.request.get("/api/v1/config")).status()).toBe(403);
  expect((await viewer.request.get(`/api/v1/config/designer/project/${project}/View/home`)).status()).toBe(403);
  expect((await viewer.request.get(`/api/v1/hmi/projects/${project}`)).status()).toBe(200);
  await viewer.close();

  // F4: the Studio refuses a gateway.write user without a designer role, and so does the config API.
  const editor = await contextFor(browser, baseURL, editorName);
  const editorPage = await editor.newPage();
  await editorPage.goto(`/studio?project=${project}`);
  await expect(editorPage.getByRole("alert")).toContainText("not allowed to use Control Canvas");
  const editorCsrf = ((await (await editor.request.get("/api/v1/auth/session")).json()) as { csrfToken: string }).csrfToken;
  const refused = await put(editor.request, editorCsrf, `/api/v1/config/designer/project/${project}/View/home`, view("home", []));
  expect(refused.status()).toBe(403);
  expect(await refused.text()).toContain("designer.login");
  await editor.close();

  // F3: a javascript: navigation URL is refused on save.
  const script = await put(
    page.request,
    csrf,
    `/api/v1/config/designer/project/${project}/View/bad`,
    view("bad", [{ id: "go", kind: "component", name: "go", type: "button", props: { text: "Go" }, events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "url", url: "javascript:alert(document.cookie)" }] }] }]),
  );
  expect(script.status()).toBe(422);

  // F9: an operator's session follows a save made in the Studio, without a reload.
  const operator = await contextFor(browser, baseURL, operatorName);
  const operatorPage = await operator.newPage();
  await operatorPage.goto(`/hmi/${project}/`);
  await expect(operatorPage.locator("[data-node-id='level']")).toContainText("12.5");
  await page.goto(`/studio?project=${project}`);
  await expect(page.getByRole("tree").first()).toBeVisible();
  await page.getByRole("navigation", { name: "Project" }).getByRole("treeitem", { name: "home" }).click();
  await page.getByRole("button", { name: "Add label" }).click();
  await page.getByTestId("inspector").getByRole("textbox", { name: "Text" }).fill("Saved from the Studio");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /unsaved|saved|Saving/ })).toHaveText("All changes saved");
  await expect(operatorPage.getByText("Saved from the Studio")).toBeVisible({ timeout: 10_000 });
  // The session was not reloaded: the live value is still there, on the same document.
  await expect(operatorPage.locator("[data-node-id='level']")).toContainText("12.5");
  await operator.close();
});

test("an HMI page fits a phone screen (HMP-081)", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const project = stamp("phone");
  const csrf = await signIn(page.request);
  const children = Array.from({ length: 6 }, (_, index) => ({ id: `row${index}`, kind: "component", name: `row${index}`, type: "label", props: { text: `Line ${index + 1} running` } }));
  const saved = await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, view("home", children));
  expect(saved.ok(), await saved.text()).toBeTruthy();
  const phone = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await signIn(phone.request);
  const mobile = await phone.newPage();
  await mobile.goto(`/hmi/${project}/`);
  await expect(mobile.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  for (let index = 0; index < 6; index += 1) await expect(mobile.getByText(`Line ${index + 1} running`)).toBeVisible();
  // Nothing runs off the side of the screen.
  const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await phone.close();
});
