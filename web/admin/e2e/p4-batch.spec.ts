import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * Expression transform, a project theme, tag browse and the power-chart tag browse on a running gateway.
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
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test("a session shows an expression transform, a project theme, and browsable tags", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("p4");
  const project = `${run}-plant`;
  const folder = run.replace(/-/g, "_");
  const tagPath = `[default]${folder}/Level`;
  const csrf = await signIn(page.request);
  const tagName = `${run}-level`;
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${tagName}`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: tagName, module: "tags" },
    spec: { path: `${folder}/Level`, type: "memory", dataType: "Double", value: 21 },
  });
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/StyleClasses/style-classes`, {
    apiVersion: "oip/v1",
    kind: "StyleClasses",
    metadata: { name: "style-classes", module: "designer" },
    spec: {
      classes: { Warm: { style: { color: "#112233" }, states: { hover: { color: "#aa0000" } } } },
      themes: { plant: { extends: "daylight", tokens: { "color-alarm": "#cc0000" } } },
    },
  });
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: {
        params: {},
        width: 480,
        height: 360,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "flex",
          props: { direction: "column" },
          children: [
            {
              id: "level",
              kind: "component",
              name: "Level",
              type: "numeric",
              props: { decimals: 0, label: "Doubled" },
              styleClasses: ["Warm"],
              bindings: {
                value: { kind: "tag", mode: "direct", path: tagPath, transforms: [{ kind: "expression", expression: "{value} * 2" }] },
              },
            },
            {
              id: "theme",
              kind: "component",
              name: "Theme",
              type: "button",
              props: { text: "Plant theme", writeValue: "plant" },
              bindings: { value: { kind: "property", bidirectional: true, source: { scope: "session", path: "theme" } } },
            },
            {
              id: "browse",
              kind: "component",
              name: "Browse",
              type: "tag-browse",
              props: { label: "Plant tags", root: `[default]${folder}` },
            },
            {
              id: "plot",
              kind: "component",
              name: "Plot",
              type: "xy-chart",
              props: { title: "Curve", series: "A:0,1;1,2" },
            },
            {
              id: "dial",
              kind: "component",
              name: "Dial",
              type: "radial-gauge",
              props: { label: "Load", value: 40, showSecond: false, ranges: "0:100:accent" },
            },
          ],
        },
      },
    },
  });

  const context = await browser.newContext({ baseURL });
  try {
    await signIn(context.request);
    const session = await context.newPage();
    await session.goto(`/hmi/${project}/`);
    await expect(session.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
    await expect(session.getByText("42")).toBeVisible();
    const states = await session.locator("style[data-style-states]").evaluate((node) => node.textContent ?? "");
    expect(states).toContain(".oip-sc-Warm:hover");
    await expect(session.getByRole("button", { name: "Level" })).toBeVisible();
    await expect(session.getByRole("figure", { name: "Curve" })).toBeVisible();
    await expect(session.getByRole("figure", { name: "Load" })).toBeVisible();
    await session.getByRole("button", { name: "Plant theme" }).click();
    await expect(session.locator(".oip-session")).toHaveAttribute("data-theme", "plant");
    const alarm = await session.locator(".oip-session").evaluate((node) => getComputedStyle(node).getPropertyValue("--oip-color-alarm").trim());
    expect(alarm).toBe("#cc0000");
  } finally {
    await context.close();
  }
});
