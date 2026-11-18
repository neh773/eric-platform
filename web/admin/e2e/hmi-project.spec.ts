import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * H-1: `/hmi/<project>/<page url>` on the gateway runs a saved project's pages as the signed-in user, shows live tag
 * values over `/api/v1/ws`, and follows saves without a reload. A browser session opens one project; each page maps
 * a URL to a primary view, and shared docks show beside every page.
 */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const metadata = { ...(resource.metadata as Record<string, unknown>), version };
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

async function memoryTag(request: APIRequestContext, csrf: string, path: string, value: number) {
  const name = path.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  await put(request, csrf, `/api/v1/config/tags/gateway/Tag/${name}`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name, module: "tags" },
    spec: { path, type: "memory", dataType: "Double", value },
  });
}

function view(path: string, title: string, children: unknown[]) {
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: path, module: "designer" },
    spec: {
      path,
      title,
      document: {
        params: {},
        width: 480,
        height: 240,
        root: { id: `${path}-root`, kind: "container", name: title, layout: "flex", props: { direction: "column", alignItems: "flex-start" }, children },
      },
    },
  };
}

function label(id: string, text: string, extra: Record<string, unknown> = {}) {
  return { id, kind: "component", name: id, type: "label", props: { text, align: "start" }, ...extra };
}

/** Screenshots for the PR, only when OIP_HMI_SHOTS names a folder. */
async function shot(page: import("@playwright/test").Page, name: string) {
  const folder = process.env.OIP_HMI_SHOTS;
  if (folder) await page.screenshot({ path: `${folder}/${name}.png`, fullPage: true });
}

test("a saved project runs at /hmi with live tags, page URLs, and saved changes", async ({ page, browser }) => {
  const folder = stamp("h1");
  const project = stamp("plant");
  const tagPath = `[default]${folder}/level`;
  const csrf = await signIn(page.request);
  const config = `/api/v1/config/designer/project/${project}`;
  await memoryTag(page.request, csrf, `${folder}/level`, 41.5);
  await put(
    page.request,
    csrf,
    `${config}/View/overview`,
    view("overview", "Overview", [
      label("heading", "Tank farm"),
      label("level", "", { bindings: { text: { kind: "tag", mode: "direct", path: tagPath, transforms: [{ kind: "format", type: "numeric", mode: "number" }] } } }),
      {
        id: "to-pumps",
        kind: "component",
        name: "Pumps",
        type: "button",
        props: { text: "Pumps", enabled: true },
        events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }],
      },
    ]),
  );
  await put(page.request, csrf, `${config}/View/pumps`, view("pumps", "Pumps", [label("pumps-title", "Pump list")]));
  await put(page.request, csrf, `${config}/View/nav`, view("nav", "Navigation", [label("nav-title", "Navigation")]));
  await put(page.request, csrf, `${config}/PageConfig/page-config`, {
    apiVersion: "oip/v1",
    kind: "PageConfig",
    metadata: { name: "page-config", module: "designer" },
    spec: {
      pages: [
        { url: "/", title: "Overview", primaryView: "overview" },
        { url: "/pumps", title: "Pumps", primaryView: "pumps" },
      ],
      sharedDocks: [{ id: "nav", edge: "left", view: "nav", size: 160 }],
    },
  });

  // The session runs the saved page, with the gateway tag over the session socket.
  const socket = page.waitForEvent("websocket", (ws) => ws.url().endsWith("/api/v1/ws"));
  await page.goto(`/hmi/${project}/`);
  const ws = await socket;
  const host = page.locator(".oip-hmi-host");
  await expect(host).toHaveAttribute("data-hmi-source", "gateway");
  await expect(host).toHaveAttribute("data-pages-from", "config");
  await expect(page.getByRole("note")).toHaveCount(0);
  await expect(page.getByText("Tank farm")).toBeVisible();
  await expect(page.locator("[data-dock-id='nav']").getByText("Navigation")).toBeVisible();
  const level = page.locator("[data-node-id='level'] .oip-label");
  await expect(level).toHaveText("41.5");
  const pushed = ws.waitForEvent("framereceived", (frame) => String(frame.payload).includes("77.25"));
  await memoryTag(page.request, csrf, `${folder}/level`, 77.25);
  await pushed;
  await expect(level).toHaveText("77.25");
  await shot(page, "hmi-saved-overview");

  // Page URLs: a navigation action, and a page opened from its URL.
  await page.getByRole("button", { name: "Pumps" }).click();
  await expect(page).toHaveURL(new RegExp(`/hmi/${project}/pumps$`));
  await expect(page.getByText("Pump list")).toBeVisible();
  await page.goBack();
  await expect(level).toHaveText("77.25");
  await page.goto(`/hmi/${project}/pumps`);
  await expect(page.getByText("Pump list")).toBeVisible();

  // A save reaches the open session without a reload.
  await put(page.request, csrf, `${config}/View/pumps`, view("pumps", "Pumps", [label("pumps-title", "Pump list, revised"), label("pumps-note", "Two pumps running")]));
  await expect(page.getByText("Pump list, revised")).toBeVisible();
  await expect(page.getByText("Two pumps running")).toBeVisible();
  await shot(page, "hmi-saved-pumps-after-save");

  // Missing page and missing project say so.
  await page.goto(`/hmi/${project}/valves`);
  await expect(page.getByRole("alert")).toContainText(`no page at "/valves"`);
  await page.goto(`/hmi/${stamp("nowhere")}/`);
  await expect(page.getByRole("alert")).toContainText("was not found");

  // Signed out: the sign-in form first, then the project.
  const anonymous = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  try {
    const outsider = await anonymous.newPage();
    await outsider.goto(`/hmi/${project}/`);
    await expect(outsider.getByRole("form", { name: "Sign in to the gateway" })).toBeVisible();
    await expect(outsider.getByText("Tank farm")).toHaveCount(0);
    await outsider.getByLabel("Username").fill("admin");
    await outsider.getByLabel("Password").fill(PASSWORD);
    await outsider.getByRole("button", { name: "Sign in" }).click();
    await expect(outsider.locator("[data-node-id='level'] .oip-label")).toHaveText("77.25");
  } finally {
    await anonymous.close();
  }
});
