import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * On the real gateway:
 *  - P4-13 (HMP-001): a time series chart history pen plots the stored history of a memory tag, read from
 *    `/api/v1/history/data`.
 *  - P4-12 (HMP-118): an expression binding with a poll interval re-evaluates in a `/hmi` session with no tag change.
 */

async function signIn(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/v1/auth/login", { data: { username: "admin", password: PASSWORD } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { csrfToken: string }).csrfToken;
}

async function put(request: APIRequestContext, csrf: string, url: string, resource: Record<string, unknown>) {
  const current = await request.get(url);
  const version = current.ok() ? ((await current.json()) as { metadata: { version: number } }).metadata.version : 0;
  const res = await request.put(url, { headers: { "X-CSRF-Token": csrf }, data: { ...resource, metadata: { ...(resource.metadata as object), version } } });
  expect(res.ok(), await res.text()).toBeTruthy();
}

function view(children: unknown[]) {
  return {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: { params: {}, width: 600, height: 400, root: { id: "root", kind: "container", name: "Home", layout: "flex", props: { direction: "column" }, children } },
    },
  };
}

test("a time series chart history pen plots the stored history of a memory tag", async ({ page }) => {
  const run = stamp("hp");
  const project = `${run}-plant`;
  const csrf = await signIn(page.request);
  const tagPath = `${run}/level`;
  await put(page.request, csrf, `/api/v1/config/tags/gateway/Tag/${run}-level`, {
    apiVersion: "oip/v1",
    kind: "Tag",
    metadata: { name: `${run}-level`, module: "tags" },
    spec: { path: tagPath, type: "memory", dataType: "Double", value: 10 },
  });
  await put(page.request, csrf, `/api/v1/config/historian/gateway/HistorySettings/${run}-level`, {
    apiVersion: "oip/v1",
    kind: "HistorySettings",
    metadata: { name: `${run}-level`, module: "historian" },
    spec: { tagPath, sampleMode: "onChange" },
  });
  const values = [11, 12, 13, 14];
  for (const value of values) {
    const res = await page.request.post("/api/v1/tags/write", { headers: { "X-CSRF-Token": csrf }, data: { writes: [{ path: `[default]${tagPath}`, value }] } });
    expect(res.ok(), await res.text()).toBeTruthy();
  }
  // The history the pen will read.
  const stored = async () => {
    const res = await page.request.get(`/api/v1/history/data?tagPath=${encodeURIComponent(`[default]${tagPath}`)}&from=0&to=${Date.now() + 60_000}`);
    expect(res.ok(), await res.text()).toBeTruthy();
    return ((await res.json()) as { samples: Array<{ value: number }> }).samples.map((sample) => sample.value);
  };
  await expect.poll(stored, { timeout: 10_000 }).toEqual(expect.arrayContaining(values));
  const count = (await stored()).length;

  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, view([
    {
      id: "chart",
      kind: "component",
      name: "chart",
      type: "time-series-chart",
      props: { title: "Level history", historyPens: `[default]${tagPath}`, rangeSeconds: 3600, autoScale: true },
      flex: { grow: 0, shrink: 0, basis: "260px", display: true },
    },
  ]));

  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  const chart = page.locator(".oip-node[data-node-id='chart'] .oip-time-series");
  await expect(chart).toHaveAttribute("data-points", String(count), { timeout: 10_000 });
  await expect(chart.locator("[data-series='level'] path").first()).toBeVisible();
  await expect(chart.getByRole("img", { name: "Level history: level 14.0" })).toBeVisible();
  await expect(chart.getByRole("alert")).toHaveCount(0);
});

test("an expression binding with a poll interval re-evaluates in a session", async ({ page }) => {
  const run = stamp("ep");
  const project = `${run}-plant`;
  const csrf = await signIn(page.request);
  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, view([
    {
      id: "clock",
      kind: "component",
      name: "clock",
      type: "label",
      props: { text: "" },
      flex: { grow: 0, shrink: 0, basis: "40px", display: true },
      bindings: { text: { kind: "expression", expression: "'t' + now()", pollMs: 200 } },
    },
  ]));
  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  const clock = page.locator(".oip-node[data-node-id='clock']");
  await expect(clock).toContainText(/t\d{13}/);
  const read = async (locator: typeof clock) => Number(/t(\d{13})/.exec((await locator.textContent()) ?? "")?.[1]);
  const first = await read(clock);
  // Several poll steps later the label has moved on, with no tag or property change behind it.
  await expect.poll(() => read(clock), { timeout: 5_000 }).toBeGreaterThanOrEqual(first + 600);
});
