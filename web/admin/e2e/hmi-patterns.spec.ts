import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * HMP-043, HMP-064, HMP-109, HMP-112, HMP-024 and HMP-085 on the real gateway: a session page with a moving analog
 * indicator on a tag, a form whose values come from an expression structure binding, a self-hiding navigation drawer,
 * a sandboxed inline frame, and a brand bar that appears when the session switches to a branded theme. The page runs
 * under the gateway's Content-Security-Policy and no violation is reported.
 */

const FRAME_HOST = "https://frames.test";
const BRAND_HOST = "https://cdn.acme.test";

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

const FIELDS = [
  { name: "name", label: "Name", type: "text", required: true },
  { name: "quantity", label: "Quantity", type: "number", min: 1, max: 100 },
  { name: "agree", label: "Agree", type: "checkbox", required: true },
];

const FRAMED_PAGE =
  "<!doctype html><title>framed</title><p>framed ok</p><script>" +
  "try { void window.parent.document.title; document.body.dataset.access = 'yes'; } catch (error) { document.body.dataset.access = 'blocked'; }" +
  "</script>";

test("analog indicator, form, navigation drawer, inline frame and brand run in a session", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("patterns");
  const project = `${run}-plant`;
  const folder = run.replace(/-/g, "_");
  const levelPath = `[default]${folder}/level`;
  const csrf = await signIn(page.request);
  const tagName = `${run}-level`;
  const setLevel = (value: number) =>
    put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${tagName}`, {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name: tagName, module: "tags" },
      spec: { path: `${folder}/level`, type: "memory", dataType: "Double", value },
    });
  await setLevel(95);
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/StyleClasses/style-classes`, {
    apiVersion: "oip/v1",
    kind: "StyleClasses",
    metadata: { name: "style-classes", module: "designer" },
    spec: {
      classes: {},
      themes: {
        acme: {
          extends: "daylight",
          tokens: { "color-accent": "#aa3300" },
          brand: { name: "Acme Water", logo: `${BRAND_HOST}/logo.svg`, logoAlt: "Acme logo" },
        },
      },
    },
  });
  const component = (id: string, type: string, frame: Record<string, number>, props: Record<string, unknown>, bindings?: Record<string, unknown>) => ({
    id,
    kind: "component",
    name: id,
    type,
    frame,
    props,
    ...(bindings ? { bindings } : {}),
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
        width: 900,
        height: 640,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "coordinate",
          props: {},
          children: [
            component(
              "level",
              "analog-indicator",
              { x: 0, y: 0, width: 260, height: 56 },
              { label: "Reactor level", unit: "bar", min: 0, max: 100, low: 20, high: 80, highHigh: 90, setpoint: 60 },
              { value: { kind: "tag", mode: "direct", path: levelPath } },
            ),
            component(
              "order",
              "form",
              { x: 0, y: 80, width: 300, height: 260 },
              // The view stores submitCount, as a saved designer view does; a property binding reads stored props only.
              { label: "Order form", fields: FIELDS, submitText: "Place order", submitCount: 0 },
              { values: { kind: "expression-structure", expressions: { name: "'Tank ' + 'A'", quantity: "5" } } },
            ),
            component("count", "label", { x: 320, y: 80, width: 60, height: 30 }, { text: "0" }, {
              text: { kind: "property", source: { scope: "prop", nodeId: "order", prop: "submitCount" } },
            }),
            component("menu", "navigation-drawer", { x: 320, y: 130, width: 40, height: 40 }, { label: "Plant navigation", items: "Home = /home, Docs = https://docs.acme.test/" }),
            component("vendor", "inline-frame", { x: 400, y: 0, width: 400, height: 200 }, { source: `${FRAME_HOST}/page.html`, title: "Vendor page" }),
            component("theme", "button", { x: 400, y: 220, width: 160, height: 40 }, { text: "Acme brand", writeValue: "acme" }, {
              value: { kind: "property", bidirectional: true, source: { scope: "session", path: "theme" } },
            }),
          ],
        },
      },
    },
  });

  const context = await browser.newContext({ baseURL });
  try {
    await signIn(context.request);
    const session = await context.newPage();
    await session.context().route(`${FRAME_HOST}/**`, (route) => route.fulfill({ status: 200, contentType: "text/html", body: FRAMED_PAGE }));
    await session.context().route(`${BRAND_HOST}/**`, (route) =>
      route.fulfill({ status: 200, contentType: "image/svg+xml", body: '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><rect width="40" height="40" fill="#aa3300"/></svg>' }),
    );
    const violations: string[] = [];
    await session.exposeFunction("__oipCspViolation", (text: string) => violations.push(text));
    await session.addInitScript(() => {
      document.addEventListener("securitypolicyviolation", (event) => {
        (window as unknown as { __oipCspViolation: (text: string) => void }).__oipCspViolation(`${event.violatedDirective} ${event.blockedURI}`);
      });
    });
    await session.goto(`/hmi/${project}/`);
    await expect(session.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");

    // Moving analog indicator: the tag moves it into the high-high band, and back to normal.
    const meter = session.getByRole("meter", { name: "Reactor level" });
    await expect(meter).toHaveAttribute("data-state", "high-high");
    await expect(meter).toHaveAttribute("aria-valuetext", "95.0 bar, high-high");
    await setLevel(50);
    await expect(meter).toHaveAttribute("data-state", "normal");
    await expect(meter.locator(".oip-analog-setpoint")).toHaveAttribute("data-setpoint", "60");

    // Expression structure binding fills the form; an invalid submit changes nothing, a valid one counts.
    const form = session.getByRole("form", { name: "Order form" });
    await expect(form.getByRole("textbox", { name: "Name", exact: false })).toHaveValue("Tank A");
    await expect(form.getByRole("spinbutton", { name: "Quantity", exact: false })).toHaveValue("5");
    await form.getByRole("textbox", { name: "Name", exact: false }).fill("");
    await form.getByRole("button", { name: "Place order", exact: true }).click();
    await expect(form.getByRole("alert").filter({ hasText: "Name is required" })).toBeVisible();
    await expect(form.getByRole("alert").filter({ hasText: "Agree must be checked" })).toBeVisible();
    await expect(session.locator("[data-node-id='count']")).toHaveText("0");
    await form.getByRole("textbox", { name: "Name", exact: false }).fill("Pump 7");
    await form.getByRole("checkbox", { name: "Agree", exact: true }).check();
    await form.getByRole("button", { name: "Place order", exact: true }).click();
    await expect(form.getByRole("alert")).toHaveCount(0);
    await expect(session.locator("[data-node-id='count']")).toHaveText("1");

    // Navigation drawer: opens over the page, hides after a choice and on Escape.
    const open = session.getByRole("button", { name: "Open navigation", exact: true });
    await open.click();
    const drawer = session.getByRole("navigation", { name: "Plant navigation" });
    await expect(drawer).toBeVisible();
    await session.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);
    await open.click();
    await drawer.getByRole("link", { name: "Home", exact: true }).click();
    await expect(drawer).toHaveCount(0);

    // Inline frame: sandboxed, no same-origin access to the session, loaded under the page's policy.
    const frame = session.locator("iframe[title='Vendor page']");
    await expect(frame).toHaveAttribute("sandbox", "allow-scripts allow-forms");
    await expect(frame).toHaveAttribute("src", `${FRAME_HOST}/page.html`);
    const inner = session.frameLocator("iframe[title='Vendor page']");
    await expect(inner.getByText("framed ok")).toBeVisible();
    await expect(inner.locator("body")).toHaveAttribute("data-access", "blocked");

    // Brand: no bar on the default theme; the branded theme brings its logo, name and colors.
    await expect(session.locator(".oip-brand")).toHaveCount(0);
    await session.getByRole("button", { name: "Acme brand", exact: true }).click();
    await expect(session.locator(".oip-session")).toHaveAttribute("data-theme", "acme");
    await expect(session.getByRole("img", { name: "Acme logo", exact: true })).toBeVisible();
    await expect(session.locator(".oip-brand-name")).toHaveText("Acme Water");
    expect(await session.locator(".oip-session").evaluate((node) => (node as HTMLElement).style.getPropertyValue("--oip-color-accent"))).toBe("#aa3300");
    expect(violations).toEqual([]);
  } finally {
    await context.close();
  }
});
