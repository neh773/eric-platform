import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * P4-03 (HMP-055) on the real gateway: an operator types a setpoint into a numeric entry field whose `value` is
 * bound bidirectionally to a memory tag. A valid entry writes the tag through the session socket; an out-of-range or
 * non-numeric entry is refused in the field and the tag keeps its value.
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

async function tagValue(request: APIRequestContext, csrf: string, path: string): Promise<unknown> {
  const res = await request.post("/api/v1/tags/read", { headers: { "X-CSRF-Token": csrf }, data: { paths: [path] } });
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = (await res.json()) as { results?: Array<{ value?: unknown }>; values?: Array<{ value?: unknown }> };
  return (body.results ?? body.values ?? [])[0]?.value;
}

test("an operator writes a setpoint from a numeric entry field, and a bounded entry is refused", async ({ page, browser }) => {
  const baseURL = test.info().project.use.baseURL!;
  const run = stamp("ne");
  const project = `${run}-plant`;
  const folder = `${run}`;
  const tagPath = `[default]${folder}/setpoint`;
  const csrf = await signIn(page.request);
  const name = `${run}-setpoint`;
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${name}`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name, module: "tags" },
    spec: { path: `${folder}/setpoint`, type: "memory", dataType: "Double", value: 20 },
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
        width: 360,
        height: 200,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "flex",
          props: { direction: "column", alignItems: "flex-start" },
          children: [
            {
              id: "setpoint",
              kind: "component",
              name: "Setpoint",
              type: "numeric-entry",
              props: { label: "Tank setpoint", inputType: "decimal", decimals: 1, suffix: " °C", limitMin: true, minimum: 0, limitMax: true, maximum: 80, enabled: true },
              bindings: { value: { kind: "tag", mode: "direct", path: tagPath, bidirectional: true } },
            },
          ],
        },
      },
    },
  });
  const operator = `${run}-op`;
  const created = await page.request.post("/api/v1/security/users", {
    data: { username: operator, password: PASSWORD, roles: ["operator"] },
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf },
  });
  expect(created.ok(), await created.text()).toBeTruthy();

  const context = await browser.newContext({ baseURL });
  try {
    await signIn(context.request, operator);
    const session = await context.newPage();
    await session.goto(`/hmi/${project}/`);
    await expect(session.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
    const field = session.getByRole("textbox", { name: "Tank setpoint" });
    await expect(field).toHaveValue("20.0 °C");

    // A valid entry is written to the tag on Enter.
    await field.click();
    await expect(field).toHaveValue("20");
    await field.fill("42.5");
    await field.press("Enter");
    await expect.poll(() => tagValue(page.request, csrf, tagPath)).toBe(42.5);
    await field.blur();
    await expect(field).toHaveValue("42.5 °C");

    // Out of range: refused and shown; the tag keeps 42.5.
    await field.click();
    await field.fill("95");
    await field.press("Enter");
    await expect(session.getByRole("alert")).toHaveText("Enter 80 or less.");
    await expect(field).toHaveAttribute("aria-invalid", "true");
    // Not numeric: refused too.
    await field.fill("hot");
    await field.press("Enter");
    await expect(session.getByRole("alert")).toHaveText("Enter a number.");
    await field.blur();
    await expect(field).toHaveValue("hot");
    expect(await tagValue(page.request, csrf, tagPath)).toBe(42.5);

    // Escape gives the current value back, and the next valid entry writes again.
    await field.click();
    await field.press("Escape");
    await expect(session.getByRole("alert")).toHaveCount(0);
    await expect(field).toHaveValue("42.5 °C");
    await field.click();
    await field.fill("0");
    await field.press("Tab");
    await expect.poll(() => tagValue(page.request, csrf, tagPath)).toBe(0);

    // A tag change reaches the field.
    await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${name}`, {
      apiVersion: "oip/v1",
      kind: "Tag",
      metadata: { name, module: "tags" },
      spec: { path: `${folder}/setpoint`, type: "memory", dataType: "Double", value: 33 },
    });
    await expect(field).toHaveValue("33.0 °C");
  } finally {
    await context.close();
  }
});
