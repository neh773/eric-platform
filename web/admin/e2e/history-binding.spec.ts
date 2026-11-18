import { expect, test, type APIRequestContext } from "@playwright/test";
import { PASSWORD, stamp } from "./gateway";

/**
 * HMP-124: a tag history binding fills a table with aggregate windows of a memory tag's stored history.
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

test("a tag history binding shows the last stored value", async ({ page }) => {
  const run = stamp("hb");
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
  for (const value of [11, 12, 13, 14]) {
    const res = await page.request.post("/api/v1/tags/write", { headers: { "X-CSRF-Token": csrf }, data: { writes: [{ path: `[default]${tagPath}`, value }] } });
    expect(res.ok(), await res.text()).toBeTruthy();
  }
  const stored = async () => {
    const res = await page.request.get(`/api/v1/history/data?tagPath=${encodeURIComponent(`[default]${tagPath}`)}&from=0&to=${Date.now() + 60_000}`);
    expect(res.ok(), await res.text()).toBeTruthy();
    return ((await res.json()) as { samples: Array<{ value: number }> }).samples.map((sample) => sample.value);
  };
  await expect.poll(stored, { timeout: 10_000 }).toEqual(expect.arrayContaining([11, 12, 13, 14]));

  await put(page.request, csrf, `/api/v1/config/designer/project/${project}/View/home`, {
    apiVersion: "oip/v1",
    kind: "View",
    metadata: { name: "home", module: "designer" },
    spec: {
      path: "home",
      title: "Home",
      document: {
        params: {},
        width: 600,
        height: 400,
        root: {
          id: "root",
          kind: "container",
          name: "Home",
          layout: "flex",
          props: { direction: "column" },
          children: [
            {
              id: "history",
              kind: "component",
              name: "history",
              type: "table",
              props: {
                data: [],
                columns: [{ field: "value", header: "Value" }],
                pageSize: 10,
              },
              flex: { grow: 0, shrink: 0, basis: "180px", display: true },
              bindings: {
                data: {
                  kind: "history",
                  path: `[default]${tagPath}`,
                  rangeMs: 3_600_000,
                  intervalMs: 3_600_000,
                  aggregate: "LastValue",
                },
              },
            },
          ],
        },
      },
    },
  });

  await page.goto(`/hmi/${project}/`);
  await expect(page.locator(".oip-hmi-host")).toHaveAttribute("data-hmi-source", "gateway");
  const table = page.locator(".oip-node[data-node-id='history']");
  await expect(table.getByRole("cell", { name: "14" })).toBeVisible({ timeout: 10_000 });
});
